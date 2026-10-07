package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.WorkoutState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #1182: the achieved Echo load is the measured working-window peak per cable, never
 * the configured seed. Availability is PROVENANCE (were accepted finite working samples
 * captured?), never numerical equality (merge-gate R1: a valid measured load may equal the
 * configured metadata) and never a warmup transient (merge-gate R2). Persisted rows use the
 * non-null 0 sentinel for "no measurement", so callers render "Load unavailable" rather than
 * the seed or "0 kg lifted".
 */
class EchoAchievedLoadResolverTest {

    private fun echoSummary(
        measuredWorkingPeak: Float?,
        configured: Float = 5f,
        isEcho: Boolean = true,
    ) = WorkoutState.SetSummary(
        metrics = emptyList(),
        peakLoadKgPerCable = measuredWorkingPeak ?: 0f,
        avgLoadKgPerCable = 0f,
        repCount = 9,
        heaviestLiftKgPerCable = measuredWorkingPeak ?: configured,
        configuredWeightKgPerCable = configured,
        measuredWorkingPeakKgPerCable = measuredWorkingPeak,
        isEchoMode = isEcho,
    )

    private fun echoSession(
        configured: Float,
        heaviest: Float?,
        mode: String = "Echo",
    ) = WorkoutSession(mode = mode, weightPerCableKg = configured, heaviestLiftKg = heaviest)

    // ===== isEcho =====

    @Test
    fun `isEcho detects echo modes and rejects non-echo`() {
        assertEquals(true, EchoAchievedLoadResolver.isEcho(echoSession(5f, 80f, mode = "Echo")))
        assertEquals(true, EchoAchievedLoadResolver.isEcho(echoSession(5f, 80f, mode = "Echo Epic")))
        assertEquals(false, EchoAchievedLoadResolver.isEcho(echoSession(5f, 80f, mode = "OldSchool")))
    }

    // ===== fromSummary (live, provenance-based) =====

    @Test
    fun `fromSummary returns the measured working peak as achieved load`() {
        // 5f configured, 80f measured -> 80f is achieved, never the seed.
        assertEquals(80f, EchoAchievedLoadResolver.fromSummary(echoSummary(measuredWorkingPeak = 80f)))
    }

    @Test
    fun `fromSummary keeps a valid measurement equal to the configured metadata`() {
        // Merge-gate R1: accepted working load equal to configured metadata is still
        // measured. Numerical equality is NOT provenance.
        assertEquals(5f, EchoAchievedLoadResolver.fromSummary(echoSummary(measuredWorkingPeak = 5f, configured = 5f)))
    }

    @Test
    fun `fromSummary is unavailable when no accepted working sample was captured`() {
        // Merge-gate R2: warmup-only / empty working windows carry no measurement.
        assertNull(EchoAchievedLoadResolver.fromSummary(echoSummary(measuredWorkingPeak = null)))
    }

    @Test
    fun `fromSummary rejects zero and non-finite peaks as unavailable`() {
        assertNull(EchoAchievedLoadResolver.fromSummary(echoSummary(measuredWorkingPeak = 0f)))
        assertNull(EchoAchievedLoadResolver.fromSummary(echoSummary(measuredWorkingPeak = Float.NaN)))
        assertNull(EchoAchievedLoadResolver.fromSummary(echoSummary(measuredWorkingPeak = -3f)))
    }

    @Test
    fun `fromSummary returns null for non-echo sets`() {
        assertNull(EchoAchievedLoadResolver.fromSummary(echoSummary(measuredWorkingPeak = 80f, isEcho = false)))
    }

    // ===== fromSession (historical, read time) =====

    @Test
    fun `fromSession returns the recorded measured heaviest as achieved load`() {
        assertEquals(80f, EchoAchievedLoadResolver.fromSession(echoSession(configured = 5f, heaviest = 80f)))
    }

    @Test
    fun `fromSession honors the unmeasured sentinel`() {
        // Post-#1182 rows record 0 when no accepted working sample exists.
        assertNull(EchoAchievedLoadResolver.fromSession(echoSession(configured = 5f, heaviest = 0f)))
    }

    @Test
    fun `fromSession conservatively treats an unprovenanced heaviest equal to the configured seed as unavailable`() {
        // Conservative legacy handling: a pre-#1182 empty-window row stores the configured
        // seed in the measured column and records zero forces; it can never surface as
        // achievement (never the configured 11.02 lb).
        assertNull(EchoAchievedLoadResolver.fromSession(echoSession(configured = 5f, heaviest = 5f)))
    }

