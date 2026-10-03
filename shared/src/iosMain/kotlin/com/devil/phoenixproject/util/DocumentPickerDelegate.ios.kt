package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.darwin.NSObject

/**
 * Which picker owns this delegate. The protocol callbacks are the same;
 * only the debug lines differ.
 */
internal enum class DocumentPickerKind {
    /** File import/export: URL count, then the selected path. */
    File,

    /** Directory pick: one line with the selected path. */
    Directory,
}

/**
 * Single [UIDocumentPickerDelegateProtocol] for [FilePicker] and [BackupLocationPicker].
 *
 * Forwards the first picked URL. Cancel logging stays in this class so a missing
 * root view controller can call [onCancelled] without that line.
 */
@OptIn(ExperimentalForeignApi::class)
internal class DocumentPickerDelegate(
    private val onDocumentPicked: (NSURL?) -> Unit,
    val onCancelled: () -> Unit,
    private val log: Logger,
    private val kind: DocumentPickerKind,
) : NSObject(),
    UIDocumentPickerDelegateProtocol {

    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL
        when (kind) {
            DocumentPickerKind.File -> {
                log.d {
                    "Document picker: didPickDocumentsAtURLs called with ${didPickDocumentsAtURLs.size} URLs"
                }
                if (url != null) {
                    log.d { "Selected file: ${url.path}" }
                }
            }
            DocumentPickerKind.Directory -> {
                log.d { "Directory picker: selected ${url?.path}" }
            }
        }
        onDocumentPicked(url)
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        val label = when (kind) {
            DocumentPickerKind.File -> "Document"
            DocumentPickerKind.Directory -> "Directory"
        }
        log.d { "$label picker was cancelled" }
        onCancelled()
    }
}
