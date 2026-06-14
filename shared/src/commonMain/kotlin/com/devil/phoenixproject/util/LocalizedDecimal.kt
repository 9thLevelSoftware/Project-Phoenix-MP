package com.devil.phoenixproject.util

/**
 * Locale-tolerant decimal parser for free-form numeric input fields.
 *
 * On locales whose `decimalPad` keyboard does not provide a `.`
 * (Italian `it-IT`, French `fr-FR`, most of `de-DE`, `es-ES`, etc.) the
 * user types a `,` (comma), a narrow no-break space, or an apostrophe
 * as the decimal/group separator. A naive `toFloatOrNull()` on the raw
 * text drops those characters silently and produces wildly wrong values
 * (e.g. "20,5" -> 205.0). This helper normalizes the input first.
 *
 * Disambiguation rules (in order):
 *  1. Apostrophes and non-breaking spaces (U+00A0, U+202F) are always
 *     group separators and are stripped.
 *  2. If both `,` and `.` are present, the LAST separator is the
 *     decimal separator. The other is treated as a group separator
 *     and stripped. (en-US "1,000.5" -> 1000.5; it-IT "1.000,5" ->
 *     1000.5.)
 *  3. If only `,` is present, it is the decimal separator IFF the
 *     digits after the last `,` are not exactly 3. A trailing "###"
 *     strongly suggests an en-US thousands separator (e.g. "1,000" ->
 *     1000, not 1.0). This keeps the original pre-fix behaviour of
 *     dropping commas (which the iOS picker's en-US users relied on)
 *     while also accepting it-IT/fi-IT/fi-FR/fi-DE decimal-comma.
 *  4. If only `.` is present, it is always the decimal separator.
 *
 * The fix scope is the iOS `CompactNumberPicker.commitEdit()` path
 * (issue #539). The helper lives in `commonMain` so a future sweep
 * across `BulkWeightAdjustDialog`, `OneRepMaxInputScreen`,
 * `ExerciseEditDialog`, `AssessmentWizardScreen`, and
 * `SettingsTab.bodyWeightInput` can adopt it without duplicating logic.
 */
fun parseLocalizedDecimal(text: String): Float? {
    if (text.isBlank()) return null

    var cleaned = text
        .replace('\u00A0', ' ')  // non-breaking space
        .replace('\u202F', ' ')  // narrow no-break space (fr-FR, it-IT on some iOS versions)
        .replace("'", "")        // apostrophe group separator (de-CH, it-CH, fr-CH)
        .filter { !it.isWhitespace() }

    // Strip any trailing non-digit characters (units like "kg", "sec", "lb",
    // and sentence punctuation like "kg." or "sec,") before classifying
    // separators. The iOS decimal-pad never emits these, but paste paths
    // (e.g. "20,5 kg.") can, and treating the trailing "." as a decimal
    // separator would mangle the value.
    while (cleaned.isNotEmpty() && !cleaned.last().isDigit()) {
        cleaned = cleaned.dropLast(1)
    }

    val hasComma = cleaned.contains(',')
    val hasDot = cleaned.contains('.')

    if (hasComma && hasDot) {
        // Both separators present. The last one is the decimal separator.
        cleaned = if (cleaned.lastIndexOf('.') > cleaned.lastIndexOf(',')) {
            // en-US-style "1,000.5": dot is decimal, comma is group.
            cleaned.replace(",", "")
        } else {
            // de-DE/it-IT-style "1.000,5": comma is decimal, dot is group.
            cleaned.replace(".", "").replace(',', '.')
        }
    } else if (hasComma) {
        // Comma only. Decide whether it's a decimal or a thousands separator.
        // Drop any trailing non-digit, non-comma characters (e.g. " kg", " sec",
        // "lb") before classifying so the tail reflects the numeric portion.
        // "1,5" -> decimal; "10,75" -> decimal; "1,000" -> thousands; "1,500"
        // -> thousands; "1,000 kg" -> thousands. This preserves the pre-fix
        // en-US behaviour (which silently dropped commas) while also accepting
        // it-IT / fr-FR / de-DE decimal-comma input.
        val lastComma = cleaned.lastIndexOf(',')
        val numericTail = cleaned.substring(lastComma + 1).takeWhile { it.isDigit() }
        cleaned = if (numericTail.length == 3) {
            // Likely an en-US thousands separator. Strip the comma.
            cleaned.replace(",", "")
        } else {
            cleaned.replace(',', '.')
        }
    }
    // Else: only `.` present (or neither) — keep as-is.

    val sanitized = cleaned.filter { it.isDigit() || it == '.' || it == '-' }
    return sanitized.toFloatOrNull()
}
