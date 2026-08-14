package com.devil.phoenixproject.domain.model

/**
 * Carries the full machine status-word, position, and velocity from every processed BLE monitor
 * sample, including packets whose status word is zero. Supersedes the narrow [Unit]-typed
 * `deloadOccurredEvents` flow for downstream consumers that need richer context
 * (e.g. ROM-fraction stall detection in Issue #673 PR 2).
 *
 * @param timestamp Epoch-ms when the sample was received
 * @param sampleStatus Parsed status-word flags from the monitor packet
 * @param position Cable position in mm (max of A/B at time of status sample)
 * @param velocity Cable velocity in mm/s (max of A/B, EMA-smoothed, at time of status sample)
 */
data class MachineStatusEvent(
    val timestamp: Long,
    val sampleStatus: SampleStatus,
    val position: Float,
    val velocity: Float,
)
