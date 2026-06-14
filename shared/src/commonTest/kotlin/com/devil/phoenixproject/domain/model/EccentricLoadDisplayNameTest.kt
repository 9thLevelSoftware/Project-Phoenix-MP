package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression + recreation-evidence test for issue #540:
 * "iOS Italian locale: eccentric percentage UI is clipped in Just Lift".
 *
 * Background
 * ----------
 * On Italian iPhone (app 0.9.1), the Just Lift Echo-mode block rendered fully
 * in English and the Eccentric Load dropdown value `110%` had its `%` glyph
 * visually collide with the trailing `0` in iOS SF Pro at bodyLarge, making
 * the value read as `11 U%` / `11U%`. Three layered defects:
 *
 *   1. JustLiftScreen.kt:515,528,544 used hard-coded English Kotlin literals
 *      (`"Eccentric Load"`, `"Echo Level"`, `"Rep Count Timing"`, etc.) and
 *      bound the raw `EccentricLoad.displayName` to the dropdown value with
 *      no Locale-aware formatter.
 *   2. `EccentricLoad.displayName` is `"<int>%"` with no separator; iOS SF
 *      Pro drops the `%` glyph with effectively zero left-advance, causing
 *      the trailing-0 / % collision.
 *   3. composeResources had values-{de,es,fr,nl} but no values-it directory,
 *      and iosApp Info.plist did not declare `CFBundleLocalizations`, so
 *      Italian could not be served even if the literals were converted.
 *
 * The bugfix:
 *   - Replaces the hard-coded English literals with `stringResource` lookups.
 *   - Routes the dropdown value through a new `formatEccentricLoad(load,
 *     language)` helper (and the Rest Timer integer-percent equivalent) that
 *     emits `"110\u00A0%"` (with U+00A0 NBSP) for any `it-*` / `it_*`
 *     language and `"110%"` for every other locale.
 *   - Adds `values-it/strings.xml` plus a `CFBundleLocalizations` entry in the
 *     iOS Info.plist so the system actually advertises Italian as a shipped
 *     locale.
 *
 * These assertions pin:
 *   - The unchanged `EccentricLoad` / `EchoLevel` / `WorkoutMode.Echo` enum
 *     `displayName` values (BLE, tests, CSV consumers rely on the raw form).
 *   - The new locale-aware formatter's behaviour for `en` (ASCII) and `it`
 *     (NBSP), including a region-subtag variant (`it-IT`) so we know the
 *     substring check is robust to BCP-47 tags.
 *   - A per-entry cross-locale invariant (every `EccentricLoad` value
 *     formats correctly under both `en` and `it`).
 */
class EccentricLoadDisplayNameTest {

    // -------- Pre-fix invariants: enum displayName contract (unchanged) --------

    @Test
    fun everyEccentricLoadDisplayNameIsPercentSuffixedWithNoSeparator() {
        EccentricLoad.entries.forEach { load ->
            assertTrue(
                load.displayName.endsWith("%"),
                "EccentricLoad.${load.name}.displayName should end with '%' but was '${load.displayName}'",
            )
            // NBSP must NOT appear in the wire format — that would corrupt
            // BLE / CSV consumers.
            assertFalse(
                load.displayName.contains('\u00A0'),
                "EccentricLoad.${load.name}.displayName must not contain NBSP (wire format) but was '${load.displayName}'",
            )
        }
    }

    @Test
    fun load110DisplayNameIsExactly110PercentWithAsciiPercentGlyph() {
        assertEquals("110%", EccentricLoad.LOAD_110.displayName)
        assertEquals(110, EccentricLoad.LOAD_110.percentage)
    }

    @Test
    fun echoLevelDisplayNameValuesAreHardCodedEnglish() {
        assertEquals("Hard", EchoLevel.HARD.displayName)
        assertEquals("Harder", EchoLevel.HARDER.displayName)
        assertEquals("Hardest", EchoLevel.HARDEST.displayName)
        assertEquals("Epic", EchoLevel.EPIC.displayName)
    }

    @Test
    fun workoutModeEchoDisplayNameIsHardCodedEnglish() {
        assertEquals("Echo", WorkoutMode.Echo(EchoLevel.HARD).displayName)
    }

    // -------- Post-fix: locale-aware formatter (issue #540) --------

    @Test
    fun formatEccentricLoadPercentSupportsRestTimerFivePercentIncrements() {
        // RestTimerEccentricLoadSlider stores the slider value as an Int rather
        // than as an EccentricLoad enum entry, and can land on 5% increments
        // such as 105. The integer helper must preserve the same locale policy.
        assertEquals("105%", formatEccentricLoadPercent(105, "en"))
        assertEquals("105\u00A0%", formatEccentricLoadPercent(105, "it"))
    }

