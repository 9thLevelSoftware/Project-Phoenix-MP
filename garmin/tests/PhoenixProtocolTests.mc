import Toybox.Lang;
import Toybox.Test;
import Toybox.WatchUi;

//
// PhoenixProtocolTests - Run No Evil simulator checks for issue #1391 B7.
//
// Built with `monkeyc ... -t` and run per allowlisted product with
// `monkeydo <prg> <device> -t` (see garmin/tests/run-simulator-tests.sh).
// They pin the frozen v1 wire contract and the button/feedback behaviour:
//   - HELLO handshake shape (never Arm, no action fields)
//   - COMMAND shape incl. mandatory confirmationToken for CANCEL_EXERCISE
//   - message limits (2048 bytes / 24 keys / 2 levels / 3 array elements /
//     64-byte ids / 80-byte labels / 32-byte enums)
//   - STATE / RESULT parsing incl. REJECTED reason enum rules
//   - five-button mapping (START/Next, urgent Stop, Cancel open, dismiss,
//     LIGHT not intercepted)
//   - one action per press, no key-repeat double-send
//   - mandatory feedback: pending / applied / stale / UNKNOWN_RESULT /
//     REJECTED reason code
//   - HELLO only on demand (never periodic), fresh STATE required before a
//     COMMAND, at most ONE retained gesture, timeout -> UNKNOWN_RESULT and a
//     fresh press, stale rejection -> new press (never resend), haptic only
//     after stage=APPLIED
//

// ----------------------------------------------------------------------
//  Builders
// ----------------------------------------------------------------------

function mkStateDict() {
    return {
        "v" => 1,
        "type" => "STATE",
        "controlEpoch" => "epoch-1",
        "revision" => 7,
        "contextId" => "ctx-1",
        "allowedActions" => ["START_NEXT", "STOP_SET", "CANCEL_EXERCISE"],
        "readiness" => "ARMED_READY",
        "phoneForeground" => true,
        "foregroundRequired" => true,
        "backgroundStopSupported" => false,
        "displayLabel" => "Bench Press",
        "actionLabel" => "Start set",
        "actionGrant" => "grant-1",
        "grantTtlMs" => 3000,
        "confirmationToken" => "token-1",
        "confirmationTtlMs" => 10000,
        "confirmationLabel" => "Bench Press"
    };
}

function mkResultDict(commandId, stage, reason) {
    var d = {
        "v" => 1,
        "type" => "RESULT",
        "commandId" => commandId,
        "controlEpoch" => "epoch-1",
        "contextId" => "ctx-1",
        "stage" => stage
    };
    if (reason != null) {
        d["reason"] = reason;
    }
    return d;
}

function mkMachineWithState(now) {
    var m = new PhoenixProtocol.ControlMachine("w1-1");
    var st = PhoenixProtocol.parseState(mkStateDict());
    m.onState(st, now);
    return m;
}

function asciiString(n) {
    var s = "";
    for (var i = 0; i < n; i++) {
        s = s + "a";
    }
    return s;
}

// ----------------------------------------------------------------------
//  HELLO contract (handshake only, never Arm)
// ----------------------------------------------------------------------

(:test)
function testHelloContract(logger) as Boolean {
    var hello = PhoenixProtocol.buildHello("w1763400000-42-1");
    Test.assert(hello != null);
    Test.assertEqual(hello["v"], 1);
    Test.assertEqual(hello["type"], "HELLO");
    Test.assertEqual(hello["watchInstanceId"], "w1763400000-42-1");
    var versions = hello["supportedVersions"];
    Test.assertEqual(versions.size(), 1);
    Test.assertEqual(versions[0], 1);
    // Handshake only: no action, no grant, no Arm fields whatsoever.
    Test.assert(!hello.hasKey("action"));
    Test.assert(!hello.hasKey("actionGrant"));
    Test.assert(!hello.hasKey("commandId"));
    Test.assertEqual(hello.keys().size(), 4);
    // Invalid instance ids are refused (64 ASCII bytes max).
    Test.assert(PhoenixProtocol.buildHello(asciiString(65)) == null);
    Test.assert(PhoenixProtocol.buildHello("") == null);
    return true;
}

