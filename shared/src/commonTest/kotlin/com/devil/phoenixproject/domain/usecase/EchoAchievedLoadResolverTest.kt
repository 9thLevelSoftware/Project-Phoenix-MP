package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.WorkoutState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Issue #1182: the achieved Echo load is the measured peak per cable, never the configured
 * seed. These tests pin the resolver's availability contract: a distinct measurement resolves
 * to that value; the configured placeholder (or absent/invalid telemetry) resolves to null so
 * callers render "Load unavailable" rather than the seed or "0 kg lifted".
 */
class EchoAchievedLoadResolverTest {

    private fun echoSummary(
        peak: Float,
        configured: Float,
        isEcho: Boolean = true,
    ) = WorkoutState.SetSummary(
        metrics = emptyList(),
        peakLoadKgPerCable = 0f,
        avgLoadKgPerCable = 0f,
        repCount = 9,
        heaviestLiftKgPerCable = peak,
        configuredWeightKgPerCable = configured,
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

    // ===== fromSummary (live) =====

    @Test
    fun `fromSummary returns the distinct measured peak as achieved load`() {
        // 5f configured, 80f measured -> 80f is achieved, never the seed.
        assertEquals(80f, EchoAchievedLoadResolver.fromSummary(echoSummary(peak = 80f, configured = 5f)))
    }

    @Test
    fun `fromSummary treats the configured placeholder as unavailable`() {
        // Empty-telemetry fallback stores the configured weight in heaviestLiftKgPerCable.
        assertNull(EchoAchievedLoadResolver.fromSummary(echoSummary(peak = 5f, configured = 5f)))
    }

    @Test
    fun `fromSummary rejects zero and non-finite peaks as unavailable`() {
        assertNull(EchoAchievedLoadResolver.fromSummary(echoSummary(peak = 0f, configured = 5f)))
        assertNull(EchoAchievedLoadResolver.fromSummary(echoSummary(peak = Float.NaN, configured = 5f)))
        assertNull(EchoAchievedLoadResolver.fromSummary(echoSummary(peak = -3f, configured = 5f)))
    }

    @Test
    fun `fromSummary returns null for non-echo sets`() {
        assertNull(EchoAchievedLoadResolver.fromSummary(echoSummary(peak = 80f, configured = 5f, isEcho = false)))
    }

    // ===== fromSession (historical) =====

    @Test
    fun `fromSession returns the distinct measured heaviest as achieved load`() {
        assertEquals(80f, EchoAchievedLoadResolver.fromSession(echoSession(configured = 5f, heaviest = 80f)))
    }

    @Test
    fun `fromSession treats a heaviest equal to the configured seed as unavailable`() {
        assertNull(EchoAchievedLoadResolver.fromSession(echoSession(configured = 5f, heaviest = 5f)))
    }

    @Test
    fun `fromSession treats missing or invalid heaviest as unavailable`() {
        assertNull(EchoAchievedLoadResolver.fromSession(echoSession(configured = 5f, heaviest = null)))
        assertNull(EchoAchievedLoadResolver.fromSession(echoSession(configured = 5f, heaviest = 0f)))
        assertNull(EchoAchievedLoadResolver.fromSession(echoSession(configured = 5f, heaviest = Float.NaN)))
    }

    @Test
    fun `fromSession returns null for non-echo sessions`() {
        assertNull(EchoAchievedLoadResolver.fromSession(echoSession(configured = 5f, heaviest = 80f, mode = "OldSchool")))
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
        assertNull(EchoAchievedLoadResolver.primaryLoadKg(echoSession(configured = 5f, heaviest = 5f)))
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
}
