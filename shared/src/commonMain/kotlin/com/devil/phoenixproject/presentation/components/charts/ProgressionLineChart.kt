package com.devil.phoenixproject.presentation.components.charts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.util.KmpUtils
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun ProgressionLineChart(
    data: List<Pair<Long, Float>>, // Timestamp to Weight
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    modifier: Modifier = Modifier.height(200.dp),
    lineColor: Color = MaterialTheme.colorScheme.primary,
    fillColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
) {
    if (data.isEmpty()) return

    val textMeasurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    
    // Sort data by timestamp just in case
    val sortedData = remember(data) { data.sortedBy { it.first } }
    
    val minWeight = sortedData.minOf { it.second }
    val maxWeight = sortedData.maxOf { it.second }
    val weightRange = (maxWeight - minWeight).coerceAtLeast(1f) // Avoid divide by zero
    
    // Y-axis padding (10%)
    val yPandding = weightRange * 0.1f
    val yMin = (minWeight - yPandding).coerceAtLeast(0f)
    val yMax = maxWeight + yPandding
    val yRange = yMax - yMin

    BoxWithConstraints(modifier = modifier) {
        val density = LocalDensity.current
        
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val chartHeight = height - 20.dp.toPx() // Reserve space for X-axis labels

            if (data.size < 2) {
                // Not enough data for a line
                return@Canvas
            }
            
            val path = Path()
            val fillPath = Path()
            
            val xStep = width / (sortedData.size - 1)
            
            // Generate path points
            sortedData.forEachIndexed { index, point ->
                val x = index * xStep
                // Invert Y because canvas Y grows downwards
                val normalizedY = (point.second - yMin) / yRange
                val y = chartHeight - (normalizedY * chartHeight)
                
                if (index == 0) {
                    path.moveTo(x, y)
                    fillPath.moveTo(x, chartHeight) // Start at bottom for fill
                    fillPath.lineTo(x, y)
                } else {
                    path.lineTo(x, y)
                    fillPath.lineTo(x, y)
                }
                
                // Draw data points
                drawCircle(
                    color = lineColor,
                    radius = 4.dp.toPx(),
                    center = Offset(x, y)
                )
                
                drawCircle(
                    color = Color.White,
                    radius = 2.dp.toPx(),
                    center = Offset(x, y)
                )
            }
            
            // Close fill path
            fillPath.lineTo(width, chartHeight)
            fillPath.close()

            // Draw fill
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(fillColor, fillColor.copy(alpha = 0f)),
                    startY = 0f,
                    endY = chartHeight
                )
            )

            // Draw line
            drawPath(
                path = path,
                color = lineColor,
                style = Stroke(
                    width = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            )
            
            // Draw Min/Max labels on Y-Axis (Left side, inside chart)
            val maxLabel = formatWeight(maxWeight, weightUnit)
            val minLabel = formatWeight(minWeight, weightUnit)
            
            drawText(
                textMeasurer = textMeasurer,
                text = maxLabel,
                style = labelStyle.copy(color = labelColor),
                topLeft = Offset(10f, 10f)
            )
             
            drawText(
                textMeasurer = textMeasurer,
                text = minLabel,
                style = labelStyle.copy(color = labelColor),
                topLeft = Offset(10f, chartHeight - 20.dp.toPx())
            )
            
            // Draw X-Axis labels (First and Last date)
            val firstDate = KmpUtils.formatTimestamp(sortedData.first().first, "MMM d")
            val lastDate = KmpUtils.formatTimestamp(sortedData.last().first, "MMM d")
            
            drawText(
                textMeasurer = textMeasurer,
                text = firstDate,
                style = labelStyle.copy(color = labelColor),
                topLeft = Offset(0f, chartHeight + 4.dp.toPx())
            )
            
            val lastTextLayout = textMeasurer.measure(lastDate, labelStyle)
            drawText(
                textMeasurer = textMeasurer,
                text = lastDate,
                style = labelStyle.copy(color = labelColor),
                topLeft = Offset(width - lastTextLayout.size.width, chartHeight + 4.dp.toPx())
            )
        }
    }
}
