package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * Hybrid slider with fine-tuning +/- buttons
 * Slider for coarse adjustment, buttons for precise increments
 */
@Composable
fun SliderWithButtons(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    label: String,
    formatValue: (Float) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    // Calculate number of discrete steps for the slider
    // steps = number of intervals - 1 (excluding start and end)
    val range = valueRange.endInclusive - valueRange.start
    val sliderSteps = ((range / step).roundToInt() - 1).coerceAtLeast(0)
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small)
    ) {
        // Label and current value
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = formatValue(value),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Slider with +/- buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small)
        ) {
            // Decrease button
            FilledIconButton(
                onClick = {
                    val newValue = (value - step).coerceIn(valueRange)
                    onValueChange(newValue)
                },
                modifier = Modifier.size(36.dp),
                enabled = enabled && value > valueRange.start,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = CircleShape
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = "Decrease",
                    modifier = Modifier.size(18.dp)
                )
            }

            // Slider with discrete steps matching the +/- button increments
            ExpressiveSlider(
                value = value,
                onValueChange = { rawValue ->
                    if (enabled) {
                        // Snap to nearest step to avoid floating point precision issues
                        val snapped = (rawValue / step).roundToInt() * step
                        onValueChange(snapped.coerceIn(valueRange))
                    }
                },
                valueRange = valueRange,
                steps = sliderSteps,
                modifier = Modifier.weight(1f),
            )

            // Increase button
            FilledIconButton(
                onClick = {
                    val newValue = (value + step).coerceIn(valueRange)
                    onValueChange(newValue)
                },
                modifier = Modifier.size(36.dp),
                enabled = enabled && value < valueRange.endInclusive,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = CircleShape
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Increase",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
