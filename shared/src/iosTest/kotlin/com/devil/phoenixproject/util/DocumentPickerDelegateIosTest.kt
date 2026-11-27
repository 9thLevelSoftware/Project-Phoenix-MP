@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerViewController
import platform.UniformTypeIdentifiers.UTTypeFolder
import platform.UniformTypeIdentifiers.UTTypeJSON

class DocumentPickerDelegateIosTest {

    @Test
    fun delegateForwardsFirstUrlHandlesEmptyListAndForwardsCancellation() {
        val picked = mutableListOf<NSURL?>()
        var cancelled = 0
        val delegate = DocumentPickerDelegate(
            onDocumentPicked = { picked += it },
            onCancelled = { cancelled++ },
            log = Logger.withTag("DocumentPickerDelegateIosTest"),
            kind = DocumentPickerKind.File,
        )
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeJSON), asCopy = true)
        val first = NSURL.fileURLWithPath("/tmp/first.json")
        val second = NSURL.fileURLWithPath("/tmp/second.json")

        delegate.documentPicker(picker, didPickDocumentsAtURLs = listOf(first, second))
        delegate.documentPicker(picker, didPickDocumentsAtURLs = emptyList<Any>())
        delegate.documentPicker(picker, didPickDocumentsAtURLs = listOf("not-an-nsurl"))
        delegate.documentPickerWasCancelled(picker)

        assertEquals(listOf<NSURL?>(first, null, null), picked)
        assertEquals(1, cancelled)
    }

    @Test
    fun presentDocumentPickerCancelsCleanlyWhenRootControllerUnavailable() {
        var cancelled = 0
        val delegate = DocumentPickerDelegate(
            onDocumentPicked = {},
            onCancelled = { cancelled++ },
            log = Logger.withTag("DocumentPickerDelegateIosTest"),
            kind = DocumentPickerKind.Directory,
        )
        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeFolder))
        if (keyWindowRootViewController() == null) {
            presentDocumentPicker(picker, delegate, Logger.withTag("DocumentPickerDelegateIosTest"))
            assertEquals(1, cancelled)
        }
    }
}
