import Toybox.Lang;
import Toybox.System;
import Toybox.WatchUi;

//
// PhoenixDelegate - five-button input (issue #1391 signoff correction 3).
//
//   top-right    START/ENTER  -> Start/Next action
//   bottom-right BACK/LAP     -> urgent STOP_SET (ALWAYS Stop, never confirms)
//   bottom-left  DOWN         -> open Cancel confirmation
//   middle-left  UP           -> dismiss confirmation
//   top-left     LIGHT        -> NOT intercepted (system handles it)
//
// Press/release is consumed exactly once: the PressGuard eats key-repeat and
// hold double-sends. There is NO long-hold destructive action anywhere.
//
class PhoenixDelegate extends WatchUi.InputDelegate {
    var mApp;
    var mGuard;

    function initialize(app) {
        InputDelegate.initialize();
        mApp = app;
        mGuard = new PhoenixProtocol.PressGuard();
    }

    // Legacy combined press+release callback (some firmwares only use this).
    function onKey(keyEvent) {
        return handleKey(keyEvent);
    }

    // Press edge - the only place an action may fire.
    function onKeyPressed(keyEvent) {
        return handleKey(keyEvent);
    }

    // Release edge - consumed, never actionable (no hold semantics).
    function onKeyReleased(keyEvent) {
        return handleKey(keyEvent);
    }

    function handleKey(keyEvent) {
        var key = keyEvent.getKey();
        var role = PhoenixProtocol.roleForKey(key);
        if (PhoenixProtocol.ROLE_NONE.equals(role)) {
            // Top-left LIGHT and any unmapped key: NOT intercepted.
            return false;
        }
        var t = keyEvent.getType();
        if (t == WatchUi.PRESS_TYPE_UP) {
            mGuard.release(key);
            return true;
        }
        if (t == WatchUi.PRESS_TYPE_DOWN) {
            if (!mGuard.press(key, System.getTimer())) {
                // Key repeat / hold: consume the event, do NOT re-send.
                return true;
            }
            mApp.handleRole(role);
            return true;
        }
        // PRESS_TYPE_ACTION (and anything unexpected): consumed, no action.
        return true;
    }

    // Called from the app's housekeeping timer: drops held keys whose release
    // event was lost, so a button can never deadlock.
    function sweep(now) {
        mGuard.sweep(now, 2000);
    }
}
