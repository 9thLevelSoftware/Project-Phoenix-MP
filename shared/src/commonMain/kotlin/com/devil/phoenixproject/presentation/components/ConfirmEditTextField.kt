package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import com.devil.phoenixproject.presentation.util.rememberIsTvRemoteInputMode

class ConfirmEditTextFieldState(val isTvRemoteInputMode: Boolean) {
    var isEditing by mutableStateOf(false)
        private set

    fun onFocusChanged(isFocused: Boolean) {
        if (!isFocused) {
            isEditing = false
        }
    }

    fun onConfirmKey(): Boolean {
        if (!isTvRemoteInputMode || isEditing) return false
        isEditing = true
        return true
    }

    fun onDismissEditing(): Boolean {
        if (!isTvRemoteInputMode || !isEditing) return false
        isEditing = false
        return true
    }

    fun effectiveReadOnly(originalReadOnly: Boolean): Boolean = originalReadOnly || (isTvRemoteInputMode && !isEditing)
}

@Composable
fun ConfirmEditTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    label: @Composable (() -> Unit)? = null,
    placeholder: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    singleLine: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    shape: Shape = OutlinedTextFieldDefaults.shape,
    colors: TextFieldColors = OutlinedTextFieldDefaults.colors(),
) {
    val isTvRemoteInputMode = rememberIsTvRemoteInputMode()
    val state = remember(isTvRemoteInputMode) { ConfirmEditTextFieldState(isTvRemoteInputMode) }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(isTvRemoteInputMode, state.isEditing) {
        if (isTvRemoteInputMode && state.isEditing) {
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    val effectiveModifier = modifier
        .focusRequester(focusRequester)
        .onFocusChanged {
            if (isTvRemoteInputMode) {
                state.onFocusChanged(it.isFocused)
            }
        }
        .onPreviewKeyEvent { event ->
            if (!isTvRemoteInputMode) {
                false
            } else {
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter,
                    Key.NumPadEnter,
                    Key.DirectionCenter,
                    -> state.onConfirmKey()

                    Key.Back,
                    Key.Escape,
                    -> {
                        if (state.onDismissEditing()) {
                            keyboardController?.hide()
                            true
                        } else {
                            false
                        }
                    }

                    else -> false
                }
            }
        }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = effectiveModifier,
        enabled = enabled,
        readOnly = state.effectiveReadOnly(readOnly),
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        suffix = suffix,
        isError = isError,
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = shape,
        colors = colors,
    )
}
