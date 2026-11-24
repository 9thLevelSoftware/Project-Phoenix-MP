package com.devil.phoenixproject.data.maintenance

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.ProfileMutationBarrier
import com.devil.phoenixproject.data.repository.ProfileRecoveryActivityTracker
import com.devil.phoenixproject.database.MetricSample
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.SampleStatus
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext

/**
 * Retroactive cable-telemetry compaction service (issue #1425).
 *
 * `analyze()` is strictly read-only and never takes the barrier. `compact()` runs one set per
 * transaction inside `ProfileMutationBarrier.withExclusive` with the G0 reservation held only
 * around each transaction and the final VACUUM (I3): a blocked workout start returns silently,
 * so the reservation must stay short. The range delete is exact only together with the
 * id-ordered re-plan inside the same transaction (signoff #3).
 */
class TelemetryCompactionService(
    private val database: PhoenixDatabase,
    private val preferencesManager: PreferencesManager,
    private val barrier: ProfileMutationBarrier,
    private val activityTracker: ProfileRecoveryActivityTracker,
    /** G0 session guard (I3): true while a workout session may be active/resumable. */
    private val workoutSessionGuard: () -> Boolean,
    private val databaseMaintenance: DatabaseMaintenance,
    private val backupBytesEstimator: TelemetryBackupBytesEstimator,
    private val nowEpochMs: () -> Long = ::currentTimeMillis,
) {
    private val queries get() = database.phoenixDatabaseQueries

    /** Two compaction passes never overlap. */
    private val passMutex = Mutex()

    /**
     * Read-only dry run: scans candidate sets and reports what a cleanup would reclaim.
     * Writes nothing (AC1) and never takes the barrier.
     */
    suspend fun analyze(
        rescanAll: Boolean = false,
        progress: (suspend (checked: Int, total: Int) -> Unit)? = null,
    ): CompactionReport = passMutex.withLock {
        val startedAt = nowEpochMs()
        val epochRepairApplied = epochRepairApplied()
        val total = countCandidates(rescanAll)
        var setsScanned = 0
        var setsCleanable = 0
        var setsKept = 0
        var rowsReclaimable = 0
        val keepReasonCounts = linkedMapOf<PreserveReason, Int>()
        val estimatorRows = mutableListOf<MetricSample>()

        forEachCandidate(rescanAll) { row ->
            val archival = readArchivalSet(row.sessionId)
            val plan = RetroactiveTelemetryCompactionPlanner.plan(
                candidate = row.toCompactionCandidate(),
                samples = archival.samples,
                minUnitVersion = archival.minUnitVersion,
                firstRepStartMs = archival.firstRepStartMs,
                epochRepairApplied = epochRepairApplied,
            )
            when (plan) {
                is SetCompactionPlan.Trim -> {
                    setsCleanable++
                    rowsReclaimable += plan.discardCount
                }
                is SetCompactionPlan.Preserve -> {
                    setsKept++
                    keepReasonCounts[plan.reason] = (keepReasonCounts[plan.reason] ?: 0) + 1
                }
            }
            if (estimatorRows.size < ESTIMATOR_ROW_SAMPLE_LIMIT) {
                estimatorRows += archival.rawRows.take(ESTIMATOR_ROW_SAMPLE_LIMIT - estimatorRows.size)
            }
            setsScanned++
            progress?.invoke(setsScanned, total)
        }

        val report = CompactionReport(
            setsScanned = setsScanned,
            setsCleanable = setsCleanable,
            setsKept = setsKept,
            rowsReclaimable = rowsReclaimable,
            estimatedDatabaseBytesReclaimable = estimateDatabaseBytesReclaimable(rowsReclaimable),
            estimatedBackupBytesReclaimable =
                backupBytesEstimator.estimateBackupBytesReclaimable(estimatorRows, rowsReclaimable),
            keepReasonCounts = keepReasonCounts,
        )
        Logger.i {
            "telemetry-compaction analyze sets=${report.setsScanned} cleanable=${report.setsCleanable} " +
                "rows=$rowsReclaimable keeps=${keepReasonCounts.map { it.key.code to it.value }} " +
                "durationMs=${nowEpochMs() - startedAt}"
        }
        report
    }

    /**
     * Runs the retroactive trim pass: one set per transaction, watermark advanced only after
     * each committed set, cancellation checked between sets. With [vacuum] the pass finishes
     * with a guarded VACUUM. Failures never corrupt set metrics: a count mismatch rolls back
     * exactly that set.
     */
    suspend fun compact(
        vacuum: Boolean = true,
        progress: (suspend (checked: Int, total: Int) -> Unit)? = null,
        stopRequested: () -> Boolean = { false },
    ): CompactionResult = passMutex.withLock {
        val startedAt = nowEpochMs()
        val databaseBytesBeforePass = databaseMaintenance.measure().logicalBytes
        val epochRepairApplied = epochRepairApplied()
        val total = countCandidates(rescanAll = false)
        var setsCleaned = 0
        var rowsDeleted = 0
        var stoppedAfterSet = false
        var abortedActiveWorkout = false
        val keepReasonCounts = linkedMapOf<PreserveReason, Int>()
        var processed = 0

        run loop@{
            forEachCandidate(rescanAll = false) { row ->
                if (stopRequested()) {
                    stoppedAfterSet = true
                    return@loop
                }
                val outcome = compactOneSet(row, epochRepairApplied)
                if (outcome.aborted) {
                    abortedActiveWorkout = true
                    return@loop
                }
                setsCleaned += outcome.setsCleaned
                rowsDeleted += outcome.rowsDeleted
                outcome.preserveReason?.let { keepReasonCounts[it] = (keepReasonCounts[it] ?: 0) + 1 }
                processed++
                // Watermark advances only after this set's transaction committed.
                preferencesManager.setTelemetryCompactionWatermark(
                    TelemetryCompactionWatermark(
                        policyVersion = TELEMETRY_COMPACTION_POLICY_VERSION,
                        timestamp = row.timestamp,
                        sessionId = row.sessionId,
                    ),
                )
                progress?.invoke(processed, total)
                coroutineContext.ensureActive()
            }
        }

        val vacuumOutcome = runVacuumStep(
            requested = vacuum,
            abortedActiveWorkout = abortedActiveWorkout,
            rowsDeleted = rowsDeleted,
        )
        val result = CompactionResult(
            setsCleaned = setsCleaned,
            rowsDeleted = rowsDeleted,
            stoppedAfterSet = stoppedAfterSet,
            abortedActiveWorkout = abortedActiveWorkout,
            databaseBytesBefore = if (vacuumOutcome is VacuumOutcome.Ran) databaseBytesBeforePass else null,
            databaseBytesAfter = (vacuumOutcome as? VacuumOutcome.Ran)?.bytesAfter,
            vacuum = vacuumOutcome,
        )
        Logger.i {
            "telemetry-compaction compact setsCleaned=$setsCleaned rowsDeleted=$rowsDeleted " +
                "stopped=$stoppedAfterSet aborted=$abortedActiveWorkout vacuum=$vacuumOutcome " +
                "keeps=${keepReasonCounts.map { it.key.code to it.value }} durationMs=${nowEpochMs() - startedAt}"
        }
        result
    }

    private data class SetOutcome(
        val aborted: Boolean,
        val setsCleaned: Int = 0,
        val rowsDeleted: Int = 0,
        val preserveReason: PreserveReason? = null,
    )

    private suspend fun compactOneSet(
        row: CandidateRow,
        epochRepairApplied: Boolean,
    ): SetOutcome = barrier.withExclusive {
        // G0 (I3): session guard + atomic reservation, held only around this transaction.
        if (workoutSessionGuard() || !activityTracker.tryReserveRecovery()) {
            return@withExclusive SetOutcome(aborted = true)
        }
        try {
            var rowsDeleted = 0
            var setsCleaned = 0
            var preserveReason: PreserveReason? = null
            database.transaction {
                // In-transaction id-ordered re-plan (signoff #3): never derive the boundary
                // from a timestamp-ordered read.
                val archival = readArchivalSet(row.sessionId)
                val plan = RetroactiveTelemetryCompactionPlanner.plan(
                    candidate = row.toCompactionCandidate(),
                    samples = archival.samples,
                    minUnitVersion = archival.minUnitVersion,
                    firstRepStartMs = archival.firstRepStartMs,
                    epochRepairApplied = epochRepairApplied,
                )
                when (plan) {
                    is SetCompactionPlan.Preserve -> preserveReason = plan.reason
                    is SetCompactionPlan.Trim -> {
                        queries.deleteIdlePrefixMetrics(
                            sessionId = row.sessionId,
                            boundaryTs = plan.boundaryTs,
                            safetyMask = SAFETY_STATUS_MASK,
                        ).value
                        val changed = queries.selectChangesCount().executeAsOne() ?: 0L
                        check(changed == plan.discardCount.toLong()) {
                            "telemetry-compaction delete count mismatch for ${row.sessionId}: " +
                                "changes=$changed expected=${plan.discardCount}"
                        }
                        rowsDeleted = changed.toInt()
                        setsCleaned = 1
                    }
                }
            }
            SetOutcome(
                aborted = false,
                setsCleaned = setsCleaned,
                rowsDeleted = rowsDeleted,
                preserveReason = preserveReason,
            )
        } finally {
            activityTracker.releaseRecoveryReservation()
        }
    }

    private suspend fun runVacuumStep(
        requested: Boolean,
        abortedActiveWorkout: Boolean,
        rowsDeleted: Int,
    ): VacuumOutcome {
        if (!requested) return VacuumOutcome.Skipped(VacuumSkipReason.NOT_REQUESTED)
        if (abortedActiveWorkout || rowsDeleted == 0) {
            return VacuumOutcome.Skipped(
                if (abortedActiveWorkout) VacuumSkipReason.ACTIVE_WORKOUT else VacuumSkipReason.INSUFFICIENT_FREELIST,
            )
        }
        val precheck = databaseMaintenance.canVacuum()
        if (precheck is VacuumPrecheck.Blocked) return VacuumOutcome.Skipped(precheck.reason)
        return barrier.withExclusive {
            if (workoutSessionGuard() || !activityTracker.tryReserveRecovery()) {
                VacuumOutcome.Skipped(VacuumSkipReason.ACTIVE_WORKOUT)
            } else {
                try {
                    databaseMaintenance.vacuum()
                } finally {
                    activityTracker.releaseRecoveryReservation()
                }
            }
        }
    }

    private data class ArchivalSet(
        val rawRows: List<MetricSample>,
        val samples: List<WorkoutMetric>?,
        val minUnitVersion: Long,
        val firstRepStartMs: Long?,
    )

    /** Id-ordered archival read (I2) + planner inputs. samples == null means NULL_POSITION (I1). */
    private fun readArchivalSet(sessionId: String): ArchivalSet {
        val rawRows = queries.selectArchivalMetricsBySession(sessionId).executeAsList()
        var samples: MutableList<WorkoutMetric>? = mutableListOf()
        for (row in rawRows) {
            val mapped = row.toArchivalWorkoutMetricOrNull()
            if (mapped == null) {
                samples = null
                break
            }
            samples?.add(mapped)
        }
        return ArchivalSet(
            rawRows = rawRows,
            samples = samples,
            minUnitVersion = queries.selectMinMetricUnitVersionBySession(sessionId).executeAsOne().MIN ?: 1L,
            firstRepStartMs = queries.selectFirstRepStartBySession(sessionId).executeAsOne().MIN,
        )
    }

    private fun epochRepairApplied(): Boolean =
        queries.selectAppliedDataRepair(EPOCH_ZERO_REPAIR_KEY).executeAsOneOrNull() != null

    private fun estimateDatabaseBytesReclaimable(rowsReclaimable: Int): Long? {
        if (rowsReclaimable == 0) return 0L
        val totalSampleRows = queries.countBackupMetricSamples().executeAsOne()
        if (totalSampleRows <= 0L) return null
        val logicalBytes = databaseMaintenance.measure().logicalBytes
        val bytesPerRow = (logicalBytes / totalSampleRows).coerceAtLeast(1L)
        return bytesPerRow * rowsReclaimable
    }

    private data class CandidateRow(
        val sessionId: String,
        val timestamp: Long,
        val targetReps: Long,
        val exerciseId: String?,
        val exerciseIsBodyweight: Long?,
        val exerciseEquipment: String?,
    ) {
        fun toCompactionCandidate(): CompactionCandidate = CompactionCandidate(
            sessionId = sessionId,
            timestamp = timestamp,
            targetReps = targetReps,
            exerciseId = exerciseId,
            exerciseIsBodyweight = resolveArchivalBodyweight(exerciseId, exerciseIsBodyweight, exerciseEquipment),
        )
    }

    private fun countCandidates(rescanAll: Boolean): Int {
        var count = 0
        forEachCandidate(rescanAll) { count++ }
        return count
    }

    private inline fun forEachCandidate(rescanAll: Boolean, block: (CandidateRow) -> Unit) {
        val watermark = if (rescanAll) {
            null
        } else {
            preferencesManager.telemetryCompactionWatermark()
                ?.takeIf { it.policyVersion == TELEMETRY_COMPACTION_POLICY_VERSION }
        }
        var afterTimestamp = watermark?.timestamp ?: Long.MIN_VALUE
        var afterSessionId = watermark?.sessionId ?: ""
        while (true) {
            val page = queries.selectTelemetryCompactionCandidates(
                afterTimestamp = afterTimestamp,
                afterSessionId = afterSessionId,
                limitCount = CANDIDATE_PAGE_LIMIT.toLong(),
            ).executeAsList()
            if (page.isEmpty()) return
            for (row in page) {
                block(
                    CandidateRow(
                        sessionId = row.sessionId,
                        timestamp = row.timestamp,
                        targetReps = row.targetReps,
                        exerciseId = row.exerciseId,
                        exerciseIsBodyweight = row.exerciseIsBodyweight,
                        exerciseEquipment = row.exerciseEquipment,
                    ),
                )
            }
            val last = page.last()
            afterTimestamp = last.timestamp
            afterSessionId = last.sessionId
            if (page.size < CANDIDATE_PAGE_LIMIT) return
        }
    }

    companion object {
        /** Keyset page cap; pages are tiny so a temp B-tree for the id tiebreak is fine (I6). */
        const val CANDIDATE_PAGE_LIMIT = 200

        /** Rows sampled for the JSON backup byte estimate. */
        const val ESTIMATOR_ROW_SAMPLE_LIMIT = 200

        const val EPOCH_ZERO_REPAIR_KEY = "epoch-zero-session-starts-v1"

        val SAFETY_STATUS_MASK: Long = SampleStatus.SAFETY_STATUS_MASK.toLong()

        /**
         * Resolves the Exercise domain bodyweight rule (`isBodyweightOverride ?: !hasCableAccessory`)
         * from the LEFT JOIN projection. Null means the exercise is unknown (or the join missed):
         * such sets are preserved (G3).
         */
        internal fun resolveArchivalBodyweight(
            exerciseId: String?,
            rawIsBodyweight: Long?,
            exerciseEquipment: String?,
        ): Long? {
            if (exerciseId == null) return null
            if (rawIsBodyweight != null) return if (rawIsBodyweight != 0L) 1L else 0L
            if (exerciseEquipment == null) return null // join miss: unknown exercise
            val derived = Exercise(
                name = "",
                muscleGroup = "",
                equipment = exerciseEquipment,
                isBodyweightOverride = null,
            ).isBodyweight
            return if (derived) 1L else 0L
        }
    }
}
