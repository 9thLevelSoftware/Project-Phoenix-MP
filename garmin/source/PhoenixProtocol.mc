import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

//
// PhoenixProtocol - frozen v1 wire contract for issue #1391 (packets B2-B7).
//
// The phone side (GarminCompanionTransport et al.) speaks this exact message
// set over the Connect IQ Mobile SDK foreground messaging. Messages are
// Monkey C Dictionaries with STRING keys. There is no REST anywhere.
//
// Common fields on EVERY message:
//   v    : Number  - protocol version, always 1
//   type : String  - "HELLO" | "STATE" | "COMMAND" | "RESULT"
//
// MESSAGE LIMITS (checked before the watch sends anything):
//   <= 2048 encoded bytes per message
//   <= 24 dictionary keys
//   <= 2 container levels (root dict + scalar array)
//   <= 3 array elements
//   ids/grants/tokens <= 64 ASCII bytes
//   labels <= 80 UTF-8 bytes
//   enum strings <= 32 bytes
//
// This module is deliberately transport-free so the Run No Evil tests in
// garmin/tests/PhoenixProtocolTests.mc can exercise it headlessly per device.
//
module PhoenixProtocol {

    const PROTOCOL_VERSION = 1;

    // ---- Message limits (wire contract) ----
    const MAX_MESSAGE_BYTES = 2048;
    const MAX_DICT_KEYS = 24;
    const MAX_CONTAINER_LEVELS = 2;   // root dict + scalar array only
    const MAX_ARRAY_ELEMENTS = 3;
    const MAX_ID_BYTES = 64;          // ids / grants / tokens, ASCII only
    const MAX_LABEL_BYTES = 80;       // UTF-8 bytes
    const MAX_ENUM_BYTES = 32;        // enum strings, UTF-8 bytes

    // ---- Timing (grant / confirmation TTLs are advisory for display; the
    //      phone is authoritative and answers STALE_STATE /
    //      CONFIRMATION_EXPIRED when the watch oversteps) ----
    const GRANT_TTL_MS = 3000;
    const CONFIRMATION_TTL_MS = 10000;
    const RESULT_TIMEOUT_MS = 5000;
    const HELLO_MIN_INTERVAL_MS = 500;  // anti-flooding guard between HELLOs
    const HELLO_NEVER = -1000000;

    // ---- Message types ----
    const TYPE_HELLO = "HELLO";
    const TYPE_STATE = "STATE";
    const TYPE_COMMAND = "COMMAND";
    const TYPE_RESULT = "RESULT";

    // ---- Actions (exact enum strings) ----
    const ACTION_START_NEXT = "START_NEXT";
    const ACTION_STOP_SET = "STOP_SET";
    const ACTION_CANCEL_EXERCISE = "CANCEL_EXERCISE";

    // ---- RESULT stages (exact enum strings) ----
    const STAGE_ACCEPTED = "ACCEPTED";
    const STAGE_APPLIED = "APPLIED";
    const STAGE_REJECTED = "REJECTED";

    // ---- REJECTED reason codes (exact enum strings) ----
    const REASON_STALE_STATE = "STALE_STATE";
    const REASON_NOT_READY = "NOT_READY";
    const REASON_DISCONNECTED = "DISCONNECTED";
    const REASON_UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION";
    const REASON_DISABLED = "DISABLED";
    const REASON_BUSY = "BUSY";
    const REASON_RECOVERY_REQUIRED = "RECOVERY_REQUIRED";
    const REASON_SAVE_FAILED = "SAVE_FAILED";
    const REASON_UNKNOWN_RESULT = "UNKNOWN_RESULT";
    const REASON_INVALID_MESSAGE = "INVALID_MESSAGE";
    const REASON_PAYLOAD_TOO_LARGE = "PAYLOAD_TOO_LARGE";
    const REASON_UNAUTHORIZED_DEVICE = "UNAUTHORIZED_DEVICE";
    const REASON_COMMAND_ID_CONFLICT = "COMMAND_ID_CONFLICT";
    const REASON_CONFIRMATION_EXPIRED = "CONFIRMATION_EXPIRED";
    const REASON_EPOCH_CAP_REACHED = "EPOCH_CAP_REACHED";

