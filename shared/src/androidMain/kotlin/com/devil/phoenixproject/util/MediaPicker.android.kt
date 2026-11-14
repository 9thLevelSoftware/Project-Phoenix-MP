package com.devil.phoenixproject.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import co.touchlab.kermit.Logger

/**
 * Android implementation of [MediaPicker] (issue #1358, SD1).
 *
 * Uses `ActivityResultContracts.OpenMultipleDocuments` with the image MIME
 * wildcard (string literal "image/" + "*") — the
 * concrete multi-select document contract. NOT single-select `OpenDocument`,
 * NOT `PickVisualMedia`. Opening documents through the Storage Access
 * Framework takes no `READ_MEDIA_*`/photo-library permission and shows no
 * permission prompt; camera and video are never offered.
 */
actual class MediaPicker {

    @Composable
    actual fun LaunchPhotoPicker(onResult: (MediaPickResult) -> Unit) {
        val context = LocalContext.current
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris: List<Uri> ->
            if (uris.isEmpty()) {
                // User dismissed the picker: clean no-op (SD1).
                onResult(MediaPickResult.Cancelled)
            } else {
                onResult(MediaPickResult.Picked(uris.map { it.toPickedPhoto(context) }))
            }
        }

        LaunchedEffect(Unit) {
            launcher.launch(arrayOf("image/*"))
        }
    }
}

/**
 * Remember a [MediaPicker] instance for use in Compose.
 */
@Composable
actual fun rememberMediaPicker(): MediaPicker = remember { MediaPicker() }

private val log = Logger.withTag("MediaPicker.Android")

/**
 * Resolve display name and byte size from the document provider when cheaply
 * available. Unknown values stay null/derived — the media store revalidates
 * everything at copy time (SD11: picker limits are never trusted).
 */
private fun Uri.toPickedPhoto(context: Context): PickedPhoto {
    var displayName: String? = null
    var byteSize: Long? = null
    try {
        context.contentResolver.query(
            this,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    displayName = cursor.getString(nameIndex)
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    byteSize = cursor.getLong(sizeIndex)
                }
            }
        }
    } catch (e: Exception) {
        log.w(e) { "Could not read picker metadata for $this" }
    }
    return PickedPhoto(
        handle = this.toString(),
        displayName = displayName ?: "photo",
        byteSize = byteSize,
    )
}
