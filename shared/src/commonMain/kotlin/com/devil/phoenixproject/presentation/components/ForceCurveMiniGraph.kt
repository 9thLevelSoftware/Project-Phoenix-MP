package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.ForceCurveResult

/**
 * Compact mini-graph rendering a 101-point normalized force curve.
 *
 * Shows force production through ROM with a sticking point marker (red dot).
 * Tapping expands to a full-size overlay with detailed analysis.
 *
 * @param forceCurveResult Force curve data with normalized force array and sticking point
 * @param onTapToExpand Callback when user taps to see expanded view
 * @param modifier Modifier for layout customization
 */
@Composable
fun ForceCurveMiniGraph(
    forceCurveResult: ForceCurveResult,
    onTapToExpand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val forceData = forceCurveResult.normalizedForceN
    val stickingPointPct = forceCurveResult.stickingPointPct
    val primaryColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .height(80.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable { onTapToExpand() }
    ) {
        if (forceData.isEmpty()) {
            // No data placeholder
            Text(
                "No data",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            // Force curve canvas
            Canvas(modifier = Modifier.fillMaxSize().padding(8.dp)) {
                val maxForce = forceData.max()
                val minForce = forceData.min()
                val forceRange = (maxForce - minForce).coerceAtLeast(1f)

                val path = Path()
                forceData.forEachIndexed { index, force ->
                    val x = (index / 100f) * size.width
                    val y = size.height - ((force - minForce) / forceRange) * size.height
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = primaryColor, style = Stroke(width = 2.dp.toPx()))

                // Sticking point marker
                stickingPointPct?.let { pct ->
                    val spX = (pct / 100f) * size.width
                    val spIndex = pct.toInt().coerceIn(0, forceData.lastIndex)
                    val spY = size.height - ((forceData[spIndex] - minForce) / forceRange) * size.height
                    drawCircle(color = Color.Red, radius = 4.dp.toPx(), center = Offset(spX, spY))
                }
            }

            // Label overlay
            Text(
                "Force Curve",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            )
        }
    }
}

/**
 * Full-size expanded force curve overlay with detailed analysis.
 *
 * Shows the force curve at larger scale with:
 * - X axis: ROM % with tick marks at 0, 25, 50, 75, 100
 * - Y axis: Force (N) with auto-scaled tick marks
 * - Sticking point annotation with percentage
 * - Strength profile badge (e.g., ASCENDING, BELL_SHAPED)
 *
 * @param forceCurveResult Force curve data
 * @param onDismiss Callback when user dismisses the overlay
 * @param modifier Modifier for layout customization
 */
@Composable
fun ExpandedForceCurve(
    forceCurveResult: ForceCurveResult,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val forceData = forceCurveResult.normalizedForceN
    val stickingPointPct = forceCurveResult.stickingPointPct
    val strengthProfile = forceCurveResult.strengthProfile
    val primaryColor = MaterialTheme.colorScheme.primary

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Force Curve",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                // Strength profile badge
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = strengthProfile.name.replace("_", " "),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (forceData.isEmpty()) {
                    Text(
                        "No force curve data available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    val maxForce = forceData.max()
                    val minForce = forceData.min()

                    // Y axis label
                    Text(
                        "Force (N)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Large canvas
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(16.dp)
                    ) {
                        val forceRange = (maxForce - minForce).coerceAtLeast(1f)
                        val gridColor = Color.Gray.copy(alpha = 0.3f)

                        // Draw grid lines for X axis (ROM %)
                        for (pct in listOf(0, 25, 50, 75, 100)) {
                            val x = (pct / 100f) * size.width
                            drawLine(
                                color = gridColor,
                                start = Offset(x, 0f),
                                end = Offset(x, size.height),
                                strokeWidth = 1.dp.toPx()
                            )
                        }

                        // Draw grid lines for Y axis (4 divisions)
                        for (i in 0..4) {
                            val y = (i / 4f) * size.height
                            drawLine(
                                color = gridColor,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 1.dp.toPx()
                            )
                        }

                        // Draw force curve
                        val path = Path()
                        forceData.forEachIndexed { index, force ->
                            val x = (index / 100f) * size.width
                            val y = size.height - ((force - minForce) / forceRange) * size.height
                            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, color = primaryColor, style = Stroke(width = 3.dp.toPx()))

                        // Sticking point marker
                        stickingPointPct?.let { pct ->
                            val spX = (pct / 100f) * size.width
                            val spIndex = pct.toInt().coerceIn(0, forceData.lastIndex)
                            val spY = size.height - ((forceData[spIndex] - minForce) / forceRange) * size.height
                            drawCircle(
                                color = Color.Red,
                                radius = 6.dp.toPx(),
                                center = Offset(spX, spY)
                            )
                        }
                    }

                    // X axis label with tick marks
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        for (pct in listOf(0, 25, 50, 75, 100)) {
                            Text(
                                "$pct%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text(
                        "ROM %",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Sticking point annotation
                    stickingPointPct?.let { pct ->
                        val roundedPct = pct.toInt()
                        Surface(
                            color = Color.Red.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Sticking point: $roundedPct% ROM",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color.Red,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // Force range info
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Min",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "${minForce.toInt()} N",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Max",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "${maxForce.toInt()} N",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        },
        modifier = modifier
    )
}
