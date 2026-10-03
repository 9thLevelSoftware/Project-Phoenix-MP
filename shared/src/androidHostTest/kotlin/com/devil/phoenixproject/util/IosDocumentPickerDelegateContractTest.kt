package com.devil.phoenixproject.util

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Locks the shared iOS document-picker delegate.
 * The delegate itself is Kotlin/Native and does not run on the host.
 */
class IosDocumentPickerDelegateContractTest {

    @Test
    fun oneDelegateForwardsTheFirstUrlAndKeepsEachPickersLog() {
        val delegate = requireSource(
            "src/iosMain/kotlin/com/devil/phoenixproject/util/DocumentPickerDelegate.ios.kt",
        )
        val protocol = "UIDocumentPickerDelegateProtocol"

        assertTrue(delegate.contains("internal class DocumentPickerDelegate"), "one shared delegate class")
        assertEquals(
            1,
            count(delegate, ") : NSObject(),\n    $protocol"),
            "the delegate file implements the protocol once",
        )
        assertTrue(delegate.contains("firstOrNull() as? NSURL"), "single selection still uses the first URL")
        assertTrue(
            delegate.indexOf("onDocumentPicked(url)") > delegate.indexOf("firstOrNull()"),
            "the picked callback runs after the first URL is chosen",
        )
        assertTrue(
            delegate.contains(
                "\"Document picker: didPickDocumentsAtURLs called with \${didPickDocumentsAtURLs.size} URLs\"",
            ),
            "file picker still logs the URL count",
        )
        assertTrue(delegate.contains("\"Selected file: \${url.path}\""), "file picker still logs the selected path")
        assertTrue(
            delegate.contains("\"Directory picker: selected \${url?.path}\""),
            "directory picker still logs the selected path",
        )
        assertTrue(delegate.contains("\"\$label picker was cancelled\""), "cancel still logs before the callback")
        assertTrue(
            delegate.indexOf("onCancelled()") > delegate.indexOf("picker was cancelled"),
            "cancel log stays inside the delegate, ahead of onCancelled",
        )
        assertTrue(!delegate.contains("keyWindow"), "root view controller lookup stays with the pickers")
        assertTrue(!delegate.contains("connectedScenes"), "root view controller lookup stays with the pickers")
    }

    @Test
    fun fileAndDirectoryPickersUseTheSharedDelegate() {
        val filePicker = requireSource("src/iosMain/kotlin/com/devil/phoenixproject/util/FilePicker.ios.kt")
        val directoryPicker = requireSource(
            "src/iosMain/kotlin/com/devil/phoenixproject/util/BackupLocationPicker.ios.kt",
        )

        assertEquals(2, count(filePicker, "DocumentPickerDelegate("), "import and export both use the shared delegate")
        assertEquals(2, count(filePicker, "DocumentPickerKind.File"), "both file pickers keep the file log")
        assertTrue(!filePicker.contains("UIDocumentPickerDelegateProtocol"), "file picker no longer implements the protocol")
        assertTrue(!filePicker.contains("class DocumentPickerDelegate"), "file picker no longer declares its own delegate")

        assertEquals(1, count(directoryPicker, "DocumentPickerDelegate("), "directory picker uses the shared delegate")
        assertTrue(directoryPicker.contains("DocumentPickerKind.Directory"), "directory picker keeps the directory log")
        assertTrue(directoryPicker.contains("presentDirectoryPicker(delegate: DocumentPickerDelegate)"))
        assertTrue(!directoryPicker.contains("DirectoryPickerDelegate"), "directory picker delegate is gone")
        assertTrue(
            !directoryPicker.contains("UIDocumentPickerDelegateProtocol"),
            "directory picker no longer implements the protocol",
        )
    }

    private fun requireSource(relativePath: String): String =
        readProjectFile(relativePath) ?: fail("Missing $relativePath")

    private fun count(source: String, needle: String): Int = source.split(needle).size - 1
}
