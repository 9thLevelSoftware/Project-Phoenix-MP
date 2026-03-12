package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SafetyPolicyTest {

    private val policy = DefaultSafetyPolicy()

    @Test
    fun startRejectedWhenWeightOutsidePolicyLimits() {
        val params = WorkoutParameters(weightPerCableKg = 999f)

        val decision = policy.evaluateStart(params, WorkoutState.Idle)

        assertFalse(decision.allowed)
        assertTrue(decision.reason?.contains("outside policy limits") == true)
    }

    @Test
    fun adjustClampsToConfiguredBounds() {
        val params = WorkoutParameters(weightPerCableKg = 10f)

        val lowDecision = policy.evaluateAdjust(params, -20f, WorkoutState.Active)
        val highDecision = policy.evaluateAdjust(params, 400f, WorkoutState.Active)

        assertTrue(lowDecision.allowed)
        assertEquals(policy.minLoadKgPerCable, lowDecision.approvedWeightKg)

        assertTrue(highDecision.allowed)
        assertEquals(policy.maxLoadKgPerCable, highDecision.approvedWeightKg)
    }

    @Test
    fun stopRejectedWhenAlreadyIdle() {
        val decision = policy.evaluateStop(WorkoutState.Idle, emergency = false)

        assertFalse(decision.allowed)
        assertTrue(decision.reason?.contains("ignored") == true)
    }
}
