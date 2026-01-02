package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * iOS implementation using Compose-based scrollable picker.
 * Provides a wheel-like experience using LazyColumn with snap behavior.
 *
 * Note: This uses Compose rather than native UIPickerView due to the complexity
 * of iOS delegate/datasource patterns in Kotlin/Native. Can be enhanced later.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
        else values.indices.minByOrNull { abs(values[it] - value) } ?: 0
    }

    // Format a value for display
    fun formatValue(floatVal: Float): String {
        val formatted = if (step >= 1.0f && floatVal % 1.0f == 0f) {
            floatVal.toInt().toString()
        } else {
            val intPart = floatVal.toInt()
            val decPart = ((floatVal - intPart) * 10).toInt().let { if (floatVal < 0 && it < 0) -it else abs(it) }
            "$intPart.$decPart"
        }
        if (suffix.isNotEmpty()) "$formatted $suffix" else formatted
        return if (suffix.isNotEmpty()) "$formatted $suffix" else formatted
    }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0))
    val coroutineScope = rememberCoroutineScope()
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val focusManager = LocalFocusManager.current

    // Inline editing state
    var isEditing by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var hasFocusedOnce by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Format value for editing (without suffix)
    fun formatValueForEdit(floatVal: Float): String {
        return if (step >= 1.0f && floatVal % 1.0f == 0f) {
            floatVal.toInt().toString()
        } else {
            val intPart = floatVal.toInt()
            val decPart = ((floatVal - intPart) * 10).toInt().let { if (floatVal < 0 && it < 0) -it else abs(it) }
            "$intPart.$decPart"
        }
    }

    // Commit the edited value
    fun commitEdit() {
        inputText.toFloatOrNull()?.let { parsed ->
            val clamped = parsed.coerceIn(range)
            val closestIndex = values.indices.minByOrNull { abs(values[it] - clamped) } ?: 0
            onValueChange(values[closestIndex])
            coroutineScope.launch {
                listState.animateScrollToItem(closestIndex)
            }
        }
        isEditing = false
    }

    // Sync list position when external value changes
    LaunchedEffect(currentIndex) {
        if (listState.firstVisibleItemIndex != currentIndex) {
            listState.animateScrollToItem(currentIndex.coerceAtLeast(0))
        }
    }

    // Focus the text field when editing starts
    LaunchedEffect(isEditing) {
        if (isEditing) {
            hasFocusedOnce = false
            inputText = formatValueForEdit(value)
            // Small delay to ensure TextField is composed before requesting focus
            kotlinx.coroutines.delay(100)
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    // Update value when scroll settles
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val centerIndex = listState.firstVisibleItemIndex
            if (centerIndex in values.indices && values[centerIndex] != value) {
                onValueChange(values[centerIndex])
            }
        }
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
                    coroutineScope.launch {
                        listState.animateScrollToItem(newIndex)
                    }
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

            // Scrollable picker - Item height is ~40dp (text + padding)
            val itemHeight = 40.dp
            val containerHeight = 120.dp
            // Center padding pushes first item to middle of container
            val centerPadding = (containerHeight - itemHeight) / 2

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(containerHeight),
                contentAlignment = Alignment.Center
            ) {
                LazyColumn(
                    state = listState,
                    flingBehavior = flingBehavior,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(vertical = centerPadding),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(values) { index, floatVal ->
                        val isSelected = index == listState.firstVisibleItemIndex

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(itemHeight)
                                .alpha(if (isSelected) 1f else 0.5f)
                                .then(
                                    if (isSelected && !isEditing) {
                                        Modifier.clickable { isEditing = true }
                                    } else {
                                        Modifier
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected && isEditing) {
                                // Inline TextField for editing
                                BasicTextField(
                                    value = inputText,
                                    onValueChange = { inputText = it },
                                    textStyle = TextStyle(
                                        fontSize = MaterialTheme.typography.headlineMedium.fontSize,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        textAlign = TextAlign.Center
                                    ),
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = if (step >= 1.0f) KeyboardType.Number else KeyboardType.Decimal,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = { commitEdit() }
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .focusRequester(focusRequester)
                                        .onFocusChanged { focusState ->
                                            if (focusState.isFocused) {
                                                hasFocusedOnce = true
                                            } else if (hasFocusedOnce && isEditing) {
                                                // Only commit if we actually had focus before losing it
                                                commitEdit()
                                            }
                                        }
                                )
                            } else {
                                // Regular Text display
                                Text(
                                    text = formatValue(floatVal),
                                    style = if (isSelected)
                                        MaterialTheme.typography.headlineMedium
                                    else
                                        MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }

                // Selection indicator lines - positioned to frame center item
                HorizontalDivider(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = centerPadding - 2.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
                HorizontalDivider(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = centerPadding - 2.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
            }

            // Increase button
            IconButton(
                onClick = {
                    val newIndex = (currentIndex + 1).coerceIn(values.indices)
                    onValueChange(values[newIndex])
                    coroutineScope.launch {
                        listState.animateScrollToItem(newIndex)
                    }
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
