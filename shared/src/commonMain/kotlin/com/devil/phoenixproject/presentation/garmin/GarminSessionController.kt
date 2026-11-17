package com.devil.phoenixproject.presentation.garmin

import com.devil.phoenixproject.data.garmin.GarminCompanionTransport
import com.devil.phoenixproject.data.garmin.GarminControlPreferences
import com.devil.phoenixproject.data.garmin.GarminDeviceSelectionResult
import com.devil.phoenixproject.data.garmin.GarminInboundEnvelope
import com.devil.phoenixproject.data.garmin.GarminTransportState
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.presentation.manager.DefaultWorkoutSessionManager
import com.devil.phoenixproject.presentation.manager.MachineTeardownState
import com.devil.phoenixproject.presentation.manager.RemoteSetControlTerminal
import com.devil.phoenixproject.presentation.manager.RemotePersistenceOutcome
import com.devil.phoenixproject.presentation.manager.RemoteTeardownState
import com.devil.phoenixproject.presentation.manager.RestTransitionCommand
import com.devil.phoenixproject.presentation.manager.RestTransitionPlan
import com.devil.phoenixproject.presentation.manager.actionIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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
 *
 * Threading: state is confined to the owner dispatcher — the platform transports marshal
 * inbound envelopes and send completions onto it ([GarminCompanionTransport] contract) and UI
 * actions arrive from the main thread. Late native callbacks after [dispose] are dropped via
 * the owner generation.
 *
 * RESULT semantics (signoff decision 2): `ACCEPTED` is admission only. `APPLIED` requires the
 * observed intended state plus teardown Ready plus a commit-path persistence receipt of
 * SUCCEEDED/NOT_REQUIRED whenever a save is involved; `SetSummary` publication is never a
 * receipt. A save still PENDING at the result-window end reports `UNKNOWN_RESULT` and blocks
 * the next watch start until the save resolves; a FAILED commit reports `SAVE_FAILED` and
 * blocks as well.
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

    // ---- Owner generation: late native callbacks after dispose are dropped ----
    private var ownerGeneration = 0L
    private var disposed = false

    // ---- Arming / epochs / revisions (memory-only authority) ----
    private var armed = false
    private var controlEpoch: String = "epoch-0"
    private var epochSeq = 0L
    private var semanticRevision = 0
    private var lastSemanticKey: String? = null

    // ---- Freshness: phone-issued grant + confirmation (volatile, monotonic clock) ----
    private var actionGrant: String? = null
    private var actionGrantIssuedAt = 0L
    private var grantSeq = 0L

    private data class ConfirmationRecord(
        val token: String,
        val controlEpoch: String,
        val contextId: String,
        val revision: Int,
        val exerciseLabel: String,
        val issuedAt: Long,
    )

    private var confirmation: ConfirmationRecord? = null
    private var confirmationSeq = 0L

    // ---- Fail-closed dedupe: reserved before dispatch, complete payload binding ----
    private class DedupeRecord(val fingerprint: String) {
        var latestResult: Map<String, Any?>? = null
    }

    private val dedupe = LinkedHashMap<String, DedupeRecord>()

    // ---- Single serialized admission (no FIFO held across long prep/save) ----
    private class InFlightCommand(val commandId: String, val action: String, val job: Job) {
        var mutationStarted = false
    }

    private var inFlight: InFlightCommand? = null

    // ---- Start block latch: UNKNOWN_RESULT / SAVE_FAILED until the save resolves ----
    private var startBlockReason: String? = null

    private var phoneForeground = true

    /** Transport listeners are registered once per owner generation, paired with [detach]. */
    private var transportAttached = false

    private fun attachTransport() {
        if (disposed || transportAttached) return
        transportAttached = true
        transport.attach(ownerGeneration)
    }

    init {
        scope.launch {
            transport.inbound.collect { envelope -> onInbound(envelope) }
        }
        scope.launch {
            transport.state.collect { onTransportState(it) }
        }
        scope.launch {
            session.coordinator.workoutState.collect { refreshSemanticRevision() }
        }
        scope.launch {
            session.coordinator.routineFlowState.collect { refreshSemanticRevision() }
        }
        scope.launch {
            session.coordinator.workoutParameters.collect { refreshSemanticRevision() }
        }
        scope.launch {
            session.coordinator.workoutSaveFailureOffer.collect { onOfferChanged(it != null) }
        }
        refreshConnectionState()
    }

    // ---- UI actions (packet B2 implements; packet B3 calls) ----

    /** Master opt-in toggle (default OFF). Disabling revokes all authority immediately. */
    fun setEnabled(enabled: Boolean) {
        if (disposed) return
        preferences.enabled = enabled
        if (!enabled) {
            revokeAuthority()
            semanticRevision += 1
            transport.detach()
            transportAttached = false
            refreshConnectionState()
            return
        }
        attachTransport()
        refreshConnectionState()
        pushState()
    }

    /** Begin platform device selection; result is reflected in [connectionState]. */
    fun beginDeviceSelection() {
        if (disposed) return
        val generation = ownerGeneration
        transport.startDeviceSelection { result ->
            if (disposed || generation != ownerGeneration) return@startDeviceSelection
            when (result) {
                is GarminDeviceSelectionResult.Selected -> {
                    val previous = preferences.selectedDeviceRef
                    if (previous != result.device.deviceRef) {
                        // A different watch: every grant/epoch/confirmation dies with the old one.
                        revokeAuthority()
                        preferences.selectedDeviceRef = result.device.deviceRef
                    }
                    refreshConnectionState()
                    pushState()
                }
                is GarminDeviceSelectionResult.Cancelled -> refreshConnectionState()
                is GarminDeviceSelectionResult.Failed -> {
                    _connectionState.value = _connectionState.value.copy(
                        availability = GarminAvailability.ERROR,
                        statusDetailCode = result.code,
                    )
                }
            }
        }
    }

    /** Forget the selected watch: clears association and revokes all authority. */
    fun forgetDevice() {
        if (disposed) return
        revokeAuthority()
        preferences.forgetAssociation()
        refreshConnectionState()
    }

    /** Arm control for the current prepared workout only (fresh epoch). */
    fun arm() {
        if (disposed) return
        if (!preferences.enabled || preferences.watchAppUuid == null || preferences.selectedDeviceRef == null) {
            refreshConnectionState()
            return
        }
        attachTransport()
        if (!watchAppMatched()) {
            refreshConnectionState()
            return
        }
        if (!armableTargetExists()) {
            // Nothing controllable is prepared; arming would mint authority without a target.
            refreshConnectionState()
            return
        }
        rotateEpoch() // fresh epoch per arm
        armed = true
        semanticRevision += 1 // arm is an actionable authority change
        refreshConnectionState()
        pushState()
    }

    /** Disarm: revokes future authority; already-required teardown is allowed to finish. */
    fun disarm() {
        if (disposed) return
        revokeAuthority()
        semanticRevision += 1
        refreshConnectionState()
        pushState()
    }

    /** Owner disposal: unregisters listeners, drops late callbacks (owner generation), revokes authority. */
    fun dispose() {
        if (disposed) return
        disposed = true
        ownerGeneration += 1
        armed = false
        actionGrant = null
        confirmation = null
        dedupe.clear()
        inFlight?.job?.cancel()
        inFlight = null
        transportAttached = false
        transport.detach()
        transport.shutdown()
    }

    // ---- Additive owner hooks (never renames) ----

    /**
     * Authority invalidation from the app owner: logout, account change or profile switch.
     * Revokes all remote authority immediately; restart/reconnect never silently re-arms,
     * so an explicit [arm] with a fresh epoch is required afterwards.
     */
    fun onAuthorityInvalidated() {
        if (disposed) return
        revokeAuthority()
        semanticRevision += 1
        refreshConnectionState()
    }

    /**
     * Foreground signal from the app lifecycle (integration-model §5). Backgrounding
     * invalidates retained pre-mutation gestures and rejects starts ([foregroundRequired]
     * disclosure); foregrounding never drains or auto-replays anything.
     */
    fun setPhoneForeground(foreground: Boolean) {
        if (disposed) return
        phoneForeground = foreground
        if (!foreground) {
            inFlight?.takeIf { !it.mutationStarted }?.job?.cancel()
            inFlight = null
        }
        refreshArmState()
        if (armed) pushState()
    }

    // =====================================================================
    //  Transport events
    // =====================================================================

    private fun onTransportState(state: GarminTransportState) {
        if (disposed) return
        when (state) {
            is GarminTransportState.ShutDown, is GarminTransportState.Initializing,
            is GarminTransportState.ClientUnavailable, is GarminTransportState.Error,
            -> {
                // Restart/SDK shutdown/unavailable while armed: authority dies. A later
                // reconnect never silently re-arms; the next arm mints a fresh epoch.
                if (armed) {
                    revokeAuthority()
                    semanticRevision += 1
                }
            }
            else -> Unit
        }
        refreshConnectionState()
    }

    private fun onInbound(envelope: GarminInboundEnvelope) {
        if (disposed) return // post-disposal callbacks dropped (owner generation)
        val msg = envelope.message

        // Native provenance first — device/app identity is SDK-delivered, never a
        // dictionary claim (integration-model §4).
        val selectedRef = preferences.selectedDeviceRef
        val configuredApp = preferences.watchAppUuid
        if (selectedRef == null || envelope.deviceRef != selectedRef ||
            configuredApp == null || envelope.appUuid != configuredApp
        ) {
            // A wrong device is dropped silently (no leak). The correct device running an
            // unmatched app gets an addressed rejection so it can clear its gesture.
            if (selectedRef != null && envelope.deviceRef == selectedRef) {
                val addressed = extractAddressing(msg)
                if (addressed != null) {
                    val (commandId, epoch, contextId) = addressed
                    val result = GarminProtocol.buildResult(
                        GarminProtocol.ResultMessage(
                            commandId, epoch, contextId,
                            GarminProtocol.STAGE_REJECTED, GarminProtocol.REASON_UNAUTHORIZED_DEVICE,
                        ),
                    )
                    if (result != null) transport.send(envelope.deviceRef, result) { }
                }
            }
            return
        }

        when (msg["type"]) {
            GarminProtocol.TYPE_HELLO -> handleHello(msg, envelope)
            GarminProtocol.TYPE_COMMAND -> handleCommand(msg, envelope)
            else -> Unit // unknown type: redacted drop
        }
    }

    private fun handleHello(msg: Map<String, Any?>, envelope: GarminInboundEnvelope) {
        val hello = GarminProtocol.parseHello(msg) ?: return // unparseable HELLO: redacted drop
        if (!hello.supportedVersions.contains(GarminProtocol.PROTOCOL_VERSION)) {
            refreshConnectionState(errorCode = "unsupported_version")
            return
        }
        // HELLO is handshake only, never Arm. It earns a fresh STATE; when armed the
        // STATE carries a fresh single-gesture grant.
        refreshConnectionState()
        pushState(targetDevice = envelope.deviceRef)
    }

    private fun handleCommand(msg: Map<String, Any?>, envelope: GarminInboundEnvelope) {
        val cmd = GarminProtocol.parseCommand(msg)
        if (cmd == null) {
            // Valid addressing on a malformed payload is answerable; anything else is
            // dropped with redacted diagnostics (never echo arbitrary inbound strings).
            val addressed = extractAddressing(msg)
            if (addressed != null) {
                val (commandId, epoch, contextId) = addressed
                val reason = if (!GarminProtocol.checkMessage(msg)) {
                    GarminProtocol.REASON_PAYLOAD_TOO_LARGE
                } else {
                    GarminProtocol.REASON_INVALID_MESSAGE
                }
                val result = GarminProtocol.buildResult(
                    GarminProtocol.ResultMessage(commandId, epoch, contextId, GarminProtocol.STAGE_REJECTED, reason),
                )
                if (result != null) {
                    transport.send(envelope.deviceRef, result) { }
                    _lastEvent.value = GarminControlEvent.Rejected(commandId, msg["action"] as? String ?: "", reason)
                }
            }
            return
        }
        admit(cmd, envelope)
    }

    /** Bounded, non-echoing extraction of addressing fields from an unparsed message. */
    private fun extractAddressing(msg: Map<String, Any?>): Triple<String, String, String>? {
        val commandId = msg["commandId"] as? String ?: return null
        val epoch = msg["controlEpoch"] as? String ?: return null
        val contextId = msg["contextId"] as? String ?: return null
        if (!GarminProtocol.isValidId(commandId) || !GarminProtocol.isValidId(epoch) || !GarminProtocol.isValidId(contextId)) return null
        return Triple(commandId, epoch, contextId)
    }

    // =====================================================================
    //  Admission (single serialized, fail-closed)
    // =====================================================================

    private fun admit(cmd: GarminProtocol.CommandMessage, envelope: GarminInboundEnvelope) {
        val now = monotonicNowMs()

        if (!preferences.enabled) {
            reject(cmd, GarminProtocol.REASON_DISABLED)
            return
        }
        if (!armed) {
            reject(cmd, GarminProtocol.REASON_NOT_READY)
            return
        }
        if (cmd.controlEpoch != controlEpoch) {
            // Old authority: the press predates an epoch rotation. A fresh press after
            // re-arm is required — never a resubmission.
            reject(cmd, GarminProtocol.REASON_STALE_STATE)
            return
        }

        // Dedupe: reserve (epoch, commandId) BEFORE any dispatch, with complete payload
        // binding. Identical duplicate returns the cached outcome (never re-executes);
        // different payload rejects. Rejected validly addressed admissions are recorded.
        val key = "${cmd.controlEpoch}|${cmd.commandId}"
        val existing = dedupe[key]
        if (existing != null) {
            if (existing.fingerprint == cmd.payloadFingerprint()) {
                val cached = existing.latestResult
                if (cached != null) transport.send(envelope.deviceRef, cached) { }
            } else {
                val conflict = buildResult(cmd, GarminProtocol.STAGE_REJECTED, GarminProtocol.REASON_COMMAND_ID_CONFLICT)
                existing.latestResult = conflict
                transport.send(envelope.deviceRef, conflict) { }
                _lastEvent.value = GarminControlEvent.Rejected(cmd.commandId, cmd.action, GarminProtocol.REASON_COMMAND_ID_CONFLICT)
            }
            return
        }
        if (dedupe.size >= GarminProtocol.DEDUPE_CAPACITY) {
            // At capacity: disarm/rekey and require explicit re-arm. Never evict replay
            // protection and never dispatch the overflowing mutation.
            val capped = buildResult(cmd, GarminProtocol.STAGE_REJECTED, GarminProtocol.REASON_EPOCH_CAP_REACHED)
            transport.send(envelope.deviceRef, capped) { }
            _lastEvent.value = GarminControlEvent.Rejected(cmd.commandId, cmd.action, GarminProtocol.REASON_EPOCH_CAP_REACHED)
            revokeAuthority()
            semanticRevision += 1
            refreshConnectionState()
            pushState()
            return
        }
        val record = DedupeRecord(cmd.payloadFingerprint())
        dedupe[key] = record // reserved before dispatch

        // Freshness: the phone-issued 3 s grant, bound to this epoch.
        val grant = actionGrant
        if (grant == null || grant != cmd.actionGrant || now - actionGrantIssuedAt > GarminProtocol.GRANT_TTL_MS) {
            reject(cmd, GarminProtocol.REASON_STALE_STATE, record = record)
            return
        }
        // Context binding.
        if (cmd.contextId != currentContextId()) {
            reject(cmd, GarminProtocol.REASON_STALE_STATE, record = record)
            return
        }
        // Atomic revision matching for START/CANCEL. STOP tolerates revision drift but
        // only within the same epoch, authorized execution context and unexpired grant.
        if (cmd.action != GarminProtocol.ACTION_STOP_SET && cmd.expectedRevision != semanticRevision) {
            reject(cmd, GarminProtocol.REASON_STALE_STATE, record = record)
            return
        }
        // Confirmation binding (Cancel only): epoch + exercise + context + semantic revision.
        if (cmd.action == GarminProtocol.ACTION_CANCEL_EXERCISE) {
            val c = confirmation
            val valid = c != null && c.token == cmd.confirmationToken &&
                c.controlEpoch == cmd.controlEpoch && c.contextId == cmd.contextId &&
                c.revision == semanticRevision && now - c.issuedAt <= GarminProtocol.CONFIRMATION_TTL_MS
            if (!valid) {
                reject(cmd, GarminProtocol.REASON_CONFIRMATION_EXPIRED, record = record)
                return
            }
        }

        // Action admission (behavioral contract §1).
        if (cmd.action !in allowedActionsNow()) {
            reject(cmd, admissionRejectReason(), record = record)
            return
        }

        // Serialized admission: one outstanding mutation. Urgent STOP_SET pre-empts a
        // pending (pre-mutation) prepare/start/advance; a Stop can never land on a later
        // execution because its dispatch re-anchors on the live execution identity.
        val inflight = inFlight
        if (inflight != null) {
            if (cmd.action == GarminProtocol.ACTION_STOP_SET && !inflight.mutationStarted) {
                inflight.job.cancel()
                // The superseded command can never complete; close it honestly. (A watch
                // that replaced its gesture ignores this RESULT safely.)
                concludeById(inflight.commandId, inflight.action, GarminProtocol.STAGE_REJECTED, GarminProtocol.REASON_STALE_STATE)
                inFlight = null
            } else {
                reject(cmd, GarminProtocol.REASON_BUSY, record = record)
                return
            }
        }

        val accepted = buildResult(cmd, GarminProtocol.STAGE_ACCEPTED, null)
        record.latestResult = accepted
        transport.send(envelope.deviceRef, accepted) { }
        _lastEvent.value = GarminControlEvent.Pending(cmd.commandId, cmd.action)

        val job = scope.launch { execute(cmd, record, envelope.deviceRef) }
        inFlight = InFlightCommand(cmd.commandId, cmd.action, job)
    }

    private fun reject(
        cmd: GarminProtocol.CommandMessage,
        reason: String,
        record: DedupeRecord? = null,
    ) {
        val result = buildResult(cmd, GarminProtocol.STAGE_REJECTED, reason)
        (record ?: dedupe["${cmd.controlEpoch}|${cmd.commandId}"])?.latestResult = result
        val deviceRef = preferences.selectedDeviceRef
        if (deviceRef != null) transport.send(deviceRef, result) { }
        _lastEvent.value = if (reason == GarminProtocol.REASON_STALE_STATE) {
            GarminControlEvent.Stale(cmd.commandId, cmd.action)
        } else {
            GarminControlEvent.Rejected(cmd.commandId, cmd.action, reason)
        }
    }

    private fun concludeById(commandId: String, action: String, stage: String, reason: String?) {
        val deviceRef = preferences.selectedDeviceRef ?: return
        val result = GarminProtocol.buildResult(
            GarminProtocol.ResultMessage(commandId, controlEpoch, currentContextId(), stage, reason),
        ) ?: return
        dedupe["$controlEpoch|$commandId"]?.latestResult = result
        transport.send(deviceRef, result) { }
        _lastEvent.value = eventFor(commandId, action, stage, reason)
    }

    private fun buildResult(cmd: GarminProtocol.CommandMessage, stage: String, reason: String?): Map<String, Any?> {
        val msg = GarminProtocol.buildResult(
            GarminProtocol.ResultMessage(cmd.commandId, cmd.controlEpoch, cmd.contextId, stage, reason),
        )
        return requireNotNull(msg) { "locally built RESULT must satisfy the frozen contract" }
    }

    private fun eventFor(commandId: String, action: String, stage: String, reason: String?): GarminControlEvent = when {
        stage == GarminProtocol.STAGE_APPLIED -> GarminControlEvent.Applied(commandId, action)
        reason == GarminProtocol.REASON_UNKNOWN_RESULT -> GarminControlEvent.UnknownResult(commandId, action)
        reason == GarminProtocol.REASON_STALE_STATE -> GarminControlEvent.Stale(commandId, action)
        else -> GarminControlEvent.Rejected(commandId, action, reason ?: GarminProtocol.REASON_NOT_READY)
    }

    // =====================================================================
    //  Execution (manager-level APIs only)
    // =====================================================================

    // Pre-mutation observation anchors, captured at execute() entry (single in-flight).
    private var observedBeforeWorkout: WorkoutState? = null
    private var observedBeforeFlow: RoutineFlowState? = null

    private suspend fun execute(cmd: GarminProtocol.CommandMessage, record: DedupeRecord, deviceRef: String) {
        observedBeforeWorkout = session.coordinator.workoutState.value
        observedBeforeFlow = session.coordinator.routineFlowState.value
        try {
            when (cmd.action) {
                GarminProtocol.ACTION_START_NEXT -> executeStartNext(cmd, record, deviceRef)
                GarminProtocol.ACTION_STOP_SET -> executeStopSet(cmd, record, deviceRef)
                GarminProtocol.ACTION_CANCEL_EXERCISE -> executeCancel(cmd, record, deviceRef)
                else -> reject(cmd, GarminProtocol.REASON_INVALID_MESSAGE, record)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            conclude(cmd, record, deviceRef, GarminProtocol.STAGE_REJECTED, GarminProtocol.REASON_NOT_READY)
        } finally {
            if (inFlight?.commandId == cmd.commandId) inFlight = null
        }
    }

    private suspend fun executeStartNext(cmd: GarminProtocol.CommandMessage, record: DedupeRecord, deviceRef: String) {
        val coordinator = session.coordinator
        val state = coordinator.workoutState.value
        val routine = coordinator.loadedRoutine.value
        when {
            // Active set: finalize once, await teardown + persistence receipt, prepare
            // the successor WITHOUT starting it. A fresh press starts it.
            state is WorkoutState.Active -> runEndCurrentSet(cmd, record, deviceRef)
            // Persisted summary: proceed once; normal rest may install instead of instant SetReady.
            state is WorkoutState.SetSummary -> {
                markMutationStarted()
                session.proceedFromSummary()
                concludeAfterChange(cmd, record, deviceRef)
            }
            // Resting: identity-bound applyRestTransition only; no forced timer/index
            // mutation, no implicit drop-set answer, no chained summary→rest→start.
            state is WorkoutState.Resting -> runRestTransition(cmd, record, deviceRef)
            // Routine SetReady + Idle: identical to phone START (skipCountdown = true
            // lives inside RoutineFlowManager — no remote-only countdown exists).
            state is WorkoutState.Idle && routine != null &&
                coordinator.routineFlowState.value is RoutineFlowState.SetReady -> {
                markMutationStarted()
                session.startSetFromReady()
                concludeAfterChange(cmd, record, deviceRef)
            }
            // Standalone JustLift prepared + Idle (the watch never prepares anything).
            // Preparation clears selectedExerciseId (engine selection clearing), so the
            // prepared marker is the isJustLift parameter flag.
            state is WorkoutState.Idle && routine == null && justLiftPrepared() -> {
                markMutationStarted()
                session.startWorkout(skipCountdown = false, isJustLiftMode = true)
                concludeAfterChange(cmd, record, deviceRef)
            }
            else -> reject(cmd, GarminProtocol.REASON_NOT_READY, record)
        }
    }

    private suspend fun executeStopSet(cmd: GarminProtocol.CommandMessage, record: DedupeRecord, deviceRef: String) {
        val state = session.coordinator.workoutState.value
        if (state is WorkoutState.Active) {
            // Phone Stop-Set semantics live inside the seam's completion attribution
            // (stopAndReturnToSetReady, Issue #320); the typed receipt contract applies.
            runEndCurrentSet(cmd, record, deviceRef)
        } else {
            reject(cmd, GarminProtocol.REASON_NOT_READY, record)
        }
    }

    private suspend fun executeCancel(cmd: GarminProtocol.CommandMessage, record: DedupeRecord, deviceRef: String) {
        val coordinator = session.coordinator
        val routine = coordinator.loadedRoutine.value
        // The confirmation is single-use per semantic state.
        confirmation = null
        markMutationStarted()
        if (routine != null) {
            // Loaded real routine: skip the NAMED exercise only (may complete the routine
            // when it is the last). Previously saved sets survive.
            session.stopAndSkipCurrentExercise()
        } else {
            // Single/JustLift: cancel the current exercise by ending the workout.
            session.stopWorkout(exitingWorkout = true)
        }
        // No typed receipt exists on this path; APPLIED requires the observed post-state
        // change plus teardown Ready plus no unresolved commit (conservative mapping).
        concludeAfterChange(cmd, record, deviceRef)
        refreshArmState()
    }

    private suspend fun runRestTransition(cmd: GarminProtocol.CommandMessage, record: DedupeRecord, deviceRef: String) {
        val plan = session.restTransitionPlan.value
        when (plan) {
            is RestTransitionPlan.NormalAdvance, is RestTransitionPlan.Declined -> {
                // Identity-bound SkipRest through the reducer: never mutate timers/index.
                markMutationStarted()
                session.applyRestTransition(RestTransitionCommand.SkipRest(plan.actionIdentity()))
                concludeAfterChange(cmd, record, deviceRef)
            }
            // An unresolved drop offer (or an accepted retry in flight) is never answered
            // remotely — no implicit drop-set answer. The phone owns that choice.
            else -> reject(cmd, GarminProtocol.REASON_NOT_READY, record)
        }
    }

    private suspend fun runEndCurrentSet(cmd: GarminProtocol.CommandMessage, record: DedupeRecord, deviceRef: String) {
        val identity = session.currentRemoteExecutionIdentityOrNull()
        if (identity == null) {
            reject(cmd, GarminProtocol.REASON_NOT_READY, record)
            return
        }
        markMutationStarted()
        val receipt = session.endCurrentSetAndPrepareNext(identity)
        val persistence = receipt.persistence
        val terminal = receipt.terminal
        when {
            // APPLIED never precedes a SUCCEEDED/NOT_REQUIRED commit-path receipt with
            // teardown Ready and the successor prepared (or the run complete).
            (persistence == RemotePersistenceOutcome.SUCCEEDED || persistence == RemotePersistenceOutcome.NOT_REQUIRED) &&
                receipt.teardownState == RemoteTeardownState.READY &&
                (terminal == RemoteSetControlTerminal.PREPARED || terminal == RemoteSetControlTerminal.COMPLETE) -> {
                conclude(cmd, record, deviceRef, GarminProtocol.STAGE_APPLIED, null)
            }
            // Stuck save at the result-window end: UNKNOWN_RESULT; the next watch Start
            // stays blocked until the save resolves.
            persistence == RemotePersistenceOutcome.PENDING -> {
                startBlockReason = GarminProtocol.REASON_UNKNOWN_RESULT
                conclude(cmd, record, deviceRef, GarminProtocol.STAGE_REJECTED, GarminProtocol.REASON_UNKNOWN_RESULT)
            }
            // Failed commit: SAVE_FAILED; blocks the next start as well.
            persistence == RemotePersistenceOutcome.FAILED -> {
                startBlockReason = GarminProtocol.REASON_SAVE_FAILED
                conclude(cmd, record, deviceRef, GarminProtocol.STAGE_REJECTED, GarminProtocol.REASON_SAVE_FAILED)
            }
            // Authority/context moved underneath the command.
            terminal == RemoteSetControlTerminal.SUPERSEDED -> {
                conclude(cmd, record, deviceRef, GarminProtocol.STAGE_REJECTED, GarminProtocol.REASON_STALE_STATE)
            }
            // The phone requires user input (JustLift selection cleared, bodyweight entry).
            terminal == RemoteSetControlTerminal.PHONE_INPUT_REQUIRED -> {
                conclude(cmd, record, deviceRef, GarminProtocol.STAGE_REJECTED, GarminProtocol.REASON_NOT_READY)
            }
            else -> {
                conclude(cmd, record, deviceRef, GarminProtocol.STAGE_REJECTED, GarminProtocol.REASON_NOT_READY)
            }
        }
    }

    // ---- Conclusion helpers ----

    private fun markMutationStarted() {
        inFlight?.mutationStarted = true
    }

    /** APPLIED only after an observable post-mutation state change inside the result window. */
    private suspend fun concludeAfterChange(
        cmd: GarminProtocol.CommandMessage,
        record: DedupeRecord,
        deviceRef: String,
    ) {
        val coordinator = session.coordinator
        val beforeWorkout = observedBeforeWorkout ?: coordinator.workoutState.value
        val beforeFlow = observedBeforeFlow ?: coordinator.routineFlowState.value
        val changed = withTimeoutOrNull(GarminProtocol.RESULT_TIMEOUT_MS) {
            kotlinx.coroutines.flow.combine(
                coordinator.workoutState,
                coordinator.routineFlowState,
            ) { workout, flow -> workout to flow }
                .first { (workout, flow) -> workout != beforeWorkout || flow != beforeFlow }
        }
        if (changed != null) {
            conclude(cmd, record, deviceRef, GarminProtocol.STAGE_APPLIED, null)
        } else {
            // Never claim APPLIED on unobserved state. The outcome is unknown; block the
            // next start so a stale double-start cannot slip through.
            startBlockReason = GarminProtocol.REASON_UNKNOWN_RESULT
            conclude(cmd, record, deviceRef, GarminProtocol.STAGE_REJECTED, GarminProtocol.REASON_UNKNOWN_RESULT)
        }
    }

    private fun conclude(
        cmd: GarminProtocol.CommandMessage,
        record: DedupeRecord,
        deviceRef: String,
        stage: String,
        reason: String?,
    ) {
        val result = buildResult(cmd, stage, reason)
        record.latestResult = result
        transport.send(deviceRef, result) { }
        _lastEvent.value = eventFor(cmd.commandId, cmd.action, stage, reason)
    }

    // =====================================================================
    //  Authority / freshness / semantics
    // =====================================================================

    /** Revokes all remote authority. In-flight pre-mutation work is superseded; required teardown may finish. */
    private fun revokeAuthority() {
        armed = false
        rotateEpoch()
        inFlight?.takeIf { !it.mutationStarted }?.job?.cancel()
        inFlight = null
    }

    private fun rotateEpoch() {
        epochSeq += 1
        controlEpoch = "epoch-$epochSeq"
        actionGrant = null
        confirmation = null
        dedupe.clear()
    }

    private fun grantExpired(now: Long = monotonicNowMs()): Boolean =
        actionGrant == null || now - actionGrantIssuedAt > GarminProtocol.GRANT_TTL_MS

    private fun issueGrant() {
        grantSeq += 1
        actionGrant = "grant-$grantSeq"
        actionGrantIssuedAt = monotonicNowMs()
    }

    /**
     * Semantic revision: changes on actionable state/configuration/confirmation
     * transitions, never on telemetry ticks. A bounded discrete tuple only.
     */
    private fun refreshSemanticRevision() {
        if (disposed) return
        val coordinator = session.coordinator
        val key = listOf(
            currentContextId(),
            coordinator.workoutState.value::class.simpleName ?: "?",
            coordinator.routineFlowState.value::class.simpleName ?: "?",
            coordinator.currentExerciseIndex.value.toString(),
            coordinator.currentSetIndex.value.toString(),
            coordinator.workoutParameters.value.selectedExerciseId ?: "",
        ).joinToString("|")
        if (key != lastSemanticKey) {
            val hadKey = lastSemanticKey != null
            lastSemanticKey = key
            if (hadKey) {
                semanticRevision += 1
                // Progression/configuration invalidates the pending confirmation binding.
                confirmation = null
            }
            // A stuck-save block lifts once completion settles into a stable post-state
            // with no failure offer showing (the save resolved — that is the only
            // unblock trigger; re-arm never bypasses an unresolved save).
            if (startBlockReason == GarminProtocol.REASON_UNKNOWN_RESULT &&
                session.coordinator.workoutSaveFailureOffer.value == null
            ) {
                val s = coordinator.workoutState.value
                if (s is WorkoutState.SetSummary || s is WorkoutState.Resting ||
                    s is WorkoutState.Idle || s is WorkoutState.RoutineComplete
                ) {
                    startBlockReason = null
                }
            }
            refreshArmState()
            if (armed) pushState()
        }
    }

    private fun onOfferChanged(present: Boolean) {
        if (disposed) return
        if (!present && startBlockReason == GarminProtocol.REASON_SAVE_FAILED) {
            // The save-failure offer cleared: the failed save was resolved by the user.
            startBlockReason = null
        }
        if (!present && startBlockReason == GarminProtocol.REASON_UNKNOWN_RESULT) {
            // A stuck save settled without a failure offer: completion reached a stable
            // post-state (summary/rest/ready), so the block may lift.
            val state = session.coordinator.workoutState.value
            if (state is WorkoutState.SetSummary || state is WorkoutState.Resting ||
                state is WorkoutState.Idle || state is WorkoutState.RoutineComplete
            ) {
                startBlockReason = null
            }
        }
        refreshArmState()
    }

    private fun currentContextId(): String {
        val routineSession = session.coordinator.currentRoutineSessionId
        if (!routineSession.isNullOrBlank()) return routineSession
        if (session.coordinator.loadedRoutine.value != null) return "routine"
        if (justLiftPrepared()) return "justlift"
        return "none"
    }

    /**
     * Standalone JustLift prepared: `prepareForJustLift()` sets `isJustLift` and clears
     * the exercise selection (selection clearing), so neither marker alone is honest.
     */
    private fun justLiftPrepared(): Boolean {
        val params = session.coordinator.workoutParameters.value
        return params.isJustLift || !params.selectedExerciseId.isNullOrBlank()
    }

    private fun watchAppMatched(): Boolean {
        val device = transport.devices.value.firstOrNull { it.deviceRef == preferences.selectedDeviceRef }
        return preferences.watchAppUuid != null && device?.watchAppInstalled == true
    }

    // =====================================================================
    //  UI state projection (single source of truth for B3)
    // =====================================================================

    private fun refreshConnectionState(errorCode: String? = null) {
        val enabled = preferences.enabled
        val deviceRef = preferences.selectedDeviceRef
        val device = transport.devices.value.firstOrNull { it.deviceRef == deviceRef }
        val matched = watchAppMatched()
        val availability = when {
            !enabled -> GarminAvailability.OFF
            errorCode != null -> GarminAvailability.ERROR
            // Release boundary (signoff correction 7): without a matched production
            // watch-app UUID the card is unavailable — no Install/Arm.
            preferences.watchAppUuid == null -> GarminAvailability.UNAVAILABLE
            deviceRef == null -> GarminAvailability.SETUP
            armed -> GarminAvailability.ARMED
            device != null -> GarminAvailability.CONNECTED
            else -> GarminAvailability.SETUP
        }
        _connectionState.value = GarminConnectionUiState(
            enabled = enabled,
            availability = availability,
            deviceName = device?.name,
            watchAppMatched = matched,
            statusDetailCode = errorCode,
        )
        refreshArmState()
    }

    private fun refreshArmState() {
        val coordinator = session.coordinator
        val state = coordinator.workoutState.value
        val readiness = when {
            !preferences.enabled -> GarminReadiness.DISABLED
            !watchAppMatched() -> GarminReadiness.UNAVAILABLE
            preferences.selectedDeviceRef == null -> GarminReadiness.SETUP
            startBlockReason == GarminProtocol.REASON_SAVE_FAILED -> GarminReadiness.ERROR
            state is WorkoutState.Error -> GarminReadiness.ERROR
            session.machineTeardownState.value !is MachineTeardownState.Ready -> GarminReadiness.BUSY
            state is WorkoutState.BodyweightRepEntry -> GarminReadiness.PHONE_INPUT_REQUIRED
            state is WorkoutState.Paused -> GarminReadiness.PHONE_INPUT_REQUIRED
            state is WorkoutState.Countdown || state is WorkoutState.Initializing -> GarminReadiness.BUSY
            state is WorkoutState.Active -> GarminReadiness.ACTIVE
            state is WorkoutState.Resting -> GarminReadiness.RESTING
            state is WorkoutState.SetSummary -> GarminReadiness.SUMMARY
            armed -> GarminReadiness.ARMED_READY
            else -> GarminReadiness.CONNECTED
        }
        val exerciseName = currentExerciseLabel()
        val allowed = allowedActionsNow()
        _armState.value = GarminArmUiState(
            armed = armed,
            readiness = readiness,
            displayLabel = exerciseName,
            actionLabel = actionLabelFor(state, allowed),
            foregroundRequired = true,
            backgroundStopSupported = false,
            cancelConfirmationLabel = if (GarminProtocol.ACTION_CANCEL_EXERCISE in allowed) exerciseName.take(64) else null,
        )
    }

    private fun currentExerciseLabel(): String {
        val coordinator = session.coordinator
        val routine = coordinator.loadedRoutine.value
        val name = routine?.exercises?.getOrNull(coordinator.currentExerciseIndex.value)?.exercise?.name
        return (name ?: "Workout").take(GarminProtocol.MAX_LABEL_BYTES)
    }

    private fun actionLabelFor(state: WorkoutState, allowed: Set<String>): String = when {
        state is WorkoutState.Active && GarminProtocol.ACTION_START_NEXT in allowed -> "End set; prepare next"
        state is WorkoutState.Active -> "Stop set"
        state is WorkoutState.Resting -> "Continue to rest"
        state is WorkoutState.SetSummary -> "Continue"
        GarminProtocol.ACTION_START_NEXT in allowed -> "Start set"
        GarminProtocol.ACTION_CANCEL_EXERCISE in allowed -> "Cancel exercise"
        else -> ""
    }

    /** True when some workout target is prepared/active, regardless of authority — the [arm] precondition. */
    private fun armableTargetExists(): Boolean {
        val coordinator = session.coordinator
        val state = coordinator.workoutState.value
        val hasTarget = coordinator.loadedRoutine.value != null || justLiftPrepared()
        val teardownReady = session.machineTeardownState.value is MachineTeardownState.Ready
        return teardownReady && phoneForeground &&
            (hasTarget || state is WorkoutState.Active || state is WorkoutState.SetSummary || state is WorkoutState.Resting)
    }

    private fun allowedActionsNow(): Set<String> {
        val coordinator = session.coordinator
        val state = coordinator.workoutState.value
        val routine = coordinator.loadedRoutine.value
        val flowReady = coordinator.routineFlowState.value is RoutineFlowState.SetReady
        val hasTarget = routine != null || justLiftPrepared()
        val teardownReady = session.machineTeardownState.value is MachineTeardownState.Ready
        val blockedBySave = startBlockReason != null
        val actions = mutableSetOf<String>()
        if (armed && teardownReady && !blockedBySave && phoneForeground) {
            when {
                state is WorkoutState.Active -> actions.add(GarminProtocol.ACTION_START_NEXT)
                state is WorkoutState.SetSummary -> actions.add(GarminProtocol.ACTION_START_NEXT)
                state is WorkoutState.Resting -> actions.add(GarminProtocol.ACTION_START_NEXT)
                state is WorkoutState.Idle && routine != null && flowReady -> actions.add(GarminProtocol.ACTION_START_NEXT)
                state is WorkoutState.Idle && routine == null && hasTarget -> actions.add(GarminProtocol.ACTION_START_NEXT)
            }
        }
        if (armed && state is WorkoutState.Active && teardownReady && phoneForeground) {
            actions.add(GarminProtocol.ACTION_STOP_SET)
        }
        if (armed && teardownReady && hasTarget && phoneForeground &&
            (state is WorkoutState.Active || state is WorkoutState.Idle || state is WorkoutState.Resting || state is WorkoutState.SetSummary)
        ) {
            actions.add(GarminProtocol.ACTION_CANCEL_EXERCISE)
        }
        return actions
    }

    private fun admissionRejectReason(): String = when {
        !preferences.enabled -> GarminProtocol.REASON_DISABLED
        startBlockReason == GarminProtocol.REASON_SAVE_FAILED -> GarminProtocol.REASON_SAVE_FAILED
        startBlockReason == GarminProtocol.REASON_UNKNOWN_RESULT -> GarminProtocol.REASON_UNKNOWN_RESULT
        session.machineTeardownState.value !is MachineTeardownState.Ready -> GarminProtocol.REASON_BUSY
        session.coordinator.workoutState.value is WorkoutState.BodyweightRepEntry -> GarminProtocol.REASON_NOT_READY
        session.coordinator.workoutState.value is WorkoutState.Error -> GarminProtocol.REASON_RECOVERY_REQUIRED
        else -> GarminProtocol.REASON_NOT_READY
    }

    // ---- STATE emission ----

    /**
     * Builds and sends the current STATE. When armed, each STATE carries a fresh-enough
     * single-gesture grant (3 s) and, when cancel is admissible, a confirmation token
     * bound to epoch + context + semantic revision (10 s, display-only label).
     */
    private fun pushState(targetDevice: String? = null) {
        val deviceRef = targetDevice ?: preferences.selectedDeviceRef ?: return
        val epoch = controlEpoch
        val contextId = currentContextId()
        if (armed && grantExpired()) issueGrant()
        val allowed = allowedActionsNow()
        if (armed && confirmation == null && GarminProtocol.ACTION_CANCEL_EXERCISE in allowed) {
            confirmationSeq += 1
            confirmation = ConfirmationRecord(
                token = "confirm-$confirmationSeq",
                controlEpoch = epoch,
                contextId = contextId,
                revision = semanticRevision,
                exerciseLabel = currentExerciseLabel().take(64),
                issuedAt = monotonicNowMs(),
            )
        }
        val confirmationNow = confirmation
        val msg = GarminProtocol.buildState(
            GarminProtocol.StateMessage(
                controlEpoch = epoch,
                revision = semanticRevision,
                contextId = contextId,
                allowedActions = allowed.toList(),
                readiness = wireReadiness(),
                phoneForeground = phoneForeground,
                foregroundRequired = true,
                backgroundStopSupported = false,
                displayLabel = currentExerciseLabel(),
                actionLabel = actionLabelFor(session.coordinator.workoutState.value, allowed),
                actionGrant = if (armed) actionGrant else null,
                grantTtlMs = if (armed) GarminProtocol.GRANT_TTL_MS.toInt() else null,
                confirmationToken = confirmationNow?.token,
                confirmationTtlMs = if (confirmationNow != null) GarminProtocol.CONFIRMATION_TTL_MS.toInt() else null,
                confirmationLabel = confirmationNow?.exerciseLabel,
            ),
        )
        if (msg != null) transport.send(deviceRef, msg) { }
    }

    private fun wireReadiness(): String = when (_armState.value.readiness) {
        GarminReadiness.DISABLED -> GarminProtocol.READY_DISABLED
        GarminReadiness.UNAVAILABLE -> GarminProtocol.READY_UNAVAILABLE
        GarminReadiness.SETUP -> GarminProtocol.READY_SETUP
        GarminReadiness.CONNECTED -> GarminProtocol.READY_CONNECTED
        GarminReadiness.ARMED_READY -> GarminProtocol.READY_ARMED_READY
        GarminReadiness.ACTIVE -> GarminProtocol.READY_ACTIVE
        GarminReadiness.RESTING -> GarminProtocol.READY_RESTING
        GarminReadiness.SUMMARY -> GarminProtocol.READY_SUMMARY
        GarminReadiness.PHONE_INPUT_REQUIRED -> GarminProtocol.READY_PHONE_INPUT_REQUIRED
        GarminReadiness.BUSY -> GarminProtocol.READY_BUSY
        GarminReadiness.ERROR -> GarminProtocol.READY_ERROR
        GarminReadiness.RECOVERY_REQUIRED -> GarminProtocol.READY_RECOVERY_REQUIRED
    }
}
