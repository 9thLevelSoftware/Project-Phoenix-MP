# Phase 17: WCAG Accessibility - Research

**Researched:** 2026-02-27
**Domain:** Compose Multiplatform accessibility theming, WCAG AA 1.4.1 compliance, deuteranopia-safe color palettes
**Confidence:** HIGH

## Summary

This phase retrofits WCAG AA 1.4.1 compliance ("Use of Color") into the existing Vitruvian app by establishing two parallel systems: (1) an `AccessibilityColors` data class provided through a `LocalColorBlindMode` CompositionLocal that swaps the default red/green/yellow semantic colors for a deuteranopia-safe blue/orange/teal palette, and (2) always-on secondary visual signals (text labels for velocity zones, numeric asymmetry percentages for the balance bar) that ensure color-coded information is never conveyed by color alone.

The codebase has a well-established theme infrastructure (`VitruvianTheme` in `Theme.kt`, `ThemeMode` toggle pattern) and a proven `CompositionLocalProvider` pattern already in use for `LocalWindowSizeClass`. The settings system (`UserPreferences` -> `PreferencesManager` -> `SettingsManager`) follows a clean data-flow pattern for adding new toggles. The main technical work is: (a) defining `AccessibilityColors` with both standard and colorblind palettes, (b) wiring a `LocalColorBlindMode` boolean into the theme root, (c) auditing all composables that use hardcoded red/green/yellow colors and replacing them with `AccessibilityColors` lookups, and (d) adding text labels to velocity zone displays and ensuring the balance bar's numeric percentage is prominent.

**Primary recommendation:** Use `compositionLocalOf` (not `staticCompositionLocalOf`) for `LocalColorBlindMode` since users can toggle it at runtime, and define `AccessibilityColors` as an `@Immutable` data class provided via a separate `staticCompositionLocalOf` since the color set itself changes infrequently (only when the mode toggles).

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
- Deuteranopia-safe palette: blue/orange/teal replacing green/red
- Toggled via Settings -- not a system-wide replacement, user opt-in
- Palette applies globally when enabled (velocity zones, balance bar, readiness indicators, any future color-coded UI)
- Text labels ("Explosive", "Grind", etc.) displayed alongside color coding
- Labels visible regardless of color-blind mode (always-on secondary signal)
- Numeric asymmetry percentage displayed alongside the colored bar
- Visible regardless of color-blind mode
- LocalColorBlindMode CompositionLocal at theme root
- AccessibilityColors provided through theme for all composables
- All new composables in Phases 18-22 must consume these

### Claude's Discretion
- **Color palette scope**: Deuteranopia-only for v0.5.1 (roadmap specifies deuteranopia). Multi-type support (protanopia, tritanopia) can be a future enhancement
- **Exact color values**: Choose accessible blue/orange/teal shades that maintain sufficient contrast ratios (WCAG AA 4.5:1 for text, 3:1 for UI components)
- **Velocity zone label placement**: Inline text within or adjacent to zone indicators -- prioritize readability without adding visual clutter to the workout HUD
- **Balance bar percentage placement**: Numeric value beside the bar (not inside, to avoid overlap at extreme values)
- **Settings toggle placement**: Within existing Settings screen, grouped with display/appearance preferences
- **Settings default state**: Off by default (standard palette is the default experience)
- **System accessibility auto-detect**: Not required -- simple manual toggle is sufficient
- **Live preview**: Optional -- toggle can apply immediately without a dedicated preview mode
- **Icon/shape usage**: Text labels are the primary secondary signal; additional icons/shapes at Claude's discretion if they improve clarity without clutter
- **Existing indicator audit**: Sweep all composables using hardcoded green/red/yellow and retrofit to use AccessibilityColors

