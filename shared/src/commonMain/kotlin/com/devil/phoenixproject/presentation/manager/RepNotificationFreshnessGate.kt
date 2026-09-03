package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.data.repository.RepNotification

private const val UNLIMITED_REPS_SET_TOTAL = 252

internal sealed interface RepFreshnessState {
    data object AwaitingEvidence : RepFreshnessState
    data class LegacyBaseline(val topCounter: Int, val completeCounter: Int) : RepFreshnessState
    data object Armed : RepFreshnessState
}

internal enum class RepDropReason {
    LEASE_NOT_ACTIVE,
    PRE_CUTOVER_TIMESTAMP,
    TARGET_MISMATCH,
    TERMINAL_BEFORE_EVIDENCE,
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
        states[lease.identity()] = RepFreshnessState.Armed
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
        val targetMatches = lease.isJustLift ||
            isUnlimitedAmrapPacket ||
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

        if (allZero) {
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

    private fun evaluateLegacy(identity: LeaseIdentity, notification: RepNotification): RepFreshnessDecision {
        val state = states[identity]
        if (state !is RepFreshnessState.LegacyBaseline) {
            states[identity] = RepFreshnessState.LegacyBaseline(notification.topCounter, notification.completeCounter)
            return RepFreshnessDecision.BaselineOnly
        }
        if (state.topCounter == notification.topCounter && state.completeCounter == notification.completeCounter) {
            return RepFreshnessDecision.BaselineOnly
        }
        states[identity] = RepFreshnessState.Armed
        return RepFreshnessDecision.Process
    }

    private fun isActive(lease: ExecutionLease): Boolean = lease.activationCutoverTimestampMs != null && lease.identity() !in invalidatedLeases

    private fun ExecutionLease.identity() = LeaseIdentity(executionId, sessionId)

    private data class LeaseIdentity(
        val executionId: Long,
        val sessionId: String,
    )
}
