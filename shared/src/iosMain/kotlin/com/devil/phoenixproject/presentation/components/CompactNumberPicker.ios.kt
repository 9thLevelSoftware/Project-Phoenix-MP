package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
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

    // Find current index
    val currentIndex = remember(value, values) {
        values.indexOfFirst { abs(it - value) < 0.001f }.coerceAtLeast(0)
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

    // Sync list position when external value changes
    LaunchedEffect(currentIndex) {
        if (listState.firstVisibleItemIndex != currentIndex) {
            listState.animateScrollToItem(currentIndex.coerceAtLeast(0))
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

            // Scrollable picker
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                LazyColumn(
                    state = listState,
                    flingBehavior = flingBehavior,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(values) { index, floatVal ->
                        val isSelected = index == listState.firstVisibleItemIndex
                        Text(
                            text = formatValue(floatVal),
                            style = if (isSelected)
                                MaterialTheme.typography.headlineMedium
                            else
                                MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .alpha(if (isSelected) 1f else 0.5f)
                        )
                    }
                }

                // Selection indicator lines
                HorizontalDivider(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 40.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
                HorizontalDivider(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 40.dp),
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
