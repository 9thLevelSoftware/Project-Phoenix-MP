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
 * This helper is the minimal product fix:
 *   - `surface` family comes from `fallback` (the brand-controlled static
 *     `DarkColorScheme` — Slate950/900/800/700). Chrome stays brand-dark.
 *   - `primary` / `secondary` / `tertiary` / `error` / outline* / surfaceVariant /
 *     `background` come from `dynamic`. The wallpaper hue still flows through
 *     toggles, sliders, badges, error states, etc.
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
)