package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.data.repository.SqlDelightPolarTelemetryRepository
import com.devil.phoenixproject.data.repository.PolarTelemetryRepository
import com.devil.phoenixproject.domain.model.PolarBeatSample
import com.devil.phoenixproject.domain.model.PolarEcgSegment
import com.devil.phoenixproject.domain.model.PolarRecovery
import com.devil.phoenixproject.domain.model.PolarHrMeasurement
import com.devil.phoenixproject.domain.model.PolarPmdMeasurement
import com.devil.phoenixproject.testutil.createTestDatabase
import com.devil.phoenixproject.testutil.seedExercise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end telemetry persistence test verifying that raw Polar beat and ECG records
 * survive foreign-key constraints (PRAGMA foreign_keys = ON) via in-memory buffering
 * prior to session row creation and direct persistence during post-workout cooldown.
 */
class PolarH10PersistenceTest {

    private lateinit var database: PhoenixDatabase
    private lateinit var testScope: CoroutineScope
    private var simulatedTime: Long = 1_000_000L

    @BeforeTest
    fun setUp() {
        database = createTestDatabase()
        testScope = CoroutineScope(Dispatchers.Unconfined + Job())
        database.seedExercise("bench")
    }

    @AfterTest
    fun tearDown() {
        testScope.cancel()
    }

    private fun insertWorkoutSession(id: String) {
        database.phoenixDatabaseQueries.insertSession(
            id = id,
            timestamp = 0L,
            mode = "OldSchool",
            targetReps = 10L,
            weightPerCableKg = 40.0,
            progressionKg = 0.0,
            duration = 0L,
            totalReps = 10L,
            warmupReps = 0L,
            workingReps = 10L,
            isJustLift = 0L,
            stopAtTop = 0L,
            eccentricLoad = 100L,
            echoLevel = 1L,
            exerciseId = "bench",
            exerciseName = "Bench Press",
            routineSessionId = null,
            routineName = null,
            safetyFlags = 0L,
            deloadWarningCount = 0L,
            romViolationCount = 0L,
            spotterActivations = 0L,
            peakForceConcentricA = null,
            peakForceConcentricB = null,
            peakForceEccentricA = null,
            peakForceEccentricB = null,
            avgForceConcentricA = null,
            avgForceConcentricB = null,
            avgForceEccentricA = null,
            avgForceEccentricB = null,
            heaviestLiftKg = null,
            totalVolumeKg = null,
            cableCount = null,
            estimatedCalories = null,
            warmupAvgWeightKg = null,
            workingAvgWeightKg = null,
            burnoutAvgWeightKg = null,
            peakWeightKg = null,
            rpe = null,
            routineId = null,
            avgMcvMmS = null,
            avgAsymmetryPercent = null,
            totalVelocityLossPercent = null,
            dominantSide = null,
            strengthProfile = null,
            formScore = null,
            profile_id = "default",
            display_multiplier = null,
            externalAddedLoadKg = 0.0,
            counterweightKg = 0.0,
            rackItemsJson = "[]",
        )
    }

