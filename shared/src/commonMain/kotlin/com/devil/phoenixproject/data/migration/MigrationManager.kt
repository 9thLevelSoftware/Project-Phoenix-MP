package com.devil.phoenixproject.data.migration

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.local.LegacyCatalogueRemapper
import com.devil.phoenixproject.data.preferences.LegacyProfilePreferencesReader
import com.devil.phoenixproject.data.preferences.ProfileLocalSafetyStore
import com.devil.phoenixproject.data.preferences.ProfilePreferencesCodec
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.LegacyBaselineRepair
import com.devil.phoenixproject.data.repository.ProfilePreferencesRepository
import com.devil.phoenixproject.data.repository.ProfileMutationBarrier
import com.devil.phoenixproject.data.repository.ProfileRecoveryDiscovery
import com.devil.phoenixproject.data.repository.ProfileScopedDataMerger
import com.devil.phoenixproject.data.repository.SqlDelightPersonalRecordRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.normalizeWorkoutModeKey
import com.devil.phoenixproject.database.Routine
import com.devil.phoenixproject.database.RoutineExercise
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.database.WorkoutSession
import com.devil.phoenixproject.domain.model.SessionTiming
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.premium.RpgAttributeEngine
import com.russhwolf.settings.Settings
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface RequiredMigrationState {
    data object NotStarted : RequiredMigrationState
    data object Applying : RequiredMigrationState
    data object Ready : RequiredMigrationState
    data class Failed(val message: String) : RequiredMigrationState
}

sealed interface NonCriticalRepairState {
    data object NotStarted : NonCriticalRepairState
    data object Applying : NonCriticalRepairState
    data object Ready : NonCriticalRepairState
    data class Failed(val diagnosticCode: String) : NonCriticalRepairState
}

fun interface PersonalRecordHistoryRepair {
    suspend fun repair(session: WorkoutSession, profileId: String): Int
}

/**
 * Manages data migrations on app startup.
 * Call [checkAndRunMigrations] after Koin is initialized.
 * Call [close] when done to prevent memory leaks.
 */
