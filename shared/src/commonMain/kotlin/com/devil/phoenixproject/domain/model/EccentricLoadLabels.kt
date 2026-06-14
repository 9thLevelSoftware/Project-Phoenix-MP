package com.devil.phoenixproject.domain.model

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.echo_level_epic
import vitruvianprojectphoenix.shared.generated.resources.echo_level_hard
import vitruvianprojectphoenix.shared.generated.resources.echo_level_harder
import vitruvianprojectphoenix.shared.generated.resources.echo_level_hardest

/**
 * Locale-aware label helpers for eccentric-load percentage UI, including the
 * Echo-mode block in [com.devil.phoenixproject.presentation.screen.JustLiftScreen]
 * and cycle progression settings.
 *
 * Background (issue #540): on Italian iPhone the system font (SF Pro) renders
 * the literal sequence `110%` with effectively zero left-advance on the `%`
 * glyph, so the EccentricLoad dropdown value reads as `11U%` / `11 U%`.
 * Italian typography inserts a hard non-breaking space (U+00A0) between the
 * digits and the percent sign — `110 %` — and iOS SF Pro renders that form
 * without a glyph collision. This file emits that form for Italian language
 * tags and the back-compat ASCII `110%` form for every other locale.
 *
 * The pure formatter [formatEccentricLoad] is `internal` so the unit test in
 * `commonTest` can exercise the per-locale behaviour without needing a
 * Compose runtime or a platform-specific locale implementation. The
 * `@Composable` [eccentricLoadLabel] wrapper uses the expect/actual
 * [currentLanguageCode] helper to get the active app locale without pulling
 * in any Compose-Multiplatform-specific locale API.
 */

/**
 * Backwards-compatible formatter for [EccentricLoad] enum values.
 */
internal fun formatEccentricLoad(load: EccentricLoad, language: String): String =
    formatEccentricLoad(load.percentage, language)

/**
 * Non-Composable pure formatter for raw eccentric-load percentages. Unlike
 * [EccentricLoad], sliders can land on intermediate values such as `105`, so
 * callers must not coerce through the enum and accidentally relabel the
 * selected percentage.
 *
 * Behaviour:
 *  * For Italian this returns the digits followed by U+00A0 NBSP and `%` —
 *    e.g. `"110\u00A0%"`. This is the canonical Italian typographic form and
 *    resolves the iOS SF Pro `%`-glyph collision.
 *  * For every other locale this returns the digits followed by `%` — e.g.
 *    `"110%"`.
 *
 * @param percentage the eccentric-load percentage value to render.
 * @param language the language code or BCP-47 tag of the active locale, e.g.
 *                 `"en"`, `"it"`, `"it-IT"`, `"de"`. Pass `""` or a non-Italian
 *                 value to get the ASCII form.
 */
internal fun formatEccentricLoad(percentage: Int, language: String): String = if (language.isItalianLanguage()) {
    "$percentage\u00A0%"
} else {
    "$percentage%"
}

private fun String.isItalianLanguage(): Boolean =
    substringBefore('-').substringBefore('_').equals("it", ignoreCase = true)

/**
 * Composable wrapper that resolves the active language code via
 * [currentLanguageCode] and delegates to [formatEccentricLoad]. No
 * Compose-Multiplatform-specific locale API is required, so this helper
 * compiles cleanly for both the Android and iOS targets.
 */
@Composable
fun eccentricLoadLabel(load: EccentricLoad): String = formatEccentricLoad(load, currentLanguageCode())

@Composable
fun eccentricLoadLabel(percentage: Int): String = formatEccentricLoad(percentage, currentLanguageCode())

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
