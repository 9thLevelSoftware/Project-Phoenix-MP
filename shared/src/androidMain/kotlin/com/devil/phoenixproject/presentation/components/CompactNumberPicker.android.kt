package com.devil.phoenixproject.presentation.components

import android.os.Build
import android.widget.NumberPicker
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

/**
 * Android implementation using native NumberPicker wheel.
 * Provides smooth wheel-based number selection with proper physics.
 */
@Composable
actual fun CompactNumberPicker(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier,
    label: String,
    suffix: String,
    step: Float
) {
    // Generate array of values based on step
    val values = remember(range, step) {
        buildList {
            var current = range.start
            while (current <= range.endInclusive) {
                add(current)
                current += step
            }
        }
    }

    // Find current index - use minByOrNull to find CLOSEST value regardless of precision
    // This handles unit conversions (e.g., 20kg -> 44.0924 lbs) where exact matching fails
    val currentIndex = remember(value, values) {
        if (values.isEmpty()) 0
        else values.indices.minByOrNull { kotlin.math.abs(values[it] - value) } ?: 0
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        // Row with -/+ buttons and number picker
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Decrease button
            IconButton(
                onClick = {
                    val newIndex = (currentIndex - 1).coerceIn(values.indices)
                    onValueChange(values[newIndex])
                },
                enabled = currentIndex > 0,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Decrease $label",
                    tint = if (currentIndex > 0)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }

            // Get the theme-aware text color
            val textColor = MaterialTheme.colorScheme.onSurface

            // Native Android NumberPicker wrapped in AndroidView
            AndroidView(
                factory = { context ->
                    NumberPicker(context).apply {
                        minValue = 0
                        maxValue = values.size - 1
                        this.value = currentIndex.coerceIn(0, values.size - 1)
                        wrapSelectorWheel = false

                        // Format displayed values with proper decimal precision
                        val displayValues = values.map {
                            val formatted = if (step >= 1.0f && it % 1.0f == 0f) {
                                // Show as integer if step is 1.0 and value is whole number
                                it.toInt().toString()
                            } else {
                                // Show with one decimal place for fractional values
                                "%.1f".format(it)
                            }
                            if (suffix.isNotEmpty()) "$formatted $suffix" else formatted
                        }.toTypedArray()
                        this.displayedValues = displayValues

                        setOnValueChangedListener { _, _, newIndex ->
                            onValueChange(values[newIndex])
                        }

                        // Set text color for all Android versions
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            setTextColor(textColor.toArgb())
                        } else {
                            // API 28 and below: Style text children
                            post {
                                try {
                                    val count = childCount
                                    for (i in 0 until count) {
                                        val child = getChildAt(i)
                                        when (child) {
                                            is android.widget.EditText -> {
                                                child.setTextColor(textColor.toArgb())
                                                child.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                            }
                                            is android.widget.TextView -> {
                                                child.setTextColor(textColor.toArgb())
                                                child.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                            }
                                        }
                                    }

                                    // Try to access and modify the Paint object
                                    try {
                                        val paintField = NumberPicker::class.java.getDeclaredField("mSelectorWheelPaint")
                                        paintField.isAccessible = true
                                        val paint = paintField.get(this) as? android.graphics.Paint
                                        paint?.color = textColor.toArgb()
                                    } catch (e: Exception) {
                                        // Paint field not found - expected on some Android versions
                                    }
                                } catch (e: Exception) {
                                    // Reflection failed - fall back to default styling
                                }
                            }
                        }
                    }
                },
                update = { picker ->
                    // Update picker when value changes externally
                    if (picker.value != currentIndex) {
                        picker.value = currentIndex.coerceIn(0, values.size - 1)
                    }

                    // Update text color on every recomposition
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        picker.setTextColor(textColor.toArgb())
                    } else {
                        // API 28 and below: Apply text color to ALL text children
                        picker.post {
                            try {
                                val count = picker.childCount
                                for (i in 0 until count) {
                                    val child = picker.getChildAt(i)
                                    when (child) {
                                        is android.widget.EditText -> {
                                            child.setTextColor(textColor.toArgb())
                                            child.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                        }
                                        is android.widget.TextView -> {
                                            child.setTextColor(textColor.toArgb())
                                            child.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                        }
                                    }
                                }

                                // Try to access and modify the Paint object
                                try {
                                    val paintField = NumberPicker::class.java.getDeclaredField("mSelectorWheelPaint")
                                    paintField.isAccessible = true
                                    val paint = paintField.get(picker) as? android.graphics.Paint
                                    paint?.color = textColor.toArgb()
                                } catch (e: Exception) {
                                    // Paint field not found - expected on some Android versions
                                }
                            } catch (e: Exception) {
                                // Reflection failed - fall back to default styling
                            }
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(120.dp)
            )

            // Increase button
            IconButton(
                onClick = {
                    val newIndex = (currentIndex + 1).coerceIn(values.indices)
                    onValueChange(values[newIndex])
                },
                enabled = currentIndex < values.size - 1,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Increase $label",
                    tint = if (currentIndex < values.size - 1)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
        }
    }
}

/**
 * Int overload for backward compatibility
 */
@Composable
actual fun CompactNumberPicker(
    value: Int,
    onValueChange: (Int) -> Unit,
    range: IntRange,
    modifier: Modifier,
    label: String,
    suffix: String
) {
    CompactNumberPicker(
        value = value.toFloat(),
        onValueChange = { onValueChange(it.roundToInt()) },
        range = range.first.toFloat()..range.last.toFloat(),
        modifier = modifier,
        label = label,
        suffix = suffix,
        step = 1.0f
    )
}
