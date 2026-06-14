package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.experimental.ExperimentalNativeApi

/**
 * Tests for [parseLocalizedDecimal], the locale-tolerant parser used by the
 * iOS `CompactNumberPicker.commitEdit()` path (issue #539) and available to
 * other free-form numeric input fields.
 *
 * The original bug: on an Italian iPhone, `UIKeyboardType.decimalPad` has only
 * a `,` (no `.`), so typing "20,5" was silently rewritten to "205" by the
 * locale-blind filter and then snapped to the picker max via `coerceIn(range)`.
 */
@OptIn(ExperimentalNativeApi::class)
class LocalizedDecimalTest {

    @Test
    fun parses_english_dot_decimal() {
        assertEquals(20.5f, parseLocalizedDecimal("20.5"))
        assertEquals(0.5f, parseLocalizedDecimal(".5"))
        assertEquals(110f, parseLocalizedDecimal("110"))
    }

    @Test
    fun parses_italian_comma_decimal() {
        // Primary repro from issue #539: "20,5" must resolve to 20.5, not 205.
        assertEquals(20.5f, parseLocalizedDecimal("20,5"))
        assertEquals(0.5f, parseLocalizedDecimal(",5"))
        assertEquals(110f, parseLocalizedDecimal("110,0"))
    }

    @Test
    fun parses_french_narrow_non_breaking_space_group_separator() {
        // "1 000,5" with U+202F (narrow NBSP) is the fr-FR rendering of 1000.5.
        assertEquals(1000.5f, parseLocalizedDecimal("1\u202F000,5"))
        // "1 000,5" with U+00A0 (NBSP) must work too.
        assertEquals(1000.5f, parseLocalizedDecimal("1\u00A0000,5"))
    }

    @Test
    fun parses_swiss_apostrophe_group_separator() {
        // "1'000.5" is the de-CH / fr-CH rendering of 1000.5.
        assertEquals(1000.5f, parseLocalizedDecimal("1'000.5"))
    }

    @Test
    fun parses_negative_values() {
        assertEquals(-5f, parseLocalizedDecimal("-5"))
        assertEquals(-5.5f, parseLocalizedDecimal("-5,5"))
    }

    @Test
    fun strips_unrelated_garbage() {
        // Letters are dropped, leaving the surviving digits/dot.
        assertEquals(20.5f, parseLocalizedDecimal("20,5 kg"))
        // Plus sign is dropped, leaving the digits.
        assertEquals(5f, parseLocalizedDecimal("+5"))
    }

    @Test
    fun rejects_blank_or_garbage_input() {
        assertNull(parseLocalizedDecimal(""))
        assertNull(parseLocalizedDecimal("   "))
        assertNull(parseLocalizedDecimal("abc"))
        assertNull(parseLocalizedDecimal("--"))
    }

    @Test
    fun regression_issue539_repro_yields_20_5_not_205() {
        // Mirror the exact RCA failure mode: input "20,5" must NOT become 205.
        val parsed = parseLocalizedDecimal("20,5")
        assertEquals(20.5f, parsed)
        // And it must be inside the picker range (1f..110f), so no clamp snap.
        assert(parsed != null && parsed >= 1f && parsed <= 110f)
    }

    @Test
    fun regression_en_us_thousands_separator_still_parses() {
        // Regression for Codex review comment: en-US "1,000" must still parse
        // as 1000, not 1.0. (iOS decimal-pad does not emit a comma as group,
        // but the user can paste or use a hardware keyboard. The pre-fix
        // filter dropped the comma entirely, so "1,000" parsed as 1000.
        // Preserve that.)
        assertEquals(1000f, parseLocalizedDecimal("1,000"))
        assertEquals(1500f, parseLocalizedDecimal("1,500"))

        // "1,000.5" (en-US) -> last separator is '.', so dot is decimal and
        // comma is group. Result: 1000.5.
        assertEquals(1000.5f, parseLocalizedDecimal("1,000.5"))
    }

    @Test
    fun en_us_multiple_thousands_separators() {
        // Kilo review edge case: "1,000,000" (multiple en-US commas). The
        // tail after the LAST comma is "000" (3 digits) -> thousands ->
        // strip all commas -> 1000000. (Multiple consecutive thousands
        // separators are unambiguous; only an en-US locale would emit this.)
        assertEquals(1000000f, parseLocalizedDecimal("1,000,000"))
    }

    @Test
    fun trailing_unit_suffix_after_comma_thousands() {
        // Codex review edge case: "1,000 kg" (paste with unit). The numeric
        // tail after the last comma is "000" once we drop the suffix, so it
        // is classified as thousands -> "1000 kg" -> filter digits -> 1000.
        assertEquals(1000f, parseLocalizedDecimal("1,000 kg"))
        assertEquals(1000f, parseLocalizedDecimal("1,000sec"))
    }

    @Test
    fun trailing_punctuation_after_decimal() {
        // Codex review edge case: "20,5 kg." (paste with sentence punctuation).
        // The trailing "." after the unit must not be treated as a decimal
        // separator. After stripping trailing non-digits, "20,5 kg" -> numeric
        // tail after last comma is "5" (1 digit) -> decimal -> 20.5.
        assertEquals(20.5f, parseLocalizedDecimal("20,5 kg."))
        assertEquals(20.5f, parseLocalizedDecimal("20,5 sec."))
        // Same fix for an en-US pastes with a sentence period.
        assertEquals(20.5f, parseLocalizedDecimal("20.5 kg."))
    }

    @Test
    fun disambiguates_european_format_with_both_separators() {
        // Gemini review edge case: "1.000,5" (it-IT / de-DE) has both.
        // The last separator is ',' -> comma is decimal, dot is group.
        // Result: 1000.5, NOT 1.000.5 (which would be unparseable).
        assertEquals(1000.5f, parseLocalizedDecimal("1.000,5"))
        // And "1.200,50" -> 1200.50.
        assertEquals(1200.50f, parseLocalizedDecimal("1.200,50"))
    }

    @Test
    fun comma_only_short_tails_are_decimal() {
        // Comma-only with a short tail: 1 or 2 digits after the last comma.
        // These are unambiguously decimal-comma in it-IT/fr-FR/de-DE.
        assertEquals(1.5f, parseLocalizedDecimal("1,5"))
        assertEquals(10.75f, parseLocalizedDecimal("10,75"))
        assertEquals(20.5f, parseLocalizedDecimal("20,5"))
    }
}
