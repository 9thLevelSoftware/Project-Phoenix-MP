import Toybox.Application;
import Toybox.Attention;
import Toybox.Communications;
import Toybox.Lang;
import Toybox.System;
import Toybox.Time;
import Toybox.Timer;
import Toybox.WatchUi;

//
// PhoenixApp - issue #1391 packet B7: Garmin watch button controls (Phase 1).
//
// Transport: Connect IQ Mobile SDK FOREGROUND messaging only (no REST).
//   watch -> phone : Communications.transmit(Dictionary, options, listener)
//   phone -> watch : Communications.registerForPhoneAppMessages(...)
// The app must be OPEN during the workout; the phone side is the Phoenix
// phone app speaking the frozen v1 protocol in PhoenixProtocol.mc.
//

// transmit() completion callbacks. Haptic is NOT tied to send success - the
// contract allows a buzz only after RESULT stage=APPLIED.
class PhoenixSendListener extends Communications.ConnectionListener {
    var mApp;

    function initialize(app) {
        ConnectionListener.initialize();
        mApp = app;
    }

    function onComplete() {
        mApp.onSendOutcome(true);
    }

    function onError() {
        mApp.onSendOutcome(false);
    }
}

class PhoenixApp extends Application.AppBase {
    var mMachine;        // PhoenixProtocol.ControlMachine
    var mSendListener;
    var mTimer;
    var mDelegate = null;
    var mFeedback = null;      // PhoenixProtocol.FEEDBACK_* code
    var mFeedbackDetail = null; // REJECTED reason code etc.

    function initialize() {
        AppBase.initialize();
        // watchInstanceId: untrusted metadata (the phone must not authorize
        // anything on it). "w<epochSeconds>-<msSinceBoot>" is unique per run.
        var instanceId = "w" + Time.now().value().toString()
                + "-" + System.getTimer().toString();
        mMachine = new PhoenixProtocol.ControlMachine(instanceId);
        mSendListener = new PhoenixSendListener(self);
    }

    function onStart(state) {
        Communications.registerForPhoneAppMessages(method(:onPhoneMessage));
        if (Communications has :registerForPhoneAppMessageErrors) {
            Communications.registerForPhoneAppMessageErrors(method(:onPhoneMessageError));
        }
        // Initial entry: HELLO once (handshake only, never Arm).
        sendHelloNow();
        // Housekeeping only: the timer NEVER sends traffic (no heartbeat).
        mTimer = new Timer.Timer();
        mTimer.start(method(:onTick), 250, true);
    }

    function onStop(state) {
        // App going to background: discard retained gestures and the
        // confirmation per contract (fresh press required after resume).
        mMachine.onDisconnect();
    }

    function getInitialView() {
        var view = new PhoenixView(self);
        mDelegate = new PhoenixDelegate(self);
        return [view, mDelegate];
    }

    // ------------------------------------------------------------------
    //  Machine glue
    // ------------------------------------------------------------------

    function machine() {
        return mMachine;
    }

    function feedback() {
        return mFeedback;
    }

    function feedbackDetail() {
        return mFeedbackDetail;
    }

    // Button press edge (already de-duplicated by the delegate's PressGuard).
    function handleRole(role) {
        applyEnvelope(mMachine.press(role, System.getTimer()));
    }

    function sweepGuard() {
        if (mDelegate != null) {
            mDelegate.sweep(System.getTimer());
        }
    }

    // Apply a ControlMachine envelope to the transport + display.
    function applyEnvelope(env) {
        if (env == null) {
            return;
        }
        var fb = env["feedback"];
        if (fb != null) {
            mFeedback = fb;
            mFeedbackDetail = env["detail"];
        }
        if (env["send"] != null) {
            transmitMessage(env["send"]);
        }
        if (env["hello"] == true) {
            transmitMessage(mMachine.helloMessage());
        }
        if (env["haptic"] == true) {
            // Contract: haptic ONLY after RESULT stage=APPLIED.
            buzz();
        }
        if (fb != null || env["haptic"] == true) {
            WatchUi.requestUpdate();
        }
    }

    function transmitMessage(msg) {
        if (msg == null) {
            return;
        }
        if (!(Communications has :transmit)) {
            return;
        }
        Communications.transmit(msg, {}, mSendListener);
    }

    function sendHelloNow() {
        var hello = mMachine.helloNow(System.getTimer());
        if (hello != null) {
            transmitMessage(hello);
        }
    }

    function buzz() {
        if (Attention has :vibrate) {
            Attention.vibrate([new Attention.VibeProfile(50, 250)]);
        }
    }

    // ------------------------------------------------------------------
    //  Phone message intake (STATE / RESULT only)
    // ------------------------------------------------------------------

    function onPhoneMessage(message) {
        var data = message.data;
        if (!(data instanceof Dictionary)) {
            return;
        }
        if (!data.hasKey("type") || !(data["type"] instanceof String)) {
            return;
        }
        var now = System.getTimer();
        if (PhoenixProtocol.TYPE_STATE.equals(data["type"])) {
            var st = PhoenixProtocol.parseState(data);
            if (st == null) {
                // Malformed STATE: show invalid feedback, keep nothing.
                mMachine.onDisconnect();
                mFeedback = PhoenixProtocol.FEEDBACK_INVALID;
                mFeedbackDetail = null;
                WatchUi.requestUpdate();
                return;
            }
            mMachine.onState(st, now);
            WatchUi.requestUpdate();
            return;
        }
        if (PhoenixProtocol.TYPE_RESULT.equals(data["type"])) {
            var res = PhoenixProtocol.parseResult(data);
            if (res == null) {
                mFeedback = PhoenixProtocol.FEEDBACK_INVALID;
                mFeedbackDetail = null;
                WatchUi.requestUpdate();
                return;
            }
            applyEnvelope(mMachine.onResult(res, now));
            return;
        }
        // Unknown message types are ignored (forward compatibility).
    }

    function onPhoneMessageError(error) {
        // Link trouble: retained gestures are invalid; show link-lost text.
        // Recovery is user-driven (fresh press sends a throttled HELLO).
        mMachine.onDisconnect();
        mFeedback = PhoenixProtocol.FEEDBACK_LINK_LOST;
        mFeedbackDetail = null;
        WatchUi.requestUpdate();
    }

    function onSendOutcome(ok) {
        if (ok) {
            return;
        }
        // Failed transmit: drop the retained gesture and require a fresh
        // press - the watch must NEVER auto-retry with a new command id.
        mMachine.onDisconnect();
        mFeedback = PhoenixProtocol.FEEDBACK_LINK_LOST;
        mFeedbackDetail = null;
        WatchUi.requestUpdate();
    }

    // ------------------------------------------------------------------
    //  Periodic housekeeping (never network traffic)
    // ------------------------------------------------------------------

    function onTick() {
        var now = System.getTimer();
        var env = mMachine.tick(now);
        if (env["feedback"] != null) {
            applyEnvelope(env);
        }
        sweepGuard();
    }
}
