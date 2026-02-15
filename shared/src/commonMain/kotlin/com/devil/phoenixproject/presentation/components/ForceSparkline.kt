package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Compact force curve sparkline for embedding in rep replay cards.
 *
 * Renders a 40dp tall force curve visualization using Canvas.
 * Draws force values as a continuous line with optional peak marker.
 *
 * @param forceData Combined concentric + eccentric force values (kg)
 * @param peakIndex Optional index of peak force for marker display
 * @param modifier Modifier for layout customization
 * @param lineColor Color for the force curve line (defaults to primary)
 * @param peakMarkerColor Color for the peak marker dot (defaults to tertiary)
 */
@Composable
fun ForceSparkline(
    forceData: FloatArray,
    peakIndex: Int?,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    peakMarkerColor: Color = MaterialTheme.colorScheme.tertiary
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(4.dp)
    ) {
        // Handle empty data gracefully
        if (forceData.isEmpty()) return@Canvas

        // Single point - draw a horizontal line
        if (forceData.size == 1) {
            val y = size.height / 2
            drawLine(
                color = lineColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.5.dp.toPx()
            )
            return@Canvas
        }

        // Calculate Y axis normalization
        val maxForce = forceData.max()
        val minForce = forceData.min()
        val forceRange = (maxForce - minForce).coerceAtLeast(0.001f) // Prevent division by zero

        // Build the path for the force curve
        val path = Path()
        forceData.forEachIndexed { index, force ->
            val x = (index.toFloat() / (forceData.size - 1)) * size.width
            val y = size.height - ((force - minForce) / forceRange) * size.height

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        // Draw the force curve
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 1.5.dp.toPx())
        )

        // Draw peak marker if provided
        peakIndex?.let { idx ->
            if (idx in forceData.indices) {
                val peakX = (idx.toFloat() / (forceData.size - 1)) * size.width
                val peakY = size.height - ((forceData[idx] - minForce) / forceRange) * size.height

                drawCircle(
                    color = peakMarkerColor,
                    radius = 3.dp.toPx(),
                    center = Offset(peakX, peakY)
                )
            }
        }
    }
}
