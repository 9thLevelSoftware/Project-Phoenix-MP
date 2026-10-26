package com.devil.phoenixproject.presentation.tv

import kotlin.test.Test
import kotlin.test.assertEquals

class TvTelemetryWatchdogTest {
    @Test
    fun activeSetWithStaleTelemetryRequestsSharedStop() {
        assertEquals(
            TvTelemetryAction.STOP_ACTIVE_SET,
            resolveTvTelemetryAction(machineSetActive = true, elapsedWithoutTelemetryMs = TV_TELEMETRY_TIMEOUT_MS),
        )
    }

    @Test
    fun recentTelemetryDoesNotStopAnActiveSet() {
        assertEquals(
            TvTelemetryAction.NO_OP,
            resolveTvTelemetryAction(machineSetActive = true, elapsedWithoutTelemetryMs = TV_TELEMETRY_TIMEOUT_MS - 1),
        )
    }

    @Test
    fun staleTelemetryWhileIdleDoesNotIssueMachineCommand() {
        assertEquals(
            TvTelemetryAction.NO_OP,
            resolveTvTelemetryAction(machineSetActive = false, elapsedWithoutTelemetryMs = TV_TELEMETRY_TIMEOUT_MS * 2),
        )
    }
}
