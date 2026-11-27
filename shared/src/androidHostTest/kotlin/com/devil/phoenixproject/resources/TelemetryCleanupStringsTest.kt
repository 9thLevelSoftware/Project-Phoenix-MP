package com.devil.phoenixproject.resources

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T4.2 (issue #1425): every `telemetry_cleanup_*` string exists in all six locales
 * (en/de/es/fr/it/nl), following the `settings_backup_file_one/other` convention.
 */
class TelemetryCleanupStringsTest {

    @Test
    fun everyTelemetryCleanupStringExistsInAllSixLocales() {
        val files = localeStringFiles()
        assertTrue(files.isNotEmpty(), "no composeResources values*/strings.xml found")
        val byLocale = files.associate { it.parentFile.name to it.readText() }
        val default = byLocale.getValue("values")
        val defaultKeys = telemetryKeys(default)
        assertTrue(defaultKeys.size >= 30, "expected the full telemetry_cleanup string set, found ${defaultKeys.size}")
        for ((locale, text) in byLocale) {
            val keys = telemetryKeys(text)
            val missing = defaultKeys - keys
            assertTrue(missing.isEmpty(), "$locale is missing telemetry cleanup strings: $missing")
        }
    }

    @Test
    fun theFrame07BackupLineIsSplitIntoLabelAndValue() {
        val default = localeStringFiles().first { it.parentFile.name == "values" }.readText()
        assertTrue(default.contains("telemetry_cleanup_estimate_backup\""), "the savings label exists")
        assertTrue(default.contains("telemetry_cleanup_estimate_backup_value\""), "the savings value exists")
    }

    private fun telemetryKeys(text: String): Set<String> =
        Regex("""<string name="(telemetry_cleanup_[a-z0-9_]+)"""").findAll(text).map { it.groupValues[1] }.toSet()

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