    // ---- Readiness enum values (exact enum strings) ----
    const READY_DISABLED = "DISABLED";
    const READY_UNAVAILABLE = "UNAVAILABLE";
    const READY_SETUP = "SETUP";
    const READY_CONNECTED = "CONNECTED";
    const READY_ARMED_READY = "ARMED_READY";
    const READY_ACTIVE = "ACTIVE";
    const READY_RESTING = "RESTING";
    const READY_SUMMARY = "SUMMARY";
    const READY_PHONE_INPUT_REQUIRED = "PHONE_INPUT_REQUIRED";
    const READY_BUSY = "BUSY";
    const READY_ERROR = "ERROR";
    const READY_RECOVERY_REQUIRED = "RECOVERY_REQUIRED";

    // ---- Button roles (five-button layout, signoff correction 3) ----
    // top-right START/ENTER ............ Start/Next (and confirm Cancel)
    // bottom-right BACK/LAP ............ urgent STOP_SET (never confirms)
    // bottom-left DOWN ................. open Cancel confirmation
    // middle-left UP ................... dismiss confirmation
    // top-left LIGHT ................... NOT intercepted
    const ROLE_NONE = "NONE";
    const ROLE_START_NEXT = "START_NEXT";
    const ROLE_STOP_SET = "STOP_SET";
    const ROLE_OPEN_CANCEL = "OPEN_CANCEL";
    const ROLE_DISMISS_CANCEL = "DISMISS_CANCEL";

    // ---- Feedback codes the view renders (strings live in strings.xml) ----
    const FEEDBACK_PENDING = "PENDING";            // mandatory: pending
    const FEEDBACK_APPLIED = "APPLIED";            // mandatory: applied
    const FEEDBACK_STALE = "STALE";                // mandatory: stale
    const FEEDBACK_UNKNOWN = "UNKNOWN_RESULT";     // mandatory: UNKNOWN_RESULT
    const FEEDBACK_REJECTED = "REJECTED";          // mandatory: + reason code
    const FEEDBACK_WAITING = "WAITING";
    const FEEDBACK_BUSY = "BUSY";
    const FEEDBACK_NOT_AVAILABLE = "NOT_AVAILABLE";
    const FEEDBACK_PHONE_REQUIRED = "PHONE_REQUIRED";
    const FEEDBACK_INVALID = "INVALID";
    const FEEDBACK_LINK_LOST = "LINK_LOST";
    const FEEDBACK_DISMISSED = "DISMISSED";
    const FEEDBACK_ARMED = "ARMED";
    const FEEDBACK_CONFIRM_EXPIRED = "CONFIRM_EXPIRED";

    // =====================================================================
    //  Primitive validators
    // =====================================================================

    // UTF-8 encoded byte length of a Monkey C String.
    function utf8Bytes(s) {
        var n = 0;
        var chars = s.toCharArray();
        for (var i = 0; i < chars.size(); i++) {
            var c = chars[i].toNumber();
            if (c < 0x80) {
                n += 1;
            } else if (c < 0x800) {
                n += 2;
            } else if (c < 0x10000) {
                n += 3;
            } else {
                n += 4;
            }
        }
        return n;
    }

    function isAsciiString(s) {
        if (!(s instanceof String)) {
            return false;
        }
        var chars = s.toCharArray();
        for (var i = 0; i < chars.size(); i++) {
            if (chars[i].toNumber() > 0x7F) {
                return false;
            }
        }
        return true;
    }

    // ids / grants / tokens / commandIds: non-empty, <= 64 ASCII bytes.
    function isValidId(s) {
        if (!isAsciiString(s)) {
            return false;
        }
        if (s.length() == 0) {
            return false;
        }
        return utf8Bytes(s) <= MAX_ID_BYTES;
    }

    // Human-readable labels (displayLabel, actionLabel, confirmationLabel).
    function isValidLabel(s) {
        if (!(s instanceof String)) {
            return false;
        }
        return utf8Bytes(s) <= MAX_LABEL_BYTES;
    }

    // Enum strings (actions, readiness, stages, reasons).
    function isValidEnum(s) {
        if (!(s instanceof String)) {
            return false;
        }
        return utf8Bytes(s) <= MAX_ENUM_BYTES;
    }