    @Test
    fun `fromSession keeps a measured load equal to the configured metadata when telemetry evidence exists`() {
        // Post-#1182 rows (or legacy rows with recorded forces) prove telemetry was
        // captured; a genuine measured load equal to the configured metadata is preserved.
        val session = WorkoutSession(
            mode = "Echo",
            weightPerCableKg = 5f,
            heaviestLiftKg = 5f,
            peakForceEccentricA = 9.4f,
        )
        assertEquals(5f, EchoAchievedLoadResolver.fromSession(session))
    }

    @Test
    fun `fromSession does not let portal-hydrated concentric forces vouch for a pulled seed`() {
        // A pulled row carries weightKg as both configured and measured, and concentric
        // peaks hydrated from rep summaries. A pre-#1182 push put the configured seed in
        // weightKg, so those forces are not evidence that the weight was measured.
        val pulledLegacy = WorkoutSession(
            mode = "Echo",
            weightPerCableKg = 5f,
            heaviestLiftKg = 5f,
            peakForceConcentricA = 41.2f,
            peakForceConcentricB = 40.8f,
        )
        assertNull(EchoAchievedLoadResolver.fromSession(pulledLegacy))
    }

    @Test
    fun `fromSession treats missing or invalid heaviest as unavailable`() {
        assertNull(EchoAchievedLoadResolver.fromSession(echoSession(configured = 5f, heaviest = null)))
        assertNull(EchoAchievedLoadResolver.fromSession(echoSession(configured = 5f, heaviest = Float.NaN)))
    }

    @Test
    fun `fromSession returns null for non-echo sessions`() {
        assertNull(EchoAchievedLoadResolver.fromSession(echoSession(configured = 5f, heaviest = 80f, mode = "OldSchool")))
    }

    // ===== hasMeasuredLoad (row provenance shared with analytics) =====

    @Test
    fun `hasMeasuredLoad requires a positive finite measurement with provenance`() {
        assertTrue(EchoAchievedLoadResolver.hasMeasuredLoad(80f, 5f))
        assertFalse(EchoAchievedLoadResolver.hasMeasuredLoad(null, 5f))
        assertFalse(EchoAchievedLoadResolver.hasMeasuredLoad(0f, 5f))
        assertFalse(EchoAchievedLoadResolver.hasMeasuredLoad(-1f, 5f))
        assertFalse(EchoAchievedLoadResolver.hasMeasuredLoad(Float.NaN, 5f))
        // Equal to the placeholder: trusted only with independent telemetry evidence.
        assertFalse(EchoAchievedLoadResolver.hasMeasuredLoad(5f, 5f))
        assertTrue(EchoAchievedLoadResolver.hasMeasuredLoad(5f, 5f, forceTelemetry = true))
    }

    // ===== primaryLoadKg =====

    @Test
    fun `primaryLoadKg keeps the configured weight for non-echo sessions`() {
        // Non-Echo configured weight IS the achieved load; display must not change.
        assertEquals(32f, EchoAchievedLoadResolver.primaryLoadKg(echoSession(configured = 32f, heaviest = 40f, mode = "OldSchool")))
    }

    @Test
    fun `primaryLoadKg resolves echo to the measured peak or unavailable`() {
        assertEquals(80f, EchoAchievedLoadResolver.primaryLoadKg(echoSession(configured = 5f, heaviest = 80f)))
        assertNull(EchoAchievedLoadResolver.primaryLoadKg(echoSession(configured = 5f, heaviest = 0f)))
    }

    // ===== completedSetLoadKg =====

    @Test
    fun `completedSetLoadKg resolves echo via the session and non-echo via the set`() {
        val echoSet = CompletedSet.create(sessionId = "s1", setNumber = 0, actualReps = 9, actualWeightKg = 5f)
        // Echo: a legacy placeholder actualWeightKg is not achievement; the session measured peak wins.
        assertEquals(80f, EchoAchievedLoadResolver.completedSetLoadKg(echoSet, echoSession(configured = 5f, heaviest = 80f)))
        assertNull(EchoAchievedLoadResolver.completedSetLoadKg(echoSet, echoSession(configured = 5f, heaviest = 5f)))

        val nonEchoSet = CompletedSet.create(sessionId = "s2", setNumber = 0, actualReps = 9, actualWeightKg = 42f)
        // Non-Echo: the recorded set weight is used unchanged.
        assertEquals(42f, EchoAchievedLoadResolver.completedSetLoadKg(nonEchoSet, echoSession(configured = 32f, heaviest = 40f, mode = "OldSchool")))
    }

    @Test
    fun `completedSetLoadKg honors the set row unmeasured sentinel before consulting the session`() {
        val unmeasuredSet = CompletedSet.create(sessionId = "s3", setNumber = 0, actualReps = 9, actualWeightKg = 0f)
        assertNull(EchoAchievedLoadResolver.completedSetLoadKg(unmeasuredSet, echoSession(configured = 5f, heaviest = 80f)))
    }
}
