# Phase 17: WCAG Accessibility - Context

**Gathered:** 2026-02-27
**Status:** Ready for planning

<domain>
## Phase Boundary

Make all color-coded UI indicators usable by color-blind users through secondary visual signals and an optional deuteranopia-safe color palette. This phase establishes the WCAG AA 1.4.1 infrastructure (LocalColorBlindMode, AccessibilityColors) that all subsequent phases (18-22) must consume. No new features — purely accessibility retrofitting and theme infrastructure.

</domain>

<decisions>
## Implementation Decisions

### Color palette (locked by roadmap)
- Deuteranopia-safe palette: blue/orange/teal replacing green/red
- Toggled via Settings — not a system-wide replacement, user opt-in
- Palette applies globally when enabled (velocity zones, balance bar, readiness indicators, any future color-coded UI)

### Velocity zone signals (locked by success criteria)
- Text labels ("Explosive", "Grind", etc.) displayed alongside color coding
- Labels visible regardless of color-blind mode (always-on secondary signal)

### Balance bar signals (locked by success criteria)
- Numeric asymmetry percentage displayed alongside the colored bar
- Visible regardless of color-blind mode

### Theme infrastructure (locked by success criteria)
- LocalColorBlindMode CompositionLocal at theme root
- AccessibilityColors provided through theme for all composables
- All new composables in Phases 18-22 must consume these

### Claude's Discretion
- **Color palette scope**: Deuteranopia-only for v0.5.1 (roadmap specifies deuteranopia). Multi-type support (protanopia, tritanopia) can be a future enhancement
- **Exact color values**: Choose accessible blue/orange/teal shades that maintain sufficient contrast ratios (WCAG AA 4.5:1 for text, 3:1 for UI components)
- **Velocity zone label placement**: Inline text within or adjacent to zone indicators — prioritize readability without adding visual clutter to the workout HUD
- **Balance bar percentage placement**: Numeric value beside the bar (not inside, to avoid overlap at extreme values)
- **Settings toggle placement**: Within existing Settings screen, grouped with display/appearance preferences
- **Settings default state**: Off by default (standard palette is the default experience)
- **System accessibility auto-detect**: Not required — simple manual toggle is sufficient
- **Live preview**: Optional — toggle can apply immediately without a dedicated preview mode
- **Icon/shape usage**: Text labels are the primary secondary signal; additional icons/shapes at Claude's discretion if they improve clarity without clutter
- **Existing indicator audit**: Sweep all composables using hardcoded green/red/yellow and retrofit to use AccessibilityColors

</decisions>

<specifics>
## Specific Ideas

No specific requirements — open to standard approaches. The success criteria are well-defined: toggle, text labels, numeric percentage, and theme infrastructure.

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 17-wcag-accessibility*
*Context gathered: 2026-02-27*