// ----------------------------------------------------------------------
//  COMMAND contract
// ----------------------------------------------------------------------

(:test)
function testCommandContract(logger) as Boolean {
    var cmd = PhoenixProtocol.buildCommand("epoch-1", "w1-1-1", "ctx-1", 7,
            "START_NEXT", "grant-1", null);
    Test.assert(cmd != null);
    Test.assertEqual(cmd["v"], 1);
    Test.assertEqual(cmd["type"], "COMMAND");
    Test.assertEqual(cmd["controlEpoch"], "epoch-1");
    Test.assertEqual(cmd["commandId"], "w1-1-1");
    Test.assertEqual(cmd["contextId"], "ctx-1");
    Test.assertEqual(cmd["expectedRevision"], 7);
    Test.assertEqual(cmd["action"], "START_NEXT");
    Test.assertEqual(cmd["actionGrant"], "grant-1");
    Test.assert(!cmd.hasKey("confirmationToken"));
    Test.assertEqual(cmd.keys().size(), 8);

    // CANCEL_EXERCISE requires confirmationToken.
    Test.assert(PhoenixProtocol.buildCommand("epoch-1", "w1-1-2", "ctx-1", 7,
            "CANCEL_EXERCISE", "grant-1", null) == null);
    var cancel = PhoenixProtocol.buildCommand("epoch-1", "w1-1-2", "ctx-1", 7,
            "CANCEL_EXERCISE", "grant-1", "token-1");
    Test.assert(cancel != null);
    Test.assertEqual(cancel["action"], "CANCEL_EXERCISE");
    Test.assertEqual(cancel["confirmationToken"], "token-1");

    // Tokens ride ONLY on confirmed Cancel.
    Test.assert(PhoenixProtocol.buildCommand("epoch-1", "w1-1-3", "ctx-1", 7,
            "STOP_SET", "grant-1", "token-1") == null);

    // Ids/grants/tokens <= 64 ASCII bytes.
    Test.assert(PhoenixProtocol.buildCommand("epoch-1", asciiString(65),
            "ctx-1", 7, "START_NEXT", "grant-1", null) == null);
    Test.assert(PhoenixProtocol.buildCommand("epoch-1", "w1-1-4", "ctx-1", 7,
            "START_NEXT", asciiString(65), null) == null);

    // Actions are the exact enum set only.
    Test.assert(PhoenixProtocol.buildCommand("epoch-1", "w1-1-5", "ctx-1", 7,
            "ARM", "grant-1", null) == null);
    // expectedRevision must be an int.
    Test.assert(PhoenixProtocol.buildCommand("epoch-1", "w1-1-6", "ctx-1", "7",
            "START_NEXT", "grant-1", null) == null);
    return true;
}

// ----------------------------------------------------------------------
//  Message limits (the full pre-send gate)
// ----------------------------------------------------------------------

(:test)
function testMessageLimits(logger) as Boolean {
    // 24 dictionary keys max.
    var big = {};
    for (var i = 0; i < 25; i++) {
        big["k" + i.toString()] = "v";
    }
    Test.assert(!PhoenixProtocol.hasValidContainers(big));

    // 3 array elements max.
    var arr = {"v" => 1, "type" => "HELLO", "xs" => [1, 2, 3]};
    Test.assert(PhoenixProtocol.hasValidContainers(arr));
    arr["xs"] = [1, 2, 3, 4];
    Test.assert(!PhoenixProtocol.hasValidContainers(arr));

    // 2 container levels max: no nested dicts, no nested arrays.
    Test.assert(!PhoenixProtocol.hasValidContainers({"a" => {"b" => 1}}));
    Test.assert(!PhoenixProtocol.hasValidContainers({"a" => [[1]]}));

    // 2048 encoded bytes max.
    var fat = {"v" => 1, "type" => "COMMAND"};
    for (var j = 0; j < 22; j++) {
        fat["k" + j.toString()] = asciiString(100);
    }
    Test.assert(PhoenixProtocol.hasValidContainers(fat));
    Test.assert(!PhoenixProtocol.checkMessage(fat));

    // ids <= 64 ASCII bytes; labels <= 80 UTF-8 bytes; enums <= 32 bytes.
    Test.assert(PhoenixProtocol.isValidId(asciiString(64)));
    Test.assert(!PhoenixProtocol.isValidId(asciiString(65)));
    Test.assert(PhoenixProtocol.isValidLabel(asciiString(80)));
    Test.assert(!PhoenixProtocol.isValidLabel(asciiString(81)));
    Test.assert(PhoenixProtocol.isValidEnum(asciiString(32)));
    Test.assert(!PhoenixProtocol.isValidEnum(asciiString(33)));
    return true;
}

