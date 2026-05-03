package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import com.devil.phoenixproject.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.cd_decrease
import vitruvianprojectphoenix.shared.generated.resources.cd_increase
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
    enabled: Boolean = true,
    deltaText: String? = null,
    isDeltaPositive: Boolean = true,
) {
    // Calculate number of discrete steps for the slider
    // steps = number of intervals - 1 (excluding start and end)
    val range = valueRange.endInclusive - valueRange.start
    val sliderSteps = ((range / step).roundToInt() - 1).coerceAtLeast(0)
    val windowSizeClass = LocalWindowSizeClass.current
    val fontScale = LocalDensity.current.fontScale
    val shouldStackHeader = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact && fontScale >= 1.15f
    val formattedValue = formatValue(value)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small),
    ) {
        // Label and current value
        if (shouldStackHeader) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formattedValue,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = Spacing.small),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formattedValue,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
            }
        }

        // Delta from baseline indicator (e.g., "+11 lb" or "-5 kg")
        if (deltaText != null) {
            Text(
                text = deltaText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = if (isDeltaPositive) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.align(Alignment.End),
            )
        }

        // Slider with +/- buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
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
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                shape = CircleShape,
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = stringResource(Res.string.cd_decrease),
                    modifier = Modifier.size(18.dp),
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
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                shape = CircleShape,
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(Res.string.cd_increase),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