    function isAction(s) {
        return ACTION_START_NEXT.equals(s)
            || ACTION_STOP_SET.equals(s)
            || ACTION_CANCEL_EXERCISE.equals(s);
    }

    function isReadiness(s) {
        return READY_DISABLED.equals(s)
            || READY_UNAVAILABLE.equals(s)
            || READY_SETUP.equals(s)
            || READY_CONNECTED.equals(s)
            || READY_ARMED_READY.equals(s)
            || READY_ACTIVE.equals(s)
            || READY_RESTING.equals(s)
            || READY_SUMMARY.equals(s)
            || READY_PHONE_INPUT_REQUIRED.equals(s)
            || READY_BUSY.equals(s)
            || READY_ERROR.equals(s)
            || READY_RECOVERY_REQUIRED.equals(s);
    }

    function isStage(s) {
        return STAGE_ACCEPTED.equals(s)
            || STAGE_APPLIED.equals(s)
            || STAGE_REJECTED.equals(s);
    }

    function isReason(s) {
        return REASON_STALE_STATE.equals(s)
            || REASON_NOT_READY.equals(s)
            || REASON_DISCONNECTED.equals(s)
            || REASON_UNSUPPORTED_VERSION.equals(s)
            || REASON_DISABLED.equals(s)
            || REASON_BUSY.equals(s)
            || REASON_RECOVERY_REQUIRED.equals(s)
            || REASON_SAVE_FAILED.equals(s)
            || REASON_UNKNOWN_RESULT.equals(s)
            || REASON_INVALID_MESSAGE.equals(s)
            || REASON_PAYLOAD_TOO_LARGE.equals(s)
            || REASON_UNAUTHORIZED_DEVICE.equals(s)
            || REASON_COMMAND_ID_CONFLICT.equals(s)
            || REASON_CONFIRMATION_EXPIRED.equals(s)
            || REASON_EPOCH_CAP_REACHED.equals(s);
    }

    // =====================================================================
    //  Message limit enforcement (checked before EVERY send)
    // =====================================================================

    // Conservative upper bound of the encoded size of a value. Never
    // underestimates; being fat here only makes the check stricter.
    function estimateBytes(value) {
        if (value instanceof String) {
            return utf8Bytes(value) + 2;
        }
        if (value instanceof Boolean) {
            return 5;
        }
        if (value instanceof Number) {
            return 12;
        }
        if (value instanceof Array) {
            var total = 2;
            for (var i = 0; i < value.size(); i++) {
                total += estimateBytes(value[i]) + 1;
            }
            return total;
        }
        if (value instanceof Dictionary) {
            var total = 2;
            var keys = value.keys();
            for (var i = 0; i < keys.size(); i++) {
                total += estimateBytes(keys[i]) + estimateBytes(value[keys[i]]) + 2;
            }
            return total;
        }
        // Long / Float / Double / Char / unknown: assume fat, fail the
        // check if abused. (Avoids type symbols newer than minSdkVersion.)
        return 26;
    }

    // Container shape: root dictionary only, values are scalars or scalar
    // arrays (<= 3 elements). No nested dictionaries, no nested arrays.
    function hasValidContainers(msg) {
        if (!(msg instanceof Dictionary)) {
            return false;
        }
        var keys = msg.keys();
        if (keys.size() > MAX_DICT_KEYS) {
            return false;
        }
        for (var i = 0; i < keys.size(); i++) {
            var v = msg[keys[i]];
            if (v instanceof Array) {
                if (v.size() > MAX_ARRAY_ELEMENTS) {
                    return false;
                }
                for (var j = 0; j < v.size(); j++) {
                    var e = v[j];
                    if (e instanceof Array || e instanceof Dictionary) {
                        return false;
                    }
                }
            } else if (v instanceof Dictionary) {
                return false;
            }
        }
        return true;
    }

    // The full pre-send gate: shape + size. Every builder funnels through it.
    function checkMessage(msg) {
        if (!hasValidContainers(msg)) {
            return false;
        }
        return estimateBytes(msg) <= MAX_MESSAGE_BYTES;
    }

