package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.preferences.ProfilePreferencesValidator
import com.devil.phoenixproject.data.repository.ProfilePreferencesRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.sync.PortalSyncAdapter
import com.devil.phoenixproject.database.CompletedSet
import com.devil.phoenixproject.database.CycleDay
import com.devil.phoenixproject.database.CycleProgress
import com.devil.phoenixproject.database.CycleProgression
import com.devil.phoenixproject.database.CycleConflictDraft
import com.devil.phoenixproject.database.CycleSyncState
import com.devil.phoenixproject.database.EarnedBadge
import com.devil.phoenixproject.database.Exercise
import com.devil.phoenixproject.data.preferences.PendingProfileDeletionStore
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.database.GamificationStats
import com.devil.phoenixproject.database.MetricSample
import com.devil.phoenixproject.database.PersonalRecord
import com.devil.phoenixproject.database.PlannedSet
import com.devil.phoenixproject.database.ProgressionEvent
import com.devil.phoenixproject.database.Routine
import com.devil.phoenixproject.database.RoutineExercise
import com.devil.phoenixproject.database.RoutineGroup
import com.devil.phoenixproject.database.PendingProfileRecovery
import com.devil.phoenixproject.database.OwnershipTransferOutbox
import com.devil.phoenixproject.database.AppliedOwnershipEvent
import com.devil.phoenixproject.database.LocalOwnershipClaim
import com.devil.phoenixproject.database.SessionNotes
import com.devil.phoenixproject.database.StreakHistory
import com.devil.phoenixproject.database.Superset
import com.devil.phoenixproject.database.TrainingCycle
import com.devil.phoenixproject.database.UserProfile
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.database.WorkoutSession
import com.devil.phoenixproject.database.WorkoutDeletion
import com.devil.phoenixproject.domain.model.CoreProfilePreferences
import com.devil.phoenixproject.domain.model.LedPreferences
import com.devil.phoenixproject.domain.model.ProfilePreferenceSection
import com.devil.phoenixproject.domain.model.ProfilePreferenceValidity
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackPreferences
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.UserProfilePreferences
import com.devil.phoenixproject.domain.model.VbtPreferences
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.util.BaseDataBackupManager.Companion.IMPORT_BATCH_SIZE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Markers of a constraint violation, matched case-insensitively against the exception
 * chain's messages because each platform driver (Android framework, JDBC, native SQLiter)
 * reports SQLite result codes through its own exception type and wording.
 */
private val CONSTRAINT_VIOLATION_MARKERS = listOf(
    "sqlite_constraint", "constraint failed", "constraint violation",
)

/**
 * Whether a per-row restore failure may be skipped. Allow-list: only a row that does not
 * parse or that violates a constraint affects that row alone. Everything else (disk full,
 * I/O, corruption, driver errors, bugs such as a null dereference) and cancellation aborts
 * the whole restore, so later rows are never written after an unknown failure.
 */
internal fun isSkippableRestoreRowFailure(failure: Throwable): Boolean {
    if (failure is CancellationException) return false
    val chain = generateSequence(failure) { it.cause }.take(8).toList()
    if (chain.any { it is SerializationException }) return true
    val messages = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
    return CONSTRAINT_VIOLATION_MARKERS.any { it in messages }
}

/**
 * Platform-agnostic interface for backup/restore operations.
 * Platform implementations handle file I/O and sharing.
 */
interface DataBackupManager {
    /**
     * Export all data to a BackupData object
     */
    suspend fun exportAllData(): BackupData

    /**
     * Export all data as a JSON string
     */
    suspend fun exportToJson(): String

    /**
     * Import data from a JSON string
     * Uses "skip duplicates" strategy - existing records are not overwritten
     */
    suspend fun importFromJson(jsonString: String): Result<ImportResult>

    /**
     * Save backup to platform-specific location (Downloads on Android, Documents on iOS)
     * Returns the file path on success
     */
    suspend fun saveToFile(backup: BackupData): Result<String>

    /**
     * Export all data to a file using streaming JSON to avoid OOM on large datasets.
     * Writes data incrementally to disk -- peak memory is ~1 session's worth of metrics.
     * Returns the final file path on success.
     */
    suspend fun exportToFile(onProgress: (BackupProgress) -> Unit = {}): Result<String>

    /**
     * Import data from a file path
     */
    suspend fun importFromFile(filePath: String): Result<ImportResult>

    /**
     * Get shareable content (JSON string) for sharing via platform share sheet
     */
    suspend fun getShareableContent(): String

    /**
     * Share backup via platform share sheet (Android Intent, iOS UIActivityViewController)
     */
    suspend fun shareBackup()

    /**
     * Export a single workout session (and its metrics) to a JSON file on the device filesystem.
     * The output is a valid BackupData with a single-element sessions list, compatible with import.
     * Returns the file path of the written backup on success.
     */
    suspend fun exportSession(sessionId: String): Result<String>

    /**
     * Export every WorkoutSession row that shares the given [routineSessionId] (plus each
     * session's metrics and completed sets) to a single JSON file on the device filesystem.
     * Issue #525: routine workouts previously produced one backup file per set; this collapses
     * them to one file per completed routine.
     * Returns the file path of the written backup on success, or failure if no sessions match.
     */
    suspend fun exportRoutine(routineSessionId: String): Result<String>

    /**
     * Returns file count and total size of session auto-backup files on disk.
     */
    suspend fun getBackupStats(): BackupStats

    /**
     * Open the backup folder in the platform's file manager.
     * - Android: launches an ACTION_VIEW intent for the directory
     * - iOS: not directly supported; implementations may show a share sheet for the folder
     */
    fun openBackupFolder()
}

/**
 * Common implementation that handles database operations.
 * Platform implementations extend this and add file I/O.
 */