    @Test
    fun `telemetry is buffered before session exists and flushed after session creation with foreign keys ON`() {
        val manager = PolarH10BleManager(
            telemetryRepository = SqlDelightPolarTelemetryRepository(database, { simulatedTime }, Dispatchers.Unconfined),
            scope = testScope,
            timeSource = { simulatedTime },
            persistenceDispatcher = Dispatchers.Unconfined,
        )
        manager.setRawEcgStorageEnabled(true)
        val sessionId = "session-fk-test"

        // 1. Begin recording before WorkoutSession row exists
        manager.startSessionRecording(sessionId = sessionId, phase = "WORKING_SET")

        // 2. Feed HR/RR beats and ECG segment while session does not exist in DB
        val hrMeasurement = PolarHrMeasurement(
            hr = 145,
            contactDetected = true,
            rrIntervalsMs = listOf(800.0, 820.0),
        )
        manager.processRrIntervals(hrMeasurement)

        val ecgMeasurement = PolarPmdMeasurement.Ecg(
            sensorTimestampNs = 123456789L,
            samplesMicrovolts = listOf(150, 250, 350, 450),
        )
        manager.processPmdMeasurement(ecgMeasurement)

        // 3. Verify records are held in memory buffer and not yet in SQLite
        assertEquals(2, manager.getBufferedBeatSamplesForTesting().size)
        assertEquals(1, manager.getBufferedEcgSegmentsForTesting().size)
        val beatRowsBeforeCommit = database.phoenixDatabaseQueries.selectPolarBeatSamplesBySession(sessionId).executeAsList()
        val ecgRowsBeforeCommit = database.phoenixDatabaseQueries.selectPolarEcgSegmentsBySession(sessionId).executeAsList()
        assertTrue(beatRowsBeforeCommit.isEmpty())
        assertTrue(ecgRowsBeforeCommit.isEmpty())

        // 4. Now commit the parent WorkoutSession row
        insertWorkoutSession(sessionId)

        // 5. Flush buffered telemetry
        manager.flushSessionTelemetry(sessionId)

        // 6. Verify in-memory buffers are cleared and rows are durable in SQLite with foreign keys ON
        assertEquals(0, manager.getBufferedBeatSamplesForTesting().size)
        assertEquals(0, manager.getBufferedEcgSegmentsForTesting().size)

        val beatRowsAfterCommit = database.phoenixDatabaseQueries.selectPolarBeatSamplesBySession(sessionId).executeAsList()
        val ecgRowsAfterCommit = database.phoenixDatabaseQueries.selectPolarEcgSegmentsBySession(sessionId).executeAsList()

        assertEquals(2, beatRowsAfterCommit.size)
        assertEquals(1, ecgRowsAfterCommit.size)

        val firstBeat = beatRowsAfterCommit[0]
        assertEquals(sessionId, firstBeat.sessionId)
        assertEquals(145L, firstBeat.hrBpm)
        assertEquals(800.0, firstBeat.rrMs)
        assertEquals("WORKING_SET", firstBeat.phase)

        val ecgSeg = ecgRowsAfterCommit[0]
        assertEquals(sessionId, ecgSeg.sessionId)
        assertEquals(123456789L, ecgSeg.sensorTimestampNs)
        assertEquals("150,250,350,450", ecgSeg.samplesUvCsv)

        // 7. Post-workout cooldown: subsequent beats are buffered in memory to prevent autocommit storm and flushed when cooldown ends
        manager.triggerCooldown(sessionId)
        val cooldownBeat = PolarHrMeasurement(
            hr = 130,
            contactDetected = true,
            rrIntervalsMs = listOf(860.0),
        )
        manager.processRrIntervals(cooldownBeat)

        assertEquals(1, manager.getBufferedBeatSamplesForTesting().size)
        manager.stopCooldown()
        assertEquals(0, manager.getBufferedBeatSamplesForTesting().size)

        val beatRowsDuringCooldown = database.phoenixDatabaseQueries.selectPolarBeatSamplesBySession(sessionId).executeAsList()
        assertEquals(3, beatRowsDuringCooldown.size)
        assertEquals(130L, beatRowsDuringCooldown[2].hrBpm)
    }

    @Test
    fun `abandoned set discards buffered telemetry cleanly`() {
        val manager = PolarH10BleManager(
            telemetryRepository = SqlDelightPolarTelemetryRepository(database, { simulatedTime }, Dispatchers.Unconfined),
            scope = testScope,
            timeSource = { simulatedTime },
        )
        val sessionId = "session-abandoned"

        manager.startSessionRecording(sessionId = sessionId, phase = "WORKING_SET")
        manager.processRrIntervals(
            PolarHrMeasurement(
                hr = 140,
                contactDetected = true,
                rrIntervalsMs = listOf(800.0),
            )
        )

        assertEquals(1, manager.getBufferedBeatSamplesForTesting().size)

        // Abandon set
        manager.discardActiveRecording()

        assertEquals(0, manager.getBufferedBeatSamplesForTesting().size)
        assertEquals(null, manager.currentRecordingSessionId)

        val beatRows = database.phoenixDatabaseQueries.selectPolarBeatSamplesBySession(sessionId).executeAsList()
        assertTrue(beatRows.isEmpty())
    }