    // =====================================================================
    //  Builders (watch -> phone)
    // =====================================================================

    // HELLO = {v:1, type:"HELLO", watchInstanceId:string,
    //          supportedVersions:[1]}
    // Handshake only, never Arm. Sent on open/initial entry/reconnect and
    // before any gesture when no fresh grant exists - never periodically.
    function buildHello(watchInstanceId) {
        if (!isValidId(watchInstanceId)) {
            return null;
        }
        var msg = {
            "v" => PROTOCOL_VERSION,
            "type" => TYPE_HELLO,
            "watchInstanceId" => watchInstanceId,
            "supportedVersions" => [PROTOCOL_VERSION]
        };
        if (!checkMessage(msg)) {
            return null;
        }
        return msg;
    }

    // COMMAND = {v:1, type:"COMMAND", controlEpoch:string, commandId:string,
    //            contextId:string, expectedRevision:int, action:string,
    //            actionGrant:string}
    // CANCEL_EXERCISE additionally requires confirmationToken.
    function buildCommand(controlEpoch, commandId, contextId, expectedRevision,
                          action, actionGrant, confirmationToken) {
        if (!isValidId(controlEpoch) || !isValidId(commandId)
                || !isValidId(contextId) || !isValidId(actionGrant)) {
            return null;
        }
        if (!(expectedRevision instanceof Number)) {
            return null;
        }
        if (!isValidEnum(action) || !isAction(action)) {
            return null;
        }
        if (ACTION_CANCEL_EXERCISE.equals(action)) {
            if (!isValidId(confirmationToken)) {
                return null;
            }
        } else if (confirmationToken != null) {
            // Tokens ride ONLY on confirmed Cancel.
            return null;
        }
        var msg = {
            "v" => PROTOCOL_VERSION,
            "type" => TYPE_COMMAND,
            "controlEpoch" => controlEpoch,
            "commandId" => commandId,
            "contextId" => contextId,
            "expectedRevision" => expectedRevision,
            "action" => action,
            "actionGrant" => actionGrant
        };
        if (confirmationToken != null) {
            msg["confirmationToken"] = confirmationToken;
        }
        if (!checkMessage(msg)) {
            return null;
        }
        return msg;
    }

    // =====================================================================
    //  Parsers (phone -> watch)
    // =====================================================================

