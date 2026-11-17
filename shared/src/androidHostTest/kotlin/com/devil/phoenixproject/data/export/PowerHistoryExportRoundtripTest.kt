package com.devil.phoenixproject.data.export

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.devil.phoenixproject.data.local.ExerciseImporter
import com.devil.phoenixproject.data.repository.PowerLossCompanionStore
import com.devil.phoenixproject.data.repository.SqlDelightExerciseRepository
import com.devil.phoenixproject.data.repository.SqlDelightWorkoutRepository
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.powerloss.PowerCable
import com.devil.phoenixproject.domain.powerloss.PowerLossAttemptRecord
import com.devil.phoenixproject.domain.powerloss.PowerLossSetEvidence
import com.devil.phoenixproject.domain.powerloss.PowerStopReason
import com.devil.phoenixproject.domain.powerloss.SampleQualityMarkers
import com.devil.phoenixproject.domain.powerloss.TargetMode
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.createTestSchema
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Issue #1392 (packet P4) — power-history export v1 roundtrip.
 *
 * P4 acceptance: "power-history export roundtrips preserving null/zero/identity".
 * Export → parse back → field-equal, INCLUDING the null-vs-zero distinction: a
 * measured 0 must survive as 0 and a missing metric must survive as null — the
 * two must never collapse into each other.
 */
class PowerHistoryExportRoundtripTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: PhoenixDatabase
    private lateinit var exporter: PowerHistoryExporter

    @Before
    fun setup() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        createTestSchema(driver)
        database = PhoenixDatabase(driver)
        val exerciseRepository = SqlDelightExerciseRepository(
            database,
            ExerciseImporter(database),
            FakePreferencesManager(),
        )
        val workoutRepository = SqlDelightWorkoutRepository(database, exerciseRepository)
        exporter = PowerHistoryExporterImpl(database)
        // The exporter enumerates sessions per profile; seed one owned session and one
        // foreign-profile session (which must never appear in the export).
        kotlinx.coroutines.runBlocking {
            workoutRepository.saveSession(WorkoutSession(id = SESSION_ID, profileId = PROFILE_ID))
            workoutRepository.saveSession(WorkoutSession(id = OTHER_SESSION_ID, profileId = OTHER_PROFILE_ID))
        }
    }

    @After
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `export roundtrips field-equal with null-vs-zero preserved`() = runTest {
        val queries = database.phoenixDatabaseQueries
        PowerLossCompanionStore.commitEvidence(queries, ownEvidence(), nowMs = 1_000L)
        PowerLossCompanionStore.commitEvidence(queries, foreignEvidence(), nowMs = 2_000L)

        val result = exporter.exportPowerData(PROFILE_ID)

        assertEquals(1, result.version)
        assertEquals("power-history-v1-$PROFILE_ID.json", result.displayName)
        // The document identifies itself and its units explicitly.
        assertTrue(result.content.contains("\"documentType\": \"power-history-export\""), result.content)
        assertTrue(result.content.contains("\"version\": 1"), result.content)
        assertTrue(result.content.contains("\"power\": \"watts\""), result.content)
        assertTrue(result.content.contains("\"rom\": \"millimeters\""), result.content)
        assertTrue(result.content.contains("\"duration\": \"milliseconds\""), result.content)

        val document = PowerHistoryExportCodec.decode(result.content)
        assertEquals(1, document.version)
        assertEquals(PROFILE_ID, document.profileId)
        // Profile scoping: only this profile's session, only sessions with evidence.
        assertEquals(listOf(SESSION_ID), document.sessions.map { it.sessionId })
        assertEquals(1, document.sessions.single().sets.size)

        // Full field-equality against the seeded evidence (identity + every metric).
        val decoded = document.sessions.single().sets.single()
        assertEquals(ownEvidence(), decoded)

        // Null-vs-zero, called out explicitly on top of the deep equality.
        val measuredZero = decoded.attempts.first { it.workingConcentricOrdinal == 1 }
        val missing = decoded.attempts.first { it.workingConcentricOrdinal == 2 }
        assertEquals(0.0f, measuredZero.mcpWatts, "a measured zero must not roundtrip as null")
        assertEquals(0L, measuredZero.concentricDurationMs)
        assertEquals(0.0f, measuredZero.lossPercentAtDecision)
        assertEquals(0.0f, measuredZero.romAMm)
        assertNull(missing.mcpWatts, "a missing metric must not roundtrip as 0")
        assertNull(missing.eccentricPowerWatts)
        assertNull(missing.concentricDurationMs)
        assertNull(missing.eccentricDurationMs)
        assertNull(missing.lossPercentAtDecision)
        assertNull(missing.romAMm)
        assertNull(missing.romBMm)
        assertNull(missing.romAPercentOfReference)
        assertNull(missing.romBPercentOfReference)
        // Attempt-only identity survives (pending ≠ completed).
        assertEquals(false, missing.completed)
        assertNull(missing.legacyRepIndex)
        // Set-level facts survive: reference/trigger/winner + BOTH qualification facts.
        assertEquals(1, decoded.referenceWorkingOrdinal)
        assertEquals(100.5f, decoded.referenceMcpWatts)
        assertNull(decoded.referenceRomBMm)
        assertEquals(2, decoded.triggerWorkingOrdinal)
        assertEquals(PowerStopReason.POWER_LOSS, decoded.winnerReason)
        assertEquals(true, decoded.shortRomObserved)
        assertEquals(true, decoded.powerLossObserved)
    }

    /** Measured zeros on one attempt, missing (null) metrics on the attempt-only row. */
    private fun ownEvidence(): PowerLossSetEvidence = PowerLossSetEvidence(
        sessionId = SESSION_ID,
        setId = "set-1",
        metricVersion = 1,
        targetMode = TargetMode.POWER,
        thresholdPercent = 25,
        maxWorkingReps = null,
        requireTwoConsecutive = true,
        savedNominalReps = 12,
        activeCables = setOf(PowerCable.A, PowerCable.B),
        activeCableProvenance = "frozen-mask",
        referenceWorkingOrdinal = 1,
        referenceMcpWatts = 100.5f,
        referenceRomAMm = 420.0f,
        referenceRomBMm = null,
        triggerWorkingOrdinal = 2,
        winnerReason = PowerStopReason.POWER_LOSS,
        shortRomObserved = true,
        powerLossObserved = true,
        lossPercentAtDecision = 21.5f,
        attempts = listOf(
            PowerLossAttemptRecord(
                workingConcentricOrdinal = 1,
                completed = true,
                legacyRepIndex = 0,
                mcpWatts = 0.0f,
                eccentricPowerWatts = null,
                concentricDurationMs = 0L,
                eccentricDurationMs = null,
                lossPercentAtDecision = 0.0f,
                referenceWorkingOrdinal = 1,
                romAMm = 0.0f,
                romBMm = null,
                romAPercentOfReference = 0.0f,
                romBPercentOfReference = null,
                romTrustworthy = true,
                quality = SampleQualityMarkers(stale = true),
                shortRom = true,
                powerLossQualified = false,
            ),
            PowerLossAttemptRecord(
                workingConcentricOrdinal = 2,
                completed = false,
                legacyRepIndex = null,
                mcpWatts = null,
                eccentricPowerWatts = null,
                concentricDurationMs = null,
                eccentricDurationMs = null,
                lossPercentAtDecision = null,
                referenceWorkingOrdinal = 2,
                romAMm = null,
                romBMm = null,
                romAPercentOfReference = null,
                romBPercentOfReference = null,
                romTrustworthy = false,
                quality = SampleQualityMarkers(),
                shortRom = false,
                powerLossQualified = true,
            ),
        ),
    )

    private fun foreignEvidence(): PowerLossSetEvidence = PowerLossSetEvidence(
        sessionId = OTHER_SESSION_ID,
        setId = "set-foreign",
        metricVersion = 1,
        targetMode = TargetMode.REP_TARGET,
        thresholdPercent = 20,
        maxWorkingReps = 20,
        requireTwoConsecutive = false,
        savedNominalReps = null,
        activeCables = setOf(PowerCable.A),
        activeCableProvenance = "frozen-mask",
        referenceWorkingOrdinal = null,
        referenceMcpWatts = null,
        referenceRomAMm = null,
        referenceRomBMm = null,
        triggerWorkingOrdinal = null,
        winnerReason = null,
        shortRomObserved = false,
        powerLossObserved = false,
        lossPercentAtDecision = null,
        attempts = emptyList(),
    )

    private companion object {
        const val PROFILE_ID = "profile-roundtrip"
        const val OTHER_PROFILE_ID = "profile-other"
        const val SESSION_ID = "session-roundtrip"
        const val OTHER_SESSION_ID = "session-other"
    }
}
