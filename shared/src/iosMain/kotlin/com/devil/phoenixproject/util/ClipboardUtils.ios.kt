package com.devil.phoenixproject.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIPasteboard

@Composable
actual fun rememberCopyTextToClipboard(): (String) -> Unit = remember {
    { text: String ->
        UIPasteboard.generalPasteboard.string = text
    }
}
