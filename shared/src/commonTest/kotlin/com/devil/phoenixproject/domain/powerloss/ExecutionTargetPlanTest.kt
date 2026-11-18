package com.devil.phoenixproject.domain.powerloss

import com.devil.phoenixproject.domain.model.ProgramMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #1392 P2 execution-target-plan tests (SD4): protocol byte bounds,
 * finite/unlimited identity preservation, mandatory POWER backstop, and
 * capability gating.
 */
class ExecutionTargetPlanTest {

    private val finiteBounds = ProtocolRepBounds(
        maxRepresentableWorkingReps = 254, // PROGRAM: 254 − warmup applied by caller
        protocolIdentityFinite = true,
    )

    private val unlimitedBounds = ProtocolRepBounds(
        maxRepresentableWorkingReps = 254,
        protocolIdentityFinite = false,
    )

    private fun powerConfig(cap: Int = 20, nominal: Int? = 12) = PowerLossConfig(
        targetMode = TargetMode.POWER,
        thresholdPercent = 20,
        maxWorkingReps = cap,
        savedNominalReps = nominal,
    )

    // ===== POWER derivation =====

    @Test
    fun `POWER derives a finite cap and preserves the nominal as display-only`() {
        val plan = ExecutionTargetPlan.derive(powerConfig(cap = 20, nominal = 12), finiteBounds, warmupSetCount = 2, now = 7L)
        assertNotNull(plan)
        assertEquals(TargetMode.POWER, plan.targetMode)
        assertEquals(20, plan.maxWorkingReps)
        assertEquals(12, plan.nominalReps) // preserved, never a stop condition
        assertEquals(2, plan.warmupSetCount)
        assertTrue(plan.protocolIdentityFinite)
        assertEquals(7L, plan.planValidatedAt)
    }

    @Test
    fun `POWER rejects impossible plans beyond the protocol bound`() {
        assertNull(ExecutionTargetPlan.derive(powerConfig(cap = 255), finiteBounds, warmupSetCount = 0, now = 0L))
        // Echo applicable finite byte bound is supplied by the validator, never hardcoded:
        val echoBound = ProtocolRepBounds(maxRepresentableWorkingReps = 30, protocolIdentityFinite = true)
        assertNotNull(ExecutionTargetPlan.derive(powerConfig(cap = 30), echoBound, warmupSetCount = 0, now = 0L))
        assertNull(ExecutionTargetPlan.derive(powerConfig(cap = 31), echoBound, warmupSetCount = 0, now = 0L))
    }

    @Test
    fun `POWER rejects configs below the minimum backstop`() {
        assertNull(
            ExecutionTargetPlan.derive(
                powerConfig(cap = 1),
                finiteBounds,
                warmupSetCount = 0,
                now = 0L,
            ),
        )
    }

    // ===== REP identity =====

    @Test
    fun `REP finite protocol keeps the nominal target with no derived cap`() {
        val plan = ExecutionTargetPlan.derive(
            PowerLossConfig(targetMode = TargetMode.REP_TARGET, savedNominalReps = 12),
            finiteBounds,
            warmupSetCount = 1,
            now = 0L,
        )
        assertNotNull(plan)
        assertEquals(12, plan.nominalReps)
        assertNull(plan.maxWorkingReps)
        assertTrue(plan.protocolIdentityFinite)
    }

    @Test
    fun `REP unlimited protocol identity stays unlimited`() {
        // Just Lift / AMRAP warmup fallback: unlimited identity must be preserved.
        val plan = ExecutionTargetPlan.derive(
            PowerLossConfig(targetMode = TargetMode.REP_TARGET, savedNominalReps = null),
            unlimitedBounds,
            warmupSetCount = 0,
            now = 0L,
        )
        assertNotNull(plan)
        assertFalse(plan.protocolIdentityFinite)
        assertNull(plan.maxWorkingReps)
    }

    @Test
    fun `REP finite protocol rejects an unrepresentable nominal`() {
        assertNull(
            ExecutionTargetPlan.derive(
                PowerLossConfig(targetMode = TargetMode.REP_TARGET, savedNominalReps = 255),
                finiteBounds,
                warmupSetCount = 0,
                now = 0L,
            ),
        )
    }

    // ===== Cap semantics =====

    @Test
    fun `cap stops only in POWER mode and only at or after the effective max`() {
        val plan = ExecutionTargetPlan.derive(powerConfig(cap = 20), finiteBounds, warmupSetCount = 0, now = 0L)!!
        assertFalse(plan.capStopAtWorkingAttempt(19))
        assertTrue(plan.capStopAtWorkingAttempt(20))
        val repPlan = ExecutionTargetPlan.derive(
            PowerLossConfig(targetMode = TargetMode.REP_TARGET, savedNominalReps = 12),
            finiteBounds,
            warmupSetCount = 0,
            now = 0L,
        )!!
        assertFalse(repPlan.capStopAtWorkingAttempt(50))
    }

    @Test
    fun `saved nominal below the cap never stops POWER early`() {
        val plan = ExecutionTargetPlan.derive(powerConfig(cap = 20, nominal = 12), finiteBounds, warmupSetCount = 0, now = 0L)!!
        // The nominal (12) is not a cap: only the effective max stops the set.
        assertFalse(plan.capStopAtWorkingAttempt(12))
        assertFalse(plan.capStopAtWorkingAttempt(13))
    }

    // ===== Capability gating =====

    @Test
    fun `protocol family mapping supports exactly the V1 effective modes`() {
        assertEquals(PowerLossProtocolFamily.OLD_SCHOOL, PowerLossProtocolFamily.of(ProgramMode.OldSchool, false))
        assertEquals(PowerLossProtocolFamily.PUMP, PowerLossProtocolFamily.of(ProgramMode.Pump, false))
        assertEquals(PowerLossProtocolFamily.ECHO, PowerLossProtocolFamily.of(ProgramMode.Echo, false))
        assertEquals(PowerLossProtocolFamily.JUST_LIFT, PowerLossProtocolFamily.of(ProgramMode.OldSchool, true))
        assertEquals(PowerLossProtocolFamily.UNSUPPORTED, PowerLossProtocolFamily.of(ProgramMode.TUT, false))
        assertEquals(PowerLossProtocolFamily.UNSUPPORTED, PowerLossProtocolFamily.of(ProgramMode.TUTBeast, false))
        assertEquals(PowerLossProtocolFamily.UNSUPPORTED, PowerLossProtocolFamily.of(ProgramMode.EccentricOnly, false))
    }

    @Test
    fun `POWER capability requires both protocol support and validated paired samples`() {
        assertTrue(PowerLossCapability.isEligible(PowerLossProtocolFamily.ECHO, pairedSamplesValidated = true))
        assertFalse(PowerLossCapability.isEligible(PowerLossProtocolFamily.ECHO, pairedSamplesValidated = false))
        assertFalse(PowerLossCapability.isEligible(PowerLossProtocolFamily.UNSUPPORTED, pairedSamplesValidated = true))
    }
}
