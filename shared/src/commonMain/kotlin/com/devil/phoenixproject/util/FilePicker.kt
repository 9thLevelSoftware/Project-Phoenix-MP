package com.devil.phoenixproject.util

import androidx.compose.runtime.Composable

/**
 * Platform-specific file picker for backup/restore operations.
 *
 * Usage from Compose:
 * ```
 * val filePicker = rememberFilePicker()
 *
 * // To pick a file for import:
 * filePicker.LaunchFilePicker { uri ->
 *     uri?.let { viewModel.importFromFile(it) }
 * }
 *
 * // To save a file:
 * filePicker.LaunchFileSaver(
 *     fileName = "backup.json",
 *     content = jsonContent
 * ) { path ->
 *     path?.let { showSuccess("Saved to $it") }
 * }
 * ```
 */
expect class FilePicker {
    /**
     * Composable that launches a file picker to select a JSON file for import.
     * The picker UI is platform-specific (Android uses Document picker, iOS uses native picker).
     *
     * @param onFilePicked Callback with file URI/path as string, or null if cancelled
     */
    @Composable
    fun LaunchFilePicker(
        onFilePicked: (String?) -> Unit
    )

    /**
     * Composable that launches a save file dialog to export backup.
     * The save UI is platform-specific.
     *
     * @param fileName Suggested file name for the backup
     * @param content JSON content to save
     * @param onSaved Callback with saved file path, or null if cancelled/failed
     */
    @Composable
    fun LaunchFileSaver(
        fileName: String,
        content: String,
        onSaved: (String?) -> Unit
    )
}

/**
 * Remember a FilePicker instance for use in Compose.
 */
@Composable
expect fun rememberFilePicker(): FilePicker
