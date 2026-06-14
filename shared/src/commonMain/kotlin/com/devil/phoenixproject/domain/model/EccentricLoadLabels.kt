package com.devil.phoenixproject.domain.model

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.echo_level_epic
import vitruvianprojectphoenix.shared.generated.resources.echo_level_hard
import vitruvianprojectphoenix.shared.generated.resources.echo_level_harder
import vitruvianprojectphoenix.shared.generated.resources.echo_level_hardest

/**
 * Locale-aware label helpers for the Echo-mode UI block in
 * [com.devil.phoenixproject.presentation.screen.JustLiftScreen].
 *
 * Background (issue #540): on Italian iPhone the system font (SF Pro) renders
 * the literal sequence `110%` with effectively zero left-advance on the `%`
 * glyph, so the EccentricLoad dropdown value reads as `11U%` / `11 U%`.
 * Italian typography inserts a hard non-breaking space (U+00A0) between the
 * digits and the percent sign — `110 %` — and iOS SF Pro renders that form
 * without a glyph collision. This file emits that form for any `it-*`
 * language (including regional variants like `it-IT` / `it_IT`) and the
 * back-compat ASCII `110%` form for every other locale.
 *
 * The pure formatter [formatEccentricPercent] is `internal` so the unit test
 * in `commonTest` can exercise the per-locale behaviour without needing a
 * Compose runtime or a platform-specific locale implementation. The
 * `@Composable` [eccentricLoadLabel] / [eccentricPercentLabel] wrappers use the
 * expect/actual [currentLanguageCode] helper to get the active app locale
 * without pulling in any Compose-Multiplatform-specific locale API.
 */

/**
 * Non-Composable pure formatter used by the `@Composable` label wrappers.
 * Exposed as `internal` for unit testing.
 *
 * Behaviour:
 *  * For Italian (the base language code `it`, case-insensitive) this returns the
 *    digits followed by U+00A0 NBSP and `%` — e.g. `"110\u00A0%"`. This is
 *    the canonical Italian typographic form and resolves the iOS SF Pro
 *    `%`-glyph collision.
 *  * For every other locale this returns the digits followed by `%` — e.g.
 *    `"110%"` (byte-identical to the legacy `EccentricLoad.displayName` so
 *    the existing English UI is unchanged).
 *
 * @param percent  the eccentric load percentage to format for display.
 * @param language the language code or locale tag of the active locale, e.g.
 *                 `"en"`, `"it"`, `"it-IT"`, `"it_IT"`, `"de"`. Pass `""`
 *                 or a non-Italian value to get the ASCII form.
 */
internal fun formatEccentricPercent(percent: Int, language: String): String =
    if (language.substringBefore('-').substringBefore('_').equals("it", ignoreCase = true)) {
        "$percent\u00A0%"
    } else {
        "$percent%"
    }

/**
 * Formats an [EccentricLoad] enum entry while preserving the enum's raw
 * [EccentricLoad.displayName] contract for non-Italian locales.
 */
internal fun formatEccentricLoad(load: EccentricLoad, language: String): String = formatEccentricPercent(load.percentage, language)

/**
 * Composable wrapper that resolves the active language code via
 * [currentLanguageCode] and delegates to [formatEccentricLoad]. No
 * Compose-Multiplatform-specific locale API is required, so this helper
 * compiles cleanly for both the Android and iOS targets.
 *
 * Returns the locale-formatted percentage for the dropdown value cell in
 * [com.devil.phoenixproject.presentation.screen.JustLiftScreen] (line ~528)
 * and the matching dropdown item text (line ~544).
 */
@Composable
fun eccentricLoadLabel(load: EccentricLoad): String = formatEccentricLoad(load, currentLanguageCode())

/**
 * Composable wrapper for slider/readout surfaces that store eccentric load as
 * a raw integer percentage instead of an [EccentricLoad] enum entry.
 */
@Composable
fun eccentricPercentLabel(percent: Int): String = formatEccentricPercent(percent, currentLanguageCode())

/**
 * Returns the [EchoLevel] label via the [stringResource] lookup so the
 * FilterChip and SegmentedButton rows pick up the per-locale translation.
 *
 * Resource key convention: `echo_level_<lowercase name>` → `echo_level_hard`,
 * `echo_level_harder`, `echo_level_hardest`, `echo_level_epic`.
 */
@Composable
fun echoLevelLabel(level: EchoLevel): String = when (level) {
    EchoLevel.HARD -> stringResource(Res.string.echo_level_hard)
    EchoLevel.HARDER -> stringResource(Res.string.echo_level_harder)
    EchoLevel.HARDEST -> stringResource(Res.string.echo_level_hardest)
    EchoLevel.EPIC -> stringResource(Res.string.echo_level_epic)
}
