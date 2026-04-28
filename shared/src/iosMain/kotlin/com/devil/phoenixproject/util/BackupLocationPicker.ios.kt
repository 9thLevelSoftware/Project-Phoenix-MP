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
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.NSURLBookmarkCreationWithSecurityScope
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.lastPathComponent
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindowScene
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private val log = Logger.withTag("BackupLocationPicker.iOS")

/**
 * iOS implementation of [BackupLocationPicker].
 *
 * Uses [UIDocumentPickerViewController] with [UTTypeFolder] to let the user pick a directory.
 * Creates a security-scoped bookmark via [bookmarkDataWithOptions] so the app can
 * regain access across launches. The bookmark [NSData] is Base64-encoded for storage.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class BackupLocationPicker {

    @Composable
    actual fun LaunchDirectoryPicker(onDirectoryPicked: (BackupDestination.Custom?) -> Unit) {
        val scope = rememberCoroutineScope()

        val delegate = remember {
            DirectoryPickerDelegate(
                onDirectorySelected = { url ->
                    scope.launch(Dispatchers.Main) {
                        if (url != null) {
                            val accessing = url.startAccessingSecurityScopedResource()
                            try {
                                val result = createBookmarkedDestination(url)
                                onDirectoryPicked(result)
                            } finally {
                                if (accessing) {
                                    url.stopAccessingSecurityScopedResource()
                                }
                            }
                        } else {
                            onDirectoryPicked(null)
                        }
                    }
                },
                onCancelled = {
                    scope.launch(Dispatchers.Main) {
                        onDirectoryPicked(null)
                    }
                },
            )
        }

        LaunchedEffect(Unit) {
            dispatch_async(dispatch_get_main_queue()) {
                presentDirectoryPicker(delegate)
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                delegate.cleanup()
            }
        }
    }

    /**
     * Create a [BackupDestination.Custom] from the selected URL,
     * including a Base64-encoded security-scoped bookmark.
     */
    private fun createBookmarkedDestination(url: NSURL): BackupDestination.Custom? = try {
        val uri = url.absoluteString ?: url.path
        if (uri.isNullOrBlank()) {
            log.w { "Directory URL has no usable URI — treating as cancelled" }
            return null
        }

        // Create a security-scoped bookmark for persistent cross-launch access.
        val bookmarkData: NSData? = url.bookmarkDataWithOptions(
            options = NSURLBookmarkCreationWithSecurityScope,
            includingResourceValuesForKeys = null,
            relativeToURL = null,
            error = null,
        )

        val base64Bookmark = bookmarkData?.base64EncodedStringWithOptions(0u)

        if (base64Bookmark == null) {
            log.w { "Failed to create security-scoped bookmark — destination would be inaccessible on next launch" }
            return null
        }

        val displayName = url.lastPathComponent ?: "Selected folder"

        log.d { "Directory bookmarked: $displayName" }

        BackupDestination.Custom(
            uri = uri,
            displayName = displayName,
            bookmarkData = base64Bookmark,
        )
    } catch (e: Exception) {
        log.e { "Failed to create bookmark for directory: ${e.message}" }
        null
    }

    /**
     * Present the directory picker from the current root view controller.
     */
    private fun presentDirectoryPicker(delegate: DirectoryPickerDelegate) {
        val rootViewController = getRootViewController() ?: run {
            log.e { "Could not get root view controller" }
            delegate.onCancelled()
            return
        }

        val picker = UIDocumentPickerViewController(
            forOpeningContentTypes = listOf(UTTypeFolder),
        )

        picker.delegate = delegate
        picker.allowsMultipleSelection = false

        rootViewController.presentViewController(
            picker,
            animated = true,
            completion = null,
        )
    }

    /**
     * Get the root UIViewController from the current window scene.
     * Same pattern as [FilePicker.getRootViewController].
     */
    private fun getRootViewController(): UIViewController? {
        val scenes = UIApplication.sharedApplication.connectedScenes
        val windowScene = scenes.firstOrNull {
            it is UIWindowScene
        } as? UIWindowScene

        return windowScene?.keyWindow?.rootViewController
    }
}

/**
 * Delegate that receives callbacks from the directory picker.
 * Mirrors [DocumentPickerDelegate] from FilePicker but simplified for directory-only use.
 */
@OptIn(ExperimentalForeignApi::class)
private class DirectoryPickerDelegate(
    private val onDirectorySelected: (NSURL?) -> Unit,
    val onCancelled: () -> Unit,
) : NSObject(),
    UIDocumentPickerDelegateProtocol {

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        log.d { "Directory picker: selected ${url?.path}" }
        onDirectorySelected(url)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        log.d { "Directory picker was cancelled" }
        onCancelled()
    }

    fun cleanup() {
        // No explicit cleanup needed; method provided for DisposableEffect symmetry
    }
}

/**
 * Remember a [BackupLocationPicker] instance for use in Compose.
 */
@Composable
actual fun rememberBackupLocationPicker(): BackupLocationPicker = remember { BackupLocationPicker() }
