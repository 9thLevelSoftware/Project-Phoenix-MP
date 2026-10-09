package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #363: Source-contract test for the full-screen Tag exercise picker.
 *
 * Pins the implementation contract without a Compose UI test dependency:
 * - the Tag picker shell is a full-screen Dialog (no 520dp AlertDialog cap, no
 *   bottom Cancel; Back + system back dismiss),
 * - the row-name style is a defaulted parameter threaded down to the row, so
 *   only the Tag picker steps titleMedium -> titleSmall,
 * - every other picker call site stays on the default titleMedium and is
 *   byte-untouched.
 */
class MiniExercisePickerDialogContractTest {

    // ── Packet FE-1: full-screen dialog shell ─────────────────────────────

    @Test
    fun miniPicker_usesFullScreenDialogShell() {
        val src = readMiniPickerSource()
        assertTrue(
            src.contains("usePlatformDefaultWidth = false"),
            "MiniExercisePickerDialog must use a full-width Dialog (usePlatformDefaultWidth = false).",
        )
        assertTrue(
            src.contains("dismissOnClickOutside = false"),
            "The picker must not dismiss on scrim tap.",
        )
        assertTrue(
            src.contains("dismissOnBackPress = true"),
            "System back must dismiss the picker.",
        )
        assertTrue(
            src.contains("fillMaxSize"),
            "The scaffold content must fillMaxSize so the weighted list has bounded height (#893).",
        )
        assertTrue(
            src.contains("Res.string.cd_back"),
            "The top-bar navigation icon must expose the back content description.",
        )
        assertTrue(
            src.contains("Res.string.tag_exercise_action"),
            "The top bar must be titled with the Tag exercise string.",
        )
    }

    @Test
    fun miniPicker_hasNoAlertDialogCapOrCancelButton() {
        val src = readMiniPickerSource()
        assertFalse(
            src.contains("AlertDialog"),
            "The 520dp AlertDialog shell must be gone.",
        )
        assertFalse(
            src.contains("heightIn(max = 520.dp)"),
            "The 520dp height cap must be removed.",
        )
        assertFalse(
            src.contains("action_cancel"),
            "The bottom Cancel button must be gone (Back replaces it).",
        )
        assertFalse(
            src.contains("verticalScroll"),
            "The dialog must not wrap the list in verticalScroll (#893 crash risk).",
        )
    }