    // STATE (received) = {v:1, type:"STATE", controlEpoch:string,
    //   revision:int, contextId:string, allowedActions:array<string>,
    //   readiness:string, phoneForeground:bool, foregroundRequired:bool,
    //   backgroundStopSupported:bool, displayLabel:string, actionLabel:string}
    //   optional {actionGrant:string, grantTtlMs:int, confirmationToken:string,
    //             confirmationTtlMs:int, confirmationLabel:string}
    function parseState(data) {
        if (!(data instanceof Dictionary)) {
            return null;
        }
        if (!checkMessage(data)) {
            return null;
        }
        if (!data.hasKey("v") || !(data["v"] instanceof Number)
                || data["v"] != PROTOCOL_VERSION) {
            return null;
        }
        if (!data.hasKey("type") || !(data["type"] instanceof String)
                || !TYPE_STATE.equals(data["type"])) {
            return null;
        }
        if (!data.hasKey("controlEpoch") || !isValidId(data["controlEpoch"])) {
            return null;
        }
        if (!data.hasKey("revision") || !(data["revision"] instanceof Number)) {
            return null;
        }
        if (!data.hasKey("contextId") || !isValidId(data["contextId"])) {
            return null;
        }
        if (!data.hasKey("allowedActions") || !(data["allowedActions"] instanceof Array)) {
            return null;
        }
        var allowed = data["allowedActions"];
        for (var i = 0; i < allowed.size(); i++) {
            if (!isValidEnum(allowed[i]) || !isAction(allowed[i])) {
                return null;
            }
        }
        if (!data.hasKey("readiness") || !isValidEnum(data["readiness"])
                || !isReadiness(data["readiness"])) {
            return null;
        }
        if (!data.hasKey("phoneForeground") || !(data["phoneForeground"] instanceof Boolean)) {
            return null;
        }
        if (!data.hasKey("foregroundRequired") || !(data["foregroundRequired"] instanceof Boolean)) {
            return null;
        }
        if (!data.hasKey("backgroundStopSupported") || !(data["backgroundStopSupported"] instanceof Boolean)) {
            return null;
        }
        if (!data.hasKey("displayLabel") || !isValidLabel(data["displayLabel"])) {
            return null;
        }
        if (!data.hasKey("actionLabel") || !isValidLabel(data["actionLabel"])) {
            return null;
        }

        var st = new State();
        st.controlEpoch = data["controlEpoch"];
        st.revision = data["revision"];
        st.contextId = data["contextId"];
        st.allowedActions = allowed;
        st.readiness = data["readiness"];
        st.phoneForeground = data["phoneForeground"];
        st.foregroundRequired = data["foregroundRequired"];
        st.backgroundStopSupported = data["backgroundStopSupported"];
        st.displayLabel = data["displayLabel"];
        st.actionLabel = data["actionLabel"];

        if (data.hasKey("actionGrant")) {
            if (!isValidId(data["actionGrant"])) {
                return null;
            }
            st.actionGrant = data["actionGrant"];
        }
        if (data.hasKey("grantTtlMs")) {
            if (!(data["grantTtlMs"] instanceof Number) || data["grantTtlMs"] <= 0) {
                return null;
            }
            st.grantTtlMs = data["grantTtlMs"];
        } else {
            st.grantTtlMs = GRANT_TTL_MS;
        }
        if (data.hasKey("confirmationToken")) {
            if (!isValidId(data["confirmationToken"])) {
                return null;
            }
            st.confirmationToken = data["confirmationToken"];
        }
        if (data.hasKey("confirmationTtlMs")) {
            if (!(data["confirmationTtlMs"] instanceof Number) || data["confirmationTtlMs"] <= 0) {
                return null;
            }
            st.confirmationTtlMs = data["confirmationTtlMs"];
        } else {
            st.confirmationTtlMs = CONFIRMATION_TTL_MS;
        }
        if (data.hasKey("confirmationLabel")) {
            if (!isValidLabel(data["confirmationLabel"])) {
                return null;
            }
            st.confirmationLabel = data["confirmationLabel"];
        }
        return st;
    }

    // RESULT (received) = {v:1, type:"RESULT", commandId:string,
    //   controlEpoch:string, contextId:string, stage: ACCEPTED|APPLIED|REJECTED}
    //   + optional reason (REQUIRED for REJECTED; reason enum).
    function parseResult(data) {
        if (!(data instanceof Dictionary)) {
            return null;
        }
        if (!checkMessage(data)) {
            return null;
        }
        if (!data.hasKey("v") || !(data["v"] instanceof Number)
                || data["v"] != PROTOCOL_VERSION) {
            return null;
        }
        if (!data.hasKey("type") || !(data["type"] instanceof String)
                || !TYPE_RESULT.equals(data["type"])) {
            return null;
        }
        if (!data.hasKey("commandId") || !isValidId(data["commandId"])) {
            return null;
        }
        if (!data.hasKey("controlEpoch") || !isValidId(data["controlEpoch"])) {
            return null;
        }
        if (!data.hasKey("contextId") || !isValidId(data["contextId"])) {
            return null;
        }
        if (!data.hasKey("stage") || !isValidEnum(data["stage"])
                || !isStage(data["stage"])) {
            return null;
        }
        var reason = null;
        if (data.hasKey("reason")) {
            if (!isValidEnum(data["reason"]) || !isReason(data["reason"])) {
                return null;
            }
            reason = data["reason"];
        }
        if (STAGE_REJECTED.equals(data["stage"]) && reason == null) {
            // REJECTED without a reason code is invalid per contract.
            return null;
        }

        var res = new Result();
        res.commandId = data["commandId"];
        res.controlEpoch = data["controlEpoch"];
        res.contextId = data["contextId"];
        res.stage = data["stage"];
        res.reason = reason;
        return res;
    }

    // =====================================================================
    //  Button mapping (pure, unit-tested)
    // =====================================================================

