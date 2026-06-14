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
 * language and the back-compat ASCII `110%` form for every other locale.
 *
 * The pure formatter [formatEccentricLoad] is `internal` so the unit test in
 * `commonTest` can exercise the per-locale behaviour without needing a
 * Compose runtime or a platform-specific locale implementation. The
 * `@Composable` [eccentricLoadLabel] wrapper uses the expect/actual
 * [currentLanguageCode] helper to get the active app locale without pulling
 * in any Compose-Multiplatform-specific locale API.
 */

/**
 * Non-Composable pure formatter used by the `@Composable` [eccentricLoadLabel]
 * wrapper. Exposed as `internal` for unit testing.
 *
 * Behaviour:
 *  * For Italian (the language code `it`, case-insensitive) this returns the
 *    digits followed by U+00A0 NBSP and `%` — e.g. `"110\u00A0%"`. This is
 *    the canonical Italian typographic form and resolves the iOS SF Pro
 *    `%`-glyph collision.
 *  * For every other locale this returns the digits followed by `%` — e.g.
 *    `"110%"` (byte-identical to the legacy `EccentricLoad.displayName` so
 *    the existing English UI is unchanged).
 *
 * @param load     the [EccentricLoad] enum entry whose
 *                 [EccentricLoad.percentage] is formatted.
 * @param language the lowercased language code of the active locale, e.g.
 *                 `"en"`, `"it"`, `"de"`. Pass `""` or a non-`"it"` value to
 *                 get the ASCII form.
 */
internal fun formatEccentricLoadPercent(percent: Int, language: String): String {
    return if (language.equals("it", ignoreCase = true)) {
        "$percent\u00A0%"
    } else {
        "$percent%"
    }
}

internal fun formatEccentricLoad(load: EccentricLoad, language: String): String {
    return formatEccentricLoadPercent(load.percentage, language)
}

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
fun eccentricLoadLabel(load: EccentricLoad): String {
    return formatEccentricLoad(load, currentLanguageCode())
}

/**
 * Composable wrapper for UI surfaces that store eccentric load as an integer
 * percentage rather than as an [EccentricLoad] enum entry.
 */
@Composable
fun eccentricLoadPercentLabel(percent: Int): String {
@Composable
fun eccentricLoadPercentLabel(percent: Int): String {
    val language = currentLanguageCode()
    return formatEccentricLoadPercent(percent, language)
}
    return androidx.compose.runtime.remember(percent, language) { formatEccentricLoadPercent(percent, language) }
}

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