### Deferred Ideas (OUT OF SCOPE)
None -- discussion stayed within phase scope
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| BOARD-02 | All color-coded indicators (velocity zones, balance bar, readiness card) have secondary visual signals (icon, label, or pattern) for WCAG AA 1.4.1 compliance | CompositionLocal pattern for AccessibilityColors, text label patterns for velocity zones, numeric percentage for balance bar, audited list of 22+ files with hardcoded semantic colors |
</phase_requirements>

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Compose Multiplatform | 1.7.1 | `CompositionLocalProvider`, `@Immutable`, `compositionLocalOf` / `staticCompositionLocalOf` | Already in project; native theme extension pattern |
| Material 3 | (bundled) | `MaterialTheme` integration, color scheme tokens | Already the app's design system |
| multiplatform-settings (russhwolf) | (in project) | Persist color-blind mode preference | Already used for all user preferences |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Koin | 4.0.0 | DI for PreferencesManager, SettingsManager | Already wired; no new modules needed |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `compositionLocalOf` for mode boolean | `staticCompositionLocalOf` | Static is more performant but doesn't propagate recomposition on change; since user toggles this at runtime, dynamic `compositionLocalOf` is correct for the boolean |
| Custom `AccessibilityColors` class | Extending `MaterialTheme.colorScheme` | Material colorScheme has fixed slots; custom class is cleaner for semantic fitness colors that don't map to Material roles |
| Per-composable color parameter | CompositionLocal | Parameters require threading through many layers; CompositionLocal is implicit and matches existing `LocalWindowSizeClass` pattern |

## Architecture Patterns

### Recommended File Additions/Modifications
```
shared/src/commonMain/kotlin/com/devil/phoenixproject/
├── ui/theme/
│   ├── AccessibilityColors.kt          # NEW: @Immutable data class + CompositionLocals
│   └── Theme.kt                        # MODIFY: Wire CompositionLocalProvider into VitruvianTheme
├── domain/model/
│   └── UserPreferences.kt              # MODIFY: Add colorBlindModeEnabled field
├── data/preferences/
│   └── PreferencesManager.kt           # MODIFY: Add KEY + setter for color-blind mode
├── presentation/manager/
│   └── SettingsManager.kt              # MODIFY: Add colorBlindMode state flow + setter
├── presentation/screen/
│   ├── SettingsTab.kt                  # MODIFY: Add color-blind mode toggle row
│   ├── WorkoutHud.kt                   # MODIFY: Replace zoneColor(), add zone text labels
│   ├── SetSummaryCard.kt              # MODIFY: Replace zoneColor()
│   └── [other screens]                 # MODIFY: Replace hardcoded colors
├── presentation/components/
│   ├── BalanceBar.kt                   # MODIFY: Use AccessibilityColors, ensure numeric % visible
│   ├── RepQualityIndicator.kt          # MODIFY: Use AccessibilityColors
│   ├── BiomechanicsHistoryCard.kt      # MODIFY: Replace velocityZoneColor()
│   └── [other components]              # MODIFY: Replace hardcoded semantic colors
└── App.kt                              # MODIFY: Pass colorBlindMode to VitruvianTheme
```

### Pattern 1: AccessibilityColors CompositionLocal
**What:** An `@Immutable` data class holding all semantic accessibility colors (success, error, warning, velocity zones, asymmetry severity), provided via `staticCompositionLocalOf` and switched in the theme root based on a `compositionLocalOf<Boolean>` for the mode toggle.
**When to use:** Whenever a composable needs a color that conveys semantic meaning (good/bad, fast/slow, balanced/imbalanced).
**Example:**
```kotlin
// Source: Android official docs - Anatomy of a theme in Compose
// https://developer.android.com/develop/ui/compose/designsystems/anatomy

@Immutable
data class AccessibilityColors(
    // Semantic status colors
    val success: Color,
    val error: Color,
    val warning: Color,
    val neutral: Color,

    // Velocity zone colors (BiomechanicsVelocityZone)
    val zoneExplosive: Color,
    val zoneFast: Color,
    val zoneModerate: Color,
    val zoneSlow: Color,
    val zoneGrind: Color,

    // Asymmetry severity colors (BalanceBar)
    val asymmetryGood: Color,      // < 10%
    val asymmetryCaution: Color,   // 10-15%
    val asymmetryBad: Color,       // > 15%

    // Rep quality score colors
    val qualityExcellent: Color,
    val qualityGood: Color,
    val qualityFair: Color,
    val qualityBelowAverage: Color,
    val qualityPoor: Color,
)

val LocalColorBlindMode = compositionLocalOf { false }

val LocalAccessibilityColors = staticCompositionLocalOf {
    AccessibilityColors(/* standard palette defaults */)
}

// Convenience access object
object AccessibilityTheme {
    val colors: AccessibilityColors
        @Composable
        get() = LocalAccessibilityColors.current

    val isColorBlindMode: Boolean
        @Composable
        get() = LocalColorBlindMode.current
}
```