// ----------------------------------------------------------------------
//  STATE parsing
// ----------------------------------------------------------------------

(:test)
function testStateParse(logger) as Boolean {
    var st = PhoenixProtocol.parseState(mkStateDict());
    Test.assert(st != null);
    Test.assertEqual(st.controlEpoch, "epoch-1");
    Test.assertEqual(st.revision, 7);
    Test.assertEqual(st.contextId, "ctx-1");
    Test.assertEqual(st.readiness, "ARMED_READY");
    Test.assertEqual(st.actionGrant, "grant-1");
    Test.assertEqual(st.grantTtlMs, 3000);
    Test.assertEqual(st.confirmationToken, "token-1");
    Test.assertEqual(st.confirmationLabel, "Bench Press");

    // Readiness must be one of the exact enum values.
    var d = mkStateDict();
    d["readiness"] = "MAYBE_READY";
    Test.assert(PhoenixProtocol.parseState(d) == null);

    // Required fields are required.
    var e = mkStateDict();
    e.remove("displayLabel");
    Test.assert(PhoenixProtocol.parseState(e) == null);

    // allowedActions carry exact action enum strings only.
    var f = mkStateDict();
    f["allowedActions"] = ["START_NEXT", "ARM"];
    Test.assert(PhoenixProtocol.parseState(f) == null);

    // Wrong type is refused.
    Test.assert(PhoenixProtocol.parseState("STATE") == null);
    return true;
}

// ----------------------------------------------------------------------
//  RESULT parsing
// ----------------------------------------------------------------------

(:test)
function testResultParse(logger) as Boolean {
    var ok = PhoenixProtocol.parseResult(
            mkResultDict("w1-1-1", "APPLIED", null));
    Test.assert(ok != null);
    Test.assertEqual(ok.stage, "APPLIED");
    Test.assert(ok.reason == null);

    // REJECTED requires a reason from the exact reason enum.
    Test.assert(PhoenixProtocol.parseResult(
            mkResultDict("w1-1-1", "REJECTED", null)) == null);
    Test.assert(PhoenixProtocol.parseResult(
            mkResultDict("w1-1-1", "REJECTED", "SOMETHING")) == null);
    var rej = PhoenixProtocol.parseResult(
            mkResultDict("w1-1-1", "REJECTED", "STALE_STATE"));
    Test.assert(rej != null);
    Test.assertEqual(rej.reason, "STALE_STATE");

    // Stage enum is closed.
    Test.assert(PhoenixProtocol.parseResult(
            mkResultDict("w1-1-1", "APPROVED", null)) == null);
    return true;
}

// ----------------------------------------------------------------------
//  Button mapping (five-button products, signoff correction 3)
// ----------------------------------------------------------------------

