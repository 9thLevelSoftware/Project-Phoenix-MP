package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.format

/**
 * Weight input with +/- stepper buttons.
 * Increments/decrements by 2.5kg (standard plate increment).
 *
 * @param weight Current weight value in kg
 * @param onWeightChange Callback when weight is changed
 * @param modifier Modifier for the composable
 * @param minWeight Minimum allowed weight (default 0)
 * @param maxWeight Maximum allowed weight per cable (default 100kg)
 * @param step Weight increment/decrement step (default 2.5kg)
 * @param label Label text displayed above the control
 * @param prWeight Optional PR weight to show percentage indicator
 */
@Composable
fun WeightStepper(
    weight: Float,
    onWeightChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    minWeight: Float = 0f,
    maxWeight: Float = 100f, // Per cable max (V-Form: 100kg, Trainer+: 110kg)
    step: Float = 2.5f,
    label: String = "Weight",
    prWeight: Float? = null
) {
    Column(modifier = modifier) {
        // Header row with label and PR indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp
            )

            // PR Indicator shown when prWeight is provided
            if (prWeight != null) {
                PRIndicator(
                    currentWeight = weight,
                    prWeight = prWeight
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.small))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLow,
                    RoundedCornerShape(Spacing.medium)
                )
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    RoundedCornerShape(Spacing.medium)
                )
                .padding(Spacing.extraSmall),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Minus button
            FilledTonalIconButton(
                onClick = {
                    val newWeight = (weight - step).coerceAtLeast(minWeight)
                    onWeightChange(newWeight)
                },
                enabled = weight > minWeight,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Decrease weight")
            }

            // Weight display
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (weight == weight.toLong().toFloat()) {
                        weight.toLong().toString()
                    } else {
                        weight.format(1)
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "kg per cable",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Plus button
            FilledTonalIconButton(
                onClick = {
                    val newWeight = (weight + step).coerceAtMost(maxWeight)
                    onWeightChange(newWeight)
                },
                enabled = weight < maxWeight,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Increase weight")
            }
        }

        // Total weight for 2 cables indicator
        Spacer(modifier = Modifier.height(Spacing.small))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Spacing.small),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.medium, vertical = Spacing.small),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(Spacing.small))
                val totalWeight = weight * 2
                Text(
                    text = "Total weight for 2 cables: ${
                        if (totalWeight == totalWeight.toLong().toFloat()) {
                            totalWeight.toLong().toString()
                        } else {
                            totalWeight.format(1)
                        }
                    } kg",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