### Pattern 2: Theme Root Wiring
**What:** `VitruvianTheme` accepts a `colorBlindMode` parameter and provides both `LocalColorBlindMode` and `LocalAccessibilityColors` via `CompositionLocalProvider`.
**When to use:** At the single theme root call in `App.kt`.
**Example:**
```kotlin
@Composable
fun VitruvianTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    colorBlindMode: Boolean = false,
    content: @Composable () -> Unit
) {
    val useDarkColors = when (themeMode) { /* existing logic */ }
    val accessibilityColors = if (colorBlindMode) {
        ColorBlindPalette   // deuteranopia-safe colors
    } else {
        StandardPalette     // original colors
    }

    MaterialTheme(
        colorScheme = if (useDarkColors) DarkColorScheme else LightColorScheme,
        typography = Typography,
        shapes = ExpressiveShapes,
    ) {
        CompositionLocalProvider(
            LocalColorBlindMode provides colorBlindMode,
            LocalAccessibilityColors provides accessibilityColors,
            content = content
        )
    }
}
```

### Pattern 3: Consuming AccessibilityColors in Composables
**What:** Replace hardcoded `Color(0xFF...)` values with `AccessibilityTheme.colors.propertyName`.
**When to use:** Every composable that currently uses hardcoded semantic colors.
**Example:**
```kotlin
// BEFORE (WorkoutHud.kt):
private fun zoneColor(zone: BiomechanicsVelocityZone): Color = when (zone) {
    BiomechanicsVelocityZone.EXPLOSIVE -> Color(0xFFE53935)
    BiomechanicsVelocityZone.FAST -> Color(0xFFFF9800)
    // ...
}

// AFTER:
@Composable
fun zoneColor(zone: BiomechanicsVelocityZone): Color {
    val colors = AccessibilityTheme.colors
    return when (zone) {
        BiomechanicsVelocityZone.EXPLOSIVE -> colors.zoneExplosive
        BiomechanicsVelocityZone.FAST -> colors.zoneFast
        BiomechanicsVelocityZone.MODERATE -> colors.zoneModerate
        BiomechanicsVelocityZone.SLOW -> colors.zoneSlow
        BiomechanicsVelocityZone.GRIND -> colors.zoneGrind
    }
}
```

### Pattern 4: Velocity Zone Text Labels (Always-On Secondary Signal)
**What:** Display human-readable zone labels alongside color-coded zone indicators.
**When to use:** WorkoutHud velocity card, SetSummaryCard zone distribution, BiomechanicsHistoryCard.
**Example:**
```kotlin
// The zone label text is ALWAYS visible (not gated by color-blind mode)
// This is the WCAG 1.4.1 "secondary signal" requirement
StatColumn(
    label = "Zone",
    value = velocityZoneLabel(zone),  // "Explosive", "Fast", etc.
    color = zoneColor(zone)
)

// Helper (already exists in BiomechanicsHistoryCard, needs to be promoted to shared utility)
fun velocityZoneLabel(zone: BiomechanicsVelocityZone): String = when (zone) {
    BiomechanicsVelocityZone.EXPLOSIVE -> "Explosive"
    BiomechanicsVelocityZone.FAST -> "Fast"
    BiomechanicsVelocityZone.MODERATE -> "Moderate"
    BiomechanicsVelocityZone.SLOW -> "Slow"
    BiomechanicsVelocityZone.GRIND -> "Grind"
}
```

