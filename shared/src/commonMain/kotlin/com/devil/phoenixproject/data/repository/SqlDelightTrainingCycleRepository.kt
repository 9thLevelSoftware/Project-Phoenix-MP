package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.CycleItem
import com.devil.phoenixproject.domain.model.CycleProgress
import com.devil.phoenixproject.domain.model.CycleProgression
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.util.CycleDayBackup
import com.devil.phoenixproject.util.CycleProgressBackup
import com.devil.phoenixproject.util.CycleProgressionBackup
import com.devil.phoenixproject.util.TrainingCycleBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class PersistedCycleDraftPayload(
    val cycle: TrainingCycleBackup,
    val days: List<CycleDayBackup>,
    val progress: CycleProgressBackup? = null,
    val progression: CycleProgressionBackup? = null,
)

class SqlDelightTrainingCycleRepository(private val db: PhoenixDatabase) : TrainingCycleRepository {

    private val queries = db.phoenixDatabaseQueries
    private val json = Json { ignoreUnknownKeys = true }

    private fun nextEditTimestamp(cycleId: String, proposed: Long = currentTimeMillis()): Long {
        val stored = queries.selectTrainingCycleById(cycleId).executeAsOneOrNull()?.updatedAt ?: Long.MIN_VALUE
        return maxOf(proposed, if (stored == Long.MAX_VALUE) stored else stored + 1)
    }

    private fun ensureCycleSyncState(cycleId: String, profileId: String, accountId: String? = null) {
        queries.insertCycleSyncStateIfAbsent(
            cycleId = cycleId,
            profileId = profileId,
            accountId = accountId,
            dirtyGeneration = 0L,
            acknowledgedGeneration = 0L,
            pendingDeleteUpdatedAt = null,
            pendingDeleteGeneration = null,
        )
    }

    private fun markCycleEdited(cycleId: String, profileId: String, timestamp: Long = nextEditTimestamp(cycleId)) {
        queries.touchTrainingCycleUpdatedAt(updatedAt = timestamp, cycleId = cycleId)
        ensureCycleSyncState(cycleId, profileId)
        queries.markCycleDirty(profileId = profileId, cycleId = cycleId)
    }

    private fun markStoredCycleEdited(cycleId: String) {
        val cycle = queries.selectTrainingCycleById(cycleId).executeAsOneOrNull() ?: return
        markCycleEdited(cycleId, cycle.profile_id)
    }

    // ==================== Mapping Functions ====================

    private suspend fun mapToTrainingCycle(
        id: String,
        name: String,
        description: String?,
        created_at: Long,
        is_active: Long,
        // Multi-profile support (migration 21)
        profileId: String,
        template_id: String?,
        week_number: Long,
        updatedAt: Long,
    ): TrainingCycle {
        val days = getCycleDays(id)
        return TrainingCycle(
            id = id,
            name = name,
            description = description,
            days = days,
            createdAt = created_at,
            isActive = is_active == 1L,
            profileId = profileId,
            templateId = template_id,
            weekNumber = week_number.toInt(),
            updatedAt = updatedAt,
        )
    }

    private fun mapToCycleDay(
        id: String,
        cycle_id: String,
        day_number: Long,
        name: String?,
        routine_id: String?,
        is_rest_day: Long,
        echo_level: String?,
        eccentric_load_percent: Long?,
        weight_progression_percent: Double?,
        rep_modifier: Long?,
        rest_time_override_seconds: Long?,
    ): CycleDay = CycleDay(
        id = id,
        cycleId = cycle_id,
        dayNumber = day_number.toInt(),
        name = name,
        routineId = routine_id,
        isRestDay = is_rest_day == 1L,
        echoLevel = echo_level?.let { parseEchoLevel(it) },
        eccentricLoadPercent = eccentric_load_percent?.toInt(),
        weightProgressionPercent = weight_progression_percent?.toFloat(),
        repModifier = rep_modifier?.toInt(),
        restTimeOverrideSeconds = rest_time_override_seconds?.toInt(),
    )

    private fun mapToCycleProgress(
        id: String,
        cycle_id: String,
        current_day_number: Long,
        last_completed_date: Long?,
        cycle_start_date: Long,
        last_advanced_at: Long?,
        completed_days: String?,
        missed_days: String?,
        rotation_count: Long,
    ): CycleProgress = CycleProgress(
        id = id,
        cycleId = cycle_id,
        currentDayNumber = current_day_number.toInt(),
        lastCompletedDate = last_completed_date,
        cycleStartDate = cycle_start_date,
        lastAdvancedAt = last_advanced_at,
        completedDays = parseIntSet(completed_days),
        missedDays = parseIntSet(missed_days),
        rotationCount = rotation_count.toInt(),
    )

    // ==================== Helper Functions ====================

