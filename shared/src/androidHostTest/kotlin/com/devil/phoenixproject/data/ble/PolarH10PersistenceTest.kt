package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.PolarHrMeasurement
import com.devil.phoenixproject.domain.model.PolarPmdMeasurement
import com.devil.phoenixproject.testutil.createTestDatabase
import com.devil.phoenixproject.testutil.seedExercise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
            database = database,
            scope = testScope,
            timeSource = { simulatedTime },
        )
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
            database = database,
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
}