### Pattern 5: Settings Toggle (Following Existing Pattern)
**What:** Add a `colorBlindModeEnabled` boolean following the exact same pattern as `ledFeedbackEnabled`.
**When to use:** The toggle flow touches 4 files in sequence: `UserPreferences` -> `PreferencesManager` -> `SettingsManager` -> `SettingsTab`.
**Example flow:**
```
1. UserPreferences.kt:     val colorBlindModeEnabled: Boolean = false
2. PreferencesManager.kt:  KEY_COLOR_BLIND_MODE = "color_blind_mode_enabled"
                           suspend fun setColorBlindModeEnabled(enabled: Boolean)
3. SettingsManager.kt:     val colorBlindModeEnabled: StateFlow<Boolean>
                           fun setColorBlindModeEnabled(enabled: Boolean)
4. SettingsTab.kt:         Switch row in Display/Appearance section
5. App.kt:                 Collect state, pass to VitruvianTheme(colorBlindMode = ...)
```

### Anti-Patterns to Avoid
- **Hardcoded color values in composables:** Every semantic color must come from `AccessibilityColors`, never from inline `Color(0xFF...)`. This is the entire point of this phase.
- **Gating text labels behind color-blind mode:** WCAG 1.4.1 requires secondary signals ALWAYS be present, not only in color-blind mode. Text labels for velocity zones and numeric percentage for balance bar must be visible in both modes.
- **Using `staticCompositionLocalOf` for the mode boolean:** Since users can toggle this at runtime in Settings, the mode boolean must use `compositionLocalOf` (dynamic) to trigger recomposition. The `AccessibilityColors` object itself can use `staticCompositionLocalOf` since when the mode changes, the entire provider value changes (not internal properties).
- **Duplicating `zoneColor()` across files:** Currently `zoneColor()` is defined privately in WorkoutHud.kt, SetSummaryCard.kt, and `velocityZoneColor()` in BiomechanicsHistoryCard.kt -- three different copies with different color mappings. Consolidate to a single shared function in `AccessibilityColors.kt`.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Color-blind palette values | Custom math/color transforms | Research-backed deuteranopia palette (blue 0xFF2196F3, orange 0xFFFF9800, teal 0xFF009688) | Color theory for CVD requires ophthalmological research, not guesswork |
| Contrast ratio checking | Runtime contrast calculator | Pre-verified hex values against dark/light backgrounds using WCAG tools | Runtime checking adds complexity; verify at design time |
| Preference persistence | Custom file I/O | `russhwolf/multiplatform-settings` (already in project) | Already proven pattern for all settings |
| CompositionLocal boilerplate | Manual threading through parameters | Compose `CompositionLocalProvider` | Implicit propagation is the standard pattern; avoids parameter pollution |

**Key insight:** The accessibility infrastructure is purely a theme/presentation concern. No domain logic, database, or BLE changes are needed. The only domain touch point is adding one boolean field to `UserPreferences`.

## Common Pitfalls

### Pitfall 1: Forgetting to Update Both Dark and Light Mode Palettes
**What goes wrong:** Colors look good in dark mode but fail WCAG contrast in light mode (or vice versa).
**Why it happens:** Developers test only in their preferred mode.
**How to avoid:** Define separate color values in `StandardPalette` and `ColorBlindPalette` for light vs dark mode if needed, or choose colors with sufficient contrast against both backgrounds. The current signal colors (`SignalSuccess`, `SignalError`, `SignalWarning`) are mode-agnostic; the accessibility palette should follow the same pattern.
**Warning signs:** Blue text on dark background looks washed out; orange on light background has low contrast.

