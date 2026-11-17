package com.devil.phoenixproject.presentation.garmin

import com.devil.phoenixproject.data.garmin.GarminCompanionTransport
import com.devil.phoenixproject.data.garmin.GarminControlPreferences
import com.devil.phoenixproject.presentation.manager.DefaultWorkoutSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Garmin watch-controls session controller (issue #1391 Phase 1).
 *
 * Owns association, per-workout arming, freshness/grants/epochs, single serialized admission,
 * fail-closed dedupe and observable outcomes. Calls manager-level APIs only — controller bypass
 * of [DefaultWorkoutSessionManager] is forbidden. Urgent STOP_SET pre-empts pending prepare/start/
 * advance and can never land on a later execution. One phone manager only; no second session.
 *
 * The public surface below is the FROZEN UI contract consumed by the phone UI packet (B3).
 * Packet B2 implements the internals behind it; additive members only, never renames.
 */

enum class GarminAvailability {
    /** Feature off (default). */
    OFF,

    /** No supported, matched watch app configured/detected — release boundary (no Install/Arm). */
    UNAVAILABLE,

    /** Enabled but needs device selection / watch app setup. */
    SETUP,

    /** Selected watch connected (NOT armed). */
    CONNECTED,

    /** Armed for the current workout. */
    ARMED,

    /** Error state; bounded detail code only. */
    ERROR,

    /** Recovery required (e.g. account/profile/restart revalidation). */
    RECOVERY,
}

/** Wire readiness enum values, exact per integration-model.md §4. */
enum class GarminReadiness {
    DISABLED,
    UNAVAILABLE,
    SETUP,
    CONNECTED,
    ARMED_READY,
    ACTIVE,
    RESTING,
    SUMMARY,
    PHONE_INPUT_REQUIRED,
    BUSY,
    ERROR,
    RECOVERY_REQUIRED,
}

data class GarminConnectionUiState(
    val enabled: Boolean = false,
    val availability: GarminAvailability = GarminAvailability.OFF,
    val deviceName: String? = null,
    /** True only when the selected device runs the matched production watch-app UUID/protocol. */
    val watchAppMatched: Boolean = false,
    /** Bounded internal detail code (never raw native text); null when not in error. */
    val statusDetailCode: String? = null,
)

data class GarminArmUiState(
    val armed: Boolean = false,
    val readiness: GarminReadiness = GarminReadiness.DISABLED,
    /** Minimal non-sensitive current target/context text (≤ 80 UTF-8 bytes enforced by protocol). */
    val displayLabel: String = "",
    /** State-specific action label, e.g. "Start set", "End set; prepare next", "Continue to rest". */
    val actionLabel: String = "",
    val foregroundRequired: Boolean = true,
    val backgroundStopSupported: Boolean = false,
    /** Truncated exercise name for the cancel confirmation sheet; display-only, never authority. */
    val cancelConfirmationLabel: String? = null,
)

sealed class GarminControlEvent {
    data class Pending(val commandId: String, val action: String) : GarminControlEvent()
    data class Applied(val commandId: String, val action: String) : GarminControlEvent()
    data class Stale(val commandId: String, val action: String) : GarminControlEvent()
    data class Rejected(val commandId: String, val action: String, val reasonCode: String) : GarminControlEvent()
    data class UnknownResult(val commandId: String, val action: String) : GarminControlEvent()
}

class GarminSessionController(
    private val transport: GarminCompanionTransport,
    private val preferences: GarminControlPreferences,
    private val session: DefaultWorkoutSessionManager,
    private val scope: CoroutineScope,
    private val monotonicNowMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val _connectionState = MutableStateFlow(GarminConnectionUiState())
    val connectionState: StateFlow<GarminConnectionUiState> = _connectionState.asStateFlow()

    private val _armState = MutableStateFlow(GarminArmUiState())
    val armState: StateFlow<GarminArmUiState> = _armState.asStateFlow()

    private val _lastEvent = MutableStateFlow<GarminControlEvent?>(null)
    val lastEvent: StateFlow<GarminControlEvent?> = _lastEvent.asStateFlow()

    // ---- UI actions (packet B2 implements; packet B3 calls) ----

    /** Master opt-in toggle (default OFF). Disabling revokes all authority immediately. */
    fun setEnabled(enabled: Boolean) {
        TODO("packet B2")
    }

    /** Begin platform device selection; result is reflected in [connectionState]. */
    fun beginDeviceSelection() {
        TODO("packet B2")
    }

    /** Forget the selected watch: clears association and revokes all authority. */
    fun forgetDevice() {
        TODO("packet B2")
    }

    /** Arm control for the current prepared workout only (fresh epoch). */
    fun arm() {
        TODO("packet B2")
    }

    /** Disarm: revokes future authority; already-required teardown is allowed to finish. */
    fun disarm() {
        TODO("packet B2")
    }

    /** Owner disposal: unregisters listeners, drops late callbacks (owner generation), revokes authority. */
    fun dispose() {
        TODO("packet B2")
    }
}
