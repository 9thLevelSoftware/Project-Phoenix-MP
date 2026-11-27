import Toybox.Graphics;
import Toybox.Lang;
import Toybox.WatchUi;

//
// PhoenixView - MINIMAL display only (issue #1391 B7, Phase 1).
//
//   main screen    : current target (displayLabel) + readiness + feedback
//   confirm screen : names the exercise from STATE confirmationLabel, with
//                    the mandated safety copy
//
// The full exercise/set/rest dashboard is Phase 2 and FORBIDDEN here.
// All copy comes from resources/strings/strings.xml; wire-protocol enum
// codes (readiness, REJECTED reason) are shown verbatim by contract.
//
class PhoenixView extends WatchUi.View {
    var mApp;

    function initialize(app) {
        View.initialize();
        mApp = app;
    }

    function onUpdate(dc) {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.clear();
        var w = dc.getWidth();
        var cx = w / 2;

        var machine = mApp.machine();
        if (machine.isConfirmOpen()) {
            drawConfirm(dc, w, cx, machine);
            return;
        }

        // ---- minimal current target + readiness ----
        var st = machine.state();
        if (st == null) {
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText(cx, 30, Graphics.FONT_MEDIUM,
                    loadString(Rez.Strings.StatusWaiting),
                    Graphics.TEXT_JUSTIFY_CENTER);
        } else {
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
            dc.drawText(cx, 30, Graphics.FONT_MEDIUM, st.displayLabel,
                    Graphics.TEXT_JUSTIFY_CENTER);
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText(cx, 74, Graphics.FONT_SMALL, st.readiness,
                    Graphics.TEXT_JUSTIFY_CENTER);
        }

        // ---- mandatory press feedback ----
        var fb = feedbackText();
        if (fb != null) {
            dc.setColor(feedbackColor(), Graphics.COLOR_BLACK);
            dc.drawText(cx, 140, Graphics.FONT_SMALL, fb,
                    Graphics.TEXT_JUSTIFY_CENTER);
        }
    }