    @Test
    fun `queued flush detaches old session before another recording and recovery updates only HRR`() = runTest {
        val manager = PolarH10BleManager(
            telemetryRepository = SqlDelightPolarTelemetryRepository(database, { simulatedTime }, Dispatchers.Unconfined),
            scope = backgroundScope,
            timeSource = { simulatedTime },
            persistenceDispatcher = StandardTestDispatcher(testScheduler),
        )
        insertWorkoutSession("first")
        insertWorkoutSession("second")
        database.phoenixDatabaseQueries.updateSessionPolarSummary(
            id = "first", avgHeartRateBpm = 150, maxHeartRateBpm = 170, minHeartRateBpm = 130,
            avgRrIntervalMs = 800.0, rmssdMs = 25.0, hrr60Bpm = null, hrr300Bpm = null,
            maxTorsoPitchDeg = 12.0, maxTorsoRollDeg = 8.0,
        )
        manager.startSessionRecording("first")
        manager.processRrIntervals(PolarHrMeasurement(150, true, rrIntervalsMs = listOf(800.0)))
        val firstFlush = manager.flushSessionTelemetry("first")
        assertTrue(database.phoenixDatabaseQueries.selectPolarBeatSamplesBySession("first").executeAsList().isEmpty())
        manager.triggerCooldown("first")
        simulatedTime += 65_000L
        manager.processRrIntervals(PolarHrMeasurement(120, true, rrIntervalsMs = listOf(1000.0)))
        manager.startSessionRecording("second")
        manager.processRrIntervals(PolarHrMeasurement(180, true, rrIntervalsMs = listOf(700.0)))
        val secondFlush = manager.flushSessionTelemetry("second")
        runCurrent()
        firstFlush?.join()
        secondFlush?.join()
        val first = database.phoenixDatabaseQueries.selectSessionById("first").executeAsOne()
        assertEquals(150L, first.avgHeartRateBpm)
        assertEquals(170L, first.maxHeartRateBpm)
        assertEquals(25.0, first.rmssdMs)
        assertEquals(12.0, first.maxTorsoPitchDeg)
        assertEquals(30L, first.hrr60Bpm)
        assertEquals(null, first.hrr300Bpm)
        assertEquals(listOf(150L, 120L), database.phoenixDatabaseQueries.selectPolarBeatSamplesBySession("first").executeAsList().map { it.hrBpm })
        assertEquals(listOf(180L), database.phoenixDatabaseQueries.selectPolarBeatSamplesBySession("second").executeAsList().map { it.hrBpm })
    }

    @Test
    fun `failed telemetry transaction remains retryable after parent row is committed`() = runTest {
        insertWorkoutSession("retry")
        val delegate = SqlDelightPolarTelemetryRepository(database, { simulatedTime }, Dispatchers.Unconfined)
        var failNextSave = true
        val repository = object : PolarTelemetryRepository by delegate {
            override suspend fun saveTelemetry(
                sessionId: String,
                beats: List<PolarBeatSample>,
                ecgs: List<PolarEcgSegment>,
                recovery: PolarRecovery?,
            ) {
                if (failNextSave) {
                    failNextSave = false
                    error("Transient telemetry storage failure")
                }
                delegate.saveTelemetry(sessionId, beats, ecgs, recovery)
            }
        }
        val manager = PolarH10BleManager(
            telemetryRepository = repository,
            scope = backgroundScope,
            timeSource = { simulatedTime },
            persistenceDispatcher = StandardTestDispatcher(testScheduler),
        )
        manager.startSessionRecording("retry")
        manager.processRrIntervals(PolarHrMeasurement(145, true, rrIntervalsMs = listOf(800.0)))
        manager.flushSessionTelemetry("retry")
        runCurrent()
        assertTrue(manager.telemetryPersistenceError.value != null)
        manager.flushSessionTelemetry("retry")
        runCurrent()
        assertEquals(null, manager.telemetryPersistenceError.value)
        assertEquals(1, database.phoenixDatabaseQueries.selectPolarBeatSamplesBySession("retry").executeAsList().size)
    }

