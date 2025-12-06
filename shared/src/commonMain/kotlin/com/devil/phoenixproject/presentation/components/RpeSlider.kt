package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.ui.theme.Spacing
import kotlin.math.roundToInt

/**
 * RPE (Rate of Perceived Exertion) data.
 */
data class RpeInfo(
    val value: Int,
    val emoji: String,
    val label: String,
    val riR: String // Reps in Reserve
)

/**
 * RPE scale from 6-10 with emoji representations.
 */
val rpeScale = listOf(
    RpeInfo(6, "😊", "Easy", "4+ RiR"),
    RpeInfo(7, "🙂", "Moderate", "3 RiR"),
    RpeInfo(8, "😐", "Challenging", "2 RiR"),
    RpeInfo(9, "😰", "Hard", "1 RiR"),
    RpeInfo(10, "😵", "Max Effort", "0 RiR")
)

/**
 * Compact RPE indicator that shows current RPE or "Add RPE" button.
 * Tapping expands to show the full slider.
 */
@Composable
fun RpeIndicator(
    currentRpe: Int?,
    onRpeChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        // Collapsed state - shows current RPE or "Add" button
        if (currentRpe != null) {
            val rpeInfo = rpeScale.find { it.value == currentRpe } ?: rpeScale.last()
            Surface(
                onClick = { expanded = !expanded },
                shape = RoundedCornerShape(12.dp),
                color = getRpeColor(currentRpe).copy(alpha = 0.2f),
                modifier = Modifier.height(40.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = rpeInfo.emoji,
                        fontSize = 18.sp
                    )
                    Text(
                        text = "RPE $currentRpe",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = getRpeColor(currentRpe)
                    )
                }
            }
        } else {
            OutlinedButton(
                onClick = { expanded = true },
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.height(40.dp)
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Log RPE",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        // Expanded slider
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            RpeSlider(
                selectedRpe = currentRpe ?: 8,
                onRpeSelected = { rpe ->
                    onRpeChanged(rpe)
                    expanded = false
                },
                onDismiss = { expanded = false },
                modifier = Modifier.padding(top = Spacing.small)
            )
        }
    }
}

/**
 * Full RPE slider with emoji faces.
 */
@Composable
fun RpeSlider(
    selectedRpe: Int,
    onRpeSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var sliderValue by remember(selectedRpe) { mutableStateOf(selectedRpe.toFloat()) }
    val currentRpeInfo = rpeScale.find { it.value == sliderValue.roundToInt() } ?: rpeScale[2]

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header with emoji and description
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = currentRpeInfo.emoji,
                    fontSize = 32.sp
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "RPE ${sliderValue.roundToInt()}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = getRpeColor(sliderValue.roundToInt())
                    )
                    Text(
                        text = "${currentRpeInfo.label} • ${currentRpeInfo.riR}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(Spacing.medium))

            // Emoji row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                rpeScale.forEach { rpeInfo ->
                    val isSelected = sliderValue.roundToInt() == rpeInfo.value
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { sliderValue = rpeInfo.value.toFloat() }
                            .background(
                                if (isSelected) getRpeColor(rpeInfo.value).copy(alpha = 0.2f)
                                else Color.Transparent
                            )
                            .padding(8.dp)
                    ) {
                        Text(
                            text = rpeInfo.emoji,
                            fontSize = if (isSelected) 28.sp else 22.sp
                        )
                        Text(
                            text = rpeInfo.value.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) getRpeColor(rpeInfo.value)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.medium))

            // Slider
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                valueRange = 6f..10f,
                steps = 3,
                colors = SliderDefaults.colors(
                    thumbColor = getRpeColor(sliderValue.roundToInt()),
                    activeTrackColor = getRpeColor(sliderValue.roundToInt())
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(Spacing.medium))

            // Confirm button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = { onRpeSelected(sliderValue.roundToInt()) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = getRpeColor(sliderValue.roundToInt())
                    )
                ) {
                    Text("Confirm")
                }
            }
        }
    }
}

/**
 * Inline RPE quick selector - compact horizontal buttons.
 */
@Composable
fun RpeQuickSelect(
    selectedRpe: Int?,
    onRpeSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        rpeScale.forEach { rpeInfo ->
            val isSelected = selectedRpe == rpeInfo.value
            Surface(
                onClick = { onRpeSelected(rpeInfo.value) },
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) getRpeColor(rpeInfo.value).copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(44.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = rpeInfo.emoji,
                            fontSize = 16.sp
                        )
                        Text(
                            text = rpeInfo.value.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) getRpeColor(rpeInfo.value)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Get color for RPE value.
 */
@Composable
fun getRpeColor(rpe: Int): Color {
    return when (rpe) {
        6 -> Color(0xFF4CAF50)  // Green - easy
        7 -> Color(0xFF8BC34A)  // Light green
        8 -> Color(0xFFFFC107)  // Amber - moderate
        9 -> Color(0xFFFF9800)  // Orange - hard
        10 -> Color(0xFFF44336) // Red - max effort
        else -> MaterialTheme.colorScheme.primary
    }
}

/**
 * Format RPE for display.
 */
fun formatRpe(rpe: Int?): String {
    if (rpe == null) return "-"
    val info = rpeScale.find { it.value == rpe }
    return info?.let { "${it.emoji} $rpe" } ?: rpe.toString()
}
