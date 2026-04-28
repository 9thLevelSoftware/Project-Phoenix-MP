package com.devil.phoenixproject.util

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import co.touchlab.kermit.Logger

private val log = Logger.withTag("BackupLocationPicker.Android")

/**
 * Android implementation of [BackupLocationPicker].
 *
 * Uses [ActivityResultContracts.OpenDocumentTree] to let the user choose a directory,
 * then calls [takePersistableUriPermission] so the grant survives device reboots.
 * The display name is extracted via [DocumentFile.fromTreeUri].
 */
actual class BackupLocationPicker {

    @Composable
    actual fun LaunchDirectoryPicker(onDirectoryPicked: (BackupDestination.Custom?) -> Unit) {
        val context = LocalContext.current

        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri: Uri? ->
            if (uri != null) {
                try {
                    // Persist read/write permission across reboots
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(uri, flags)

                    // Extract a human-readable folder name
                    val displayName = DocumentFile.fromTreeUri(context, uri)?.name
                        ?: uri.lastPathSegment
                        ?: "Selected folder"

                    log.d { "Directory picked: $displayName ($uri)" }
                    onDirectoryPicked(
                        BackupDestination.Custom(
                            uri = uri.toString(),
                            displayName = displayName,
                            bookmarkData = null, // Not needed on Android
                        ),
                    )
                } catch (e: Exception) {
                    log.e(e) { "Failed to persist directory permission" }
                    onDirectoryPicked(null)
                }
            } else {
                onDirectoryPicked(null)
            }
        }

        LaunchedEffect(Unit) {
            launcher.launch(null)
        }
    }
}

/**
 * Remember a [BackupLocationPicker] instance for use in Compose.
 */
@Composable
actual fun rememberBackupLocationPicker(): BackupLocationPicker = remember { BackupLocationPicker() }