    @Test
    fun `late manual stop commit remaps only its source buffer after next recording starts`() = runTest {
        val manager = PolarH10BleManager(
            telemetryRepository = SqlDelightPolarTelemetryRepository(database, { simulatedTime }, Dispatchers.Unconfined),
            scope = backgroundScope, timeSource = { simulatedTime },
            persistenceDispatcher = StandardTestDispatcher(testScheduler),
        )
        manager.startSessionRecording("source")
        manager.processRrIntervals(PolarHrMeasurement(145, true, rrIntervalsMs = listOf(800.0)))
        assertEquals(145, manager.freezeSessionRecording("source")?.avgHeartRateBpm)
        manager.processRrIntervals(PolarHrMeasurement(160, true, rrIntervalsMs = listOf(750.0)))
        manager.startSessionRecording("next")
        manager.processRrIntervals(PolarHrMeasurement(180, true, rrIntervalsMs = listOf(650.0)))
        assertEquals(145, manager.getSessionSummary("source")?.avgHeartRateBpm)
        insertWorkoutSession("committed")
        manager.flushSessionTelemetry("committed", recordedSourceId = "source")
        runCurrent()
        assertEquals("next", manager.currentRecordingSessionId)
        assertEquals("next", manager.lastRecordedSessionId.value)
        assertEquals(1, manager.getBufferedBeatSamplesForTesting().size)
        val stored = database.phoenixDatabaseQueries.selectPolarBeatSamplesBySession("committed").executeAsList()
        assertEquals(listOf(145L), stored.map { it.hrBpm })
    }

    @Test
    fun `precommit cooldown waits for matching parent commit and remaps the source`() = runTest {
        val manager = PolarH10BleManager(
            telemetryRepository = SqlDelightPolarTelemetryRepository(database, { simulatedTime }, Dispatchers.Unconfined),
            scope = backgroundScope, timeSource = { simulatedTime },
            persistenceDispatcher = StandardTestDispatcher(testScheduler),
        )
        manager.startSessionRecording("uncommitted")
        manager.processRrIntervals(PolarHrMeasurement(150, true, rrIntervalsMs = listOf(800.0)))
        manager.freezeSessionRecording("uncommitted")
        manager.triggerCooldown("uncommitted")
        runCurrent()
        assertTrue(!manager.isCooldownActive.value)
        assertEquals(1, manager.getBufferedBeatSamplesForTesting().size)
        assertEquals(null, manager.telemetryPersistenceError.value)
        insertWorkoutSession("saved")
        manager.flushSessionTelemetry("saved", recordedSourceId = "uncommitted")
        runCurrent()
        assertTrue(manager.isCooldownActive.value)
        assertEquals("saved", manager.activeCooldownSessionId.value)
        assertEquals(1, database.phoenixDatabaseQueries.selectPolarBeatSamplesBySession("saved").executeAsList().size)
        manager.cancelCooldown()
        runCurrent()
    }

    @Test
    fun `next recording supersedes cooldown request before old parent commit`() = runTest {
        val manager = PolarH10BleManager(
            telemetryRepository = SqlDelightPolarTelemetryRepository(database, { simulatedTime }, Dispatchers.Unconfined),
            scope = backgroundScope, timeSource = { simulatedTime },
            persistenceDispatcher = StandardTestDispatcher(testScheduler),
        )
        manager.startSessionRecording("previous")
        manager.processRrIntervals(PolarHrMeasurement(145, true, rrIntervalsMs = listOf(800.0)))
        manager.triggerCooldown("previous")
        manager.startSessionRecording("current")
        insertWorkoutSession("previous")
        manager.flushSessionTelemetry("previous")
        runCurrent()
        assertTrue(!manager.isCooldownActive.value)
        assertEquals("current", manager.currentRecordingSessionId)
        assertEquals("current", manager.lastRecordedSessionId.value)
    }
}
