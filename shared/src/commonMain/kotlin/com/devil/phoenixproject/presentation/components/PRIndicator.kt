package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Displays the current weight as a percentage of the user's Personal Record (PR).
 *
 * Shows:
 * - Percentage of PR (e.g., "82% PR")
 * - Up arrow if above PR, down arrow if below PR
 * - Color coding: primary when above PR, tertiary when 90%+, default otherwise
 *
 * @param currentWeight The current weight setting in kg
 * @param prWeight The user's PR weight for the exercise (null if no PR exists)
 * @param modifier Modifier for the composable
 */
@Composable
fun PRIndicator(
    currentWeight: Float,
    prWeight: Float?,
    modifier: Modifier = Modifier
) {
    if (prWeight == null || prWeight <= 0) {
        Text(
            text = "No PR",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
        return
    }

    val percentage = ((currentWeight / prWeight) * 100).toInt().coerceIn(0, 200)
    val isAbovePR = currentWeight > prWeight
    // Use epsilon comparison for float equality (0.01f = 10g, safely below 0.5kg increments)
    val isAtPR = kotlin.math.abs(currentWeight - prWeight) < 0.01f

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Text(
            text = "$percentage% PR",
            style = MaterialTheme.typography.labelMedium,
            color = when {
                isAbovePR -> MaterialTheme.colorScheme.primary
                percentage >= 90 -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )

        Spacer(Modifier.width(4.dp))

        if (!isAtPR) {
            Icon(
                imageVector = if (isAbovePR)
                    Icons.Default.KeyboardArrowUp
                else
                    Icons.Default.KeyboardArrowDown,
                contentDescription = if (isAbovePR) "Above PR" else "Below PR",
                tint = if (isAbovePR)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Compact version of PRIndicator that shows only the percentage number.
 *
 * Useful for space-constrained layouts where the full indicator would be too large.
 *
 * @param currentWeight The current weight setting in kg
 * @param prWeight The user's PR weight for the exercise (null if no PR exists)
 * @param modifier Modifier for the composable
 */
@Composable
fun PRIndicatorCompact(
    currentWeight: Float,
    prWeight: Float?,
    modifier: Modifier = Modifier
) {
    if (prWeight == null || prWeight <= 0) return

    val percentage = ((currentWeight / prWeight) * 100).toInt().coerceIn(0, 200)

    Text(
        text = "$percentage%",
        style = MaterialTheme.typography.labelSmall,
        color = when {
            percentage > 100 -> MaterialTheme.colorScheme.primary
            percentage >= 90 -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier
    )
}
