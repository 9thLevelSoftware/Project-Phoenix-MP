package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Google "G" logo drawn with Canvas primitives so we don't have to ship a
 * raster or SVG asset. Uses Google's brand blue regardless of theme — the
 * logo is trademarked and we render it as Google publishes it.
 */
@Composable
fun GoogleIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val side = size.minDimension
        val strokeWidth = side * 0.15f
        val radius = side * 0.4f
        val center = Offset(side / 2, side / 2)

        drawArc(
            color = Color(0xFF4285F4),
            startAngle = 45f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )

        drawLine(
            color = Color(0xFF4285F4),
            start = Offset(center.x, center.y),
            end = Offset(center.x + radius, center.y),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

/**
 * Apple logo drawn with Canvas primitives. Uses the ambient
 * [LocalContentColor] so the icon stays readable on both light and dark
 * `OutlinedButton` surfaces — hardcoded black disappears in dark mode.
 */
@Composable
fun AppleIcon(modifier: Modifier = Modifier) {
    val iconColor = LocalContentColor.current
    Canvas(modifier = modifier) {
        val side = size.minDimension
        val centerX = side / 2

        val path = Path().apply {
            moveTo(centerX, side * 0.15f)
            cubicTo(
                centerX + side * 0.5f,
                side * 0.2f,
                centerX + side * 0.4f,
                side * 0.7f,
                centerX,
                side * 0.95f,
            )
            cubicTo(
                centerX - side * 0.4f,
                side * 0.7f,
                centerX - side * 0.5f,
                side * 0.2f,
                centerX,
                side * 0.15f,
            )
            close()
        }

        drawPath(
            path = path,
            color = iconColor,
        )

        drawLine(
            color = iconColor,
            start = Offset(centerX + side * 0.05f, side * 0.15f),
            end = Offset(centerX + side * 0.15f, side * 0.02f),
            strokeWidth = side * 0.08f,
            cap = StrokeCap.Round,
        )
    }
}