### Pitfall 2: Missing a Hardcoded Color During Audit
**What goes wrong:** Some composables still use green/red hardcoded values, making them inaccessible.
**Why it happens:** Large codebase with colors scattered across 22+ files.
**How to avoid:** Systematic audit using grep for `Color(0xFF4CAF50)`, `Color(0xFFF44336)`, `Color(0xFFE53935)`, `Color(0xFF22C55E)`, `Color(0xFFEF4444)`, `Color(0xFFFFC107)`, `Color(0xFFFDD835)`, `SignalSuccess`, `SignalError`, `SignalWarning`. After phase completion, grep should return ZERO hits in presentation layer for direct semantic color usage.
**Warning signs:** Any remaining `Color(0xFF...)` in presentation code that maps to red/green/yellow/amber.

### Pitfall 3: Breaking Existing Visual Design
**What goes wrong:** Standard (non-colorblind) mode looks different after refactoring because color values are subtly changed.
**Why it happens:** Copy-pasting colors into `StandardPalette` with slightly different hex values.
**How to avoid:** Extract EXACT existing hex values from current code into `StandardPalette`. The standard palette must produce identical visual output to the current hardcoded colors. Test by toggling -- standard mode should be pixel-identical to pre-phase state.
**Warning signs:** Visual regression in screenshots.

### Pitfall 4: Zone Color Mismatch Between Files
**What goes wrong:** WorkoutHud shows EXPLOSIVE as red, BiomechanicsHistoryCard shows it as cyan -- they use different mappings.
**Why it happens:** Three separate private `zoneColor()` functions exist with different color assignments.
**How to avoid:** This is a pre-existing inconsistency that MUST be resolved during this phase. The standard palette should establish one canonical color per zone. The BiomechanicsHistoryCard mapping (which is more VBT-standard with cyan for explosive, green for fast) appears more intentional than the WorkoutHud mapping.
**Warning signs:** Zone colors differ between live HUD and history view.

### Pitfall 5: BalanceBar Percentage Already Exists But Needs Review
**What goes wrong:** Assuming the balance bar lacks a numeric percentage when it already has one.
**Why it happens:** Not reading the existing code carefully.
**How to avoid:** The BalanceBar ALREADY displays `${asymmetryPercent.toInt()}%` centered on the bar (line 160-172 of BalanceBar.kt). The success criterion says "numeric asymmetry percentage alongside the colored bar" -- the current implementation shows it INSIDE the bar. CONTEXT.md clarifies "beside the bar (not inside)". This means relocating the text to be adjacent rather than centered.
**Warning signs:** Percentage text overlapping with the bar indicator at extreme asymmetry values.

## Code Examples

### Existing Color Usage Audit (Verified from Codebase)

Files requiring AccessibilityColors migration (confirmed via codebase grep):

**Velocity zone colors (3 DUPLICATE functions -- consolidate):**
- `WorkoutHud.kt:1049` -- `zoneColor()` with Red/Orange/Yellow/Blue/Gray mapping
- `SetSummaryCard.kt:628` -- `zoneColor()` with identical mapping
- `BiomechanicsHistoryCard.kt:62` -- `velocityZoneColor()` with DIFFERENT mapping (Red/Orange/Amber/Green/Cyan)

**Asymmetry/severity colors (hardcoded green/yellow/red):**
- `BalanceBar.kt:57-60` -- `Color(0xFF4CAF50)` green, `Color(0xFFFFC107)` yellow, `Color(0xFFF44336)` red
- `BalanceBar.kt:79` -- Alert border `Color(0xFFF44336)` red

**Rep quality colors (hardcoded gradient):**
- `RepQualityIndicator.kt:33-39` -- `scoreColor()` with bright green/green/yellow/orange/red

**Signal colors used in presentation layer:**
- `SafetyEventsCard.kt` -- uses `MaterialTheme.colorScheme.error` (safe -- Material token)
- `ProgressionSuggestion.kt` -- uses `Color(0xFF4CAF50)` and `Color(0xFFE53935)` directly
- Multiple other components use `SignalSuccess`/`SignalError`/`SignalWarning` from Color.kt

**Velocity zone label (already exists in one place):**
- `BiomechanicsHistoryCard.kt:87-94` -- `velocityZoneLabel()` function mapping zone to "Explosive"/"Fast"/etc.
- `WorkoutHud.kt:873` -- uses `zone.name` (enum name, all caps) instead of friendly label

