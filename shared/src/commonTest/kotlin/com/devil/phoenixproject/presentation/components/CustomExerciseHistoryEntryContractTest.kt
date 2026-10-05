package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #362: pins the custom-exercise History entry-point contract that the signed-off
 * design approved — the Edit Exercise dialog gains an optional `onViewHistory` button
 * above Delete/Save, only `SingleExerciseScreen` wires it (dismiss without saving, then
 * navigate the existing id-keyed `ExerciseDetail` route), and every other picker host
 * stays exactly as it is today.
 *
 * The custom hold stays "edit" and the canned hold stays "history"; nothing here may
 * change the long-press split in `GroupedExerciseList`.
 */
class CustomExerciseHistoryEntryContractTest {
    private val dialogPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/CreateExerciseDialog.kt"
    private val singleExercisePath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SingleExerciseScreen.kt"
    private val groupedListPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/exercisepicker/GroupedExerciseList.kt"
    private val exercisePickerPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ExercisePicker.kt"
    private val miniPickerPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/MiniExercisePickerDialog.kt"
    private val routineEditorPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt"
    private val profileScreenPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ProfileScreen.kt"
    private val workoutTabPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt"
    private val defaultStringsPath = "src/commonMain/composeResources/values/strings.xml"

    private val localizedStrings = mapOf(
        "src/commonMain/composeResources/values/strings.xml" to "History",
        "src/commonMain/composeResources/values-de/strings.xml" to "Verlauf",
        "src/commonMain/composeResources/values-nl/strings.xml" to "Geschiedenis",
        "src/commonMain/composeResources/values-es/strings.xml" to "Historial",
        "src/commonMain/composeResources/values-fr/strings.xml" to "Historique",
        "src/commonMain/composeResources/values-it/strings.xml" to "Cronologia",
    )

    @Test
    fun groupedExerciseListLongPressKeepsCustomEditAndCannedHistoryBranches() {
        val list = source(groupedListPath)
        val longPress = braceBlockAfter(list, "onLongPress = when")

        val customBranch = assertNotNull(
            Regex(
                """exercise\.isCustom\s*&&\s*onEditExercise\s*!=\s*null\s*->\s*\{[^{}]*\{[^{}]*\}[^{}]*\}""",
            ).find(longPress)?.value,
            "custom long-press must keep routing to onEditExercise",
        )
        assertContains(customBranch, "onEditExercise(exercise)")
        assertFalse(
            customBranch.contains("onViewExerciseDetail"),
            "custom long-press must not fall through to history: $customBranch",
        )

        val cannedBranch = assertNotNull(
            Regex(
                """onViewExerciseDetail\s*!=\s*null\s*->\s*\{[^{}]*\{[^{}]*\}[^{}]*\}""",
            ).find(longPress)?.value,
            "canned long-press must keep routing to onViewExerciseDetail",
        )
        assertContains(cannedBranch, "onViewExerciseDetail(exercise)")
        assertFalse(
            cannedBranch.contains("onEditExercise"),
            "canned long-press must not open the edit dialog: $cannedBranch",
        )

        assertTrue(
            longPress.indexOf("exercise.isCustom") < longPress.indexOf("onViewExerciseDetail != null"),
            "the custom edit branch must be checked before the canned history branch",
        )
    }

    @Test
    fun createExerciseDialogDeclaresViewHistoryAsAnOptionalEditOnlyControl() {
        val dialog = source(dialogPath)

        val signature = parenthesizedCall(dialog, "fun CreateExerciseDialog")
        assertContains(signature, "onViewHistory: (() -> Unit)? = null")
        assertFalse(signature.contains("themeMode"), "dialog must not take themeMode: $signature")
        assertFalse(signature.contains("NavController"), "dialog must not take a NavController: $signature")
        assertFalse(signature.contains("exerciseId"), "the id already lives on existingExercise: $signature")

        assertEquals(
            1,
            Regex("""Res\.string\.action_history""").findAll(dialog).count(),
            "action_history must be referenced exactly once",
        )
        assertContains(
            dialog,
            "val historyContentDescription = stringResource(Res.string.cd_exercise_history_detail)",
        )
        assertTrue(
            Regex(
                """if\s*\(\s*isEditMode\s*&&\s*onViewHistory\s*!=\s*null\s*&&\s*existingExercise\?\.id\?\.isNotBlank\(\)\s*==\s*true\s*\)""",
            ).containsMatchIn(dialog),
            "History must require edit mode + a wired callback + a non-blank id",
        )
    }