    // Maps a five-button physical key to its role. Anything not listed is
    // NOT intercepted (top-left LIGHT included).
    function roleForKey(key) {
        if (key == WatchUi.KEY_ENTER || key == WatchUi.KEY_START) {
            return ROLE_START_NEXT;
        }
        if (key == WatchUi.KEY_ESC || key == WatchUi.KEY_LAP) {
            return ROLE_STOP_SET;
        }
        if (key == WatchUi.KEY_DOWN) {
            return ROLE_OPEN_CANCEL;
        }
        if (key == WatchUi.KEY_UP) {
            return ROLE_DISMISS_CANCEL;
        }
        return ROLE_NONE;
    }

    // =====================================================================
    //  Press guard: exactly one action per press/release, no key repeats
    // =====================================================================

    class PressGuard {
        var mHeld = {};   // key -> monotonic ms when pressed

        // Returns true ONLY on the fresh press edge. Key-repeat and held-key
        // events return false so no press can double-send.
        function press(key, now) {
            if (mHeld.hasKey(key)) {
                return false;
            }
            mHeld[key] = now;
            return true;
        }

        function release(key) {
            if (mHeld.hasKey(key)) {
                mHeld.remove(key);
            }
        }

        // Watchdog: if a release event is lost (some firmwares), drop stale
        // held keys so the button never deadlocks.
        function sweep(now, maxHoldMs) {
            var stale = [];
            var keys = mHeld.keys();
            for (var i = 0; i < keys.size(); i++) {
                if (now - mHeld[keys[i]] > maxHoldMs) {
                    stale.add(keys[i]);
                }
            }
            for (var j = 0; j < stale.size(); j++) {
                mHeld.remove(stale[j]);
            }
        }

        function clear() {
            mHeld = {};
        }
    }

    // =====================================================================
    //  Parsed message carriers
    // =====================================================================

    class State {
        var controlEpoch = null;
        var revision = 0;
        var contextId = null;
        var allowedActions = [];
        var readiness = null;
        var phoneForeground = false;
        var foregroundRequired = false;
        var backgroundStopSupported = false;
        var displayLabel = null;
        var actionLabel = null;
        var actionGrant = null;
        var grantTtlMs = GRANT_TTL_MS;
        var confirmationToken = null;
        var confirmationTtlMs = CONFIRMATION_TTL_MS;
        var confirmationLabel = null;
    }

    class Result {
        var commandId = null;
        var controlEpoch = null;
        var contextId = null;
        var stage = null;
        var reason = null;
    }

    // =====================================================================
    //  ControlMachine - the watch-side protocol state machine.
    //
    //  Transport-free and time-injected so the Run No Evil tests can pin the
    //  contract behaviour: at most ONE retained gesture, retention only
    //  inside the phone-issued grant window and context, HELLO only on
    //  demand (never periodic), stale rejection requires a NEW press,
    //  timeout shows UNKNOWN_RESULT and requires a fresh press, haptic only
    //  after stage=APPLIED.
    // =====================================================================

    class ControlMachine {
        var mInstanceId;          // watch instance id (untrusted metadata)
        var mCounter = 0;         // monotonic command counter
        var mState = null;        // latest parsed State
        var mStateAt = 0;
        var mHelloAt = HELLO_NEVER;
        var mInFlight = null;     // retained gesture dict or null
        var mConfirmOpen = false;

        function initialize(instanceId) {
            mInstanceId = instanceId;
        }

        // ---------------------------------------------------------------
        //  Internals
        // ---------------------------------------------------------------

        function makeCommandId() {
            mCounter += 1;
            return mInstanceId + "-" + mCounter.toString();
        }

        function hasFreshGrant(now) {
            if (mState == null) {
                return false;
            }
            if (mState.actionGrant == null) {
                return false;
            }
            var ttl = mState.grantTtlMs;
            if (ttl == null || ttl <= 0) {
                ttl = GRANT_TTL_MS;
            }
            return (now - mStateAt) <= ttl;
        }

        function hasFreshConfirmation(now) {
            if (mState == null || mState.confirmationToken == null) {
                return false;
            }
            var ttl = mState.confirmationTtlMs;
            if (ttl == null || ttl <= 0) {
                ttl = CONFIRMATION_TTL_MS;
            }
            return (now - mStateAt) <= ttl;
        }

