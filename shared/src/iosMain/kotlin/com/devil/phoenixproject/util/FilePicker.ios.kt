package com.devil.phoenixproject.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*

/**
 * iOS implementation of FilePicker.
 *
 * For MVP, file picking returns null and relies on the existing
 * IosDataBackupManager.getAvailableBackups() to list available backups.
 *
 * File saving writes directly to the app's Documents/VitruvianBackups directory.
 *
 * Note: Full UIDocumentPickerViewController integration would require
 * additional SwiftUI/UIKit interop which is planned for a future release.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class FilePicker {

    @Composable
    actual fun LaunchFilePicker(onFilePicked: (String?) -> Unit) {
        // iOS document picker implementation
        // For now, return null and rely on the existing IosDataBackupManager.getAvailableBackups()
        // TODO: Implement UIDocumentPickerViewController integration for full file picker support
        LaunchedEffect(Unit) {
            onFilePicked(null)
        }
    }

    @Composable
    actual fun LaunchFileSaver(
        fileName: String,
        content: String,
        onSaved: (String?) -> Unit
    ) {
        // iOS uses direct file save to Documents directory
        LaunchedEffect(Unit) {
            val result = saveToBackupDirectory(fileName, content)
            onSaved(result)
        }
    }

    /**
     * Save content to the VitruvianBackups directory in Documents.
     * Returns the file path on success, null on failure.
     */
    private fun saveToBackupDirectory(fileName: String, content: String): String? {
        return try {
            val fileManager = NSFileManager.defaultManager
            val paths = NSSearchPathForDirectoriesInDomains(
                NSDocumentDirectory,
                NSUserDomainMask,
                true
            )
            @Suppress("USELESS_CAST")
            val documentsDir = paths.firstOrNull() as? String ?: return null

            val backupDir = "$documentsDir/VitruvianBackups"
            val dirUrl = NSURL.fileURLWithPath(backupDir)

            // Create backup directory if it doesn't exist
            if (!fileManager.fileExistsAtPath(backupDir)) {
                fileManager.createDirectoryAtURL(
                    dirUrl,
                    withIntermediateDirectories = true,
                    attributes = null,
                    error = null
                )
            }

            val filePath = "$backupDir/$fileName"
            // Use NSString.create to convert Kotlin String to NSString for encoding
            val nsString = NSString.create(string = content)
            val data = nsString.dataUsingEncoding(NSUTF8StringEncoding)

            if (data != null && data.writeToFile(filePath, atomically = true)) {
                filePath
            } else {
                null
            }
        } catch (e: Exception) {
            null
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
