package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.RepNotification

private const val UNLIMITED_REPS_SET_TOTAL = 252

internal sealed interface RepFreshnessState {
    data object AwaitingEvidence : RepFreshnessState
    data class LegacyBaseline(val topCounter: Int, val completeCounter: Int) : RepFreshnessState

    /**
     * Issue #712: the legacy counter baseline for this execution is established and a later
     * legacy packet has moved past it. Every further legacy packet is a delta from that baseline.
     */
    data object LegacyArmed : RepFreshnessState
    data object Armed : RepFreshnessState
}

internal enum class RepDropReason {
    LEASE_NOT_ACTIVE,
    PRE_CUTOVER_TIMESTAMP,
    TARGET_MISMATCH,
    TERMINAL_BEFORE_EVIDENCE,
    TIMED_CABLE_BEFORE_MOVEMENT,
}

internal sealed interface RepFreshnessDecision {
    data object Process : RepFreshnessDecision
    data object BaselineOnly : RepFreshnessDecision
    data class Drop(val reason: RepDropReason) : RepFreshnessDecision
}

internal class RepNotificationFreshnessGate {
    private val states = mutableMapOf<LeaseIdentity, RepFreshnessState>()
    private val invalidatedLeases = mutableSetOf<LeaseIdentity>()

    fun resetFor(lease: ExecutionLease) {
        val identity = lease.identity()
        invalidatedLeases.remove(identity)
        states[identity] = RepFreshnessState.AwaitingEvidence
    }

    fun invalidate(lease: ExecutionLease) {
        val identity = lease.identity()
        states.remove(identity)
        invalidatedLeases.add(identity)
    }

    fun observeMovement(lease: ExecutionLease): Boolean {
        if (!isActive(lease)) return false
        val identity = lease.identity()
        // Issue #712: movement is freshness evidence for modern packets only. A legacy
        // baseline is already fresh; replacing it with Armed made the next legacy packet
        // re-baseline and swallow that packet's rep.
        when (states[identity]) {
            is RepFreshnessState.LegacyBaseline, RepFreshnessState.LegacyArmed -> Unit
            else -> states[identity] = RepFreshnessState.Armed
        }
        return true
    }

    fun stateFor(lease: ExecutionLease): RepFreshnessState = states[lease.identity()] ?: RepFreshnessState.AwaitingEvidence

    fun evaluate(lease: ExecutionLease, notification: RepNotification): RepFreshnessDecision {
        if (!isActive(lease)) return RepFreshnessDecision.Drop(RepDropReason.LEASE_NOT_ACTIVE)

        val cutover = lease.activationCutoverTimestampMs ?: return RepFreshnessDecision.Drop(RepDropReason.LEASE_NOT_ACTIVE)
        if (notification.timestamp < cutover) {
            return RepFreshnessDecision.Drop(RepDropReason.PRE_CUTOVER_TIMESTAMP)
        }

        val identity = lease.identity()
        if (notification.isLegacyFormat) return evaluateLegacy(identity, notification)

        // Issue #698/#700/#712: Just Lift and AMRAP use unlimited target
        // semantics on the wire (0xFF, reported back as 252). Routine AMRAP
        // leases retain their configured finite UI fallback target (for
        // example 10), so lease.workingRepTarget == 0 is not a reliable
        // unlimited discriminator. The notification sentinel is.
        val isUnlimitedAmrapPacket = lease.isAmrap &&
            lease.usesUnlimitedRepTarget &&
            notification.repsSetTotal == UNLIMITED_REPS_SET_TOTAL
        val isUnlimitedTimedCablePacket = lease.isTimedCable &&
            notification.repsSetTotal == UNLIMITED_REPS_SET_TOTAL
        val targetMatches = lease.isJustLift ||
            isUnlimitedAmrapPacket ||
            isUnlimitedTimedCablePacket ||
            notification.repsSetTotal == 0 ||
            notification.repsSetTotal == lease.workingRepTarget
        if (!targetMatches) return RepFreshnessDecision.Drop(RepDropReason.TARGET_MISMATCH)
        if (stateFor(lease) is RepFreshnessState.Armed) return RepFreshnessDecision.Process

        // Issue #698/#700: Just Lift and AMRAP have no finite rep target,
        // so repsSetCount should never be treated as terminal.
        val terminal = !(lease.isJustLift || lease.isAmrap) &&
            lease.workingRepTarget > 0 &&
            notification.repsSetCount >= lease.workingRepTarget
        val allZero = notification.topCounter == 0 &&
            notification.completeCounter == 0 &&
            notification.repsRomCount == 0 &&
            notification.repsSetCount == 0
        val hasNonTerminalProgress = !terminal && (
            notification.topCounter > 0 ||
                notification.completeCounter > 0 ||
                notification.repsRomCount > 0 ||
                notification.repsSetCount > 0
            )

        // A delayed target-252 packet from a prior unlimited execution can
        // carry working-set counts and force warmup completion on a successor
        // lease. Current-set calibration reports ROM progress with
        // repsSetCount == 0; accept that immediately. A clean baseline or
        // HandleState.Moving remains the proof for working-set 252 packets.
        val timedCableBaseline = isUnlimitedTimedCablePacket &&
            notification.repsRomCount == 0 &&
            notification.repsSetCount == 0
        if (isUnlimitedTimedCablePacket &&
            stateFor(lease) !is RepFreshnessState.Armed &&
            notification.repsSetCount > 0
        ) {
            return RepFreshnessDecision.Drop(RepDropReason.TIMED_CABLE_BEFORE_MOVEMENT)
        }

        if (allZero || timedCableBaseline) {
            states[identity] = RepFreshnessState.Armed
            return RepFreshnessDecision.BaselineOnly
        }
        if (hasNonTerminalProgress) {
            states[identity] = RepFreshnessState.Armed
            return RepFreshnessDecision.Process
        }
        if (terminal) return RepFreshnessDecision.Drop(RepDropReason.TERMINAL_BEFORE_EVIDENCE)

        return RepFreshnessDecision.BaselineOnly
    }

    /**
     * Legacy packets carry only the machine's directional counters, which run across sets, so the
     * first post-cutover packet of an execution is taken as that execution's baseline. That happens
     * ONCE: issue #712 re-baselined every legacy packet that arrived after a processed packet or a
     * Moving handle state, and each re-baseline swallowed that packet's top-counter increment. When
     * the first packet of a set was a top packet, every rep of the set was lost.
     */
    private fun evaluateLegacy(identity: LeaseIdentity, notification: RepNotification): RepFreshnessDecision {
        when (val state = states[identity]) {
            RepFreshnessState.LegacyArmed -> return RepFreshnessDecision.Process

            is RepFreshnessState.LegacyBaseline -> {
                if (state.topCounter == notification.topCounter && state.completeCounter == notification.completeCounter) {
                    return RepFreshnessDecision.BaselineOnly
                }
                states[identity] = RepFreshnessState.LegacyArmed
                return RepFreshnessDecision.Process
            }

            else -> {
                states[identity] = RepFreshnessState.LegacyBaseline(notification.topCounter, notification.completeCounter)
                return RepFreshnessDecision.BaselineOnly
            }
        }
    }

    private fun isActive(lease: ExecutionLease): Boolean = lease.activationCutoverTimestampMs != null && lease.identity() !in invalidatedLeases

    private fun ExecutionLease.identity() = LeaseIdentity(executionId, sessionId)

    private data class LeaseIdentity(
        val executionId: Long,
        val sessionId: String,
    )
}
