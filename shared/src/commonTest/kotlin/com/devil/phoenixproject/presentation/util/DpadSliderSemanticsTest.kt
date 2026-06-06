package com.devil.phoenixproject.presentation.util

import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DpadSliderSemanticsTest {

    @Test
    fun leftDecrementsByExplicitStep() {
        val result = calculateDpadSliderKeyResult(
            command = DpadSliderCommand.Left,
            value = 50f,
            valueRange = 0f..100f,
            steps = 0,
            explicitStep = 5f,
            enabled = true,
        )

        assertEquals(true, result.consumed)
        assertEquals(45f, result.newValue)
        assertNull(result.focusMove)
    }

    @Test
    fun rightIncrementsByExplicitStep() {
        val result = calculateDpadSliderKeyResult(
            command = DpadSliderCommand.Right,
            value = 50f,
            valueRange = 0f..100f,
            steps = 0,
            explicitStep = 5f,
            enabled = true,
        )

        assertEquals(true, result.consumed)
        assertEquals(55f, result.newValue)
        assertNull(result.focusMove)
    }

    @Test
    fun materialStepsDeriveIntervalWhenExplicitStepMissing() {
        val result = calculateDpadSliderKeyResult(
            command = DpadSliderCommand.Right,
            value = 50f,
            valueRange = 0f..100f,
            steps = 3,
            explicitStep = null,
            enabled = true,
        )

        assertEquals(true, result.consumed)
        assertEquals(75f, result.newValue)
    }

    @Test
    fun clampsAtMinimumAndMaximum() {
        val minResult = calculateDpadSliderKeyResult(
            command = DpadSliderCommand.Left,
            value = 0f,
            valueRange = 0f..100f,
            steps = 0,
            explicitStep = 5f,
            enabled = true,
        )
        val maxResult = calculateDpadSliderKeyResult(
            command = DpadSliderCommand.Right,
            value = 100f,
            valueRange = 0f..100f,
            steps = 0,
            explicitStep = 5f,
            enabled = true,
        )

        assertEquals(0f, minResult.newValue)
        assertEquals(100f, maxResult.newValue)
    }

    @Test
    fun disabledSliderDoesNotHandleDirectionalInput() {
        val result = calculateDpadSliderKeyResult(
            command = DpadSliderCommand.Right,
            value = 50f,
            valueRange = 0f..100f,
            steps = 0,
            explicitStep = 5f,
            enabled = false,
        )

        assertFalse(result.consumed)
        assertNull(result.newValue)
        assertNull(result.focusMove)
    }

    @Test
    fun upAndDownMoveFocusWithoutChangingValue() {
        val upResult = calculateDpadSliderKeyResult(
            command = DpadSliderCommand.Up,
            value = 50f,
            valueRange = 0f..100f,
            steps = 0,
            explicitStep = 5f,
            enabled = true,
        )
        val downResult = calculateDpadSliderKeyResult(
            command = DpadSliderCommand.Down,
            value = 50f,
            valueRange = 0f..100f,
            steps = 0,
            explicitStep = 5f,
            enabled = true,
        )

        assertEquals(true, upResult.consumed)
        assertNull(upResult.newValue)
        assertEquals(DpadSliderFocusMove.Up, upResult.focusMove)
        assertEquals(true, downResult.consumed)
        assertNull(downResult.newValue)
        assertEquals(DpadSliderFocusMove.Down, downResult.focusMove)
    }

    @Test
    fun leftAndRightFinishOnlyOnKeyUp() {
        assertFalse(
            shouldFinishDpadSliderValueChange(
                command = DpadSliderCommand.Right,
                eventType = KeyEventType.KeyDown,
                enabled = true,
            ),
        )
        assertTrue(
            shouldFinishDpadSliderValueChange(
                command = DpadSliderCommand.Right,
                eventType = KeyEventType.KeyUp,
                enabled = true,
            ),
        )
        assertTrue(
            shouldFinishDpadSliderValueChange(
                command = DpadSliderCommand.Left,
                eventType = KeyEventType.KeyUp,
                enabled = true,
            ),
        )
    }

    @Test
    fun focusMovesAndDisabledSliderDoNotFinishValueChange() {
        assertFalse(
            shouldFinishDpadSliderValueChange(
                command = DpadSliderCommand.Up,
                eventType = KeyEventType.KeyUp,
                enabled = true,
            ),
        )
        assertFalse(
            shouldFinishDpadSliderValueChange(
                command = DpadSliderCommand.Right,
                eventType = KeyEventType.KeyUp,
                enabled = false,
            ),
        )
    }
}
