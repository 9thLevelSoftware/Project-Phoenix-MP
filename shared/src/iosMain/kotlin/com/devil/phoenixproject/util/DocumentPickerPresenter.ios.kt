package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.UIKit.UIDocumentPickerViewController

/**
 * Presents [picker] from the top of the key window's presented-controller chain.
 *
 * UIKit ignores `present` on a controller that is already presenting, so a sheet or
 * alert on the root would otherwise hide the picker and leave the caller's callback
 * hanging. The walk matches [presentShareSheet].
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
    var presenter = keyWindowRootViewController() ?: run {
        log.e { "Could not get root view controller" }
        delegate.onCancelled()
        return
    }
    while (true) {
        presenter = presenter.presentedViewController ?: break
    }

    picker.delegate = delegate
    presenter.presentViewController(
        picker,
        animated = true,
        completion = null,
    )
}
