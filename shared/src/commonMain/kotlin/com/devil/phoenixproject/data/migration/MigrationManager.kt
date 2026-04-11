package com.devil.phoenixproject.data.migration

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.local.ReconciliationStatus
import com.devil.phoenixproject.data.local.SchemaIndexOperation
import com.devil.phoenixproject.data.local.applyIndexCreate
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.SqlDelightPersonalRecordRepository
import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.normalizeWorkoutModeKey
import com.devil.phoenixproject.database.Routine
import com.devil.phoenixproject.database.RoutineExercise
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.database.WorkoutSession
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.premium.RpgAttributeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages data migrations on app startup.
 * Call [checkAndRunMigrations] after Koin is initialized.
 * Call [close] when done to prevent memory leaks.
 */
class MigrationManager(
    private val database: VitruvianDatabase,
    private val userProfileRepository: UserProfileRepository? = null,
    private val gamificationRepository: GamificationRepository? = null,
    private val driver: SqlDriver? = null,
) {
    private val log = Logger.withTag("MigrationManager")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queries get() = database.vitruvianDatabaseQueries
    private val migrationMutex = Mutex()

    private val _profileScopeRepairState = MutableStateFlow<ProfileScopeRepairState>(ProfileScopeRepairState.Idle)
    val profileScopeRepairState: StateFlow<ProfileScopeRepairState> = _profileScopeRepairState.asStateFlow()

    private var pendingProfileScopeRepair: PendingProfileScopeRepair? = null

    // Issue #319: Track orphaned data repair state for PRs with deleted profile IDs
    private val _orphanedDataRepairState = MutableStateFlow<OrphanedDataRepairState>(OrphanedDataRepairState.Idle)
    val orphanedDataRepairState: StateFlow<OrphanedDataRepairState> = _orphanedDataRepairState.asStateFlow()

    private data class RoutineNameResolutionContext(
        val routineNameById: Map<String, String>,
        val routineIdByExerciseId: Map<String, String>,
        val uniqueRoutineNameByExerciseId: Map<String, String>,
        val uniqueRoutineNameByExerciseName: Map<String, String>,
    )

    private data class ProfileScopedCounts(
        val sessions: Long,
        val personalRecords: Long,
        val routines: Long,
        val cycles: Long,
        val assessments: Long,
        val progressions: Long,
        val badges: Long,
        val streaks: Long,
        val gamificationStats: Long,
        val rpgProfiles: Long,
    ) {
        val totalRows: Long = sessions +
            personalRecords +
            routines +
            cycles +
            assessments +
            progressions +
            badges +
            streaks +
            gamificationStats +
            rpgProfiles
        val hasAnyData: Boolean get() = totalRows > 0
    }

    private data class PendingProfileScopeRepair(
        val fromProfileId: String,
        val toProfileId: String,
        val toProfileName: String,
        val fromCounts: ProfileScopedCounts,
        val toCounts: ProfileScopedCounts,
    )

    private data class CanonicalPersonalRecord(
        val exerciseId: String,
        val exerciseName: String,
        val weight: Double,
        val reps: Long,
        val oneRepMax: Double,
        val achievedAt: Long,
        val workoutMode: String,
        val prType: String,
        val volume: Double,
        val phase: String,
    )

    private data class CanonicalEarnedBadge(
        val badgeId: String,
        val earnedAt: Long,
        val celebratedAt: Long?,
    )

    /**
     * Check for and run any pending migrations.
     * This should be called once on app startup.
     */
    fun checkAndRunMigrations() {
        scope.launch {
            runMigrationsNow()
        }
    }

    suspend fun runMigrationsNow() {
        migrationMutex.withLock {
            _profileScopeRepairState.value = ProfileScopeRepairState.Applying("Running startup data repair")
            pendingProfileScopeRepair = null
            try {
                runMigrations()
                if (_profileScopeRepairState.value !is ProfileScopeRepairState.NeedsChoice) {
                    _profileScopeRepairState.value = ProfileScopeRepairState.Completed("Startup data repair complete")
                }
            } catch (e: Exception) {
                log.e(e) { "Migration failed" }
                _profileScopeRepairState.value = ProfileScopeRepairState.Failed(
                    e.message ?: "Startup data repair failed",
                )
            }
        }
    }

    suspend fun moveDefaultDataToActiveProfile() {
        migrationMutex.withLock {
            val context = pendingProfileScopeRepair
                ?: return@withLock
            _profileScopeRepairState.value = ProfileScopeRepairState.Applying(
                "Moving legacy data into ${context.toProfileName}",
            )
            try {
                applyProfileScopeMove(context)
                pendingProfileScopeRepair = null
                _profileScopeRepairState.value = ProfileScopeRepairState.Completed(
                    "Moved legacy data into ${context.toProfileName}",
                )
            } catch (e: Exception) {
                log.e(e) { "Profile-scope repair failed during manual move" }
                _profileScopeRepairState.value = ProfileScopeRepairState.Failed(
                    e.message ?: "Profile-scope repair failed",
                )
            }
        }
    }

    suspend fun switchToDefaultProfileWithoutMovingData() {
        migrationMutex.withLock {
            _profileScopeRepairState.value = ProfileScopeRepairState.Applying(
                "Switching back to Default profile",
            )
            try {
                setActiveProfileInternal("default")
                pendingProfileScopeRepair = null
                _profileScopeRepairState.value = ProfileScopeRepairState.Completed(
                    "Using Default profile without moving data",
                )
            } catch (e: Exception) {
                log.e(e) { "Profile-scope repair failed while switching to default profile" }
                _profileScopeRepairState.value = ProfileScopeRepairState.Failed(
                    e.message ?: "Failed to switch to Default profile",
                )
            }
        }
    }

    private suspend fun runMigrations() {
        refreshProfilesIfAvailable()
        cleanupFabricatedRoutineSessionIds()
        normalizeLegacyWorkoutModes()
        backfillLegacyWorkoutRoutineNames()
        repairPersonalRecordsFromWorkoutHistory()
        auditAndRepairProfileScopedData()
        // Issue #319: Check for orphaned PR records after all other migrations
        checkAndRepairOrphanedData()
    }

    private suspend fun auditAndRepairProfileScopedData() {
        val activeProfile = resolveActiveProfile() ?: run {
            log.w { "Profile-scope audit skipped: no active profile found" }
            return
        }

        if (activeProfile.id == "default") {
            log.d { "Profile-scope audit: active profile is default, no repair needed" }
            return
        }

        val auditDriver = driver ?: run {
            log.w { "Profile-scope audit skipped for non-default active profile because SqlDriver was not provided" }
            return
        }

        val defaultCounts = loadProfileScopedCounts(auditDriver, "default")
        val activeCounts = loadProfileScopedCounts(auditDriver, activeProfile.id)

        log.i {
            "Profile-scope audit: active=${activeProfile.id} defaultRows=${defaultCounts.totalRows} activeRows=${activeCounts.totalRows}"
        }

        when {
            !defaultCounts.hasAnyData -> {
                log.d { "Profile-scope audit: no legacy default-scoped data found" }
            }

            !activeCounts.hasAnyData -> {
                val context = PendingProfileScopeRepair(
                    fromProfileId = "default",
                    toProfileId = activeProfile.id,
                    toProfileName = activeProfile.name,
                    fromCounts = defaultCounts,
                    toCounts = activeCounts,
                )
                _profileScopeRepairState.value = ProfileScopeRepairState.Applying(
                    "Moving legacy data into ${activeProfile.name}",
                )
                applyProfileScopeMove(context)
            }

            else -> {
                pendingProfileScopeRepair = PendingProfileScopeRepair(
                    fromProfileId = "default",
                    toProfileId = activeProfile.id,
                    toProfileName = activeProfile.name,
                    fromCounts = defaultCounts,
                    toCounts = activeCounts,
                )
                _profileScopeRepairState.value = ProfileScopeRepairState.NeedsChoice(
                    activeProfileId = activeProfile.id,
                    activeProfileName = activeProfile.name,
                    defaultRowCount = defaultCounts.totalRows,
                    activeRowCount = activeCounts.totalRows,
                )
            }
        }
    }

    private suspend fun applyProfileScopeMove(context: PendingProfileScopeRepair) {
        val moveDriver = driver
            ?: error("Profile-scope repair requires a SqlDriver")

        database.transaction {
            mergePersonalRecords(context.fromProfileId, context.toProfileId, moveDriver)
            mergeEarnedBadges(context.fromProfileId, context.toProfileId, moveDriver)
            moveProfileScopedRows(moveDriver, "WorkoutSession", context.fromProfileId, context.toProfileId)
            moveProfileScopedRows(moveDriver, "Routine", context.fromProfileId, context.toProfileId)
            moveProfileScopedRows(moveDriver, "TrainingCycle", context.fromProfileId, context.toProfileId)
            moveProfileScopedRows(moveDriver, "AssessmentResult", context.fromProfileId, context.toProfileId)
            moveProfileScopedRows(moveDriver, "ProgressionEvent", context.fromProfileId, context.toProfileId)
            moveProfileScopedRows(moveDriver, "StreakHistory", context.fromProfileId, context.toProfileId)
            deleteProfileScopedRows(moveDriver, "GamificationStats", context.fromProfileId)
            deleteProfileScopedRows(moveDriver, "GamificationStats", context.toProfileId)
            deleteProfileScopedRows(moveDriver, "RpgAttributes", context.fromProfileId)
            deleteProfileScopedRows(moveDriver, "RpgAttributes", context.toProfileId)
        }

        validateAndRepairPrUniqueIndex(moveDriver)
        recomputeDerivedGamification(context.toProfileId)
        refreshProfilesIfAvailable()
    }

    private suspend fun recomputeDerivedGamification(profileId: String) {
        val repo = gamificationRepository ?: return
        repo.updateStats(profileId)
        val rpgInput = repo.getRpgInput(profileId)
        repo.saveRpgProfile(RpgAttributeEngine.computeProfile(rpgInput), profileId)
    }

    private suspend fun refreshProfilesIfAvailable() {
        userProfileRepository?.refreshProfiles()
    }

    private suspend fun setActiveProfileInternal(profileId: String) {
        val repo = userProfileRepository
        if (repo != null) {
            repo.setActiveProfile(profileId)
            repo.refreshProfiles()
        } else {
            queries.setActiveProfile(profileId)
        }
    }

    private suspend fun resolveActiveProfile(): UserProfile? {
        userProfileRepository?.refreshProfiles()
        userProfileRepository?.activeProfile?.value?.let { return it }

        val active = queries.getActiveProfile().executeAsOneOrNull() ?: return null
        return UserProfile(
            id = active.id,
            name = active.name,
            colorIndex = active.colorIndex.toInt(),
            createdAt = active.createdAt,
            isActive = active.isActive == 1L,
            supabaseUserId = active.supabase_user_id,
            subscriptionStatus = com.devil.phoenixproject.data.repository.SubscriptionStatus.fromString(
                active.subscription_status,
            ),
            subscriptionExpiresAt = active.subscription_expires_at,
            lastAuthAt = active.last_auth_at,
        )
    }

    private fun loadProfileScopedCounts(auditDriver: SqlDriver, profileId: String): ProfileScopedCounts = ProfileScopedCounts(
        sessions = countProfileScopedRows(auditDriver, "WorkoutSession", profileId),
        personalRecords = countProfileScopedRows(auditDriver, "PersonalRecord", profileId),
        routines = countProfileScopedRows(auditDriver, "Routine", profileId),
        cycles = countProfileScopedRows(auditDriver, "TrainingCycle", profileId),
        assessments = countProfileScopedRows(auditDriver, "AssessmentResult", profileId),
        progressions = countProfileScopedRows(auditDriver, "ProgressionEvent", profileId),
        badges = countProfileScopedRows(auditDriver, "EarnedBadge", profileId),
        streaks = countProfileScopedRows(auditDriver, "StreakHistory", profileId),
        gamificationStats = countProfileScopedRows(auditDriver, "GamificationStats", profileId),
        rpgProfiles = countProfileScopedRows(auditDriver, "RpgAttributes", profileId),
    )

    private fun countProfileScopedRows(auditDriver: SqlDriver, tableName: String, profileId: String): Long {
        var count = 0L
        auditDriver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM $tableName WHERE profile_id = ?",
            mapper = { cursor ->
                if (cursor.next().value) {
                    count = cursor.getLong(0) ?: 0L
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) {
            bindString(0, profileId)
        }
        return count
    }

    private fun moveProfileScopedRows(auditDriver: SqlDriver, tableName: String, fromProfileId: String, toProfileId: String) {
        auditDriver.execute(
            identifier = null,
            sql = "UPDATE $tableName SET profile_id = ? WHERE profile_id = ?",
            parameters = 2,
        ) {
            bindString(0, toProfileId)
            bindString(1, fromProfileId)
        }
    }

    private fun deleteProfileScopedRows(auditDriver: SqlDriver, tableName: String, profileId: String) {
        auditDriver.execute(
            identifier = null,
            sql = "DELETE FROM $tableName WHERE profile_id = ?",
            parameters = 1,
        ) {
            bindString(0, profileId)
        }
    }

    private fun mergePersonalRecords(fromProfileId: String, toProfileId: String, auditDriver: SqlDriver) {
        val canonicalByKey = linkedMapOf<String, CanonicalPersonalRecord>()
        val allRecords = queries.selectAllRecords(profileId = fromProfileId).executeAsList() +
            queries.selectAllRecords(profileId = toProfileId).executeAsList()

        allRecords.forEach { record ->
            val canonical = CanonicalPersonalRecord(
                exerciseId = record.exerciseId,
                exerciseName = record.exerciseName,
                weight = record.weight,
                reps = record.reps,
                oneRepMax = record.oneRepMax,
                achievedAt = record.achievedAt,
                workoutMode = normalizeWorkoutModeKey(record.workoutMode),
                prType = record.prType,
                volume = record.volume,
                phase = record.phase,
            )
            val key = "${canonical.exerciseId}|${canonical.workoutMode}|${canonical.prType}|${canonical.phase}"
            val existing = canonicalByKey[key]
            canonicalByKey[key] = when {
                existing == null -> canonical
                shouldReplacePersonalRecord(canonical, existing) -> canonical.copy(
                    exerciseName = canonical.exerciseName.ifBlank { existing.exerciseName },
                )

                else -> existing.copy(
                    exerciseName = existing.exerciseName.ifBlank { canonical.exerciseName },
                )
            }
        }

        deleteProfileScopedRows(auditDriver, "PersonalRecord", fromProfileId)
        deleteProfileScopedRows(auditDriver, "PersonalRecord", toProfileId)

        canonicalByKey.values.forEach { record ->
            queries.upsertPR(
                exerciseId = record.exerciseId,
                exerciseName = record.exerciseName,
                weight = record.weight,
                reps = record.reps,
                oneRepMax = record.oneRepMax,
                achievedAt = record.achievedAt,
                workoutMode = record.workoutMode,
                prType = record.prType,
                volume = record.volume,
                phase = record.phase,
                profile_id = toProfileId,
            )
        }
    }

    private fun mergeEarnedBadges(fromProfileId: String, toProfileId: String, auditDriver: SqlDriver) {
        val merged = linkedMapOf<String, CanonicalEarnedBadge>()
        val allBadges = queries.selectAllEarnedBadges(profileId = fromProfileId).executeAsList() +
            queries.selectAllEarnedBadges(profileId = toProfileId).executeAsList()

        allBadges.forEach { badge ->
            val existing = merged[badge.badgeId]
            val canonical = CanonicalEarnedBadge(
                badgeId = badge.badgeId,
                earnedAt = badge.earnedAt,
                celebratedAt = badge.celebratedAt,
            )
            merged[badge.badgeId] = when {
                existing == null -> canonical
                canonical.earnedAt < existing.earnedAt -> canonical.copy(
                    celebratedAt = canonical.celebratedAt ?: existing.celebratedAt,
                )

                else -> existing.copy(
                    celebratedAt = existing.celebratedAt ?: canonical.celebratedAt,
                )
            }
        }

        deleteProfileScopedRows(auditDriver, "EarnedBadge", fromProfileId)
        deleteProfileScopedRows(auditDriver, "EarnedBadge", toProfileId)

        merged.values.forEach { badge ->
            auditDriver.execute(
                identifier = null,
                sql = "INSERT INTO EarnedBadge (badgeId, earnedAt, celebratedAt, profile_id) VALUES (?, ?, ?, ?)",
                parameters = 4,
            ) {
                bindString(0, badge.badgeId)
                bindLong(1, badge.earnedAt)
                bindLong(2, badge.celebratedAt)
                bindString(3, toProfileId)
            }
        }
    }

    private fun validateAndRepairPrUniqueIndex(auditDriver: SqlDriver) {
        val duplicateKeys = mutableListOf<String>()
        auditDriver.executeQuery(
            identifier = null,
            sql = """
                SELECT exerciseId || '|' || workoutMode || '|' || prType || '|' || phase || '|' || profile_id
                FROM PersonalRecord
                GROUP BY exerciseId, workoutMode, prType, phase, profile_id
                HAVING COUNT(*) > 1
            """.trimIndent(),
            mapper = { cursor ->
                while (cursor.next().value) {
                    duplicateKeys += cursor.getString(0).orEmpty()
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )

        check(duplicateKeys.isEmpty()) {
            "PersonalRecord still has duplicate composite keys after repair: ${duplicateKeys.take(5)}"
        }

        val createResult = applyIndexCreate(
            auditDriver,
            SchemaIndexOperation(
                name = "idx_pr_unique",
                createSql = "CREATE UNIQUE INDEX IF NOT EXISTS idx_pr_unique ON PersonalRecord(exerciseId, workoutMode, prType, phase, profile_id)",
                preDropSql = "DROP INDEX IF EXISTS idx_pr_unique",
            ),
        )

        check(createResult.status != ReconciliationStatus.FAILED) {
            "Failed to recreate idx_pr_unique: ${createResult.detail ?: "unknown error"}"
        }
    }

    /**
     * Remove fabricated `legacy_session_*` routineSessionIds that were incorrectly
     * generated by an earlier version of the export/import code. These synthetic IDs
     * break history grouping by making every session appear as a separate routine execution.
     */
    private fun cleanupFabricatedRoutineSessionIds() {
        val sessions = runCatching { queries.selectAllSessionsSync().executeAsList() }
            .getOrElse { error ->
                log.e(error) { "Failed to load workout sessions for routineSessionId cleanup" }
                return
            }

        var cleaned = 0
        database.transaction {
            sessions.forEach { session ->
                val rawId = session.routineSessionId ?: return@forEach
                // Only strip IDs that exactly match the known fabrication pattern
                if (rawId.equals("legacy_session_${session.id}", ignoreCase = true)) {
                    queries.updateSessionRoutineSessionId(
                        routineSessionId = null,
                        id = session.id,
                    )
                    cleaned++
                }
            }
        }

        if (cleaned > 0) {
            log.i { "Cleaned $cleaned fabricated legacy_session_* routineSessionIds" }
        }
    }

    private fun backfillLegacyWorkoutRoutineNames() {
        val sessions = runCatching { queries.selectAllSessionsSync().executeAsList() }
            .getOrElse { error ->
                log.e(error) { "Failed to load workout sessions for legacy routine-name backfill" }
                return
            }
        if (sessions.isEmpty()) return

        val routines = runCatching { queries.selectAllRoutinesSync().executeAsList() }
            .getOrElse { error ->
                log.e(error) { "Failed to load routines for legacy routine-name backfill" }
                emptyList()
            }
        val routineExercises = runCatching { queries.selectAllRoutineExercisesSync().executeAsList() }
            .getOrElse { error ->
                log.e(error) { "Failed to load routine exercises for legacy routine-name backfill" }
                emptyList()
            }
        val resolutionContext = buildRoutineNameResolutionContext(routines, routineExercises)

        var updatedNameRows = 0
        var updatedIdRows = 0
        database.transaction {
            sessions.forEach { session ->
                // Backfill routineId for sessions that have a routineSessionId but no routineId
                if (session.routineId == null && session.routineSessionId != null) {
                    val resolvedRoutineId = resolveRoutineIdForSession(session, resolutionContext)
                    if (resolvedRoutineId != null) {
                        queries.updateSessionRoutineId(
                            routineId = resolvedRoutineId,
                            id = session.id,
                        )
                        updatedIdRows++
                    }
                }

                // Backfill routine name (or clear garbage names that can't be inferred)
                val updatedRoutineName = resolveRoutineNameForSession(session, resolutionContext)
                if (updatedRoutineName == session.routineName) return@forEach
                // If resolved is null and current is also null, nothing to change
                if (updatedRoutineName == null && session.routineName == null) return@forEach
                queries.updateSessionRoutineName(
                    routineName = updatedRoutineName,
                    id = session.id,
                )
                updatedNameRows++
            }
        }

        if (updatedNameRows > 0 || updatedIdRows > 0) {
            log.i { "Legacy routine backfill: updated $updatedNameRows names, $updatedIdRows routineIds" }
        }
    }

    private fun normalizeLegacyWorkoutModes() {
        normalizeLegacySessionModes()
        normalizeLegacyPersonalRecordModes()
    }

    private fun normalizeLegacySessionModes() {
        val sessions = runCatching { queries.selectAllSessionsSync().executeAsList() }
            .getOrElse { error ->
                log.e(error) { "Failed to load workout sessions for mode normalization" }
                return
            }

        var updated = 0
        database.transaction {
            sessions.forEach { session ->
                val normalizedMode = normalizeWorkoutModeKey(session.mode)
                if (normalizedMode == session.mode) return@forEach

                queries.updateSessionMode(
                    mode = normalizedMode,
                    id = session.id,
                )
                updated++
            }
        }

        if (updated > 0) {
            log.i { "Normalized workout mode keys for $updated workout sessions" }
        }
    }

    private fun normalizeLegacyPersonalRecordModes() {
        knownProfileIds().forEach { profileId ->
            val records = runCatching { queries.selectAllRecords(profileId = profileId).executeAsList() }
                .getOrElse { error ->
                    log.e(error) { "Failed to load personal records for mode normalization (profile=$profileId)" }
                    return@forEach
                }

            var updated = 0
            var merged = 0
            database.transaction {
                records.forEach { record ->
                    val normalizedMode = normalizeWorkoutModeKey(record.workoutMode)
                    if (normalizedMode == record.workoutMode) return@forEach

                    val recordProfileId = record.profile_id.ifBlank { profileId }
                    val canonicalRecord = queries.selectPR(
                        exerciseId = record.exerciseId,
                        workoutMode = normalizedMode,
                        prType = record.prType,
                        phase = record.phase,
                        profileId = recordProfileId,
                    ).executeAsOneOrNull()

                    if (canonicalRecord == null || shouldReplacePersonalRecord(record, canonicalRecord)) {
                        queries.upsertPR(
                            exerciseId = record.exerciseId,
                            exerciseName = record.exerciseName,
                            weight = record.weight,
                            reps = record.reps,
                            oneRepMax = record.oneRepMax,
                            achievedAt = record.achievedAt,
                            workoutMode = normalizedMode,
                            prType = record.prType,
                            volume = record.volume,
                            phase = record.phase,
                            profile_id = recordProfileId,
                        )
                        updated++
                    } else {
                        merged++
                    }

                    queries.deletePRByKey(
                        exerciseId = record.exerciseId,
                        workoutMode = record.workoutMode,
                        prType = record.prType,
                        phase = record.phase,
                        profile_id = recordProfileId,
                    )
                }
            }

            if (updated > 0 || merged > 0) {
                log.i { "Normalized personal record mode keys for profile=$profileId: updated $updated rows, merged $merged legacy duplicates" }
            }
        }
    }

    private suspend fun repairPersonalRecordsFromWorkoutHistory() {
        val sessions = runCatching { queries.selectAllSessionsSync().executeAsList() }
            .getOrElse { error ->
                log.e(error) { "Failed to load workout sessions for PR repair" }
                return
            }
            .sortedBy { it.timestamp }

        if (sessions.isEmpty()) return

        val personalRecordRepository = SqlDelightPersonalRecordRepository(database)
        var repairedSessions = 0
        var repairedRecords = 0

        sessions
            .groupBy { it.profile_id.ifBlank { "default" } }
            .forEach { (profileId, profileSessions) ->
                var profileRepairedSessions = 0
                var profileRepairedRecords = 0

                profileSessions.sortedBy { it.timestamp }.forEach { session ->
                    val exerciseId = sanitizeLegacyLabel(session.exerciseId) ?: return@forEach
                    if (session.isJustLift != 0L) return@forEach

                    val normalizedMode = normalizeWorkoutModeKey(session.mode)
                    if (normalizedMode == "Echo") return@forEach

                    val reps = session.workingReps.toInt()
                    if (reps <= 0) return@forEach

                    val achievedWeightKg = session.heaviestLiftKg?.toFloat() ?: session.weightPerCableKg.toFloat()
                    val configuredWeightKg = session.weightPerCableKg.toFloat()
                    if (achievedWeightKg <= 0f || configuredWeightKg <= 0f) return@forEach

                    val brokenPRs = runCatching {
                        personalRecordRepository.updatePRsIfBetter(
                            exerciseId = exerciseId,
                            weightPRWeightPerCableKg = achievedWeightKg,
                            volumePRWeightPerCableKg = configuredWeightKg,
                            reps = reps,
                            workoutMode = normalizedMode,
                            timestamp = session.timestamp,
                            profileId = profileId,
                        ).getOrThrow()
                    }.getOrElse { error ->
                        log.e(error) { "Failed to repair PRs for session ${session.id} (profile=$profileId)" }
                        return@forEach
                    }

                    if (brokenPRs.isNotEmpty()) {
                        repairedSessions++
                        repairedRecords += brokenPRs.size
                        profileRepairedSessions++
                        profileRepairedRecords += brokenPRs.size
                    }
                }

                if (profileRepairedRecords > 0) {
                    log.i { "Repaired $profileRepairedRecords PR records from $profileRepairedSessions workout sessions for profile=$profileId" }
                }
            }

        if (repairedRecords > 0) {
            log.i { "Repaired $repairedRecords PR records from $repairedSessions workout sessions" }
        }
    }

    private fun knownProfileIds(): List<String> {
        val profileIds = queries.getAllProfiles().executeAsList()
            .map { it.id }
            .ifEmpty { listOf("default") }

        return if ("default" in profileIds) profileIds.distinct() else (listOf("default") + profileIds).distinct()
    }

    // Issue #319: Detect and repair PR records for deleted profiles

    /**
     * Scan for PR records belonging to profiles that no longer exist.
     * This can happen when users delete profiles after v0.6.0 migration created
     * profile-scoped PR records.
     *
     * @return Map of orphaned profile IDs to record counts
     */
    fun scanForOrphanedPRRecords(): Map<String, Int> {
        val existingProfileIds = knownProfileIds().toSet()
        val orphanedCounts = mutableMapOf<String, Int>()

        // Get all unique profile_ids from PersonalRecord table
        val allRecordProfileIds = mutableSetOf<String>()
        driver?.executeQuery(
            identifier = null,
            sql = "SELECT DISTINCT profile_id FROM PersonalRecord",
            mapper = { cursor ->
                while (cursor.next().value) {
                    cursor.getString(0)?.let { allRecordProfileIds.add(it) }
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )

        // Find profile IDs in PR table that don't exist in UserProfile table
        val orphanedProfileIds = allRecordProfileIds - existingProfileIds

        for (orphanId in orphanedProfileIds) {
            var count = 0
            driver?.executeQuery(
                identifier = null,
                sql = "SELECT COUNT(*) FROM PersonalRecord WHERE profile_id = ?",
                mapper = { cursor ->
                    if (cursor.next().value) {
                        count = cursor.getLong(0)?.toInt() ?: 0
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 1,
            ) {
                bindString(0, orphanId)
            }
            orphanedCounts[orphanId] = count
        }

        return orphanedCounts
    }

    /**
     * Issue #319: Repair orphaned PR records by migrating them to the active profile.
     * This should be called when scanForOrphanedPRRecords() finds orphaned data.
     *
     * @param targetProfileId Profile ID to migrate orphaned records to (usually active profile)
     * @return Number of records repaired
     */
    suspend fun repairOrphanedPRRecords(targetProfileId: String): Int {
        return migrationMutex.withLock {
            _orphanedDataRepairState.value = OrphanedDataRepairState.Repairing("Migrating orphaned PR records to $targetProfileId")

            val orphanedCounts = scanForOrphanedPRRecords()
            repairOrphanedPRRecordsInternal(targetProfileId, orphanedCounts)
        }
    }

    /**
     * Issue #319: Internal implementation that does the actual repair.
     * Called from both repairOrphanedPRRecords (with mutex) and checkAndRepairOrphanedData (already inside mutex).
     */
    private suspend fun repairOrphanedPRRecordsInternal(targetProfileId: String, orphanedCounts: Map<String, Int>): Int {
        if (orphanedCounts.isEmpty()) {
            _orphanedDataRepairState.value = OrphanedDataRepairState.Completed("No orphaned records found", 0)
            return 0
        }

        var totalRepaired = 0
        database.transaction {
            for ((orphanProfileId, count) in orphanedCounts) {
                log.i { "Issue #319: Migrating $count PR records from deleted profile '$orphanProfileId' to '$targetProfileId'" }

                // Use raw SQL to update profile_id for all records belonging to the orphaned profile
                driver?.execute(
                    identifier = null,
                    sql = "UPDATE PersonalRecord SET profile_id = ? WHERE profile_id = ?",
                    parameters = 2,
                ) {
                    bindString(0, targetProfileId)
                    bindString(1, orphanProfileId)
                }

                // Derived profile aggregates are recomputed after the move to avoid
                // carrying duplicate singleton rows across profiles.
                driver?.execute(
                    identifier = null,
                    sql = "DELETE FROM GamificationStats WHERE profile_id IN (?, ?)",
                    parameters = 2,
                ) {
                    bindString(0, targetProfileId)
                    bindString(1, orphanProfileId)
                }

                driver?.execute(
                    identifier = null,
                    sql = "DELETE FROM RpgAttributes WHERE profile_id IN (?, ?)",
                    parameters = 2,
                ) {
                    bindString(0, targetProfileId)
                    bindString(1, orphanProfileId)
                }

                // Migrate EarnedBadge records
                driver?.execute(
                    identifier = null,
                    sql = "UPDATE EarnedBadge SET profile_id = ? WHERE profile_id = ?",
                    parameters = 2,
                ) {
                    bindString(0, targetProfileId)
                    bindString(1, orphanProfileId)
                }

                totalRepaired += count
            }
        }

        // Recompute gamification after repair
        recomputeDerivedGamification(targetProfileId)

        _orphanedDataRepairState.value = OrphanedDataRepairState.Completed(
            "Migrated $totalRepaired records to $targetProfileId",
            totalRepaired,
        )

        log.i { "Issue #319: Successfully repaired $totalRepaired orphaned PR records" }
        return totalRepaired
    }

    /**
     * Issue #319: Full check for orphaned data that can be called on startup
     * after the regular profile scope repair completes.
     * NOTE: This should only be called from within migrationMutex.withLock
     */
    suspend fun checkAndRepairOrphanedData() {
        // Skip if no driver available (test environments)
        if (driver == null) {
            log.d { "Issue #319: Skipping orphaned data check — no SqlDriver available" }
            return
        }

        _orphanedDataRepairState.value = OrphanedDataRepairState.Scanning("Checking for orphaned PR records")

        val orphanedCounts = scanForOrphanedPRRecords()
        val activeProfile = resolveActiveProfile()
        val targetProfileId = activeProfile?.id ?: "default"

        if (orphanedCounts.isEmpty()) {
            _orphanedDataRepairState.value = OrphanedDataRepairState.Idle
            return
        }

        log.w { "Issue #319: Found orphaned PR records: $orphanedCounts. Target profile for repair: $targetProfileId" }

        // Auto-repair if there's an active profile
        if (activeProfile != null) {
            _orphanedDataRepairState.value = OrphanedDataRepairState.NeedsRepair(
                orphanedProfileIds = orphanedCounts.keys.toList(),
                orphanedRecordCounts = orphanedCounts,
                targetProfileId = targetProfileId,
            )

            // Auto-repair (without acquiring mutex again - already inside runMigrations)
            repairOrphanedPRRecordsInternal(targetProfileId, orphanedCounts)
        } else {
            _orphanedDataRepairState.value = OrphanedDataRepairState.Failed(
                "Found orphaned records but no active profile to migrate to: $orphanedCounts",
            )
        }
    }

    private fun shouldReplacePersonalRecord(
        candidate: com.devil.phoenixproject.database.PersonalRecord,
        current: com.devil.phoenixproject.database.PersonalRecord,
    ): Boolean = when (candidate.prType) {
        PRType.MAX_VOLUME.name -> when {
            candidate.volume > current.volume -> true
            candidate.volume < current.volume -> false
            else -> candidate.achievedAt > current.achievedAt
        }

        else -> when {
            candidate.weight > current.weight -> true
            candidate.weight < current.weight -> false
            candidate.oneRepMax > current.oneRepMax -> true
            candidate.oneRepMax < current.oneRepMax -> false
            else -> candidate.achievedAt > current.achievedAt
        }
    }

    private fun shouldReplacePersonalRecord(
        candidate: CanonicalPersonalRecord,
        current: CanonicalPersonalRecord,
    ): Boolean = when (candidate.prType) {
        PRType.MAX_VOLUME.name -> when {
            candidate.volume > current.volume -> true
            candidate.volume < current.volume -> false
            else -> candidate.achievedAt > current.achievedAt
        }

        else -> when {
            candidate.weight > current.weight -> true
            candidate.weight < current.weight -> false
            candidate.oneRepMax > current.oneRepMax -> true
            candidate.oneRepMax < current.oneRepMax -> false
            else -> candidate.achievedAt > current.achievedAt
        }
    }

    private fun resolveRoutineNameForSession(
        session: WorkoutSession,
        routineNameResolutionContext: RoutineNameResolutionContext,
    ): String? {
        val existingRoutineName = sanitizeRoutineName(session.routineName)
        val inferredRoutineName = inferRoutineName(session, routineNameResolutionContext)
        val existingLooksLikeExercisePlaceholder =
            normalizeExerciseToken(existingRoutineName) == normalizeExerciseToken(session.exerciseName)

        return when {
            session.isJustLift != 0L -> "Just Lift"
            inferredRoutineName != null && (existingRoutineName == null || existingLooksLikeExercisePlaceholder) -> inferredRoutineName
            existingRoutineName != null && !existingLooksLikeExercisePlaceholder -> existingRoutineName
            else -> null // Can't determine routine - leave null (standalone exercise)
        }
    }

    /**
     * Attempt to resolve the routineId for a legacy session by checking if the session's
     * exerciseId uniquely belongs to a single routine.
     */
    private fun resolveRoutineIdForSession(
        session: WorkoutSession,
        routineNameResolutionContext: RoutineNameResolutionContext,
    ): String? {
        val exerciseId = sanitizeLegacyLabel(session.exerciseId) ?: return null
        return routineNameResolutionContext.routineIdByExerciseId[exerciseId]
    }

    private fun inferRoutineName(
        session: WorkoutSession,
        routineNameResolutionContext: RoutineNameResolutionContext,
    ): String? {
        val byExerciseId = sanitizeLegacyLabel(session.exerciseId)?.let { exerciseId ->
            routineNameResolutionContext.uniqueRoutineNameByExerciseId[exerciseId]
        }
        if (byExerciseId != null) return byExerciseId

        val normalizedExerciseName = normalizeExerciseToken(session.exerciseName) ?: return null
        return routineNameResolutionContext.uniqueRoutineNameByExerciseName[normalizedExerciseName]
    }

    private fun buildRoutineNameResolutionContext(
        routines: List<Routine>,
        routineExercises: List<RoutineExercise>,
    ): RoutineNameResolutionContext {
        val routineNameById = routines.associate { routine ->
            routine.id to sanitizeEntityName(routine.name, "Unnamed Routine")
        }

        // Build exerciseId → routineId map for sessions where exercise uniquely belongs to one routine
        val routineIdsByExerciseId = mutableMapOf<String, MutableSet<String>>()
        routineExercises.forEach { exercise ->
            val exerciseId = sanitizeLegacyLabel(exercise.exerciseId) ?: return@forEach
            routineIdsByExerciseId.getOrPut(exerciseId) { mutableSetOf() }.add(exercise.routineId)
        }
        val routineIdByExerciseId = mutableMapOf<String, String>()
        routineIdsByExerciseId.forEach { (exerciseId, routineIds) ->
            if (routineIds.size == 1) {
                routineIdByExerciseId[exerciseId] = routineIds.first()
            }
        }

        val nonTemplateRoutineIds = routines
            .asSequence()
            .filterNot { it.id.startsWith("cycle_routine_") }
            .map { it.id }
            .toSet()

        fun collectUniqueRoutineNamesByExerciseId(
            allowedRoutineIds: Set<String>? = null,
        ): Map<String, String> {
            val routineIdsByExerciseId = mutableMapOf<String, MutableSet<String>>()
            routineExercises.forEach { exercise ->
                if (allowedRoutineIds != null && exercise.routineId !in allowedRoutineIds) return@forEach
                val exerciseId = sanitizeLegacyLabel(exercise.exerciseId) ?: return@forEach
                routineIdsByExerciseId.getOrPut(exerciseId) { mutableSetOf() }.add(exercise.routineId)
            }

            val uniqueRoutineNames = mutableMapOf<String, String>()
            routineIdsByExerciseId.forEach { (exerciseId, routineIds) ->
                if (routineIds.size != 1) return@forEach
                val routineId = routineIds.first()
                val routineName = routineNameById[routineId] ?: return@forEach
                uniqueRoutineNames[exerciseId] = routineName
            }
            return uniqueRoutineNames
        }

        fun collectUniqueRoutineNamesByExerciseName(
            allowedRoutineIds: Set<String>? = null,
        ): Map<String, String> {
            val routineIdsByExerciseName = mutableMapOf<String, MutableSet<String>>()
            routineExercises.forEach { exercise ->
                if (allowedRoutineIds != null && exercise.routineId !in allowedRoutineIds) return@forEach
                val normalizedExerciseName = normalizeExerciseToken(exercise.exerciseName) ?: return@forEach
                routineIdsByExerciseName.getOrPut(normalizedExerciseName) { mutableSetOf() }.add(exercise.routineId)
            }

            val uniqueRoutineNames = mutableMapOf<String, String>()
            routineIdsByExerciseName.forEach { (exerciseName, routineIds) ->
                if (routineIds.size != 1) return@forEach
                val routineId = routineIds.first()
                val routineName = routineNameById[routineId] ?: return@forEach
                uniqueRoutineNames[exerciseName] = routineName
            }
            return uniqueRoutineNames
        }

        val uniqueFromNonTemplateById = collectUniqueRoutineNamesByExerciseId(
            allowedRoutineIds = nonTemplateRoutineIds.takeIf { it.isNotEmpty() },
        )
        val uniqueFromAllById = collectUniqueRoutineNamesByExerciseId()
        val uniqueRoutineNameByExerciseId = uniqueFromAllById.toMutableMap().apply {
            putAll(uniqueFromNonTemplateById)
        }
        val uniqueFromNonTemplateByName = collectUniqueRoutineNamesByExerciseName(
            allowedRoutineIds = nonTemplateRoutineIds.takeIf { it.isNotEmpty() },
        )
        val uniqueFromAllByName = collectUniqueRoutineNamesByExerciseName()
        val uniqueRoutineNameByExerciseName = uniqueFromAllByName.toMutableMap().apply {
            putAll(uniqueFromNonTemplateByName)
        }

        return RoutineNameResolutionContext(
            routineNameById = routineNameById,
            routineIdByExerciseId = routineIdByExerciseId,
            uniqueRoutineNameByExerciseId = uniqueRoutineNameByExerciseId,
            uniqueRoutineNameByExerciseName = uniqueRoutineNameByExerciseName,
        )
    }

    /**
     * Sanitize a routineSessionId, also filtering out fabricated `legacy_session_*` IDs
     * that were incorrectly generated by an earlier version of the export/import code.
     */
    private fun sanitizeRoutineSessionId(raw: String?): String? {
        val sanitized = sanitizeLegacyLabel(raw) ?: return null
        if (sanitized.startsWith("legacy_session_", ignoreCase = true)) return null
        return sanitized
    }

    private fun sanitizeEntityName(raw: String?, fallback: String): String = sanitizeLegacyLabel(raw) ?: fallback

    private fun sanitizeLegacyLabel(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.equals("null", ignoreCase = true)) return null
        if (!trimmed.any { it.isLetterOrDigit() }) return null
        return trimmed
    }

    /**
     * Generic placeholder routine names set by external imports (e.g. Vitruvian cloud).
     * These don't identify a real routine and should be treated as null/unknown.
     */
    private val GARBAGE_ROUTINE_NAMES = setOf(
        "imported strength training session",
    )

    private fun sanitizeRoutineName(raw: String?): String? {
        val sanitized = sanitizeLegacyLabel(raw) ?: return null
        if (sanitized.lowercase().trim() in GARBAGE_ROUTINE_NAMES) return null
        return sanitized
    }

    private fun normalizeExerciseToken(raw: String?): String? {
        val sanitized = sanitizeLegacyLabel(raw) ?: return null
        val collapsedWhitespace = sanitized
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
        return collapsedWhitespace.ifEmpty { null }
    }

    /**
     * Cancels the coroutine scope to prevent memory leaks.
     * Should be called when the MigrationManager is no longer needed.
     */
    fun close() {
        scope.cancel()
        log.d { "MigrationManager scope cancelled" }
    }
}