    private fun parseEchoLevel(value: String): EchoLevel? = try {
        EchoLevel.valueOf(value)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun parseIntSet(json: String?): Set<Int> {
        if (json.isNullOrBlank()) return emptySet()
        return json.trim('[', ']')
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()
    }

    private fun intSetToJson(set: Set<Int>): String = set.sorted().joinToString(",", "[", "]")

    override suspend fun getCycleSyncState(cycleId: String): CycleSyncState? = withContext(Dispatchers.IO) {
        queries.selectCycleSyncState(cycleId).executeAsOneOrNull()?.let {
            CycleSyncState(
                cycleId = it.cycle_id,
                dirtyGeneration = it.dirty_generation,
                acknowledgedGeneration = it.acknowledged_generation,
            )
        }
    }

    override suspend fun acknowledgeCycleGeneration(cycleId: String, sentGeneration: Long) {
        withContext(Dispatchers.IO) {
            queries.ackCycleStateIfGenerationMatches(generation = sentGeneration, cycleId = cycleId)
        }
    }

    override suspend fun getPendingCycleDeletions(
        ownerUserId: String,
        profileId: String,
    ): List<PendingCycleDeletion> = withContext(Dispatchers.IO) {
        require(ownerUserId.isNotBlank()) { "ownerUserId must not be blank" }
        queries.selectPendingCycleDeletions(accountId = ownerUserId, profileId = profileId)
            .executeAsList()
            .mapNotNull { row ->
                val updatedAt = row.updatedAt ?: return@mapNotNull null
                val generation = row.generation ?: return@mapNotNull null
                PendingCycleDeletion(row.id, updatedAt, generation)
            }
    }

    override suspend fun acknowledgeCycleDeletions(
        ownerUserId: String,
        sentGenerationsById: Map<String, Long>,
        acknowledgedIds: Set<String>,
        at: Long,
    ) {
        if (ownerUserId.isBlank() || acknowledgedIds.isEmpty()) return
        withContext(Dispatchers.IO) {
            db.transaction {
                acknowledgedIds.forEach { cycleId ->
                    val generation = sentGenerationsById[cycleId] ?: return@forEach
                    val cycle = queries.selectTrainingCycleById(cycleId).executeAsOneOrNull() ?: return@forEach
                    queries.ackCycleDeletionIfMatches(
                        cycleId = cycleId,
                        profileId = cycle.profile_id,
                        deletedAt = at,
                        accountId = ownerUserId,
                        generation = generation,
                    )
                }
            }
        }
    }

    override suspend fun saveRejectedCycleDraft(
        snapshot: CycleComponentSnapshot,
        rejectedUpdatedAt: Long,
    ) {
        persistRejectedCycleDraft(snapshot.context, rejectedUpdatedAt)
    }

    private suspend fun persistRejectedCycleDraft(
        context: com.devil.phoenixproject.data.sync.PortalSyncAdapter.CycleWithContext,
        rejectedUpdatedAt: Long,
    ) {
        val cycle = context.cycle
        val progress = context.progress
        val progression = context.progression
        val payload = PersistedCycleDraftPayload(
            cycle = TrainingCycleBackup(
                id = cycle.id,
                name = cycle.name,
                description = cycle.description,
                createdAt = cycle.createdAt,
                isActive = false,
                profileId = cycle.profileId,
                templateId = cycle.templateId,
                weekNumber = cycle.weekNumber,
                updatedAt = cycle.updatedAt,
            ),
            days = cycle.days.map { day ->
                CycleDayBackup(
                    id = day.id,
                    cycleId = day.cycleId,
                    dayNumber = day.dayNumber,
                    name = day.name,
                    routineId = day.routineId,
                    isRestDay = day.isRestDay,
                    echoLevel = day.echoLevel?.name,
                    eccentricLoadPercent = day.eccentricLoadPercent,
                    weightProgressionPercent = day.weightProgressionPercent,
                    repModifier = day.repModifier,
                    restTimeOverrideSeconds = day.restTimeOverrideSeconds,
                )
            },
            progress = progress?.let {
                CycleProgressBackup(
                    id = it.id,
                    cycleId = it.cycleId,
                    currentDayNumber = it.currentDayNumber,
                    lastCompletedDate = it.lastCompletedDate,
                    cycleStartDate = it.cycleStartDate,
                    lastAdvancedAt = it.lastAdvancedAt,
                    completedDays = intSetToJson(it.completedDays),
                    missedDays = intSetToJson(it.missedDays),
                    rotationCount = it.rotationCount,
                )
            },
            progression = progression?.let {
                CycleProgressionBackup(
                    cycleId = it.cycleId,
                    frequencyCycles = it.frequencyCycles,
                    weightIncreasePercent = it.weightIncreasePercent,
                    echoLevelIncrease = if (it.echoLevelIncrease) 1 else 0,
                    eccentricLoadIncreasePercent = it.eccentricLoadIncreasePercent,
                )
            },
        )
        withContext(Dispatchers.IO) {
            val localVersion = cycle.updatedAt ?: cycle.createdAt
            queries.insertCycleConflictDraftIfAbsent(
                id = "${cycle.id}:$localVersion",
                cycleId = cycle.id,
                originalProfileId = cycle.profileId,
                rejectedUpdatedAt = rejectedUpdatedAt,
                payloadJson = json.encodeToString(payload),
                createdAt = currentTimeMillis(),
                resolution = null,
            )
        }
    }

    override suspend fun getCycleConflictDrafts(profileId: String): List<CycleConflictDraft> = withContext(Dispatchers.IO) {
        queries.selectUnresolvedCycleConflictDrafts(profileId).executeAsList().map { row ->
            val payload = try {
                json.decodeFromString<PersistedCycleDraftPayload>(row.payload_json)
            } catch (error: Exception) {
                throw IllegalStateException("Cycle conflict draft ${row.id} is unreadable", error)
            }
            val cycle = payload.cycle
            CycleConflictDraft(
                    id = row.id,
                    cycleId = row.cycle_id,
                    rejectedUpdatedAt = row.rejected_updated_at,
                    cycle = TrainingCycle(
                        id = cycle.id,
                        name = cycle.name,
                        description = cycle.description,
                        days = payload.days.map { day ->
                            CycleDay(
                                id = day.id,
                                cycleId = day.cycleId,
                                dayNumber = day.dayNumber,
                                name = day.name,
                                routineId = day.routineId,
                                isRestDay = day.isRestDay,
                                echoLevel = day.echoLevel?.let { EchoLevel.valueOf(it) },
                                eccentricLoadPercent = day.eccentricLoadPercent,
                                weightProgressionPercent = day.weightProgressionPercent,
                                repModifier = day.repModifier,
                                restTimeOverrideSeconds = day.restTimeOverrideSeconds,
                            )
                        },
                        createdAt = cycle.createdAt,
                        isActive = false,
                        weekNumber = cycle.weekNumber,
                        profileId = cycle.profileId ?: row.original_profile_id,
                        templateId = cycle.templateId,
                        updatedAt = cycle.updatedAt,
                    ),
                )
        }
    }

    override suspend fun keepServerCycle(draftId: String) {
        withContext(Dispatchers.IO) {
            queries.resolveCycleConflictDraft(resolution = "KEEP_SERVER", id = draftId)
        }
    }

    override suspend fun saveCycleDraftAsCopy(draftId: String): String? = withContext(Dispatchers.IO) {
        var savedId: String? = null
        db.transaction {
            val row = queries.selectCycleConflictDraftById(draftId).executeAsOneOrNull()
                ?.takeIf { it.resolution == null }
                ?: return@transaction
            val payload = try {
                json.decodeFromString<PersistedCycleDraftPayload>(row.payload_json)
            } catch (error: Exception) {
                throw IllegalStateException("Cycle conflict draft $draftId is unreadable", error)
            }
            val source = payload.cycle
            val newCycleId = generateUUID()
            val now = currentTimeMillis()
            queries.insertTrainingCycle(
                id = newCycleId,
                name = "${source.name} (Recovered)",
                description = source.description,
                created_at = now,
                is_active = 0L,
                profile_id = row.original_profile_id,
                template_id = source.templateId,
                week_number = source.weekNumber.toLong(),
                updatedAt = now,
            )
            val accountId = queries.getProfileById(row.original_profile_id).executeAsOneOrNull()?.supabase_user_id
            queries.insertCycleSyncState(
                cycleId = newCycleId,
                profileId = row.original_profile_id,
                accountId = accountId,
                dirtyGeneration = 1L,
                acknowledgedGeneration = 0L,
                pendingDeleteUpdatedAt = null,
                pendingDeleteGeneration = null,
            )
            payload.days.forEach { day ->
                queries.insertCycleDay(
                    id = generateUUID(),
                    cycle_id = newCycleId,
                    day_number = day.dayNumber.toLong(),
                    name = day.name,
                    routine_id = day.routineId,
                    is_rest_day = if (day.isRestDay) 1L else 0L,
                    echo_level = day.echoLevel,
                    eccentric_load_percent = day.eccentricLoadPercent?.toLong(),
                    weight_progression_percent = day.weightProgressionPercent?.toDouble(),
                    rep_modifier = day.repModifier?.toLong(),
                    rest_time_override_seconds = day.restTimeOverrideSeconds?.toLong(),
                )
            }
            payload.progress?.let { progress ->
                queries.insertCycleProgress(
                    id = generateUUID(),
                    cycle_id = newCycleId,
                    current_day_number = progress.currentDayNumber.toLong(),
                    last_completed_date = progress.lastCompletedDate,
                    cycle_start_date = progress.cycleStartDate,
                    last_advanced_at = progress.lastAdvancedAt,
                    completed_days = progress.completedDays,
                    missed_days = progress.missedDays,
                    rotation_count = progress.rotationCount.toLong(),
                )
            }
            payload.progression?.let { progression ->
                queries.upsertCycleProgression(
                    cycle_id = newCycleId,
                    frequency_cycles = progression.frequencyCycles.toLong(),
                    weight_increase_percent = progression.weightIncreasePercent?.toDouble(),
                    echo_level_increase = progression.echoLevelIncrease.toLong(),
                    eccentric_load_increase_percent = progression.eccentricLoadIncreasePercent?.toLong(),
                )
            }
            queries.resolveCycleConflictDraft(resolution = "SAVED_COPY", id = draftId)
            savedId = newCycleId
        }
        savedId
    }

    // ==================== Training Cycles ====================

    override fun getAllCycles(profileId: String): Flow<List<TrainingCycle>> = queries.selectAllTrainingCycles(profileId = profileId) {
            id,
            name,
            description,
            created_at,
            is_active,
            profile_id,
            _deletedAt,
            template_id,
            week_number,
            updatedAt,
            _serverUpdatedAt,
        ->
        TrainingCycle(
            id = id,
            name = name,
            description = description,
            days = emptyList(), // Will be loaded separately when needed
            createdAt = created_at,
            isActive = is_active == 1L,
            profileId = profile_id,
            templateId = template_id,
            weekNumber = week_number.toInt(),
            updatedAt = updatedAt,
        )
    }
        .asFlow()
        .mapToList(Dispatchers.IO)
        .map { basicCycles ->
            // Load days for each cycle
            basicCycles.map { cycle ->
                val days = getCycleDays(cycle.id)
                cycle.copy(days = days)
            }
        }

    override suspend fun getCycleById(cycleId: String): TrainingCycle? {
        return withContext(Dispatchers.IO) {
            val row = queries.selectTrainingCycleById(cycleId).executeAsOneOrNull()
                ?: return@withContext null
            if (row.deletedAt != null) return@withContext null

            val days = getCycleDays(cycleId)

            TrainingCycle(
                id = row.id,
                name = row.name,
                description = row.description,
                days = days,
                createdAt = row.created_at,
                isActive = row.is_active == 1L,
                profileId = row.profile_id,
                templateId = row.template_id,
                weekNumber = row.week_number.toInt(),
                updatedAt = row.updatedAt,
            )
        }
    }

    override fun getActiveCycle(profileId: String): Flow<TrainingCycle?> = queries.selectActiveTrainingCycle(profileId = profileId) {
            id,
            name,
            description,
            created_at,
            is_active,
            profile_id,
            _deletedAt,
            template_id,
            week_number,
            updatedAt,
            _serverUpdatedAt,
        ->
        TrainingCycle(
            id = id,
            name = name,
            description = description,
            days = emptyList(), // Will be loaded separately
            createdAt = created_at,
            isActive = is_active == 1L,
            profileId = profile_id,
            templateId = template_id,
            weekNumber = week_number.toInt(),
            updatedAt = updatedAt,
        )
    }
        .asFlow()
        .mapToOneOrNull(Dispatchers.IO)
        .map { cycle ->
            cycle?.let {
                val days = getCycleDays(it.id)
                it.copy(days = days)
            }
        }

    override suspend fun getCycleWithProgress(cycleId: String): Pair<TrainingCycle, CycleProgress?>? {
        return withContext(Dispatchers.IO) {
            val cycle = getCycleById(cycleId) ?: return@withContext null
            val progress = getCycleProgress(cycleId)
            Pair(cycle, progress)
        }
    }

    override suspend fun saveCycle(cycle: TrainingCycle) {
        withContext(Dispatchers.IO) {
            db.transaction {
                val editTimestamp = cycle.updatedAt ?: maxOf(cycle.createdAt, currentTimeMillis())
                // Insert the training cycle
                queries.insertTrainingCycle(
                    id = cycle.id,
                    name = cycle.name,
                    description = cycle.description,
                    created_at = cycle.createdAt,
                    is_active = if (cycle.isActive) 1L else 0L,
                    profile_id = cycle.profileId,
                    template_id = cycle.templateId,
                    week_number = cycle.weekNumber.toLong(),
                    updatedAt = editTimestamp,
                )
                val accountId = queries.getProfileById(cycle.profileId).executeAsOneOrNull()?.supabase_user_id
                queries.insertCycleSyncState(
                    cycleId = cycle.id,
                    profileId = cycle.profileId,
                    accountId = accountId,
                    dirtyGeneration = 1L,
                    acknowledgedGeneration = 0L,
                    pendingDeleteUpdatedAt = null,
                    pendingDeleteGeneration = null,
                )

                // Insert all days
                cycle.days.forEach { day ->
                    queries.insertCycleDay(
                        id = day.id,
                        cycle_id = cycle.id,
                        day_number = day.dayNumber.toLong(),
                        name = day.name,
                        routine_id = day.routineId,
                        is_rest_day = if (day.isRestDay) 1L else 0L,
                        echo_level = day.echoLevel?.name,
                        eccentric_load_percent = day.eccentricLoadPercent?.toLong(),
                        weight_progression_percent = day.weightProgressionPercent?.toDouble(),
                        rep_modifier = day.repModifier?.toLong(),
                        rest_time_override_seconds = day.restTimeOverrideSeconds?.toLong(),
                    )
                }

                // If cycle is active, deactivate all others and initialize progress
                if (cycle.isActive) {
                    val previouslyActive = queries.selectTrainingCyclesByProfile(cycle.profileId)
                        .executeAsList()
                        .filter { it.id != cycle.id && it.is_active == 1L }
                    queries.setActiveTrainingCycle(cycle.id, profileId = cycle.profileId)
                    previouslyActive.forEach { markCycleEdited(it.id, cycle.profileId) }
                    // Initialize progress if it doesn't exist (uses INSERT OR IGNORE to prevent UNIQUE constraint violations)
                    val now = currentTimeMillis()
                    val progressId = generateUUID()
                    queries.insertCycleProgressIfNotExists(
                        id = progressId,
                        cycle_id = cycle.id,
                        current_day_number = 1L,
                        last_completed_date = null,
                        cycle_start_date = now,
                        last_advanced_at = null,
                        completed_days = null,
                        missed_days = null,
                        rotation_count = 0L,
                    )
                }
            }
        }
    }

    override suspend fun updateCycle(cycle: TrainingCycle) {
        withContext(Dispatchers.IO) {
            db.transaction {
                val editTimestamp = nextEditTimestamp(cycle.id, cycle.updatedAt ?: currentTimeMillis())
                // Update the training cycle
                queries.updateTrainingCycle(
                    name = cycle.name,
                    description = cycle.description,
                    is_active = if (cycle.isActive) 1L else 0L,
                    template_id = cycle.templateId,
                    week_number = cycle.weekNumber.toLong(),
                    updatedAt = editTimestamp,
                    id = cycle.id,
                )

                // Delete existing days and re-insert
                queries.deleteCycleDaysByCycle(cycle.id)

                // Insert all days
                cycle.days.forEach { day ->
                    queries.insertCycleDay(
                        id = day.id,
                        cycle_id = cycle.id,
                        day_number = day.dayNumber.toLong(),
                        name = day.name,
                        routine_id = day.routineId,
                        is_rest_day = if (day.isRestDay) 1L else 0L,
                        echo_level = day.echoLevel?.name,
                        eccentric_load_percent = day.eccentricLoadPercent?.toLong(),
                        weight_progression_percent = day.weightProgressionPercent?.toDouble(),
                        rep_modifier = day.repModifier?.toLong(),
                        rest_time_override_seconds = day.restTimeOverrideSeconds?.toLong(),
                    )
                }

                // If cycle is active, deactivate all others
                if (cycle.isActive) {
                    val previouslyActive = queries.selectTrainingCyclesByProfile(cycle.profileId)
                        .executeAsList()
                        .filter { it.id != cycle.id && it.is_active == 1L }
                    queries.setActiveTrainingCycle(cycle.id, profileId = cycle.profileId)
                    previouslyActive.forEach { markCycleEdited(it.id, cycle.profileId) }
                }
                ensureCycleSyncState(cycle.id, cycle.profileId)
                queries.markCycleDirty(profileId = cycle.profileId, cycleId = cycle.id)
            }
        }
    }

    override suspend fun updateWeekNumber(cycleId: String, weekNumber: Int) {
        withContext(Dispatchers.IO) {
            db.transaction {
                val cycle = queries.selectTrainingCycleById(cycleId).executeAsOneOrNull() ?: return@transaction
                queries.updateTrainingCycleWeekNumber(
                    week_number = weekNumber.toLong(),
                    updatedAt = nextEditTimestamp(cycleId),
                    id = cycleId,
                )
                ensureCycleSyncState(cycleId, cycle.profile_id)
                queries.markCycleDirty(profileId = cycle.profile_id, cycleId = cycleId)
            }
        }
    }

    override suspend fun setActiveCycle(cycleId: String, profileId: String) {
        withContext(Dispatchers.IO) {
            db.transaction {
                val before = queries.selectTrainingCyclesByProfile(profileId).executeAsList()
                val selected = before.firstOrNull { it.id == cycleId } ?: return@transaction
                // Re-selecting the already-active cycle is a UI no-op. Resetting its nested
                // progress here would be an unversioned mutation and could be lost to a stale ack.
                if (selected.is_active == 1L) return@transaction
                // Set this cycle as active and all others as inactive
                queries.setActiveTrainingCycle(cycleId, profileId = profileId)
                before.filter { (it.id == cycleId) != (it.is_active == 1L) }.forEach { changed ->
                    markCycleEdited(changed.id, profileId)
                }

                // Initialize progress if it doesn't exist (uses INSERT OR IGNORE to prevent race conditions)
                val now = currentTimeMillis()
                val progressId = generateUUID()
                queries.insertCycleProgressIfNotExists(
                    id = progressId,
                    cycle_id = cycleId,
                    current_day_number = 1L,
                    last_completed_date = null,
                    cycle_start_date = now,
                    last_advanced_at = null,
                    completed_days = null,
                    missed_days = null,
                    rotation_count = 0L,
                )

                // Always reset progress on activation so deactivate/reactivate starts fresh
                queries.resetCycleProgress(
                    cycle_start_date = now,
                    cycle_id = cycleId,
                )
            }
        }
    }

    override suspend fun clearActiveCycle(profileId: String) {
        withContext(Dispatchers.IO) {
            db.transaction {
                val active = queries.selectTrainingCyclesByProfile(profileId).executeAsList().filter { it.is_active == 1L }
                queries.deactivateAllCycles(profileId = profileId)
                active.forEach { markCycleEdited(it.id, profileId) }
            }
        }
    }

    override suspend fun deleteCycle(cycleId: String) {
        withContext(Dispatchers.IO) {
            db.transaction {
                val cycle = queries.selectTrainingCycleById(cycleId).executeAsOneOrNull() ?: return@transaction
                if (cycle.deletedAt != null) return@transaction
                val deletedAt = nextEditTimestamp(cycleId)
                val accountId = queries.getProfileById(cycle.profile_id).executeAsOneOrNull()?.supabase_user_id
                // Capture the immutable owner at the local delete boundary. A later login
                // cannot bind a legacy NULL deletion for upload.
                ensureCycleSyncState(cycleId, cycle.profile_id)
                queries.softDeleteTrainingCycle(deletedAt = deletedAt, updatedAt = deletedAt, id = cycleId)
                queries.markCycleDeletionPending(
                    profileId = cycle.profile_id,
                    accountId = accountId,
                    deletedAt = deletedAt,
                    cycleId = cycleId,
                )
            }
        }
    }

    // ==================== Cycle Days ====================

    override suspend fun getCycleDays(cycleId: String): List<CycleDay> = withContext(Dispatchers.IO) {
        queries.selectCycleDaysByCycle(cycleId, ::mapToCycleDay)
            .executeAsList()
    }

    override suspend fun addCycleDay(day: CycleDay) {
        withContext(Dispatchers.IO) {
            db.transaction {
                queries.insertCycleDay(
                    id = day.id,
                    cycle_id = day.cycleId,
                    day_number = day.dayNumber.toLong(),
                    name = day.name,
                    routine_id = day.routineId,
                    is_rest_day = if (day.isRestDay) 1L else 0L,
                    echo_level = day.echoLevel?.name,
                    eccentric_load_percent = day.eccentricLoadPercent?.toLong(),
                    weight_progression_percent = day.weightProgressionPercent?.toDouble(),
                    rep_modifier = day.repModifier?.toLong(),
                    rest_time_override_seconds = day.restTimeOverrideSeconds?.toLong(),
                )
                val cycle = queries.selectTrainingCycleById(day.cycleId).executeAsOne()
                markCycleEdited(day.cycleId, cycle.profile_id)
            }
        }
    }

    override suspend fun updateCycleDay(day: CycleDay) {
        withContext(Dispatchers.IO) {
            db.transaction {
                queries.updateCycleDay(
                    day_number = day.dayNumber.toLong(),
                    name = day.name,
                    routine_id = day.routineId,
                    is_rest_day = if (day.isRestDay) 1L else 0L,
                    echo_level = day.echoLevel?.name,
                    eccentric_load_percent = day.eccentricLoadPercent?.toLong(),
                    weight_progression_percent = day.weightProgressionPercent?.toDouble(),
                    rep_modifier = day.repModifier?.toLong(),
                    rest_time_override_seconds = day.restTimeOverrideSeconds?.toLong(),
                    id = day.id,
                )
                val cycle = queries.selectTrainingCycleById(day.cycleId).executeAsOne()
                markCycleEdited(day.cycleId, cycle.profile_id)
            }
        }
    }

    override suspend fun deleteCycleDay(dayId: String) {
        withContext(Dispatchers.IO) {
            db.transaction {
                val day = queries.selectCycleDayById(dayId).executeAsOneOrNull() ?: return@transaction
                val cycle = queries.selectTrainingCycleById(day.cycle_id).executeAsOne()
                queries.deleteCycleDay(dayId)
                markCycleEdited(day.cycle_id, cycle.profile_id)
            }
        }
    }

    override suspend fun reorderCycleDays(cycleId: String, dayIds: List<String>) {
        withContext(Dispatchers.IO) {
            db.transaction {
                val dayMap = queries.selectCycleDaysByCycle(cycleId).executeAsList().associateBy { it.id }
                dayIds.forEachIndexed { index, dayId ->
                    val day = dayMap[dayId] ?: return@forEachIndexed
                    queries.updateCycleDay(
                        day_number = (index + 1).toLong(),
                        name = day.name,
                        routine_id = day.routine_id,
                        is_rest_day = day.is_rest_day,
                        echo_level = day.echo_level,
                        eccentric_load_percent = day.eccentric_load_percent,
                        weight_progression_percent = day.weight_progression_percent,
                        rep_modifier = day.rep_modifier,
                        rest_time_override_seconds = day.rest_time_override_seconds,
                        id = day.id,
                    )
                }
                val cycle = queries.selectTrainingCycleById(cycleId).executeAsOne()
                markCycleEdited(cycleId, cycle.profile_id)
            }
        }
    }

    // ==================== Cycle Progress ====================

    override suspend fun getCycleProgress(cycleId: String): CycleProgress? = withContext(Dispatchers.IO) {
        queries.selectCycleProgressByCycle(cycleId, ::mapToCycleProgress)
            .executeAsOneOrNull()
    }

    override suspend fun initializeProgress(cycleId: String): CycleProgress = withContext(Dispatchers.IO) {
        val now = currentTimeMillis()
        val progressId = generateUUID()

        val progress = CycleProgress(
            id = progressId,
            cycleId = cycleId,
            currentDayNumber = 1,
            lastCompletedDate = null,
            cycleStartDate = now,
            lastAdvancedAt = null,
            completedDays = emptySet(),
            missedDays = emptySet(),
            rotationCount = 0,
        )

        db.transaction {
            queries.insertCycleProgress(
                id = progress.id,
                cycle_id = progress.cycleId,
                current_day_number = progress.currentDayNumber.toLong(),
                last_completed_date = progress.lastCompletedDate,
                cycle_start_date = progress.cycleStartDate,
                last_advanced_at = progress.lastAdvancedAt,
                completed_days = intSetToJson(progress.completedDays),
                missed_days = intSetToJson(progress.missedDays),
                rotation_count = progress.rotationCount.toLong(),
            )
            markStoredCycleEdited(cycleId)
        }

        progress
    }

    override suspend fun advanceToNextDay(cycleId: String): Int {
        return withContext(Dispatchers.IO) {
            val progress = getCycleProgress(cycleId)
                ?: throw IllegalStateException("No progress found for cycle $cycleId")

            val cycle = getCycleById(cycleId)
                ?: throw IllegalStateException("Cycle not found: $cycleId")

            if (cycle.days.isEmpty()) {
                return@withContext progress.currentDayNumber
            }

            val updated = progress.advanceToNextDay(
                totalDays = cycle.days.size,
                markMissed = false,
            )

            db.transaction {
                queries.updateCycleProgress(
                    current_day_number = updated.currentDayNumber.toLong(),
                    last_completed_date = updated.lastCompletedDate,
                    cycle_start_date = updated.cycleStartDate,
                    last_advanced_at = updated.lastAdvancedAt,
                    completed_days = intSetToJson(updated.completedDays),
                    missed_days = intSetToJson(updated.missedDays),
                    rotation_count = updated.rotationCount.toLong(),
                    cycle_id = cycleId,
                )
                markStoredCycleEdited(cycleId)
            }

            updated.currentDayNumber
        }
    }

    override suspend fun resetProgress(cycleId: String) {
        withContext(Dispatchers.IO) {
            val now = currentTimeMillis()

            db.transaction {
                queries.resetCycleProgress(cycle_start_date = now, cycle_id = cycleId)
                markStoredCycleEdited(cycleId)
            }
        }
    }

    override suspend fun jumpToDay(cycleId: String, dayNumber: Int) {
        withContext(Dispatchers.IO) {
            val progress = getCycleProgress(cycleId)
                ?: throw IllegalStateException("No progress found for cycle $cycleId")

            db.transaction {
                queries.updateCycleProgress(
                    current_day_number = dayNumber.toLong(),
                    last_completed_date = progress.lastCompletedDate,
                    cycle_start_date = progress.cycleStartDate,
                    last_advanced_at = progress.lastAdvancedAt,
                    completed_days = intSetToJson(progress.completedDays),
                    missed_days = intSetToJson(progress.missedDays),
                    rotation_count = progress.rotationCount.toLong(),
                    cycle_id = cycleId,
                )
                markStoredCycleEdited(cycleId)
            }
        }
    }

    override suspend fun markDayCompleted(cycleId: String) {
        withContext(Dispatchers.IO) {
            val progress = getCycleProgress(cycleId)
                ?: throw IllegalStateException("No progress found for cycle $cycleId")

            val now = currentTimeMillis()
            val updatedCompletedDays = progress.completedDays + progress.currentDayNumber

            db.transaction {
                queries.updateCycleProgress(
                    current_day_number = progress.currentDayNumber.toLong(),
                    last_completed_date = now,
                    cycle_start_date = progress.cycleStartDate,
                    last_advanced_at = progress.lastAdvancedAt,
                    completed_days = intSetToJson(updatedCompletedDays),
                    missed_days = intSetToJson(progress.missedDays),
                    rotation_count = progress.rotationCount.toLong(),
                    cycle_id = cycleId,
                )
                markStoredCycleEdited(cycleId)
            }
        }
    }

    override suspend fun updateCycleProgress(progress: CycleProgress) {
        withContext(Dispatchers.IO) {
            db.transaction {
                queries.updateCycleProgress(
                    current_day_number = progress.currentDayNumber.toLong(),
                    last_completed_date = progress.lastCompletedDate,
                    cycle_start_date = progress.cycleStartDate,
                    last_advanced_at = progress.lastAdvancedAt,
                    completed_days = intSetToJson(progress.completedDays),
                    missed_days = intSetToJson(progress.missedDays),
                    rotation_count = progress.rotationCount.toLong(),
                    cycle_id = progress.cycleId,
                )
                markStoredCycleEdited(progress.cycleId)
            }
        }
    }

    override suspend fun checkAndAutoAdvance(cycleId: String): CycleProgress? {
        return withContext(Dispatchers.IO) {
            val progress = getCycleProgress(cycleId) ?: return@withContext null
            val cycle = getCycleById(cycleId) ?: return@withContext null

            if (cycle.days.isEmpty()) {
                return@withContext progress
            }

            val daysToAdvance = progress.pendingAutoAdvanceDays()

            if (daysToAdvance > 0) {
                var updated = progress
                repeat(daysToAdvance) {
                    // Only mark as missed if the current day wasn't already completed
                    val shouldMarkMissed = updated.currentDayNumber !in updated.completedDays
                    updated = updated.advanceToNextDay(cycle.days.size, markMissed = shouldMarkMissed)
                }

                db.transaction {
                    queries.updateCycleProgress(
                        current_day_number = updated.currentDayNumber.toLong(),
                        last_completed_date = updated.lastCompletedDate,
                        cycle_start_date = updated.cycleStartDate,
                        last_advanced_at = updated.lastAdvancedAt,
                        completed_days = intSetToJson(updated.completedDays),
                        missed_days = intSetToJson(updated.missedDays),
                        rotation_count = updated.rotationCount.toLong(),
                        cycle_id = cycleId,
                    )
                    markStoredCycleEdited(cycleId)
                }

                updated
            } else {
                progress
            }
        }
    }

    // ==================== Cycle Progression ====================

    override suspend fun getCycleProgression(cycleId: String): CycleProgression? = withContext(Dispatchers.IO) {
        queries.selectCycleProgression(cycleId).executeAsOneOrNull()?.let { row ->
            CycleProgression(
                cycleId = row.cycle_id,
                frequencyCycles = row.frequency_cycles.toInt(),
                weightIncreasePercent = row.weight_increase_percent?.toFloat(),
                echoLevelIncrease = row.echo_level_increase != 0L,
                eccentricLoadIncreasePercent = row.eccentric_load_increase_percent?.toInt(),
            )
        }
    }

    override suspend fun saveCycleProgression(progression: CycleProgression) {
        withContext(Dispatchers.IO) {
            db.transaction {
                queries.upsertCycleProgression(
                    cycle_id = progression.cycleId,
                    frequency_cycles = progression.frequencyCycles.toLong(),
                    weight_increase_percent = progression.weightIncreasePercent?.toDouble(),
                    echo_level_increase = if (progression.echoLevelIncrease) 1L else 0L,
                    eccentric_load_increase_percent = progression.eccentricLoadIncreasePercent?.toLong(),
                )
                markStoredCycleEdited(progression.cycleId)
            }
        }
    }

    override suspend fun deleteCycleProgression(cycleId: String) {
        withContext(Dispatchers.IO) {
            db.transaction {
                queries.deleteCycleProgression(cycleId)
                markStoredCycleEdited(cycleId)
            }
        }
    }

    override suspend fun getCycleItems(cycleId: String): List<CycleItem> = withContext(Dispatchers.IO) {
        val days = getCycleDays(cycleId)
        val routineIds = days.mapNotNull { it.routineId }.distinct()

        // Fetch routine info for all referenced routines
        // Triple: name, exerciseCount, exerciseNames
        val routineInfo = mutableMapOf<String, Triple<String, Int, List<String>>>()
        routineIds.forEach { routineId ->
            queries.selectRoutineById(routineId).executeAsOneOrNull()?.let { routine ->
                val exercises = queries.selectExercisesByRoutine(routineId).executeAsList()
                routineInfo[routineId] = Triple(
                    routine.name,
                    exercises.size,
                    exercises.map { it.exerciseName },
                )
            }
        }

        days.map { day ->
            val info = day.routineId?.let { routineInfo[it] }
            CycleItem.fromCycleDay(
                day = day,
                routineName = info?.first,
                exerciseCount = info?.second ?: 0,
                exerciseNames = info?.third ?: emptyList(),
            )
        }
    }
}