class MigrationManager(
    private val database: PhoenixDatabase,
    private val userProfileRepository: UserProfileRepository,
    private val gamificationRepository: GamificationRepository,
    private val settings: Settings,
    private val profilePreferencesRepository: ProfilePreferencesRepository,
    private val profileLocalSafetyStore: ProfileLocalSafetyStore,
    private val legacyProfilePreferencesReader: LegacyProfilePreferencesReader,
    private val profileScopedDataMerger: ProfileScopedDataMerger = ProfileScopedDataMerger(database),
    private val driver: SqlDriver? = null,
    private val legacyBaselineRepair: LegacyBaselineRepair? = null,
    private val profileRecoveryDiscovery: ProfileRecoveryDiscovery? = null,
    private val profileMutationBarrier: ProfileMutationBarrier? = null,
    private val personalRecordHistoryRepair: PersonalRecordHistoryRepair? = null,
) : RequiredMigrationGate {
    private val log = Logger.withTag("MigrationManager")

    companion object {
        private const val KEY_PROFILE_PREFERENCES_MIGRATION_COMPLETE =
            "profile_preferences_legacy_migration_complete_v1"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queries get() = database.phoenixDatabaseQueries
    private val migrationMutex = Mutex()
    private val requiredMigrationMutex = Mutex()

    private val _requiredMigrationState = MutableStateFlow<RequiredMigrationState>(
        RequiredMigrationState.NotStarted,
    )
    override val requiredMigrationState: StateFlow<RequiredMigrationState> =
        _requiredMigrationState.asStateFlow()
    private val _nonCriticalRepairState = MutableStateFlow<NonCriticalRepairState>(
        NonCriticalRepairState.NotStarted,
    )
    val nonCriticalRepairState: StateFlow<NonCriticalRepairState> =
        _nonCriticalRepairState.asStateFlow()

    private data class RoutineNameResolutionContext(
        val routineNameById: Map<String, String>,
        val routineIdByExerciseId: Map<String, String>,
        val uniqueRoutineNameByExerciseId: Map<String, String>,
        val uniqueRoutineNameByExerciseName: Map<String, String>,
    )

    /**
     * Check for and run any pending migrations.
     * This should be called once on app startup.
     */
    fun checkAndRunMigrations() {
        scope.launch {
            runRequiredMigrations()
        }
    }

    suspend fun runMigrationsNow() {
        runRequiredMigrations(scheduleNonCriticalRepairs = false)
        if (requiredMigrationState.value == RequiredMigrationState.Ready) {
            runNonCriticalRepairsNow()
        }
    }

    suspend fun runRequiredMigrations() = runRequiredMigrations(scheduleNonCriticalRepairs = true)

    private suspend fun runRequiredMigrations(scheduleNonCriticalRepairs: Boolean) = requiredMigrationMutex.withLock {
        if (_requiredMigrationState.value == RequiredMigrationState.Ready) return@withLock
        _requiredMigrationState.value = RequiredMigrationState.Applying
        try {
            migrateProfilePreferences()
            _requiredMigrationState.value = RequiredMigrationState.Ready
            if (scheduleNonCriticalRepairs) scope.launch { runNonCriticalRepairsNow() }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            log.e(error) { "Required profile preference migration failed" }
            _requiredMigrationState.value = RequiredMigrationState.Failed(
                error.message ?: "Profile preference migration failed",
            )
        }
    }

    suspend fun retryRequiredMigrations() {
        runRequiredMigrations()
    }

    override suspend fun awaitRequiredMigrations() {
        when (val terminal = requiredMigrationState.first {
            it is RequiredMigrationState.Ready || it is RequiredMigrationState.Failed
        }) {
            RequiredMigrationState.Ready -> Unit
            is RequiredMigrationState.Failed -> throw RequiredMigrationFailedException(terminal.message)
            else -> error("Required migration gate returned a non-terminal state")
        }
    }

    private suspend fun migrateProfilePreferences() {
        userProfileRepository.ensureDefaultProfile()
        profilePreferencesRepository.seedMissingProfiles()
        userProfileRepository.recoverPendingProfileTransitionForStartup()
        val existingProfiles = userProfileRepository.allProfiles.value
        val snapshot = legacyProfilePreferencesReader.readNormalized()
        val migrationTime = currentTimeMillis()
        existingProfiles.forEach { profile ->
            queries.applyLegacyProfilePreferences(
                profile_id = profile.id,
                body_weight_kg = snapshot.core.bodyWeightKg.toDouble(),
                weight_unit = snapshot.core.weightUnit.name,
                weight_increment = snapshot.core.weightIncrement.toDouble(),
                equipment_rack_json = ProfilePreferencesCodec.encodeRack(snapshot.rack),
                workout_preferences_json = ProfilePreferencesCodec.encodeWorkout(snapshot.workout),
                led_color_scheme_id = snapshot.led.colorScheme.toLong(),
                led_preferences_json = ProfilePreferencesCodec.encodeLed(snapshot.led),
                vbt_enabled = 1L,
                vbt_preferences_json = ProfilePreferencesCodec.encodeVbt(
                    snapshot.vbt.copy(enabled = true),
                ),
                migrated_at = migrationTime,
            )
        }
        if (!settings.getBoolean(KEY_PROFILE_PREFERENCES_MIGRATION_COMPLETE, false)) {
            profileLocalSafetyStore.copyLegacyToProfiles(existingProfiles.map { it.id }, snapshot.localSafety)
            settings.putBoolean(KEY_PROFILE_PREFERENCES_MIGRATION_COMPLETE, true)
        }
        val baselineRepair = legacyBaselineRepair?.reconcileAfterProfileBootstrap(existingProfiles)
        runRequiredDataRepairs()
        profileRecoveryDiscovery?.apply {
            discoverProfileData(existingProfiles)
            discoverLegacyBaselines(baselineRepair?.ambiguous?.size ?: 0)
        }
        userProfileRepository.retryPendingLocalCleanup()
        userProfileRepository.reconcileActiveProfileContext()
    }

    private suspend fun runNonCriticalRepairsNow() {
        val run = suspend {
            migrationMutex.withLock {
                applyNonCriticalRepairs()
            }
        }
        profileMutationBarrier?.withExclusive { run() } ?: run()
    }

    private suspend fun applyNonCriticalRepairs() {
            try {
                _nonCriticalRepairState.value = NonCriticalRepairState.Applying
                runMigrations()
                _nonCriticalRepairState.value = NonCriticalRepairState.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.e(e) { "Migration failed" }
                _nonCriticalRepairState.value = NonCriticalRepairState.Failed(
                    diagnosticCode = "PERSONAL_RECORD_HISTORY_REPAIR_FAILED",
                )
            }
    }

    private suspend fun runMigrations() {
        refreshProfilesIfAvailable()
        runNonCriticalPersonalRecordRepair()
        // Data-gated, so a no-op in the steady state; catches legacy catalogue ids that
        // arrived while the app was last running (pull, restore) before any UI entry point.
        LegacyCatalogueRemapper.healAfterBulkWrite(database, source = "startup")
    }

    private fun runRequiredDataRepairs() {
        runAtomicDataRepair("fabricated-routine-session-ids-v1", ::cleanupFabricatedRoutineSessionIds)
        runAtomicDataRepair("workout-mode-keys-v1", ::normalizeLegacyWorkoutModes)
        runAtomicDataRepair("legacy-workout-routine-names-v1", ::backfillLegacyWorkoutRoutineNames)
        runAtomicDataRepair("epoch-zero-session-starts-v1", ::repairEpochZeroSessionStarts)
    }

    /**
     * Sessions saved by the "1970" bug (a save read a zeroed workoutStartTime) have
     * timestamp 0 and a duration equal to the save time in epoch ms. Rebuild the start from
     * the session's own samples/sets and replace the duration with the sample span (or 0).
     * Local only: sync generations are not bumped, and the portal repairs these on push.
     */
    private fun repairEpochZeroSessionStarts() {
        queries.repairEpochZeroSessionStarts(minPlausibleMs = SessionTiming.MIN_PLAUSIBLE_START_MS)
    }

    private fun runAtomicDataRepair(repairKey: String, repair: () -> Unit) {
        if (queries.selectAppliedDataRepair(repairKey).executeAsOneOrNull() != null) return
        database.transaction {
            repair()
            check(queries.insertAppliedDataRepair(repairKey, currentTimeMillis()).value == 1L) {
                "Required repair ledger write failed for $repairKey"
            }
        }
    }

    private suspend fun runNonCriticalPersonalRecordRepair() {
        val repairKey = "personal-record-history-v1"
        if (queries.selectAppliedDataRepair(repairKey).executeAsOneOrNull() != null) return
        repairPersonalRecordsFromWorkoutHistory()
        check(queries.insertAppliedDataRepair(repairKey, currentTimeMillis()).value == 1L) {
            "Non-critical repair ledger write failed for $repairKey"
        }
    }

    private suspend fun recomputeDerivedGamification(profileId: String) {
        gamificationRepository.updateStats(profileId)
        val rpgInput = gamificationRepository.getRpgInput(profileId)
        gamificationRepository.saveRpgProfile(RpgAttributeEngine.computeProfile(rpgInput), profileId)
    }

    private suspend fun refreshProfilesIfAvailable() {
        userProfileRepository.refreshProfiles()
    }

    /**
     * Remove fabricated `legacy_session_*` routineSessionIds that were incorrectly
     * generated by an earlier version of the export/import code. These synthetic IDs
     * break history grouping by making every session appear as a separate routine execution.
     */
    private fun cleanupFabricatedRoutineSessionIds() {
        val sessions = queries.selectAllSessionsSync().executeAsList()

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
        val sessions = queries.selectAllSessionsSync().executeAsList()
        if (sessions.isEmpty()) return

        val routines = queries.selectAllRoutinesSync().executeAsList()
        val routineExercises = queries.selectAllRoutineExercisesSync().executeAsList()
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
        val sessions = queries.selectAllSessionsSync().executeAsList()

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
            var affectedRows = 0
            try {
                database.transaction {
                    affectedRows = profileScopedDataMerger.normalizePersonalRecordModes(profileId)
                }
            } catch (error: Throwable) {
                log.e(error) {
                    "Failed to normalize personal record modes for profile=$profileId"
                }
                throw error
            }
            if (affectedRows > 0) {
                log.i {
                    "Normalized personal record mode keys for profile=$profileId: affected $affectedRows rows"
                }
            }
        }
    }

    private suspend fun repairPersonalRecordsFromWorkoutHistory() {
        val sessions = try {
            queries.selectAllSessionsSync().executeAsList().sortedBy { it.timestamp }
        } catch (error: Throwable) {
            log.e(error) { "Failed to load workout sessions for PR repair" }
            throw error
        }

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

                    val repairedCount = try {
                        personalRecordHistoryRepair?.repair(session, profileId)
                            ?: personalRecordRepository.updatePRsIfBetter(
                            exerciseId = exerciseId,
                            weightPRWeightPerCableKg = achievedWeightKg,
                            volumePRWeightPerCableKg = configuredWeightKg,
                            reps = reps,
                            workoutMode = normalizedMode,
                            timestamp = session.timestamp,
                            profileId = profileId,
                        ).getOrThrow().size
                    } catch (error: Throwable) {
                        log.e(error) { "Failed to repair PRs for session ${session.id} (profile=$profileId)" }
                        throw error
                    }

                    if (repairedCount > 0) {
                        repairedSessions++
                        repairedRecords += repairedCount
                        profileRepairedSessions++
                        profileRepairedRecords += repairedCount
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
            // Live rows only: a permanently deleted profile (PR 20) keeps its PR tombstones
            // under its own id; moving them would hand them to another profile.
            sql = "SELECT DISTINCT profile_id FROM PersonalRecord WHERE deletedAt IS NULL",
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
                sql = "SELECT COUNT(*) FROM PersonalRecord WHERE profile_id = ? AND deletedAt IS NULL",
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
    suspend fun repairOrphanedPRRecords(targetProfileId: String): Int = migrationMutex.withLock {
        val orphanedCounts = scanForOrphanedPRRecords()
        repairOrphanedPRRecordsInternal(targetProfileId, orphanedCounts)
    }

    /**
     * Issue #319: Internal implementation that does the actual repair.
     * Called from [repairOrphanedPRRecords] while its migration mutex is held.
     */
    private suspend fun repairOrphanedPRRecordsInternal(targetProfileId: String, orphanedCounts: Map<String, Int>): Int {
        if (orphanedCounts.isEmpty()) {
            return 0
        }

        // F013/F014: the orphan repair previously moved PR and badge rows with a
        // raw `UPDATE ... SET profile_id`. When the target profile already holds a
        // row with the same composite key (idx_pr_unique:
        // exerciseId+workoutMode+prType+phase+profile_id; idx_earned_badge_profile:
        // badgeId+profile_id), the update produces a duplicate key and aborts the
        // whole repair transaction. Reuse the shared merger from profile deletion;
        // it resolves aliases before reassignment while retaining target numeric IDs,
        // UUIDs, and sync metadata. A SqlDriver remains required for the orphan scan
        // and derived-aggregate cleanup performed in the same transaction.
        val moveDriver = driver
        if (moveDriver == null) {
            log.w { "Orphaned PR repair skipped: no SqlDriver available for a safe dedup-merge" }
            return 0
        }
        var totalRepaired = 0
        database.transaction {
            for ((orphanProfileId, count) in orphanedCounts) {
                log.i { "Issue #319: Migrating $count PR records from deleted profile '$orphanProfileId' to '$targetProfileId'" }

                profileScopedDataMerger.mergePersonalRecords(orphanProfileId, targetProfileId)
                profileScopedDataMerger.mergeEarnedBadges(orphanProfileId, targetProfileId)

                // Derived profile aggregates are recomputed after the move to avoid
                // carrying duplicate singleton rows across profiles.
                moveDriver.execute(
                    identifier = null,
                    sql = "DELETE FROM GamificationStats WHERE profile_id IN (?, ?)",
                    parameters = 2,
                ) {
                    bindString(0, targetProfileId)
                    bindString(1, orphanProfileId)
                }

                moveDriver.execute(
                    identifier = null,
                    sql = "DELETE FROM RpgAttributes WHERE profile_id IN (?, ?)",
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

        log.i { "Issue #319: Successfully repaired $totalRepaired orphaned PR records" }
        return totalRepaired
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
     * Generic placeholder routine names set by external imports (e.g. legacy cloud exports).
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