### Deuteranopia-Safe Color Palette (Recommended Values)

Based on colorblind design research (blue/orange are the primary safe pair for all CVD types):

```kotlin
// Standard palette (preserves existing visuals exactly)
val StandardPalette = AccessibilityColors(
    success = Color(0xFF22C55E),       // Current SignalSuccess
    error = Color(0xFFEF4444),         // Current SignalError
    warning = Color(0xFFF59E0B),       // Current SignalWarning
    neutral = Color(0xFF9E9E9E),       // Gray

    // Velocity zones (reconciled -- use BiomechanicsHistoryCard mapping as canonical)
    zoneExplosive = Color(0xFF06B6D4), // Cyan
    zoneFast = Color(0xFF22C55E),      // Green
    zoneModerate = Color(0xFFF59E0B),  // Amber
    zoneSlow = Color(0xFFF97316),      // Orange
    zoneGrind = Color(0xFFEF4444),     // Red

    // Asymmetry severity
    asymmetryGood = Color(0xFF4CAF50),
    asymmetryCaution = Color(0xFFFFC107),
    asymmetryBad = Color(0xFFF44336),

    // Rep quality
    qualityExcellent = Color(0xFF00E676),
    qualityGood = Color(0xFF43A047),
    qualityFair = Color(0xFFFDD835),
    qualityBelowAverage = Color(0xFFFF9800),
    qualityPoor = Color(0xFFE53935),
)

// Deuteranopia-safe palette
val ColorBlindPalette = AccessibilityColors(
    success = Color(0xFF2196F3),       // Blue (replaces green)
    error = Color(0xFFFF9800),         // Orange (replaces red)
    warning = Color(0xFFFDD835),       // Yellow (unchanged -- visible to deuteranopes)
    neutral = Color(0xFF9E9E9E),       // Gray (unchanged)

    // Velocity zones (blue-orange-yellow scale)
    zoneExplosive = Color(0xFF1565C0), // Dark blue (high energy)
    zoneFast = Color(0xFF42A5F5),      // Medium blue
    zoneModerate = Color(0xFFFDD835),  // Yellow (unchanged)
    zoneSlow = Color(0xFFFF9800),      // Orange
    zoneGrind = Color(0xFFE65100),     // Deep orange (near failure)

    // Asymmetry severity (blue-yellow-orange)
    asymmetryGood = Color(0xFF2196F3),
    asymmetryCaution = Color(0xFFFDD835),
    asymmetryBad = Color(0xFFFF9800),

    // Rep quality (blue scale to orange scale)
    qualityExcellent = Color(0xFF1565C0),
    qualityGood = Color(0xFF42A5F5),
    qualityFair = Color(0xFFFDD835),
    qualityBelowAverage = Color(0xFFFF9800),
    qualityPoor = Color(0xFFE65100),
)
```

**Note:** These hex values are recommendations based on deuteranopia-safe design principles (blue/orange primary axis). Exact values should be verified against dark and light mode backgrounds using a WCAG contrast checker before finalizing. Confidence: MEDIUM -- values chosen from established colorblind-safe palettes but not contrast-verified against this specific app's background colors.

### Settings Toggle Row Example

