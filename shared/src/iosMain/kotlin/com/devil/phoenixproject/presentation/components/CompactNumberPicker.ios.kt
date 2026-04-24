package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WindowHeightSizeClass
import com.devil.phoenixproject.presentation.util.WindowWidthSizeClass
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private data class PickerSizing(
    val itemHeight: Dp,
    val containerHeight: Dp,
    val selectedTextStyle: TextStyle,
    val unselectedTextStyle: TextStyle,
    val buttonSize: Dp,
    val compactOverlayTextStyle: TextStyle,
)

@Composable
private fun rememberPickerSizing(): PickerSizing {
    val windowSizeClass = LocalWindowSizeClass.current
    val fontScale = LocalDensity.current.fontScale
    val typography = MaterialTheme.typography
    val isCompactWidth = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact
    val compactHeightMode =
        isCompactWidth && windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact && fontScale <= 1.05f

    val itemHeight: Dp
    val selectedTextStyle: TextStyle
    val buttonSize: Dp
    val compactOverlayTextStyle: TextStyle

    when {
        compactHeightMode -> {
            // Compact width + compact height (e.g. iPhone landscape)
            itemHeight = 30.dp
            selectedTextStyle = typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 22.sp)
            buttonSize = 40.dp
            compactOverlayTextStyle = selectedTextStyle
        }

        isCompactWidth -> {
            // Compact width + normal height (e.g. iPhone portrait)
            itemHeight = 36.dp
            selectedTextStyle = typography.titleLarge
            buttonSize = 40.dp
            compactOverlayTextStyle = typography.titleMedium.copy(fontSize = 18.sp, lineHeight = 22.sp)
        }

        else -> {
            // Medium+ width (iPad, large phones landscape)
            itemHeight = 40.dp
            selectedTextStyle = if (fontScale > 1.15f) typography.titleLarge else typography.headlineMedium
            buttonSize = 48.dp
            compactOverlayTextStyle = typography.titleMedium
        }
    }

    return PickerSizing(
        itemHeight = itemHeight,
        containerHeight = itemHeight * 3,
        selectedTextStyle = selectedTextStyle,
        unselectedTextStyle = if (compactHeightMode) typography.bodyMedium else typography.bodyLarge,
        buttonSize = buttonSize,
        compactOverlayTextStyle = compactOverlayTextStyle,
    )
}

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
    step: Float,
) {
    // Generate values deterministically to avoid floating-point drift on iOS.
    val values = remember(range.start, range.endInclusive, step) {
        if (step <= 0f || range.start > range.endInclusive) {
            emptyList()
        } else {
            buildList {
                val start = range.start
                val end = range.endInclusive
                val totalSteps = (((end - start) / step).toInt()).coerceAtLeast(0)
                for (index in 0..totalSteps) {
                    add((start + (index * step)).coerceIn(start, end))
                }
                if (isEmpty() || abs(last() - end) > 0.0001f) {
                    add(end)
                }
            }
        }
    }

    // Find current index - use minByOrNull to find CLOSEST value regardless of precision
    // This handles unit conversions (e.g., 20kg -> 44.0924 lbs) where exact matching fails
    val currentIndex = remember(value, values) {
        if (values.isEmpty()) {
            0
        } else {
            values.indices.minByOrNull { abs(values[it] - value) } ?: 0
        }
    }
    val safeCurrentIndex = if (values.isEmpty()) 0 else currentIndex.coerceIn(values.indices)

    // Format a value for display
    fun formatValue(floatVal: Float): String {
        val formatted = formatCompactNumberPickerValue(floatVal, step)
        return if (suffix.isNotEmpty()) "$formatted $suffix" else formatted
    }

    val listState = rememberLazyListState(initialFirstVisibleItemIndex = safeCurrentIndex)
    val coroutineScope = rememberCoroutineScope()
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val focusManager = LocalFocusManager.current
    val pickerSizing = rememberPickerSizing()

    // Inline editing state
    var isEditing by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    // Issue #166 Fix: Track focus state per edit session to prevent premature commitEdit
    // hasFocusedOnce is ONLY true after focus is gained in the CURRENT edit session
    var hasFocusedOnce by remember { mutableStateOf(false) }
    // Guard to prevent commitEdit during initial TextField composition
    var editSessionReady by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // Issue #166: Track if user is actively interacting with the wheel
    // This prevents the external value sync from fighting with scroll position
    var isUserInteracting by remember { mutableStateOf(false) }
    // Track the last value we set via scroll to prevent feedback loops
    var lastScrollSetValue by remember { mutableStateOf(value) }

    // Issue #359 Fix: Track whether the current scroll was initiated by a real user drag.
    // Programmatic scrolls (commitEdit, +/- buttons, external value sync) must NOT write
    // the scroll position back to onValueChange — doing so created a feedback loop where
    // a stale `centeredVisibleIndex` (iOS layoutInfo lag) would counter-write the user's
    // value, producing the -1 lb drift and making the + button appear non-functional.
    var wasUserDragging by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                wasUserDragging = true
            }
        }
    }

    // Issue #224 Fix: Use rememberUpdatedState to ensure LaunchedEffects always see
    // the current value, not a stale captured value from composition time
    val currentValue by rememberUpdatedState(value)
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    // Issue #359 Fix: Calculate target index from current value prop for re-sync on layout changes
    // This ensures we always know which index the value corresponds to, regardless of scroll position
    val targetIndexFromValue = remember(value, values) {
        if (values.isEmpty()) 0 else values.indices.minByOrNull { abs(values[it] - value) } ?: 0
    }

    // True center index derived from current viewport/layout rather than first visible item.
    val centeredVisibleIndex by remember(listState, values) {
        derivedStateOf {
            if (values.isEmpty()) {
                0
            } else {
                val layoutInfo = listState.layoutInfo
                val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                val nearestCenteredItem = layoutInfo.visibleItemsInfo.minByOrNull { itemInfo ->
                    abs((itemInfo.offset + itemInfo.size / 2) - viewportCenter)
                }
                (nearestCenteredItem?.index ?: listState.firstVisibleItemIndex).coerceIn(values.indices)
            }
        }
    }

    // Issue #359 Fix: Re-sync scroll position when itemHeight changes (e.g., screen rotation)
    // Without this, changing itemHeight shifts which item appears "centered" in the viewport
    LaunchedEffect(pickerSizing.itemHeight) {
        if (!isUserInteracting && !isEditing && values.isNotEmpty()) {
            if (centeredVisibleIndex != targetIndexFromValue) {
                listState.scrollToItem(targetIndexFromValue)
            }
        }
    }

    // Keep list position valid and in sync when value/range changes.
    LaunchedEffect(values.size, safeCurrentIndex) {
        if (values.isNotEmpty()) {
            val clampedIndex = listState.firstVisibleItemIndex.coerceIn(values.indices)
            if (clampedIndex != listState.firstVisibleItemIndex) {
                listState.scrollToItem(clampedIndex)
            }
            if (clampedIndex != safeCurrentIndex) {
                listState.scrollToItem(safeCurrentIndex)
            }
        }
    }

    // Format value for editing (without suffix)
    fun formatValueForEdit(floatVal: Float): String = formatCompactNumberPickerValue(floatVal, step)

    fun formatOverlayValue(floatVal: Float, useCompactOverlay: Boolean): String = if (useCompactOverlay) formatValueForEdit(floatVal) else formatValue(floatVal)

    // Commit the edited value
    // Issue #4 Fix: Sanitize input and handle parse failure gracefully
    fun commitEdit() {
        if (values.isEmpty()) {
            isEditing = false
            focusManager.clearFocus()
            return
        }

        // Sanitize input: remove non-numeric chars except decimal point and minus
        val sanitized = inputText.filter { it.isDigit() || it == '.' || it == '-' }
        val parsed = sanitized.toFloatOrNull()

        if (parsed != null) {
            val clamped = parsed.coerceIn(range)
            val closestIndex = values.indices.minByOrNull { abs(values[it] - clamped) } ?: 0
            val newValue = values[closestIndex]
            lastScrollSetValue = newValue
            onValueChange(newValue)
            coroutineScope.launch {
                listState.animateScrollToItem(closestIndex)
            }
        } else {
            // Parse failed - keep current scroll position value instead of defaulting to max
            val currentScrollIndex = centeredVisibleIndex.coerceIn(values.indices)
            val currentValue = values[currentScrollIndex]
            lastScrollSetValue = currentValue
            onValueChange(currentValue)
            Logger.w { "CompactNumberPicker: Failed to parse '$inputText', keeping current value" }
        }
        isEditing = false
        focusManager.clearFocus()
    }

    // Issue #166 Fix: Only sync list position when external value changes from OUTSIDE
    // (not from our own scroll updates) and user is not currently interacting
    // IMPORTANT: isEditing MUST be a key to prevent race condition where effect starts
    // with stale isEditing=false while user is tapping to edit
    // Issue #224 Fix: Use currentValue (rememberUpdatedState) to avoid stale captures
    LaunchedEffect(currentIndex, isUserInteracting, isEditing) {
        if (!isUserInteracting && !isEditing) {
            // Check if this is a genuine external value change (not from scroll)
            val externalValueChanged = abs(currentValue - lastScrollSetValue) > 0.001f
            if (externalValueChanged && centeredVisibleIndex != currentIndex) {
                lastScrollSetValue = currentValue
                listState.animateScrollToItem(currentIndex.coerceAtLeast(0))
            }
        }
    }

    // Focus the text field when editing starts
    LaunchedEffect(isEditing) {
        if (isEditing) {
            // Issue #166 Fix: Reset focus tracking at start of each edit session
            // MUST set these BEFORE any delays to prevent race with onFocusChanged
            hasFocusedOnce = false
            editSessionReady = false
            // Issue #359 Fix: Prefer the external value prop when not actively scrolling.
            // centeredVisibleIndex can lag on iOS after programmatic scrolls, which would
            // previously start the edit session showing a stale value (e.g. "39.0" when
            // the real value was 40.0), making the next commit drift the weight downward.
            val currentScrollIndex = if (values.isEmpty()) {
                0
            } else if (listState.isScrollInProgress || isUserInteracting) {
                centeredVisibleIndex.coerceIn(values.indices)
            } else {
                safeCurrentIndex
            }
            val currentScrollValue = values.getOrElse(currentScrollIndex) { value }
            inputText = formatValueForEdit(currentScrollValue)
            // Issue #166 Fix: iOS needs more time after clearFocus() before focus can be requested again.
            // Use retry loop with increasing delays to ensure keyboard appears reliably.
            for (attempt in 1..3) {
                kotlinx.coroutines.delay(if (attempt == 1) 150L else 100L)
                try {
                    focusRequester.requestFocus()
                    // Mark session as ready AFTER successful focus request
                    editSessionReady = true
                    break // Success, exit retry loop
                } catch (_: Exception) {
                    // Focus request failed, will retry if attempts remain
                }
            }
        } else {
            // Reset when exiting edit mode
            editSessionReady = false
        }
    }

    // Track scroll state changes and update value when scroll settles
    // Issue #224 Fix: Use currentValue and currentOnValueChange to avoid stale captures
    // Issue #359 Fix: Only write the scroll position back to onValueChange when the user
    // actually dragged the wheel (wasUserDragging). Programmatic scrolls (commitEdit,
    // +/- buttons, external sync) would otherwise fire this listener with a stale
    // centeredVisibleIndex on iOS, counter-writing the user's intended value and causing
    // the weight to drift downward each cycle.
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            isUserInteracting = true
        } else {
            if (wasUserDragging) {
                val centerIndex = centeredVisibleIndex.coerceIn(values.indices)
                if (centerIndex in values.indices) {
                    val scrollValue = values[centerIndex]
                    Logger.i {
                        "PICKER_DEBUG[iOS]: user-drag settle centerIndex=$centerIndex, scrollValue=$scrollValue, currentValue=$currentValue, values.size=${values.size}"
                    }
                    if (abs(scrollValue - currentValue) > 0.001f) {
                        lastScrollSetValue = scrollValue
                        currentOnValueChange(scrollValue)
                    }
                }
                wasUserDragging = false
            }
            // Small delay before allowing external sync again
            kotlinx.coroutines.delay(50)
            isUserInteracting = false
        }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        // Blended index: use viewport-derived index during/just-after scroll so +/-
        // matches what the user currently sees; fall back to value-prop-derived index
        // when idle so buttons stay consistent with the external state.
        val actionIndex =
            if (listState.isScrollInProgress || isUserInteracting) {
                centeredVisibleIndex
            } else {
                safeCurrentIndex
            }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Decrease button
            IconButton(
                onClick = {
                    if (values.isNotEmpty()) {
                        val baseIndex = actionIndex.coerceIn(values.indices)
                        val newIndex = (baseIndex - 1).coerceIn(values.indices)
                        if (newIndex != baseIndex) {
                            val newValue = values[newIndex]
                            lastScrollSetValue = newValue
                            currentOnValueChange(newValue)
                            coroutineScope.launch {
                                listState.animateScrollToItem(newIndex)
                            }
                        }
                    }
                },
                enabled = values.isNotEmpty() && actionIndex > 0,
                modifier = Modifier.size(pickerSizing.buttonSize),
            ) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = "Decrease $label",
                    tint = if (values.isNotEmpty() && actionIndex > 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }

            val itemHeight = pickerSizing.itemHeight
            val containerHeight = pickerSizing.containerHeight
            // Center padding pushes first item to middle of container
            val centerPadding = (containerHeight - itemHeight) / 2

            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .height(containerHeight),
                contentAlignment = Alignment.Center,
            ) {
                val showCenteredOverlay = !isEditing && values.isNotEmpty()
                // Side-by-side pickers on narrow iPhone layouts do not have enough space
                // for the full suffixed string at the large overlay style.
                val useCompactOverlay = maxWidth < 110.dp
                val overlayTextStyle =
                    if (useCompactOverlay) pickerSizing.compactOverlayTextStyle else pickerSizing.selectedTextStyle
                val editButtonSize = if (useCompactOverlay) 20.dp else 24.dp
                val editIconSize = if (useCompactOverlay) 14.dp else 16.dp

                LazyColumn(
                    state = listState,
                    flingBehavior = flingBehavior,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(vertical = centerPadding),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(values) { index, floatVal ->
                        val isSelected = index == centeredVisibleIndex

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(itemHeight)
                                // Issue #265 Fix: Selected item invisible when not editing —
                                // overlay Text renders the value. Show item only when editing (TextField).
                                .alpha(
                                    if (isSelected) {
                                        if (isEditing) 1f else 0f
                                    } else {
                                        0.5f
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (isSelected && isEditing) {
                                // Issue #166 Fix: Row with TextField and visible Done button
                                // iOS keyboard may not show Done button for numeric input
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    BasicTextField(
                                        value = inputText,
                                        onValueChange = { inputText = it },
                                        textStyle = TextStyle(
                                            fontSize = pickerSizing.selectedTextStyle.fontSize,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center,
                                        ),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = if (step >= 1.0f) KeyboardType.Number else KeyboardType.Decimal,
                                            imeAction = ImeAction.Done,
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onDone = { commitEdit() },
                                        ),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        modifier = Modifier
                                            .weight(1f)
                                            .focusRequester(focusRequester)
                                            .onFocusChanged { focusState ->
                                                if (focusState.isFocused) {
                                                    hasFocusedOnce = true
                                                } else if (editSessionReady && hasFocusedOnce && isEditing) {
                                                    // Issue #166 Fix: Only commit if:
                                                    // 1. editSessionReady = focus was successfully requested (not during initial composition)
                                                    // 2. hasFocusedOnce = we actually had focus in this session
                                                    // 3. isEditing = we're still in edit mode
                                                    commitEdit()
                                                }
                                            },
                                    )
                                    // Visible Done button for iOS
                                    IconButton(
                                        onClick = { commitEdit() },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Done",
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            } else if (!(showCenteredOverlay && isSelected)) {
                                // Regular Text display (no suffix — overlay shows suffix for selected)
                                Text(
                                    text = formatValueForEdit(floatVal),
                                    style = if (isSelected) {
                                        pickerSizing.selectedTextStyle
                                    } else {
                                        pickerSizing.unselectedTextStyle
                                    },
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                if (showCenteredOverlay) {
                    // Issue #359 Fix: When idle, trust the external value prop over the
                    // scroll-derived center index. centeredVisibleIndex can lag behind
                    // layout on iOS, which previously made the overlay visibly disagree
                    // with the parent state after programmatic scrolls.
                    val previewIndex = if (listState.isScrollInProgress || isUserInteracting) {
                        centeredVisibleIndex.coerceIn(values.indices)
                    } else {
                        safeCurrentIndex
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = formatOverlayValue(values[previewIndex], useCompactOverlay),
                            style = overlayTextStyle,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            onClick = { isEditing = true },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(editButtonSize),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit $label",
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                modifier = Modifier.size(editIconSize),
                            )
                        }
                    }
                }

                // Selection indicator lines - positioned to frame center item
                HorizontalDivider(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = -(itemHeight / 2)),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                )
                HorizontalDivider(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = itemHeight / 2),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                )
            }

            // Increase button
            IconButton(
                onClick = {
                    if (values.isNotEmpty()) {
                        val baseIndex = actionIndex.coerceIn(values.indices)
                        val newIndex = (baseIndex + 1).coerceIn(values.indices)
                        if (newIndex != baseIndex) {
                            val newValue = values[newIndex]
                            lastScrollSetValue = newValue
                            currentOnValueChange(newValue)
                            coroutineScope.launch {
                                listState.animateScrollToItem(newIndex)
                            }
                        }
                    }
                },
                enabled = values.isNotEmpty() && actionIndex < values.lastIndex,
                modifier = Modifier.size(pickerSizing.buttonSize),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Increase $label",
                    tint = if (values.isNotEmpty() && actionIndex < values.lastIndex) {
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
) {
    CompactNumberPicker(
        value = value.toFloat(),
        onValueChange = { onValueChange(it.roundToInt()) },
        range = range.first.toFloat()..range.last.toFloat(),
        modifier = modifier,
        label = label,
        suffix = suffix,
        step = 1.0f,
    )
}
