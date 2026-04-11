package com.devil.phoenixproject.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun rememberCopyTextToClipboard(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        val clipboardManager =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        { text: String ->
            clipboardManager.setPrimaryClip(ClipData.newPlainText("Phoenix Logs", text))
        }
    }
}
