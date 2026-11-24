package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.Foundation.NSURLComponents
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

private val log = Logger.withTag("IosFolderOpener")

/**
 * Build the `shareddocuments://` handoff URL for [folderPath] so the Files app
 * opens that folder (issue #1423).
 *
 * The path is assigned through [NSURLComponents] so spaces and other reserved
 * characters are percent-encoded on `.URL`; string concatenation would produce
 * an invalid URL for paths like `.../My Backups`.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun filesHandoffUrl(folderPath: String): NSURL? {
    if (folderPath.isBlank()) return null
    val components = NSURLComponents()
    components.scheme = "shareddocuments"
    components.path = folderPath
    return components.URL
}

/**
 * Single seam for handing the backup folder to the system file manager
 * (issue #1423). [IosDataBackupManager] owns the fallback policy (custom
 * destination -> default folder -> browse); this class only performs the two
 * UI side effects, both on the main queue.
 *
 * Tests subclass this recorder-style: the URL building stays production code
 * so assertions see the same percent-encoded URLs the system would receive.
 */
@OptIn(ExperimentalForeignApi::class)
internal open class IosFolderOpener {

    /**
     * Hand [handoffUrl] (a `shareddocuments://` URL) to the system.
     * [onResult] reports whether the system accepted the handoff request —
     * not that the folder became visible in the Files app.
     */
    open fun openFolderInFiles(handoffUrl: NSURL, onResult: (Boolean) -> Unit) {
        dispatch_async(dispatch_get_main_queue()) {
            // Deprecated single-argument openURL(_:) is rejected on newer iOS;
            // the 3-argument form matches AppSettingsOpener.ios.kt.
            UIApplication.sharedApplication.openURL(
                url = handoffUrl,
                options = emptyMap<Any?, Any>(),
                completionHandler = { accepted -> onResult(accepted) },
            )
        }
    }

    /**
     * Present a folder document picker browsing [directoryUrl] as the last
     * resort when no Files handoff was accepted. Browse-only: picking or
     * cancelling has no side effects.
     */
    open fun presentFolderBrowse(directoryUrl: NSURL) {
        dispatch_async(dispatch_get_main_queue()) {
            val picker = UIDocumentPickerViewController(
                forOpeningContentTypes = listOf(UTTypeFolder),
            )
            picker.allowsMultipleSelection = false
            picker.directoryURL = directoryUrl
            val delegate = DocumentPickerDelegate(
                onDocumentPicked = {},
                onCancelled = {},
                log = log,
                kind = DocumentPickerKind.Directory,
            )
            presentDocumentPicker(picker, delegate, log)
        }
    }
}
