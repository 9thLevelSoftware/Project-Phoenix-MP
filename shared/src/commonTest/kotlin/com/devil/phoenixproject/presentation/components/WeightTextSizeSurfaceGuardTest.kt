package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #1367 T16/T17/T20 + SD4/SD12: source-text guards for the weight-text-size surfaces.
 *
 * T16: the weight `SliderWithButtons` sites carry the explicit `isWeightValue = true`
 * opt-in (SD5) and the rep sites do not. The expectations below are derived from the
 * call-site re-census on this branch (3 weight / 5 rep at main@4d4de558) — if the census
 * numbers change because a call site was legitimately added, re-census and update the
 * expectations rather than deleting the guard.
 *
 * T17: both `WorkoutHud.HudBottomBar` value branches (numeric/"Adaptive" and bodyweight
 * "No machine load") use the scaled value style while the supporting labelSmall labels
 * stay unscaled (SD9).
 *
 * T20/SD4: `shouldStackWeightCards` stays byte-identical (iOS gesture-conflict guard),
 * and Just Lift / `CompactNumberPicker` never see the weight text scale (Option B).
 *
 * SD12: the presentation-only invariant — no backup/restore, portal codec, SQLDelight,
 * or tvOS file ever touches the preference key.
 */
class WeightTextSizeSurfaceGuardTest {

    private val sliderComponentPath =
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/SliderWithButtons.kt"
    private val screenPaths = listOf(
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SetReadyScreen.kt",
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineOverviewScreen.kt",
        "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RestTimerCard.kt",
    )
    private val hudPath = "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutHud.kt"
    private val windowSizeClassPath = "src/commonMain/kotlin/com/devil/phoenixproject/presentation/util/WindowSizeClass.kt"
    private val justLiftPath = "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt"
    private val exerciseEditPath = "src/phoneMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseEditBottomSheet.kt"
    private val compactPickerPath = "src/phoneMain/kotlin/com/devil/phoenixproject/presentation/components/CompactNumberPicker.kt"
    private val compactPickerAndroidPath =
        "src/androidMain/kotlin/com/devil/phoenixproject/presentation/components/CompactNumberPicker.android.kt"
    private val compactPickerIosPath =
        "src/iosMain/kotlin/com/devil/phoenixproject/presentation/components/CompactNumberPicker.ios.kt"

    private fun source(path: String): String = requireNotNull(readProjectFile(path)) { path }

    /** Extract every balanced-paren call body starting at each [marker] occurrence. */
    private fun callBodies(source: String, marker: String): List<String> {
        val bodies = mutableListOf<String>()
        var searchFrom = 0
        while (true) {
            val start = source.indexOf(marker, searchFrom)
            if (start < 0) break
            val open = source.indexOf('(', start)
            var depth = 0
            var end = -1
            for (index in open until source.length) {
                when (source[index]) {
                    '(' -> depth += 1
                    ')' -> {
                        depth -= 1
                        if (depth == 0) {
                            end = index
                            break
                        }
                    }
                }
            }
            assertTrue(end > open, "unbalanced call at $start")
            bodies.add(source.substring(open, end + 1))
            searchFrom = end
        }
        return bodies
    }

    private fun isWeightSite(body: String): Boolean =
        body.contains("rest_weight_per_cable") || body.contains("\"Weight per cable\"")

    @Test
    fun `weight slider sites opt in and rep slider sites do not`() {
        val allSites = screenPaths.flatMap { path -> callBodies(source(path), "SliderWithButtons(") }
        // Branch census at 4d4de558: 3 weight + 5 rep = 8 total (SD5/SD13).
        assertEquals(8, allSites.size, "SliderWithButtons call-site census changed — re-census and update")
        val weightSites = allSites.filter { body -> isWeightSite(body) }
        val repSites = allSites.filter { body -> !isWeightSite(body) }
        assertEquals(3, weightSites.size, "weight-site census changed — re-census and update")
        assertEquals(5, repSites.size, "rep-site census changed — re-census and update")

        weightSites.forEach { body ->
            assertContains(body, "isWeightValue = true", message = "weight site missing the SD5 opt-in: $body")
        }
        repSites.forEach { body ->
            assertFalse(body.contains("isWeightValue"), "rep site must not receive the treatment: $body")
        }
    }

    @Test
    fun `slider component keeps the explicit default and gates scaling on the opt-in`() {
        val component = source(sliderComponentPath)
        assertContains(component, "isWeightValue: Boolean = false")
        assertContains(component, "if (isWeightValue)")
    }