        function allowsAction(action) {
            if (mState == null) {
                return false;
            }
            for (var i = 0; i < mState.allowedActions.size(); i++) {
                if (action.equals(mState.allowedActions[i])) {
                    return true;
                }
            }
            return false;
        }

        // Build the standard machine response envelope.
        function respond(feedback, detail, send, hello, haptic) {
            return {
                "feedback" => feedback,
                "detail" => detail,
                "send" => send,
                "hello" => hello,
                "haptic" => haptic
            };
        }

        function discardGestures() {
            mInFlight = null;
            mConfirmOpen = false;
        }

        // ---------------------------------------------------------------
        //  Inbound events
        // ---------------------------------------------------------------

        // A STATE message was parsed successfully. Invalidation rules: a new
        // controlEpoch or contextId invalidates any retained gesture and the
        // Cancel confirmation; a phone that went to background (when the
        // phone requires foreground) does the same.
        function onState(state, now) {
            var invalidated = false;
            if (mState != null) {
                if (!state.controlEpoch.equals(mState.controlEpoch)
                        || !state.contextId.equals(mState.contextId)) {
                    invalidated = true;
                }
            }
            mState = state;
            mStateAt = now;
            if (invalidated) {
                mInFlight = null;
                mConfirmOpen = false;
            }
            if (state.foregroundRequired && !state.phoneForeground) {
                // Phone background: retained gestures are invalid.
                mInFlight = null;
            }
            return invalidated;
        }

        // A RESULT message was parsed successfully. Returns the envelope;
        // "haptic" is true ONLY for stage=APPLIED (never ACCEPTED, never any
        // rejection). Non-matching command ids are ignored (dedupe/replay
        // protection is PHONE-side; the watch never rewrites history).
        function onResult(result, now) {
            if (mInFlight == null) {
                return respond(null, null, null, false, false);
            }
            if (!result.commandId.equals(mInFlight["commandId"])
                    || !result.controlEpoch.equals(mInFlight["controlEpoch"])) {
                return respond(null, null, null, false, false);
            }

            if (STAGE_ACCEPTED.equals(result.stage)) {
                // Still pending. NO haptic on ACCEPTED.
                return respond(FEEDBACK_PENDING, null, null, false, false);
            }

            var action = mInFlight["action"];
            mInFlight = null;

            if (STAGE_APPLIED.equals(result.stage)) {
                if (ACTION_CANCEL_EXERCISE.equals(action)) {
                    mConfirmOpen = false;
                }
                // Haptic ONLY here.
                return respond(FEEDBACK_APPLIED, null, null, false, true);
            }

            // STAGE_REJECTED: show the reason code, never resubmit. A stale
            // rejection requires a NEW press (never resend the old command).
            if (REASON_STALE_STATE.equals(result.reason)) {
                return respond(FEEDBACK_STALE, result.reason, null, false, false);
            }
            return respond(FEEDBACK_REJECTED, result.reason, null, false, false);
        }

        // Disconnected / watch app backgrounded: discard retained gestures.
        function onDisconnect() {
            mInFlight = null;
            mConfirmOpen = false;
            mState = null;   // no fresh STATE -> nothing may dispatch
            mHelloAt = HELLO_NEVER;
        }

        // ---------------------------------------------------------------
        //  Outbound intents
        // ---------------------------------------------------------------

