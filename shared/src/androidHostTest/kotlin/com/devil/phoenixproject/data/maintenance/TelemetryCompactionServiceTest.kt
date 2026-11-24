package com.devil.phoenixproject.data.maintenance

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.ProfileMutationBarrier
import com.devil.phoenixproject.data.repository.ProfileRecoveryActivityTracker
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.SampleStatus
import com.devil.phoenixproject.presentation.manager.CableTelemetryArchivalRetention
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.createTestSchema
import com.devil.phoenixproject.testutil.seedExercise
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T2.1–T2.9 (issue #1425): multi-set fixture correctness, dry-run immutability, write-time
 * parity (T2.3 / signoff #3), summary invariance (T2.4 / I10), idempotency, transactional
 * rollback, childless-check invariance, watermark + restore, and the G0 gate.
 */
class TelemetryCompactionServiceTest {

    // ===== T2.1: multi-set fixture =====

    @Test
    fun `multi set fixture trims only eligible sets and reports exact keep reasons`() {
        val fixture = Fixture()
        fixture.seedAll()
        val report = runBlocking { fixture.service.analyze() }

        assertEquals(14, report.setsScanned)
        assertEquals(2, report.setsCleanable, "only the eligible and safety-prefix sets are cleanable")
        assertEquals(6, report.rowsReclaimable, "4 idle rows + 2 idle rows behind safety flags")
        assertEquals(12, report.setsKept)
        assertEquals(
            mapOf(
                PreserveReason.NOTHING_TO_TRIM to 1,
                PreserveReason.EXERCISE_UNKNOWN_OR_BODYWEIGHT to 3,
                PreserveReason.NOT_REP_TARGETED to 1,
                PreserveReason.LEGACY_UNIT_SCALE to 1,
                PreserveReason.CORRUPT_OR_UNREPAIRED_START to 1,
                PreserveReason.NO_REP_EVIDENCE to 1,
                PreserveReason.BOUNDARY_INSIDE_REP to 1,
                PreserveReason.policyKeep(
                    com.devil.phoenixproject.presentation.manager.RetentionKeepReason.NO_CONFIRMED_DEPARTURE,
                ) to 1,
                PreserveReason.NULL_POSITION to 1,
                PreserveReason.policyKeep(
                    com.devil.phoenixproject.presentation.manager.RetentionKeepReason.INVALID_ORDER_OR_POSITION,
                ) to 1,
            ),
            report.keepReasonCounts,
        )

        runBlocking { fixture.service.compact(vacuum = false) }

        // Only the two eligible sets changed; every kept set kept every row.
        assertEquals(listOf(5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L), fixture.sampleIdsOf(S_ELIGIBLE))
        assertEquals(
            listOf(101L, 103L, 105L, 106L, 107L, 108L, 109L, 110L, 111L, 112L, 113L),
            fixture.sampleIdsOf(S_SAFETY),
            "the two safety rows survive while the two unflagged idle rows are trimmed",
        )
        for (kept in listOf(
            S_CLEAN, S_BODYWEIGHT, S_TIMED, S_UNKNOWN_EX, S_LEGACY, S_CORRUPT,
            S_NOREP, S_EARLYREP, S_AMBIGUOUS, S_NULLPOS, S_OUTOFORDER, S_NOEXERCISE,
        )) {
            assertEquals(
                fixture.sampleIdsSnapshotBefore[kept],
                fixture.sampleIdsOf(kept),
                "kept set $kept must keep every sample",
            )
        }
    }

    // ===== T2.2: dry run writes nothing =====

    @Test
    fun `dry run leaves every table and the page count unchanged`() {
        val fixture = Fixture()
        fixture.seedAll()
        val tables = listOf("MetricSample", "WorkoutSession", "CompletedSet", "RepMetric", "RepBiomechanics")
        val before = tables.associateWith { fixture.tableChecksum(it) } + ("page_count" to fixture.pragmaLong("PRAGMA page_count").toString())

        runBlocking { fixture.service.analyze() }
        runBlocking { fixture.service.analyze(rescanAll = true) }

        val after = tables.associateWith { fixture.tableChecksum(it) } + ("page_count" to fixture.pragmaLong("PRAGMA page_count").toString())
        assertEquals(before, after, "analyze() must be strictly read-only")
    }

    // ===== T2.3: write-time parity (AC3) =====

    @Test
    fun `compaction keeps exactly the rows the write time policy keeps`() {
        val fixture = Fixture()
        fixture.seedAll()
        runBlocking { fixture.service.compact(vacuum = false) }

        for (sessionId in listOf(S_ELIGIBLE, S_SAFETY, S_CLEAN)) {
            val rows = fixture.archivalRowsOf(sessionId)
            val expectedKept = CableTelemetryArchivalRetention.selectForRomCableSet(
                samples = rows.map { it.toArchivalWorkoutMetricOrNull()!! },
                romEnabled = true,
            )
            assertEquals(
                expectedKept.map { it.timestamp },
                fixture.sampleTimestampsOf(sessionId),
                "write-time policy kept rows must equal the surviving rows for $sessionId",
            )
        }
    }

    // ===== T2.4: summaries byte-identical =====

    @Test
    fun `summaries peak power and torso chart input are unchanged`() {
        val fixture = Fixture()
        fixture.seedAll()
        val sessionDumpBefore = fixture.tableChecksum("WorkoutSession")
        val completedSetBefore = fixture.tableChecksum("CompletedSet")
        val repMetricBefore = fixture.tableChecksum("RepMetric")
        val repBiomechanicsBefore = fixture.tableChecksum("RepBiomechanics")
        val peakBefore = fixture.selectPeakPower()
        val torsoInputBefore = fixture.torsoChartInputOf(S_ELIGIBLE)

        runBlocking { fixture.service.compact(vacuum = false) }

        assertEquals(sessionDumpBefore, fixture.tableChecksum("WorkoutSession"), "incl. sync generations")
        assertEquals(completedSetBefore, fixture.tableChecksum("CompletedSet"))
        assertEquals(repMetricBefore, fixture.tableChecksum("RepMetric"))
        assertEquals(repBiomechanicsBefore, fixture.tableChecksum("RepBiomechanics"))
        assertEquals(peakBefore, fixture.selectPeakPower())
        // I10: the torso chart input is exactly the rows the policy retained.
        val retained = fixture.archivalRowsOf(S_ELIGIBLE).map { Triple(it.position, it.positionB, it.torsoPitchDeg) }
        assertEquals(retained, torsoInputAfter(fixture, S_ELIGIBLE))
        assertTrue(torsoInputBefore.size > retained.size, "the fixture really trimmed lead-in rows")
    }

    // ===== T2.5: idempotency =====

    @Test
    fun `second pass deletes zero rows and reports nothing reclaimable`() {
        val fixture = Fixture()
        fixture.seedAll()
        val first = runBlocking { fixture.service.compact(vacuum = false) }
        assertEquals(6, first.rowsDeleted)

        val second = runBlocking { fixture.service.compact(vacuum = false) }
        assertEquals(0, second.rowsDeleted, "a trimmed set is never trimmed twice")
        assertEquals(0, second.setsCleaned)

        val rescan = runBlocking { fixture.service.analyze(rescanAll = true) }
        assertEquals(0, rescan.rowsReclaimable)
        assertEquals(0, rescan.setsCleanable)
    }

    // ===== T2.6: transactional =====

    @Test
    fun `count mismatch rolls back only the failing set`() {
        val fixture = Fixture(changesOverride = { 999L })
        fixture.seedAll()
        // Arm the mismatch only after the first set has committed.
        var armed = false
        fixture.armChangesOverrideWhen { armed }
        val error = runCatching {
            runBlocking {
                // S_CORRUPT (ts 0) is scanned first as a Preserve; the first Trim set commits,
                // then the injected mismatch rolls back the second Trim set only.
                fixture.service.compact(vacuum = false, progress = { checked, _ -> if (checked == 2) armed = true })
            }
        }.exceptionOrNull()
        assertIs<IllegalStateException>(error)

        assertEquals(listOf(5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L), fixture.sampleIdsOf(S_ELIGIBLE), "first set stayed committed")
        assertEquals(
            fixture.sampleIdsSnapshotBefore[S_SAFETY],
            fixture.sampleIdsOf(S_SAFETY),
            "the failing set rolled back completely",
        )
    }

    @Test
    fun `cancellation between sets leaves a consistent database`() {
        val fixture = Fixture()
        fixture.seedAll()
        runBlocking {
            lateinit var job: Job
            job = launch {
                fixture.service.compact(vacuum = false, progress = { checked, _ -> if (checked == 2) job.cancel() })
            }
            job.join()
        }
        assertEquals(listOf(5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L), fixture.sampleIdsOf(S_ELIGIBLE))
        assertEquals(
            fixture.sampleIdsSnapshotBefore[S_SAFETY],
            fixture.sampleIdsOf(S_SAFETY),
            "the pass stopped between sets without a half-applied set",
        )
    }

    // ===== T2.7: childless checks unchanged =====

    @Test
    fun `childless routine group checks are unchanged by compaction`() {
        val fixture = Fixture()
        fixture.seedAll()
        val before = fixture.childlessCheckResults()
        runBlocking { fixture.service.compact(vacuum = false) }
        assertEquals(before, fixture.childlessCheckResults(), "G7 keeps at least one sample per touched session")
    }

    // ===== T2.8: watermark and restore =====

    @Test
    fun `watermark skips scanned sets and a restore makes rows reclaimable again`() {
        val fixture = Fixture()
        fixture.seedAll()
        runBlocking { fixture.service.compact(vacuum = false) }

        val skipped = runBlocking { fixture.service.analyze() }
        assertEquals(0, skipped.setsScanned, "analyze() skips everything at or below the watermark")

        // Restore the four trimmed rows of the eligible set with their original ids.
        val restored = runBlocking {
            fixture.restorePrefixRows(S_ELIGIBLE)
            fixture.testManager.restoreCommittedPublic()
            fixture.service.analyze()
        }
        assertEquals(4, restored.rowsReclaimable, "the restored untrimmed rows are found again")
    }

    // ===== T2.9: G0 guard =====

    @Test
    fun `active workout aborts the pass and the reservation blocks workout starts`() {
        val fixture = Fixture(guard = { true })
        fixture.seedAll()
        val result = runBlocking { fixture.service.compact(vacuum = true) }

        assertTrue(result.abortedActiveWorkout)
        assertEquals(0, result.rowsDeleted, "nothing is deleted while a workout may be active")
        assertEquals(
            fixture.sampleIdsSnapshotBefore[S_ELIGIBLE],
            fixture.sampleIdsOf(S_ELIGIBLE),
        )
        assertTrue(result.vacuum is VacuumOutcome.Skipped)
        assertEquals(VacuumSkipReason.ACTIVE_WORKOUT, (result.vacuum as VacuumOutcome.Skipped).reason)

        // Tracker semantics: a held reservation blocks workout starts and is released after.
        val tracker = ProfileRecoveryActivityTracker()
        assertTrue(tracker.tryReserveRecovery())
        assertFalse(tracker.setWorkoutActive(true), "workout start is refused while reserved")
        tracker.releaseRecoveryReservation()
        assertTrue(tracker.setWorkoutActive(true))
    }

    private fun torsoInputAfter(fixture: Fixture, sessionId: String): List<Triple<Double?, Double?, Double?>> =
        fixture.torsoChartInputOf(sessionId)

    // ===== Fixture =====

    private class Fixture(
        guard: () -> Boolean = { false },
        private val changesOverride: (() -> Long?)? = null,
    ) {
        private val driver: SqlDriver = FaultInjectingDriver(
            delegate = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY),
            changesOverride = changesOverride,
        ).also(::createTestSchema)
        private val database = PhoenixDatabase(driver)
        private val queries = database.phoenixDatabaseQueries
        private val prefs: PreferencesManager = FakePreferencesManager()
        private val guard = guard
        val testManager = TestBackupManager(database, prefs)
        val service = TelemetryCompactionService(
            database = database,
            preferencesManager = prefs,
            barrier = ProfileMutationBarrier(),
            activityTracker = ProfileRecoveryActivityTracker(),
            workoutSessionGuard = guard,
            databaseMaintenance = DatabaseMaintenance(
                driver = driver,
                availableVolumeBytes = { 64L * 1024L * 1024L * 1024L },
                workoutSessionGuard = guard,
            ),
            backupBytesEstimator = TelemetryBackupBytesEstimator(testManager),
        )

        private val faultDriver get() = driver as FaultInjectingDriver

        fun armChangesOverrideWhen(predicate: () -> Boolean) {
            faultDriver.armWhen(predicate)
        }

        val sampleIdsSnapshotBefore: MutableMap<String, List<Long>> = mutableMapOf()

        fun seedAll() {
            queries.insertUserProfileIgnore("default", "Default", 0L, 1L, 1L)
            queries.insertDefaultProfilePreferences("default", 1L)
            queries.insertAppliedDataRepair(TelemetryCompactionService.EPOCH_ZERO_REPAIR_KEY, 1L)
            database.seedExercise(EX_CABLE, isCustom = true)
            database.seedExercise(EX_BODYWEIGHT, isCustom = true)
            driver.execute(null, "UPDATE Exercise SET isBodyweight = 0 WHERE id = '$EX_CABLE'", 0)
            driver.execute(null, "UPDATE Exercise SET isBodyweight = 1 WHERE id = '$EX_BODYWEIGHT'", 0)

            seedSession(S_ELIGIBLE, ts = BASE_TS + 0L, targetReps = 10, exerciseId = EX_CABLE)
            seedTrace(S_ELIGIBLE, idBase = 1L, positions = TRIMMABLE_POSITIONS, baseTs = BASE_TS + 0L)
            seedRepMetric(S_ELIGIBLE, startTs = BASE_TS + 10_000L)

            seedSession(S_SAFETY, ts = BASE_TS + 1_000_000L, targetReps = 10, exerciseId = EX_CABLE)
            seedTrace(S_SAFETY, idBase = 101L, positions = TRIMMABLE_POSITIONS, baseTs = BASE_TS + 1_000_000L, safetyAt = setOf(0, 2))
            seedRepMetric(S_SAFETY, startTs = BASE_TS + 1_010_000L)

            seedSession(S_CLEAN, ts = BASE_TS + 2_000_000L, targetReps = 10, exerciseId = EX_CABLE)
            seedTrace(S_CLEAN, idBase = 201L, positions = TRIMMABLE_POSITIONS.drop(4), baseTs = BASE_TS + 2_000_000L + 1_000L)
            seedRepMetric(S_CLEAN, startTs = BASE_TS + 2_010_000L)

            seedSession(S_BODYWEIGHT, ts = BASE_TS + 3_000_000L, targetReps = 10, exerciseId = EX_BODYWEIGHT)
            seedTrace(S_BODYWEIGHT, idBase = 301L, positions = TRIMMABLE_POSITIONS, baseTs = BASE_TS + 3_000_000L)
            seedRepMetric(S_BODYWEIGHT, startTs = BASE_TS + 3_010_000L)

            seedSession(S_TIMED, ts = BASE_TS + 4_000_000L, targetReps = 0, exerciseId = EX_CABLE)
            seedTrace(S_TIMED, idBase = 401L, positions = TRIMMABLE_POSITIONS, baseTs = BASE_TS + 4_000_000L)

            seedSession(S_UNKNOWN_EX, ts = BASE_TS + 5_000_000L, targetReps = 10, exerciseId = "missing-exercise")
            seedTrace(S_UNKNOWN_EX, idBase = 501L, positions = TRIMMABLE_POSITIONS, baseTs = BASE_TS + 5_000_000L)
            seedRepMetric(S_UNKNOWN_EX, startTs = BASE_TS + 5_010_000L)

            seedSession(S_LEGACY, ts = BASE_TS + 6_000_000L, targetReps = 10, exerciseId = EX_CABLE)
            seedTrace(S_LEGACY, idBase = 601L, positions = TRIMMABLE_POSITIONS, baseTs = BASE_TS + 6_000_000L, unitVersion = 1L)
            seedRepMetric(S_LEGACY, startTs = BASE_TS + 6_010_000L)

            seedSession(S_CORRUPT, ts = 0L, targetReps = 10, exerciseId = EX_CABLE)
            seedTrace(S_CORRUPT, idBase = 701L, positions = TRIMMABLE_POSITIONS, baseTs = 0L)
            seedRepMetric(S_CORRUPT, startTs = 10_000L)

            seedSession(S_NOREP, ts = BASE_TS + 7_000_000L, targetReps = 10, exerciseId = EX_CABLE)
            seedTrace(S_NOREP, idBase = 801L, positions = TRIMMABLE_POSITIONS, baseTs = BASE_TS + 7_000_000L)

            seedSession(S_EARLYREP, ts = BASE_TS + 8_000_000L, targetReps = 10, exerciseId = EX_CABLE)
            seedTrace(S_EARLYREP, idBase = 901L, positions = TRIMMABLE_POSITIONS, baseTs = BASE_TS + 8_000_000L)
            seedRepMetric(S_EARLYREP, startTs = BASE_TS + 8_000_000L + 100L)

            seedSession(S_AMBIGUOUS, ts = BASE_TS + 9_000_000L, targetReps = 10, exerciseId = EX_CABLE)
            seedTrace(S_AMBIGUOUS, idBase = 1_001L, positions = JITTER_POSITIONS, baseTs = BASE_TS + 9_000_000L)
            seedRepMetric(S_AMBIGUOUS, startTs = BASE_TS + 9_010_000L)

            seedSession(S_NULLPOS, ts = BASE_TS + 10_000_000L, targetReps = 10, exerciseId = EX_CABLE)
            seedTrace(S_NULLPOS, idBase = 1_101L, positions = TRIMMABLE_POSITIONS, baseTs = BASE_TS + 10_000_000L, nullPositionAt = 6)
            seedRepMetric(S_NULLPOS, startTs = BASE_TS + 10_010_000L)

            seedSession(S_OUTOFORDER, ts = BASE_TS + 11_000_000L, targetReps = 10, exerciseId = EX_CABLE)
            seedTrace(S_OUTOFORDER, idBase = 1_201L, positions = TRIMMABLE_POSITIONS, baseTs = BASE_TS + 11_000_000L, decreasingAt = 5)
            seedRepMetric(S_OUTOFORDER, startTs = BASE_TS + 11_010_000L)

            seedSession(S_NOEXERCISE, ts = BASE_TS + 12_000_000L, targetReps = 10, exerciseId = null)
            seedTrace(S_NOEXERCISE, idBase = 1_301L, positions = TRIMMABLE_POSITIONS, baseTs = BASE_TS + 12_000_000L)
            seedRepMetric(S_NOEXERCISE, startTs = BASE_TS + 12_010_000L)

            // Childless routine-group mate (T2.7) plus a row with only a CompletedSet child.
            seedSession(S_CHILDLESS, ts = BASE_TS + 13_000_000L, targetReps = 8, exerciseId = EX_CABLE, routineSessionId = "group-1")
            seedSession(S_WITHCOMPLETED, ts = BASE_TS + 14_000_000L, targetReps = 8, exerciseId = EX_CABLE, routineSessionId = "group-1")
            seedCompletedSet(S_WITHCOMPLETED)

            // Summary fixtures (T2.4)
            seedRepBiomechanics(S_ELIGIBLE)

            // Pre-compaction snapshot for kept-set assertions.
            sampleIdsSnapshotBefore.clear()
            ALL_SESSIONS.forEach { sampleIdsSnapshotBefore[it] = sampleIdsOf(it) }
        }

        private fun seedSession(
            id: String,
            ts: Long,
            targetReps: Long,
            exerciseId: String?,
            routineSessionId: String? = null,
        ) {
            val ex = exerciseId?.let { "'$it'" } ?: "NULL"
            val routine = routineSessionId?.let { "'$it'" } ?: "NULL"
            driver.execute(
                null,
                "INSERT INTO WorkoutSession (id, timestamp, mode, targetReps, weightPerCableKg, exerciseId, routineSessionId, profile_id) " +
                    "VALUES ('$id', $ts, 'OldSchool', $targetReps, 20.0, $ex, $routine, 'default')",
                0,
            )
        }

        private fun seedTrace(
            sessionId: String,
            idBase: Long,
            positions: List<Float>,
            baseTs: Long,
            safetyAt: Set<Int> = emptySet(),
            unitVersion: Long = 2L,
            nullPositionAt: Int? = null,
            decreasingAt: Int? = null,
        ) {
            positions.forEachIndexed { index, position ->
                val id = idBase + index
                val ts = baseTs + index * SAMPLE_INTERVAL_MS - if (decreasingAt == index) SAMPLE_INTERVAL_MS + 1 else 0
                val pos = if (nullPositionAt == index) "NULL" else position.toString()
                val status = if (index in safetyAt) SampleStatus.ROM_OUTSIDE_HIGH else 0
                driver.execute(
                    null,
                    "INSERT INTO MetricSample (id, sessionId, timestamp, position, positionB, velocity, velocityB, load, loadB, power, status, metric_unit_version) " +
                        "VALUES ($id, '$sessionId', $ts, $pos, 200.0, 0.0, 0.0, 20.0, 18.0, 0.0, $status, $unitVersion)",
                    0,
                )
            }
        }

        private fun seedRepMetric(sessionId: String, startTs: Long) {
            driver.execute(
                null,
                "INSERT INTO RepMetric (sessionId, repNumber, startTimestamp, endTimestamp, durationMs, concentricDurationMs, " +
                    "concentricPositions, concentricLoadsA, concentricLoadsB, concentricVelocities, concentricTimestamps, " +
                    "eccentricDurationMs, eccentricPositions, eccentricLoadsA, eccentricLoadsB, eccentricVelocities, eccentricTimestamps, " +
                    "peakForceA, peakForceB, avgForceConcentricA, avgForceConcentricB, avgForceEccentricA, avgForceEccentricB, " +
                    "peakVelocity, avgVelocityConcentric, avgVelocityEccentric, rangeOfMotionMm, peakPowerWatts, avgPowerWatts) " +
                    "VALUES ('$sessionId', 1, $startTs, ${startTs + 2_000L}, 2000, 1000, '[]', '[]', '[]', '[]', '[]', " +
                    "1000, '[]', '[]', '[]', '[]', '[]', 100.0, 100.0, 90.0, 90.0, 80.0, 80.0, 1.0, 0.8, 0.6, 400.0, 200.0, 150.0)",
                0,
            )
        }

        private fun seedCompletedSet(sessionId: String) {
            driver.execute(
                null,
                "INSERT INTO CompletedSet (id, session_id, set_number, actual_reps, actual_weight_kg, completed_at) " +
                    "VALUES ('cs-$sessionId', '$sessionId', 1, 8, 20.0, ${BASE_TS + 20_000L})",
                0,
            )
        }

        private fun seedRepBiomechanics(sessionId: String) {
            driver.execute(
                null,
                "INSERT INTO RepBiomechanics (sessionId, repNumber, mcvMmS, peakVelocityMmS, velocityZone, normalizedForceN, " +
                    "normalizedPositionPct, strengthProfile, asymmetryPercent, dominantSide, avgLoadA, avgLoadB, timestamp) " +
                    "VALUES ('$sessionId', 1, 500.0, 700.0, 'MODERATE', '[]', '[]', 'BALANCED', 2.0, 'LEFT', 20.0, 18.0, ${BASE_TS + 21_000L})",
                0,
            )
        }

        fun restorePrefixRows(sessionId: String) {
            // Re-inserts the four discarded idle rows of the eligible trace at their original ids.
            for (index in 0 until 4) {
                val id = 1L + index
                queries.insertMetricRestoreIfAbsent(
                    id = id,
                    sessionId = sessionId,
                    timestamp = BASE_TS + index * SAMPLE_INTERVAL_MS,
                    position = TRIMMABLE_POSITIONS[index].toDouble(),
                    positionB = 200.0,
                    velocity = 0.0,
                    velocityB = 0.0,
                    load = 20.0,
                    loadB = 18.0,
                    power = 0.0,
                    status = 0L,
                    heartRateBpm = null,
                    rrIntervalMs = null,
                    torsoPitchDeg = null,
                    torsoRollDeg = null,
                    dynamicAccMg = null,
                    chestAccXMg = null,
                    chestAccYMg = null,
                    chestAccZMg = null,
                )
            }
        }

        fun sampleIdsOf(sessionId: String): List<Long> = queryLongs(
            "SELECT id FROM MetricSample WHERE sessionId = '$sessionId' ORDER BY id",
        )

        fun archivalRowsOf(sessionId: String): List<com.devil.phoenixproject.database.MetricSample> =
            queries.selectArchivalMetricsBySession(sessionId).executeAsList()

        fun sampleTimestampsOf(sessionId: String): List<Long> = queryLongs(
            "SELECT timestamp FROM MetricSample WHERE sessionId = '$sessionId' ORDER BY id",
        )

        fun torsoChartInputOf(sessionId: String): List<Triple<Double?, Double?, Double?>> =
            queries.selectMetricsBySession(sessionId).executeAsList().map {
                Triple(it.position, it.positionB, it.torsoPitchDeg)
            }

        fun selectPeakPower(): Double? {
            val rows = queries.selectPeakPowerForProfile("default").executeAsList()
            return rows.firstOrNull()?.peakPower
        }

        fun childlessCheckResults(): Pair<List<String>, List<Pair<String, String>>> {
            val blocked = queries.selectBlockedRoutineGroupSiblings("default", listOf("group-1")).executeAsList()
            val childless = queries.selectRoutineGroupsWithChildlessRows("default", listOf("group-1")).executeAsList()
            return childless to blocked.map { it.id to (it.routineSessionId ?: "") }
        }

        fun tableChecksum(table: String): String {
            val columns = driver.executeQuery(null, "PRAGMA table_info($table)", { cursor ->
                QueryResult.Value(buildList {
                    while (cursor.next().value) add(cursor.getString(1) ?: "")
                })
            }, 0).value
            assertTrue(columns.isNotEmpty(), "unknown table $table")
            val rowExpr = columns.joinToString(" || '|' || ") { "quote(COALESCE(CAST($it AS TEXT), '~'))" }
            return driver.executeQuery(
                null,
                "SELECT COALESCE(group_concat(rowline, char(10)), '') FROM (SELECT $rowExpr AS rowline FROM $table ORDER BY rowline)",
                { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) ?: "" else "") },
                0,
            ).value
        }

        fun pragmaLong(pragma: String): Long = driver.executeQuery(null, pragma, { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else 0L)
        }, 0).value ?: 0L

        private fun queryLongs(sql: String): List<Long> = driver.executeQuery(null, sql, { cursor ->
            QueryResult.Value(buildList {
                while (cursor.next().value) add(cursor.getLong(0) ?: 0L)
            })
        }, 0).value
    }

    /**
     * Wraps a JDBC driver and can override the reported `SELECT changes()` count once armed —
     * the injected count mismatch proves the transaction rolls back only that set (T2.6).
     */
    private class FaultInjectingDriver(
        private val delegate: SqlDriver,
        private val changesOverride: (() -> Long?)?,
    ) : SqlDriver by delegate {
        private var armedPredicate: (() -> Boolean)? = null

        fun armWhen(predicate: () -> Boolean) {
            armedPredicate = predicate
        }

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            if (sql == "SELECT changes()" && changesOverride != null && armedPredicate?.invoke() == true) {
                @Suppress("UNCHECKED_CAST")
                return QueryResult.Value(changesOverride.invoke() as R)
            }
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }
    }

    /** Minimal BaseDataBackupManager for T2.8's `onRestoreCommitted()` and the estimator. */
    private class TestBackupManager(
        database: PhoenixDatabase,
        preferencesManager: PreferencesManager,
    ) : com.devil.phoenixproject.util.BaseDataBackupManager(
        database,
        com.devil.phoenixproject.data.repository.SqlDelightProfilePreferencesRepository(database),
        com.devil.phoenixproject.data.repository.SqlDelightUserProfileRepository(
            database = database,
            profilePreferencesRepository = com.devil.phoenixproject.data.repository.SqlDelightProfilePreferencesRepository(database),
            profileLocalSafetyStore = com.devil.phoenixproject.data.preferences.SettingsProfileLocalSafetyStore(
                com.russhwolf.settings.MapSettings(),
            ),
            gamificationRepository = com.devil.phoenixproject.data.repository.SqlDelightGamificationRepository(database),
        ),
        null,
        preferencesManager,
        null,
    ) {
        suspend fun restoreCommittedPublic() = onRestoreCommitted()

        override fun createBackupWriter(): com.devil.phoenixproject.util.BackupJsonWriter =
            com.devil.phoenixproject.util.BackupJsonWriter(
                kotlin.io.path.createTempDirectory("telemetry-compaction-test").toFile()
                    .resolve("export.json").absolutePath,
            )

        override suspend fun finalizeExport(tempFilePath: String): Result<String> = Result.success(tempFilePath)
        override suspend fun importFromFile(filePath: String): Result<com.devil.phoenixproject.util.ImportResult> =
            error("unused")

        override suspend fun shareBackup() = Unit
        override fun getSessionBackupDirectory(): String = ""
        override fun listBackupFileSizes(): List<Long> = emptyList()
        override fun openBackupFolder() = Unit
        override fun pruneOldBackups(keepCount: Int) = Unit
    }

    private companion object {
        const val BASE_TS = 1_700_000_000_000L
        const val SAMPLE_INTERVAL_MS = 250L

        const val EX_CABLE = "ex-cable"
        const val EX_BODYWEIGHT = "ex-bodyweight"

        const val S_ELIGIBLE = "s-eligible"
        const val S_SAFETY = "s-safety"
        const val S_CLEAN = "s-clean"
        const val S_BODYWEIGHT = "s-bodyweight"
        const val S_TIMED = "s-timed"
        const val S_UNKNOWN_EX = "s-unknown-ex"
        const val S_LEGACY = "s-legacy"
        const val S_CORRUPT = "s-corrupt"
        const val S_NOREP = "s-norep"
        const val S_EARLYREP = "s-earlyrep"
        const val S_AMBIGUOUS = "s-ambiguous"
        const val S_NULLPOS = "s-nullpos"
        const val S_OUTOFORDER = "s-outoforder"
        const val S_NOEXERCISE = "s-noexercise"
        const val S_CHILDLESS = "s-childless"
        const val S_WITHCOMPLETED = "s-withcompleted"

        val ALL_SESSIONS = listOf(
            S_ELIGIBLE, S_SAFETY, S_CLEAN, S_BODYWEIGHT, S_TIMED, S_UNKNOWN_EX, S_LEGACY, S_CORRUPT,
            S_NOREP, S_EARLYREP, S_AMBIGUOUS, S_NULLPOS, S_OUTOFORDER, S_NOEXERCISE,
        )

        val TRIMMABLE_POSITIONS = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1.5f, 3f, 5.5f, 8f, 11f).map { 100f + it }
        val JITTER_POSITIONS = listOf(100f, 100.8f, 99.6f, 101f, 99f, 101.4f, 99.3f, 100.7f, 106f, 100.4f, 104.9f, 100.2f)
    }
}
