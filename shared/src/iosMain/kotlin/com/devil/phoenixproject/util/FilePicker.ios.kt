package com.devil.phoenixproject.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.Foundation.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.UTType
import platform.UniformTypeIdentifiers.UTTypeCommaSeparatedText
import platform.UniformTypeIdentifiers.UTTypeJSON
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
        LaunchImportPicker(UTTypeJSON, onFilePicked)
    }

    @Composable
    actual fun LaunchCsvFilePicker(onFilePicked: (String?) -> Unit) {
        LaunchImportPicker(UTTypeCommaSeparatedText, onFilePicked)
    }

    @Composable
    private fun LaunchImportPicker(contentType: UTType, onFilePicked: (String?) -> Unit) {
        val currentOnFilePicked by rememberUpdatedState(onFilePicked)
        val scope = rememberCoroutineScope()

        val delegate = remember {
            DocumentPickerDelegate(
                onDocumentPicked = { url ->
                    scope.launch(Dispatchers.Main) {
                        if (url != null) {
                            // asCopy already copied the file into the app sandbox.
                            val tempPath = copyToTempDirectory(url)
                            currentOnFilePicked(tempPath)
                        } else {
                            currentOnFilePicked(null)
                        }
                    }
                },
                onCancelled = {
                    scope.launch(Dispatchers.Main) {
                        currentOnFilePicked(null)
                    }
                },
                log = log,
                kind = DocumentPickerKind.File,
            )
        }

        LaunchedEffect(Unit) {
            dispatch_async(dispatch_get_main_queue()) {
                presentImportPicker(contentType, delegate)
            }
        }
    }

    @Composable
    actual fun LaunchFileSaver(fileName: String, content: String, onSaved: (String?) -> Unit) {
        val currentOnSaved by rememberUpdatedState(onSaved)
        val scope = rememberCoroutineScope()
        val tempHolder = remember { ExportTempFileHolder() }

        // Create delegate that handles picker callbacks
        val delegate = remember {
            DocumentPickerDelegate(
                onDocumentPicked = { url ->
                    scope.launch(Dispatchers.Main) {
                        tempHolder.cleanup()
                        currentOnSaved(url?.path)
                    }
                },
                onCancelled = {
                    scope.launch(Dispatchers.Main) {
                        tempHolder.cleanup()
                        currentOnSaved(null)
                    }
                },
                log = log,
                kind = DocumentPickerKind.File,
            )
        }

        DisposableEffect(Unit) {
            onDispose {
                tempHolder.cleanup()
            }
        }

        LaunchedEffect(fileName, content) {
            tempHolder.cleanup()
            // First, save content to a temp file
            val tempFilePath = saveToTempFile(fileName, content)
            if (tempFilePath == null) {
                currentOnSaved(null)
                return@LaunchedEffect
            }
            tempHolder.replaceWith(tempFilePath)

            dispatch_async(dispatch_get_main_queue()) {
                if (tempHolder.activeTempPath == tempFilePath) {
                    presentExportPicker(tempFilePath, delegate)
                }
            }
        }
    }

    /** The export picker takes the document type from the file's `.csv` extension. */
    @Composable
    actual fun LaunchCsvFileSaver(fileName: String, content: String, onSaved: (String?) -> Unit) {
        LaunchFileSaver(fileName, content, onSaved)
    }

    /**
     * Present document picker for importing a file of [contentType].
     */
    private fun presentImportPicker(contentType: UTType, delegate: DocumentPickerDelegate) {
        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = listOf(contentType),
            asCopy = true, // Copy to app sandbox for security
        )
        picker.allowsMultipleSelection = false
        presentDocumentPicker(picker, delegate, log)
    }

    /**
     * Present document picker for exporting/saving a file.
     */
    private fun presentExportPicker(tempFilePath: String, delegate: DocumentPickerDelegate) {
        val fileURL = NSURL.fileURLWithPath(tempFilePath)

        // Create picker for exporting the file
        val picker = UIDocumentPickerViewController(
            forExportingURLs = listOf(fileURL),
            asCopy = true, // Export as copy, preserving original
        )

        presentDocumentPicker(picker, delegate, log)
    }

    /**
     * Copy the picker URL into the temp directory.
     * Import uses asCopy, so the source is already an app-sandbox copy.
     */
    private fun copyToTempDirectory(url: NSURL): String? = try {
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
            null,
        )

        if (success) destPath else null
    } catch (e: Exception) {
        log.e { "Failed to copy file to temp: ${e.message}" }
        null
    }

    /**
     * Save content to a temporary file for export.
     */
    private fun saveToTempFile(fileName: String, content: String): String? = try {
        writeUtf8TempFile(fileName, content)
    } catch (e: Exception) {
        log.e { "Failed to save temp file: ${e.message}" }
        null
    }
}

/**
 * Tracks the single export temp file staged for [FilePicker.LaunchFileSaver] and deletes it
 * on completion, cancellation, replacement, or disposal.
 */
@OptIn(ExperimentalForeignApi::class)
internal class ExportTempFileHolder(
    private val deleteFile: (String) -> Unit = { path ->
        runCatching { NSFileManager.defaultManager.removeItemAtPath(path, null) }
    },
) {
    var activeTempPath: String? = null
        private set

    fun replaceWith(path: String?) {
        val previous = activeTempPath
        if (previous != null && previous != path) {
            deleteFile(previous)
        }
        activeTempPath = path
    }

    fun cleanup() {
        val current = activeTempPath ?: return
        activeTempPath = null
        deleteFile(current)
    }
}

/**
 * Remember a FilePicker instance for use in Compose.
 */
@Composable
actual fun rememberFilePicker(): FilePicker = remember { FilePicker() }
