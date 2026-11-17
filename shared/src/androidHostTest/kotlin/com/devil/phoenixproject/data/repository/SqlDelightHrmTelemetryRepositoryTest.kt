package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.HrmBeatSample
import com.devil.phoenixproject.domain.model.PolarEcgSegment
import com.devil.phoenixproject.domain.model.PolarRecovery
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.createTestDriver
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SqlDelightHrmTelemetryRepositoryTest {
    @Test
    fun `a batch for a deleted session is discarded and the next valid batch saves`() = runTest {
        val driver = createTestDriver()
        try {
            val database = PhoenixDatabase(driver)
            val workouts = SqlDelightWorkoutRepository(database, FakeExerciseRepository())
            val repository = SqlDelightHrmTelemetryRepository(
                database, { NOW_MS }, StandardTestDispatcher(testScheduler),
            )
            workouts.saveSession(session("deleted"))
            workouts.saveSession(session("valid"))
            database.phoenixDatabaseQueries.hardDeleteWorkoutComponent("deleted")

            repository.saveTelemetry("deleted", listOf(beat("deleted")), listOf(ecg("deleted")), PolarRecovery(20, 30))
            repository.saveTelemetry("valid", listOf(beat("valid")), listOf(ecg("valid")), PolarRecovery(15, 25))

            val queries = database.phoenixDatabaseQueries
            assertTrue(queries.selectPolarBeatSamplesBySession("deleted").executeAsList().isEmpty())
            assertTrue(queries.selectPolarEcgSegmentsBySession("deleted").executeAsList().isEmpty())
            assertEquals(145L, queries.selectPolarBeatSamplesBySession("valid").executeAsOne().hrBpm)
            assertEquals("100,105", queries.selectPolarEcgSegmentsBySession("valid").executeAsOne().samplesUvCsv)
            assertEquals(15L, queries.selectSessionById("valid").executeAsOne().hrr60Bpm)
            assertEquals(25L, queries.selectSessionById("valid").executeAsOne().hrr300Bpm)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `save and standalone prune enforce the thirty day cutoff`() = runTest {
        val driver = createTestDriver()
        try {
            val database = PhoenixDatabase(driver)
            SqlDelightWorkoutRepository(database, FakeExerciseRepository()).saveSession(session("retention"))
            var now = NOW_MS
            val repository = SqlDelightHrmTelemetryRepository(
                database, { now }, StandardTestDispatcher(testScheduler),
            )
            val cutoff = now - SqlDelightHrmTelemetryRepository.RAW_ECG_RETENTION_DAYS * 86_400_000L

            repository.saveTelemetry(
                "retention",
                emptyList(),
                listOf(
                    ecg("retention").copy(wallClockTimestampMs = cutoff - 1, sensorTimestampNs = 1),
                    ecg("retention").copy(wallClockTimestampMs = cutoff, sensorTimestampNs = 2),
                    ecg("retention").copy(wallClockTimestampMs = now, sensorTimestampNs = 3),
                ),
            )

            assertEquals(
                listOf(2L, 3L),
                database.phoenixDatabaseQueries.selectPolarEcgSegmentsBySession("retention").executeAsList()
                    .map { it.sensorTimestampNs },
            )
            now += 31 * 86_400_000L
            repository.pruneRawEcg()
            assertTrue(database.phoenixDatabaseQueries.selectPolarEcgSegmentsBySession("retention").executeAsList().isEmpty())
        } finally {
            driver.close()
        }
    }

    @Test
    fun `save retains only the newest thirty six thousand ECG segments`() = runTest {
        val driver = createTestDriver()
        try {
            val database = PhoenixDatabase(driver)
            SqlDelightWorkoutRepository(database, FakeExerciseRepository()).saveSession(session("cap"))
            val repository = SqlDelightHrmTelemetryRepository(
                database, { NOW_MS }, StandardTestDispatcher(testScheduler),
            )
            val count = SqlDelightHrmTelemetryRepository.MAX_STORED_ECG_SEGMENTS.toInt() + 2
            val segments = List(count) { index ->
                ecg("cap").copy(wallClockTimestampMs = NOW_MS - count + index, sensorTimestampNs = index.toLong())
            }

            repository.saveTelemetry("cap", emptyList(), segments)

            val retained = database.phoenixDatabaseQueries.selectPolarEcgSegmentsBySession("cap").executeAsList()
            assertEquals(36_000, retained.size)
            assertEquals(2L, retained.first().sensorTimestampNs)
            assertEquals((count - 1).toLong(), retained.last().sensorTimestampNs)
        } finally {
            driver.close()
        }
    }

    // T29: beat/HRR persistence path unchanged; ECG/ACC columns stay null for a
    // non-PMD (e.g. Garmin broadcast) connection (repository-level, SD9).
    @Test
    fun `T29 non-PMD beat and HRR persistence stores null ECG and ACC columns`() = runTest {
        val driver = createTestDriver()
        try {
            val database = PhoenixDatabase(driver)
            SqlDelightWorkoutRepository(database, FakeExerciseRepository()).saveSession(session("garmin-style"))
            val repository = SqlDelightHrmTelemetryRepository(
                database, { NOW_MS }, StandardTestDispatcher(testScheduler),
            )

            repository.saveTelemetry(
                "garmin-style",
                listOf(beat("garmin-style").copy(rmssdMs = 42.0)),
                emptyList(), // non-PMD connection: no ECG segments ever produced
                PolarRecovery(15, 25),
            )

            val queries = database.phoenixDatabaseQueries
            val row = queries.selectPolarBeatSamplesBySession("garmin-style").executeAsOne()
            // Beat path unchanged.
            assertEquals(145L, row.hrBpm)
            assertEquals(800.0, row.rrMs)
            assertEquals(42.0, row.rmssdMs)
            // ECG/ACC columns are null for a non-PMD connection.
            assertNull(row.torsoPitchDeg)
            assertNull(row.torsoRollDeg)
            assertNull(row.dynamicAccMg)
            assertTrue(queries.selectPolarEcgSegmentsBySession("garmin-style").executeAsList().isEmpty())
            // HRR persistence path unchanged.
            assertEquals(15L, queries.selectSessionById("garmin-style").executeAsOne().hrr60Bpm)
            assertEquals(25L, queries.selectSessionById("garmin-style").executeAsOne().hrr300Bpm)
        } finally {
            driver.close()
        }
    }

    private fun session(sessionId: String) = WorkoutSession(
        id = sessionId,
        timestamp = NOW_MS,
        mode = "OldSchool",
        reps = 10,
        weightPerCableKg = 25f,
        totalReps = 10,
        workingReps = 10,
    )

    private fun beat(sessionId: String) = HrmBeatSample(
        id = 900,
        sessionId = sessionId,
        timestampMs = NOW_MS,
        phase = "WORKING_SET",
        hrBpm = 145,
        rrMs = 800.0,
        rmssdMs = null,
        torsoPitchDeg = null,
        torsoRollDeg = null,
        dynamicAccMg = null,
    )

    private fun ecg(sessionId: String) = PolarEcgSegment(
        id = 901,
        sessionId = sessionId,
        sensorTimestampNs = 1,
        wallClockTimestampMs = NOW_MS,
        phase = "WORKING_SET",
        samplesUvCsv = "100,105",
    )

    companion object {
        private const val NOW_MS = 1_700_000_000_000L
    }
}