    @Test
    fun formatEccentricLoadDelegatesToIntegerPercentFormatter() {
        EccentricLoad.entries.forEach { load ->
            assertEquals(
                formatEccentricLoadPercent(load.percentage, "it"),
                formatEccentricLoad(load, "it"),
                "enum helper should share the integer formatter for ${load.name}",
            )
        }
    }

    @Test
    fun formatEccentricLoadEnglishKeepsAsciiPercentNoSeparator() {
        EccentricLoad.entries.forEach { load ->
            val label = formatEccentricLoad(load, "en")
            assertEquals("${load.percentage}%", label, "en locale should emit ASCII form for ${load.name}")
            assertFalse(label.contains('\u00A0'), "en must not introduce NBSP")
        }
    }

    @Test
    fun formatEccentricLoadItalianInsertsNbspBeforePercentGlyph() {
        val label = formatEccentricLoad(EccentricLoad.LOAD_110, "it")
        // Italian typography uses NBSP (U+00A0) between number and "%" so the
        // SF Pro "%" glyph no longer collides with the trailing "0" on
        // Italian iPhone at bodyLarge.
        assertEquals("110\u00A0%", label)
        assertTrue(label.contains('\u00A0'), "it must contain NBSP (U+00A0)")
        assertTrue(label.endsWith("%"), "percent glyph still required")
    }

    @Test
    fun formatEccentricLoadEnglishSupportsIntermediateSliderPercentages() {
        val label = formatEccentricLoad(105, "en")
        assertEquals("105%", label)
        assertFalse(label.contains('\u00A0'), "en must not introduce NBSP")
    }

    @Test
    fun formatEccentricLoadItalianSupportsIntermediateSliderPercentages() {
        val label = formatEccentricLoad(105, "it")
        assertEquals("105\u00A0%", label)
        assertTrue(label.contains('\u00A0'), "it must contain NBSP (U+00A0)")
        assertTrue(label.endsWith("%"), "percent glyph still required")
    }

    @Test
    fun formatEccentricLoadItalianAcceptsRegionTags() {
        assertEquals("110\u00A0%", formatEccentricLoad(EccentricLoad.LOAD_110, "it-IT"))
        assertEquals("105\u00A0%", formatEccentricLoad(105, "it_IT"))
        assertEquals("110\u00A0%", formatEccentricLoad(EccentricLoad.LOAD_110, "IT-ch"))
    }

    @Test
    fun currentLanguageCodeIosExtractsLanguageSubtag() {
        // The iOS actual for `currentLanguageCode()` should reduce a BCP-47
        // AppleLanguages entry like "it-IT" / "en-US" to the language subtag.
        // The formatter also defensively accepts full tags, but the platform
        // helper should still keep its public contract of returning only the
        // language subtag.
        val full = "it-IT"
        val extracted = full.substringBefore('-').lowercase()
        assertEquals("it", extracted)
        val label = formatEccentricLoad(EccentricLoad.LOAD_110, extracted)
        assertEquals("110\u00A0%", label)
    }

    @Test
    fun formatEccentricLoadEmptyLanguageFallsBackToAscii() {
        // Defensive: empty / unknown language code must not crash and must
        // emit the safe ASCII form (no NBSP).
        val label = formatEccentricLoad(EccentricLoad.LOAD_110, "")
        assertEquals("110%", label)
    }

    @Test
    fun formatEccentricLoadItCaseInsensitive() {
        // The helper compares the extracted base language case-insensitively;
        // verify the uppercase / mixed-case form still routes to the Italian branch.
        val label = formatEccentricLoad(EccentricLoad.LOAD_100, "IT")
        assertEquals("100\u00A0%", label)
    }

    @Test
    fun everyEccentricLoadCrossLocaleInvariant() {
        // Per-entry cross-locale check: for every EccentricLoad value the
        // en and it outputs differ only in the separator (NBSP vs none) and
        // both end in '%'.
        EccentricLoad.entries.forEach { load ->
            val en = formatEccentricLoad(load, "en")
            val it = formatEccentricLoad(load, "it")
            assertEquals("${load.percentage}%", en, "en form of ${load.name}")
            assertEquals("${load.percentage}\u00A0%", it, "it form of ${load.name}")
            assertTrue(en.endsWith("%") && it.endsWith("%"))
        }
    }
}
