# Phase 13: Biomechanics Persistence - Context

**Gathered:** 2026-02-20
**Status:** Ready for planning

<domain>
## Phase Boundary

Persist per-rep biomechanics data (VBT metrics, force curves, asymmetry) from BiomechanicsEngine to the database via schema v16. Surface this data in session history so users can review biomechanics after workout completion. Data capture follows GATE-04 pattern (capture for all tiers, gate UI display).

</domain>

<decisions>
## Implementation Decisions

### Claude's Discretion

User trusts Claude's judgment on all implementation areas. The following guidance captures intent for downstream agents:

**Session history layout:**
- Integrate biomechanics data into existing session detail view rather than a separate tab
- Use cards or collapsible sections consistent with existing session history patterns
- Set-level biomechanics summary (avg MCV, avg asymmetry, velocity loss trend) should be visible at a glance without drilling down
- Per-rep detail should be accessible but not cluttering the primary view

**Per-rep drill-down:**
- Set-level summary is the primary view
- Individual rep biomechanics accessible via tap/expand interaction
- Each rep shows: MCV, velocity zone, velocity loss %, asymmetry %, dominant side
- Keep the interaction pattern consistent with existing session detail UX

**Force curve visualization:**
- Use compact sparkline-style curves in the per-rep view (consistent with existing ForceSparkline from Phase 12)
- Show sticking point and strength profile as labeled annotations or stats alongside the curve
- Full interactive chart is unnecessary for history review — summary visualization is sufficient
- Reuse the ForceSparkline composable from Phase 12 (RepReplayCard) where possible

**Tier gating:**
- Follow GATE-04 pattern: capture biomechanics data for ALL tiers at the data layer
- Gate the biomechanics UI display in session history behind Phoenix+ tier
- FREE tier users still have the data persisted (available if they upgrade)

</decisions>

<specifics>
## Specific Ideas

No specific requirements — open to standard approaches. Key technical constraints:
- Schema v16 must handle 101-point normalized force curve arrays efficiently (PERSIST-02)
- DriverFactory.ios.kt must be synced for iOS schema migration (Daem0n warning #155)
- Existing BiomechanicsEngine output structures (from v0.4.6) define the data shape

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 13-biomechanics-persistence*
*Context gathered: 2026-02-20*