(:test)
function testButtonMapping(logger) as Boolean {
    // top-right START/ENTER -> Start/Next
    Test.assertEqual(PhoenixProtocol.roleForKey(WatchUi.KEY_ENTER),
            PhoenixProtocol.ROLE_START_NEXT);
    Test.assertEqual(PhoenixProtocol.roleForKey(WatchUi.KEY_START),
            PhoenixProtocol.ROLE_START_NEXT);
    // bottom-right BACK/LAP -> urgent STOP_SET
    Test.assertEqual(PhoenixProtocol.roleForKey(WatchUi.KEY_ESC),
            PhoenixProtocol.ROLE_STOP_SET);
    Test.assertEqual(PhoenixProtocol.roleForKey(WatchUi.KEY_LAP),
            PhoenixProtocol.ROLE_STOP_SET);
    // bottom-left DOWN -> open Cancel confirmation
    Test.assertEqual(PhoenixProtocol.roleForKey(WatchUi.KEY_DOWN),
            PhoenixProtocol.ROLE_OPEN_CANCEL);
    // middle-left UP -> dismiss confirmation
    Test.assertEqual(PhoenixProtocol.roleForKey(WatchUi.KEY_UP),
            PhoenixProtocol.ROLE_DISMISS_CANCEL);
    // top-left LIGHT is NOT intercepted (nor MENU)
    Test.assertEqual(PhoenixProtocol.roleForKey(WatchUi.KEY_LIGHT),
            PhoenixProtocol.ROLE_NONE);
    Test.assertEqual(PhoenixProtocol.roleForKey(WatchUi.KEY_MENU),
            PhoenixProtocol.ROLE_NONE);
    return true;
}

// ----------------------------------------------------------------------
//  One action per press, no repeats (PressGuard)
// ----------------------------------------------------------------------

(:test)
function testPressGuardOneActionPerPress(logger) as Boolean {
    var g = new PhoenixProtocol.PressGuard();
    var key = WatchUi.KEY_ENTER;
    // Fresh press edge acts exactly once.
    Test.assert(g.press(key, 0));
    // Key-repeat / hold: suppressed.
    Test.assert(!g.press(key, 50));
    Test.assert(!g.press(key, 150));
    // Release, then a new press acts again (a NEW press, never a resend).
    g.release(key);
    Test.assert(g.press(key, 200));
    g.release(key);
    // Lost release events cannot deadlock a button forever.
    g.press(WatchUi.KEY_ESC, 1000);
    g.sweep(4000, 2000);
    Test.assert(g.press(WatchUi.KEY_ESC, 4100));
    return true;
}

// ----------------------------------------------------------------------
//  Mandatory feedback: pending -> applied (haptic ONLY on APPLIED)
// ----------------------------------------------------------------------

(:test)
function testFeedbackPendingAppliedHaptic(logger) as Boolean {
    var m = mkMachineWithState(0);
    var env = m.press(PhoenixProtocol.ROLE_START_NEXT, 100);
    Test.assertEqual(env["feedback"], PhoenixProtocol.FEEDBACK_PENDING);   // pending text
    Test.assert(env["send"] != null);
    Test.assertEqual(env["send"]["action"], PhoenixProtocol.ACTION_START_NEXT);
    Test.assertEqual(env["haptic"], false);                  // never on press

    var commandId = env["send"]["commandId"];

    // ACCEPTED: still pending, NO haptic.
    var acc = m.onResult(PhoenixProtocol.parseResult(
            mkResultDict(commandId, "ACCEPTED", null)), 200);
    Test.assertEqual(acc["feedback"], PhoenixProtocol.FEEDBACK_PENDING);
    Test.assertEqual(acc["haptic"], false);
    Test.assert(m.isInFlight());

    // APPLIED: applied text + haptic (the ONLY haptic in the app).
    var app = m.onResult(PhoenixProtocol.parseResult(
            mkResultDict(commandId, "APPLIED", null)), 300);
    Test.assertEqual(app["feedback"], PhoenixProtocol.FEEDBACK_APPLIED);
    Test.assertEqual(app["haptic"], true);
    Test.assert(!m.isInFlight());
    return true;
}

// ----------------------------------------------------------------------
//  Stale rejection: NEW press required, never resend the old command
// ----------------------------------------------------------------------

(:test)
function testStaleRequiresNewPress(logger) as Boolean {
    var m = mkMachineWithState(0);
    var env = m.press(PhoenixProtocol.ROLE_START_NEXT, 100);
    var firstId = env["send"]["commandId"];

    var rej = m.onResult(PhoenixProtocol.parseResult(
            mkResultDict(firstId, "REJECTED", "STALE_STATE")), 200);
    Test.assertEqual(rej["feedback"], PhoenixProtocol.FEEDBACK_STALE);    // stale text
    Test.assertEqual(rej["haptic"], false);                 // no haptic on reject
    Test.assert(!m.isInFlight());

    // Time passing never resubmits by itself (no auto-retry anywhere).
    var ticked = m.tick(1000);
    Test.assert(ticked["send"] == null);
    Test.assert(ticked["feedback"] == null);

    // A NEW press sends a NEW command id.
    var second = m.press(PhoenixProtocol.ROLE_START_NEXT, 1100);
    Test.assert(second["send"] != null);
    Test.assert(!second["send"]["commandId"].equals(firstId));
    return true;
}

