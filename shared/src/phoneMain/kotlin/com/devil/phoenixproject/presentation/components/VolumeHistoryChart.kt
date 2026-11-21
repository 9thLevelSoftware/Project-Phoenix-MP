package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.ui.theme.DataColors
import com.devil.phoenixproject.util.UnitConverter
import kotlin.math.roundToInt

data class VolumePoint(
    val label: String, // e.g., "Mon", "Tue" or Date
    val volume: Float,
)

/**
 * Volume history bar chart with density-safe stacked date labels.
 */
@Composable
fun VolumeHistoryChart(
    data: List<VolumePoint>,
    modifier: Modifier = Modifier.height(200.dp),
    barColor: Color = DataColors.Volume,
    weightUnit: WeightUnit = WeightUnit.KG,
) {
    if (data.isEmpty()) return

    val displayValues = remember(data, weightUnit) {
        data.map { point ->
            val raw = if (point.volume.isFinite() && point.volume > 0f) point.volume else 0f
            if (weightUnit == WeightUnit.LB) UnitConverter.kgToLb(raw) else raw
        }
    }
    val maxVolume = (displayValues.maxOrNull() ?: 1f).let {
        if (it.isFinite() && it > 0f) it else 1f
    }
    val unitLabel = if (weightUnit == WeightUnit.LB) "lbs" else "kg"
    val maxLabel = if (maxVolume >= 1000f) "${(maxVolume / 1000f).roundToInt()}k $unitLabel" else "${maxVolume.roundToInt()} $unitLabel"
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier) {
        Text(
            text = maxLabel,
            style = labelStyle,
            color = labelColor,
            maxLines = 1,
            modifier = Modifier.padding(bottom = 2.dp),
        )

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            val slotWidth = size.width / displayValues.size.coerceAtLeast(1)
            val barWidth = slotWidth * 0.55f
            val sideInset = (slotWidth - barWidth) / 2f
            val corner = CornerRadius(3.dp.toPx(), 3.dp.toPx())

            displayValues.forEachIndexed { index, volume ->
                val fraction = (volume / maxVolume).coerceIn(0f, 1f)
                val barHeight = fraction * size.height
                val x = index * slotWidth + sideInset
                val y = size.height - barHeight

                if (barHeight > 0f) {
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset(x, y),
                        size = Size(barWidth, barHeight),
                        cornerRadius = corner,
                    )
                }
            }
        }

        // Stacked X-axis Row automatically expands height at 1.3x–2.0x font scale without clipping
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            data.forEach { point ->
                Text(
                    text = point.label,
                    style = labelStyle,
                    color = labelColor,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
