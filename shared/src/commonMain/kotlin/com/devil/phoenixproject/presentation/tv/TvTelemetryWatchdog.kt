package com.devil.phoenixproject.presentation.tv

/** 4 Hz polling can tolerate short radio jitter; two seconds means eight missed samples. */
const val TV_TELEMETRY_TIMEOUT_MS = 2_000L

enum class TvTelemetryAction {
    NO_OP,
    STOP_ACTIVE_SET,
}

/** A stale telemetry signal only requests the shared set-stop path; it never sends BLE data. */
fun resolveTvTelemetryAction(
    machineSetActive: Boolean,
    elapsedWithoutTelemetryMs: Long,
): TvTelemetryAction = if (
    machineSetActive && elapsedWithoutTelemetryMs >= TV_TELEMETRY_TIMEOUT_MS
) {
    TvTelemetryAction.STOP_ACTIVE_SET
} else {
    TvTelemetryAction.NO_OP
}