// ----------------------------------------------------------------------
//  UNKNOWN_RESULT on timeout, then a fresh press
// ----------------------------------------------------------------------

(:test)
function testUnknownResultTimeout(logger) as Boolean {
    var m = mkMachineWithState(0);
    var env = m.press(PhoenixProtocol.ROLE_STOP_SET, 100);
    Test.assert(env["send"] != null);

    // Still inside the 5 s result timeout: nothing happens, no traffic.
    var early = m.tick(100 + PhoenixProtocol.RESULT_TIMEOUT_MS);
    Test.assert(early["feedback"] == null);
    Test.assert(early["send"] == null);

    // Past the timeout: UNKNOWN_RESULT text, gesture discarded.
    var late = m.tick(100 + PhoenixProtocol.RESULT_TIMEOUT_MS + 1);
    Test.assertEqual(late["feedback"], PhoenixProtocol.FEEDBACK_UNKNOWN);
    Test.assert(!m.isInFlight());

    // Never auto-retry: ticking further sends nothing.
    var after = m.tick(9000);
    Test.assert(after["send"] == null);
    Test.assert(after["feedback"] == null);
    return true;
}

// ----------------------------------------------------------------------
//  HELLO on demand only (no flooding), fresh STATE before COMMAND
// ----------------------------------------------------------------------

(:test)
function testHelloOnDemandNoFlooding(logger) as Boolean {
    var m = new PhoenixProtocol.ControlMachine("w1-1");

    // Gesture with no STATE at all: HELLO, no COMMAND.
    var env = m.press(PhoenixProtocol.ROLE_START_NEXT, 1000);
    Test.assertEqual(env["feedback"], PhoenixProtocol.FEEDBACK_WAITING);
    Test.assert(env["send"] == null);
    Test.assertEqual(env["hello"], true);

    // Rapid presses do not flood HELLO (throttled), and never dispatch.
    var env2 = m.press(PhoenixProtocol.ROLE_START_NEXT, 1100);
    Test.assert(env2["send"] == null);
    Test.assertEqual(env2["hello"], false);

    // Periodic ticks NEVER send HELLO or anything else (no heartbeat).
    for (var t = 2000; t < 10000; t += 250) {
        var ticked = m.tick(t);
        Test.assert(ticked["hello"] == false);
        Test.assert(ticked["send"] == null);
    }

    // The throttle window has passed: the next gesture may HELLO again.
    var env3 = m.press(PhoenixProtocol.ROLE_START_NEXT, 11000);
    Test.assertEqual(env3["hello"], true);
    Test.assert(env3["send"] == null);
    return true;
}

(:test)
function testFreshStateRequiredForCommand(logger) as Boolean {
    // STATE received at t=0 with a 3 s grant.
    var m = mkMachineWithState(0);

    // Grant fresh: dispatches immediately.
    var ok = m.press(PhoenixProtocol.ROLE_START_NEXT, 100);
    Test.assert(ok["send"] != null);

    // Grant expired: NO command; HELLO + waiting text; a NEW press is
    // required after the next STATE - old presses are never replayed.
    var m2 = mkMachineWithState(0);
    var stale = m2.press(PhoenixProtocol.ROLE_START_NEXT, 3001);
    Test.assertEqual(stale["feedback"], PhoenixProtocol.FEEDBACK_WAITING);
    Test.assert(stale["send"] == null);
    Test.assertEqual(stale["hello"], true);

    // A STATE without an actionGrant can never dispatch either.
    var d = mkStateDict();
    d.remove("actionGrant");
    var m3 = new PhoenixProtocol.ControlMachine("w1-1");
    m3.onState(PhoenixProtocol.parseState(d), 0);
    var noGrant = m3.press(PhoenixProtocol.ROLE_START_NEXT, 100);
    Test.assert(noGrant["send"] == null);
    Test.assertEqual(noGrant["feedback"], PhoenixProtocol.FEEDBACK_WAITING);
    return true;
}

