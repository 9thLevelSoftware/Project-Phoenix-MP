package com.devil.phoenixproject.util

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Locks the shared iOS document-picker present path.
 * The presenter itself is Kotlin/Native and does not run on the host.
 */
class IosDocumentPickerPresentContractTest {

    @Test
    fun presenterUsesKeyWindowAndCancelsWithoutTheUserCancelLog() {
        val presenter = requireSource(
            "src/iosMain/kotlin/com/devil/phoenixproject/util/DocumentPickerPresenter.ios.kt",
        )
        val body = functionBody(presenter, "internal fun presentDocumentPicker")

        val missing = body.indexOf("keyWindowRootViewController()")
        val cancel = body.indexOf("delegate.onCancelled()")
        val present = body.indexOf("presentViewController")
        assertTrue(missing >= 0 && cancel > missing, "a missing root controller cancels")
        assertTrue(body.contains("Could not get root view controller"), "missing root still logs the same error")
        assertTrue(present > cancel, "present runs only after the root controller is known")
        assertTrue(body.contains("picker.delegate = delegate"), "the presenter assigns the shared delegate")
        assertTrue(body.contains("animated = true"), "presentation stays animated")
        assertTrue(body.contains("completion = null"), "presentation has no completion")
        assertTrue(!body.contains("connectedScenes"), "scene walk stays in the key-window helper")
        assertTrue(!body.contains("documentPickerWasCancelled"), "missing root does not log a user cancel")
        assertTrue(!body.contains("allowsMultipleSelection"), "selection mode stays with each picker")
        assertTrue(!body.contains("asCopy"), "copy mode stays with each picker")
    }

    @Test
    fun filePickerPresentsThroughTheSharedHelper() {
        val source = requireSource("src/iosMain/kotlin/com/devil/phoenixproject/util/FilePicker.ios.kt")
        val importPicker = functionBody(source, "private fun presentImportPicker")
        val exportPicker = functionBody(source, "private fun presentExportPicker")

        assertTrue(importPicker.contains("asCopy = true"), "import still copies into the sandbox")
        assertTrue(importPicker.contains("allowsMultipleSelection = false"), "import stays single-select")
        assertTrue(importPicker.contains("presentDocumentPicker(picker, delegate, log)"))
        assertTrue(!importPicker.contains("keyWindowRootViewController"), "import no longer looks up the window")
        assertTrue(!importPicker.contains("presentViewController"), "import no longer presents itself")

        assertTrue(exportPicker.contains("forExportingURLs"), "export still uses the export constructor")
        assertTrue(exportPicker.contains("asCopy = true"), "export still copies")
        assertTrue(!exportPicker.contains("allowsMultipleSelection"), "export still leaves selection at the default")
        assertTrue(exportPicker.contains("presentDocumentPicker(picker, delegate, log)"))
        assertTrue(!exportPicker.contains("keyWindowRootViewController"), "export no longer looks up the window")
        assertTrue(!exportPicker.contains("presentViewController"), "export no longer presents itself")

        assertTrue(!source.contains("Could not get root view controller"), "missing-root log lives in the presenter")
    }

    @Test
    fun directoryPickerPresentsThroughTheSharedHelper() {
        val source = requireSource(
            "src/iosMain/kotlin/com/devil/phoenixproject/util/BackupLocationPicker.ios.kt",
        )
        val body = functionBody(source, "private fun presentDirectoryPicker")

        assertTrue(body.contains("UTTypeFolder"), "directory picker still opens folders")
        assertTrue(!body.contains("asCopy"), "directory picker still omits asCopy")
        assertTrue(body.contains("allowsMultipleSelection = false"), "directory picker stays single-select")
        assertTrue(body.contains("presentDocumentPicker(picker, delegate, log)"))
        assertTrue(!body.contains("keyWindowRootViewController"), "directory picker no longer looks up the window")
        assertTrue(!body.contains("presentViewController"), "directory picker no longer presents itself")
        assertTrue(!source.contains("Could not get root view controller"), "missing-root log lives in the presenter")
        assertTrue(source.contains("presentDirectoryPicker(delegate: DocumentPickerDelegate)"))
    }

    @Test
    fun shareSheetKeepsItsOwnPresenter() {
        val source = requireSource("src/iosMain/kotlin/com/devil/phoenixproject/util/CrashLog.ios.kt")

        assertTrue(source.contains("fun presentShareSheet"), "share sheet presenter remains")
        assertTrue(source.contains("presentViewController"), "share sheet still presents itself")
        assertTrue(source.contains("presentedViewController"), "share sheet still walks to the top presenter")
        assertTrue(!source.contains("presentDocumentPicker"), "share sheet does not use the document presenter")
        assertTrue(!source.contains("keyWindowRootViewController"), "share sheet keeps its own window walk")
    }

    private fun requireSource(relativePath: String): String =
        readProjectFile(relativePath) ?: fail("Missing $relativePath")

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "Missing $signature")
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        fail("Unclosed $signature")
    }
}
