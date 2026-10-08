package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Issue #1223 FE-C source-contract tests (supplemental to the behavioral tests in
 * `presentation/viewmodel/`). The repo has no Compose UI test dependency in
 * commonTest, so these pin the wiring the behavioral tests cannot reach:
 * draft-injection ordering in RoutineEditorScreen, the Create-with-AI entry, the
 * preserved Add FAB, the no-argument route, and the exact §1.4 consent/disclosure
 * copy in all five locales.
 */
class AiRoutineDraftInjectionContractTest {

    private fun readRequired(relativePath: String): String {
        val source = readProjectFile(relativePath)
        assertNotNull(source, "Could not read $relativePath")
        return source
    }

    /** Removes // line comments so negative assertions cannot trip on commentary. */
    private fun stripComments(source: String): String =
        source.replace(Regex("//[^\n]*"), "")

    private fun stringValue(xml: String, key: String): String {
        val match = Regex(
            "<string name=\"${Regex.escape(key)}\">(.*?)</string>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(xml)
        return assertNotNull(match, "string key '$key' not found").groupValues[1]
    }

    private fun keyPresentWithText(xml: String, key: String): Boolean {
        val match = Regex(
            "<string name=\"${Regex.escape(key)}\">(.*?)</string>",
            RegexOption.DOT_MATCHES_ALL,
        ).find(xml) ?: return false
        return match.groupValues[1].isNotBlank()
    }

    // === RoutineEditorScreen: holder consumption + expired-draft recovery ===

    @Test
    fun routineEditorScreen_consumesGeneratedDraftHolderBeforeTheRepositoryLookup() {
        val src = readRequired(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt",
        )
        val consumeIndex = src.indexOf("viewModel::consumeGeneratedRoutine")
        val lookupIndex = src.indexOf("viewModel::getRoutineById")
        assertTrue(consumeIndex >= 0, "RoutineEditorScreen must consume the AI draft holder")
        assertTrue(lookupIndex >= 0, "RoutineEditorScreen must keep its repository lookup")
        assertTrue(
            consumeIndex < lookupIndex,
            "the holder must be consumed BEFORE getRoutineById (issue #1223 load effect)",
        )
    }

    @Test
    fun routineEditorScreen_skipsLookupAndNewBranchForConsumedDraftsAndRecoversExpiredOnes() {
        val src = readRequired(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt",
        )
        assertTrue(
            src.contains("AiRoutineEditorLoad.StagedDraft"),
            "a consumed staged draft must load straight into editor state",
        )
        assertTrue(
            src.contains("AiRoutineEditorLoad.ExpiredDraft"),
            "missing holder + missing id must show the expired-draft recovery state",
        )
        assertTrue(
            src.contains("generatedDraftSaveEnabled("),
            "Save must be gated by the generated-draft save rules (Save disabled when expired)",
        )
    }

    @Test
    fun routineEditorScreen_keepsTheExistingSavePathAsTheOnlyWrite() {
        val src = readRequired(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineEditorScreen.kt",
        )
        val occurrences = src.split("viewModel.saveRoutine(").size - 1
        assertTrue(
            occurrences == 1,
            "the editor must keep exactly one save call site (the first and only write), found $occurrences",
        )
    }

    // === Create-with-AI entry ===

    @Test
    fun dailyRoutinesScreen_navigatesToAiRoutinesRouteForTheCreateWithAiCallback() {
        val src = readRequired(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/DailyRoutinesScreen.kt",
        )
        val start = src.indexOf("onCreateWithAi = {")
        assertTrue(start >= 0, "DailyRoutinesScreen must wire onCreateWithAi")
        val end = src.indexOf("onExportRoutineCsv", startIndex = start)
        assertTrue(end > start, "unexpected RoutinesTab call layout")
        val callback = src.substring(start, end)

        assertTrue(
            callback.contains("NavigationRoutes.AiRoutine.route"),
            "the AI callback must navigate to the ai_routine route",
        )
        assertTrue(
            !callback.contains("createRoute(\"new\")"),
            "the AI callback must never open the \"new\" editor directly",
        )
        assertTrue(
            callback.contains("showWorkoutActiveDialog = true"),
            "the AI entry reuses the workout-active guard dialog (issue #130)",
        )
    }

    @Test
    fun routinesTab_keepsTheAddFabAndAddsTheSecondaryCreateWithAiAction() {
        val src = readRequired(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutinesTab.kt",
        )
        // The Add FAB is sacred and unchanged.
        assertTrue(src.contains("FloatingActionButton("), "Add FAB missing")
        assertTrue(src.contains("onClick = onCreateRoutine"), "Add FAB wiring changed")
        assertTrue(src.contains("Icons.Default.Add"), "Add FAB icon changed")
        assertTrue(src.contains("cd_add_routine"), "Add FAB content description changed")

        // The optional AI action.
        assertTrue(
            src.contains("onCreateWithAi: (() -> Unit)? = null"),
            "RoutinesTab must expose the optional onCreateWithAi entry",
        )
        val normalModeStart = src.indexOf("// Normal mode: Single + FAB")
        val selectionModeStart = src.indexOf("// Selection mode:", startIndex = normalModeStart)
        assertTrue(normalModeStart >= 0 && selectionModeStart > normalModeStart, "FAB area layout changed")
        val normalModeBlock = src.substring(normalModeStart, selectionModeStart)
        assertTrue(
            normalModeBlock.contains("onCreateWithAi"),
            "the Create with AI action must live with the normal-mode FABs (hidden in selection mode)",
        )
        val aiStart = normalModeBlock.indexOf("if (onCreateWithAi != null)")
        val addStart = normalModeBlock.indexOf("onClick = onCreateRoutine")
        assertTrue(
            aiStart in 0 until addStart,
            "the AI SmallFloatingActionButton must sit above the Add FAB in the same column",
        )
        assertTrue(
            normalModeBlock.contains("SmallFloatingActionButton"),
            "the AI action must be a SmallFloatingActionButton",
        )
    }

    // === Navigation ===

    @Test
    fun navigation_aiRoutineRouteHasNoArguments() {
        val routes = readRequired(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavigationRoutes.kt",
        )
        assertTrue(
            routes.contains("object AiRoutine : NavigationRoutes(\"ai_routine\")"),
            "NavigationRoutes.AiRoutine must be the plain \"ai_routine\" route (no arguments)",
        )

        val graph = readRequired(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt",
        )
        assertTrue(
            graph.contains("composable(NavigationRoutes.AiRoutine.route)"),
            "NavGraph must register the ai_routine destination beside RoutineEditor",
        )
    }

    // === Prompt screen: consent/disclosure + tier gate ===

    @Test
    fun promptScreen_showsTheSection14ConsentCopyBeforeGenerate() {
        val src = readRequired(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AiRoutinePromptScreen.kt",
        )
        listOf(
            "ai_routine_disclosure",
            "ai_routine_retention",
            "ai_routine_load_toggle",
            "ai_routine_load_toggle_helper",
            "ai_routine_hint_limitation",
        ).forEach { key ->
            assertTrue(src.contains("Res.string.$key"), "prompt screen must show $key")
        }
        assertTrue(
            src.contains("mutableStateOf(false)") && src.contains("includeLoadContext"),
            "the load-context toggle must default OFF",
        )
    }

    @Test
    fun promptScreen_tierGateUsesTheLadderAndLinkAccountOnly() {
        val src = stripComments(
            readRequired(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AiRoutinePromptScreen.kt",
            ),
        )
        assertTrue(
            src.contains("meetsAiRoutineTier("),
            "the gate must use the FLAME ladder helper, never isPremium",
        )
        assertTrue(
            !src.contains("isPremium"),
            "the AI flow must never gate on isPremium",
        )
        assertTrue(
            src.contains("NavigationRoutes.LinkAccount.route"),
            "below-Flame users are routed to the existing LinkAccount screen, not a billing screen",
        )
    }

    // === Preview screen: badge, server disclaimer, exclusion wording ===

    @Test
    fun previewScreen_showsBadgeServerDisclaimerAndExclusionWording() {
        val preview = readRequired(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AiRoutinePreviewScreen.kt",
        )
        assertTrue(
            preview.contains("Res.string.ai_routine_preview_badge"),
            "preview must show the AI-GENERATED badge",
        )
        assertTrue(
            preview.contains("AI_ROUTINE_DRAFT_DISCLAIMER"),
            "the disclaimer shown must be the server constant, verbatim",
        )
        assertTrue(
            preview.contains("Res.string.ai_routine_preview_exclusion"),
            "preview must show the §1.4 exclusion wording",
        )
        assertTrue(
            preview.contains("Res.string.ai_routine_preview_unsaved_note"),
            "preview must say nothing is saved until the editor Save",
        )

        val prompt = readRequired(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/AiRoutinePromptScreen.kt",
        )
        assertTrue(
            prompt.contains("estimateRoutineDuration("),
            "minutes estimates come from RoutineTimeEstimator, not the model's claims",
        )
    }

    // === Strings: exact §1.4 English source + 5 locales ===

    @Test
    fun strings_englishConsentAndDisclosureCopyIsTheExactSignedOffSource() {
        val en = readRequired("src/commonMain/composeResources/values/strings.xml")

        val exact = mapOf(
            "ai_routine_disclosure" to
                "Your prompt is sent to Phoenix's AI service and processed by an external AI " +
                "model provider to create this draft. Phoenix does not save your prompt.",
            "ai_routine_retention" to
                "The provider may retain the content briefly for safety monitoring and does " +
                "not use it to train models.",
            "ai_routine_load_toggle" to "Use my weights to choose exercises",
            "ai_routine_load_toggle_helper" to
                "Shares estimated one-rep max numbers for exercises you've done — no names, " +
                "no workout history. Off by default.",
            "ai_routine_hint_limitation" to
                "Constraint hints currently understand English muscle and mode words.",
            "ai_routine_preview_badge" to "AI-GENERATED · UNSAVED DRAFT",
            "ai_routine_preview_unsaved_note" to "Nothing is saved until you save in the editor.",
            "ai_routine_preview_exclusion" to
                "This draft avoids muscle groups you excluded (as classified in the exercise " +
                "catalog). An exercise may still involve a nearby muscle.",
            "ai_routine_draft_expired" to
                "This generated draft is no longer available — generate again",
        )
        exact.forEach { (key, expected) ->
            val actual = stringValue(en, key).replace("\\'", "'")
            assertTrue(
                actual == expected,
                "values/$key must be exactly the signed-off §1.4 source.\nexpected: $expected\nactual:   $actual",
            )
        }
    }

    @Test
    fun strings_everyNewKeyExistsInAllFiveLocales() {
        val locales = listOf(
            "values",
            "values-de",
            "values-es",
            "values-fr",
            "values-nl",
        )
        val xmlByLocale = locales.associateWith { locale ->
            readRequired("src/commonMain/composeResources/$locale/strings.xml")
        }
        val keys = Regex("<string name=\"(ai_routine_[a-z0-9_]+)\">")
            .findAll(xmlByLocale.getValue("values"))
            .map { it.groupValues[1] }
            .toSet()
        assertTrue(keys.size >= 30, "expected the full ai_routine_* key set, found ${keys.size}")

        locales.forEach { locale ->
            val xml = xmlByLocale.getValue(locale)
            keys.forEach { key ->
                assertTrue(
                    keyPresentWithText(xml, key),
                    "$locale must define a non-empty value for $key",
                )
            }
        }
    }

    @Test
    fun strings_translationsAreRealNotEnglishPlaceholders() {
        val en = readRequired("src/commonMain/composeResources/values/strings.xml")
        val sentenceKeys = listOf(
            "ai_routine_disclosure",
            "ai_routine_retention",
            "ai_routine_load_toggle_helper",
            "ai_routine_hint_limitation",
            "ai_routine_preview_exclusion",
            "ai_routine_preview_unsaved_note",
            "ai_routine_draft_expired",
            "ai_routine_error_not_subscribed",
            "ai_routine_preview_badge",
        )
        listOf("values-de", "values-es", "values-fr", "values-nl").forEach { locale ->
            val xml = readRequired("src/commonMain/composeResources/$locale/strings.xml")
            sentenceKeys.forEach { key ->
                val translated = stringValue(xml, key).replace("\\'", "'")
                val english = stringValue(en, key).replace("\\'", "'")
                assertTrue(
                    translated.isNotBlank() && translated != english,
                    "$locale must translate $key (found: $translated)",
                )
            }
        }
    }
}
