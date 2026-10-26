package com.devil.phoenixproject.domain.model



/**
 * Locale-aware label helpers for percentage UI, including the Echo-mode block
 * in [com.devil.phoenixproject.presentation.screen.JustLiftScreen], cycle
 * progression settings, rest-timer edits, and exercise configuration sliders.
 *
 * Background (issue #540): on Italian iPhone the system font (SF Pro) renders
 * the literal sequence `110%` with effectively zero left-advance on the `%`
 * glyph, so the EccentricLoad dropdown value reads as `11U%` / `11 U%`.
 * Italian typography inserts a hard non-breaking space (U+00A0) between the
 * digits and the percent sign - `110 %` - and iOS SF Pro renders that form
 * without a glyph collision. This file emits that form for Italian language
 * tags and the back-compat ASCII `110%` form for every other locale.
 *
 * The pure formatter [formatPercent] is `internal` so the unit test in
 * `commonTest` can exercise the per-locale behaviour without needing a
 * Compose runtime or a platform-specific locale implementation. The
 * UI wrappers that resolve resources are kept in the phone source set; this
 * file owns only the platform-neutral formatter used by shared tests.
 */

/**
 * Non-Composable pure formatter for integer and fractional percentage values.
 *
 * Behaviour:
 *  * For Italian this returns the number followed by U+00A0 NBSP and `%` -
 *    e.g. `"110\u00A0%"`. This is the canonical Italian typographic form and
 *    resolves the iOS SF Pro `%`-glyph collision.
 *  * For every other locale this returns the number followed by `%` - e.g.
 *    `"110%"`.
 *
 * @param value the percentage value to render.
 * @param language the language code or BCP-47 tag of the active locale, e.g.
 *                 `"en"`, `"it"`, `"it-IT"`, `"de"`. Pass `""` or a non-Italian
 *                 value to get the ASCII form.
 */
internal fun formatPercent(value: Number, language: String): String = if (language.isItalianLanguage()) {
    "$value\u00A0%"
} else {
    "$value%"
}

private fun String.isItalianLanguage(): Boolean = substringBefore('-').substringBefore('_').equals("it", ignoreCase = true)