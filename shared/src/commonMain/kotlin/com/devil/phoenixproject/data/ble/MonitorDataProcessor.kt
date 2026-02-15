package com.devil.phoenixproject.data.ble

import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.currentTimeMillis

/**
 * Stub MonitorDataProcessor for TDD RED phase.
 * All methods return default values; tests must fail against this stub.
 */
class MonitorDataProcessor(
    private val onDeloadOccurred: () -> Unit = {},
    private val onRomViolation: (RomViolationType) -> Unit = {},
    private val timeProvider: () -> Long = { currentTimeMillis() }
) {
    enum class RomViolationType { OUTSIDE_HIGH, OUTSIDE_LOW }

    var strictValidationEnabled: Boolean = true
    val notificationCount: Long get() = 0L

    fun process(packet: MonitorPacket): WorkoutMetric? = null
    fun resetForNewSession() {}
    fun getPollRateStats(): String = ""
}
