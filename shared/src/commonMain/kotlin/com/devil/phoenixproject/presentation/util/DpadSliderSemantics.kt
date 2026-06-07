package com.devil.phoenixproject.presentation.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

internal enum class DpadSliderCommand {
    Left,
    Right,
    Up,
    Down,
}

internal enum class DpadSliderFocusMove {
    Up,
    Down,
}

internal data class DpadSliderKeyResult(
    val consumed: Boolean,
    val newValue: Float? = null,
    val focusMove: DpadSliderFocusMove? = null,
)

internal fun calculateDpadSliderKeyResult(
    command: DpadSliderCommand,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    explicitStep: Float?,
    enabled: Boolean,
): DpadSliderKeyResult {
    if (!enabled) return DpadSliderKeyResult(consumed = false)

    return when (command) {
        DpadSliderCommand.Up -> DpadSliderKeyResult(
            consumed = true,
            focusMove = DpadSliderFocusMove.Up,
        )

        DpadSliderCommand.Down -> DpadSliderKeyResult(
            consumed = true,
            focusMove = DpadSliderFocusMove.Down,
        )

        DpadSliderCommand.Left,
        DpadSliderCommand.Right,
        -> {
            val step = resolveSliderStep(valueRange = valueRange, steps = steps, explicitStep = explicitStep)
                ?: return DpadSliderKeyResult(consumed = false)
            val delta = if (command == DpadSliderCommand.Left) -step else step
            DpadSliderKeyResult(
                consumed = true,
                newValue = (value + delta).coerceIn(valueRange),
            )
        }
    }
}

fun Modifier.tvSliderKeys(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    explicitStep: Float?,
    enabled: Boolean,
    isTvRemoteInputMode: Boolean,
    onValueChangeFinished: (() -> Unit)? = null,
): Modifier = composed {
    if (!isTvRemoteInputMode) {
        this
    } else {
        val focusManager = LocalFocusManager.current
        onPreviewKeyEvent { event ->
            val command = event.key.toDpadSliderCommand() ?: return@onPreviewKeyEvent false

            if (shouldFinishDpadSliderValueChange(command = command, eventType = event.type, enabled = enabled)) {
                onValueChangeFinished?.invoke()
                return@onPreviewKeyEvent true
            }

            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val result = calculateDpadSliderKeyResult(
                command = command,
                value = value,
                valueRange = valueRange,
                steps = steps,
                explicitStep = explicitStep,
                enabled = enabled,
            )

            result.focusMove?.let { focusMove ->
                val moved = focusManager.moveFocus(
                    when (focusMove) {
                        DpadSliderFocusMove.Up -> FocusDirection.Up
                        DpadSliderFocusMove.Down -> FocusDirection.Down
                    },
                )
                return@onPreviewKeyEvent moved
            }

            result.newValue?.let { newValue ->
                if (newValue != value) {
                    onValueChange(newValue)
                }
            }

            result.consumed
        }
    }
}

internal fun shouldFinishDpadSliderValueChange(
    command: DpadSliderCommand,
    eventType: KeyEventType,
    enabled: Boolean,
): Boolean = enabled &&
    eventType == KeyEventType.KeyUp &&
    (command == DpadSliderCommand.Left || command == DpadSliderCommand.Right)

private fun resolveSliderStep(
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    explicitStep: Float?,
): Float? {
    explicitStep?.takeIf { it > 0f }?.let { return it }

    val range = valueRange.endInclusive - valueRange.start
    if (range <= 0f) return null

    return if (steps > 0) {
        range / (steps + 1)
    } else {
        range / CONTINUOUS_SLIDER_REMOTE_INTERVALS
    }.takeIf { it > 0f }
}

private fun Key.toDpadSliderCommand(): DpadSliderCommand? = when (this) {
    Key.DirectionLeft -> DpadSliderCommand.Left
    Key.DirectionRight -> DpadSliderCommand.Right
    Key.DirectionUp -> DpadSliderCommand.Up
    Key.DirectionDown -> DpadSliderCommand.Down
    else -> null
}

private const val CONTINUOUS_SLIDER_REMOTE_INTERVALS = 100f
