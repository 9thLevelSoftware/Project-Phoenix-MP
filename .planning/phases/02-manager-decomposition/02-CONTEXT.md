# Phase 2: Manager Decomposition - Context

**Gathered:** 2026-02-13
**Status:** Ready for planning

<domain>
## Phase Boundary

Split DefaultWorkoutSessionManager (4,024 lines) into WorkoutCoordinator + RoutineFlowManager + ActiveSessionEngine while preserving identical behavior and the same public API surface. BLE (KableBleRepository) is explicitly out of scope. No UI screen modifications required.

</domain>

<decisions>
## Implementation Decisions

### Sub-manager communication
- **Coordinator-only**: RoutineFlowManager and ActiveSessionEngine communicate exclusively through WorkoutCoordinator. They must never hold references to each other.
- Cross-manager triggering pattern (SharedFlow events on Coordinator vs DWSM orchestration): Claude's discretion based on code analysis

### DWSM API surface
- **No UI changes is the real goal** — not a frozen API. DWSM method signatures can be cleaned up/simplified during decomposition as long as MainViewModel is updated to match and no UI screens change.
- **WorkoutSessionManager interface gets updated too** — keep the abstraction honest when methods change.
- **MainViewModel talks to DWSM only** — sub-managers are internal implementation details, never exposed to the ViewModel layer.

### Ambiguous method ownership
- Methods spanning both routine and workout domains: Claude determines ownership based on code analysis during research
- DWSM final line count (~800 target): Claude lets the natural split determine the result
- handleMonitorMetric() hot path placement: Claude determines based on actual call chain analysis
- BLE command co-location interpretation: Claude determines based on coupling analysis

### Extraction safety
- **Test after every method move** — run all 38 characterization tests after each individual method extraction. Maximum paranoia.
- **Revert on failure** — if a test fails after a move, immediately revert and retry differently. Never proceed with failing tests. Never fix forward.
- **Atomic commits per move** — each method/group extraction gets its own git commit. Full git history for bisecting.
- Init block collector ordering documentation: Claude's discretion on timing (before vs during extraction)

### Claude's Discretion
- Cross-manager triggering pattern (SharedFlow events vs DWSM orchestration)
- WorkoutCoordinator state scope (all state vs cross-cutting only)
- BLE circular dependency event direction
- Ambiguous method ownership assignments
- handleMonitorMetric() placement
- BLE command co-location interpretation
- DWSM final size (let code tell the story)
- Init block ordering documentation timing

</decisions>

<specifics>
## Specific Ideas

- User wants maximum safety during extraction — paranoid verification (test after every move, revert-not-fix, atomic commits)
- Coordinator-only communication is a firm architectural constraint — no shortcuts allowed
- The roadmap's existing decisions are locked: managers out of Koin, concrete classes not interfaces, WorkoutCoordinator has zero business logic methods, BLE co-located with state transitions in ActiveSessionEngine

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 02-manager-decomposition*
*Context gathered: 2026-02-13*
