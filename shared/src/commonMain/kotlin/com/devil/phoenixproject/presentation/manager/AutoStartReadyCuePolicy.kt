package com.devil.phoenixproject.presentation.manager

/**
 * Issue #1356: pure gate for the Just Lift / Echo auto-start readiness cue
 * ([com.devil.phoenixproject.domain.model.HapticEvent.AUTO_START_READY]).
 *
 * The cue fires only for an auto-start-origin Just Lift + Echo start with auto-start
 * enabled and beeps enabled. When beeps are off the event is never created at all
 * (no silent haptic-only variant). Manual Just Lift/Echo starts stay silent for this
 * cue because `autoStartReadyCue` is only passed at the auto-start call site.
 */
internal object AutoStartReadyCuePolicy {
    /** Issue #1356: Just Lift / Echo auto-start readiness cue. */
    fun shouldEmit(
        isJustLift: Boolean,
        isEchoMode: Boolean,
        useAutoStart: Boolean,
        beepsEnabled: Boolean,
        autoStartReadyCue: Boolean,
    ): Boolean = isJustLift && isEchoMode && useAutoStart && beepsEnabled && autoStartReadyCue
}
