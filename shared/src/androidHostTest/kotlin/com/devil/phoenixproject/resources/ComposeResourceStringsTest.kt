package com.devil.phoenixproject.resources

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #896: Compose Multiplatform keeps aapt-style `\'` escapes verbatim, so dialogs rendered
 * `Can\'t`. The fix replaces every literal backslash-apostrophe in the Compose strings with a
 * plain apostrophe (XML text needs no escape); this guards the source against regressions.
 */
class ComposeResourceStringsTest {

    @Test
    fun noStringsXmlContainsABackslashApostropheEscape() {
        val files = localeStringFiles()
        assertTrue(files.isNotEmpty(), "no composeResources values*/strings.xml found from ${System.getProperty("user.dir")}")
        for (file in files) {
            val text = file.readText()
            assertFalse(
                text.contains("\\'"),
                "${file.parentFile.name}/strings.xml still contains a literal backslash-apostrophe escape",
            )
        }
    }

    @Test
    fun theCsvDialogStringsRenderPlainApostrophes() {
        val default = localeStringFiles().first { it.parentFile.name == "values" }.readText()
        assertTrue(default.contains("Can't export %1\$s"), "the export-blocked dialog title keeps its apostrophe")
        assertTrue(default.contains("Can't import this file"), "the unreadable dialog title keeps its apostrophe")
        assertTrue(default.contains("CSV files can't hold these settings yet"), "the export-blocked message keeps its apostrophe")
    }

    private fun localeStringFiles(): List<File> {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            for (root in listOf(File(dir, "src/commonMain/composeResources"), File(dir, "shared/src/commonMain/composeResources"))) {
                val values = root.listFiles()?.filter { it.isDirectory && it.name.startsWith("values") }.orEmpty()
                val files = values.map { File(it, "strings.xml") }.filter { it.isFile }
                if (files.isNotEmpty()) return files
            }
            dir = dir.parentFile
        }
        return emptyList()
    }
}
