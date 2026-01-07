package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
 */
@Composable
fun WeightStepper(
    weight: Float,
    onWeightChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    minWeight: Float = 0f,
    maxWeight: Float = 220f,
    step: Float = 2.5f,
    label: String = "Weight"
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp
        )

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
    }
}