abstract class BaseDataBackupManager(
    private val database: PhoenixDatabase,
    private val profilePreferencesRepository: ProfilePreferencesRepository,
    private val userProfileRepository: UserProfileRepository,
    /** Settings-held sync state a restore must reset (pull cursors, routine-group repair). */
    private val portalTokenStorage: PortalTokenStorage? = null,
    /** Settings-held one-shot work markers a restore must clear (see resetOneShotWorkAfterRestore). */
    private val preferencesManager: PreferencesManager? = null,
    /** Profiles a permanent delete (PR 20) is still pushing; kept out of backups both ways. */
    private val pendingProfileDeletionStore: PendingProfileDeletionStore? = null,
) : DataBackupManager {

    protected val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true // Forward compatibility
        encodeDefaults = true
        explicitNulls = false
    }

    private val queries get() = database.phoenixDatabaseQueries

    /**
     * Create a platform-specific JSON writer for streaming export.
     * The writer should point to a temporary/cache file location.
     */
    protected abstract fun createBackupWriter(): BackupJsonWriter

    /**
     * Platform-specific finalization after streaming export.
     * Copies/moves the temp file to the platform's standard backup location.
     */
    protected abstract suspend fun finalizeExport(tempFilePath: String): Result<String>

    /**
     * Returns the platform-specific directory path for session auto-backups.
     * - Android: `getExternalFilesDir("PhoenixBackups")` (no permissions required)
     * - iOS: app Documents directory (UIFileSharingEnabled = true in Info.plist)
     * The directory is created if it does not exist.
     */
    protected abstract fun getSessionBackupDirectory(): String

    /**
     * List the sizes (in bytes) of all files in the session backup directory.
     * Platform subclasses implement using native file enumeration.
     */
    protected abstract fun listBackupFileSizes(): List<Long>

    /**
     * Remove the oldest session backup files, keeping only [keepCount] most recent.
     * Platform subclasses implement using native file/MediaStore enumeration and deletion.
     */
    protected abstract fun pruneOldBackups(keepCount: Int)

    /**
     * Whether full and auto backups include raw per-sample telemetry (MetricSample). Off by
     * default (F-033): the stream is large and rarely needed. Restore still imports samples
     * whenever a file contains them. Platform managers read the user's backup preference.
     */
    protected open val includeRawTelemetryInBackups: Boolean get() = false

    private fun backupPrivacy(): BackupPrivacyMetadata =
        BackupPrivacyMetadata(containsRawTelemetry = includeRawTelemetryInBackups)

    /** One stats row per profile (the table holds one per profile; never `executeAsOneOrNull` over all rows). */
    private fun gamificationStatsByProfile(profileIds: List<String>): List<GamificationStatsBackup> =
        profileIds.mapNotNull { profileId ->
            queries.selectGamificationStats(profileId).executeAsOneOrNull()?.let(::mapGamificationStatsToBackup)
        }

    private fun stockExerciseUserFields(): List<StockExerciseUserFieldsBackup> =
        queries.selectStockExerciseUserFields().executeAsList().map {
            StockExerciseUserFieldsBackup(
                exerciseId = it.id,
                isFavorite = it.isFavorite == 1L,
                mvtOverrideMs = it.mvtOverrideMs?.toFloat(),
                legacyOneRepMaxKg = it.one_rep_max_kg?.toFloat(),
            )
        }

    /**
     * Profiles the user permanently deleted (PR 20): those whose delete is still pushing, and
     * those already finalized (their tombstones outlive the profile row). No backup writes them
     * and no restore brings them back.
     */
    private fun deletedProfileFilter(): DeletedProfileBackupFilter = DeletedProfileBackupFilter(
        queries.selectRemovedProfileIds().executeAsList().toSet() +
            pendingProfileDeletionStore?.read().orEmpty(),
    )

    /** Set for the duration of one streaming export; every array section passes through it. */
    private var activeExportFilter: DeletedProfileBackupFilter? = null

    /** Test seam for deterministic staging I/O failures; production uses private platform temp storage. */
    internal open fun createImportStagingArea(): BackupImportStagingArea = createBackupImportStagingArea()

    companion object {
        /**
         * Maximum number of auto-backup files to retain. Shared between per-session
         * (exportSession) and per-routine (exportRoutine) auto-backup flows.
         */
        const val MAX_ROUTINE_BACKUPS = 90

        /** Batch size for metric sample transactions in streaming import. */
        const val IMPORT_BATCH_SIZE = 5_000

        private val OWNERSHIP_CLAIM_ENTITY_TYPES = setOf("WORKOUT", "ROUTINE", "CYCLE", "PERSONAL_RECORD")

        /**
         * Legacy pull-sync sentinel written into RoutineExercise.exerciseEquipment before
         * migration 39 (#635). Old backup files may still carry it; restore converts it
         * to the explicit isBodyweight flag.
         */
        const val LEGACY_BODYWEIGHT_SENTINEL = "Bodyweight"

        private val ARRAY_BACKUP_SECTIONS = setOf(
            "customExercises",
            "stockExerciseUserFields",
            "gamificationStatsByProfile",
            "userProfiles",
            "profilePreferences",
            "routineGroups",
            "routines",
            "supersets",
            "routineExercises",
            "plannedSets",
            "workoutDeletions",
            "pendingProfileRecoveries",
            "ownershipTransfers",
            "appliedOwnershipEvents",
            "localOwnershipClaims",
            "profileExerciseBaselines",
            "trainingCycles",
            "cycleDays",
            "cycleProgress",
            "cycleProgressions",
            "cycleSyncStates",
            "cycleConflictDrafts",
            "workoutSessions",
            "metricSamples",
            "completedSets",
            "personalRecords",
            "progressionEvents",
            "earnedBadges",
            "streakHistory",
            "sessionNotes",
        )

        /** Parents and resurrection guards always precede their dependent live rows. */
        private val RESTORE_SECTION_ORDER = listOf(
            "userProfiles",
            "customExercises",
            "stockExerciseUserFields",
            "profilePreferences",
            "pendingProfileRecoveries",
            "ownershipTransfers",
            "appliedOwnershipEvents",
            "localOwnershipClaims",
            "workoutDeletions",
            "routineGroups",
            "routines",
            "supersets",
            "routineExercises",
            "plannedSets",
            "profileExerciseBaselines",
            "trainingCycles",
            "cycleDays",
            "cycleProgress",
            "cycleProgressions",
            "cycleSyncStates",
            "cycleConflictDrafts",
            "workoutSessions",
            "metricSamples",
            "completedSets",
            "personalRecords",
            "progressionEvents",
            "earnedBadges",
            "streakHistory",
            "gamificationStats",
            "gamificationStatsByProfile",
            "sessionNotes",
            "equipmentRackItems",
        )
    }

    private data class RoutineNameResolutionContext(
        val routineNameById: Map<String, String>,
        val uniqueRoutineNameByExerciseId: Map<String, String>,
        val uniqueRoutineNameByExerciseName: Map<String, String>,
    )

    private data class DeferredProfileRestore(
        val backupVersion: Int,
        val profilePreferences: List<ProfilePreferencesBackup>,
        val legacyRackFieldPresent: Boolean,
        val legacyRackElement: JsonElement?,
        val representedProfileIds: Set<String>,
    )

    private inline fun <reified T> Json.encodeValidBackupSection(
        section: ProfilePreferenceSection<T>,
    ): JsonElement? = if (section.validity is ProfilePreferenceValidity.Valid) {
        encodeToJsonElement(section.value)
    } else {
        null
    }

    private fun UserProfilePreferences.toBackup(): ProfilePreferencesBackup =
        ProfilePreferencesBackup(
            profileId = profileId,
            core = json.encodeValidBackupSection(core),
            rack = json.encodeValidBackupSection(rack),
            workout = json.encodeValidBackupSection(workout),
            led = json.encodeValidBackupSection(led),
            vbt = json.encodeValidBackupSection(vbt),
        )

    // -- Streaming export (Discussion #244 OOM fix) --

    override suspend fun exportToFile(onProgress: (BackupProgress) -> Unit): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cachePath = exportToCache(onProgress)
            onProgress(BackupProgress(BackupPhase.FINALIZING, 0, 0))
            finalizeExport(cachePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e) { "Streaming export failed" }
            Result.failure(e)
        }
    }

    /**
     * Stream export to a cache/temp file. Returns the file path.
     * Used by both exportToFile() and shareBackup().
     */
    protected suspend fun exportToCache(onProgress: (BackupProgress) -> Unit = {}): String {
        val writer = createBackupWriter()
        try {
            writer.open()
            streamExportToWriter(writer, onProgress)
            writer.close()
            return writer.filePath
        } catch (e: Exception) {
            runCatching { writer.close() }
            runCatching { writer.delete() }
            throw e
        }
    }

    // -- Legacy export (kept for backward compatibility) --

    override suspend fun exportAllData(): BackupData {
        val backup = exportAllDataUnfiltered()
        val filter = deletedProfileFilter()
        if (!filter.isActive) return backup
        val data = filter.filterData(json.encodeToJsonElement(BackupContent.serializer(), backup.data).jsonObject)
        return backup.copy(data = json.decodeFromJsonElement(BackupContent.serializer(), data))
    }

    private suspend fun exportAllDataUnfiltered(): BackupData = withContext(Dispatchers.IO) {
        val sessions = queries.selectBackupSessionsSync().executeAsList()

        // IMPORTANT: Load metrics per-session to avoid memory exhaustion on iOS.
        // Loading all metrics at once can cause OOM crashes on iOS due to how the
        // native SQLite driver handles large result sets.
        val metrics = mutableListOf<MetricSample>()
        if (includeRawTelemetryInBackups) {
            for (session in sessions) {
                val sessionMetrics = queries.selectMetricsBySession(session.id).executeAsList()
                metrics.addAll(sessionMetrics)
            }
        }

        // Full backups retain routine tombstones because historical telemetry keeps
        // foreign-key links to the original routine and routine exercise IDs.
        val allRoutines = queries.selectAllRoutinesSync().executeAsList()
        val allRoutineExercises = queries.selectAllRoutineExercisesSync().executeAsList()
        val routines = allRoutines
        val activeRoutineIds = allRoutines.filter { it.deletedAt == null }.mapTo(hashSetOf()) { it.id }
        val routineExercises = allRoutineExercises
        val routineNameResolutionContext = buildRoutineNameResolutionContext(allRoutines, allRoutineExercises)
        // Every table read below fails the export on error (F-041): a backup that silently
        // exports an empty section is worse than a retryable failure.
        val supersets = queries.selectAllSupersetsSync().executeAsList()
        val personalRecords = queries.selectActiveRecordsForBackup().executeAsList().map { pr ->
            mapPersonalRecordToBackup(pr)
        }
        val trainingCycles = queries.selectAllTrainingCyclesSync().executeAsList()
        val cycleDays = trainingCycles.flatMap { cycle ->
            queries.selectCycleDaysByCycle(cycle.id).executeAsList()
        }.map { day ->
            if (day.routine_id != null && day.routine_id !in activeRoutineIds) {
                mapCycleDayToBackup(day).copy(routineId = null)
            } else {
                mapCycleDayToBackup(day)
            }
        }

        val cycleProgress = queries.selectAllCycleProgressSync().executeAsList()
        val cycleProgressions = queries.selectAllCycleProgressionsSync().executeAsList()
        val plannedSets = queries.selectAllPlannedSetsSync().executeAsList()
        val plannedSetIds = plannedSets.mapTo(hashSetOf()) { it.id }
        val completedSets = queries.selectAllCompletedSetsSync().executeAsList()
        val progressionEvents = queries.selectAllProgressionEventsSync().executeAsList()
        val earnedBadges = queries.selectAllEarnedBadgesSync().executeAsList()
        val streakHistory = queries.selectAllStreakHistorySync().executeAsList()
        // Profile identities are a required root of a complete backup. Never emit an
        // apparently successful empty identity/preference payload after a query failure.
        val userProfiles = queries.selectAllUserProfilesSync().executeAsList()
        val gamificationStats = gamificationStatsByProfile(userProfiles.map { it.id })
        val profilePreferences = userProfiles.map { profile ->
            profilePreferencesRepository.get(profile.id).toBackup()
        }
        val customExercises = queries.selectCustomExercises().executeAsList()
        val profileExerciseBaselines = queries.selectAllProfileExerciseBaselines().executeAsList()
        val workoutDeletions = queries.selectAllWorkoutDeletions().executeAsList()
        val pendingProfileRecoveries = queries.selectAllPendingProfileRecoveries().executeAsList()
        val ownershipTransfers = queries.selectAllOwnershipTransfers().executeAsList()
        val appliedOwnershipEvents = queries.selectAllAppliedOwnershipEvents().executeAsList()
        val localOwnershipClaims = queries.selectAllLocalOwnershipClaims().executeAsList()
        val cycleSyncStates = queries.selectAllCycleSyncStates().executeAsList()
        val cycleConflictDrafts = queries.selectAllCycleConflictDrafts().executeAsList()
        val sessionNotes = queries.selectAllSessionNotesSync().executeAsList()
        // Migration 27 added RoutineGroup. Preserve empty groups as legitimate user state.
        val routineGroups = queries.selectAllRoutineGroupsSync().executeAsList()

        val nowMs = KmpUtils.currentTimeMillis()
        BackupData(
            version = CURRENT_BACKUP_VERSION,
            exportedAt = KmpUtils.formatTimestamp(nowMs, "yyyy-MM-dd") + "T" +
                KmpUtils.formatTimestamp(nowMs, "HH:mm:ss") + "Z",
            appVersion = Constants.APP_VERSION,
            privacy = backupPrivacy(),
            data = BackupContent(
                customExercises = customExercises.map(::mapCustomExerciseToBackup),
                workoutSessions = sessions.map { session -> mapSessionToBackup(session, routineNameResolutionContext) },
                metricSamples = metrics.map { mapMetricToBackup(it) },
                routines = routines.map { mapRoutineToBackup(it) },
                routineExercises = routineExercises.map { mapRoutineExerciseToBackup(it) },
                supersets = supersets.map { mapSupersetToBackup(it) },
                personalRecords = personalRecords,
                trainingCycles = trainingCycles.map { mapTrainingCycleToBackup(it) },
                cycleDays = cycleDays,
                cycleProgress = cycleProgress.map { mapCycleProgressToBackup(it) },
                cycleProgressions = cycleProgressions.map { mapCycleProgressionToBackup(it) },
                plannedSets = plannedSets.map { mapPlannedSetToBackup(it) },
                completedSets = completedSets.map { mapCompletedSetToBackup(it).let { backup ->
                    if (backup.plannedSetId !in plannedSetIds) backup.copy(plannedSetId = null) else backup
                } },
                progressionEvents = progressionEvents.map { mapProgressionEventToBackup(it) },
                earnedBadges = earnedBadges.map { mapEarnedBadgeToBackup(it) },
                streakHistory = streakHistory.map { mapStreakHistoryToBackup(it) },
                gamificationStatsByProfile = gamificationStats,
                stockExerciseUserFields = stockExerciseUserFields(),
                userProfiles = userProfiles.map { mapUserProfileToBackup(it) },
                profilePreferences = profilePreferences,
                sessionNotes = sessionNotes.map { mapSessionNotesToBackup(it) },
                routineGroups = routineGroups.map { mapRoutineGroupToBackup(it) },
                profileExerciseBaselines = profileExerciseBaselines.map {
                    ProfileExerciseBaselineBackup(
                        profileId = it.profile_id,
                        exerciseId = it.exercise_id,
                        oneRepMaxPerCableKg = it.one_rep_max_per_cable_kg?.toFloat(),
                        updatedAt = it.updated_at,
                        revision = it.revision,
                    )
                },
                workoutDeletions = workoutDeletions.map(::mapWorkoutDeletionToBackup),
                pendingProfileRecoveries = pendingProfileRecoveries.map(::mapPendingRecoveryToBackup),
                ownershipTransfers = ownershipTransfers.map(::mapOwnershipTransferToBackup),
                appliedOwnershipEvents = appliedOwnershipEvents.map(::mapAppliedOwnershipEventToBackup),
                localOwnershipClaims = localOwnershipClaims.map(::mapLocalOwnershipClaimToBackup),
                cycleSyncStates = cycleSyncStates.map(::mapCycleSyncStateToBackup),
                cycleConflictDrafts = cycleConflictDrafts.map(::mapCycleConflictDraftToBackup),
            ),
        )
    }

    override suspend fun exportToJson(): String = withContext(Dispatchers.IO) {
        json.encodeToString(exportAllData())
    }

    override suspend fun importFromJson(jsonString: String): Result<ImportResult> = withContext(Dispatchers.IO) {
        val source = StringBackupStreamSource(jsonString)
        source.open()
        try {
            importFromStream(source)
        } finally {
            source.close()
        }
    }

    /**
     * Streaming import: walks the backup JSON via [BackupJsonNavigator] and persists
     * entities one-by-one, keeping peak memory at roughly one entity's size.
     *
     * Transaction strategy:
     * - One transaction per entity type for most tables.
     * - metricSamples are batched ([IMPORT_BATCH_SIZE] per transaction) because a single
     *   session can have hundreds of thousands of rows.
     *
     * Routine name resolution is simplified vs [importFromJson]: the streaming path uses
     * `session.routineName` directly because the full routine list may not yet have been
     * parsed when sessions arrive (field order is not guaranteed).
     */
    protected suspend fun importFromStream(
        source: BackupStreamSource,
        onProgress: (BackupProgress) -> Unit = {},
    ): Result<ImportResult> {
        val callerContext = currentCoroutineContext()
        val checkedSource = GuardedBackupStreamSource(source) { callerContext.ensureActive() }
        val staging = createImportStagingArea()
        val deletedProfiles = deletedProfileFilter()
        return try {
            val header = try {
                stageAndValidateBackup(checkedSource, staging, deletedProfiles)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                throw IllegalArgumentException(
                    "Backup is malformed or produced by an incompatible app version: ${failure.message}",
                    failure,
                )
            }
            val replay = StagedBackupReplaySource(
                staging = staging,
                version = header.version,
                exportedAtJson = header.exportedAtJson,
                appVersionJson = header.appVersionJson,
                privacyJson = header.privacyJson,
                sections = RESTORE_SECTION_ORDER,
            )
            replay.open()
            try {
                importValidatedOrderedStream(
                    GuardedBackupStreamSource(replay) { callerContext.ensureActive() },
                    staging,
                    onProgress,
                ).map { result -> result.copy(deletedProfileRowsSkipped = deletedProfiles.dropped) }
            } finally {
                replay.close()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Logger.e { "Backup import validation/staging failed category=${e::class.simpleName}" }
            Result.failure(e)
        } finally {
            staging.cleanup()
        }
    }

    private data class StagedBackupHeader(
        val version: Int,
        val exportedAtJson: String,
        val appVersionJson: String,
        val privacyJson: String?,
    )

    private fun stageAndValidateBackup(
        source: BackupStreamSource,
        staging: BackupImportStagingArea,
        filter: DeletedProfileBackupFilter,
    ): StagedBackupHeader {
        val nav = BackupJsonNavigator(source)
        var version: Int? = null
        var exportedAtJson: String? = null
        var appVersionJson: String? = null
        var privacyJson: String? = null
        var sawData = false

        nav.beginObject()
        while (nav.hasNextInObject()) {
            when (val field = nav.nextName()) {
                "version" -> version = nav.nextInt()
                "exportedAt" -> exportedAtJson = nav.nextValueAsString().also {
                    json.decodeFromString<String>(it)
                }
                "appVersion" -> appVersionJson = nav.nextValueAsString().also {
                    json.decodeFromString<String>(it)
                }
                "privacy" -> privacyJson = nav.nextValueAsString().also {
                    json.decodeFromString<BackupPrivacyMetadata>(it)
                }
                "data" -> {
                    require(!sawData) { "Backup contains duplicate data objects" }
                    sawData = true
                    stageDataObject(nav, staging, filter)
                }
                else -> {
                    Logger.d { "Backup validation: skipping unknown root field '$field'" }
                    nav.skipValue()
                }
            }
        }
        nav.endObject()
        nav.requireEndOfInput()

        val parsedVersion = requireNotNull(version) { "Backup version is missing" }
        require(parsedVersion >= 1) { "Backup version must be positive" }
        require(sawData) { "Backup data object is missing" }
        return StagedBackupHeader(
            version = parsedVersion,
            exportedAtJson = requireNotNull(exportedAtJson) { "Backup exportedAt is missing" },
            appVersionJson = requireNotNull(appVersionJson) { "Backup appVersion is missing" },
            privacyJson = privacyJson,
        )
    }

    private fun stageDataObject(
        nav: BackupJsonNavigator,
        staging: BackupImportStagingArea,
        filter: DeletedProfileBackupFilter,
    ) {
        val seenSections = mutableSetOf<String>()
        nav.beginObject()
        while (nav.hasNextInObject()) {
            val section = nav.nextName()
            if ((section in ARRAY_BACKUP_SECTIONS || section == "gamificationStats" || section == "equipmentRackItems") &&
                !seenSections.add(section)
            ) {
                throw IllegalArgumentException("Backup contains duplicate data section '$section'")
            }
            when {
                section in ARRAY_BACKUP_SECTIONS -> {
                    staging.beginArray(section)
                    nav.beginArray()
                    while (nav.hasNextInArray()) {
                        val raw = nav.nextValueAsString()
                        validateSectionValue(section, raw)
                        // A profile the user permanently deleted never comes back from a backup.
                        if (!filter.keep(section, raw)) continue
                        staging.appendArrayValue(section, raw)
                    }
                    nav.endArray()
                    staging.endArray(section)
                }
                section == "gamificationStats" -> {
                    val raw = nav.nextValueAsString()
                    if (raw != "null") json.decodeFromString<GamificationStatsBackup>(raw)
                    staging.writeValue(section, filter.filterScalar(section, raw))
                }
                section == "equipmentRackItems" -> {
                    val raw = nav.nextValueAsString()
                    json.decodeFromString<JsonElement>(raw)
                    staging.writeValue(section, raw)
                }
                else -> {
                    Logger.d { "Backup validation: skipping unknown data field '$section'" }
                    nav.skipValue()
                }
            }
        }
        nav.endObject()
    }

    private fun validateSectionValue(section: String, raw: String) {
        when (section) {
            "customExercises" -> json.decodeFromString<CustomExerciseBackup>(raw)
            "stockExerciseUserFields" -> json.decodeFromString<StockExerciseUserFieldsBackup>(raw)
            "gamificationStatsByProfile" -> json.decodeFromString<GamificationStatsBackup>(raw)
            "userProfiles" -> json.decodeFromString<UserProfileBackup>(raw)
            "profilePreferences" -> json.decodeFromString<ProfilePreferencesBackup>(raw)
            "routineGroups" -> json.decodeFromString<RoutineGroupBackup>(raw)
            "routines" -> json.decodeFromString<RoutineBackup>(raw)
            "supersets" -> json.decodeFromString<SupersetBackup>(raw)
            "routineExercises" -> json.decodeFromString<RoutineExerciseBackup>(raw)
            "plannedSets" -> json.decodeFromString<PlannedSetBackup>(raw)
            "workoutDeletions" -> json.decodeFromString<WorkoutDeletionBackup>(raw).also { deletion ->
                require(deletion.scope == "COMPONENT" || deletion.scope == "WORKOUT") { "Invalid workout deletion scope" }
                require(deletion.source == "LOCAL" || deletion.source == "REMOTE") { "Invalid workout deletion source" }
                require((deletion.scope == "COMPONENT") == (deletion.componentSessionId != null)) {
                    "Workout deletion component does not match scope"
                }
            }
            "pendingProfileRecoveries" -> json.decodeFromString<PendingProfileRecoveryBackup>(raw).also { recovery ->
                require(recovery.kind == "PROFILE_DATA" || recovery.kind == "LEGACY_BASELINE") {
                    "Invalid pending recovery kind"
                }
                json.decodeFromString<JsonElement>(recovery.countsJson)
            }
            "ownershipTransfers" -> json.decodeFromString<OwnershipTransferBackup>(raw).also { transfer ->
                val sections = listOf(
                    transfer.workoutSessionIdsJson,
                    transfer.routineIdsJson,
                    transfer.cycleIdsJson,
                    transfer.personalRecordIdsJson,
                ).map { json.decodeFromString<List<String>>(it) }
                require(sections.any { it.isNotEmpty() }) { "Ownership transfer contains no entity ids" }
            }
            "appliedOwnershipEvents" -> json.decodeFromString<AppliedOwnershipEventBackup>(raw)
            "localOwnershipClaims" -> json.decodeFromString<LocalOwnershipClaimBackup>(raw).also { claim ->
                require(claim.entityType in OWNERSHIP_CLAIM_ENTITY_TYPES) { "Invalid ownership claim entity type" }
            }
            "profileExerciseBaselines" -> json.decodeFromString<ProfileExerciseBaselineBackup>(raw)
            "trainingCycles" -> json.decodeFromString<TrainingCycleBackup>(raw)
            "cycleDays" -> json.decodeFromString<CycleDayBackup>(raw)
            "cycleProgress" -> json.decodeFromString<CycleProgressBackup>(raw)
            "cycleProgressions" -> json.decodeFromString<CycleProgressionBackup>(raw)
            "cycleSyncStates" -> json.decodeFromString<CycleSyncStateBackup>(raw).also { state ->
                require(state.dirtyGeneration >= 0L && state.acknowledgedGeneration in 0L..state.dirtyGeneration) {
                    "Invalid cycle sync generations"
                }
                require((state.pendingDeleteUpdatedAt == null) == (state.pendingDeleteGeneration == null)) {
                    "Incomplete cycle pending-delete state"
                }
            }
            "cycleConflictDrafts" -> json.decodeFromString<CycleConflictDraftBackup>(raw)
            "workoutSessions" -> json.decodeFromString<WorkoutSessionBackup>(raw)
            "metricSamples" -> json.decodeFromString<MetricSampleBackup>(raw)
            "completedSets" -> json.decodeFromString<CompletedSetBackup>(raw)
            "personalRecords" -> json.decodeFromString<PersonalRecordBackup>(raw)
            "progressionEvents" -> json.decodeFromString<ProgressionEventBackup>(raw)
            "earnedBadges" -> json.decodeFromString<EarnedBadgeBackup>(raw)
            "streakHistory" -> json.decodeFromString<StreakHistoryBackup>(raw)
            "sessionNotes" -> json.decodeFromString<SessionNotesBackup>(raw)
        }
    }

    private suspend fun importValidatedOrderedStream(
        source: BackupStreamSource,
        staging: BackupImportStagingArea,
        onProgress: (BackupProgress) -> Unit = {},
    ): Result<ImportResult> {
        val importContext = currentCoroutineContext()
        var preImportActiveProfileId: String? = null
        var preImportDefaultProfileExisted = false
        val representedProfileIds = linkedSetOf<String>()
        var databaseWorkCommitted = false
        var activeIdentityNormalized = false
        var reconciliationAttempted = false
        fun legacyFallbackProfileId(): String = preImportActiveProfileId
            ?.takeIf { queries.getProfileById(it).executeAsOneOrNull() != null }
            ?: "default".takeIf {
                preImportDefaultProfileExisted && queries.getProfileById(it).executeAsOneOrNull() != null
            }
            ?: representedProfileIds.firstOrNull {
                queries.getProfileById(it).executeAsOneOrNull() != null
            }
            ?: "default"
        // Restored sessions whose sync markers must be (re)applied once every child section
        // has restored, because metric/set/note restores re-dirty their session.
        val restoredSessionSyncMarkers = mutableListOf<WorkoutSessionBackup>()
        // Sessions that already existed and matched the backup, where both the target row and the
        // backup show nothing pending. The source's acknowledged push already delivered these
        // children (and the rep summaries a backup lacks), so restoring the missing children
        // must not turn the row into a destructive re-push. Without that proof (v6 file, pending
        // row) restored children reopen the parent so the portal receives them.
        val matchedCleanSessionIds = mutableListOf<String>()
        // Routine groups the restore could make repair-eligible, per profile; held out of the
        // repair. A group qualifies once any of its rows was inserted, or an existing row gained
        // a restored MetricSample / CompletedSet (the repair's non-childless test); restored
        // rows never carry the rep summaries a repair re-push would need.
        val restoredRoutineGroupIds = mutableMapOf<String, MutableSet<String>>()
        // Matched existing sessions in a routine group: session id -> (profile id, group id).
        val matchedSessionGroups = mutableMapOf<String, Pair<String, String>>()
        fun holdGroupOfRestoredChild(sessionId: String) {
            val (profileId, groupId) = matchedSessionGroups[sessionId] ?: return
            restoredRoutineGroupIds.getOrPut(profileId) { linkedSetOf() } += groupId
        }
        fun applyRestoredSessionSyncMarkers() {
            if (restoredSessionSyncMarkers.isEmpty() && matchedCleanSessionIds.isEmpty()) return
            database.transaction {
                restoredSessionSyncMarkers.forEach { session ->
                    queries.restoreSessionSyncMarkers(
                        portalOrigin = if (session.portalOrigin) 1L else 0L,
                        updatedAt = session.updatedAt,
                        acknowledged = if (session.syncAcknowledged) 1L else 0L,
                        id = session.id,
                    )
                }
                // Only rows that were clean before the restore; a pending local edit stays pending.
                matchedCleanSessionIds.forEach { id -> queries.markSessionSynced(id) }
            }
        }
        suspend fun resetStateABackupCannotCarry() {
            // State a backup file cannot carry must not describe the pre-restore database
            // (sync cursors, repair walks, one-shot work markers).
            portalTokenStorage?.resetAfterBackupRestore(
                profileIds = representedProfileIds.ifEmpty { setOf(legacyFallbackProfileId()) },
                restoredRoutineGroupIds = restoredRoutineGroupIds,
            )
            preferencesManager?.resetOneShotWorkAfterRestore()
        }
        try {
            val nav = BackupJsonNavigator(source)
            var legacyRackFieldPresent = false
            var legacyRackElement: JsonElement? = null

            // -- Track import counts (mirrors importFromJson exactly) --
            var sessionsImported = 0
            var sessionsSkipped = 0
            var metricsImported = 0
            var metricsSkipped = 0
            var routinesImported = 0
            var routinesSkipped = 0
            var routineExercisesImported = 0
            var routineExercisesSkipped = 0
            var supersetsImported = 0
            var supersetsSkipped = 0
            var personalRecordsImported = 0
            var personalRecordsSkipped = 0
            var trainingCyclesImported = 0
            var trainingCyclesSkipped = 0
            var cycleDaysImported = 0
            var cycleDaysSkipped = 0
            var userProfilesImported = 0
            var userProfilesSkipped = 0
            var cycleProgressImported = 0
            var cycleProgressSkipped = 0
            var cycleProgressionsImported = 0
            var cycleProgressionsSkipped = 0
            var plannedSetsImported = 0
            var plannedSetsSkipped = 0
            var completedSetsImported = 0
            var completedSetsSkipped = 0
            var progressionEventsImported = 0
            var progressionEventsSkipped = 0
            var earnedBadgesImported = 0
            var earnedBadgesSkipped = 0
            var streakHistoryImported = 0
            var streakHistorySkipped = 0
            var gamificationStatsImported = 0
            var gamificationStatsSkipped = 0
            var sessionNotesImported = 0
            var sessionNotesSkipped = 0
            var routineGroupsImported = 0
            var routineGroupsSkipped = 0
            var customExercisesImported = 0
            var customExercisesSkipped = 0
            var stockExerciseUserFieldsImported = 0
            var stockExerciseUserFieldsSkipped = 0
            var profileExerciseBaselinesImported = 0
            var profileExerciseBaselinesSkipped = 0
            var workoutDeletionsImported = 0
            var workoutDeletionsSkipped = 0
            var pendingProfileRecoveriesImported = 0
            var pendingProfileRecoveriesSkipped = 0
            var ownershipTransfersImported = 0
            var ownershipTransfersSkipped = 0
            var appliedOwnershipEventsImported = 0
            var appliedOwnershipEventsSkipped = 0
            var localOwnershipClaimsImported = 0
            var localOwnershipClaimsSkipped = 0
            var cycleSyncStatesImported = 0
            var cycleSyncStatesSkipped = 0
            var cycleConflictDraftsImported = 0
            var cycleConflictDraftsSkipped = 0
            var repairedReferences = 0
            var entitiesWithErrors = 0
            var parentIndexFailed = false

            preImportActiveProfileId = queries.getAllProfiles()
                .executeAsList()
                .firstOrNull { it.isActive == 1L }
                ?.id
            preImportDefaultProfileExisted = queries.getProfileById("default").executeAsOneOrNull() != null

            fun parentStatus(namespace: String, id: String): BackupParentStatus? {
                if (parentIndexFailed) return null
                return try {
                    staging.parentStatus(namespace, id)
                } catch (failure: Exception) {
                    parentIndexFailed = true
                    entitiesWithErrors++
                    Logger.w(failure) {
                        "Backup restore parent index read failed namespace=$namespace id=$id; dependent rows will be rejected"
                    }
                    null
                }
            }

            fun parentAvailable(namespace: String, id: String): Boolean =
                parentStatus(namespace, id)?.isAvailable == true

            fun optionalParentCompatible(namespace: String, id: String): Boolean =
                parentStatus(namespace, id) != BackupParentStatus.UNAVAILABLE && !parentIndexFailed

            fun profileParentAvailable(profileId: String): Boolean {
                val status = parentStatus("profile", profileId)
                return !parentIndexFailed && status?.isAvailable != false &&
                    queries.getProfileById(profileId).executeAsOneOrNull() != null
            }

            fun ownershipClaimAllows(entityType: String, entityId: String, profileId: String): Boolean {
                val claims = queries.selectLocalOwnershipClaimsForEntity(entityType, entityId).executeAsList()
                if (claims.isEmpty()) return true
                val ownerUserId = queries.getProfileById(profileId).executeAsOneOrNull()?.supabase_user_id ?: return false
                return claims.firstOrNull { it.owner_user_id == ownerUserId }?.target_profile_id == profileId
            }

            fun inferRoutineName(session: WorkoutSessionBackup): String? {
                session.routineId?.let { routineId ->
                    if (optionalParentCompatible("routine", routineId)) {
                        queries.selectRoutineById(routineId).executeAsOneOrNull()?.name?.let { return it }
                    }
                }
                if (session.exerciseId == null && session.exerciseName.isNullOrBlank()) return null
                val section = staging.openSection("routineExercises") ?: return null
                val source = GuardedBackupStreamSource(section) { importContext.ensureActive() }
                source.open()
                var candidateRoutineId: String? = null
                var ambiguous = false
                try {
                    val sectionNav = BackupJsonNavigator(source)
                    sectionNav.beginArray()
                    while (sectionNav.hasNextInArray()) {
                        val exercise = json.decodeFromString<RoutineExerciseBackup>(sectionNav.nextValueAsString())
                        val matches = if (session.exerciseId != null) {
                            exercise.exerciseId == session.exerciseId
                        } else {
                            normalizeExerciseToken(exercise.exerciseName) == normalizeExerciseToken(session.exerciseName)
                        }
                        if (matches && parentAvailable("routineExercise", exercise.id)) {
                            if (candidateRoutineId == null) candidateRoutineId = exercise.routineId
                            else if (candidateRoutineId != exercise.routineId) ambiguous = true
                        }
                    }
                    sectionNav.endArray()
                    sectionNav.requireEndOfInput()
                } finally {
                    source.close()
                }
                return candidateRoutineId
                    ?.takeUnless { ambiguous }
                    ?.let { queries.selectRoutineById(it).executeAsOneOrNull()?.name }
            }

            fun committedTransaction(block: () -> Unit) {
                database.transaction { block() }
                databaseWorkCommitted = true
            }

            // Error-resilient per-entity insert helper (same pattern as importFromJson)
            fun <T> tryImport(label: String, entityId: String?, block: () -> T): T? = try {
                block()
            } catch (e: Exception) {
                if (!isSkippableRestoreRowFailure(e)) throw e
                entitiesWithErrors++
                Logger.w {
                    "Streaming import skip $label" +
                        (entityId?.let { " id=$it" } ?: "") +
                        " category=${e::class.simpleName}"
                }
                null
            }

            fun recordParent(namespace: String, id: String, status: BackupParentStatus) {
                try {
                    staging.recordParentStatus(namespace, id, status)
                } catch (failure: Exception) {
                    parentIndexFailed = true
                    entitiesWithErrors++
                    Logger.w(failure) {
                        "Backup restore parent index failed namespace=$namespace id=$id; dependent rows will be rejected"
                    }
                }
            }

            fun onInvalidProfileState(profileId: String, sectionName: String, failure: Throwable?) {
                entitiesWithErrors++
                Logger.w {
                    "Streaming import skip profilePreference profileId=$profileId section=$sectionName" +
                        (failure?.let { " category=${it::class.simpleName}" } ?: "")
                }
            }

            fun restoreMetric(metric: MetricSampleBackup) {
                val sessionStatus = parentStatus("session", metric.sessionId)
                if (parentIndexFailed || sessionStatus?.isAvailable != true ||
                    queries.selectSessionById(metric.sessionId).executeAsOneOrNull() == null
                ) {
                    entitiesWithErrors++
                    Logger.w { "Backup restore missing required session ${metric.sessionId} for metric ${metric.id}" }
                    return
                }
                if (metric.id <= 0L) {
                    if (sessionStatus != BackupParentStatus.INSERTED) {
                        entitiesWithErrors++
                        Logger.w { "Backup restore cannot safely retry legacy metric without stable id for session ${metric.sessionId}" }
                        return
                    }
                    queries.insertMetric(
                        sessionId = metric.sessionId,
                        timestamp = metric.timestamp,
                        position = metric.position?.toDouble(),
                        positionB = metric.positionB?.toDouble(),
                        velocity = metric.velocity?.toDouble(),
                        velocityB = metric.velocityB?.toDouble(),
                        load = metric.load?.toDouble(),
                        loadB = metric.loadB?.toDouble(),
                        power = metric.power?.toDouble(),
                        status = metric.status.toLong(),
                    )
                    queries.markWorkoutComponentDirty(metric.sessionId)
                    holdGroupOfRestoredChild(metric.sessionId)
                    metricsImported++
                    return
                }
                val existing = queries.selectMetricById(metric.id).executeAsOneOrNull()
                if (existing != null) {
                    val same = existing.sessionId == metric.sessionId && existing.timestamp == metric.timestamp &&
                        existing.position?.toFloat() == metric.position && existing.positionB?.toFloat() == metric.positionB &&
                        existing.velocity?.toFloat() == metric.velocity && existing.velocityB?.toFloat() == metric.velocityB &&
                        existing.load?.toFloat() == metric.load && existing.loadB?.toFloat() == metric.loadB &&
                        existing.power?.toFloat() == metric.power && existing.status == metric.status.toLong()
                    if (same) metricsSkipped++ else {
                        entitiesWithErrors++
                        Logger.w { "Backup restore conflict metric id=${metric.id}" }
                    }
                    return
                }
                queries.insertMetricRestoreIfAbsent(
                    id = metric.id,
                    sessionId = metric.sessionId,
                    timestamp = metric.timestamp,
                    position = metric.position?.toDouble(),
                    positionB = metric.positionB?.toDouble(),
                    velocity = metric.velocity?.toDouble(),
                    velocityB = metric.velocityB?.toDouble(),
                    load = metric.load?.toDouble(),
                    loadB = metric.loadB?.toDouble(),
                    power = metric.power?.toDouble(),
                    status = metric.status.toLong(),
                )
                queries.markWorkoutComponentDirty(metric.sessionId)
                holdGroupOfRestoredChild(metric.sessionId)
                metricsImported++
            }

            var backupVersion = 1

            fun restoreGamificationStats(stats: GamificationStatsBackup) {
                committedTransaction {
                    if (backupVersion >= 6 && !profileParentAvailable(stats.profileId)) {
                        entitiesWithErrors++
                        Logger.w { "Backup restore missing required profile ${stats.profileId} for gamificationStats" }
                        return@committedTransaction
                    }
                    val existing = queries.selectGamificationStats(stats.profileId).executeAsOneOrNull()
                    if (existing != null) {
                        if (mapGamificationStatsToBackup(existing) == stats) gamificationStatsSkipped++ else {
                            entitiesWithErrors++
                            Logger.w { "Backup restore conflict gamificationStats profile=${stats.profileId}" }
                        }
                        return@committedTransaction
                    }
                    val stableId = stats.profileId.hashCode().toLong()
                    val inserted = tryImport("gamificationStats", stats.profileId) {
                        queries.insertGamificationStatsRestoreIfAbsent(
                            id = stableId,
                            totalWorkouts = stats.totalWorkouts.toLong(),
                            totalReps = stats.totalReps.toLong(),
                            totalVolumeKg = stats.totalVolumeKg.toLong(),
                            longestStreak = stats.longestStreak.toLong(),
                            currentStreak = stats.currentStreak.toLong(),
                            uniqueExercisesUsed = stats.uniqueExercisesUsed.toLong(),
                            prsAchieved = stats.prsAchieved.toLong(),
                            lastWorkoutDate = stats.lastWorkoutDate,
                            streakStartDate = stats.streakStartDate,
                            lastUpdated = stats.lastUpdated,
                            updatedAt = stats.updatedAt,
                            serverId = stats.serverId,
                            profileId = stats.profileId,
                        )
                    }
                    if (inserted != null) {
                        val restored = queries.selectGamificationStats(stats.profileId).executeAsOneOrNull()
                        if (restored != null && mapGamificationStatsToBackup(restored) == stats) {
                            gamificationStatsImported++
                        } else {
                            entitiesWithErrors++
                            Logger.w { "Backup restore could not insert gamificationStats profile=${stats.profileId}" }
                        }
                    }
                }
            }

            // -- Parse top-level JSON structure --
            nav.beginObject()
            while (nav.hasNextInObject()) {
                when (nav.nextName()) {
                    "version" -> {
                        backupVersion = nav.nextInt()
                        if (backupVersion > CURRENT_BACKUP_VERSION) {
                            Logger.w {
                                "Backup version $backupVersion is newer than supported " +
                                    "(v$CURRENT_BACKUP_VERSION). Proceeding with forward compatibility — " +
                                    "fields added after v$CURRENT_BACKUP_VERSION will be dropped."
                            }
                        }
                    }

                    "exportedAt" -> nav.skipValue()

                    "appVersion" -> nav.skipValue()

                    "privacy" -> nav.skipValue()

                    "data" -> {
                        nav.beginObject()
                        while (nav.hasNextInObject()) {
                            val fieldName = nav.nextName()
                            when (fieldName) {
                                // --- equipmentRackItems ---
                                "equipmentRackItems" -> {
                                    legacyRackFieldPresent = true
                                    legacyRackElement = json.parseToJsonElement(nav.nextValueAsString())
                                }

                                // --- profilePreferences (small, deferred to root end) ---
                                "profilePreferences" -> {
                                    // The validated section stays on disk until identity normalization.
                                    // Re-open it one row at a time below so ordering and error precedence
                                    // match the legacy importer without retaining an input-sized list.
                                    nav.skipValue()
                                }

                                "customExercises" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val exercise = json.decodeFromString<CustomExerciseBackup>(nav.nextValueAsString())
                                            val existing = queries.selectExerciseById(exercise.id).executeAsOneOrNull()
                                            if (existing != null) {
                                                if (existing.isCustom == 1L && mapCustomExerciseToBackup(existing) == exercise) {
                                                    customExercisesSkipped++
                                                } else {
                                                    entitiesWithErrors++
                                                    Logger.w { "Backup restore conflict customExercise id=${exercise.id}; local row preserved" }
                                                }
                                                continue
                                            }
                                            queries.insertExerciseIfAbsent(
                                                id = exercise.id,
                                                name = exercise.name,
                                                displayName = exercise.displayName,
                                                description = exercise.description,
                                                created = exercise.created,
                                                muscleGroup = exercise.muscleGroup,
                                                muscleGroups = exercise.muscleGroups,
                                                muscles = exercise.muscles,
                                                equipment = exercise.equipment,
                                                movement = exercise.movement,
                                                sidedness = exercise.sidedness,
                                                grip = exercise.grip,
                                                gripWidth = exercise.gripWidth,
                                                minRepRange = exercise.minRepRange?.toDouble(),
                                                popularity = exercise.popularity.toDouble(),
                                                archived = if (exercise.archived) 1L else 0L,
                                                isFavorite = if (exercise.isFavorite) 1L else 0L,
                                                isCustom = 1L,
                                                timesPerformed = exercise.timesPerformed.toLong(),
                                                lastPerformed = exercise.lastPerformed,
                                                aliases = exercise.aliases,
                                                defaultCableConfig = exercise.defaultCableConfig,
                                                one_rep_max_kg = exercise.legacyOneRepMaxKg?.toDouble(),
                                                mvtOverrideMs = exercise.mvtOverrideMs?.toDouble(),
                                                isBodyweight = exercise.isBodyweight?.let { if (it) 1L else 0L },
                                            )
                                            exercise.updatedAt?.let { updatedAt ->
                                                queries.updateCustomExerciseFromSync(
                                                    name = exercise.name,
                                                    displayName = exercise.displayName,
                                                    muscleGroup = exercise.muscleGroup,
                                                    muscleGroups = exercise.muscleGroups,
                                                    equipment = exercise.equipment,
                                                    defaultCableConfig = exercise.defaultCableConfig,
                                                    updatedAt = updatedAt,
                                                    serverId = exercise.serverId,
                                                    deletedAt = exercise.deletedAt,
                                                    id = exercise.id,
                                                )
                                            }
                                            customExercisesImported++
                                        }
                                        nav.endArray()
                                    }
                                }

                                "profileExerciseBaselines" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val baseline = json.decodeFromString<ProfileExerciseBaselineBackup>(nav.nextValueAsString())
                                            if (baseline.revision <= 0L ||
                                                !profileParentAvailable(baseline.profileId) ||
                                                queries.selectExerciseById(baseline.exerciseId).executeAsOneOrNull() == null
                                            ) {
                                                entitiesWithErrors++
                                                Logger.w { "Backup restore missing required baseline parent or invalid revision profile=${baseline.profileId} exercise=${baseline.exerciseId}" }
                                                continue
                                            }
                                            val existing = queries.selectProfileExerciseBaseline(
                                                baseline.profileId,
                                                baseline.exerciseId,
                                            ).executeAsOneOrNull()
                                            if (existing != null) {
                                                val same = existing.one_rep_max_per_cable_kg?.toFloat() == baseline.oneRepMaxPerCableKg &&
                                                    existing.updated_at == baseline.updatedAt && existing.revision == baseline.revision
                                                if (same) profileExerciseBaselinesSkipped++ else {
                                                    entitiesWithErrors++
                                                    Logger.w { "Backup restore conflict profileExerciseBaseline profile=${baseline.profileId} exercise=${baseline.exerciseId}" }
                                                }
                                                continue
                                            }
                                            queries.insertProfileExerciseBaselineIfAbsent(
                                                profileId = baseline.profileId,
                                                exerciseId = baseline.exerciseId,
                                                oneRepMaxPerCableKg = baseline.oneRepMaxPerCableKg?.toDouble(),
                                                updatedAt = baseline.updatedAt,
                                                revision = baseline.revision,
                                            )
                                            profileExerciseBaselinesImported++
                                        }
                                        nav.endArray()
                                    }
                                }

                                "workoutDeletions" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val deletion = json.decodeFromString<WorkoutDeletionBackup>(nav.nextValueAsString())
                                            val existing = queries.selectWorkoutDeletionByMutationId(deletion.mutationId).executeAsOneOrNull()
                                            if (existing == null) {
                                                queries.insertWorkoutDeletionRestoreIfAbsent(
                                                    mutationId = deletion.mutationId,
                                                    ownerUserId = deletion.ownerUserId,
                                                    profileId = deletion.profileId,
                                                    scope = deletion.scope,
                                                    portalSessionId = deletion.portalSessionId,
                                                    componentSessionId = deletion.componentSessionId,
                                                    deletedAt = deletion.deletedAt,
                                                    acknowledgedAt = deletion.acknowledgedAt,
                                                    source = deletion.source,
                                                )
                                                workoutDeletionsImported++
                                            } else {
                                                val local = mapWorkoutDeletionToBackup(existing)
                                                if (local.copy(acknowledgedAt = null) == deletion.copy(acknowledgedAt = null)) {
                                                    deletion.acknowledgedAt?.let {
                                                        queries.advanceWorkoutDeletionAcknowledgedAt(
                                                            acknowledgedAt = it,
                                                            mutationId = deletion.mutationId,
                                                            ownerUserId = deletion.ownerUserId,
                                                        )
                                                    }
                                                    workoutDeletionsSkipped++
                                                } else {
                                                    entitiesWithErrors++
                                                    Logger.w { "Backup restore conflict workoutDeletion mutationId=${deletion.mutationId}; original owner preserved" }
                                                }
                                            }
                                        }
                                        nav.endArray()
                                    }
                                }

                                "pendingProfileRecoveries" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val recovery = json.decodeFromString<PendingProfileRecoveryBackup>(nav.nextValueAsString())
                                            val byId = queries.selectPendingProfileRecoveryById(recovery.recoveryId).executeAsOneOrNull()
                                            val bySource = queries.selectPendingProfileRecoveryBySourceKey(recovery.sourceKey).executeAsOneOrNull()
                                            val existing = byId ?: bySource
                                            if (existing == null) {
                                                queries.insertPendingProfileRecoveryRestoreIfAbsent(
                                                    recoveryId = recovery.recoveryId,
                                                    kind = recovery.kind,
                                                    sourceKey = recovery.sourceKey,
                                                    sourceProfileId = recovery.sourceProfileId,
                                                    sourceProfileName = recovery.sourceProfileName,
                                                    ownerUserId = recovery.ownerUserId,
                                                    countsJson = recovery.countsJson,
                                                    discoveredAt = recovery.discoveredAt,
                                                    resolvedAt = recovery.resolvedAt,
                                                )
                                                pendingProfileRecoveriesImported++
                                            } else if (mapPendingRecoveryToBackup(existing) == recovery) {
                                                pendingProfileRecoveriesSkipped++
                                            } else {
                                                entitiesWithErrors++
                                                Logger.w { "Backup restore conflict pendingProfileRecovery id=${recovery.recoveryId}; original owner preserved" }
                                            }
                                        }
                                        nav.endArray()
                                    }
                                }

                                "ownershipTransfers" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val transfer = json.decodeFromString<OwnershipTransferBackup>(nav.nextValueAsString())
                                            val existing = queries.selectOwnershipTransferByMutationId(transfer.mutationId).executeAsOneOrNull()
                                            if (existing == null) {
                                                queries.insertOwnershipTransferRestoreIfAbsent(
                                                    mutationId = transfer.mutationId,
                                                    ownerUserId = transfer.ownerUserId,
                                                    sourceProfileId = transfer.sourceProfileId,
                                                    targetProfileId = transfer.targetProfileId,
                                                    workoutSessionIdsJson = transfer.workoutSessionIdsJson,
                                                    routineIdsJson = transfer.routineIdsJson,
                                                    cycleIdsJson = transfer.cycleIdsJson,
                                                    personalRecordIdsJson = transfer.personalRecordIdsJson,
                                                    createdAt = transfer.createdAt,
                                                    acknowledgedAt = transfer.acknowledgedAt,
                                                )
                                                ownershipTransfersImported++
                                            } else {
                                                val local = mapOwnershipTransferToBackup(existing)
                                                if (local.copy(acknowledgedAt = null) == transfer.copy(acknowledgedAt = null)) {
                                                    transfer.acknowledgedAt?.let {
                                                        queries.advanceOwnershipTransferAcknowledgedAt(
                                                            acknowledgedAt = it,
                                                            mutationId = transfer.mutationId,
                                                            ownerUserId = transfer.ownerUserId,
                                                        )
                                                    }
                                                    ownershipTransfersSkipped++
                                                } else {
                                                    entitiesWithErrors++
                                                    Logger.w { "Backup restore conflict ownershipTransfer mutationId=${transfer.mutationId}; original owner preserved" }
                                                }
                                            }
                                        }
                                        nav.endArray()
                                    }
                                }

                                "appliedOwnershipEvents" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val event = json.decodeFromString<AppliedOwnershipEventBackup>(nav.nextValueAsString())
                                            val existing = queries.selectAppliedOwnershipEvent(event.ownerUserId, event.mutationId).executeAsOneOrNull()
                                            if (existing == null) {
                                                queries.insertAppliedOwnershipEventIfAbsent(
                                                    ownerUserId = event.ownerUserId,
                                                    mutationId = event.mutationId,
                                                    canonicalBodyHash = event.canonicalBodyHash,
                                                    appliedAt = event.appliedAt,
                                                )
                                                appliedOwnershipEventsImported++
                                            } else if (mapAppliedOwnershipEventToBackup(existing) == event) {
                                                appliedOwnershipEventsSkipped++
                                            } else {
                                                entitiesWithErrors++
                                                Logger.w { "Backup restore conflict appliedOwnershipEvent owner=${event.ownerUserId} mutationId=${event.mutationId}" }
                                            }
                                        }
                                        nav.endArray()
                                    }
                                }

                                "localOwnershipClaims" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val claim = json.decodeFromString<LocalOwnershipClaimBackup>(nav.nextValueAsString())
                                            val existing = queries.selectLocalOwnershipClaim(
                                                claim.ownerUserId,
                                                claim.entityType,
                                                claim.entityId,
                                            ).executeAsOneOrNull()
                                            if (existing == null) {
                                                queries.insertLocalOwnershipClaimIfAbsent(
                                                    ownerUserId = claim.ownerUserId,
                                                    entityType = claim.entityType,
                                                    entityId = claim.entityId,
                                                    mutationId = claim.mutationId,
                                                    sourceProfileId = claim.sourceProfileId,
                                                    targetProfileId = claim.targetProfileId,
                                                    transferredAt = claim.transferredAt,
                                                )
                                                localOwnershipClaimsImported++
                                                queries.selectLocalOwnershipClaim(
                                                    claim.ownerUserId,
                                                    claim.entityType,
                                                    claim.entityId,
                                                ).executeAsOneOrNull()
                                            } else if (mapLocalOwnershipClaimToBackup(existing) == claim) {
                                                localOwnershipClaimsSkipped++
                                            } else {
                                                entitiesWithErrors++
                                                Logger.w {
                                                    "Backup restore conflict localOwnershipClaim owner=${claim.ownerUserId} " +
                                                        "type=${claim.entityType} id=${claim.entityId}; original claim preserved"
                                                }
                                            }
                                        }
                                        nav.endArray()
                                    }
                                }

                                // --- workoutSessions ---
                                "workoutSessions" -> {
                                    onProgress(BackupProgress(BackupPhase.SESSIONS, 0, 0))
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val rawJson = nav.nextValueAsString()
                                            val session = tryImport("session-parse", null) {
                                                json.decodeFromString<WorkoutSessionBackup>(rawJson)
                                            } ?: continue

                                            if (backupVersion >= 6 && session.profileId != null &&
                                                !profileParentAvailable(session.profileId)
                                            ) {
                                                entitiesWithErrors++
                                                recordParent("session", session.id, BackupParentStatus.UNAVAILABLE)
                                                Logger.w { "Backup restore missing required profile ${session.profileId} for session ${session.id}" }
                                                continue
                                            }
                                            val sessionProfileId = session.profileId?.takeIf {
                                                profileParentAvailable(it)
                                            } ?: legacyFallbackProfileId()
                                            val sessionExerciseId = session.exerciseId?.takeIf {
                                                queries.selectExerciseById(it).executeAsOneOrNull() != null
                                            }.also { if (session.exerciseId != null && it == null) repairedReferences++ }
                                            val sessionRoutineId = session.routineId?.takeIf {
                                                optionalParentCompatible("routine", it) &&
                                                    queries.selectRoutineById(it).executeAsOneOrNull() != null
                                            }.also { if (session.routineId != null && it == null) repairedReferences++ }
                                            val safeEccentricLoad = session.eccentricLoad.sanitizeEccentricLoad()
                                            val resolvedRoutineSessionId = sanitizeRoutineSessionId(session.routineSessionId)
                                            val sanitizedRoutineName = sanitizeRoutineName(session.routineName)
                                            val resolvedRoutineName = when {
                                                session.isJustLift -> "Just Lift"
                                                normalizeExerciseToken(sanitizedRoutineName) == normalizeExerciseToken(session.exerciseName) ->
                                                    inferRoutineName(session)
                                                sanitizedRoutineName == null -> inferRoutineName(session)
                                                else -> sanitizedRoutineName
                                            }
                                            val safeProgressionKg = session.progressionKg.sanitizeProgressionKg()
                                            val normalizedSession = session.copy(
                                                eccentricLoad = safeEccentricLoad,
                                                progressionKg = safeProgressionKg,
                                                exerciseId = sessionExerciseId,
                                                routineSessionId = resolvedRoutineSessionId,
                                                routineName = resolvedRoutineName,
                                                routineId = sessionRoutineId,
                                                profileId = sessionProfileId,
                                            )
                                            val sessionOwner = queries.getProfileById(sessionProfileId)
                                                .executeAsOneOrNull()?.supabase_user_id
                                            val portalSessionId = sanitizeRoutineSessionId(session.routineSessionId) ?: session.id
                                            if (!ownershipClaimAllows("WORKOUT", portalSessionId, sessionProfileId)) {
                                                entitiesWithErrors++
                                                recordParent("session", session.id, BackupParentStatus.UNAVAILABLE)
                                                Logger.w { "Backup restore ownership conflict workout id=$portalSessionId profile=$sessionProfileId" }
                                                continue
                                            }
                                            val isBlockedByDeletion = queries.selectBlockingWorkoutDeletionForRestore(
                                                profileId = sessionProfileId,
                                                ownerUserId = sessionOwner,
                                                portalSessionId = portalSessionId,
                                                componentSessionId = session.id,
                                            ).executeAsOneOrNull() != null
                                            if (isBlockedByDeletion) {
                                                sessionsSkipped++
                                                recordParent("session", session.id, BackupParentStatus.UNAVAILABLE)
                                                Logger.w { "Backup restore suppressed deleted workout component id=${session.id}" }
                                                continue
                                            }

                                            val existingSession = queries.selectSessionById(session.id).executeAsOneOrNull()
                                            if (existingSession == null) {
                                                if (session.eccentricLoad != safeEccentricLoad) {
                                                    Logger.w { "Streaming import: session ${session.id} eccentricLoad ${session.eccentricLoad}% clamped to $safeEccentricLoad% (hardware limit)" }
                                                }

                                                val inserted = tryImport("session", session.id) {
                                                    queries.insertSession(
                                                        id = session.id,
                                                        timestamp = session.timestamp,
                                                        mode = session.mode,
                                                        targetReps = session.targetReps.toLong(),
                                                        weightPerCableKg = session.weightPerCableKg.toDouble(),
                                                        progressionKg = safeProgressionKg.toDouble(),
                                                        duration = session.duration,
                                                        totalReps = session.totalReps.toLong(),
                                                        warmupReps = session.warmupReps.toLong(),
                                                        workingReps = session.workingReps.toLong(),
                                                        isJustLift = if (session.isJustLift) 1L else 0L,
                                                        stopAtTop = if (session.stopAtTop) 1L else 0L,
                                                        eccentricLoad = safeEccentricLoad.toLong(),
                                                        echoLevel = session.echoLevel.toLong(),
                                                        exerciseId = sessionExerciseId,
                                                        exerciseName = session.exerciseName,
                                                        routineSessionId = resolvedRoutineSessionId,
                                                        routineName = resolvedRoutineName,
                                                        routineId = sessionRoutineId,
                                                        safetyFlags = session.safetyFlags.toLong(),
                                                        deloadWarningCount = session.deloadWarningCount.toLong(),
                                                        romViolationCount = session.romViolationCount.toLong(),
                                                        spotterActivations = session.spotterActivations.toLong(),
                                                        peakForceConcentricA = session.peakForceConcentricA?.toDouble(),
                                                        peakForceConcentricB = session.peakForceConcentricB?.toDouble(),
                                                        peakForceEccentricA = session.peakForceEccentricA?.toDouble(),
                                                        peakForceEccentricB = session.peakForceEccentricB?.toDouble(),
                                                        avgForceConcentricA = session.avgForceConcentricA?.toDouble(),
                                                        avgForceConcentricB = session.avgForceConcentricB?.toDouble(),
                                                        avgForceEccentricA = session.avgForceEccentricA?.toDouble(),
                                                        avgForceEccentricB = session.avgForceEccentricB?.toDouble(),
                                                        heaviestLiftKg = session.heaviestLiftKg?.toDouble(),
                                                        totalVolumeKg = session.totalVolumeKg?.toDouble(),
                                                        cableCount = session.cableCount?.toLong(),
                                                        display_multiplier = session.displayMultiplier?.toLong(),
                                                        externalAddedLoadKg = session.externalAddedLoadKg.toDouble(),
                                                        counterweightKg = session.counterweightKg.toDouble(),
                                                        rackItemsJson = session.rackItemsJson,
                                                        estimatedCalories = session.estimatedCalories?.toDouble(),
                                                        warmupAvgWeightKg = session.warmupAvgWeightKg?.toDouble(),
                                                        workingAvgWeightKg = session.workingAvgWeightKg?.toDouble(),
                                                        burnoutAvgWeightKg = session.burnoutAvgWeightKg?.toDouble(),
                                                        peakWeightKg = session.peakWeightKg?.toDouble(),
                                                        rpe = session.rpe?.toLong(),
                                                        avgMcvMmS = session.avgMcvMmS?.toDouble(),
                                                        avgAsymmetryPercent = session.avgAsymmetryPercent?.toDouble(),
                                                        totalVelocityLossPercent = session.totalVelocityLossPercent?.toDouble(),
                                                        dominantSide = session.dominantSide,
                                                        strengthProfile = session.strengthProfile,
                                                        formScore = session.formScore,
                                                        profile_id = sessionProfileId,
                                                    )
                                                }
                                                if (inserted != null) {
                                                    sessionsImported++
                                                    resolvedRoutineSessionId?.let { groupId ->
                                                        restoredRoutineGroupIds.getOrPut(sessionProfileId) { linkedSetOf() } += groupId
                                                    }
                                                    if (session.portalOrigin || session.updatedAt != null || session.syncAcknowledged) {
                                                        // Same transaction as the insert, so an aborted
                                                        // restore never leaves a half-marked row.
                                                        queries.restoreSessionSyncMarkers(
                                                            portalOrigin = if (session.portalOrigin) 1L else 0L,
                                                            updatedAt = session.updatedAt,
                                                            acknowledged = if (session.syncAcknowledged) 1L else 0L,
                                                            id = session.id,
                                                        )
                                                        restoredSessionSyncMarkers += session
                                                    }
                                                    recordParent("session", session.id, BackupParentStatus.INSERTED)
                                                } else {
                                                    recordParent("session", session.id, BackupParentStatus.UNAVAILABLE)
                                                }
                                            } else {
                                                // Sync markers are bookkeeping, not content: a v6 file (no markers)
                                                // still matches a row that has since been synced.
                                                val existingBackup = mapSessionToBackup(existingSession).copy(
                                                    updatedAt = normalizedSession.updatedAt,
                                                    portalOrigin = normalizedSession.portalOrigin,
                                                    syncAcknowledged = normalizedSession.syncAcknowledged,
                                                )
                                                val legacyProfileOnlyAdoption = backupVersion < 6 &&
                                                    existingSession.profile_id != sessionProfileId &&
                                                    queries.getProfileById(existingSession.profile_id).executeAsOneOrNull() == null &&
                                                    existingBackup.copy(profileId = sessionProfileId) == normalizedSession
                                                if (existingBackup != normalizedSession && !legacyProfileOnlyAdoption) {
                                                    entitiesWithErrors++
                                                    recordParent("session", session.id, BackupParentStatus.UNAVAILABLE)
                                                    Logger.w { "Backup restore conflict session id=${session.id}; local row preserved" }
                                                    continue
                                                }
                                                if (legacyProfileOnlyAdoption) {
                                                    queries.adoptSessionProfile(profileId = sessionProfileId, id = session.id)
                                                }
                                                if (normalizedSession.syncAcknowledged &&
                                                    existingSession.synced_sync_generation >= existingSession.local_sync_generation
                                                ) {
                                                    matchedCleanSessionIds += session.id
                                                }
                                                existingSession.routineSessionId?.let { groupId ->
                                                    matchedSessionGroups[session.id] = sessionProfileId to groupId
                                                }
                                                sessionsSkipped++
                                                recordParent("session", session.id, BackupParentStatus.MATCHING)
                                            }
                                        }
                                        nav.endArray()
                                    }
                                    onProgress(BackupProgress(BackupPhase.SESSIONS, sessionsImported.toLong(), sessionsImported.toLong()))
                                }

                                // --- metricSamples ---
                                "metricSamples" -> {
                                    onProgress(BackupProgress(BackupPhase.METRICS, 0, 0))
                                    nav.beginArray()
                                    var batchCount = 0
                                    var totalMetricsSeen = 0L
                                    committedTransaction {
                                        while (nav.hasNextInArray()) {
                                            val rawJson = nav.nextValueAsString()
                                            val metric = tryImport("metric-parse", null) {
                                                json.decodeFromString<MetricSampleBackup>(rawJson)
                                            }
                                            if (metric != null) {
                                                restoreMetric(metric)
                                                batchCount++
                                            }
                                            totalMetricsSeen++
                                            if (batchCount >= IMPORT_BATCH_SIZE) {
                                                // Commit current transaction and start a new one
                                                // (handled by ending this transaction block and
                                                // starting a new one below after endArray or next batch)
                                                break
                                            }
                                            if (totalMetricsSeen % 10_000 == 0L) {
                                                onProgress(BackupProgress(BackupPhase.METRICS, metricsImported.toLong(), 0))
                                            }
                                        }
                                    }
                                    // Continue batching remaining metrics
                                    while (nav.hasNextInArray()) {
                                        batchCount = 0
                                        committedTransaction {
                                            // Process up to IMPORT_BATCH_SIZE metrics per transaction
                                            do {
                                                val rawJson = nav.nextValueAsString()
                                                val metric = tryImport("metric-parse", null) {
                                                    json.decodeFromString<MetricSampleBackup>(rawJson)
                                                }
                                                if (metric != null) {
                                                    restoreMetric(metric)
                                                    batchCount++
                                                }
                                                totalMetricsSeen++
                                                if (totalMetricsSeen % 10_000 == 0L) {
                                                    onProgress(BackupProgress(BackupPhase.METRICS, metricsImported.toLong(), 0))
                                                }
                                            } while (batchCount < IMPORT_BATCH_SIZE && nav.hasNextInArray())
                                        }
                                    }
                                    nav.endArray()
                                    onProgress(BackupProgress(BackupPhase.METRICS, metricsImported.toLong(), metricsImported.toLong()))
                                }

                                // --- routines ---
                                "routines" -> {
                                    onProgress(BackupProgress(BackupPhase.ROUTINES, 0, 0))
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val rawJson = nav.nextValueAsString()
                                            val routine = tryImport("routine-parse", null) {
                                                json.decodeFromString<RoutineBackup>(rawJson)
                                            } ?: continue

                                            if (backupVersion >= 6 && routine.profileId != null &&
                                                !profileParentAvailable(routine.profileId)
                                            ) {
                                                entitiesWithErrors++
                                                recordParent("routine", routine.id, BackupParentStatus.UNAVAILABLE)
                                                Logger.w { "Backup restore missing required profile ${routine.profileId} for routine ${routine.id}" }
                                                continue
                                            }
                                            val routineProfileId = routine.profileId?.takeIf {
                                                profileParentAvailable(it)
                                            } ?: legacyFallbackProfileId()
                                            if (!ownershipClaimAllows("ROUTINE", routine.id, routineProfileId)) {
                                                entitiesWithErrors++
                                                recordParent("routine", routine.id, BackupParentStatus.UNAVAILABLE)
                                                Logger.w { "Backup restore ownership conflict routine id=${routine.id} profile=$routineProfileId" }
                                                continue
                                            }
                                            val groupId = routine.groupId?.takeIf {
                                                queries.selectRoutineGroupById(it).executeAsOneOrNull() != null
                                            }.also { if (routine.groupId != null && it == null) repairedReferences++ }

                                            val existingRoutine = queries.selectRoutineById(routine.id).executeAsOneOrNull()
                                            if (existingRoutine == null) {
                                                queries.insertRoutine(
                                                    id = routine.id,
                                                    name = routine.name,
                                                    description = routine.description,
                                                    createdAt = routine.createdAt,
                                                    lastUsed = routine.lastUsed,
                                                    useCount = routine.useCount.toLong(),
                                                    profile_id = routineProfileId,
                                                    groupId = groupId,
                                                    deletedAt = routine.deletedAt,
                                                )
                                                routinesImported++
                                                recordParent("routine", routine.id, BackupParentStatus.INSERTED)
                                            } else {
                                                val expected = routine.copy(profileId = routineProfileId, groupId = groupId)
                                                val existingBackup = mapRoutineToBackup(existingRoutine)
                                                val legacyProfileOnlyAdoption = backupVersion < 6 &&
                                                    existingRoutine.profile_id != routineProfileId &&
                                                    queries.getProfileById(existingRoutine.profile_id).executeAsOneOrNull() == null &&
                                                    existingBackup.copy(profileId = routineProfileId) == expected
                                                if (existingBackup != expected && !legacyProfileOnlyAdoption) {
                                                    entitiesWithErrors++
                                                    recordParent("routine", routine.id, BackupParentStatus.UNAVAILABLE)
                                                    Logger.w { "Backup restore conflict routine id=${routine.id}; local row preserved" }
                                                    continue
                                                }
                                                if (legacyProfileOnlyAdoption) {
                                                    queries.adoptRoutineProfile(profileId = routineProfileId, id = routine.id)
                                                }
                                                routinesSkipped++
                                                recordParent("routine", routine.id, BackupParentStatus.MATCHING)
                                            }
                                        }
                                        nav.endArray()
                                    }
                                }

                                // --- routineGroups (audit F068) ---
                                // The streaming importer previously had no case for
                                // routineGroups, so v4 backup group data was treated
                                // as an unknown field and skipped, silently dropping
                                // routine group organization on restore. Routine.groupId
                                // has no FK constraint, so import order does not matter.
                                "routineGroups" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val rawJson = nav.nextValueAsString()
                                            val group = tryImport("routineGroup-parse", null) {
                                                json.decodeFromString<RoutineGroupBackup>(rawJson)
                                            } ?: continue
                                            if (backupVersion >= 6 && !profileParentAvailable(group.profileId)) {
                                                entitiesWithErrors++
                                                Logger.w { "Backup restore missing required profile ${group.profileId} for routineGroup ${group.id}" }
                                                continue
                                            }
                                            val existing = queries.selectRoutineGroupById(group.id).executeAsOneOrNull()
                                            if (existing != null) {
                                                if (mapRoutineGroupToBackup(existing) == group) routineGroupsSkipped++ else {
                                                    entitiesWithErrors++
                                                    Logger.w { "Backup restore conflict routineGroup id=${group.id}" }
                                                }
                                                continue
                                            }
                                            val inserted = tryImport("routineGroup", group.id) {
                                                queries.insertRoutineGroupIgnore(
                                                    id = group.id,
                                                    name = group.name,
                                                    orderIndex = group.orderIndex.toLong(),
                                                    createdAt = group.createdAt,
                                                    profile_id = group.profileId,
                                                )
                                            }
                                            if (inserted != null) routineGroupsImported++ else routineGroupsSkipped++
                                        }
                                        nav.endArray()
                                    }
                                }

                                // --- supersets ---
                                "supersets" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val rawJson = nav.nextValueAsString()
                                            val superset = tryImport("superset-parse", null) {
                                                json.decodeFromString<SupersetBackup>(rawJson)
                                            } ?: continue

                                            if (parentAvailable("routine", superset.routineId) &&
                                                queries.selectRoutineById(superset.routineId).executeAsOneOrNull() != null
                                            ) {
                                                val existing = queries.selectSupersetById(superset.id).executeAsOneOrNull()
                                                if (existing == null) {
                                                    queries.insertSupersetIgnore(
                                                        id = superset.id,
                                                        routineId = superset.routineId,
                                                        name = superset.name,
                                                        colorIndex = superset.colorIndex.toLong(),
                                                        restBetweenSeconds = superset.restBetweenSeconds.toLong(),
                                                        orderIndex = superset.orderIndex.toLong(),
                                                    )
                                                    supersetsImported++
                                                    recordParent("superset", superset.id, BackupParentStatus.INSERTED)
                                                } else {
                                                    if (mapSupersetToBackup(existing) == superset) {
                                                        supersetsSkipped++
                                                        recordParent("superset", superset.id, BackupParentStatus.MATCHING)
                                                    } else {
                                                        entitiesWithErrors++
                                                        recordParent("superset", superset.id, BackupParentStatus.UNAVAILABLE)
                                                        Logger.w { "Backup restore conflict superset id=${superset.id}" }
                                                    }
                                                }
                                            } else {
                                                entitiesWithErrors++
                                                recordParent("superset", superset.id, BackupParentStatus.UNAVAILABLE)
                                                Logger.w { "Backup restore missing required routine ${superset.routineId} for superset ${superset.id}" }
                                            }
                                        }
                                        nav.endArray()
                                    }
                                }

                                // --- routineExercises ---
                                "routineExercises" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val rawJson = nav.nextValueAsString()
                                            val exercise = tryImport("routineExercise-parse", null) {
                                                json.decodeFromString<RoutineExerciseBackup>(rawJson)
                                            } ?: continue

                                            if (!parentAvailable("routine", exercise.routineId) ||
                                                queries.selectRoutineById(exercise.routineId).executeAsOneOrNull() == null
                                            ) {
                                                entitiesWithErrors++
                                                recordParent("routineExercise", exercise.id, BackupParentStatus.UNAVAILABLE)
                                                Logger.w { "Backup restore missing required routine ${exercise.routineId} for routineExercise ${exercise.id}" }
                                                continue
                                            }
                                            run {
                                                val safeExerciseEccentricLoad = exercise.eccentricLoad.sanitizeEccentricLoad()
                                                if (exercise.eccentricLoad != safeExerciseEccentricLoad) {
                                                    Logger.w { "Streaming import: routine exercise ${exercise.exerciseName} eccentricLoad ${exercise.eccentricLoad}% clamped to $safeExerciseEccentricLoad% (hardware limit)" }
                                                }

                                                val resolvedExerciseId = exercise.exerciseId?.takeIf {
                                                    queries.selectExerciseById(it).executeAsOneOrNull() != null
                                                }.also {
                                                    if (exercise.exerciseId != null && it == null) repairedReferences++
                                                }
                                                val resolvedSupersetId = exercise.supersetId?.takeIf {
                                                    parentAvailable("superset", it) && queries.selectSupersetById(it).executeAsOneOrNull() != null
                                                }.also {
                                                    if (exercise.supersetId != null && it == null) repairedReferences++
                                                }
                                                val safeExerciseProgressionKg = exercise.progressionKg.sanitizeProgressionKg()
                                                if (exercise.progressionKg != safeExerciseProgressionKg) {
                                                    Logger.w { "Streaming import: routine exercise ${exercise.id} progressionKg ${exercise.progressionKg} clamped to $safeExerciseProgressionKg" }
                                                }
                                                val normalizedExercise = exercise.copy(
                                                    exerciseEquipment = resolveBackupEquipment(exercise),
                                                    exerciseId = resolvedExerciseId,
                                                    eccentricLoad = safeExerciseEccentricLoad,
                                                    progressionKg = safeExerciseProgressionKg,
                                                    supersetId = resolvedSupersetId,
                                                    defaultRackItemIds = decodeRackItemIds(sanitizeRackItemIds(exercise.defaultRackItemIds)),
                                                    scalingBasis = exercise.scalingBasis?.let {
                                                        runCatching { com.devil.phoenixproject.domain.model.ScalingBasis.valueOf(it) }.getOrNull()
                                                    }?.name,
                                                    isBodyweight = resolveBackupIsBodyweight(exercise)?.let { it != 0L },
                                                )
                                                val existing = queries.selectRoutineExerciseById(exercise.id).executeAsOneOrNull()
                                                if (existing != null) {
                                                    if (mapRoutineExerciseToBackup(existing) == normalizedExercise) {
                                                        routineExercisesSkipped++
                                                        recordParent("routineExercise", exercise.id, BackupParentStatus.MATCHING)
                                                    } else {
                                                        entitiesWithErrors++
                                                        recordParent("routineExercise", exercise.id, BackupParentStatus.UNAVAILABLE)
                                                        Logger.w { "Backup restore conflict routineExercise id=${exercise.id}" }
                                                    }
                                                    return@run
                                                }

                                                val inserted = tryImport("routineExercise", exercise.id) {
                                                    queries.insertRoutineExerciseIgnore(
                                                        id = exercise.id,
                                                        routineId = exercise.routineId,
                                                        exerciseName = exercise.exerciseName,
                                                        exerciseMuscleGroup = exercise.exerciseMuscleGroup,
                                                        exerciseEquipment = resolveBackupEquipment(exercise),
                                                        exerciseDefaultCableConfig = exercise.exerciseDefaultCableConfig,
                                                        exerciseId = resolvedExerciseId,
                                                        cableConfig = exercise.cableConfig,
                                                        orderIndex = exercise.orderIndex.toLong(),
                                                        setReps = exercise.setReps,
                                                        weightPerCableKg = exercise.weightPerCableKg.toDouble(),
                                                        setWeights = exercise.setWeights,
                                                        mode = exercise.mode,
                                                        eccentricLoad = safeExerciseEccentricLoad.toLong(),
                                                        echoLevel = exercise.echoLevel.toLong(),
                                                        progressionKg = safeExerciseProgressionKg.toDouble(),
                                                        restSeconds = exercise.restSeconds.toLong(),
                                                        duration = exercise.duration?.toLong(),
                                                        setRestSeconds = exercise.setRestSeconds,
                                                        perSetRestTime = if (exercise.perSetRestTime) 1L else 0L,
                                                        isAMRAP = if (exercise.isAMRAP) 1L else 0L,
                                                        supersetId = resolvedSupersetId,
                                                        orderInSuperset = exercise.orderInSuperset.toLong(),
                                                        usePercentOfPR = if (exercise.usePercentOfPR) 1L else 0L,
                                                        weightPercentOfPR = exercise.weightPercentOfPR.toLong(),
                                                        prTypeForScaling = exercise.prTypeForScaling,
                                                        setWeightsPercentOfPR = exercise.setWeightsPercentOfPR,
                                                        stallDetectionEnabled = if (exercise.stallDetectionEnabled) 1L else 0L,
                                                        stopAtTop = if (exercise.stopAtTop) 1L else 0L,
                                                        repCountTiming = exercise.repCountTiming,
                                                        setEchoLevels = exercise.setEchoLevels,
                                                        warmupSets = exercise.warmupSets,
                                                        defaultRackItemIds = sanitizeRackItemIds(exercise.defaultRackItemIds),
                                                        rackBehaviorOverrides = exercise.rackBehaviorOverrides,
                                                        scalingBasis = exercise.scalingBasis?.let {
                                                            runCatching { com.devil.phoenixproject.domain.model.ScalingBasis.valueOf(it) }.getOrNull()
                                                        }?.name,
                                                        isBodyweight = resolveBackupIsBodyweight(exercise),
                                                        dropSetEnabled = if (exercise.dropSetEnabled) 1L else 0L,
                                                        dropSetMinWeightKg = exercise.dropSetMinWeightKg?.toDouble(),
                                                    )
                                                }
                                                if (inserted != null) {
                                                    routineExercisesImported++
                                                    recordParent("routineExercise", exercise.id, BackupParentStatus.INSERTED)
                                                } else {
                                                    recordParent("routineExercise", exercise.id, BackupParentStatus.UNAVAILABLE)
                                                }
                                            }
                                        }
                                        nav.endArray()
                                    }
                                }

                                // --- personalRecords ---
                                "personalRecords" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val rawJson = nav.nextValueAsString()
                                            val pr = tryImport("pr-parse", null) {
                                                json.decodeFromString<PersonalRecordBackup>(rawJson)
                                            } ?: continue
                                            val profileId = pr.profileId ?: legacyFallbackProfileId()
                                            if (backupVersion >= 6 && pr.profileId != null &&
                                                !profileParentAvailable(profileId)
                                            ) {
                                                entitiesWithErrors++
                                                Logger.w { "Backup restore missing required profile $profileId for personalRecord ${pr.id}" }
                                                continue
                                            }
                                            if (queries.selectExerciseById(pr.exerciseId).executeAsOneOrNull() == null) {
                                                entitiesWithErrors++
                                                Logger.w { "Backup restore missing required exercise ${pr.exerciseId} for personalRecord ${pr.id}" }
                                                continue
                                            }
                                            val phase = pr.phase ?: "COMBINED"
                                            val key = PersonalRecordKey(pr.exerciseId, pr.workoutMode, pr.prType, phase, profileId)
                                            if (pr.uuid != null && !ownershipClaimAllows("PERSONAL_RECORD", pr.uuid, profileId)) {
                                                entitiesWithErrors++
                                                Logger.w { "Backup restore ownership conflict personalRecord uuid=${pr.uuid} profile=$profileId" }
                                                continue
                                            }
                                            val existing = queries.selectPersonalRecordsByExerciseId(pr.exerciseId).executeAsList()
                                                .firstOrNull {
                                                    it.workoutMode == pr.workoutMode && it.prType == pr.prType &&
                                                        it.phase == phase && it.profile_id == profileId
                                                }
                                            if (existing != null) {
                                                val existingBackup = mapPersonalRecordToBackup(existing)
                                                val expected = pr.copy(id = existing.id, phase = phase, profileId = profileId, uuid = existing.uuid)
                                                if (existingBackup == expected && (pr.uuid == null || pr.uuid == existing.uuid)) {
                                                    personalRecordsSkipped++
                                                } else {
                                                    entitiesWithErrors++
                                                    Logger.w { "Backup restore conflict personalRecord key=$key; local row preserved" }
                                                }
                                                continue
                                            }

                                            val inserted = tryImport("personalRecord", pr.uuid) {
                                                queries.insertPRIgnore(
                                                    exerciseId = pr.exerciseId,
                                                    exerciseName = pr.exerciseName,
                                                    weight = pr.weight.toDouble(),
                                                    reps = pr.reps.toLong(),
                                                    oneRepMax = pr.oneRepMax.toDouble(),
                                                    achievedAt = pr.achievedAt,
                                                    workoutMode = pr.workoutMode,
                                                    prType = pr.prType,
                                                    volume = pr.volume.toDouble(),
                                                    phase = phase,
                                                    profile_id = profileId,
                                                    cable_count = pr.cableCount?.toLong(),
                                                    uuid = pr.uuid ?: generateUUID(),
                                                )
                                            }
                                            if (inserted != null) {
                                                personalRecordsImported++
                                            }
                                        }
                                        nav.endArray()
                                    }
                                }

                                // --- trainingCycles ---
                                "trainingCycles" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val rawJson = nav.nextValueAsString()
                                            val cycle = tryImport("cycle-parse", null) {
                                                json.decodeFromString<TrainingCycleBackup>(rawJson)
                                            } ?: continue

                                            if (backupVersion >= 6 && cycle.profileId != null &&
                                                !profileParentAvailable(cycle.profileId)
                                            ) {
                                                entitiesWithErrors++
                                                recordParent("cycle", cycle.id, BackupParentStatus.UNAVAILABLE)
                                                Logger.w { "Backup restore missing required profile ${cycle.profileId} for cycle ${cycle.id}" }
                                                continue
                                            }
                                            val cycleProfileId = cycle.profileId ?: legacyFallbackProfileId()
                                            if (!ownershipClaimAllows("CYCLE", cycle.id, cycleProfileId)) {
                                                entitiesWithErrors++
                                                recordParent("cycle", cycle.id, BackupParentStatus.UNAVAILABLE)
                                                Logger.w { "Backup restore ownership conflict cycle id=${cycle.id} profile=$cycleProfileId" }
                                                continue
                                            }

                                            val existingCycle = queries.selectTrainingCycleById(cycle.id).executeAsOneOrNull()
                                            if (existingCycle == null) {
                                                queries.insertTrainingCycle(
                                                    id = cycle.id,
                                                    name = cycle.name,
                                                    description = cycle.description,
                                                    created_at = cycle.createdAt,
                                                    is_active = if (cycle.isActive) 1L else 0L,
                                                    profile_id = cycleProfileId,
                                                    template_id = cycle.templateId,
                                                    week_number = cycle.weekNumber.toLong(),
                                                    updatedAt = cycle.updatedAt ?: cycle.createdAt,
                                                )
                                                restoreCycleServerVersion(cycle)
                                                cycle.deletedAt?.let { deletedAt ->
                                                    queries.softDeleteTrainingCycle(
                                                        deletedAt = deletedAt,
                                                        updatedAt = cycle.updatedAt ?: deletedAt,
                                                        id = cycle.id,
                                                    )
                                                }
                                                trainingCyclesImported++
                                                recordParent("cycle", cycle.id, BackupParentStatus.INSERTED)
                                            } else {
                                                val requestedProfile = cycleProfileId
                                                val expected = cycle.copy(
                                                    profileId = requestedProfile,
                                                    updatedAt = cycle.updatedAt ?: cycle.createdAt,
                                                )
                                                if (!trainingCycleBackupMatches(existingCycle, expected)) {
                                                    entitiesWithErrors++
                                                    recordParent("cycle", cycle.id, BackupParentStatus.UNAVAILABLE)
                                                    Logger.w { "Backup restore conflict trainingCycle id=${cycle.id}; local row preserved" }
                                                    continue
                                                } else {
                                                    trainingCyclesSkipped++
                                                    recordParent("cycle", cycle.id, BackupParentStatus.MATCHING)
                                                }
                                            }
                                            if (backupVersion < 6 && queries.selectCycleSyncState(cycle.id).executeAsOneOrNull() == null) {
                                                queries.insertCycleSyncStateIfAbsent(
                                                    cycleId = cycle.id,
                                                    profileId = cycleProfileId,
                                                    accountId = null,
                                                    dirtyGeneration = 1L,
                                                    acknowledgedGeneration = 0L,
                                                    pendingDeleteUpdatedAt = null,
                                                    pendingDeleteGeneration = null,
                                                )
                                            }
                                        }
                                        nav.endArray()
                                    }
                                }

                                "cycleSyncStates" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val state = json.decodeFromString<CycleSyncStateBackup>(nav.nextValueAsString())
                                            if (!parentAvailable("cycle", state.cycleId) ||
                                                queries.selectTrainingCycleById(state.cycleId).executeAsOneOrNull() == null ||
                                                state.dirtyGeneration < 0L ||
                                                state.acknowledgedGeneration < 0L || state.acknowledgedGeneration > state.dirtyGeneration
                                            ) {
                                                entitiesWithErrors++
                                                Logger.w { "Backup restore invalid cycleSyncState cycleId=${state.cycleId}" }
                                                continue
                                            }
                                            val cycle = queries.selectTrainingCycleById(state.cycleId).executeAsOneOrNull()
                                            if (cycle == null || cycle.profile_id != state.profileId ||
                                                !profileParentAvailable(state.profileId)
                                            ) {
                                                entitiesWithErrors++
                                                Logger.w { "Backup restore cycleSyncState profile mismatch cycleId=${state.cycleId}" }
                                                continue
                                            }
                                            val existing = queries.selectCycleSyncState(state.cycleId).executeAsOneOrNull()
                                            if (existing == null) {
                                                queries.insertCycleSyncStateIfAbsent(
                                                    cycleId = state.cycleId,
                                                    profileId = state.profileId,
                                                    accountId = state.accountId,
                                                    dirtyGeneration = state.dirtyGeneration,
                                                    acknowledgedGeneration = state.acknowledgedGeneration,
                                                    pendingDeleteUpdatedAt = state.pendingDeleteUpdatedAt,
                                                    pendingDeleteGeneration = state.pendingDeleteGeneration,
                                                )
                                                cycleSyncStatesImported++
                                            } else if (mapCycleSyncStateToBackup(existing) == state) {
                                                cycleSyncStatesSkipped++
                                            } else {
                                                entitiesWithErrors++
                                                Logger.w { "Backup restore conflict cycleSyncState cycleId=${state.cycleId}; original account preserved" }
                                            }
                                        }
                                        nav.endArray()
                                    }
                                }

                                "cycleConflictDrafts" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val draft = json.decodeFromString<CycleConflictDraftBackup>(nav.nextValueAsString())
                                            val existing = queries.selectCycleConflictDraftById(draft.id).executeAsOneOrNull()
                                            if (existing == null) {
                                                queries.insertCycleConflictDraftIfAbsent(
                                                    id = draft.id,
                                                    cycleId = draft.cycleId,
                                                    originalProfileId = draft.originalProfileId,
                                                    rejectedUpdatedAt = draft.rejectedUpdatedAt,
                                                    payloadJson = draft.payloadJson,
                                                    createdAt = draft.createdAt,
                                                    resolution = draft.resolution,
                                                )
                                                cycleConflictDraftsImported++
                                            } else if (mapCycleConflictDraftToBackup(existing) == draft) {
                                                cycleConflictDraftsSkipped++
                                            } else {
                                                entitiesWithErrors++
                                                Logger.w { "Backup restore conflict cycleConflictDraft id=${draft.id}" }
                                            }
                                        }
                                        nav.endArray()
                                    }
                                }

                                // --- cycleDays ---
                                "cycleDays" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val rawJson = nav.nextValueAsString()
                                            val day = tryImport("cycleDay-parse", null) {
                                                json.decodeFromString<CycleDayBackup>(rawJson)
                                            } ?: continue

                                            if (parentAvailable("cycle", day.cycleId) &&
                                                queries.selectTrainingCycleById(day.cycleId).executeAsOneOrNull() != null
                                            ) {
                                                val routineId = day.routineId?.takeIf {
                                                    parentAvailable("routine", it) && queries.selectRoutineById(it).executeAsOneOrNull() != null
                                                }
                                                    .also { if (day.routineId != null && it == null) repairedReferences++ }
                                                val normalizedDay = day.copy(routineId = routineId)
                                                val existing = queries.selectCycleDayById(day.id).executeAsOneOrNull()
                                                if (existing != null) {
                                                    if (mapCycleDayToBackup(existing) == normalizedDay) cycleDaysSkipped++ else {
                                                        entitiesWithErrors++
                                                        Logger.w { "Backup restore conflict cycleDay id=${day.id}" }
                                                    }
                                                    continue
                                                }
                                                val inserted = tryImport("cycleDay", day.id) {
                                                    queries.insertCycleDay(
                                                        id = day.id,
                                                        cycle_id = day.cycleId,
                                                        day_number = day.dayNumber.toLong(),
                                                        name = day.name,
                                                        routine_id = routineId,
                                                        is_rest_day = if (day.isRestDay) 1L else 0L,
                                                        echo_level = day.echoLevel,
                                                        eccentric_load_percent = day.eccentricLoadPercent?.toLong(),
                                                        weight_progression_percent = day.weightProgressionPercent?.toDouble(),
                                                        rep_modifier = day.repModifier?.toLong(),
                                                        rest_time_override_seconds = day.restTimeOverrideSeconds?.toLong(),
                                                    )
                                                }
                                                if (inserted != null) {
                                                    cycleDaysImported++
                                                }
                                            } else {
                                                entitiesWithErrors++
                                                Logger.w { "Backup restore missing required cycle ${day.cycleId} for cycleDay ${day.id}" }
                                            }
                                        }
                                        nav.endArray()
                                    }
                                }

                                // --- userProfiles ---
                                "userProfiles" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val rawJson = nav.nextValueAsString()
                                            val profile = tryImport("userProfile-parse", null) {
                                                json.decodeFromString<UserProfileBackup>(rawJson)
                                            } ?: continue

                                            representedProfileIds.add(profile.id)
                                            val existingProfile = queries.getProfileById(profile.id).executeAsOneOrNull()
                                            if (existingProfile == null) {
                                                queries.insertUserProfileIgnore(
                                                    id = profile.id,
                                                    name = profile.name,
                                                    colorIndex = profile.colorIndex.toLong(),
                                                    createdAt = profile.createdAt,
                                                    isActive = 0L,
                                                )
                                                profile.supabaseUserId?.let { ownerUserId ->
                                                    queries.linkProfileToSupabase(ownerUserId, null, profile.id)
                                                }
                                                userProfilesImported++
                                                recordParent("profile", profile.id, BackupParentStatus.INSERTED)
                                            } else {
                                                val ownerCompatible = profile.supabaseUserId == null ||
                                                    existingProfile.supabase_user_id == null ||
                                                    existingProfile.supabase_user_id == profile.supabaseUserId
                                                if (ownerCompatible) {
                                                    if (existingProfile.supabase_user_id == null && profile.supabaseUserId != null) {
                                                        queries.linkProfileToSupabase(profile.supabaseUserId, null, profile.id)
                                                    }
                                                    userProfilesSkipped++
                                                    recordParent("profile", profile.id, BackupParentStatus.MATCHING)
                                                } else {
                                                    entitiesWithErrors++
                                                    recordParent("profile", profile.id, BackupParentStatus.UNAVAILABLE)
                                                    Logger.w { "Backup restore conflict userProfile id=${profile.id}" }
                                                    continue
                                                }
                                            }
                                            queries.insertDefaultProfilePreferences(profile.id, 1L)
                                        }
                                        nav.endArray()
                                    }
                                }

                                // --- cycleProgress ---
                                "cycleProgress" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val rawJson = nav.nextValueAsString()
                                            val progress = tryImport("cycleProgress-parse", null) {
                                                json.decodeFromString<CycleProgressBackup>(rawJson)
                                            } ?: continue

                                            if (parentAvailable("cycle", progress.cycleId) &&
                                                queries.selectTrainingCycleById(progress.cycleId).executeAsOneOrNull() != null
                                            ) {
                                                val existing = queries.selectCycleProgressById(progress.id).executeAsOneOrNull()
                                                    ?: queries.selectCycleProgressByCycle(progress.cycleId).executeAsOneOrNull()
                                                if (existing != null) {
                                                    if (mapCycleProgressToBackup(existing) == progress) cycleProgressSkipped++ else {
                                                        entitiesWithErrors++
                                                        Logger.w { "Backup restore conflict cycleProgress id=${progress.id}" }
                                                    }
                                                    continue
                                                }
                                                queries.insertCycleProgressIgnore(
                                                    id = progress.id,
                                                    cycle_id = progress.cycleId,
                                                    current_day_number = progress.currentDayNumber.toLong(),
                                                    last_completed_date = progress.lastCompletedDate,
                                                    cycle_start_date = progress.cycleStartDate,
                                                    last_advanced_at = progress.lastAdvancedAt,
                                                    completed_days = progress.completedDays,
                                                    missed_days = progress.missedDays,
                                                    rotation_count = progress.rotationCount.toLong(),
                                                )
                                                cycleProgressImported++
                                            } else {
                                                entitiesWithErrors++
                                                Logger.w { "Backup restore missing required cycle ${progress.cycleId} for cycleProgress ${progress.id}" }
                                            }
                                        }
                                        nav.endArray()
                                    }
                                }

                                // --- cycleProgressions ---
                                "cycleProgressions" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val rawJson = nav.nextValueAsString()
                                            val progression = tryImport("cycleProgression-parse", null) {
                                                json.decodeFromString<CycleProgressionBackup>(rawJson)
                                            } ?: continue

                                            if (parentAvailable("cycle", progression.cycleId) &&
                                                queries.selectTrainingCycleById(progression.cycleId).executeAsOneOrNull() != null
                                            ) {
                                                val existing = queries.selectCycleProgression(progression.cycleId).executeAsOneOrNull()
                                                if (existing != null) {
                                                    if (mapCycleProgressionToBackup(existing) == progression) cycleProgressionsSkipped++ else {
                                                        entitiesWithErrors++
                                                        Logger.w { "Backup restore conflict cycleProgression cycleId=${progression.cycleId}" }
                                                    }
                                                    continue
                                                }
                                                queries.insertCycleProgressionIgnore(
                                                    cycle_id = progression.cycleId,
                                                    frequency_cycles = progression.frequencyCycles.toLong(),
                                                    weight_increase_percent = progression.weightIncreasePercent?.toDouble(),
                                                    echo_level_increase = progression.echoLevelIncrease.toLong(),
                                                    eccentric_load_increase_percent = progression.eccentricLoadIncreasePercent?.toLong(),
                                                )
                                                cycleProgressionsImported++
                                            } else {
                                                entitiesWithErrors++
                                                Logger.w { "Backup restore missing required cycle ${progression.cycleId} for cycleProgression" }
                                            }
                                        }
                                        nav.endArray()
                                    }
                                }

                                // --- plannedSets ---
                                "plannedSets" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val rawJson = nav.nextValueAsString()
                                            val plannedSet = tryImport("plannedSet-parse", null) {
                                                json.decodeFromString<PlannedSetBackup>(rawJson)
                                            } ?: continue

                                            if (!parentAvailable("routineExercise", plannedSet.routineExerciseId) ||
                                                queries.selectRoutineExerciseById(plannedSet.routineExerciseId).executeAsOneOrNull() == null
                                            ) {
                                                entitiesWithErrors++
                                                recordParent("plannedSet", plannedSet.id, BackupParentStatus.UNAVAILABLE)
                                                Logger.w { "Backup restore missing required routineExercise ${plannedSet.routineExerciseId} for plannedSet ${plannedSet.id}" }
                                            } else {
                                                val existing = queries.selectPlannedSetById(plannedSet.id).executeAsOneOrNull()
                                                if (existing != null) {
                                                    if (mapPlannedSetToBackup(existing) == plannedSet) {
                                                        plannedSetsSkipped++
                                                        recordParent("plannedSet", plannedSet.id, BackupParentStatus.MATCHING)
                                                    } else {
                                                        entitiesWithErrors++
                                                        recordParent("plannedSet", plannedSet.id, BackupParentStatus.UNAVAILABLE)
                                                        Logger.w { "Backup restore conflict plannedSet id=${plannedSet.id}" }
                                                    }
                                                    continue
                                                }
                                                queries.insertPlannedSetIgnore(
                                                    id = plannedSet.id,
                                                    routine_exercise_id = plannedSet.routineExerciseId,
                                                    set_number = plannedSet.setNumber.toLong(),
                                                    set_type = plannedSet.setType,
                                                    target_reps = plannedSet.targetReps?.toLong(),
                                                    target_weight_kg = plannedSet.targetWeightKg?.toDouble(),
                                                    target_rpe = plannedSet.targetRpe?.toLong(),
                                                    rest_seconds = plannedSet.restSeconds?.toLong(),
                                                )
                                                plannedSetsImported++
                                                recordParent("plannedSet", plannedSet.id, BackupParentStatus.INSERTED)
                                            }
                                        }
                                        nav.endArray()
                                    }
                                }

                                // --- completedSets ---
                                "completedSets" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val rawJson = nav.nextValueAsString()
                                            val completedSet = tryImport("completedSet-parse", null) {
                                                json.decodeFromString<CompletedSetBackup>(rawJson)
                                            } ?: continue

                                            if (!parentAvailable("session", completedSet.sessionId) ||
                                                queries.selectSessionById(completedSet.sessionId).executeAsOneOrNull() == null
                                            ) {
                                                entitiesWithErrors++
                                                Logger.w { "Backup restore missing required session ${completedSet.sessionId} for completedSet ${completedSet.id}" }
                                            } else {
                                                val plannedSetId = completedSet.plannedSetId?.takeIf {
                                                    when (parentStatus("plannedSet", it)) {
                                                        BackupParentStatus.UNAVAILABLE -> false
                                                        BackupParentStatus.INSERTED, BackupParentStatus.MATCHING ->
                                                            queries.selectPlannedSetById(it).executeAsOneOrNull() != null
                                                        null -> !parentIndexFailed &&
                                                            queries.selectPlannedSetById(it).executeAsOneOrNull() != null
                                                    }
                                                }
                                                    .also { if (completedSet.plannedSetId != null && it == null) repairedReferences++ }
                                                val routineExerciseId = completedSet.routineExerciseId?.takeIf {
                                                    when (parentStatus("routineExercise", it)) {
                                                        BackupParentStatus.UNAVAILABLE -> false
                                                        BackupParentStatus.INSERTED, BackupParentStatus.MATCHING ->
                                                            queries.selectRoutineExerciseById(it).executeAsOneOrNull() != null
                                                        null -> !parentIndexFailed // Preserve stable context in partial session backups.
                                                    }
                                                }
                                                    .also { if (completedSet.routineExerciseId != null && it == null) repairedReferences++ }
                                                val normalizedCompletedSet = completedSet.copy(
                                                    plannedSetId = plannedSetId,
                                                    routineExerciseId = routineExerciseId,
                                                    attemptNumber = completedSet.attemptNumber.coerceAtLeast(1),
                                                    setEndReason = SetEndReason.fromPersisted(completedSet.setEndReason).name,
                                                )
                                                val existing = queries.selectCompletedSetById(completedSet.id).executeAsOneOrNull()
                                                if (existing != null) {
                                                    if (mapCompletedSetToBackup(existing) == normalizedCompletedSet) completedSetsSkipped++ else {
                                                        entitiesWithErrors++
                                                        Logger.w { "Backup restore conflict completedSet id=${completedSet.id}" }
                                                    }
                                                    continue
                                                }
                                                queries.insertCompletedSetIgnore(
                                                    id = completedSet.id,
                                                    session_id = completedSet.sessionId,
                                                    planned_set_id = plannedSetId,
                                                    routine_exercise_id = routineExerciseId,
                                                    set_number = completedSet.setNumber.toLong(),
                                                    set_type = completedSet.setType,
                                                    attempt_number = completedSet.attemptNumber.coerceAtLeast(1).toLong(),
                                                    actual_reps = completedSet.actualReps.toLong(),
                                                    actual_weight_kg = completedSet.actualWeightKg.toDouble(),
                                                    logged_rpe = completedSet.loggedRpe?.toLong(),
                                                    is_pr = if (completedSet.isPr) 1L else 0L,
                                                    completed_at = completedSet.completedAt,
                                                    set_end_reason = SetEndReason.fromPersisted(completedSet.setEndReason).name,
                                                )
                                                queries.markWorkoutComponentDirty(completedSet.sessionId)
                                                holdGroupOfRestoredChild(completedSet.sessionId)
                                                completedSetsImported++
                                            }
                                        }
                                        nav.endArray()
                                    }
                                }

                                // --- progressionEvents ---
                                "progressionEvents" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val rawJson = nav.nextValueAsString()
                                            val event = tryImport("progressionEvent-parse", null) {
                                                json.decodeFromString<ProgressionEventBackup>(rawJson)
                                            } ?: continue

                                            if (queries.selectExerciseById(event.exerciseId).executeAsOneOrNull() == null) {
                                                entitiesWithErrors++
                                                Logger.w { "Backup restore missing required exercise ${event.exerciseId} for progressionEvent ${event.id}" }
                                                continue
                                            }
                                            val eventProfileId = event.profileId ?: legacyFallbackProfileId()
                                            if (backupVersion >= 6 && event.profileId != null &&
                                                !profileParentAvailable(eventProfileId)
                                            ) {
                                                entitiesWithErrors++
                                                Logger.w { "Backup restore missing required profile $eventProfileId for progressionEvent ${event.id}" }
                                                continue
                                            }
                                            val normalizedEvent = event.copy(profileId = eventProfileId)
                                            val existing = queries.selectProgressionEventById(event.id).executeAsOneOrNull()
                                            if (existing != null) {
                                                if (mapProgressionEventToBackup(existing) == normalizedEvent) progressionEventsSkipped++ else {
                                                    entitiesWithErrors++
                                                    Logger.w { "Backup restore conflict progressionEvent id=${event.id}" }
                                                }
                                                continue
                                            }

                                            queries.insertProgressionEventIgnore(
                                                id = event.id,
                                                exercise_id = event.exerciseId,
                                                suggested_weight_kg = event.suggestedWeightKg.toDouble(),
                                                previous_weight_kg = event.previousWeightKg.toDouble(),
                                                reason = event.reason,
                                                user_response = event.userResponse,
                                                actual_weight_kg = event.actualWeightKg?.toDouble(),
                                                timestamp = event.timestamp,
                                                profile_id = eventProfileId,
                                            )
                                            progressionEventsImported++
                                        }
                                        nav.endArray()
                                    }
                                }

                                // --- earnedBadges ---
                                "earnedBadges" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val rawJson = nav.nextValueAsString()
                                            val badge = tryImport("earnedBadge-parse", null) {
                                                json.decodeFromString<EarnedBadgeBackup>(rawJson)
                                            } ?: continue
                                            if (backupVersion >= 6 && !profileParentAvailable(badge.profileId)) {
                                                entitiesWithErrors++
                                                Logger.w { "Backup restore missing required profile ${badge.profileId} for earnedBadge ${badge.badgeId}" }
                                                continue
                                            }
                                            val existing = queries.selectEarnedBadgeById(badge.badgeId, badge.profileId).executeAsOneOrNull()
                                            if (existing != null) {
                                                if (mapEarnedBadgeToBackup(existing) == badge.copy(id = existing.id)) earnedBadgesSkipped++ else {
                                                    entitiesWithErrors++
                                                    Logger.w { "Backup restore conflict earnedBadge badgeId=${badge.badgeId} profile=${badge.profileId}" }
                                                }
                                                continue
                                            }

                                            val inserted = tryImport("earnedBadge", badge.badgeId) {
                                                queries.insertEarnedBadgeFullIgnore(
                                                    badgeId = badge.badgeId,
                                                    earnedAt = badge.earnedAt,
                                                    celebratedAt = badge.celebratedAt,
                                                    updatedAt = badge.updatedAt,
                                                    serverId = badge.serverId,
                                                    deletedAt = badge.deletedAt,
                                                    profile_id = badge.profileId,
                                                )
                                            }
                                            if (inserted != null) {
                                                earnedBadgesImported++
                                            }
                                        }
                                        nav.endArray()
                                    }
                                }

                                // --- streakHistory ---
                                "streakHistory" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val rawJson = nav.nextValueAsString()
                                            val streak = tryImport("streakHistory-parse", null) {
                                                json.decodeFromString<StreakHistoryBackup>(rawJson)
                                            } ?: continue
                                            if (backupVersion >= 6 && !profileParentAvailable(streak.profileId)) {
                                                entitiesWithErrors++
                                                Logger.w { "Backup restore missing required profile ${streak.profileId} for streakHistory ${streak.id}" }
                                                continue
                                            }
                                            val existing = queries.selectAllStreakHistory(streak.profileId).executeAsList().firstOrNull { row ->
                                                (streak.id <= 0L || row.id == streak.id) &&
                                                    row.startDate == streak.startDate && row.endDate == streak.endDate &&
                                                    row.length == streak.length.toLong()
                                            }
                                            if (existing != null) {
                                                streakHistorySkipped++
                                                continue
                                            }
                                            if (streak.id > 0L && queries.selectAllStreakHistory(streak.profileId).executeAsList().any { it.id == streak.id }) {
                                                entitiesWithErrors++
                                                Logger.w { "Backup restore conflict streakHistory id=${streak.id}" }
                                                continue
                                            }
                                            queries.insertStreakHistoryRestoreIfAbsent(
                                                id = streak.id.takeIf { it > 0L },
                                                startDate = streak.startDate,
                                                endDate = streak.endDate,
                                                length = streak.length.toLong(),
                                                profileId = streak.profileId,
                                            )
                                            val restored = queries.selectAllStreakHistory(streak.profileId).executeAsList().any { row ->
                                                (streak.id <= 0L || row.id == streak.id) && row.startDate == streak.startDate &&
                                                    row.endDate == streak.endDate && row.length == streak.length.toLong()
                                            }
                                            if (restored) streakHistoryImported++ else {
                                                entitiesWithErrors++
                                                Logger.w { "Backup restore could not insert streakHistory id=${streak.id}" }
                                            }
                                        }
                                        nav.endArray()
                                    }
                                }

                                // --- stockExerciseUserFields (v7+) ---
                                // User state on a stock catalogue row. The catalogue ships with the app,
                                // so a row missing on this install is skipped, not an error.
                                "stockExerciseUserFields" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val rawJson = nav.nextValueAsString()
                                            val fields = tryImport("stockExerciseUserFields-parse", null) {
                                                json.decodeFromString<StockExerciseUserFieldsBackup>(rawJson)
                                            } ?: continue
                                            val existing = queries.selectExerciseById(fields.exerciseId).executeAsOneOrNull()
                                            if (existing == null || existing.isCustom != 0L) {
                                                stockExerciseUserFieldsSkipped++
                                                continue
                                            }
                                            val alreadyPresent = (!fields.isFavorite || existing.isFavorite == 1L) &&
                                                (fields.mvtOverrideMs == null || existing.mvtOverrideMs != null) &&
                                                (fields.legacyOneRepMaxKg == null || existing.one_rep_max_kg != null)
                                            if (alreadyPresent) {
                                                stockExerciseUserFieldsSkipped++
                                                continue
                                            }
                                            tryImport("stockExerciseUserFields", fields.exerciseId) {
                                                queries.restoreStockExerciseUserFields(
                                                    isFavorite = if (fields.isFavorite) 1L else 0L,
                                                    mvtOverrideMs = fields.mvtOverrideMs?.toDouble(),
                                                    oneRepMaxKg = fields.legacyOneRepMaxKg?.toDouble(),
                                                    id = fields.exerciseId,
                                                )
                                            }?.let { stockExerciseUserFieldsImported++ }
                                        }
                                        nav.endArray()
                                    }
                                }

                                // --- gamificationStats (v1-v6 SINGLE OBJECT, not array) ---
                                "gamificationStats" -> {
                                    if (nav.peekIsNull()) {
                                        nav.skipNull()
                                    } else {
                                        val rawJson = nav.nextValueAsString()
                                        tryImport("gamificationStats-parse", null) {
                                            json.decodeFromString<GamificationStatsBackup>(rawJson)
                                        }?.let(::restoreGamificationStats)
                                    }
                                }

                                // --- gamificationStatsByProfile (v7+, one row per profile) ---
                                "gamificationStatsByProfile" -> {
                                    nav.beginArray()
                                    while (nav.hasNextInArray()) {
                                        val rawJson = nav.nextValueAsString()
                                        tryImport("gamificationStats-parse", null) {
                                            json.decodeFromString<GamificationStatsBackup>(rawJson)
                                        }?.let(::restoreGamificationStats)
                                    }
                                    nav.endArray()
                                }

                                // --- sessionNotes ---
                                "sessionNotes" -> {
                                    committedTransaction {
                                        nav.beginArray()
                                        while (nav.hasNextInArray()) {
                                            val rawJson = nav.nextValueAsString()
                                            val note = tryImport("sessionNotes-parse", null) {
                                                json.decodeFromString<SessionNotesBackup>(rawJson)
                                            } ?: continue
                                            val existing = queries.getSessionNotes(note.routineSessionId).executeAsOneOrNull()
                                            if (existing != null) {
                                                if (mapSessionNotesToBackup(existing) == note) {
                                                    sessionNotesSkipped++
                                                    continue
                                                }
                                                if (existing.notes == null && existing.updatedAt == null) {
                                                    queries.upsertSessionNotes(
                                                        routineSessionId = note.routineSessionId,
                                                        notes = note.notes,
                                                        updatedAt = note.updatedAt,
                                                    )
                                                    queries.markWorkoutPortalParentDirty(note.routineSessionId)
                                                    sessionNotesImported++
                                                } else {
                                                    entitiesWithErrors++
                                                    Logger.w {
                                                        "Backup restore conflict sessionNotes routineSessionId=${note.routineSessionId}; local row preserved"
                                                    }
                                                }
                                                continue
                                            }

                                            val inserted = tryImport("sessionNotes", note.routineSessionId) {
                                                queries.insertSessionNotesIgnore(
                                                    routineSessionId = note.routineSessionId,
                                                    notes = note.notes,
                                                    updatedAt = note.updatedAt,
                                                )
                                                queries.markWorkoutPortalParentDirty(note.routineSessionId)
                                            }
                                            if (inserted != null) {
                                                sessionNotesImported++
                                            }
                                        }
                                        nav.endArray()
                                    }
                                }

                                // --- unknown fields: skip for forward compatibility ---
                                else -> {
                                    Logger.d { "Streaming import: skipping unknown data field '$fieldName'" }
                                    nav.skipValue()
                                }
                            }
                        }
                        nav.endObject() // end "data"
                    }

                    else -> nav.skipValue()
                }
            }
            nav.endObject() // end root

            applyRestoredSessionSyncMarkers()

            database.transaction {
                normalizeImportedActiveIdentity(
                    preImportActiveProfileId,
                    representedProfileIds,
                    legacyFallbackProfileId(),
                )
            }
            databaseWorkCommitted = true
            activeIdentityNormalized = true

            reconciliationAttempted = true
            restoreDeferredAndReconcile(
                deferred = DeferredProfileRestore(
                    backupVersion = backupVersion,
                    profilePreferences = emptyList(),
                    legacyRackFieldPresent = legacyRackFieldPresent,
                    legacyRackElement = legacyRackElement,
                    representedProfileIds = representedProfileIds,
                ),
                staging = staging,
                now = KmpUtils.currentTimeMillis(),
                onInvalid = ::onInvalidProfileState,
            )

            if (entitiesWithErrors > 0) {
                Logger.w { "Streaming import completed with $entitiesWithErrors skipped entity row(s) — see preceding warnings for per-entity diagnostics" }
            }

            onProgress(BackupProgress(BackupPhase.FINALIZING, 0, 0))

            val importResult = ImportResult(
                sessionsImported = sessionsImported,
                sessionsSkipped = sessionsSkipped,
                metricsImported = metricsImported,
                metricsSkipped = metricsSkipped,
                routinesImported = routinesImported,
                routinesSkipped = routinesSkipped,
                routineExercisesImported = routineExercisesImported,
                routineExercisesSkipped = routineExercisesSkipped,
                supersetsImported = supersetsImported,
                supersetsSkipped = supersetsSkipped,
                personalRecordsImported = personalRecordsImported,
                personalRecordsSkipped = personalRecordsSkipped,
                trainingCyclesImported = trainingCyclesImported,
                trainingCyclesSkipped = trainingCyclesSkipped,
                cycleDaysImported = cycleDaysImported,
                cycleDaysSkipped = cycleDaysSkipped,
                cycleProgressImported = cycleProgressImported,
                cycleProgressSkipped = cycleProgressSkipped,
                cycleProgressionsImported = cycleProgressionsImported,
                cycleProgressionsSkipped = cycleProgressionsSkipped,
                plannedSetsImported = plannedSetsImported,
                plannedSetsSkipped = plannedSetsSkipped,
                completedSetsImported = completedSetsImported,
                completedSetsSkipped = completedSetsSkipped,
                progressionEventsImported = progressionEventsImported,
                progressionEventsSkipped = progressionEventsSkipped,
                earnedBadgesImported = earnedBadgesImported,
                earnedBadgesSkipped = earnedBadgesSkipped,
                streakHistoryImported = streakHistoryImported,
                streakHistorySkipped = streakHistorySkipped,
                gamificationStatsImported = gamificationStatsImported,
                gamificationStatsSkipped = gamificationStatsSkipped,
                userProfilesImported = userProfilesImported,
                userProfilesSkipped = userProfilesSkipped,
                sessionNotesImported = sessionNotesImported,
                sessionNotesSkipped = sessionNotesSkipped,
                routineGroupsImported = routineGroupsImported,
                routineGroupsSkipped = routineGroupsSkipped,
                customExercisesImported = customExercisesImported,
                customExercisesSkipped = customExercisesSkipped,
                stockExerciseUserFieldsImported = stockExerciseUserFieldsImported,
                stockExerciseUserFieldsSkipped = stockExerciseUserFieldsSkipped,
                profileExerciseBaselinesImported = profileExerciseBaselinesImported,
                profileExerciseBaselinesSkipped = profileExerciseBaselinesSkipped,
                workoutDeletionsImported = workoutDeletionsImported,
                workoutDeletionsSkipped = workoutDeletionsSkipped,
                pendingProfileRecoveriesImported = pendingProfileRecoveriesImported,
                pendingProfileRecoveriesSkipped = pendingProfileRecoveriesSkipped,
                ownershipTransfersImported = ownershipTransfersImported,
                ownershipTransfersSkipped = ownershipTransfersSkipped,
                appliedOwnershipEventsImported = appliedOwnershipEventsImported,
                appliedOwnershipEventsSkipped = appliedOwnershipEventsSkipped,
                localOwnershipClaimsImported = localOwnershipClaimsImported,
                localOwnershipClaimsSkipped = localOwnershipClaimsSkipped,
                cycleSyncStatesImported = cycleSyncStatesImported,
                cycleSyncStatesSkipped = cycleSyncStatesSkipped,
                cycleConflictDraftsImported = cycleConflictDraftsImported,
                cycleConflictDraftsSkipped = cycleConflictDraftsSkipped,
                repairedReferences = repairedReferences,
                entitiesWithErrors = entitiesWithErrors,
            )
            if (importResult.totalImported > 0) resetStateABackupCannotCarry()
            return Result.success(importResult)
        } catch (e: Throwable) {
            if (databaseWorkCommitted) {
                // An aborted restore still leaves committed rows: finish their sync markers
                // (children may have re-dirtied them) and reset the state they invalidate.
                withContext(NonCancellable) {
                    runCatching { applyRestoredSessionSyncMarkers() }.exceptionOrNull()?.let(e::addSuppressed)
                    runCatching { resetStateABackupCannotCarry() }.exceptionOrNull()?.let(e::addSuppressed)
                }
            }
            if (databaseWorkCommitted && !reconciliationAttempted) {
                withContext(NonCancellable) {
                    val normalizationFailure = if (activeIdentityNormalized) {
                        null
                    } else {
                        runCatching {
                            database.transaction {
                                normalizeImportedActiveIdentity(
                                    preImportActiveProfileId,
                                    representedProfileIds,
                                    legacyFallbackProfileId(),
                                )
                            }
                            activeIdentityNormalized = true
                        }.exceptionOrNull()
                    }
                    normalizationFailure?.let(e::addSuppressed)
                    val reconcileFailure = runCatching {
                        userProfileRepository.reconcileActiveProfileContext()
                    }.exceptionOrNull()
                    reconcileFailure?.let(e::addSuppressed)
                }
            }
            Logger.e { "Streaming backup import aborted category=${e::class.simpleName}" }
            if (e is CancellationException) throw e
            return Result.failure(e)
        }
    }

    override suspend fun getShareableContent(): String = exportToJson()

    // -- Streaming JSON writer --

    private suspend fun streamExportToWriter(
        writer: BackupJsonWriter,
        onProgress: (BackupProgress) -> Unit,
    ) {
        val filter = deletedProfileFilter()
        activeExportFilter = filter.takeIf { it.isActive }
        try {
            streamExportToWriterFiltered(writer, onProgress)
        } finally {
            activeExportFilter = null
        }
    }

    private suspend fun streamExportToWriterFiltered(
        writer: BackupJsonWriter,
        onProgress: (BackupProgress) -> Unit,
    ) {
        // Phase 1: Count
        onProgress(BackupProgress(BackupPhase.COUNTING, 0, 0))
        val sessionCount = queries.countBackupWorkoutSessions().executeAsOne()
        val metricCount = if (includeRawTelemetryInBackups) queries.countBackupMetricSamples().executeAsOne() else 0L
        val allRoutines = queries.selectAllRoutinesSync().executeAsList()
        val allRoutineExercises = queries.selectAllRoutineExercisesSync().executeAsList()
        val routines = allRoutines
        val activeRoutineIds = allRoutines.filter { it.deletedAt == null }.mapTo(hashSetOf()) { it.id }
        val routineExercises = allRoutineExercises
        val userProfiles = queries.selectAllUserProfilesSync().executeAsList()
        val profilePreferences = userProfiles.map { profile ->
            profilePreferencesRepository.get(profile.id).toBackup()
        }
        val routineNameResolutionContext = buildRoutineNameResolutionContext(allRoutines, allRoutineExercises)

        // JSON header
        val exportedAt = kotlin.time.Instant.fromEpochMilliseconds(KmpUtils.currentTimeMillis()).toString()
        val privacy = json.encodeToString(BackupPrivacyMetadata.serializer(), backupPrivacy())
        writer.write("""{"version":$CURRENT_BACKUP_VERSION,"exportedAt":"$exportedAt","appVersion":"${Constants.APP_VERSION}","privacy":$privacy,"data":{""")

        val customExercises = queries.selectCustomExercises().executeAsList()
        writeJsonArray(
            writer,
            "customExercises",
            customExercises.map { json.encodeToString(CustomExerciseBackup.serializer(), mapCustomExerciseToBackup(it)) },
        )
        writer.write(",")
        writeJsonArray(
            writer,
            "stockExerciseUserFields",
            stockExerciseUserFields().map { json.encodeToString(StockExerciseUserFieldsBackup.serializer(), it) },
        )
        writer.write(",")

        // Phase 2: Sessions
        onProgress(BackupProgress(BackupPhase.SESSIONS, 0, sessionCount))
        writer.write("\"workoutSessions\":[")
        // A deleted profile's sessions are hard-deleted by PR 20; filtered anyway so a
        // half-finished delete can never leak one into a backup.
        val sessions = queries.selectBackupSessionsSync().executeAsList().filter { session ->
            activeExportFilter?.keep("workoutSessions", mapSessionToBackup(session).let {
                json.encodeToJsonElement(WorkoutSessionBackup.serializer(), it).jsonObject
            }) ?: true
        }
        sessions.forEachIndexed { index, session ->
            if (index > 0) writer.write(",")
            writer.write(json.encodeToString(WorkoutSessionBackup.serializer(), mapSessionToBackup(session, routineNameResolutionContext)))
            val current = (index + 1).toLong()
            if (current % 100 == 0L || current == sessions.size.toLong()) {
                writer.flush()
                onProgress(BackupProgress(BackupPhase.SESSIONS, current, sessionCount))
            }
        }
        writer.write("],")
        writer.flush()

        // Phase 3: Metrics (critical path -- per-session to avoid OOM)
        onProgress(BackupProgress(BackupPhase.METRICS, 0, metricCount))
        writer.write("\"metricSamples\":[")
        var metricIndex = 0L
        var firstMetric = true
        // Raw telemetry is opt-in (F-033); the empty section keeps the file shape stable.
        for (session in if (includeRawTelemetryInBackups) sessions else emptyList()) {
            val sessionMetrics = queries.selectMetricsBySession(session.id).executeAsList()
            for (metric in sessionMetrics) {
                if (!firstMetric) writer.write(",")
                firstMetric = false
                writer.write(json.encodeToString(MetricSampleBackup.serializer(), mapMetricToBackup(metric)))
                metricIndex++
            }
            if (sessionMetrics.isNotEmpty()) {
                writer.flush()
                onProgress(BackupProgress(BackupPhase.METRICS, metricIndex, metricCount))
            }
        }
        writer.write("],")
        writer.flush()

        // Phase 4: Routines
        onProgress(BackupProgress(BackupPhase.ROUTINES, 0, 0))
        writeJsonArray(writer, "routines", routines.map { json.encodeToString(RoutineBackup.serializer(), mapRoutineToBackup(it)) })
        writer.write(",")
        writeJsonArray(writer, "routineExercises", routineExercises.map { json.encodeToString(RoutineExerciseBackup.serializer(), mapRoutineExerciseToBackup(it)) })
        writer.write(",")

        // Phase 5: Remaining tables (small, bulk-load is safe)
        onProgress(BackupProgress(BackupPhase.OTHER, 0, 0))

        val supersets = queries.selectAllSupersetsSync().executeAsList()
        writeJsonArray(writer, "supersets", supersets.map { json.encodeToString(SupersetBackup.serializer(), mapSupersetToBackup(it)) })
        writer.write(",")

        val personalRecords = queries.selectActiveRecordsForBackup().executeAsList()
        writeJsonArray(writer, "personalRecords", personalRecords.map { json.encodeToString(PersonalRecordBackup.serializer(), mapPersonalRecordToBackup(it)) })
        writer.write(",")

        val trainingCycles = queries.selectAllTrainingCyclesSync().executeAsList()
        writeJsonArray(writer, "trainingCycles", trainingCycles.map { json.encodeToString(TrainingCycleBackup.serializer(), mapTrainingCycleToBackup(it)) })
        writer.write(",")

        val cycleDays = trainingCycles.flatMap { cycle ->
            queries.selectCycleDaysByCycle(cycle.id).executeAsList()
        }.map { day ->
            if (day.routine_id != null && day.routine_id !in activeRoutineIds) {
                mapCycleDayToBackup(day).copy(routineId = null)
            } else {
                mapCycleDayToBackup(day)
            }
        }
        writeJsonArray(writer, "cycleDays", cycleDays.map { json.encodeToString(CycleDayBackup.serializer(), it) })
        writer.write(",")

        val cycleProgress = queries.selectAllCycleProgressSync().executeAsList()
        writeJsonArray(writer, "cycleProgress", cycleProgress.map { json.encodeToString(CycleProgressBackup.serializer(), mapCycleProgressToBackup(it)) })
        writer.write(",")

        val cycleProgressions = queries.selectAllCycleProgressionsSync().executeAsList()
        writeJsonArray(writer, "cycleProgressions", cycleProgressions.map { json.encodeToString(CycleProgressionBackup.serializer(), mapCycleProgressionToBackup(it)) })
        writer.write(",")

        val plannedSets = queries.selectAllPlannedSetsSync().executeAsList()
        val plannedSetIds = plannedSets.mapTo(hashSetOf()) { it.id }
        writeJsonArray(writer, "plannedSets", plannedSets.map { json.encodeToString(PlannedSetBackup.serializer(), mapPlannedSetToBackup(it)) })
        writer.write(",")

        val completedSets = queries.selectAllCompletedSetsSync().executeAsList()
        writeJsonArray(writer, "completedSets", completedSets.map {
            val backup = mapCompletedSetToBackup(it)
            json.encodeToString(
                CompletedSetBackup.serializer(),
                if (backup.plannedSetId !in plannedSetIds) backup.copy(plannedSetId = null) else backup,
            )
        })
        writer.write(",")

        val progressionEvents = queries.selectAllProgressionEventsSync().executeAsList()
        writeJsonArray(writer, "progressionEvents", progressionEvents.map { json.encodeToString(ProgressionEventBackup.serializer(), mapProgressionEventToBackup(it)) })
        writer.write(",")

        val earnedBadges = queries.selectAllEarnedBadgesSync().executeAsList()
        writeJsonArray(writer, "earnedBadges", earnedBadges.map { json.encodeToString(EarnedBadgeBackup.serializer(), mapEarnedBadgeToBackup(it)) })
        writer.write(",")

        val streakHistory = queries.selectAllStreakHistorySync().executeAsList()
        writeJsonArray(writer, "streakHistory", streakHistory.map { json.encodeToString(StreakHistoryBackup.serializer(), mapStreakHistoryToBackup(it)) })
        writer.write(",")

        // One stats row per profile (v7). v1-v6 files carried a single `gamificationStats` object.
        writeJsonArray(
            writer,
            "gamificationStatsByProfile",
            gamificationStatsByProfile(userProfiles.map { it.id }).map {
                json.encodeToString(GamificationStatsBackup.serializer(), it)
            },
        )
        writer.write(",")

        writeJsonArray(writer, "userProfiles", userProfiles.map { json.encodeToString(UserProfileBackup.serializer(), mapUserProfileToBackup(it)) })
        writer.write(",")
        writeJsonArray(
            writer,
            "profilePreferences",
            profilePreferences.map { json.encodeToString(ProfilePreferencesBackup.serializer(), it) },
        )
        writer.write(",")

        // Session notes (migration 26).
        val sessionNotes = queries.selectAllSessionNotesSync().executeAsList()
        writeJsonArray(writer, "sessionNotes", sessionNotes.map { json.encodeToString(SessionNotesBackup.serializer(), mapSessionNotesToBackup(it)) })
        writer.write(",")

        // Routine groups (migration 27). Preserve empty groups as legitimate user state.
        val routineGroups = queries.selectAllRoutineGroupsSync().executeAsList()
        writeJsonArray(writer, "routineGroups", routineGroups.map { json.encodeToString(RoutineGroupBackup.serializer(), mapRoutineGroupToBackup(it)) })
        writer.write(",")

        val baselines = queries.selectAllProfileExerciseBaselines().executeAsList().map {
            ProfileExerciseBaselineBackup(
                profileId = it.profile_id,
                exerciseId = it.exercise_id,
                oneRepMaxPerCableKg = it.one_rep_max_per_cable_kg?.toFloat(),
                updatedAt = it.updated_at,
                revision = it.revision,
            )
        }
        writeJsonArray(writer, "profileExerciseBaselines", baselines.map {
            json.encodeToString(ProfileExerciseBaselineBackup.serializer(), it)
        })
        writer.write(",")

        writeJsonArray(writer, "workoutDeletions", queries.selectAllWorkoutDeletions().executeAsList().map {
            json.encodeToString(WorkoutDeletionBackup.serializer(), mapWorkoutDeletionToBackup(it))
        })
        writer.write(",")
        writeJsonArray(writer, "pendingProfileRecoveries", queries.selectAllPendingProfileRecoveries().executeAsList().map {
            json.encodeToString(PendingProfileRecoveryBackup.serializer(), mapPendingRecoveryToBackup(it))
        })
        writer.write(",")
        writeJsonArray(writer, "ownershipTransfers", queries.selectAllOwnershipTransfers().executeAsList().map {
            json.encodeToString(OwnershipTransferBackup.serializer(), mapOwnershipTransferToBackup(it))
        })
        writer.write(",")
        writeJsonArray(writer, "appliedOwnershipEvents", queries.selectAllAppliedOwnershipEvents().executeAsList().map {
            json.encodeToString(AppliedOwnershipEventBackup.serializer(), mapAppliedOwnershipEventToBackup(it))
        })
        writer.write(",")
        writeJsonArray(writer, "localOwnershipClaims", queries.selectAllLocalOwnershipClaims().executeAsList().map {
            json.encodeToString(LocalOwnershipClaimBackup.serializer(), mapLocalOwnershipClaimToBackup(it))
        })
        writer.write(",")
        writeJsonArray(writer, "cycleSyncStates", queries.selectAllCycleSyncStates().executeAsList().map {
            json.encodeToString(CycleSyncStateBackup.serializer(), mapCycleSyncStateToBackup(it))
        })
        writer.write(",")
        writeJsonArray(writer, "cycleConflictDrafts", queries.selectAllCycleConflictDrafts().executeAsList().map {
            json.encodeToString(CycleConflictDraftBackup.serializer(), mapCycleConflictDraftToBackup(it))
        })

        // Close JSON
        writer.write("}}")
        writer.flush()
    }

    private fun writeJsonArray(writer: BackupJsonWriter, fieldName: String, jsonStrings: List<String>) {
        val filter = activeExportFilter
        val kept = if (filter == null) jsonStrings else jsonStrings.filter { filter.keep(fieldName, it) }
        writer.write("\"$fieldName\":[")
        kept.forEachIndexed { index, s ->
            if (index > 0) writer.write(",")
            writer.write(s)
        }
        writer.write("]")
    }

    // -- Mapper functions (DB types → Backup types) --

    /**
     * Legacy sessions may have null routine metadata due to older client behavior.
     * Normalize to stable non-null placeholders during export so backups remain
     * self-describing and don't require manual data repair by end users.
     */
    private fun normalizeRoutineMetadataForBackup(
        session: WorkoutSession,
        routineNameResolutionContext: RoutineNameResolutionContext? = null,
    ): Pair<String?, String?> {
        val existingSessionId = sanitizeRoutineSessionId(session.routineSessionId)
        val existingRoutineName = sanitizeRoutineName(session.routineName)

        // Direct lookup via routineId (most reliable - added in migration 12)
        val directLookupName = session.routineId?.let { routineId ->
            routineNameResolutionContext?.routineNameById?.get(routineId)
        }

        // Heuristic inference for legacy sessions without routineId
        val inferredRoutineNameById = session.exerciseId?.let { exerciseId ->
            routineNameResolutionContext?.uniqueRoutineNameByExerciseId?.get(exerciseId)
        }
        val inferredRoutineNameByExerciseName = normalizeExerciseToken(session.exerciseName)?.let { normalizedExerciseName ->
            routineNameResolutionContext?.uniqueRoutineNameByExerciseName?.get(normalizedExerciseName)
        }
        val inferredRoutineName = inferredRoutineNameById ?: inferredRoutineNameByExerciseName
        val existingLooksLikeExercisePlaceholder =
            normalizeExerciseToken(existingRoutineName) == normalizeExerciseToken(session.exerciseName)

        // Don't fabricate routineSessionId -- if none exists, leave null.
        // Fabricating unique IDs per session breaks history grouping.
        val normalizedRoutineName = when {
            session.isJustLift != 0L -> "Just Lift"
            directLookupName != null -> directLookupName
            inferredRoutineName != null && (existingRoutineName == null || existingLooksLikeExercisePlaceholder) -> inferredRoutineName
            existingRoutineName != null && !existingLooksLikeExercisePlaceholder -> existingRoutineName
            else -> null // Can't determine routine - leave null (standalone exercise)
        }

        return existingSessionId to normalizedRoutineName
    }

    private fun buildRoutineNameResolutionContext(
        routines: List<Routine>,
        routineExercises: List<RoutineExercise>,
    ): RoutineNameResolutionContext {
        val routineNameById = routines.associate { routine ->
            routine.id to sanitizeEntityName(routine.name, "Unnamed Routine")
        }
        val nonTemplateRoutineIds = routines
            .asSequence()
            .filterNot { it.id.startsWith("cycle_routine_") }
            .map { it.id }
            .toSet()

        fun collectUniqueRoutineNames(
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
            routineIdsByExerciseName.forEach { (normalizedExerciseName, routineIds) ->
                if (routineIds.size != 1) return@forEach
                val routineId = routineIds.first()
                val routineName = routineNameById[routineId] ?: return@forEach
                uniqueRoutineNames[normalizedExerciseName] = routineName
            }
            return uniqueRoutineNames
        }

        // Prefer user-authored/non-template routines first to avoid cycle template noise.
        val uniqueFromNonTemplate = collectUniqueRoutineNames(
            allowedRoutineIds = nonTemplateRoutineIds.takeIf { it.isNotEmpty() },
        )
        val uniqueFromAll = collectUniqueRoutineNames()
        val uniqueRoutineNameByExerciseId = uniqueFromAll.toMutableMap().apply {
            putAll(uniqueFromNonTemplate)
        }
        val uniqueByNameFromNonTemplate = collectUniqueRoutineNamesByExerciseName(
            allowedRoutineIds = nonTemplateRoutineIds.takeIf { it.isNotEmpty() },
        )
        val uniqueByNameFromAll = collectUniqueRoutineNamesByExerciseName()
        val uniqueRoutineNameByExerciseName = uniqueByNameFromAll.toMutableMap().apply {
            putAll(uniqueByNameFromNonTemplate)
        }

        return RoutineNameResolutionContext(
            routineNameById = routineNameById,
            uniqueRoutineNameByExerciseId = uniqueRoutineNameByExerciseId,
            uniqueRoutineNameByExerciseName = uniqueRoutineNameByExerciseName,
        )
    }

    private fun buildRoutineNameResolutionContextFromBackup(
        routines: List<RoutineBackup>,
        routineExercises: List<RoutineExerciseBackup>,
    ): RoutineNameResolutionContext {
        val routineNameById = routines.associate { routine ->
            routine.id to sanitizeEntityName(routine.name, "Unnamed Routine")
        }
        val nonTemplateRoutineIds = routines
            .asSequence()
            .filterNot { it.id.startsWith("cycle_routine_") }
            .map { it.id }
            .toSet()

        fun collectUniqueRoutineNames(
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
            routineIdsByExerciseName.forEach { (normalizedExerciseName, routineIds) ->
                if (routineIds.size != 1) return@forEach
                val routineId = routineIds.first()
                val routineName = routineNameById[routineId] ?: return@forEach
                uniqueRoutineNames[normalizedExerciseName] = routineName
            }
            return uniqueRoutineNames
        }

        // Prefer user-authored/non-template routines first to avoid cycle template noise.
        val uniqueFromNonTemplate = collectUniqueRoutineNames(
            allowedRoutineIds = nonTemplateRoutineIds.takeIf { it.isNotEmpty() },
        )
        val uniqueFromAll = collectUniqueRoutineNames()
        val uniqueRoutineNameByExerciseId = uniqueFromAll.toMutableMap().apply {
            putAll(uniqueFromNonTemplate)
        }
        val uniqueByNameFromNonTemplate = collectUniqueRoutineNamesByExerciseName(
            allowedRoutineIds = nonTemplateRoutineIds.takeIf { it.isNotEmpty() },
        )
        val uniqueByNameFromAll = collectUniqueRoutineNamesByExerciseName()
        val uniqueRoutineNameByExerciseName = uniqueByNameFromAll.toMutableMap().apply {
            putAll(uniqueByNameFromNonTemplate)
        }

        return RoutineNameResolutionContext(
            routineNameById = routineNameById,
            uniqueRoutineNameByExerciseId = uniqueRoutineNameByExerciseId,
            uniqueRoutineNameByExerciseName = uniqueRoutineNameByExerciseName,
        )
    }

    private fun resolveImportedRoutineName(
        session: WorkoutSessionBackup,
        routineNameResolutionContext: RoutineNameResolutionContext,
    ): String? {
        val existingRoutineName = sanitizeRoutineName(session.routineName)
        val directLookupName = session.routineId?.let { routineId ->
            routineNameResolutionContext.routineNameById[routineId]
        }
        val inferredRoutineNameById = session.exerciseId?.let { exerciseId ->
            routineNameResolutionContext.uniqueRoutineNameByExerciseId[exerciseId]
        }
        val inferredRoutineNameByExerciseName = normalizeExerciseToken(session.exerciseName)?.let { normalizedExerciseName ->
            routineNameResolutionContext.uniqueRoutineNameByExerciseName[normalizedExerciseName]
        }
        val inferredRoutineName = inferredRoutineNameById ?: inferredRoutineNameByExerciseName
        val existingLooksLikeExercisePlaceholder =
            normalizeExerciseToken(existingRoutineName) == normalizeExerciseToken(session.exerciseName)

        return when {
            session.isJustLift -> "Just Lift"
            directLookupName != null -> directLookupName
            inferredRoutineName != null && (existingRoutineName == null || existingLooksLikeExercisePlaceholder) -> inferredRoutineName
            existingRoutineName != null && !existingLooksLikeExercisePlaceholder -> existingRoutineName
            else -> null
        }
    }

    /**
     * Treat low-quality legacy values as missing.
     * Examples filtered out: blank, "null", ":", "--", punctuation-only tokens.
     */
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

    /**
     * Sanitize a routine name, also filtering out known garbage placeholder values
     * that were injected by external imports and don't represent real routine names.
     */
    private fun sanitizeRoutineName(raw: String?): String? {
        val sanitized = sanitizeLegacyLabel(raw) ?: return null
        if (sanitized.lowercase().trim() in GARBAGE_ROUTINE_NAMES) return null
        return sanitized
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

    private fun normalizeExerciseToken(raw: String?): String? {
        val sanitized = sanitizeLegacyLabel(raw) ?: return null
        val collapsedWhitespace = sanitized
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
        return collapsedWhitespace.ifEmpty { null }
    }

    private fun mapSessionToBackup(
        session: WorkoutSession,
        routineNameResolutionContext: RoutineNameResolutionContext? = null,
    ): WorkoutSessionBackup {
        val (routineSessionId, routineName) = normalizeRoutineMetadataForBackup(session, routineNameResolutionContext)
        return WorkoutSessionBackup(
            id = session.id,
            timestamp = session.timestamp,
            mode = session.mode,
            targetReps = session.targetReps.toInt(),
            weightPerCableKg = session.weightPerCableKg.toFloat(),
            progressionKg = session.progressionKg.toFloat(),
            duration = session.duration,
            totalReps = session.totalReps.toInt(),
            warmupReps = session.warmupReps.toInt(),
            workingReps = session.workingReps.toInt(),
            isJustLift = session.isJustLift != 0L,
            stopAtTop = session.stopAtTop != 0L,
            eccentricLoad = session.eccentricLoad.toInt(),
            echoLevel = session.echoLevel.toInt(),
            exerciseId = session.exerciseId,
            exerciseName = session.exerciseName,
            routineSessionId = routineSessionId,
            routineName = routineName,
            routineId = session.routineId,
            safetyFlags = session.safetyFlags.toInt(),
            deloadWarningCount = session.deloadWarningCount.toInt(),
            romViolationCount = session.romViolationCount.toInt(),
            spotterActivations = session.spotterActivations.toInt(),
            peakForceConcentricA = session.peakForceConcentricA?.toFloat(),
            peakForceConcentricB = session.peakForceConcentricB?.toFloat(),
            peakForceEccentricA = session.peakForceEccentricA?.toFloat(),
            peakForceEccentricB = session.peakForceEccentricB?.toFloat(),
            avgForceConcentricA = session.avgForceConcentricA?.toFloat(),
            avgForceConcentricB = session.avgForceConcentricB?.toFloat(),
            avgForceEccentricA = session.avgForceEccentricA?.toFloat(),
            avgForceEccentricB = session.avgForceEccentricB?.toFloat(),
            heaviestLiftKg = session.heaviestLiftKg?.toFloat(),
            totalVolumeKg = session.totalVolumeKg?.toFloat(),
            cableCount = session.cableCount?.toInt(),
            displayMultiplier = session.display_multiplier?.toInt(),
            externalAddedLoadKg = session.externalAddedLoadKg.toFloat(),
            counterweightKg = session.counterweightKg.toFloat(),
            rackItemsJson = session.rackItemsJson,
            estimatedCalories = session.estimatedCalories?.toFloat(),
            warmupAvgWeightKg = session.warmupAvgWeightKg?.toFloat(),
            workingAvgWeightKg = session.workingAvgWeightKg?.toFloat(),
            burnoutAvgWeightKg = session.burnoutAvgWeightKg?.toFloat(),
            peakWeightKg = session.peakWeightKg?.toFloat(),
            rpe = session.rpe?.toInt(),
            avgMcvMmS = session.avgMcvMmS?.toFloat(),
            avgAsymmetryPercent = session.avgAsymmetryPercent?.toFloat(),
            totalVelocityLossPercent = session.totalVelocityLossPercent?.toFloat(),
            dominantSide = session.dominantSide,
            strengthProfile = session.strengthProfile,
            formScore = session.formScore,
            profileId = session.profile_id,
            updatedAt = session.updatedAt,
            portalOrigin = session.portalOrigin == 1L,
            // Nothing pending: every local change reached the portal. A pulled row is clean at
            // 0/0; a local row needs at least one acknowledged push.
            syncAcknowledged = session.synced_sync_generation >= session.local_sync_generation &&
                (session.portalOrigin == 1L || session.synced_sync_generation > 0L),
        )
    }

    private fun mapMetricToBackup(metric: MetricSample): MetricSampleBackup = MetricSampleBackup(
        id = metric.id,
        sessionId = metric.sessionId,
        timestamp = metric.timestamp,
        position = metric.position?.toFloat(),
        positionB = metric.positionB?.toFloat(),
        velocity = metric.velocity?.toFloat(),
        velocityB = metric.velocityB?.toFloat(),
        load = metric.load?.toFloat(),
        loadB = metric.loadB?.toFloat(),
        power = metric.power?.toFloat(),
        status = metric.status.toInt(),
    )

    private fun mapRoutineToBackup(routine: Routine): RoutineBackup = RoutineBackup(
        id = routine.id,
        name = sanitizeEntityName(routine.name, "Unnamed Routine"),
        description = routine.description,
        createdAt = routine.createdAt,
        lastUsed = routine.lastUsed,
        useCount = routine.useCount.toInt(),
        profileId = routine.profile_id,
        groupId = routine.groupId,
        deletedAt = routine.deletedAt,
    )

    private fun mapRoutineExerciseToBackup(exercise: RoutineExercise): RoutineExerciseBackup = RoutineExerciseBackup(
        id = exercise.id,
        routineId = exercise.routineId,
        exerciseName = exercise.exerciseName,
        exerciseMuscleGroup = exercise.exerciseMuscleGroup,
        exerciseEquipment = exercise.exerciseEquipment,
        exerciseDefaultCableConfig = exercise.exerciseDefaultCableConfig,
        exerciseId = exercise.exerciseId,
        cableConfig = exercise.cableConfig,
        orderIndex = exercise.orderIndex.toInt(),
        setReps = exercise.setReps,
        weightPerCableKg = exercise.weightPerCableKg.toFloat(),
        setWeights = exercise.setWeights,
        mode = exercise.mode,
        eccentricLoad = exercise.eccentricLoad.toInt(),
        echoLevel = exercise.echoLevel.toInt(),
        progressionKg = exercise.progressionKg.toFloat(),
        restSeconds = exercise.restSeconds.toInt(),
        duration = exercise.duration?.toInt(),
        setRestSeconds = exercise.setRestSeconds,
        setEchoLevels = exercise.setEchoLevels,
        perSetRestTime = exercise.perSetRestTime != 0L,
        isAMRAP = exercise.isAMRAP != 0L,
        supersetId = exercise.supersetId,
        orderInSuperset = exercise.orderInSuperset.toInt(),
        usePercentOfPR = exercise.usePercentOfPR != 0L,
        weightPercentOfPR = exercise.weightPercentOfPR.toInt(),
        prTypeForScaling = exercise.prTypeForScaling,
        setWeightsPercentOfPR = exercise.setWeightsPercentOfPR,
        stallDetectionEnabled = exercise.stallDetectionEnabled != 0L,
        stopAtTop = exercise.stopAtTop != 0L,
        repCountTiming = exercise.repCountTiming,
        warmupSets = exercise.warmupSets,
        defaultRackItemIds = decodeRackItemIds(exercise.defaultRackItemIds),
        rackBehaviorOverrides = exercise.rackBehaviorOverrides,
        // scalingBasis is stored as the enum name (TEXT) on the DB row; persist it verbatim.
        scalingBasis = exercise.scalingBasis,
        // Explicit bodyweight flag (#635); null = derive from equipment.
        isBodyweight = exercise.isBodyweight?.let { it != 0L },
        dropSetEnabled = exercise.dropSetEnabled != 0L,
        dropSetMinWeightKg = exercise.dropSetMinWeightKg?.toFloat(),
    )

    /**
     * Resolve the explicit isBodyweight flag for a restored routine exercise (#635).
     * Pre-migration-39 backups carried a 'Bodyweight' sentinel in exerciseEquipment
     * instead of an explicit flag; convert it on restore just like migration 39 does.
     */
    private fun resolveBackupIsBodyweight(exercise: RoutineExerciseBackup): Long? = when {
        exercise.isBodyweight != null -> if (exercise.isBodyweight) 1L else 0L
        exercise.exerciseEquipment == LEGACY_BODYWEIGHT_SENTINEL -> 1L
        else -> null
    }

    /** Strip the legacy 'Bodyweight' equipment sentinel from pre-migration-39 backups (#635). */
    private fun resolveBackupEquipment(exercise: RoutineExerciseBackup): String =
        if (exercise.exerciseEquipment == LEGACY_BODYWEIGHT_SENTINEL) "" else exercise.exerciseEquipment

    private fun decodeRackItemIds(encoded: String): List<String> = runCatching {
        if (encoded.isBlank()) {
            emptyList()
        } else {
            json.decodeFromString<List<String>>(encoded)
                .filter { it.isNotBlank() }
                .distinct()
        }
    }.getOrElse { emptyList() }

    private fun sanitizeRackItemIds(itemIds: List<String>): String = json.encodeToString(
        itemIds.filter { it.isNotBlank() }.distinct(),
    )

    private fun normalizeImportedActiveIdentity(
        preImportActiveProfileId: String?,
        representedProfileIds: Set<String>,
        resolvedFallbackProfileId: String? = null,
    ): String {
        val existingProfileIds = queries.selectAllUserProfileIds().executeAsList().toSet()
        val activeProfileId = resolvedFallbackProfileId
            ?.takeIf { it in existingProfileIds }
            ?: preImportActiveProfileId
            ?.takeIf { it in existingProfileIds }
            ?: "default".takeIf { it in existingProfileIds }
            ?: representedProfileIds.firstOrNull { it in existingProfileIds }
            ?: error("No usable profile identity after backup import")
        queries.setActiveProfile(activeProfileId)
        return activeProfileId
    }

    private fun resolveImportedProfileId(
        requestedProfileId: String?,
        activeProfileId: String,
        availableProfileIds: Set<String>,
    ): String = requestedProfileId?.takeIf { it in availableProfileIds } ?: activeProfileId

    private inline fun <reified T> decodeBackupSection(
        profileId: String,
        sectionName: String,
        element: JsonElement,
        validate: (T) -> List<String>,
        onInvalid: (String, String, Throwable?) -> Unit,
    ): T? = runCatching {
        val value = json.decodeFromJsonElement<T>(element.jsonObject)
        val validationErrors = validate(value)
        require(validationErrors.isEmpty()) { validationErrors.joinToString(",") }
        value
    }.fold(
        onSuccess = { it },
        onFailure = {
            onInvalid(profileId, sectionName, it)
            null
        },
    )

    private fun decodeLegacyV4Rack(
        element: JsonElement?,
        onInvalid: (String, String, Throwable?) -> Unit,
    ): List<RackItem>? {
        if (element == null) {
            onInvalid("backup", "equipmentRackItems", null)
            return null
        }
        return runCatching {
            json.decodeFromJsonElement<List<RackItem>>(element)
        }.fold(
            onSuccess = { it },
            onFailure = {
                onInvalid("backup", "equipmentRackItems", it)
                null
            },
        )
    }

    private suspend fun restoreDeferredProfileState(
        deferred: DeferredProfileRestore,
        staging: BackupImportStagingArea,
        now: Long,
        onInvalid: (String, String, Throwable?) -> Unit,
    ) {
        val profileIdsAfterImport = queries.selectAllUserProfileIds().executeAsList().toSet()
        val eligibleProfileIds = deferred.representedProfileIds
            .filterTo(linkedSetOf()) {
                it in profileIdsAfterImport && staging.parentStatus("profile", it)?.isAvailable == true
            }

        when {
            deferred.backupVersion < 4 -> Unit
            deferred.backupVersion == 4 && !deferred.legacyRackFieldPresent -> Unit
            deferred.backupVersion == 4 -> restoreLegacyV4Rack(
                items = decodeLegacyV4Rack(deferred.legacyRackElement, onInvalid),
                eligibleProfileIds = eligibleProfileIds,
                profileIdsAfterImport = profileIdsAfterImport,
                now = now,
                onInvalid = onInvalid,
            )
            else -> restoreV5ProfilePreferencesFromStaging(
                staging = staging,
                eligibleProfileIds = eligibleProfileIds,
                now = now,
                onInvalid = onInvalid,
            )
        }
    }

    private suspend fun restoreV5ProfilePreferencesFromStaging(
        staging: BackupImportStagingArea,
        eligibleProfileIds: Set<String>,
        now: Long,
        onInvalid: (String, String, Throwable?) -> Unit,
    ) {
        val section = staging.openSection("profilePreferences") ?: return
        val callerContext = currentCoroutineContext()
        val source = GuardedBackupStreamSource(section) { callerContext.ensureActive() }
        source.open()
        try {
            val nav = BackupJsonNavigator(source)
            nav.beginArray()
            while (nav.hasNextInArray()) {
                val entry = json.decodeFromString<ProfilePreferencesBackup>(nav.nextValueAsString())
                restoreV5ProfilePreferences(
                    entries = listOf(entry),
                    eligibleProfileIds = eligibleProfileIds,
                    now = now,
                    onInvalid = onInvalid,
                )
            }
            nav.endArray()
            nav.requireEndOfInput()
        } finally {
            source.close()
        }
    }

    private suspend fun restoreLegacyV4Rack(
        items: List<RackItem>?,
        eligibleProfileIds: Set<String>,
        profileIdsAfterImport: Set<String>,
        now: Long,
        onInvalid: (String, String, Throwable?) -> Unit,
    ) {
        if (items == null) return
        val targetProfileIds = eligibleProfileIds.ifEmpty {
            listOfNotNull(
                queries.getActiveProfile()
                    .executeAsOneOrNull()
                    ?.id
                    ?.takeIf { it in profileIdsAfterImport },
            )
        }
        val imported = items.filter { it.id.isNotBlank() }.distinctBy(RackItem::id)
        val importedById = imported.associateBy(RackItem::id)
        targetProfileIds.forEach { profileId ->
            val existing = profilePreferencesRepository.get(profileId).rack.value.items
            val mergedItems = if (items.isEmpty()) {
                emptyList()
            } else {
                val existingIds = existing.mapTo(linkedSetOf(), RackItem::id)
                existing.map { item -> importedById[item.id] ?: item } +
                    imported.filterNot { it.id in existingIds }
            }
            val candidate = RackPreferences(items = mergedItems)
            val validationErrors = ProfilePreferencesValidator.rack(candidate)
            if (validationErrors.isNotEmpty()) {
                onInvalid(
                    profileId,
                    "equipmentRackItems",
                    IllegalArgumentException(validationErrors.joinToString(",")),
                )
            } else {
                profilePreferencesRepository.updateRack(profileId, candidate, now)
            }
        }
    }

    private suspend fun restoreV5ProfilePreferences(
        entries: List<ProfilePreferencesBackup>,
        eligibleProfileIds: Set<String>,
        now: Long,
        onInvalid: (String, String, Throwable?) -> Unit,
    ) {
        entries.forEach { entry ->
            if (entry.profileId !in eligibleProfileIds) return@forEach
            // Infrastructure failures from this read are intentionally fatal and must
            // never be reclassified as malformed backup input.
            profilePreferencesRepository.get(entry.profileId)
            entry.core?.let { element ->
                decodeBackupSection<CoreProfilePreferences>(
                    entry.profileId,
                    "core",
                    element,
                    ProfilePreferencesValidator::core,
                    onInvalid,
                )?.let { profilePreferencesRepository.updateCore(entry.profileId, it, now) }
            }
            entry.rack?.let { element ->
                decodeBackupSection<RackPreferences>(
                    entry.profileId,
                    "rack",
                    element,
                    ProfilePreferencesValidator::rack,
                    onInvalid,
                )?.let { profilePreferencesRepository.updateRack(entry.profileId, it, now) }
            }
            entry.workout?.let { element ->
                decodeBackupSection<WorkoutPreferences>(
                    entry.profileId,
                    "workout",
                    element,
                    ProfilePreferencesValidator::workout,
                    onInvalid,
                )?.let { profilePreferencesRepository.updateWorkout(entry.profileId, it, now) }
            }
            entry.led?.let { element ->
                decodeBackupSection<LedPreferences>(
                    entry.profileId,
                    "led",
                    element,
                    ProfilePreferencesValidator::led,
                    onInvalid,
                )?.let { profilePreferencesRepository.updateLed(entry.profileId, it, now) }
            }
            entry.vbt?.let { element ->
                decodeBackupSection<VbtPreferences>(
                    entry.profileId,
                    "vbt",
                    element,
                    ProfilePreferencesValidator::vbt,
                    onInvalid,
                )?.let { profilePreferencesRepository.updateVbt(entry.profileId, it, now) }
            }
        }
    }

    private suspend fun restoreDeferredAndReconcile(
        deferred: DeferredProfileRestore,
        staging: BackupImportStagingArea,
        now: Long,
        onInvalid: (String, String, Throwable?) -> Unit,
    ) {
        try {
            restoreDeferredProfileState(deferred, staging, now, onInvalid)
        } catch (restoreFailure: Throwable) {
            val reconcileFailure = runCatching {
                withContext(NonCancellable) {
                    userProfileRepository.reconcileActiveProfileContext()
                }
            }.exceptionOrNull()
            reconcileFailure?.let(restoreFailure::addSuppressed)
            throw restoreFailure
        }
        userProfileRepository.reconcileActiveProfileContext()
    }

    private fun mapSupersetToBackup(superset: Superset): SupersetBackup = SupersetBackup(
        id = superset.id,
        routineId = superset.routineId,
        name = superset.name,
        colorIndex = superset.colorIndex.toInt(),
        restBetweenSeconds = superset.restBetweenSeconds.toInt(),
        orderIndex = superset.orderIndex.toInt(),
    )

    private data class PersonalRecordKey(
        val exerciseId: String,
        val workoutMode: String,
        val prType: String,
        val phase: String,
        val profileId: String,
    )

    private fun mapPersonalRecordToBackup(pr: PersonalRecord): PersonalRecordBackup = PersonalRecordBackup(
        id = pr.id,
        exerciseId = pr.exerciseId,
        exerciseName = pr.exerciseName,
        weight = pr.weight.toFloat(),
        reps = pr.reps.toInt(),
        oneRepMax = pr.oneRepMax.toFloat(),
        achievedAt = pr.achievedAt,
        workoutMode = pr.workoutMode,
        prType = pr.prType,
        volume = pr.volume.toFloat(),
        phase = pr.phase,
        profileId = pr.profile_id,
        cableCount = pr.cable_count?.toInt(),
        uuid = pr.uuid,
    )

    /**
     * Restore a cycle's portal sync base so its first push after restore keeps portal
     * edits instead of taking the legacy overwrite path. Malformed values are dropped.
     */
    private fun restoreCycleServerVersion(cycle: TrainingCycleBackup) {
        PortalSyncAdapter.validCycleServerVersion(cycle.serverUpdatedAt)?.let {
            queries.updateTrainingCycleServerUpdatedAt(server_updated_at = it, id = cycle.id)
        }
    }

    /**
     * Legacy backups predate serverUpdatedAt. A missing value is therefore unspecified,
     * not evidence that an otherwise identical existing cycle conflicts. Explicit values
     * still participate in the equality check so a real base mismatch blocks child import.
     */
    private fun trainingCycleBackupMatches(
        existing: TrainingCycle,
        expected: TrainingCycleBackup,
    ): Boolean {
        val actual = mapTrainingCycleToBackup(existing)
        return if (expected.serverUpdatedAt == null) {
            actual.copy(serverUpdatedAt = null) == expected
        } else {
            actual == expected
        }
    }

    private fun mapTrainingCycleToBackup(cycle: TrainingCycle): TrainingCycleBackup = TrainingCycleBackup(
        id = cycle.id,
        name = sanitizeEntityName(cycle.name, "Unnamed Cycle"),
        description = cycle.description,
        createdAt = cycle.created_at,
        isActive = cycle.is_active != 0L,
        profileId = cycle.profile_id,
        templateId = cycle.template_id,
        weekNumber = cycle.week_number.toInt(),
        serverUpdatedAt = cycle.server_updated_at,
        deletedAt = cycle.deletedAt,
        updatedAt = cycle.updatedAt,
    )

    private fun mapCustomExerciseToBackup(exercise: Exercise): CustomExerciseBackup = CustomExerciseBackup(
        id = exercise.id,
        name = exercise.name,
        displayName = exercise.displayName,
        description = exercise.description,
        created = exercise.created,
        muscleGroup = exercise.muscleGroup,
        muscleGroups = exercise.muscleGroups,
        muscles = exercise.muscles,
        equipment = exercise.equipment,
        movement = exercise.movement,
        sidedness = exercise.sidedness,
        grip = exercise.grip,
        gripWidth = exercise.gripWidth,
        minRepRange = exercise.minRepRange?.toFloat(),
        popularity = exercise.popularity.toFloat(),
        archived = exercise.archived != 0L,
        isFavorite = exercise.isFavorite != 0L,
        timesPerformed = exercise.timesPerformed.toInt(),
        lastPerformed = exercise.lastPerformed,
        aliases = exercise.aliases,
        defaultCableConfig = exercise.defaultCableConfig,
        legacyOneRepMaxKg = exercise.one_rep_max_kg?.toFloat(),
        updatedAt = exercise.updatedAt,
        serverId = exercise.serverId,
        deletedAt = exercise.deletedAt,
        mvtOverrideMs = exercise.mvtOverrideMs?.toFloat(),
        isBodyweight = exercise.isBodyweight?.let { it != 0L },
    )

    private fun mapWorkoutDeletionToBackup(row: WorkoutDeletion) = WorkoutDeletionBackup(
        mutationId = row.mutation_id,
        ownerUserId = row.owner_user_id,
        profileId = row.profile_id,
        scope = row.scope,
        portalSessionId = row.portal_session_id,
        componentSessionId = row.component_session_id,
        deletedAt = row.deleted_at,
        acknowledgedAt = row.acknowledged_at,
        source = row.source,
    )

    private fun mapPendingRecoveryToBackup(row: PendingProfileRecovery) = PendingProfileRecoveryBackup(
        recoveryId = row.recovery_id,
        kind = row.kind,
        sourceKey = row.source_key,
        sourceProfileId = row.source_profile_id,
        sourceProfileName = row.source_profile_name,
        ownerUserId = row.owner_user_id,
        countsJson = row.counts_json,
        discoveredAt = row.discovered_at,
        resolvedAt = row.resolved_at,
    )

    private fun mapOwnershipTransferToBackup(row: OwnershipTransferOutbox) = OwnershipTransferBackup(
        mutationId = row.mutation_id,
        ownerUserId = row.owner_user_id,
        sourceProfileId = row.source_profile_id,
        targetProfileId = row.target_profile_id,
        workoutSessionIdsJson = row.workout_session_ids_json,
        routineIdsJson = row.routine_ids_json,
        cycleIdsJson = row.cycle_ids_json,
        personalRecordIdsJson = row.personal_record_ids_json,
        createdAt = row.created_at,
        acknowledgedAt = row.acknowledged_at,
    )

    private fun mapAppliedOwnershipEventToBackup(row: AppliedOwnershipEvent) = AppliedOwnershipEventBackup(
        ownerUserId = row.owner_user_id,
        mutationId = row.mutation_id,
        canonicalBodyHash = row.canonical_body_hash,
        appliedAt = row.applied_at,
    )

    private fun mapLocalOwnershipClaimToBackup(row: LocalOwnershipClaim) = LocalOwnershipClaimBackup(
        ownerUserId = row.owner_user_id,
        entityType = row.entity_type,
        entityId = row.entity_id,
        mutationId = row.mutation_id,
        sourceProfileId = row.source_profile_id,
        targetProfileId = row.target_profile_id,
        transferredAt = row.transferred_at,
    )

    private fun mapCycleSyncStateToBackup(row: CycleSyncState) = CycleSyncStateBackup(
        cycleId = row.cycle_id,
        profileId = row.profile_id,
        accountId = row.account_id,
        dirtyGeneration = row.dirty_generation,
        acknowledgedGeneration = row.acknowledged_generation,
        pendingDeleteUpdatedAt = row.pending_delete_updated_at,
        pendingDeleteGeneration = row.pending_delete_generation,
    )

    private fun mapCycleConflictDraftToBackup(row: CycleConflictDraft) = CycleConflictDraftBackup(
        id = row.id,
        cycleId = row.cycle_id,
        originalProfileId = row.original_profile_id,
        rejectedUpdatedAt = row.rejected_updated_at,
        payloadJson = row.payload_json,
        createdAt = row.created_at,
        resolution = row.resolution,
    )

    private fun mapCycleDayToBackup(day: CycleDay): CycleDayBackup = CycleDayBackup(
        id = day.id,
        cycleId = day.cycle_id,
        dayNumber = day.day_number.toInt(),
        name = day.name,
        routineId = day.routine_id,
        isRestDay = day.is_rest_day != 0L,
        echoLevel = day.echo_level,
        eccentricLoadPercent = day.eccentric_load_percent?.toInt(),
        weightProgressionPercent = day.weight_progression_percent?.toFloat(),
        repModifier = day.rep_modifier?.toInt(),
        restTimeOverrideSeconds = day.rest_time_override_seconds?.toInt(),
    )

    private fun mapCycleProgressToBackup(cp: CycleProgress): CycleProgressBackup = CycleProgressBackup(
        id = cp.id,
        cycleId = cp.cycle_id,
        currentDayNumber = cp.current_day_number.toInt(),
        lastCompletedDate = cp.last_completed_date,
        cycleStartDate = cp.cycle_start_date,
        lastAdvancedAt = cp.last_advanced_at,
        completedDays = cp.completed_days,
        missedDays = cp.missed_days,
        rotationCount = cp.rotation_count.toInt(),
    )

    private fun mapCycleProgressionToBackup(cprog: CycleProgression): CycleProgressionBackup = CycleProgressionBackup(
        cycleId = cprog.cycle_id,
        frequencyCycles = cprog.frequency_cycles.toInt(),
        weightIncreasePercent = cprog.weight_increase_percent?.toFloat(),
        echoLevelIncrease = cprog.echo_level_increase.toInt(),
        eccentricLoadIncreasePercent = cprog.eccentric_load_increase_percent?.toInt(),
    )

    private fun mapPlannedSetToBackup(ps: PlannedSet): PlannedSetBackup = PlannedSetBackup(
        id = ps.id,
        routineExerciseId = ps.routine_exercise_id,
        setNumber = ps.set_number.toInt(),
        setType = ps.set_type,
        targetReps = ps.target_reps?.toInt(),
        targetWeightKg = ps.target_weight_kg?.toFloat(),
        targetRpe = ps.target_rpe?.toInt(),
        restSeconds = ps.rest_seconds?.toInt(),
    )

    private fun mapCompletedSetToBackup(cs: CompletedSet): CompletedSetBackup = CompletedSetBackup(
        id = cs.id,
        sessionId = cs.session_id,
        plannedSetId = cs.planned_set_id,
        routineExerciseId = cs.routine_exercise_id,
        setNumber = cs.set_number.toInt(),
        setType = cs.set_type,
        attemptNumber = cs.attempt_number.toInt().coerceAtLeast(1),
        actualReps = cs.actual_reps.toInt(),
        actualWeightKg = cs.actual_weight_kg.toFloat(),
        loggedRpe = cs.logged_rpe?.toInt(),
        isPr = cs.is_pr != 0L,
        completedAt = cs.completed_at,
        setEndReason = SetEndReason.fromPersisted(cs.set_end_reason).name,
    )

    private fun mapProgressionEventToBackup(pe: ProgressionEvent): ProgressionEventBackup = ProgressionEventBackup(
        id = pe.id,
        exerciseId = pe.exercise_id,
        suggestedWeightKg = pe.suggested_weight_kg.toFloat(),
        previousWeightKg = pe.previous_weight_kg.toFloat(),
        reason = pe.reason,
        userResponse = pe.user_response,
        actualWeightKg = pe.actual_weight_kg?.toFloat(),
        timestamp = pe.timestamp,
        profileId = pe.profile_id,
    )

    private fun mapEarnedBadgeToBackup(eb: EarnedBadge): EarnedBadgeBackup = EarnedBadgeBackup(
        id = eb.id,
        badgeId = eb.badgeId,
        earnedAt = eb.earnedAt,
        celebratedAt = eb.celebratedAt,
        profileId = eb.profile_id,
        updatedAt = eb.updatedAt,
        serverId = eb.serverId,
        deletedAt = eb.deletedAt,
    )

    private fun mapStreakHistoryToBackup(sh: StreakHistory): StreakHistoryBackup = StreakHistoryBackup(
        id = sh.id,
        startDate = sh.startDate,
        endDate = sh.endDate,
        length = sh.length.toInt(),
        profileId = sh.profile_id,
    )

    private fun mapGamificationStatsToBackup(gs: GamificationStats): GamificationStatsBackup = GamificationStatsBackup(
        totalWorkouts = gs.totalWorkouts.toInt(),
        totalReps = gs.totalReps.toInt(),
        totalVolumeKg = gs.totalVolumeKg.toInt(),
        longestStreak = gs.longestStreak.toInt(),
        currentStreak = gs.currentStreak.toInt(),
        uniqueExercisesUsed = gs.uniqueExercisesUsed.toInt(),
        prsAchieved = gs.prsAchieved.toInt(),
        lastWorkoutDate = gs.lastWorkoutDate,
        streakStartDate = gs.streakStartDate,
        lastUpdated = gs.lastUpdated,
        profileId = gs.profile_id,
        updatedAt = gs.updatedAt,
        serverId = gs.serverId,
    )

    private fun mapSessionNotesToBackup(sn: SessionNotes): SessionNotesBackup = SessionNotesBackup(
        routineSessionId = sn.routineSessionId,
        notes = sn.notes,
        updatedAt = sn.updatedAt,
    )

    private fun mapRoutineGroupToBackup(rg: RoutineGroup): RoutineGroupBackup = RoutineGroupBackup(
        id = rg.id,
        name = rg.name,
        orderIndex = rg.orderIndex.toInt(),
        createdAt = rg.createdAt,
        profileId = rg.profile_id,
    )

    private fun mapUserProfileToBackup(up: UserProfile): UserProfileBackup = UserProfileBackup(
        id = up.id,
        name = up.name,
        colorIndex = up.colorIndex.toInt(),
        createdAt = up.createdAt,
        isActive = up.isActive != 0L,
        supabaseUserId = up.supabase_user_id,
    )

    /**
     * v6 restore rejects sessions whose profile is absent and drops custom exercise
     * IDs that are not in the catalog. Auto-backups must therefore ship the
     * referenced identity rows, not only the workout payload.
     */
    private fun referencedUserProfiles(profileIds: Collection<String?>): List<UserProfileBackup> =
        profileIds.filterNotNull().distinct().mapNotNull { profileId ->
            queries.getProfileById(profileId).executeAsOneOrNull()?.let(::mapUserProfileToBackup)
        }

    private fun referencedCustomExercises(exerciseIds: Collection<String?>): List<CustomExerciseBackup> =
        exerciseIds.filterNotNull().distinct().mapNotNull { exerciseId ->
            queries.selectExerciseById(exerciseId).executeAsOneOrNull()
                ?.takeIf { it.isCustom == 1L }
                ?.let(::mapCustomExerciseToBackup)
        }

    // -- Per-session auto-backup (Phase 36) --

    override suspend fun getBackupStats(): BackupStats = withContext(Dispatchers.IO) {
        val sizes = listBackupFileSizes()
        BackupStats(
            fileCount = sizes.size,
            totalBytes = sizes.sum(),
        )
    }

    override suspend fun exportSession(sessionId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val session = queries.selectSessionById(sessionId).executeAsOneOrNull()
                ?: return@withContext Result.failure(Exception("Session not found: $sessionId"))

            val metrics = if (includeRawTelemetryInBackups) queries.selectMetricsBySession(sessionId).executeAsList() else emptyList()
            val completedSets = queries.selectCompletedSetsBySession(sessionId).executeAsList()
            val sessions = listOf(session)

            // Session payload plus the profile/custom-exercise parents v6 restore requires.
            // Note: mapSessionToBackup called without routineNameResolutionContext.
            // This means legacy sessions (pre-migration 12) won't get enriched routine names.
            // Acceptable trade-off: avoids loading all routines for a single-session backup.
            val sessionBackupNowMs = KmpUtils.currentTimeMillis()
            val backupData = BackupData(
                version = CURRENT_BACKUP_VERSION,
                exportedAt = KmpUtils.formatTimestamp(sessionBackupNowMs, "yyyy-MM-dd") + "T" +
                    KmpUtils.formatTimestamp(sessionBackupNowMs, "HH:mm:ss") + "Z",
                appVersion = Constants.APP_VERSION,
                privacy = backupPrivacy(),
                data = BackupContent(
                    userProfiles = referencedUserProfiles(sessions.map { it.profile_id }),
                    customExercises = referencedCustomExercises(sessions.map { it.exerciseId }),
                    workoutSessions = sessions.map { mapSessionToBackup(it) },
                    metricSamples = metrics.map { mapMetricToBackup(it) },
                    completedSets = completedSets.map { mapCompletedSetToBackup(it) },
                ),
            )

            val jsonString = json.encodeToString(backupData)

            // Build filename: phoenix-workout-{ISO-date}-{sessionId}.json
            // Use the session's start timestamp so the filename reflects when the workout happened
            val isoDate = KmpUtils.formatTimestamp(session.timestamp, "yyyy-MM-dd")
            val fileName = "phoenix-workout-$isoDate-$sessionId.json"

            val backupDir = getSessionBackupDirectory()
            val filePath = "$backupDir/$fileName"

            writeSessionBackupFile(filePath, jsonString)

            // Retention policy: keep only the last MAX_ROUTINE_BACKUPS files
            pruneOldBackups(MAX_ROUTINE_BACKUPS)

            Logger.d { "Auto-backup saved: $filePath (${jsonString.length} bytes)" }
            Result.success(filePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e) { "Auto-backup failed for session $sessionId" }
            Result.failure(e)
        }
    }

    /**
     * Issue #525: one backup file per completed routine.
     *
     * Looks up every WorkoutSession that shares [routineSessionId] for the active profile
     * and writes a single BackupData containing all of them, their metrics, and their
     * completed sets. Returns failure if no sessions are found. Filename uses the earliest
     * session timestamp in the routine so consecutive routines sort cleanly.
     */
    override suspend fun exportRoutine(routineSessionId: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val activeProfileId = queries.getActiveProfile().executeAsOneOrNull()?.id ?: "default"
            val sessions = queries.selectSessionsByRoutineSessionId(
                profileId = activeProfileId,
                routineSessionId = routineSessionId,
            ).executeAsList()

            if (sessions.isEmpty()) {
                return@withContext Result.failure(
                    Exception("No sessions found for routine: $routineSessionId"),
                )
            }

            // Aggregate metrics and completed sets across all sessions in the routine.
            // Done per-session to mirror exportSession()'s OOM-safe pattern.
            val allMetrics = mutableListOf<MetricSample>()
            val allCompletedSets = mutableListOf<CompletedSet>()
            for (session in sessions) {
                if (includeRawTelemetryInBackups) allMetrics.addAll(queries.selectMetricsBySession(session.id).executeAsList())
                allCompletedSets.addAll(queries.selectCompletedSetsBySession(session.id).executeAsList())
            }

            val routineNowMs = KmpUtils.currentTimeMillis()
            val backupData = BackupData(
                version = CURRENT_BACKUP_VERSION,
                exportedAt = KmpUtils.formatTimestamp(routineNowMs, "yyyy-MM-dd") + "T" +
                    KmpUtils.formatTimestamp(routineNowMs, "HH:mm:ss") + "Z",
                appVersion = Constants.APP_VERSION,
                privacy = backupPrivacy(),
                data = BackupContent(
                    userProfiles = referencedUserProfiles(sessions.map { it.profile_id }),
                    customExercises = referencedCustomExercises(sessions.map { it.exerciseId }),
                    workoutSessions = sessions.map { mapSessionToBackup(it) },
                    metricSamples = allMetrics.map { mapMetricToBackup(it) },
                    completedSets = allCompletedSets.map { mapCompletedSetToBackup(it) },
                ),
            )

            val jsonString = json.encodeToString(backupData)

            // Filename: phoenix-routine-{yyyy-MM-dd}-{routineSessionId}.json
            // Use the earliest session timestamp so consecutive routines sort by date.
            val earliestTimestamp = sessions.minOf { it.timestamp }
            val isoDate = KmpUtils.formatTimestamp(earliestTimestamp, "yyyy-MM-dd")
            val fileName = "phoenix-routine-$isoDate-$routineSessionId.json"

            val backupDir = getSessionBackupDirectory()
            val filePath = "$backupDir/$fileName"

            writeSessionBackupFile(filePath, jsonString)

            // Retention policy: keep only the last MAX_ROUTINE_BACKUPS files
            pruneOldBackups(MAX_ROUTINE_BACKUPS)

            Logger.d { "Routine auto-backup saved: $filePath (${jsonString.length} bytes, ${sessions.size} sessions)" }
            Result.success(filePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e) { "Routine auto-backup failed for routine $routineSessionId" }
            Result.failure(e)
        }
    }

    /**
     * Write backup JSON content to a file at the given path.
     * Platform subclasses override this with native file I/O.
     */
    protected open fun writeSessionBackupFile(filePath: String, content: String) {
        // Default implementation using BackupJsonWriter (works on both platforms)
        val writer = BackupJsonWriter(filePath)
        try {
            writer.open()
            writer.write(content)
            writer.flush()
            writer.close()
        } catch (e: Exception) {
            runCatching { writer.close() }
            runCatching { writer.delete() }
            throw e
        }
    }
}
