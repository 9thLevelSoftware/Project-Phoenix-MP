package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Canonical size tiers for indeterminate loading spinners.
 *
 * Pick the tier that matches the layout context — do not introduce raw dp sizes.
 */
enum class LoadingIndicatorSize(val dp: Dp, val stroke: Dp) {
    /** 16 dp — inside buttons, icon rows, and other compact inline controls. */
    Small(16.dp, 2.dp),

    /** 24 dp — inside content-area loaders such as card bodies and list sections. */
    Medium(24.dp, 2.5.dp),

    /** 48 dp — full-screen or overlay blocking states. */
    Large(48.dp, 4.dp),
}

/**
 * Indeterminate circular progress spinner using the canonical size tiers.
 *
 * Do NOT use for determinate progress (e.g. score gauges or data displays).
 * Those should call [CircularProgressIndicator] directly with an explicit `progress` parameter.
 *
 * @param size     One of [LoadingIndicatorSize.Small], [Medium][LoadingIndicatorSize.Medium],
 *                 or [Large][LoadingIndicatorSize.Large]. Defaults to Medium.
 * @param modifier Applied to the spinner's outer bounding box (position, padding, etc.).
 * @param color    Spinner arc color. Defaults to the Material 3 primary color.
 */
@Composable
fun LoadingIndicator(
    size: LoadingIndicatorSize = LoadingIndicatorSize.Medium,
    modifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.circularColor,
) {
    CircularProgressIndicator(
        modifier = modifier.size(size.dp),
        strokeWidth = size.stroke,
        color = color,
    )
}
