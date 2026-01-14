package com.devil.phoenixproject.util

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/**
 * Android implementation of FilePicker using Activity Result APIs.
 * Uses ActivityResultContracts.OpenDocument for file picking and
 * ActivityResultContracts.CreateDocument for file saving.
 */
actual class FilePicker {

    @Composable
    actual fun LaunchFilePicker(onFilePicked: (String?) -> Unit) {
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            onFilePicked(uri?.toString())
        }

        LaunchedEffect(Unit) {
            launcher.launch(arrayOf("application/json"))
        }
    }

    @Composable
    actual fun LaunchFileSaver(
        fileName: String,
        content: String,
        onSaved: (String?) -> Unit
    ) {
        val context = LocalContext.current
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/json")
        ) { uri: Uri? ->
            if (uri != null) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(content.toByteArray())
                    }
                    onSaved(uri.toString())
                } catch (e: Exception) {
                    onSaved(null)
                }
            } else {
                onSaved(null)
            }
        }

        LaunchedEffect(Unit) {
            launcher.launch(fileName)
        }
    }
}

/**
 * Remember a FilePicker instance for use in Compose.
 */
@Composable
actual fun rememberFilePicker(): FilePicker {
    return remember { FilePicker() }
}
