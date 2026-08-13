package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.RepNotification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepNotificationFreshnessGateTest {

    @Test
    fun `pre-cutover packet drop prevents stale notification processing`() {
        val gate = RepNotificationFreshnessGate()
        val lease = activeLease(target = 3, cutover = 1_000L)

        assertEquals(
            RepFreshnessDecision.Drop(RepDropReason.PRE_CUTOVER_TIMESTAMP),
            gate.evaluate(lease, modernPacket(repsSetCount = 3, repsSetTotal = 4, timestamp = 999L)),
        )
    }

    @Test
    fun `modern all-zero baseline arms later working-set progress`() {
        val gate = RepNotificationFreshnessGate()
        val lease = activeLease(target = 3, cutover = 1_000L)

        assertEquals(RepFreshnessDecision.BaselineOnly, gate.evaluate(lease, modernPacket(timestamp = 1_001L)))
        assertEquals(RepFreshnessState.Armed, gate.stateFor(lease))
        assertEquals(RepFreshnessDecision.Process, gate.evaluate(lease, modernPacket(repsSetCount = 1, repsSetTotal = 3, timestamp = 1_002L)))
    }

    @Test
    fun `matching non-terminal progress processes instead of waiting for a baseline`() {
        val gate = RepNotificationFreshnessGate()
        val lease = activeLease(target = 3, cutover = 1_000L)

        assertEquals(RepFreshnessDecision.Process, gate.evaluate(lease, modernPacket(repsSetCount = 1, repsSetTotal = 3, timestamp = 1_001L)))
        assertEquals(RepFreshnessState.Armed, gate.stateFor(lease))
    }

    @Test
    fun `conflicting modern target drops instead of arming wrong execution`() {
        val gate = RepNotificationFreshnessGate()
        val lease = activeLease(target = 3, cutover = 1_000L)

        assertEquals(
            RepFreshnessDecision.Drop(RepDropReason.TARGET_MISMATCH),
            gate.evaluate(lease, modernPacket(repsSetCount = 1, repsSetTotal = 4, timestamp = 1_001L)),
        )
        assertEquals(RepFreshnessState.AwaitingEvidence, gate.stateFor(lease))
    }

    @Test
    fun `terminal packet before evidence is rejected`() {
        val gate = RepNotificationFreshnessGate()
        val lease = activeLease(target = 3, cutover = 1_000L)

        val decision = gate.evaluate(lease, modernPacket(repsSetCount = 3, repsSetTotal = 3, timestamp = 1_001L))

        assertEquals(RepFreshnessDecision.Drop(RepDropReason.TERMINAL_BEFORE_EVIDENCE), decision)
        assertEquals(RepFreshnessState.AwaitingEvidence, gate.stateFor(lease))
    }

    @Test
    fun `movement arms a fixed one rep execution`() {
        val gate = RepNotificationFreshnessGate()
        val lease = activeLease(target = 1, cutover = 1_000L)

        assertTrue(gate.observeMovement(lease))
        assertEquals(RepFreshnessDecision.Process, gate.evaluate(lease, modernPacket(repsSetCount = 1, repsSetTotal = 1, timestamp = 1_001L)))
    }

    @Test
    fun `armed execution processes later packet even when firmware target changes`() {
        val gate = RepNotificationFreshnessGate()
        val lease = activeLease(target = 3, cutover = 1_000L)
        gate.evaluate(lease, modernPacket(timestamp = 1_001L))

        assertEquals(
            RepFreshnessDecision.Process,
            gate.evaluate(lease, modernPacket(repsSetCount = 1, repsSetTotal = 4, timestamp = 1_002L)),
        )
    }

    @Test
    fun `first legacy packet only establishes counters`() {
        val gate = RepNotificationFreshnessGate()
        val lease = activeLease(target = 3, cutover = 1_000L)

        assertEquals(RepFreshnessDecision.BaselineOnly, gate.evaluate(lease, legacyPacket(topCounter = 5, completeCounter = 4, timestamp = 1_001L)))
        assertEquals(RepFreshnessState.LegacyBaseline(5, 4), gate.stateFor(lease))
    }

    @Test
    fun `legacy counter change processes instead of remaining baseline only`() {
        val gate = RepNotificationFreshnessGate()
        val lease = activeLease(target = 3, cutover = 1_000L)
        gate.evaluate(lease, legacyPacket(topCounter = 5, completeCounter = 4, timestamp = 1_001L))

        assertEquals(RepFreshnessDecision.Process, gate.evaluate(lease, legacyPacket(topCounter = 6, completeCounter = 4, timestamp = 1_002L)))
        assertEquals(RepFreshnessState.Armed, gate.stateFor(lease))
    }

    @Test
    fun `invalidation resets state and rejects notifications for prior execution`() {
        val gate = RepNotificationFreshnessGate()
        val lease = activeLease(target = 3, cutover = 1_000L)
        gate.evaluate(lease, modernPacket(timestamp = 1_001L))

        gate.invalidate(lease)

        assertEquals(RepFreshnessState.AwaitingEvidence, gate.stateFor(lease))
        assertEquals(RepFreshnessDecision.Drop(RepDropReason.LEASE_NOT_ACTIVE), gate.evaluate(lease, modernPacket(repsSetCount = 1, repsSetTotal = 3, timestamp = 1_002L)))
    }

    @Test
    fun `reset clears prior evidence so a new execution waits for proof`() {
        val gate = RepNotificationFreshnessGate()
        val lease = activeLease(target = 3, cutover = 1_000L)
        gate.evaluate(lease, modernPacket(timestamp = 1_001L))

        gate.resetFor(lease)

        assertEquals(RepFreshnessState.AwaitingEvidence, gate.stateFor(lease))
        assertEquals(
            RepFreshnessDecision.Drop(RepDropReason.TERMINAL_BEFORE_EVIDENCE),
            gate.evaluate(lease, modernPacket(repsSetCount = 3, repsSetTotal = 3, timestamp = 1_002L)),
        )
    }

    private fun activeLease(target: Int, cutover: Long) = ExecutionLease(
        executionId = 1L,
        sessionId = "session-a",
        profileId = "profile-a",
        requiresMachine = true,
        workingRepTarget = target,
        isBodyweight = false,
        isJustLift = false,
        isAmrap = false,
        isTimedCable = false,
        activationCutoverTimestampMs = cutover,
    )

    private fun modernPacket(repsSetCount: Int = 0, repsSetTotal: Int = 0, timestamp: Long): RepNotification =
        RepNotification(0, 0, 0, 0, repsSetCount, repsSetTotal, rawData = byteArrayOf(), timestamp = timestamp)

    private fun legacyPacket(topCounter: Int, completeCounter: Int, timestamp: Long): RepNotification =
        RepNotification(topCounter, completeCounter, 0, 0, 0, 0, rawData = byteArrayOf(), timestamp = timestamp, isLegacyFormat = true)
}
