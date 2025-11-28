package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Desktop implementation with editable text field and +/- buttons.
 * Users can click in the field and type a value directly, or use buttons.
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
    // Generate array of valid values based on step
    val values = remember(range, step) {
        buildList {
            var current = range.start
            while (current <= range.endInclusive) {
                add(current)
                current += step
            }
        }
    }

    // Find current index
    val currentIndex = remember(value, values) {
        values.indexOfFirst { kotlin.math.abs(it - value) < 0.001f }.coerceAtLeast(0)
    }

    // Text field state - store the display text
    var textFieldValue by remember(value, step, suffix) {
        val formatted = if (step >= 1.0f && value % 1.0f == 0f) {
            value.toInt().toString()
        } else {
            "%.1f".format(value)
        }
        mutableStateOf(formatted)
    }

    var isEditing by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // Parse and validate input, snap to nearest valid value
    fun parseAndUpdate(input: String) {
        val parsed = input.replace(suffix, "").trim().toFloatOrNull()
        if (parsed != null) {
            // Clamp to range
            val clamped = parsed.coerceIn(range)
            // Snap to nearest step value
            val snapped = values.minByOrNull { kotlin.math.abs(it - clamped) } ?: clamped
            onValueChange(snapped)
        }
        // Reset text to current value
        val formatted = if (step >= 1.0f && value % 1.0f == 0f) {
            value.toInt().toString()
        } else {
            "%.1f".format(value)
        }
        textFieldValue = formatted
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

        // Row with -/+ buttons and editable text field
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

            // Editable text field
            OutlinedTextField(
                value = if (isEditing) textFieldValue else {
                    val formatted = if (step >= 1.0f && value % 1.0f == 0f) {
                        value.toInt().toString()
                    } else {
                        "%.1f".format(value)
                    }
                    if (suffix.isNotEmpty()) "$formatted $suffix" else formatted
                },
                onValueChange = { newValue ->
                    // Allow editing, strip suffix for input
                    textFieldValue = newValue.replace(suffix, "").trim()
                },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .onFocusChanged { focusState ->
                        if (focusState.isFocused) {
                            isEditing = true
                            // Strip suffix when editing
                            val formatted = if (step >= 1.0f && value % 1.0f == 0f) {
                                value.toInt().toString()
                            } else {
                                "%.1f".format(value)
                            }
                            textFieldValue = formatted
                        } else {
                            isEditing = false
                            parseAndUpdate(textFieldValue)
                        }
                    },
                textStyle = TextStyle(
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        parseAndUpdate(textFieldValue)
                        focusManager.clearFocus()
                    }
                ),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                )
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