    // Cancel confirmation: names the exercise from STATE confirmationLabel.
    function drawConfirm(dc, w, cx, machine) {
        var st = machine.state();
        var label = loadString(Rez.Strings.StatusLabelFallback);
        if (st != null && st.confirmationLabel != null) {
            label = st.confirmationLabel;
        }

        // Title: "Cancel <Exercise>?"
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        var title = loadString(Rez.Strings.ConfirmTitlePrefix) + label
                + loadString(Rez.Strings.ConfirmTitleSuffix);
        dc.drawText(cx, 12, Graphics.FONT_SMALL, title,
                Graphics.TEXT_JUSTIFY_CENTER);

        // Copy: "Skip only <Exercise>, not the whole routine.
        //        Previously saved sets stay saved."
        var copy = loadString(Rez.Strings.ConfirmCopyPrefix) + label
                + loadString(Rez.Strings.ConfirmCopyMid)
                + loadString(Rez.Strings.ConfirmCopySuffix);
        var lines = wrapText(copy, wrapChars(w));
        var y = 42;
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        for (var i = 0; i < lines.size(); i++) {
            dc.drawText(cx, y, Graphics.FONT_XTINY, lines[i],
                    Graphics.TEXT_JUSTIFY_CENTER);
            y += 18;
        }

        // Safety copy - ALWAYS present on the confirmation screen.
        dc.setColor(Graphics.COLOR_RED, Graphics.COLOR_BLACK);
        dc.drawText(cx, y + 6, Graphics.FONT_XTINY,
                loadString(Rez.Strings.ConfirmSafety),
                Graphics.TEXT_JUSTIFY_CENTER);

        // Button hints (bottom-right is ALWAYS Stop and never confirms).
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        dc.drawText(cx, y + 32, Graphics.FONT_XTINY,
                loadString(Rez.Strings.ConfirmHintConfirm),
                Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(cx, y + 50, Graphics.FONT_XTINY,
                loadString(Rez.Strings.ConfirmHintStop),
                Graphics.TEXT_JUSTIFY_CENTER);
        dc.drawText(cx, y + 68, Graphics.FONT_XTINY,
                loadString(Rez.Strings.ConfirmHintDismiss),
                Graphics.TEXT_JUSTIFY_CENTER);
    }

    // ------------------------------------------------------------------
    //  Feedback text (mandatory: pending, applied, stale, UNKNOWN_RESULT,
    //  and the REJECTED reason code)
    // ------------------------------------------------------------------

    function feedbackText() {
        var fb = mApp.feedback();
        if (fb == null) {
            return null;
        }
        if (PhoenixProtocol.FEEDBACK_PENDING.equals(fb)) {
            return loadString(Rez.Strings.FeedbackPending);
        }
        if (PhoenixProtocol.FEEDBACK_APPLIED.equals(fb)) {
            return loadString(Rez.Strings.FeedbackApplied);
        }
        if (PhoenixProtocol.FEEDBACK_STALE.equals(fb)) {
            return loadString(Rez.Strings.FeedbackStale);
        }
        if (PhoenixProtocol.FEEDBACK_UNKNOWN.equals(fb)) {
            return loadString(Rez.Strings.FeedbackUnknown);
        }
        if (PhoenixProtocol.FEEDBACK_REJECTED.equals(fb)) {
            // The REJECTED reason code is shown verbatim per contract.
            return loadString(Rez.Strings.FeedbackRejected) + mApp.feedbackDetail();
        }
        if (PhoenixProtocol.FEEDBACK_BUSY.equals(fb)) {
            return loadString(Rez.Strings.FeedbackBusy);
        }
        if (PhoenixProtocol.FEEDBACK_NOT_AVAILABLE.equals(fb)) {
            return loadString(Rez.Strings.FeedbackNotAvailable);
        }
        if (PhoenixProtocol.FEEDBACK_PHONE_REQUIRED.equals(fb)) {
            return loadString(Rez.Strings.FeedbackPhoneRequired);
        }
        if (PhoenixProtocol.FEEDBACK_INVALID.equals(fb)) {
            return loadString(Rez.Strings.FeedbackInvalid);
        }
        if (PhoenixProtocol.FEEDBACK_LINK_LOST.equals(fb)) {
            return loadString(Rez.Strings.FeedbackLinkLost);
        }
        if (PhoenixProtocol.FEEDBACK_DISMISSED.equals(fb)) {
            return loadString(Rez.Strings.FeedbackDismissed);
        }
        if (PhoenixProtocol.FEEDBACK_ARMED.equals(fb)) {
            return loadString(Rez.Strings.FeedbackArmed);
        }
        if (PhoenixProtocol.FEEDBACK_CONFIRM_EXPIRED.equals(fb)) {
            return loadString(Rez.Strings.FeedbackConfirmExpired);
        }
        return null;
    }

    function feedbackColor() {
        var fb = mApp.feedback();
        if (PhoenixProtocol.FEEDBACK_APPLIED.equals(fb)) {
            return Graphics.COLOR_GREEN;
        }
        if (PhoenixProtocol.FEEDBACK_STALE.equals(fb) || PhoenixProtocol.FEEDBACK_UNKNOWN.equals(fb)
                || PhoenixProtocol.FEEDBACK_REJECTED.equals(fb)
                || PhoenixProtocol.FEEDBACK_LINK_LOST.equals(fb)) {
            return Graphics.COLOR_RED;
        }
        return Graphics.COLOR_YELLOW;
    }

    // ------------------------------------------------------------------
    //  Small helpers
    // ------------------------------------------------------------------

    function loadString(id) {
        return WatchUi.loadResource(id);
    }

    function wrapChars(w) {
        // Rough char budget for FONT_XTINY on round screens.
        return (w - 24) / 7;
    }

    // Word wrap without Lang.String.split (which does not exist in Monkey C).
    function wrapText(text, maxChars) {
        if (maxChars < 8) {
            maxChars = 8;
        }
        var lines = [];
        var line = "";
        var word = "";
        var chars = text.toCharArray();
        for (var i = 0; i < chars.size(); i++) {
            var c = chars[i];
            if (c.toNumber() == 32) {
                if (word.length() > 0) {
                    if (line.length() == 0) {
                        line = word;
                    } else if ((line.length() + word.length() + 1) > maxChars) {
                        lines.add(line);
                        line = word;
                    } else {
                        line = line + " " + word;
                    }
                    word = "";
                }
            } else {
                word = word + c.toString();
            }
        }
        if (word.length() > 0) {
            if (line.length() == 0) {
                line = word;
            } else if ((line.length() + word.length() + 1) > maxChars) {
                lines.add(line);
                line = word;
            } else {
                line = line + " " + word;
            }
        }
        if (line.length() > 0) {
            lines.add(line);
        }
        return lines;
    }
}
