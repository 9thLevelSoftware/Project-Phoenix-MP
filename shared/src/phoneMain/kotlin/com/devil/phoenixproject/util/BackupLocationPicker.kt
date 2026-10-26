package com.devil.phoenixproject.util

import androidx.compose.runtime.Composable

/**
 * Platform-specific directory picker for selecting a custom backup destination.
 *
 * Follows the same Composable pattern as [FilePicker]:
 * ```
 * val picker = rememberBackupLocationPicker()
 *
 * // Launch the directory picker:
 * picker.LaunchDirectoryPicker { destination ->
 *     destination?.let { viewModel.setBackupDestination(it) }
 * }
 * ```
 *
 * Platform details:
 * - **Android**: Uses [OpenDocumentTree] with [takePersistableUriPermission] for
 *   surviving reboots. Display name extracted via [DocumentFile.fromTreeUri].
 * - **iOS**: Uses [UIDocumentPickerViewController] with [UTTypeFolder].
 *   Creates a security-scoped bookmark (Base64-encoded) for persistent cross-launch access.
 */
expect class BackupLocationPicker {
    /**
     * Composable that launches a platform directory picker.
     *
     * @param onDirectoryPicked Callback with the chosen [BackupDestination.Custom],
     *        or null if the user cancelled.
     */
    @Composable
    fun LaunchDirectoryPicker(onDirectoryPicked: (BackupDestination.Custom?) -> Unit)
}

/**
 * Remember a [BackupLocationPicker] instance for use in Compose.
 */
@Composable
expect fun rememberBackupLocationPicker(): BackupLocationPicker