    @Test
    fun miniPicker_publicSignatureUnchanged() {
        val src = readMiniPickerSource()
        val declaration = src.substringAfter("fun MiniExercisePickerDialog(")
        assertTrue(
            declaration.isNotEmpty(),
            "MiniExercisePickerDialog declaration not found.",
        )
        val paramsBlock = declaration.substringBefore(") {")
        val paramNames = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)\s*:""")
            .findAll(paramsBlock)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            listOf("exerciseRepository", "onDismiss", "onExerciseSelected", "recentExerciseIds"),
            paramNames,
            "MiniExercisePickerDialog's public parameter list must stay unchanged for its four call sites.",
        )
    }

    @Test
    fun miniPicker_selectionStillDismissesAfterCallback() {
        val src = readMiniPickerSource()
        val selectIdx = src.indexOf("onExerciseSelected(exercise)")
        val dismissIdx = src.indexOf("onDismiss()", startIndex = selectIdx)
        assertTrue(
            selectIdx >= 0 && dismissIdx > selectIdx,
            "Selection must call onExerciseSelected(exercise) before onDismiss().",
        )
    }

    // ── Packet FE-2: rowNameStyle stays local to the tag picker ───────────

    @Test
    fun miniPicker_passesTitleSmallRowNames() {
        val src = readMiniPickerSource()
        assertTrue(
            src.contains("rowNameStyle = MaterialTheme.typography.titleSmall"),
            "The Tag picker must pass titleSmall row names.",
        )
    }

    @Test
    fun exercisePicker_rowNameStyleDefaultsToTitleMedium() {
        val src = readExercisePickerSource()
        assertTrue(
            src.contains("rowNameStyle: TextStyle = MaterialTheme.typography.titleMedium"),
            "ExercisePickerContent must default rowNameStyle to titleMedium.",
        )
    }

    @Test
    fun exercisePicker_dialogBranchesDoNotOverrideRowNameStyle() {
        val src = readExercisePickerSource()
        // The fullScreen/bottom-sheet branches belong to ExercisePickerDialog, which
        // ends where the ExercisePickerContent KDoc begins. Neither branch may pass
        // rowNameStyle — only MiniExercisePickerDialog overrides it.
        val dialogStart = src.indexOf("fun ExercisePickerDialog(")
        val contentDoc = src.indexOf("Exercise Picker Content")
        assertTrue(dialogStart >= 0 && contentDoc > dialogStart, "ExercisePickerDialog not found.")
        val dialogBody = src.substring(dialogStart, contentDoc)
        assertFalse(
            dialogBody.contains("rowNameStyle"),
            "ExercisePickerDialog branches must keep default titleMedium row names.",
        )
    }

    @Test
    fun exercisePicker_kDocNoLongerClaimsCallerCappedHeight() {
        val src = readExercisePickerSource()
        assertFalse(
            src.contains("height should still be capped by the caller"),
            "The ExercisePickerContent KDoc must not claim fullScreen keeps caller-capped height.",
        )
    }

    @Test
    fun otherPickerCallSitesKeepDefaultRowNames() {
        // Style stays local: no other UI call site may pass rowNameStyle.
        val callSiteFiles = listOf(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SingleExerciseScreen.kt",
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt",
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreen.kt",
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt",
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HistoryTab.kt",
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/cycle/TemplatePreviewEditSheet.kt",
        )
        for (path in callSiteFiles) {
            val src = readProjectFile(path)
            assertNotNull(src, "Could not locate $path on disk.")
            assertFalse(
                src.contains("rowNameStyle"),
                "$path must not pass rowNameStyle — row-name reduction is scoped to the Tag picker.",
            )
        }
    }

    @Test
    fun workoutTab_keepsOneMiniPickerAndBottomSheetAddExercise() {
        val src = readProjectFile(WORKOUT_TAB_PATH)
        assertNotNull(src, "Could not locate WorkoutTab.kt on disk.")
        assertEquals(
            1,
            src.split("MiniExercisePickerDialog(").size - 1,
            "WorkoutTab must contain exactly one MiniExercisePickerDialog call (Just Lift tagging).",
        )
        assertFalse(
            src.contains("fullScreen = true"),
            "WorkoutTab's ExercisePickerDialog (Add Exercise #1018) must stay a bottom sheet.",
        )
    }

    // ── Packet FE-2 chain + row chrome contract ───────────────────────────

    @Test
    fun rowNameStyle_threadedToRowContent() {
        val grouped = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/exercisepicker/GroupedExerciseList.kt",
        )
        assertNotNull(grouped, "Could not locate GroupedExerciseList.kt on disk.")
        assertEquals(
            2,
            grouped.split("rowNameStyle = rowNameStyle").size - 1,
            "GroupedExerciseList must forward rowNameStyle to the row at both hops (item lambda and SwipeableExerciseRow).",
        )
        val swipe = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/exercisepicker/SwipeableExerciseRow.kt",
        )
        assertNotNull(swipe, "Could not locate SwipeableExerciseRow.kt on disk.")
        assertTrue(
            swipe.contains("rowNameStyle = rowNameStyle"),
            "SwipeableExerciseRow must forward rowNameStyle to ExerciseRowContent.",
        )
    }

    @Test
    fun exerciseRowContent_nameUsesParameterAndKeepsChrome() {
        val src = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/exercisepicker/ExerciseRowContent.kt",
        )
        assertNotNull(src, "Could not locate ExerciseRowContent.kt on disk.")
        assertTrue(
            src.contains("style = rowNameStyle"),
            "The row name Text must use the rowNameStyle parameter.",
        )
        assertFalse(
            src.contains("style = MaterialTheme.typography.titleMedium"),
            "The row name must not hardcode titleMedium anymore.",
        )
        assertTrue(
            src.contains("fontWeight = FontWeight.Medium"),
            "The row name keeps FontWeight.Medium.",
        )
        // Row chrome stays thumbnail-bound: subtitle, badge, and 64dp thumbnail untouched.
        assertTrue(src.contains("bodySmall"), "Subtitle must stay bodySmall.")
        assertTrue(src.contains("labelSmall"), "Times-performed badge must stay labelSmall.")
        assertTrue(src.contains("size(64.dp)"), "Thumbnail must stay 64dp.")
    }

    // ── Issue #1225: Custom filter chip in the Tag exercise picker ─────────

    @Test
    fun miniPicker_exposesCustomFilterChipWiring() {
        val src = readMiniPickerSource()

        // The chip is visible: no more hardcoded hide + dead handler.
        assertTrue(
            src.contains("showCustomFilter = true"),
            "Tag exercise must show the existing Custom filter chip (showCustomFilter = true).",
        )
        assertFalse(
            src.contains("dead control"),
            "The dead-control comment is stale: the chip filters exercises that already exist.",
        )

        // State is threaded to the content, not pinned to false. (The clear handler inside the
        // same call legitimately assigns false, so pin the argument's right-hand side instead.)
        val contentBlock = balancedFrom(src, src.indexOf("ExercisePickerContent("), '(', ')')
        val customArgRhs = Regex("""showCustomOnly\s*=\s*([^,\n]+)""")
            .find(contentBlock)
            ?.groupValues
            ?.get(1)
            ?.trim()
        assertEquals(
            "showCustomOnly",
            customArgRhs,
            "ExercisePickerContent must receive the showCustomOnly state, not a hardcoded false.",
        )
        assertTrue(
            contentBlock.contains("enableCustomExercises = false"),
            "The tag dialog stays create-disabled (B5).",
        )
        assertTrue(
            src.contains("customExerciseCount = library.count { it.isCustom }"),
            "The custom count must come from the loaded library (B3), never getCustomExercises() or candidates.",
        )

        // Clear resets the chip state but must not re-arm the one-shot Recent default.
        val clearBlock = balancedBlock(src, src.indexOf("onClearAllFilters ="))
        assertTrue(
            clearBlock.contains("showCustomOnly = false"),
            "onClearAllFilters must clear showCustomOnly.",
        )
        assertFalse(
            clearBlock.contains("recentDefaultApplied = false"),
            "onClearAllFilters must not re-arm the one-shot Recent default (B2).",
        )

        // Custom-on clears Recent and consumes the one-shot Recent default.
        val toggleBlock = balancedBlock(src, src.indexOf("onToggleCustom ="))
        assertTrue(
            toggleBlock.contains("showRecentOnly = false"),
            "Custom-on must clear Recent so never-tagged customs are visible (B2).",
        )
        assertTrue(
            toggleBlock.contains("recentDefaultApplied = true"),
            "Custom-on must consume the one-shot Recent default so a late library emission cannot undo the tap (B2).",
        )
        assertFalse(
            toggleBlock.contains("recentDefaultApplied = false"),
            "onToggleCustom must never assign recentDefaultApplied = false (B2).",
        )

        // The one-shot Recent effect must not fire once Custom has claimed intent.
        val effectBlock = balancedBlock(src, src.indexOf("LaunchedEffect("))
        assertTrue(
            effectBlock.contains("!showCustomOnly"),
            "The one-shot Recent effect must be guarded by !showCustomOnly (B2).",
        )

        // Count comes from the already-loaded library, not a second SQL subscription.
        assertFalse(
            src.contains("getCustomExercises"),
            "Do not collect getCustomExercises(): it is a second subscription on the wrong SQL (B3).",
        )

        // B9 (public signature unchanged for the four call sites) is pinned exactly once,
        // in miniPicker_publicSignatureUnchanged above.
    }

    @Test
    fun emptyState_createSubtitleOnlyWhenCreateEnabled() {
        val src = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/exercisepicker/GroupedExerciseList.kt",
        )
        assertNotNull(src, "Could not locate GroupedExerciseList.kt on disk.")

        // The custom-empty branch only.
        val branchStart = src.indexOf("showCustomOnly && customExerciseCount == 0 ->")
        assertTrue(branchStart >= 0, "The custom-empty state branch not found.")
        val branch = balancedBlock(src, branchStart)

        // The spec's "Create your own exercises..." pin maps to this source literal.
        val subtitle = "Create your own exercises to track workouts"
        val titleIdx = branch.indexOf("\"No custom exercises yet\"")
        val ifIdx = branch.indexOf("if (enableCustomExercises)")
        val subtitleIdx = branch.indexOf(subtitle)

        assertTrue(titleIdx >= 0, "The empty-state title must stay unconditional.")
        assertTrue(ifIdx >= 0, "The create affordance must be gated on enableCustomExercises (ADR-5).")
        assertTrue(
            titleIdx < ifIdx,
            "The title \"No custom exercises yet\" must be emitted before the if (enableCustomExercises) gate.",
        )
        val ifBlockStart = branch.indexOf('{', ifIdx)
        val ifBlock = balancedBlock(branch, ifIdx)
        assertTrue(
            subtitleIdx in ifBlockStart until (ifBlockStart + ifBlock.length),
            "The create subtitle must live inside if (enableCustomExercises) so create-disabled hosts see only the title.",
        )
    }

    /** Source text from the first `{` at or after [startIndex] through its matching `}`. */
    private fun balancedBlock(src: String, startIndex: Int): String =
        balancedFrom(src, startIndex, '{', '}')

    /** Source text from the first [open] at or after [startIndex] through its matching [close]. */
    private fun balancedFrom(src: String, startIndex: Int, open: Char, close: Char): String {
        require(startIndex >= 0) { "balancedFrom: marker not found" }
        val start = src.indexOf(open, startIndex)
        check(start >= 0) { "balancedFrom: no '$open' after marker" }
        var depth = 0
        for (i in start until src.length) {
            val c = src[i]
            if (c == open) {
                depth++
            } else if (c == close) {
                depth--
                if (depth == 0) return src.substring(start, i + 1)
            }
        }
        error("balancedFrom: unbalanced '$open' after marker")
    }

    private fun readMiniPickerSource(): String {
        val path =
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/MiniExercisePickerDialog.kt"
        val src = readProjectFile(path)
        assertNotNull(
            src,
            "Could not locate MiniExercisePickerDialog.kt on disk. The test relies on the " +
                "project root being discoverable from the test runner's working directory.",
        )
        return src
    }

    private fun readExercisePickerSource(): String {
        val path =
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ExercisePicker.kt"
        val src = readProjectFile(path)
        assertNotNull(
            src,
            "Could not locate ExercisePicker.kt on disk. The test relies on the " +
                "project root being discoverable from the test runner's working directory.",
        )
        return src
    }

    private companion object {
        const val WORKOUT_TAB_PATH =
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt"
    }
}