// ----------------------------------------------------------------------
//  Cancel confirmation: open / dismiss / confirm with 2nd top-right press
// ----------------------------------------------------------------------

(:test)
function testConfirmDismissFlow(logger) as Boolean {
    var m = mkMachineWithState(0);

    // Bottom-left DOWN opens the confirmation (text feedback).
    var open = m.press(PhoenixProtocol.ROLE_OPEN_CANCEL, 100);
    Test.assertEqual(open["feedback"], PhoenixProtocol.FEEDBACK_ARMED);
    Test.assert(open["send"] == null);
    Test.assert(m.isConfirmOpen());

    // Middle-left UP dismisses it.
    var dismiss = m.press(PhoenixProtocol.ROLE_DISMISS_CANCEL, 200);
    Test.assertEqual(dismiss["feedback"], PhoenixProtocol.FEEDBACK_DISMISSED);
    Test.assert(!m.isConfirmOpen());
    Test.assert(dismiss["send"] == null);
    return true;
}

(:test)
function testConfirmCancelSecondTopRight(logger) as Boolean {
    var m = mkMachineWithState(0);
    m.press(PhoenixProtocol.ROLE_OPEN_CANCEL, 100);
    Test.assert(m.isConfirmOpen());

    // SECOND top-right press confirms Cancel (mandatory pending text).
    var confirm = m.press(PhoenixProtocol.ROLE_START_NEXT, 200);
    Test.assertEqual(confirm["feedback"], PhoenixProtocol.FEEDBACK_PENDING);
    Test.assert(confirm["send"] != null);
    Test.assertEqual(confirm["send"]["action"], PhoenixProtocol.ACTION_CANCEL_EXERCISE);
    // Cancel carries the phone-issued confirmationToken.
    Test.assertEqual(confirm["send"]["confirmationToken"], "token-1");
    return true;
}

(:test)
function testBottomRightIsAlwaysStopNeverConfirms(logger) as Boolean {
    var m = mkMachineWithState(0);
    m.press(PhoenixProtocol.ROLE_OPEN_CANCEL, 100);
    Test.assert(m.isConfirmOpen());

    // Bottom-right during the confirmation: urgent STOP_SET, not Cancel.
    var stop = m.press(PhoenixProtocol.ROLE_STOP_SET, 200);
    Test.assert(stop["send"] != null);
    Test.assertEqual(stop["send"]["action"], PhoenixProtocol.ACTION_STOP_SET);
    Test.assert(!stop["send"].hasKey("confirmationToken"));
    // The confirmation stays a separate decision (one press = one transition).
    Test.assert(m.isConfirmOpen());
    return true;
}

(:test)
function testConfirmationTokenExpiry(logger) as Boolean {
    // A phone that issues a short confirmation TTL with a long grant TTL.
    var d = mkStateDict();
    d["grantTtlMs"] = 30000;
    d["confirmationTtlMs"] = 1000;
    var m = new PhoenixProtocol.ControlMachine("w1-1");
    m.onState(PhoenixProtocol.parseState(d), 0);
    m.press(PhoenixProtocol.ROLE_OPEN_CANCEL, 100);
    var confirm = m.press(PhoenixProtocol.ROLE_START_NEXT, 2000);
    // Expired token: no COMMAND, explicit text, dialog closed (reopen it).
    Test.assertEqual(confirm["feedback"], PhoenixProtocol.FEEDBACK_CONFIRM_EXPIRED);
    Test.assert(confirm["send"] == null);
    Test.assert(!m.isConfirmOpen());
    return true;
}

// ----------------------------------------------------------------------
//  Retention discipline: at most ONE gesture, discard on invalidation
// ----------------------------------------------------------------------

