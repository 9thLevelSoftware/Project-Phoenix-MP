package com.devil.phoenixproject.util

import androidx.compose.runtime.Composable

/**
 * One photo returned by the platform picker (issue #1358, SD1).
 *
 * [handle] is an opaque, transient reference: a `content://` URI string on
 * Android (the grant is only valid while the picker result is fresh) and a
 * picker-owned temporary file path on iOS (PHPicker's file representation only
 * exists during its callback, so the iOS picker copies it immediately — D4).
 * The media store consumes the handle at pick time and copies bytes into
 * app-private staging; the handle is never persisted.
 */
data class PickedPhoto(
    val handle: String,
    val displayName: String,
    val byteSize: Long?,
)

/**
 * Result of one photo-picking session (issue #1358, SD1).
 */
sealed interface MediaPickResult {
    /** User dismissed the picker: a clean no-op, never an error UI. */
    data object Cancelled : MediaPickResult

    /** One or more photos picked (multi-select). */
    data class Picked(val photos: List<PickedPhoto>) : MediaPickResult

    /** The platform picker itself failed. */
    data class Error(val message: String) : MediaPickResult
}

/**
 * Platform multi-select photo picker (issue #1358, SD1).
 *
 * Android: `ActivityResultContracts.OpenMultipleDocuments` with the image MIME
 * wildcard (string literal "image/" + "*")
 * (no storage/photos permission, no camera or video affordance).
 * iOS: `PHPickerViewController` with the images filter, unlimited selection,
 * `.current` asset representation (deployment target 15.0 — SD13).
 */
expect class MediaPicker {
    /**
     * Composable that launches the platform photo picker and delivers exactly
     * one [MediaPickResult].
     */
    @Composable
    fun LaunchPhotoPicker(onResult: (MediaPickResult) -> Unit)
}

/**
 * Remember a [MediaPicker] instance for use in Compose.
 */
@Composable
expect fun rememberMediaPicker(): MediaPicker
