package com.devil.phoenixproject.presentation.components

import android.os.Build
import android.util.TypedValue
import android.widget.NumberPicker
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.devil.phoenixproject.presentation.util.LocalWeightTextScale
import com.devil.phoenixproject.presentation.util.effectiveWeightTextScale
import com.devil.phoenixproject.presentation.util.scaledForWeightValue
import com.devil.phoenixproject.presentation.util.scaledSelectorButtonSizeDp
import com.devil.phoenixproject.presentation.util.scaledSelectorIconSizeDp
import com.devil.phoenixproject.presentation.util.scaledWeightWheelHeightDp
import com.devil.phoenixproject.presentation.util.weightTextMultiplier
import kotlin.math.abs
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
    step: Float,
    compactWheel: Boolean,
    applySelectorScale: Boolean,
) {
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val weightTextScale = LocalWeightTextScale.current
    val effectiveScale = if (applySelectorScale) {
        effectiveWeightTextScale(weightTextScale, fontScale)
    } else {
        1f
    }
    val baseWheelHeightDp = if (compactWheel) 96f else 120f
    val wheelHeight = if (applySelectorScale) {
        scaledWeightWheelHeightDp(baseWheelHeightDp, effectiveScale).dp
    } else {
        scaledWeightWheelHeightDp(baseWheelHeightDp, fontScale.coerceAtLeast(1f)).dp
    }
    val selectorTextSizePx: Float? = if (applySelectorScale) {
        val baseSp = if (compactWheel) 18f else 20f
        val targetSp = baseSp * weightTextMultiplier(weightTextScale.preferenceFactor, fontScale)
        with(density) { targetSp.sp.toPx() }.roundToInt().toFloat()
    } else {
        null
    }
    val baseButtonSizeDp = 48f
    val buttonSize = if (applySelectorScale) {
        scaledSelectorButtonSizeDp(baseButtonSizeDp, effectiveScale).dp
    } else {
        baseButtonSizeDp.dp
    }
    val iconSize = if (applySelectorScale) {
        scaledSelectorIconSizeDp(24f, effectiveScale).dp
    } else {
        24.dp
    }
    val labelStyle = if (applySelectorScale) {
        MaterialTheme.typography.labelMedium.scaledForWeightValue(weightTextScale, fontScale)
    } else {
        MaterialTheme.typography.labelMedium
    }
    val values = remember(range.start, range.endInclusive, step) {
        compactNumberPickerValues(range, step)
    }

    // Find current index - use minByOrNull to find CLOSEST value regardless of precision
    // This handles unit conversions (e.g., 20kg -> 44.0924 lbs) where exact matching fails
    val currentIndex = remember(value, values) {
        if (values.isEmpty()) {
            0
        } else {
            values.indices.minByOrNull { kotlin.math.abs(values[it] - value) } ?: 0
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = labelStyle,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        // Row with -/+ buttons and number picker
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Decrease button
            IconButton(
                onClick = {
                    val newIndex = (currentIndex - 1).coerceIn(values.indices)
                    onValueChange(values[newIndex])
                },
                enabled = currentIndex > 0,
                modifier = Modifier.size(buttonSize),
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Decrease $label",
                    modifier = Modifier.size(iconSize),
                    tint = if (currentIndex > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }

            // Get the theme-aware text color
            val textColor = MaterialTheme.colorScheme.onSurface

            // Native Android NumberPicker wrapped in AndroidView
            AndroidView(
                factory = { context ->
                    NumberPicker(context).apply {
                        applyCompactNumberPickerState(
                            picker = NumberPickerCompactWheel(this),
                            values = values,
                            step = step,
                            suffix = suffix,
                            selectedIndex = currentIndex,
                            onValueChange = onValueChange,
                        )

                        // Set text color for all Android versions
                        applyNumberPickerTextColor(textColor)
                        if (applySelectorScale && selectorTextSizePx != null) {
                            applyNumberPickerTextSize(selectorTextSizePx)
                        }
                    }
                },
                update = { picker ->
                    // Unit, increment, and suffix changes reuse this view. Refresh labels,
                    // range, and the listener here; factory alone keeps the first unit's wheel.
                    applyCompactNumberPickerState(
                        picker = NumberPickerCompactWheel(picker),
                        values = values,
                        step = step,
                        suffix = suffix,
                        selectedIndex = currentIndex,
                        onValueChange = onValueChange,
                    )

                    // Update text color on every recomposition
                    picker.applyNumberPickerTextColor(textColor)
                    if (applySelectorScale && selectorTextSizePx != null) {
                        picker.applyNumberPickerTextSize(selectorTextSizePx)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(wheelHeight),
            )

            // Increase button
            IconButton(
                onClick = {
                    val newIndex = (currentIndex + 1).coerceIn(values.indices)
                    onValueChange(values[newIndex])
                },
                enabled = currentIndex < values.size - 1,
                modifier = Modifier.size(buttonSize),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Increase $label",
                    modifier = Modifier.size(iconSize),
                    tint = if (currentIndex < values.size - 1) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
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
    suffix: String,
    applySelectorScale: Boolean,
) {
    CompactNumberPicker(
        value = value.toFloat(),
        onValueChange = { onValueChange(it.roundToInt()) },
        range = range.first.toFloat()..range.last.toFloat(),
        modifier = modifier,
        label = label,
        suffix = suffix,
        step = 1.0f,
        compactWheel = false,
        applySelectorScale = applySelectorScale,
    )
}

/**
 * Applies [textSizePx] to the wheel when [CompactNumberPicker] has `applySelectorScale = true`.
 * API 29+ uses [NumberPicker.setTextSize]; API 26–28 updates TextView children and wheel paint.
 */
internal fun NumberPicker.applyNumberPickerTextSize(textSizePx: Float) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        if (abs(textSize - textSizePx) > 0.5f) {
            textSize = textSizePx
        }
    } else {
        post {
            try {
                for (i in 0 until childCount) {
                    val child = getChildAt(i)
                    if (child is android.widget.TextView) {
                        child.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
                    }
                }
                try {
                    val paintField = NumberPicker::class.java.getDeclaredField("mSelectorWheelPaint")
                    paintField.isAccessible = true
                    val paint = paintField.get(this) as? android.graphics.Paint
                    paint?.textSize = textSizePx
                } catch (_: Exception) {
                    // Paint field not found - expected on some Android versions
                }
                try {
                    val textSizeField = NumberPicker::class.java.getDeclaredField("mTextSize")
                    textSizeField.isAccessible = true
                    textSizeField.setInt(this, textSizePx.roundToInt())
                } catch (_: Exception) {
                    // Text size field not found - expected on some Android versions
                }
                invalidate()
            } catch (_: Exception) {
                // Reflection failed - fall back to default styling
            }
        }
    }
}

/**
 * Paints the wheel with [textColor] whenever the view is created or recomposed.
 *
 * API 29+ uses [NumberPicker.setTextColor]. API 26–28 still need a posted child walk
 * plus the selector-wheel paint, because [NumberPicker.setTextColor] is not available.
 * Text color and background are applied only to [android.widget.TextView] children,
 * which includes the wheel's [android.widget.EditText]. Both the AndroidView factory
 * and update blocks share this path.
 */
private fun NumberPicker.applyNumberPickerTextColor(textColor: Color) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        setTextColor(textColor.toArgb())
    } else {
        // API 28 and below: style text children and the selector paint.
        post {
            try {
                val count = childCount
                for (i in 0 until count) {
                    val child = getChildAt(i)
                    when (child) {
                        is android.widget.TextView -> {
                            child.setTextColor(textColor.toArgb())
                            child.setBackgroundColor(
                                android.graphics.Color.TRANSPARENT,
                            )
                        }
                    }
                }

                // Try to access and modify the Paint object
                try {
                    val paintField = NumberPicker::class.java.getDeclaredField(
                        "mSelectorWheelPaint",
                    )
                    paintField.isAccessible = true
                    val paint = paintField.get(this) as? android.graphics.Paint
                    paint?.color = textColor.toArgb()
                } catch (_: Exception) {
                    // Paint field not found - expected on some Android versions
                }
            } catch (_: Exception) {
                // Reflection failed - fall back to default styling
            }
        }
    }
}

/**
 * Android [NumberPicker] surface used by [applyCompactNumberPickerState].
 * Tests supply a strict stand-in that enforces the same length and index rules.
 */
internal interface CompactWheelPicker {
    var displayedValues: Array<String>?
    var minValue: Int
    var maxValue: Int
    var value: Int
    var wrapSelectorWheel: Boolean
    fun setOnIndexSelected(listener: (Int) -> Unit)
}

/**
 * Installs labels, range, selection, and the value listener on an existing wheel.
 *
 * NumberPicker indexes the installed label array while applying a new max, and the
 * selected index has to stay inside that max. A longer list (finer step, or a unit
 * change) therefore clears the old labels before the range grows, installs the new
 * labels, then moves the selection. The listener is replaced on every call so a
 * user scroll reads the current value list.
 *
 * When the labels and range are already current, only the listener and selected
 * index are updated.
 *
 * Wrapping is switched off after the range is applied. On API 26-28, NumberPicker's
 * setMinValue/setMaxValue recompute wrapSelectorWheel from the range size and discard an
 * earlier setWrapSelectorWheel(false), so the weight wheel would otherwise wrap from its
 * minimum straight to its maximum.
 */
internal fun applyCompactNumberPickerState(
    picker: CompactWheelPicker,
    values: List<Float>,
    step: Float,
    suffix: String,
    selectedIndex: Int,
    onValueChange: (Float) -> Unit,
) {
    if (values.isEmpty()) return

    val displayValues = compactNumberPickerLabels(values, step, suffix)
    val newMax = values.lastIndex
    val safeIndex = selectedIndex.coerceIn(0, newMax)
    val installed = picker.displayedValues
    val rangeMatchesInstalled = installed != null &&
        installed.size == displayValues.size &&
        picker.minValue == 0 &&
        picker.maxValue == newMax
    if (installed != null && !rangeMatchesInstalled) {
        picker.displayedValues = null
    }
    if (picker.minValue != 0) {
        picker.minValue = 0
    }
    if (picker.maxValue != newMax) {
        picker.maxValue = newMax
    }
    if (picker.displayedValues?.contentEquals(displayValues) != true) {
        picker.displayedValues = displayValues
    }
    if (picker.wrapSelectorWheel) {
        picker.wrapSelectorWheel = false
    }
    picker.setOnIndexSelected { index ->
        values.getOrNull(index)?.let(onValueChange)
    }
    if (picker.value != safeIndex) {
        picker.value = safeIndex
    }
}

private fun compactNumberPickerLabels(
    values: List<Float>,
    step: Float,
    suffix: String,
): Array<String> = Array(values.size) { index ->
    val formatted = formatCompactNumberPickerValue(values[index], step)
    if (suffix.isNotEmpty()) "$formatted $suffix" else formatted
}

private class NumberPickerCompactWheel(
    private val picker: NumberPicker,
) : CompactWheelPicker {
    override var displayedValues: Array<String>?
        get() = picker.displayedValues
        set(value) {
            picker.displayedValues = value
        }

    override var minValue: Int
        get() = picker.minValue
        set(value) {
            picker.minValue = value
        }

    override var maxValue: Int
        get() = picker.maxValue
        set(value) {
            picker.maxValue = value
        }

    override var value: Int
        get() = picker.value
        set(value) {
            picker.value = value
        }

    override var wrapSelectorWheel: Boolean
        get() = picker.wrapSelectorWheel
        set(value) {
            picker.wrapSelectorWheel = value
        }

    override fun setOnIndexSelected(listener: (Int) -> Unit) {
        picker.setOnValueChangedListener { _, _, newIndex ->
            listener(newIndex)
        }
    }
}