        // A button press edge was accepted by the PressGuard. Exactly one
        // gesture can be retained at a time, and only inside the grant window.
        function press(role, now) {
            // Cancel confirmation screen semantics.
            if (mConfirmOpen) {
                if (ROLE_DISMISS_CANCEL.equals(role)) {
                    // Middle-left: dismiss confirmation.
                    mConfirmOpen = false;
                    return respond(FEEDBACK_DISMISSED, null, null, false, false);
                }
                if (ROLE_START_NEXT.equals(role)) {
                    // SECOND top-right press: confirm Cancel.
                    return dispatch(ACTION_CANCEL_EXERCISE, now, true);
                }
                if (ROLE_STOP_SET.equals(role)) {
                    // Bottom-right is ALWAYS Stop and never confirms.
                    return dispatch(ACTION_STOP_SET, now, false);
                }
                if (ROLE_OPEN_CANCEL.equals(role)) {
                    // Already open; one press = one transition, nothing else.
                    return respond(FEEDBACK_ARMED, null, null, false, false);
                }
                return respond(null, null, null, false, false);
            }

            if (ROLE_START_NEXT.equals(role)) {
                return dispatch(ACTION_START_NEXT, now, false);
            }
            if (ROLE_STOP_SET.equals(role)) {
                return dispatch(ACTION_STOP_SET, now, false);
            }
            if (ROLE_OPEN_CANCEL.equals(role)) {
                // Bottom-left: open Cancel confirmation.
                mConfirmOpen = true;
                return respond(FEEDBACK_ARMED, null, null, false, false);
            }
            if (ROLE_DISMISS_CANCEL.equals(role)) {
                // Middle-left with no confirmation open: no transition.
                return respond(null, null, null, false, false);
            }
            return respond(null, null, null, false, false);
        }

        // Shared COMMAND dispatch path with the full pre-send gate.
        function dispatch(action, now, needsToken) {
            // Mandatory pending feedback on every action press.
            if (mInFlight != null) {
                // At most ONE retained gesture; never queue a second.
                return respond(FEEDBACK_BUSY, null, null, false, false);
            }
            if (!hasFreshGrant(now)) {
                // No fresh grant: HELLO (throttled) and require a NEW press
                // once STATE arrives - never auto-dispatch from a stale press.
                return respond(FEEDBACK_WAITING, null, null, wantHello(now), false);
            }
            if (mState.foregroundRequired && !mState.phoneForeground) {
                return respond(FEEDBACK_PHONE_REQUIRED, null, null, wantHello(now), false);
            }
            if (!allowsAction(action)) {
                return respond(FEEDBACK_NOT_AVAILABLE, null, null, false, false);
            }
            var token = null;
            if (needsToken) {
                if (!hasFreshConfirmation(now)) {
                    mConfirmOpen = false;
                    return respond(FEEDBACK_CONFIRM_EXPIRED, null, null, false, false);
                }
                token = mState.confirmationToken;
            }

            var commandId = makeCommandId();
            var msg = buildCommand(mState.controlEpoch, commandId,
                                   mState.contextId, mState.revision, action,
                                   mState.actionGrant, token);
            if (msg == null) {
                return respond(FEEDBACK_INVALID, null, null, false, false);
            }
            // Retain the single gesture (same contextId, same grant window).
            mInFlight = {
                "commandId" => commandId,
                "action" => action,
                "controlEpoch" => mState.controlEpoch,
                "contextId" => mState.contextId,
                "sentAt" => now
            };
            return respond(FEEDBACK_PENDING, null, msg, false, false);
        }

        // Throttled HELLO intent (anti-flood: no periodic HELLO ever).
        function wantHello(now) {
            if (now - mHelloAt < HELLO_MIN_INTERVAL_MS) {
                return false;
            }
            mHelloAt = now;
            return true;
        }

        // Force a HELLO (open / initial entry / reconnect).
        function helloNow(now) {
            mHelloAt = now;
            return buildHello(mInstanceId);
        }

        function helloMessage() {
            return buildHello(mInstanceId);
        }

        // Periodic housekeeping. NOTE: this NEVER sends anything - no
        // heartbeat flooding. It only times out a lost RESULT.
        function tick(now) {
            if (mInFlight != null) {
                if (now - mInFlight["sentAt"] > RESULT_TIMEOUT_MS) {
                    mInFlight = null;
                    // Displayed as UNKNOWN_RESULT; requires a fresh press.
                    return respond(FEEDBACK_UNKNOWN, null, null, false, false);
                }
                // Still waiting: no feedback change, no traffic.
                return respond(null, null, null, false, false);
            }
            return respond(null, null, null, false, false);
        }

        // ---------------------------------------------------------------
        //  Introspection (used by the view and the tests)
        // ---------------------------------------------------------------

        function isConfirmOpen() {
            return mConfirmOpen;
        }

        function isInFlight() {
            return mInFlight != null;
        }

        function state() {
            return mState;
        }

        function instanceId() {
            return mInstanceId;
        }
    }
}
