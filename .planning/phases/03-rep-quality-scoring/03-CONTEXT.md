# Phase 3: Rep Quality Scoring - Context

**Gathered:** 2026-02-14
**Status:** Ready for planning

<domain>
## Phase Boundary

Per-rep quality scores (0-100) displayed on workout HUD during sets, with set summaries showing quality trends. Scores reflect four components: ROM, velocity, eccentric control, and smoothness. Form Master badges awarded for quality achievements (Phoenix+ tier only).

</domain>

<decisions>
## Implementation Decisions

### Per-Rep Score Display
- Center-prominent display: score takes center stage momentarily after each rep
- Quick flash timing (0.5-1s): brief acknowledgment, then fades to avoid distraction
- Gradient color scale: smooth transition from red (0) through yellow to green (100)
- Subtle sparkle/pulse effect for excellent reps (95+)

### Component Breakdown
- During workout: composite score only (components calculated internally)
- Component breakdown visible in: set summary AND workout history detail
- Presentation: radar/spider chart showing balance across all four dimensions
- Weakest component highlighted with brief improvement tip

### Set Summary Layout
- Trigger: inline expansion (set row expands in place after completion)
- Stats shown: average score + quality trend (improving/stable/declining)
- Trend visualization: mini sparkline showing quality curve across reps
- Radar chart access: swipe gesture to flip between stats view and radar view

### Badge & Achievement System
- Trigger: streak-based (3 consecutive sets above 85 quality)
- Badge tiers: Bronze/Silver/Gold based on streak length or quality threshold
- Announcement: full-screen celebration overlay (like PR achievements)
- Badge visibility: both achievements page AND inline on exercise history
- Gating: badges visible only to Phoenix+ tier users

### Claude's Discretion
- Exact sparkline rendering implementation
- Radar chart axis scaling and styling
- Specific improvement tips for each component
- Badge tier thresholds (e.g., Bronze = 3 sets, Silver = 5, Gold = 10)
- Transition animations between stats and radar views

</decisions>

<specifics>
## Specific Ideas

- Score display should feel "quick and rewarding" — flash in, make an impression, get out of the way
- Radar chart balances the "you decide" freedom on rendering with clear user expectation of a spider chart
- Swipe gesture for radar is a nice touch — keeps summary clean while providing depth on demand
- Celebration overlay should mirror existing PR celebration (consistency with current app behavior)

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 03-rep-quality-scoring*
*Context gathered: 2026-02-14*
