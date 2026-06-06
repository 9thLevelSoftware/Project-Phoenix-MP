package com.devil.phoenixproject.presentation.components

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfirmEditTextFieldStateTest {

    @Test
    fun focusAloneDoesNotEnterEditModeInTvMode() {
        val state = ConfirmEditTextFieldState(isTvRemoteInputMode = true)

        state.onFocusChanged(isFocused = true)

        assertFalse(state.isEditing)
        assertTrue(state.effectiveReadOnly(originalReadOnly = false))
    }

    @Test
    fun confirmKeyEntersEditModeInTvMode() {
        val state = ConfirmEditTextFieldState(isTvRemoteInputMode = true)

        val consumed = state.onConfirmKey()

        assertTrue(consumed)
        assertTrue(state.isEditing)
        assertFalse(state.effectiveReadOnly(originalReadOnly = false))
    }

    @Test
    fun dismissEditingExitsEditModeInTvMode() {
        val state = ConfirmEditTextFieldState(isTvRemoteInputMode = true)
        state.onConfirmKey()

        val consumed = state.onDismissEditing()

        assertTrue(consumed)
        assertFalse(state.isEditing)
        assertTrue(state.effectiveReadOnly(originalReadOnly = false))
    }

    @Test
    fun nonTvModeKeepsNormalTextFieldReadOnlyBehavior() {
        val state = ConfirmEditTextFieldState(isTvRemoteInputMode = false)

        assertFalse(state.onConfirmKey())
        assertFalse(state.effectiveReadOnly(originalReadOnly = false))
        assertTrue(state.effectiveReadOnly(originalReadOnly = true))
    }
}
