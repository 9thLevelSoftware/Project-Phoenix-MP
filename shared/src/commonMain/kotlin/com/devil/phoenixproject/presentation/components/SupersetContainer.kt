package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.ui.theme.ExpressiveMotion
import com.devil.phoenixproject.ui.theme.SupersetTheme

/**
 * Container component that wraps a superset header and its exercises
 * with a colored left border stripe for visual grouping.
 *
 * @param isDragging When true (the item is being dragged during reorder), the stripe color
 *   fades to alpha 0.8f via [ExpressiveMotion.SpringBouncyColor]; reduceMotion snaps.
 *   The drag owner (RoutineEditorScreen) threads this from ReorderableItem's isDragging lambda.
 */
@Composable
fun SupersetContainer(
    colorIndex: Int,
    modifier: Modifier = Modifier,
    isDragging: Boolean = false,
    content: @Composable () -> Unit,
) {
    val baseColor = SupersetTheme.colorForIndex(colorIndex)
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion
    val stripeColor by animateColorAsState(
        targetValue = if (isDragging) baseColor.copy(alpha = 0.8f) else baseColor,
        animationSpec = if (reduceMotion) snap() else ExpressiveMotion.SpringBouncyColor,
        label = "supersetStripeColor",
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        // Colored left border stripe — vertical gradient fades toward the bottom for
        // a brand-fire energy effect (supersets-bulk-edit-15).
        // Animates to alpha 0.8f while dragging to signal active reorder state.
        Box(
            modifier = Modifier
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.verticalGradient(listOf(stripeColor, stripeColor.copy(alpha = 0.3f)))),
        )

        // Content (header + exercises)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        ) {
            content()
        }
    }
}