(:test)
function testRetainAtMostOneGesture(logger) as Boolean {
    var m = mkMachineWithState(0);
    var first = m.press(PhoenixProtocol.ROLE_START_NEXT, 100);
    Test.assert(first["send"] != null);

    // Second press while one is in flight: no queueing, no second send.
    var second = m.press(PhoenixProtocol.ROLE_START_NEXT, 200);
    Test.assertEqual(second["feedback"], PhoenixProtocol.FEEDBACK_BUSY);
    Test.assert(second["send"] == null);

    // After the RESULT the next press dispatches again.
    m.onResult(PhoenixProtocol.parseResult(
            mkResultDict(first["send"]["commandId"], "APPLIED", null)), 300);
    var third = m.press(PhoenixProtocol.ROLE_START_NEXT, 400);
    Test.assert(third["send"] != null);
    return true;
}

(:test)
function testContextChangeInvalidatesGestures(logger) as Boolean {
    var m = mkMachineWithState(0);
    m.press(PhoenixProtocol.ROLE_OPEN_CANCEL, 100);
    m.press(PhoenixProtocol.ROLE_START_NEXT, 150);   // in-flight Cancel

    // A STATE with a new contextId invalidates everything retained.
    var d = mkStateDict();
    d["contextId"] = "ctx-2";
    m.onState(PhoenixProtocol.parseState(d), 200);
    Test.assert(!m.isInFlight());
    Test.assert(!m.isConfirmOpen());

    // Same for a new controlEpoch.
    m.press(PhoenixProtocol.ROLE_START_NEXT, 300);
    var e = mkStateDict();
    e["controlEpoch"] = "epoch-2";
    m.onState(PhoenixProtocol.parseState(e), 350);
    Test.assert(!m.isInFlight());
    return true;
}

(:test)
function testDisconnectDiscardsRetainedGestures(logger) as Boolean {
    var m = mkMachineWithState(0);
    var env = m.press(PhoenixProtocol.ROLE_STOP_SET, 100);
    Test.assert(env["send"] != null);

    // Disconnect: retained gestures are gone and nothing may dispatch.
    m.onDisconnect();
    Test.assert(!m.isInFlight());
    var after = m.press(PhoenixProtocol.ROLE_STOP_SET, 200);
    Test.assert(after["send"] == null);
    Test.assertEqual(after["feedback"], PhoenixProtocol.FEEDBACK_WAITING);
    Test.assertEqual(after["hello"], true);
    return true;
}

// ----------------------------------------------------------------------
//  Reason-code text and action/foreground gating
// ----------------------------------------------------------------------

(:test)
function testRejectedReasonCodeSurfaced(logger) as Boolean {
    var m = mkMachineWithState(0);
    var env = m.press(PhoenixProtocol.ROLE_START_NEXT, 100);
    var rej = m.onResult(PhoenixProtocol.parseResult(
            mkResultDict(env["send"]["commandId"], "REJECTED", "NOT_READY")), 200);
    Test.assertEqual(rej["feedback"], PhoenixProtocol.FEEDBACK_REJECTED);
    // The REJECTED reason code is surfaced verbatim for the view.
    Test.assertEqual(rej["detail"], "NOT_READY");
    Test.assertEqual(rej["haptic"], false);
    return true;
}

(:test)
function testAllowedActionsGate(logger) as Boolean {
    var d = mkStateDict();
    d["allowedActions"] = ["STOP_SET"];
    var m = new PhoenixProtocol.ControlMachine("w1-1");
    m.onState(PhoenixProtocol.parseState(d), 0);
    var env = m.press(PhoenixProtocol.ROLE_START_NEXT, 100);
    Test.assertEqual(env["feedback"], PhoenixProtocol.FEEDBACK_NOT_AVAILABLE);
    Test.assert(env["send"] == null);
    return true;
}

(:test)
function testForegroundRequiredGate(logger) as Boolean {
    var d = mkStateDict();
    d["foregroundRequired"] = true;
    d["phoneForeground"] = false;
    var m = new PhoenixProtocol.ControlMachine("w1-1");
    m.onState(PhoenixProtocol.parseState(d), 0);
    var env = m.press(PhoenixProtocol.ROLE_START_NEXT, 100);
    Test.assertEqual(env["feedback"], PhoenixProtocol.FEEDBACK_PHONE_REQUIRED);
    Test.assert(env["send"] == null);
    return true;
}
