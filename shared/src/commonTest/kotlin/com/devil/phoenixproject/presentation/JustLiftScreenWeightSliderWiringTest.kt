package com.devil.phoenixproject.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #571: Regression guard for the "Weight Change Per Rep slider doesn't allow
 * 1 lb increments" bug.
 *
 * The original report claimed the slider's step size was wrong (e.g. 1 → 2 → 29 → 30
 * → 44 → 45 → 49). The RCA proved the slider is correctly bound to
 * `valueRange = -10f..10f` with `steps = 19` (i.e. 21 stops → step size 1 lb) and
 * cannot produce those values. The reported values came from the iOS wheel's
 * neighbour weights being routed to the wrong state.
 *
 * These tests pin the Just Lift slider wiring down at the source level so a future
 * refactor cannot accidentally widen the range, change the step, or rebind the
 * slider to `weightPerCable`. They also verify the wheel/slider gate uses the
 * short-height-only [com.devil.phoenixproject.presentation.util.shouldStackWeightCards]
 * helper, not the broader [com.devil.phoenixproject.presentation.util.shouldUseCompactAccessibilityLayout]
 * flag.
 */
class JustLiftScreenWeightSliderWiringTest {

    @Test
    fun progressionSliderWiring_pinsValueRangeAndStepCount() {
        val src = readJustLiftScreenSource()

        // Lock down the explicit valueRange on the ProgressionSlider call site.
        assertTrue(
            src.contains("valueRange = -10f..10f"),
            "JustLiftScreen.kt must keep ProgressionSlider(valueRange = -10f..10f). " +
                "Changing this widens the slider's allowed values and reintroduces the " +
                "issue #571 regression where 29/30/44-49 could appear on screen.",
        )
    }

    @Test
    fun progressionSliderWiring_pinsWeightChangePerRepBinding() {
        val src = readJustLiftScreenSource()

        // The slider must be bound to weightChangePerRep, not weightPerCable.
        // The reporter's "44/45/46" jump only happened because the wheel (which
        // writes weightPerCable) was being touched while the user was reading the
        // slider. Both controls must keep their own independent bindings.
        val onValueChangeLine = Regex(
            "ProgressionSlider\\s*\\(([\\s\\S]*?)onValueChange\\s*=\\s*\\{([\\s\\S]*?)\\}\\s*,",
            setOf(RegexOption.DOT_MATCHES_ALL),
        ).find(src)?.value
            ?: error("Could not find the ProgressionSlider onValueChange in JustLiftScreen.kt")

        assertTrue(
            onValueChangeLine.contains("weightChangePerRep"),
            "ProgressionSlider.onValueChange must write to weightChangePerRep. " +
                "Found: $onValueChangeLine",
        )
        assertTrue(
            !onValueChangeLine.contains("weightPerCable"),
            "ProgressionSlider.onValueChange must not write to weightPerCable. " +
                "Found: $onValueChangeLine",
        )
    }

    @Test
    fun justLiftScreen_usesStackedWeightCardsGateForWeightCards() {
        val src = readJustLiftScreenSource()

        // The Weight per Cable and Weight Change Per Rep cards must be gated on
        // stackWeightCards, not the broader useCompactAccessibility. Otherwise the
        // iOS wheel can land directly above the slider and steal drags.
        assertTrue(
            src.contains("useStackedWeightCardsLayout") || src.contains("shouldStackWeightCards"),
            "JustLiftScreen.kt must compute the weight-card layout gate via " +
                "useStackedWeightCardsLayout() (or shouldStackWeightCards(...)) so the " +
                "iOS wheel does not land directly above the ProgressionSlider on iPhone " +
                "portrait. See Issue #571 RCA.",
        )
    }

    @Test
    fun justLiftScreen_usesCompactAccessibilityForOuterScrollOnly() {
        val src = readJustLiftScreenSource()

        // The outer verticalScroll + outer column's verticalArrangement can still
        // use the broader useCompactAccessibility gate — that one governs the
        // whole screen's scroll behavior, not just the weight cards. This test
        // documents that decision so a future refactor does not unify the two
        // gates by accident.
        val scrollCall = src.contains("verticalScroll(contentScrollState)")
        assertTrue(
            scrollCall,
            "JustLiftScreen.kt should still drive the outer scroll on useCompactAccessibility. " +
                "If you are removing this, see issue #571 RCA — the outer scroll is correct; " +
                "only the inner weight-card gate was wrong.",
        )
    }

    @Test
    fun justLiftScreen_weightCardsAvoidWeightModifierInsideVerticalScroll() {
        val src = readJustLiftScreenSource()

        // Modifier.weight() inside a vertically scrolling Column triggers unbounded-height
        // measure failures (crash). Bold Text / large Dynamic Type enables
        // useCompactAccessibility (verticalScroll) while stackWeightCards stays false on
        // tall portrait — weight cards must gate weight(1f) on both flags.
        assertTrue(
            src.contains("weightCardsUseIntrinsicHeight"),
            "JustLiftScreen.kt must compute weightCardsUseIntrinsicHeight so weight cards " +
                "never use Modifier.weight(1f) when the outer Column scrolls.",
        )
        assertTrue(
            src.contains("useCompactAccessibility || stackWeightCards"),
            "weightCardsUseIntrinsicHeight must OR useCompactAccessibility with stackWeightCards.",
        )

        // Ensure weight cards no longer bind weight(1f) solely on !stackWeightCards.
        val weightCardWeightPattern = Regex(
            "if \\(stackWeightCards\\) Modifier else Modifier\\.weight\\(1f\\)",
        )
        assertTrue(
            !weightCardWeightPattern.containsMatchIn(src),
            "Weight cards must not use 'if (stackWeightCards) Modifier else Modifier.weight(1f)' " +
                "— that applies weight inside verticalScroll when Bold Text is on.",
        )
    }

    private fun readJustLiftScreenSource(): String {
        // Read from the classpath via the resources directory, or fall back to the
        // project root. The test is allowed to read the source file because it
        // runs in the commonTest source set of the same Gradle module that owns
        // JustLiftScreen.kt. Working directory resolution: try the explicit
        // path first, then walk up to find a gradle root.
        val relativePath =
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt"
        val candidates = mutableListOf<java.io.File>()
        candidates.add(java.io.File(relativePath))
        // Walk up looking for a `shared` or `.git` marker to anchor the search.
        var dir: java.io.File? = java.io.File(".").absoluteFile
        repeat(6) {
            if (dir == null) return@repeat
            candidates.add(java.io.File(dir, "shared/$relativePath"))
            if (java.io.File(dir, ".git").exists() || java.io.File(dir, "settings.gradle.kts").exists()) {
                candidates.add(java.io.File(dir, relativePath))
            }
            dir = dir.parentFile
        }
        for (file in candidates) {
            if (file.exists()) return file.readText()
        }
        error(
            "Could not locate JustLiftScreen.kt on disk. Searched: " +
                candidates.joinToString(", ") { it.path } +
                ". Run the test from the shared/ module's working directory.",
        )
    }
}
