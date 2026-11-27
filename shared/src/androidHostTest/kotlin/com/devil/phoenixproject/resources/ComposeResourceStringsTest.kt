package com.devil.phoenixproject.resources

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
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

    @Test
    fun allSixLocalesHaveExactKeyZeroDuplicateAndPlaceholderParity() {
        val files = localeStringFiles().associateBy { it.parentFile.name }
        val expectedDirs = setOf("values", "values-de", "values-es", "values-fr", "values-it", "values-nl")
        assertEquals(expectedDirs, files.keys, "Expected exact 6 locale values* directories")

        val parsed = files.mapValues { (dirName, file) -> parseStringsXml(dirName, file) }
        val base = parsed.getValue("values")
        val placeholderRegex = Regex("""%(?:\d+\$)?[+#0,(]*(?:\d+)?(?:\.\d+)?(?:[sdifxX]|ld|lu)""")

        for ((dirName, map) in parsed) {
            if (dirName == "values") continue
            val missing = base.keys - map.keys
            val extra = map.keys - base.keys
            assertTrue(missing.isEmpty(), "$dirName/strings.xml is missing keys: $missing")
            assertTrue(extra.isEmpty(), "$dirName/strings.xml has unexpected extra keys: $extra")

            for ((key, enValue) in base) {
                val enPlaceholders = placeholderRegex.findAll(enValue).map { it.value }.toList()
                val locPlaceholders = placeholderRegex.findAll(map.getValue(key)).map { it.value }.toList()
                assertEquals(
                    enPlaceholders,
                    locPlaceholders,
                    "Placeholder mismatch for '$key' in $dirName/strings.xml",
                )
            }
        }
    }

    @Test
    fun routineRecoveryAndConnectionLogsAreLocalizedAndWeightUnitAware() {
        val recoverySrc = readSource(
            "src/phoneMain/kotlin/com/devil/phoenixproject/presentation/components/RoutineRecoverySheet.kt",
        )
        assertTrue(recoverySrc.contains("WeightUnit"), "RoutineRecoverySheet must accept WeightUnit")
        assertTrue(
            recoverySrc.contains("WeightDisplayFormatter.formatDisplayWeight"),
            "RoutineRecoverySheet must format planned-set weights via WeightDisplayFormatter.formatDisplayWeight",
        )
        assertTrue(recoverySrc.contains("Res.string."), "RoutineRecoverySheet must use Compose Res.string resources")
        assertFalse(recoverySrc.contains("\"Recover deleted routines"), "RoutineRecoverySheet must not hardcode title")
        assertFalse(recoverySrc.contains("\"Hide preview\""), "RoutineRecoverySheet must not hardcode Hide preview")
        assertFalse(recoverySrc.contains("\"Restore as copy\""), "RoutineRecoverySheet must not hardcode Restore as copy")
        assertFalse(recoverySrc.contains(" · \$it kg"), "RoutineRecoverySheet must not hardcode kg suffix")

        val logsSrc = readSource(
            "src/phoneMain/kotlin/com/devil/phoenixproject/presentation/screen/ConnectionLogsScreen.kt",
        )
        assertTrue(logsSrc.contains("Res.string.logs_"), "ConnectionLogsScreen must use Res.string.logs_* resources")
        assertFalse(logsSrc.contains("\"Logs copied to clipboard\""), "ConnectionLogsScreen must not hardcode clipboard toast")
        assertFalse(logsSrc.contains("\"No logs yet\""), "ConnectionLogsScreen must not hardcode empty state title")
        assertFalse(logsSrc.contains("\"Connection events will appear here\""), "ConnectionLogsScreen must not hardcode empty state subtitle")
        assertFalse(logsSrc.contains("\"Logging ON\""), "ConnectionLogsScreen must not hardcode Logging ON")
        assertFalse(logsSrc.contains("\"Logging OFF\""), "ConnectionLogsScreen must not hardcode Logging OFF")
        assertFalse(logsSrc.contains("\"Log content preview:\""), "ConnectionLogsScreen must not hardcode preview header")
    }

    @Test
    fun noUnreferencedNonLegacyStringKeysExistInSharedSources() {
        val sharedRoot = locateSharedDir()
        val baseFile = File(sharedRoot, "src/commonMain/composeResources/values/strings.xml")
        val baseKeys = parseStringsXml("values", baseFile).keys

        val retiredOrphans = setOf(
            "hrm_strip_rr_prefix",
            "hrm_strip_rmssd_prefix",
            "hrm_strip_hinge_prefix",
            "hrm_strip_tilt_prefix",
            "set_ready_echo_settings",
            "profile_delete_only_active",
        )
        val presentOrphans = baseKeys intersect retiredOrphans
        assertTrue(
            presentOrphans.isEmpty(),
            "Orphaned Wave 5 keys must stay removed from strings.xml: $presentOrphans",
        )

        val refRegex = Regex("""Res\.string\.([A-Za-z0-9_]+)""")
        val referencedKeys = File(sharedRoot, "src")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> refRegex.findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()

        val legacyUnusedKeys = setOf(
            "app_name",
            "cd_dynamic_color",
            "cd_led_scheme",
            "cd_weight_text_size",
            "delete_exercise_message",
            "media_delete_cleanup_transient",
            "profile_stall_detection",
            "profile_stop_at_top",
        )
        val unreferenced = (baseKeys - legacyUnusedKeys - referencedKeys).sorted()
        assertTrue(
            unreferenced.isEmpty(),
            "Every non-legacy strings.xml key must be referenced via Res.string.<key> in shared/src: $unreferenced",
        )
    }

    @Test
    fun wave5UiFilesUseLocalizedResourcesInsteadOfHardcodedEnglish() {
        val dateRange = readSource("src/phoneMain/kotlin/com/devil/phoenixproject/presentation/components/DateRangePickerDialog.kt")
        assertTrue(dateRange.contains("Res.string.date_range_all_time"))
        assertTrue(dateRange.contains("Res.string.date_range_this_year"))
        assertFalse(dateRange.contains("\"All Time\""))

        val exerciseDetail = readSource("src/phoneMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt")
        assertTrue(exerciseDetail.contains("Res.string.detail_delta_from_last"))
        assertTrue(exerciseDetail.contains("Res.string.detail_chart_1rm_progression"))
        assertTrue(exerciseDetail.contains("Res.string.detail_table_weight_unit"))
        assertTrue(exerciseDetail.contains("Res.string.detail_session_weight_reps"))
        assertFalse(exerciseDetail.contains("\"1RM PROGRESSION\""))
        assertFalse(exerciseDetail.contains("TableHeaderCell(\"Date\""))

        val historyTab = readSource("src/phoneMain/kotlin/com/devil/phoenixproject/presentation/screen/HistoryTab.kt")
            .lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertTrue(historyTab.contains("Res.string.history_unknown_exercise"))
        assertFalse(historyTab.contains("\"Unknown Exercise\""))

        val modeConfirm = readSource("src/phoneMain/kotlin/com/devil/phoenixproject/presentation/screen/ModeConfirmationScreen.kt")
        assertTrue(modeConfirm.contains("Res.string.mode_confirm_sets_531"))
        assertFalse(modeConfirm.contains("sets (5/3/1)"))

        val routineOverview = readSource("src/phoneMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineOverviewScreen.kt")
        assertTrue(routineOverview.contains("Res.string.routine_overview_sets_count"))
        assertFalse(routineOverview.contains("\"\${exercise.setReps.size} sets\""))

        val volumeChart = readSource("src/phoneMain/kotlin/com/devil/phoenixproject/presentation/components/charts/VolumeTrendChart.kt")
        assertTrue(volumeChart.contains("Res.string.chart_period_week"))
        assertTrue(volumeChart.contains("Res.string.chart_period_year"))
        assertFalse(volumeChart.contains("WEEK(\"Week\")"))

        val rpeSlider = readSource("src/phoneMain/kotlin/com/devil/phoenixproject/presentation/components/RpeSlider.kt")
        assertTrue(rpeSlider.contains("Res.string.rpe_label_easy"))
        assertTrue(rpeSlider.contains("Res.string.rpe_rir_0"))
        assertFalse(rpeSlider.contains("\"Max Effort\""))

        val moveToGroup = readSource("src/phoneMain/kotlin/com/devil/phoenixproject/presentation/screen/MoveToGroupDialog.kt")
        assertTrue(moveToGroup.contains("Res.string.action_save"))
        assertTrue(moveToGroup.contains("Res.string.action_cancel"))
        assertFalse(moveToGroup.contains("confirmLabel = \"Save\""))

        val analytics = readSource("src/phoneMain/kotlin/com/devil/phoenixproject/presentation/screen/AnalyticsScreen.kt")
        assertTrue(analytics.contains("Res.string.detail_loading_ellipsis"))
        assertTrue(analytics.contains("Res.string.history_unknown_exercise"))
        assertTrue(analytics.contains("Res.string.analytics_pr_reps"))
        assertFalse(analytics.contains("\"Loading...\""))
        assertFalse(analytics.contains("\"Unknown Exercise\""))
    }

    private fun locateSharedDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, "src/commonMain/composeResources/values/strings.xml").isFile) return dir
            val nested = File(dir, "shared")
            if (File(nested, "src/commonMain/composeResources/values/strings.xml").isFile) return nested
            dir = dir.parentFile
        }
        error("Could not locate shared directory from ${System.getProperty("user.dir")}")
    }

    private fun parseStringsXml(dirName: String, file: File): LinkedHashMap<String, String> {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = builder.parse(file)
        val nodes = doc.getElementsByTagName("string")
        val map = LinkedHashMap<String, String>(nodes.length)
        for (i in 0 until nodes.length) {
            val elem = nodes.item(i) as org.w3c.dom.Element
            val name = elem.getAttribute("name")
            assertTrue(name.isNotBlank(), "Blank <string> name in $dirName/strings.xml")
            assertFalse(map.containsKey(name), "Duplicate <string name=\"$name\"> in $dirName/strings.xml")
            map[name] = elem.textContent ?: ""
        }
        return map
    }

    private fun readSource(relativeFromShared: String): String {
        var dir: File? = File(System.getProperty("user.dir"))
        while (dir != null) {
            for (candidate in listOf(File(dir, relativeFromShared), File(dir, "shared/$relativeFromShared"))) {
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        error("Could not find $relativeFromShared from ${System.getProperty("user.dir")}")
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
