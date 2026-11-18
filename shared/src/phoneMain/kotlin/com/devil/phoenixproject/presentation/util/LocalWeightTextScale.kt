package com.devil.phoenixproject.presentation.util

import androidx.compose.runtime.compositionLocalOf
import com.devil.phoenixproject.domain.model.WeightTextScale

/**
 * Issue #1367: ambient weight-text-size preference for the phone app.
 *
 * Provided once in `EnhancedMainScreen` from the global (device-local) preference.
 * The safe default is [WeightTextScale.STANDARD], so previews, tests, and any
 * composition outside the app shell render unchanged base typography.
 */
val LocalWeightTextScale = compositionLocalOf { WeightTextScale.STANDARD }
