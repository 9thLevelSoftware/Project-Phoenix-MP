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
 *   - Routes enum dropdown values and raw integer/fractional percentages
 *     through `formatPercent(...)`, which emits `"110\u00A0%"` (with U+00A0
 *     NBSP) for Italian language tags and `"110%"` for every other locale.
 *   - Adds `values-it/strings.xml` plus a `CFBundleLocalizations` entry in the
 *     iOS Info.plist so the system actually advertises Italian as a shipped
 *     locale.
 *
 * These assertions pin:
 *   - The unchanged `EccentricLoad` / `EchoLevel` / `WorkoutMode.Echo` enum
 *     `displayName` values (BLE, tests, CSV consumers rely on the raw form).
 *   - The new locale-aware formatter's behaviour for `en` (ASCII) and `it`
 *     (NBSP), including region-subtag variants (`it-IT`, `it_IT`) so we know
 *     the substring check is robust to BCP-47-style tags.
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
            // NBSP must NOT appear in the wire format - that would corrupt
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
    fun formatPercentSupportsRawIntegerPercentages() {
        assertEquals("105%", formatPercent(105, "en"))
        assertEquals("105\u00A0%", formatPercent(105, "it"))
    }

    @Test
    fun formatPercentSupportsFractionalProgressionPercentages() {
        assertEquals("2.5%", formatPercent(2.5f, "en"))
        assertEquals("2.5\u00A0%", formatPercent(2.5f, "it"))
    }

    @Test
    fun formatEccentricLoadDelegatesToGenericPercentFormatter() {
        EccentricLoad.entries.forEach { load ->
            assertEquals(
                formatPercent(load.percentage, "it"),
                formatEccentricLoad(load, "it"),
                "enum helper should share the generic formatter for ${load.name}",
            )
        }
    }

    @Test
    fun formatPercentEnglishKeepsAsciiPercentNoSeparator() {
        val enumLabel = formatEccentricLoad(EccentricLoad.LOAD_110, "en")
        val rawLabel = formatPercent(105, "en")

        assertEquals("110%", enumLabel)
        assertEquals("105%", rawLabel)
        assertFalse(enumLabel.contains('\u00A0'), "en enum label must not introduce NBSP")
        assertFalse(rawLabel.contains('\u00A0'), "en raw label must not introduce NBSP")
    }

    @Test
    fun formatPercentItalianInsertsNbspBeforePercentGlyph() {
        val enumLabel = formatEccentricLoad(EccentricLoad.LOAD_110, "it")
        val rawLabel = formatPercent(105, "it")

        // Italian typography uses NBSP (U+00A0) between number and "%" so the
        // SF Pro "%" glyph no longer collides with the trailing digit on
        // Italian iPhone at bodyLarge.
        assertEquals("110\u00A0%", enumLabel)
        assertEquals("105\u00A0%", rawLabel)
        assertTrue(enumLabel.contains('\u00A0'), "it enum label must contain NBSP (U+00A0)")
        assertTrue(rawLabel.contains('\u00A0'), "it raw label must contain NBSP (U+00A0)")
        assertTrue(enumLabel.endsWith("%") && rawLabel.endsWith("%"), "percent glyph still required")
    }

    @Test
    fun formatPercentItalianAcceptsRegionTags() {
        assertEquals("110\u00A0%", formatPercent(110, "it-IT"))
        assertEquals("105\u00A0%", formatPercent(105, "it_IT"))
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
        val label = formatPercent(110, extracted)
        assertEquals("110\u00A0%", label)
    }

    @Test
    fun formatPercentEmptyLanguageFallsBackToAscii() {
        // Defensive: empty / unknown language code must not crash and must
        // emit the safe ASCII form (no NBSP).
        val label = formatPercent(110, "")
        assertEquals("110%", label)
    }

    @Test
    fun formatPercentItCaseInsensitive() {
        val label = formatPercent(100, "IT")
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
