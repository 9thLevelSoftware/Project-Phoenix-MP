package com.devil.phoenixproject.presentation.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut

/**
 * Standard navigation transition specs.
 *
 * Rule:
 *  - [tabFadeEnter] / [tabFadeExit] — used by bottom-tab-level destinations
 *    (Analytics, SmartInsights, Settings). Fade signals a peer-level switch.
 *  - Drill-down slides are defined inline in NavGraph (slideIntoContainer is an
 *    extension on AnimatedContentTransitionScope and cannot be lifted here), but
 *    all use 300 ms symmetric left/right to stay consistent with [drillTween].
 *
 * Duration convention: fades = 200 ms, slides = 300 ms.
 */
object NavTransitions {
    private val fadeTween get() = tween<Float>(200)

    /** Fade-in for entering a tab-level destination. */
    fun tabFadeEnter() = fadeIn(animationSpec = fadeTween)

    /** Fade-out for exiting a tab-level destination. */
    fun tabFadeExit() = fadeOut(animationSpec = fadeTween)
}