    @Test
    fun `hud value branches are scaled while labels stay supporting text`() {
        val hud = source(hudPath)
        // Extract the HudBottomBar function source (signature + balanced brace body).
        val marker = "private fun HudBottomBar("
        val start = hud.indexOf(marker)
        assertTrue(start >= 0, marker)
        val openParen = hud.indexOf('(', start)
        var depth = 0
        var closeParen = -1
        for (index in openParen until hud.length) {
            when (hud[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) {
                        closeParen = index
                        break
                    }
                }
            }
        }
        val openBody = hud.indexOf('{', closeParen)
        var braceDepth = 0
        var closeBody = -1
        for (index in openBody until hud.length) {
            when (hud[index]) {
                '{' -> braceDepth += 1
                '}' -> {
                    braceDepth -= 1
                    if (braceDepth == 0) {
                        closeBody = index
                        break
                    }
                }
            }
        }
        val bottomBar = hud.substring(start, closeBody + 1)
        // Both value branches use the scaled value style (SD9).
        assertEquals(
            2,
            Regex("""style = valueStyle""").findAll(bottomBar).count(),
            "both HUD value branches must use the scaled value style",
        )
        assertTrue(
            Regex(""""No machine load",\s*style = valueStyle""").containsMatchIn(bottomBar),
            "bodyweight value branch must be scaled",
        )
        assertTrue(
            Regex("""formatWeight\(selectedDisplayKg, weightUnit\)\s*},\s*style = valueStyle""")
                .containsMatchIn(bottomBar),
            "numeric/Adaptive value branch must be scaled",
        )
        // Supporting labels remain labelSmall (unscaled).
        assertTrue(
            bottomBar.contains("MaterialTheme.typography.labelSmall"),
            "supporting labels must stay labelSmall",
        )
    }

    @Test
    fun `shouldStackWeightCards stays byte identical`() {
        val window = source(windowSizeClassPath)
        val expected = """
            fun shouldStackWeightCards(
                windowSizeClass: WindowSizeClass,
            ): Boolean = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact
        """.trimIndent()
        assertContains(window, expected, message = "shouldStackWeightCards changed (SD4: iOS gesture-conflict guard)")
    }

    @Test
    fun `compact number picker selectors in exercise setup and just lift opt into scaling`() {
        val allSites = listOf(exerciseEditPath, justLiftPath)
            .flatMap { path -> callBodies(source(path), "CompactNumberPicker(") }
        assertEquals(5, allSites.size, "CompactNumberPicker call-site census changed — re-census and update")

        allSites.forEach { body ->
            assertContains(body, "applySelectorScale = true", message = "selector missing applySelectorScale opt-in: $body")
        }

        val expectSrc = source(compactPickerPath)
        assertEquals(
            2,
            Regex("""applySelectorScale:\s*Boolean\s*=\s*false""").findAll(expectSrc).count(),
            "Both CompactNumberPicker expect overloads must default applySelectorScale to false",
        )
        val androidSrc = source(compactPickerAndroidPath)
        assertContains(androidSrc, "if (applySelectorScale)")
        assertContains(androidSrc, "applyNumberPickerTextSize")
        val iosSrc = source(compactPickerIosPath)
        assertContains(iosSrc, "if (applySelectorScale)")
    }

    @Test
    fun `exercise edit bottom sheet and just lift scale weight readouts`() {
        val exerciseEdit = source(exerciseEditPath)
        assertContains(exerciseEdit, "LocalWeightTextScale")
        assertContains(exerciseEdit, "scaledForWeightValue")
        assertContains(exerciseEdit, "shouldStackExerciseSetPickers")

        val justLift = source(justLiftPath)
        assertContains(justLift, "LocalWeightTextScale")
        assertContains(justLift, "scaledForWeightValue")
    }

    @Test
    fun `presentation only invariant - no backup codec sync schema or tvos touches the key`() {
        val forbiddenPaths = listOf(
            "src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt",
            "src/commonMain/kotlin/com/devil/phoenixproject/util/BackupModels.kt",
            "src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/ProfilePreferencesCodec.kt",
        )
        val forbiddenMarkers = listOf("weight_text_scale", "weightTextScale", "WeightTextScale")
        forbiddenPaths.forEach { path ->
            val text = source(path)
            forbiddenMarkers.forEach { marker ->
                assertFalse(text.contains(marker), "$path must not touch $marker (SD12/SD6)")
            }
        }
    }
}
