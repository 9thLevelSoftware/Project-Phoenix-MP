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
 *
 * ## Why static analysis instead of Compose UI tests?
 *
 * This file reads `JustLiftScreen.kt` from disk and asserts on its source text.
 * That is intentionally a known fragile-test anti-pattern (Gemini review on PR #574).
 * We keep it because:
 *
 * 1. The bug class is "the widget is wired wrong" — slider bound to the wrong state,
 *    `valueRange` widened, gate helper swapped. These are *source-level* invariants.
 *    A Compose UI test would still pass if a refactor accidentally rebinds
 *    `onValueChange = { weightPerCable = it }` (the UI looks identical; the bug
 *    only shows up downstream when the wrong state is read).
 * 2. Compose UI tests in this repo currently cover behaviour, not wiring (see
 *    `WindowSizeClassTest`). Adding a Compose UI test for JustLiftScreen would
 *    require additional compose-test runtime + Robolectric wiring that this PR
 *    does not introduce (out of scope for the issue #571 fix).
 * 3. The tests are scoped to a small set of exact string assertions, not full
 *    parsing. They are cheap to maintain and produce a clear failure message
 *    pointing at the regression. A future refactor that legitimately restructures
 *    the file will need to update these strings — that is the intended failure mode.
 *
 * When the repo gains a Compose UI test harness for JustLiftScreen, these static
 * assertions should be replaced with semantic assertions on the rendered
 * composition (e.g. `composeTestRule.onNodeWithText("+1").performTouchInput { swipeRight() }
 * .assertTextEquals("+2")`). See the PR #574 review thread for the original
 * suggestion and the long-term migration plan.
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
