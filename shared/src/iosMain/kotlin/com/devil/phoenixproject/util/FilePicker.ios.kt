package com.devil.phoenixproject.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.Foundation.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.UTTypeJSON
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS implementation of FilePicker using UIDocumentPickerViewController.
 *
 * Uses the modern UIDocumentPickerViewController API with:
 * - forOpeningContentTypes for import (file selection)
 * - forExporting for export (save file to user-chosen location)
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class FilePicker {

    private val log = Logger.withTag("FilePicker.iOS")

    @Composable
    actual fun LaunchFilePicker(onFilePicked: (String?) -> Unit) {
        val scope = rememberCoroutineScope()

        // Create delegate that handles picker callbacks
        val delegate = remember {
            DocumentPickerDelegate(
                onDocumentPicked = { url ->
                    scope.launch(Dispatchers.Main) {
                        if (url != null) {
                            // Start security-scoped access
                            val accessing = url.startAccessingSecurityScopedResource()
                            try {
                                // Copy file to temp directory to ensure we have read access
                                val tempPath = copyToTempDirectory(url)
                                onFilePicked(tempPath)
                            } finally {
                                if (accessing) {
                                    url.stopAccessingSecurityScopedResource()
                                }
                            }
                        } else {
                            onFilePicked(null)
                        }
                    }
                },
                onCancelled = {
                    scope.launch(Dispatchers.Main) {
                        onFilePicked(null)
                    }
                },
                log = log
            )
        }

        LaunchedEffect(Unit) {
            dispatch_async(dispatch_get_main_queue()) {
                presentImportPicker(delegate)
            }
        }

        // Cleanup delegate reference when composable leaves composition
        DisposableEffect(Unit) {
            onDispose {
                delegate.cleanup()
            }
        }
    }

    @Composable
    actual fun LaunchFileSaver(
        fileName: String,
        content: String,
        onSaved: (String?) -> Unit
    ) {
        val scope = rememberCoroutineScope()

        // Create delegate that handles picker callbacks
        val delegate = remember {
            DocumentPickerDelegate(
                onDocumentPicked = { url ->
                    scope.launch(Dispatchers.Main) {
                        if (url != null) {
                            onSaved(url.path)
                        } else {
                            onSaved(null)
                        }
                    }
                },
                onCancelled = {
                    scope.launch(Dispatchers.Main) {
                        onSaved(null)
                    }
                },
                log = log
            )
        }

        LaunchedEffect(fileName, content) {
            // First, save content to a temp file
            val tempFilePath = saveToTempFile(fileName, content)
            if (tempFilePath == null) {
                onSaved(null)
                return@LaunchedEffect
            }

            dispatch_async(dispatch_get_main_queue()) {
                presentExportPicker(tempFilePath, delegate)
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                delegate.cleanup()
            }
        }
    }

    /**
     * Present document picker for importing a JSON file.
     */
    private fun presentImportPicker(delegate: DocumentPickerDelegate) {
        val rootViewController = getRootViewController() ?: run {
            log.e { "Could not get root view controller" }
            delegate.onCancelled()
            return
        }

        // Create picker for opening JSON files
        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = listOf(UTTypeJSON),
            asCopy = true  // Copy to app sandbox for security
        )

        picker.delegate = delegate
        picker.allowsMultipleSelection = false

        rootViewController.presentViewController(
            picker,
            animated = true,
            completion = null
        )
    }

    /**
     * Present document picker for exporting/saving a file.
     */
    private fun presentExportPicker(tempFilePath: String, delegate: DocumentPickerDelegate) {
        val rootViewController = getRootViewController() ?: run {
            log.e { "Could not get root view controller" }
            delegate.onCancelled()
            return
        }

        val fileURL = NSURL.fileURLWithPath(tempFilePath)

        // Create picker for exporting the file
        val picker = UIDocumentPickerViewController(
            forExportingURLs = listOf(fileURL),
            asCopy = true  // Export as copy, preserving original
        )

        picker.delegate = delegate

        rootViewController.presentViewController(
            picker,
            animated = true,
            completion = null
        )
    }

    /**
     * Get the root UIViewController from the current window scene.
     * Uses the modern connected scenes API (iOS 13+).
     */
    private fun getRootViewController(): UIViewController? {
        val scenes = UIApplication.sharedApplication.connectedScenes
        val windowScene = scenes.firstOrNull {
            it is UIWindowScene
        } as? UIWindowScene

        return windowScene?.keyWindow?.rootViewController
    }

    /**
     * Copy a security-scoped URL to the temp directory for safe access.
     */
    private fun copyToTempDirectory(url: NSURL): String? {
        return try {
            val fileManager = NSFileManager.defaultManager
            val tempDir = NSTemporaryDirectory()
            val fileName = url.lastPathComponent ?: "import_${NSDate().timeIntervalSince1970}.json"
            val destPath = "$tempDir$fileName"

            // Remove existing temp file if present
            if (fileManager.fileExistsAtPath(destPath)) {
                fileManager.removeItemAtPath(destPath, null)
            }

            // Copy to temp
            val success = fileManager.copyItemAtURL(
                url,
                NSURL.fileURLWithPath(destPath),
                null
            )

            if (success) destPath else null
        } catch (e: Exception) {
            log.e { "Failed to copy file to temp: ${e.message}" }
            null
        }
    }

    /**
     * Save content to a temporary file for export.
     */
    private fun saveToTempFile(fileName: String, content: String): String? {
        return try {
            val tempDir = NSTemporaryDirectory()
            val filePath = "$tempDir$fileName"

            val fileManager = NSFileManager.defaultManager

            // Remove existing file if present
            if (fileManager.fileExistsAtPath(filePath)) {
                fileManager.removeItemAtPath(filePath, null)
            }

            // Write content
            val nsContent = NSString.create(string = content)
            val success = nsContent.writeToFile(
                filePath,
                atomically = true,
                encoding = NSUTF8StringEncoding,
                error = null
            )

            if (success) filePath else null
        } catch (e: Exception) {
            log.e { "Failed to save temp file: ${e.message}" }
            null
        }
    }
}

/**
 * Delegate class that receives callbacks from UIDocumentPickerViewController.
 * Extends NSObject to be compatible with Objective-C runtime.
 * Implements UIDocumentPickerDelegateProtocol for picker callbacks.
 */
@OptIn(ExperimentalForeignApi::class)
private class DocumentPickerDelegate(
    private val onDocumentPicked: (NSURL?) -> Unit,
    val onCancelled: () -> Unit,
    private val log: Logger
) : NSObject(), UIDocumentPickerDelegateProtocol {

    /**
     * Called when user selects one or more documents.
     * For single selection, urls list contains one element.
     */
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>
    ) {
        log.d { "Document picker: didPickDocumentsAtURLs called with ${didPickDocumentsAtURLs.size} URLs" }

        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        if (url != null) {
            log.d { "Selected file: ${url.path}" }
        }
        onDocumentPicked(url)
    }

    /**
     * Called when user cancels the picker.
     */
    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        log.d { "Document picker was cancelled" }
        onCancelled()
    }

    /**
     * Cleanup any resources held by the delegate.
     */
    fun cleanup() {
        // No explicit cleanup needed, but method provided for future use
    }
}

/**
 * Remember a FilePicker instance for use in Compose.
 */
@Composable
actual fun rememberFilePicker(): FilePicker {
    return remember { FilePicker() }
}
