package com.devil.phoenixproject.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        // R2: provider metadata resolution is cancellable background work owned
        // by this composable's lifecycle (the coordinator's Picking phase owns
        // the busy state across the whole launch → terminal-result window).
        val scope = rememberCoroutineScope()
        val batch = remember { AndroidPickerBatch(Dispatchers.IO) }
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris: List<Uri> ->
            if (uris.isEmpty()) {
                // User dismissed the picker: clean no-op (SD1).
                onResult(MediaPickResult.Cancelled)
            } else {
                // R2: ContentResolver.query/cursor metadata reads must never run
                // on the main thread — a slow document provider can block
                // rendering before any progress begins. The callback hands the
                // batch to the resolver and returns immediately.
                batch.resolveAndDeliver(
                    scope = scope,
                    handles = uris.map { it.toString() },
                    resolveOne = { handle -> handle.toPickedPhoto(context) },
                    onResult = onResult,
                )
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

/**
 * Android handles are provider `content://` URIs with transient grants — there
 * is no app-owned temp copy to delete (R3 is the iOS picker-temp namespace).
 */
actual fun releasePickerOwnedHandle(photo: PickedPhoto) {
    // No-op by design; see the expect contract in MediaPicker.kt.
}

private val log = Logger.withTag("MediaPicker.Android")

/**
 * R2: off-main resolution of a picked batch's provider metadata.
 *
 * Extracted from the activity-result callback so the responsiveness contract
 * is testable: the caller (always on Main) returns immediately, every
 * [resolveOne] provider query/cursor read runs on [ioDispatcher] inside the
 * caller-owned [scope] (cancelled with the picker's composable), and exactly
 * one whole-batch [MediaPickResult] is delivered afterwards (SD11 whole-batch
 * semantics preserved — a batch is delivered complete or not at all).
 */
internal class AndroidPickerBatch(
    private val ioDispatcher: CoroutineDispatcher,
) {
    fun resolveAndDeliver(
        scope: CoroutineScope,
        handles: List<String>,
        resolveOne: (String) -> PickedPhoto,
        onResult: (MediaPickResult) -> Unit,
    ): Job = scope.launch {
        val photos = withContext(ioDispatcher) {
            handles.map { handle -> resolveOne(handle) }
        }
        onResult(MediaPickResult.Picked(photos))
    }
}

/**
 * Resolve display name and byte size from the document provider when cheaply
 * available. Unknown values stay null/derived — the media store revalidates
 * everything at copy time (SD11: picker limits are never trusted).
 */
private fun String.toPickedPhoto(context: Context): PickedPhoto {
    val uri = Uri.parse(this)
    var displayName: String? = null
    var byteSize: Long? = null
    try {
        context.contentResolver.query(
            uri,
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
        handle = this,
        displayName = displayName ?: "photo",
        byteSize = byteSize,
    )
}