```kotlin
// Following exact pattern of ledFeedbackEnabled toggle in SettingsTab
Row(
    modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = Spacing.small),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            "Color-blind Mode",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            "Deuteranopia-safe palette (blue/orange)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Switch(
        checked = colorBlindModeEnabled,
        onCheckedChange = onColorBlindModeChange
    )
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| System-level colorblind filters | App-level palette switching via CompositionLocal | Compose 1.0+ (2021) | More precise control, better UX than OS-level filters |
| Global color transforms at runtime | Pre-defined palette objects swapped in theme | Industry consensus 2023+ | No runtime computation, instant switching, testable |
| Color-only status indicators | WCAG 1.4.1 mandates non-color secondary signals | WCAG 2.0 (2008) | Text labels, icons, patterns alongside color |

**Deprecated/outdated:**
- Android `ColorMatrix` filter approach: Too coarse -- transforms ALL colors including brand colors, images, etc. Modern approach uses semantic color tokens.
- `isSystemAccessibility` detection: Unreliable across platforms and doesn't cover all CVD types. Manual toggle is the standard pattern.

## Open Questions

1. **Velocity zone color mapping inconsistency**
   - What we know: WorkoutHud/SetSummaryCard use a different mapping than BiomechanicsHistoryCard. The HUD maps EXPLOSIVE->Red, FAST->Orange; the history card maps EXPLOSIVE->Cyan, FAST->Green.
   - What's unclear: Which mapping is the intended canonical one.
   - Recommendation: Adopt the BiomechanicsHistoryCard mapping (cyan/green/amber/orange/red) as canonical since it follows standard VBT convention (fastest = coolest color, grind = hottest). Reconcile during this phase. This is a pre-existing inconsistency that should be resolved.

2. **Contrast verification for recommended color values**
   - What we know: The recommended hex values follow deuteranopia-safe principles.
   - What's unclear: Exact contrast ratios against this app's specific dark (Slate900/Slate950) and light (Slate50/White) backgrounds.
   - Recommendation: During implementation, verify each color pair with WebAIM contrast checker. Adjust specific values if any fall below 4.5:1 for text or 3:1 for UI components.

3. **Future phases consuming AccessibilityColors**
   - What we know: Success criteria #4 states "All new composables built in Phases 18-22 consume LocalColorBlindMode and AccessibilityColors from the theme root."
   - What's unclear: Whether Phase 17 needs to create placeholder color slots for features that don't exist yet (readiness card colors, ghost racing colors, etc.).
   - Recommendation: Include a few "reserved" semantic slots (e.g., `statusGreen`, `statusRed`, `statusYellow`) that future phases can use without modifying AccessibilityColors. This avoids future schema changes.

## Sources

### Primary (HIGH confidence)
- [Android official docs: Anatomy of a theme in Compose](https://developer.android.com/develop/ui/compose/designsystems/anatomy) -- CompositionLocal pattern for custom theme systems, `@Immutable` annotation, `staticCompositionLocalOf` vs `compositionLocalOf`
- [W3C WCAG 2.1 Understanding SC 1.4.1: Use of Color](https://www.w3.org/WAI/WCAG21/Understanding/use-of-color.html) -- Requirement that color must not be the sole means of conveying information

### Secondary (MEDIUM confidence)
- [Colorblind-Friendly Design Guidelines](https://www.colorblindguide.com/post/colorblind-friendly-design-3) -- Blue/orange as universal safe pair for deuteranopia
- [Visme: Color Blind Friendly Palettes](https://visme.co/blog/color-blind-friendly-palette/) -- Deuteranopia hex value references
- [WebAIM Contrast Checker](https://webaim.org/resources/contrastchecker/) -- WCAG AA ratio requirements (4.5:1 text, 3:1 UI)
- [WCAG 1.4.1 Implementation Guide](https://wcag.dock.codes/documentation/wcag141/) -- Secondary signal requirements

### Tertiary (LOW confidence)
- Deuteranopia palette specific hex values: Recommended based on design principles but need per-background contrast verification

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- Compose CompositionLocal, multiplatform-settings, and Material 3 are all already in the project; patterns well-documented
- Architecture: HIGH -- Followed exact patterns from Android official docs and existing project conventions (`LocalWindowSizeClass`, `ThemeViewModel`)
- Pitfalls: HIGH -- Identified from direct codebase audit; 3 duplicate zoneColor functions confirmed, BalanceBar already has percentage text
- Color values: MEDIUM -- Based on established deuteranopia research but not contrast-verified against this app's backgrounds

**Research date:** 2026-02-27
**Valid until:** 2026-03-27 (stable domain -- WCAG standards and Compose CompositionLocal patterns don't change frequently)
