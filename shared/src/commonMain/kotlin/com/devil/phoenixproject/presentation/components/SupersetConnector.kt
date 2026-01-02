package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.ui.theme.SupersetTheme

/**
 * Draws a vertical connector line between superset exercises.
 * Position determines which ends have rounded caps.
 */
enum class ConnectorPosition {
    TOP,    // Rounded cap at top, line continues down
    MIDDLE, // No caps, line continues both directions
    BOTTOM  // Line from top, rounded cap at bottom
}

/**
 * Vertical connector component that visually links exercises within a superset.
 *
 * Displays a colored vertical line on the left side of exercise cards when they
 * are part of a superset. The position parameter controls the visual endpoints:
 * - TOP: First exercise in superset (cap at top, line continues down)
 * - MIDDLE: Middle exercise (line continues in both directions)
 * - BOTTOM: Last exercise in superset (line from top, cap at bottom)
 *
 * @param colorIndex The superset color index (0-3, cycles if higher)
 * @param position Where this connector appears in the superset sequence
 * @param modifier Optional modifier for the component
 */
@Composable
fun SupersetConnector(
    colorIndex: Int,
    position: ConnectorPosition,
    modifier: Modifier = Modifier
) {
    val color = SupersetTheme.colorForIndex(colorIndex)

    Box(
        modifier = modifier
            .width(24.dp)
            .fillMaxHeight(),
        contentAlignment = Alignment.Center
    ) {
        // Vertical line
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(color)
        )

        // Top cap
        if (position == ConnectorPosition.TOP) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .size(8.dp)
                    .background(color, CircleShape)
            )
        }

        // Bottom cap
        if (position == ConnectorPosition.BOTTOM) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .size(8.dp)
                    .background(color, CircleShape)
            )
        }
    }
}

/**
 * Horizontal connector between drag handle and exercise card.
 *
 * Provides a visual link from the vertical superset connector to the
 * exercise card itself. This creates a clear visual association between
 * the colored superset indicator and the exercise content.
 *
 * @param colorIndex The superset color index (0-3, cycles if higher)
 * @param modifier Optional modifier for the component
 */
@Composable
fun SupersetConnectorHorizontal(
    colorIndex: Int,
    modifier: Modifier = Modifier
) {
    val color = SupersetTheme.colorForIndex(colorIndex)

    Box(
        modifier = modifier
            .height(3.dp)
            .width(12.dp)
            .background(color)
    )
}
