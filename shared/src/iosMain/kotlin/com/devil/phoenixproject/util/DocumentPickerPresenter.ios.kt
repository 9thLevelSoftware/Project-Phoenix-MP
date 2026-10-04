package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIDocumentPickerViewController

/**
 * Presents [picker] from the key window's root view controller.
 *
 * A missing controller logs and calls [DocumentPickerDelegate.onCancelled]
 * directly, so that path does not emit the delegate's user-cancel line.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun presentDocumentPicker(
    picker: UIDocumentPickerViewController,
    delegate: DocumentPickerDelegate,
    log: Logger,
) {
    val rootViewController = keyWindowRootViewController() ?: run {
        log.e { "Could not get root view controller" }
        delegate.onCancelled()
        return
    }

    picker.delegate = delegate
    rootViewController.presentViewController(
        picker,
        animated = true,
        completion = null,
    )
}