    @Test
    fun historyButtonIsFullWidthAboveDeleteSaveAndTouchesOnlyViewHistory() {
        val dialog = source(dialogPath)
        val actionIndex = dialog.indexOf("Res.string.action_history")
        assertTrue(actionIndex > 0, "History label missing")

        val buttonStart = dialog.lastIndexOf("OutlinedButton(", actionIndex)
        assertTrue(buttonStart > 0, "History must render as an OutlinedButton")
        val buttonEnd = dialog.indexOf("Res.string.action_history", actionIndex)
        val button = dialog.substring(buttonStart, buttonEnd + "Res.string.action_history".length)

        assertContains(button, "onClick = { onViewHistory.invoke() }")
        assertContains(button, ".fillMaxWidth()")
        assertContains(button, ".height(56.dp)")
        assertContains(button, "shape = MaterialTheme.shapes.medium")
        assertContains(button, "contentColor = MaterialTheme.colorScheme.onSurfaceVariant")
        assertContains(button, "clearAndSetSemantics")
        assertContains(button, "contentDescription = historyContentDescription")

        assertFalse(button.contains("onSave"), "History must never save the draft: $button")
        assertFalse(button.contains("onDelete"), "History must never delete: $button")
        assertFalse(button.contains("onDismiss"), "History must not dismiss on its own: $button")
        assertFalse(button.contains("showDeleteConfirmation"), "History must not open delete confirmation")
        assertFalse(
            button.contains("BorderStroke") || button.contains("colorScheme.error"),
            "Delete owns the error outline; History is a neutral outlined button",
        )

        val rowStart = dialog.indexOf("// Buttons")
        assertTrue(rowStart > buttonEnd, "History must sit above the Delete/Save row")
    }

    @Test
    fun singleExerciseScreenDisposesTheDraftThenNavigatesToExerciseDetail() {
        val screen = source(singleExercisePath)
        val block = braceBlockAfter(screen, "onViewHistory =")

        assertContains(block, "showCreateDialog = false")
        assertContains(block, "exerciseToEdit = null")
        assertContains(block, "NavigationRoutes.ExerciseDetail.createRoute(historyId)")
        assertTrue(
            block.indexOf("takeIf") < block.indexOf("showCreateDialog = false"),
            "the exercise id must be captured before the edit state is cleared",
        )
        assertFalse(block.contains("popUpTo"), "History pushes over the list route: $block")
        assertFalse(block.contains("coroutineScope"), "History must not touch persistence: $block")
        assertFalse(
            block.contains("resolveCustomExerciseSaveAction"),
            "History must not resolve a save action: $block",
        )
        assertFalse(block.contains("deleteCustomExercise"), "History must not delete: $block")
    }

    @Test
    fun everyOtherPickerHostStaysHistoryFree() {
        for (path in listOf(exercisePickerPath, miniPickerPath, routineEditorPath, profileScreenPath, workoutTabPath)) {
            assertFalse(
                source(path).contains("onViewHistory"),
                "$path must not wire the History button",
            )
        }

        val picker = source(exercisePickerPath)
        val call = parenthesizedCall(picker, "CreateExerciseDialog(")
        assertFalse(call.contains("onViewHistory"), "ExercisePicker host must omit the callback: $call")
    }

    @Test
    fun actionHistoryIsLocalizedInEveryRequestedLocale() {
        for ((path, expected) in localizedStrings) {
            assertEquals(expected, resourceValue(source(path), "action_history"), path)
        }
    }

    @Test
    fun historyAccessibilityDescriptionStaysTheExistingSentence() {
        assertEquals(
            "View exercise history and 1RM",
            resourceValue(source(defaultStringsPath), "cd_exercise_history_detail"),
        )
    }

    private fun source(path: String): String = requireNotNull(readProjectFile(path)) { path }

    private fun resourceValue(xml: String, key: String): String = requireNotNull(
        Regex(
            """<string\b[^>]*\bname\s*=\s*"${Regex.escape(key)}"[^>]*>(.*?)</string>""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(xml),
    ) { key }.groupValues[1]
        .trim()
        .replace("&apos;", "'")
        .replace("\\'", "'")

    /** Text of the `{ ... }` block that opens immediately after [marker]. */
    private fun braceBlockAfter(source: String, marker: String): String {
        val markerIndex = source.indexOf(marker)
        assertTrue(markerIndex >= 0, marker)
        val open = source.indexOf('{', markerIndex)
        assertTrue(open >= 0, "$marker opening brace")
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
        }
        error("Unclosed block: $marker")
    }

    /** Argument list of the call/fun named by [marker], excluding the parentheses. */
    private fun parenthesizedCall(source: String, marker: String): String {
        val markerIndex = source.indexOf(marker)
        assertTrue(markerIndex >= 0, marker)
        val open = source.indexOf('(', markerIndex)
        assertTrue(open >= 0, "$marker opening paren")
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
        }
        error("Unclosed call: $marker")
    }
}
