package com.devil.phoenixproject.ui.theme

import androidx.compose.material3.ColorScheme

/**
 * Clamp a wallpaper-derived dynamic dark [ColorScheme] so the chrome / card surface
 * family can never resolve to high-luminance values while keeping the wallpaper hue
 * alive on accents, toggles, and selection chips.
 *
 * Background — see RCA for issue #640:
 * On Android 12+, `dynamicDarkColorScheme(context)` returns a tonal palette derived from
 * the user's wallpaper. For some wallpapers the `surface` / `surfaceContainerHigh` /
 * `surfaceContainerHighest` roles resolve to high-luminance values while
 * `surfaceContainerLowest` stays dark. The Routines screen reads these directly:
 *   - top app bar = `MaterialTheme.colorScheme.surface`
 *   - bottom nav   = `MaterialTheme.colorScheme.surfaceContainerHigh`
 *   - expanded RoutineCard = `MaterialTheme.colorScheme.surfaceContainerHighest`
 *     at elevation 8.dp (and the same color at elevation 2.dp when collapsed)
 * which produces a half-light / half-dark screen under Dark + Material You.
 *
 * This helper is the minimal, **conservative** product fix. It clamps **the entire
 * surface family** (incl. `surfaceVariant` / `onSurfaceVariant` / `surfaceDim` /
 * `surfaceBright` / `surfaceTint` / `background` / `onBackground` /
 * `inverseSurface` / `inverseOnSurface`) to the brand-controlled static
 * `DarkColorScheme`, because those
 * tokens are co-sampled by the same chrome surfaces that the reporter saw flip light.
 * `inverseSurface` / `inverseOnSurface` in particular are used by Material 3
 * components like `Snackbar` for their container / content colors; if we clamp
 * `surface` to dark but leave the dynamic `inverseSurface` in place, a Snackbar on a
 * wallpaper with a light `surface` would render dark-on-dark (Snackbar container
 * resolves to dynamic `inverseSurface` which is dark when the dynamic `surface` is
 * light, while the underlying screen is dark from the clamp → no contrast).
 * `surfaceTint` is also clamped because Material 3 applies it to elevated surface
 * containers; a light or saturated wallpaper tint can otherwise reintroduce the
 * same color drift on elevated bars/cards after the surface roles are clamped.
 * Only the wallpaper-derived accents — primary, secondary, tertiary, error,
 * outline, outlineVariant — are preserved, so the wallpaper hue still flows
 * through toggles, sliders, badges, and error states.
 *
 * Why not pass any of those tokens through from `dynamic`? They are all part of the
 * same wallpaper-miscalibrated surface family. The conservative clamp keeps the
 * chrome visually consistent across wallpapers; the wallpaper hue stays alive on
 * accent colors that are explicitly user-interactive (toggles, selection chips,
 * error states) instead of on ambient background tints that should stay
 * brand-controlled.
 *
 * Only the dark branch of `VitruvianTheme` invokes this helper; light + Material You
 * is unchanged. iOS never invokes it because `platformDynamicColorScheme` returns
 * `null` on iOS and the `DarkColorScheme` fallback is used directly.
 *
 * @param dynamic the `dynamicDarkColorScheme(context)` result; expected non-null
 * @param fallback the static `DarkColorScheme` to clamp surfaces against
 * @return a fresh `ColorScheme` with the surface family from `fallback` and accents
 *         from `dynamic`. Returns a fresh instance via `ColorScheme.copy(...)` so the
 *         caller's `dynamic` is never mutated.
 */
fun clampDynamicDarkScheme(
    dynamic: ColorScheme,
    fallback: ColorScheme,
): ColorScheme = dynamic.copy(
    // Surface family — brand-controlled dark Slate ramp from the static DarkColorScheme.
    surface = fallback.surface,
    onSurface = fallback.onSurface,
    surfaceVariant = fallback.surfaceVariant,
    onSurfaceVariant = fallback.onSurfaceVariant,
    surfaceDim = fallback.surfaceDim,
    surfaceBright = fallback.surfaceBright,
    surfaceContainerLowest = fallback.surfaceContainerLowest,
    surfaceContainerLow = fallback.surfaceContainerLow,
    surfaceContainer = fallback.surfaceContainer,
    surfaceContainerHigh = fallback.surfaceContainerHigh,
    surfaceContainerHighest = fallback.surfaceContainerHighest,
    background = fallback.background,
    onBackground = fallback.onBackground,
    // surfaceTint: Material 3 applies this tint to elevated surface containers.
    // Keep it on the static dark ramp so elevated bars/cards cannot drift away
    // from the clamped surface family under bright/saturated wallpaper palettes.
    surfaceTint = fallback.surfaceTint,
    // inverseSurface / inverseOnSurface: Material 3 components such as Snackbar use
    // these for their container / content colors. Clamping them keeps Snackbar (and
    // any other inverse-surface component) readable under the wallpaper bug instead
    // of falling into the dark-on-dark trap. See Gemini Code Review on PR #642.
    inverseSurface = fallback.inverseSurface,
    inverseOnSurface = fallback.inverseOnSurface,
)