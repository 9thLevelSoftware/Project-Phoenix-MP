# Phase 13: Biomechanics Persistence - Research

**Researched:** 2026-02-20
**Domain:** SQLDelight schema migration, KMP data persistence, Compose UI for session history
**Confidence:** HIGH

## Summary

Phase 13 bridges the gap between the BiomechanicsEngine (Phase 12, v0.4.6) and persistent storage. Currently, per-rep biomechanics results (VBT metrics, force curves, asymmetry) are computed in-memory during workouts and displayed in the real-time SetSummaryCard, but are discarded when the workout ends. The RepMetric table stores raw curve data (positions, loads, velocities) but none of the derived biomechanics analysis (MCV, velocity zone, normalized force curve, sticking point, strength profile, asymmetry).

This phase requires: (1) a new `RepBiomechanics` table for per-rep analysis, (2) new columns on `WorkoutSession` for set-level summary, (3) a `15.sqm` migration file plus 3-location iOS sync, (4) a repository to persist biomechanics at set completion, and (5) UI integration into the existing HistoryTab session detail cards with Phoenix+ tier gating.

**Primary recommendation:** Add a new `RepBiomechanics` table (linked to RepMetric via sessionId + repNumber) for per-rep VBT/force/asymmetry results, add set-level summary columns to WorkoutSession, and persist at set completion alongside the existing RepMetric save. Store the 101-point normalized force curve as a JSON TEXT column (same pattern as RepMetric curve arrays). UI surfaces biomechanics in the existing WorkoutHistoryCard expandable section, gated behind FeatureGate.

<user_constraints>
## User Constraints (from CONTEXT.md)

### Locked Decisions
All areas are Claude's Discretion -- no hard-locked user decisions. The following guidance captures user intent:

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
- Full interactive chart is unnecessary for history review -- summary visualization is sufficient
- Reuse the ForceSparkline composable from Phase 12 (RepReplayCard) where possible

**Tier gating:**
- Follow GATE-04 pattern: capture biomechanics data for ALL tiers at the data layer
- Gate the biomechanics UI display in session history behind Phoenix+ tier
- FREE tier users still have the data persisted (available if they upgrade)

### Claude's Discretion
All implementation areas -- schema design, repository structure, UI composition, migration approach.

### Deferred Ideas (OUT OF SCOPE)
None -- discussion stayed within phase scope.
</user_constraints>

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| PERSIST-01 | Per-rep VBT metrics (MCV, velocity zone, velocity loss, rep projection) saved to DB and available in session history | New `RepBiomechanics` table with VBT columns; repository layer maps BiomechanicsRepResult to DB; HistoryTab UI displays VBT data |
| PERSIST-02 | Per-rep force curve data (101-point normalized curve, sticking point, strength profile) saved to DB | `normalizedForceN` stored as JSON TEXT in RepBiomechanics (same pattern as RepMetric arrays); stickingPointPct and strengthProfile as REAL/TEXT columns |
| PERSIST-03 | Per-rep asymmetry data (asymmetry %, dominant side) saved to DB | `asymmetryPercent`, `dominantSide`, `avgLoadA`, `avgLoadB` columns on RepBiomechanics |
| PERSIST-04 | Set-level biomechanics summary (avg MCV, avg asymmetry, velocity loss trend) stored on WorkoutSession | New nullable columns on WorkoutSession: `avgMcvMmS`, `avgAsymmetryPercent`, `totalVelocityLossPercent`, `dominantSide`, `strengthProfile` |
| PERSIST-05 | Schema migration v16 applied safely on both Android and iOS (DriverFactory.ios.kt sync) | Migration 15.sqm + DriverFactory.android.kt resilient handler + DriverFactory.ios.kt manual schema (3-location update per Daem0n warning #155) |
</phase_requirements>

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| SQLDelight | 2.0.2 | Type-safe DB schema + queries | Already in use; generates type-safe Kotlin from .sq/.sqm files |
| Compose Multiplatform | 1.7.1 | UI for session history biomechanics display | Already in use for all screens |
| Koin | 4.0.0 | DI for new BiomechanicsRepository | Already in use for all repositories |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| Manual JSON serialization | N/A | FloatArray/LongArray to TEXT | Existing pattern in RepMetricRepository (toJsonString/toFloatArrayFromJson) |
| ForceSparkline composable | Existing | Force curve visualization | Reuse from Phase 12 in history view |
| FeatureGate | Existing | Tier-based UI gating | Gate biomechanics display behind Phoenix+ |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| JSON TEXT for 101-point curve | BLOB | JSON is human-readable, debuggable, consistent with RepMetric pattern; BLOB is 2-3x smaller but breaks grep/debug |
| Separate RepBiomechanics table | Columns on RepMetric | Separate table is cleaner (RepMetric is already 32 columns wide); avoids altering an existing table with many rows |
| Set-level summary columns on WorkoutSession | Separate BiomechanicsSessionSummary table | Columns on WorkoutSession is simpler, consistent with how force/weight summaries are already stored there |

## Architecture Patterns

### Recommended Data Flow
```
BiomechanicsEngine.processRep()   -- already exists (Phase 12)
         |
         v
BiomechanicsEngine.getSetSummary() -- already exists (Phase 12)
         |
         v
ActiveSessionEngine.handleSetCompletion()
   |-- Save RepMetric data (EXISTING)
   |-- Save RepBiomechanics per rep (NEW - PERSIST-01/02/03)
   |-- Save WorkoutSession with biomechanics summary columns (NEW - PERSIST-04)
         |
         v
HistoryTab -> WorkoutHistoryCard -> BiomechanicsSection (NEW UI)
   |-- Load WorkoutSession summary columns (PERSIST-04)
   |-- Lazy-load RepBiomechanics on expand (PERSIST-01/02/03)
```

### Recommended File Structure
```
shared/src/commonMain/
├── sqldelight/.../migrations/15.sqm          # Schema migration
├── kotlin/.../data/repository/
│   └── BiomechanicsRepository.kt             # New repository (interface + impl)
├── kotlin/.../domain/model/
│   └── BiomechanicsModels.kt                 # Existing (add persistence model)
├── kotlin/.../presentation/screen/
│   └── HistoryTab.kt                         # Extend with biomechanics section
├── kotlin/.../presentation/components/
│   └── BiomechanicsHistoryCard.kt            # New composable for history display

shared/src/iosMain/
└── kotlin/.../data/local/DriverFactory.ios.kt  # Sync manual schema + version bump
```

### Pattern 1: RepBiomechanics Table Design
**What:** New table storing derived biomechanics analysis per rep, linked to the session (not to RepMetric directly, to avoid coupling).
**When to use:** At set completion, alongside RepMetric persistence.
**Schema:**
```sql
CREATE TABLE RepBiomechanics (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sessionId TEXT NOT NULL,
    repNumber INTEGER NOT NULL,
    -- VBT metrics (PERSIST-01)
    mcvMmS REAL NOT NULL,
    peakVelocityMmS REAL NOT NULL,
    velocityZone TEXT NOT NULL,
    velocityLossPercent REAL,
    estimatedRepsRemaining INTEGER,
    shouldStopSet INTEGER NOT NULL DEFAULT 0,
    -- Force curve (PERSIST-02)
    normalizedForceN TEXT NOT NULL,          -- 101-point JSON array
    normalizedPositionPct TEXT NOT NULL,      -- 101-point JSON array
    stickingPointPct REAL,
    strengthProfile TEXT NOT NULL,
    -- Asymmetry (PERSIST-03)
    asymmetryPercent REAL NOT NULL,
    dominantSide TEXT NOT NULL,
    avgLoadA REAL NOT NULL,
    avgLoadB REAL NOT NULL,
    -- Metadata
    timestamp INTEGER NOT NULL,
    FOREIGN KEY (sessionId) REFERENCES WorkoutSession(id) ON DELETE CASCADE
);

CREATE INDEX idx_rep_biomechanics_session ON RepBiomechanics(sessionId);
CREATE INDEX idx_rep_biomechanics_session_rep ON RepBiomechanics(sessionId, repNumber);
```

### Pattern 2: WorkoutSession Summary Columns (PERSIST-04)
**What:** Add nullable biomechanics summary columns to the existing WorkoutSession table.
**When to use:** At session save, computed from BiomechanicsSetSummary.
```sql
ALTER TABLE WorkoutSession ADD COLUMN avgMcvMmS REAL;
ALTER TABLE WorkoutSession ADD COLUMN avgAsymmetryPercent REAL;
ALTER TABLE WorkoutSession ADD COLUMN totalVelocityLossPercent REAL;
ALTER TABLE WorkoutSession ADD COLUMN dominantSide TEXT;
ALTER TABLE WorkoutSession ADD COLUMN strengthProfile TEXT;
```

### Pattern 3: Repository Pattern (BiomechanicsRepository)
**What:** Interface + SQLDelight implementation following the exact same pattern as RepMetricRepository.
**When to use:** For all biomechanics CRUD operations.
```kotlin
interface BiomechanicsRepository {
    suspend fun saveRepBiomechanics(sessionId: String, results: List<BiomechanicsRepResult>)
    suspend fun getRepBiomechanics(sessionId: String): List<BiomechanicsRepResult>
    suspend fun deleteRepBiomechanics(sessionId: String)
}
```

### Pattern 4: 3-Location Migration (Daem0n Warning #155)
**What:** Schema changes require updates in 3 locations:
1. `VitruvianDatabase.sq` -- canonical schema (CREATE TABLE + queries)
2. `migrations/15.sqm` -- Android migration from v15 to v16
3. `DriverFactory.ios.kt` -- manual schema + version bump (iOS never runs .sqm files)

**Why:** iOS uses a 4-layer defense system that bypasses SQLDelight migrations entirely. The iOS DriverFactory manually creates all tables, adds all columns, creates all indexes, and sets `PRAGMA user_version`. Missing any location means one platform works but the other crashes.

### Anti-Patterns to Avoid
- **Adding columns to RepMetric:** RepMetric already has 32 columns and stores raw curve data. Adding 18 more columns for derived biomechanics would create a 50-column table. Use a separate table.
- **Storing FloatArray as BLOB:** Breaks the established pattern. RepMetric uses JSON TEXT for all arrays. Consistency trumps marginal storage savings.
- **Eager-loading RepBiomechanics in history list:** The 101-point force curve per rep is expensive to deserialize. Lazy-load only when user expands a session card.
- **Running BiomechanicsEngine on persisted data:** The engine is designed for real-time computation. For history, read pre-computed results from RepBiomechanics.
- **Forgetting iOS DriverFactory sync:** The #1 cause of iOS crashes in this project. Every schema change MUST touch all 3 locations.

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| JSON array serialization | Custom parser | Existing `toJsonString()`/`toFloatArrayFromJson()` in RepMetricRepository | Already tested, handles edge cases (empty arrays, single elements) |
| Force curve visualization | New chart component | Existing `ForceSparkline` composable | Already implements canvas-based curve rendering with peak markers |
| Tier gating logic | Inline if/else checks | `FeatureGate.isEnabled()` | Centralized tier management with offline grace period |
| Schema migration on iOS | SQLDelight auto-migration | Manual schema in DriverFactory.ios.kt | iOS Kotlin/Native crashes on SQLDelight migrations (documented in DriverFactory header) |

**Key insight:** All the building blocks exist. This phase is primarily about wiring existing domain models (BiomechanicsModels.kt) to existing persistence patterns (RepMetricRepository) and existing UI patterns (HistoryTab + ForceSparkline).

## Common Pitfalls

### Pitfall 1: iOS DriverFactory Schema Desync
**What goes wrong:** New table/columns added to VitruvianDatabase.sq and 15.sqm but not to DriverFactory.ios.kt. App works on Android, crashes on iOS.
**Why it happens:** iOS uses manual schema management (Layer 4) that requires explicit CREATE TABLE + safeAddColumn calls.
**How to avoid:** The 3-location checklist: (1) .sq schema, (2) .sqm migration, (3) DriverFactory.ios.kt. Also bump `CURRENT_SCHEMA_VERSION` from 15L to 16L.
**Warning signs:** iOS "no such table" or "no such column" errors at launch.

### Pitfall 2: Schema Version Mismatch
**What goes wrong:** VitruvianDatabase.sq compiles to a schema with version = 1 + count(.sqm files). If the count is wrong, Android migration logic skips or re-runs migrations.
**Why it happens:** SQLDelight counts .sqm files to determine schema version. Adding 15.sqm makes version = 16 (base 1 + 15 migrations, but some numbers are skipped -- SQLDelight uses the file number, not count).
**How to avoid:** Current highest migration is 14.sqm. New migration MUST be 15.sqm. Verify `VitruvianDatabase.Schema.version` outputs 16 after adding it.
**Warning signs:** Android migration callback called with unexpected version numbers.

### Pitfall 3: 101-Point Force Curve Storage Bloat
**What goes wrong:** Each rep stores ~1KB of force curve JSON. A 10-rep set = ~10KB. 100 workouts x 3 sets x 10 reps = ~3MB. Not a problem individually, but needs awareness.
**Why it happens:** Normalized force curves are 101 Float values serialized to JSON text.
**How to avoid:** This is acceptable. The same pattern is already used for RepMetric curve arrays (which store more data per rep). No special mitigation needed.
**Warning signs:** Database file growing beyond ~50MB (would require thousands of workouts).

### Pitfall 4: Nullable Biomechanics on Older Sessions
**What goes wrong:** Existing WorkoutSession records won't have biomechanics summary columns populated. UI must handle null gracefully.
**Why it happens:** Only sessions saved AFTER the migration will have biomechanics data.
**How to avoid:** All new WorkoutSession columns are nullable. UI checks for null and conditionally renders the biomechanics section. `hasBiomechanicsData` computed property.
**Warning signs:** Crashes when expanding an older session in history.

### Pitfall 5: SQLDelight Mapper Mismatch After Adding WorkoutSession Columns
**What goes wrong:** The `insertSession` and `upsertSyncSession` queries in .sq need to be updated to include new columns. Forgetting to update them causes compile errors.
**Why it happens:** SQLDelight queries are typed -- every column in INSERT must be listed.
**How to avoid:** Update both `insertSession` and `upsertSyncSession` queries. Also update the `WorkoutSession` domain model to include the new fields.
**Warning signs:** SQLDelight compilation failures referencing wrong parameter count.

### Pitfall 6: GATE-04 Enforcement at Wrong Layer
**What goes wrong:** Biomechanics data not saved for FREE tier users because gating is applied at the repository layer.
**Why it happens:** Misunderstanding GATE-04: data capture is unconditional; only UI display is gated.
**How to avoid:** Repository saves for ALL tiers (no FeatureGate checks). FeatureGate checks ONLY in the Compose UI layer, matching the existing pattern in RepMetricRepository ("IMPORTANT: No subscription tier checks here. Data is captured for ALL users").
**Warning signs:** FREE tier users upgrade but have no historical biomechanics data.

## Code Examples

### Example 1: Persisting RepBiomechanics at Set Completion
```kotlin
// In ActiveSessionEngine.handleSetCompletion(), after existing RepMetric save:
// Line ~1948 area in current code

// Persist per-rep biomechanics data (GATE-04: captured for all tiers)
val biomechanicsSummary = coordinator.biomechanicsEngine.getSetSummary()
if (sessionId != null && biomechanicsSummary != null) {
    try {
        biomechanicsRepository.saveRepBiomechanics(
            sessionId,
            biomechanicsSummary.repResults
        )
        Logger.d { "Persisted ${biomechanicsSummary.repResults.size} rep biomechanics for session $sessionId" }
    } catch (e: Exception) {
        Logger.e(e) { "Failed to persist rep biomechanics for session $sessionId" }
    }
}
```

### Example 2: Adding Biomechanics Summary to WorkoutSession Save
```kotlin
// Extend the WorkoutSession construction at ~line 1595:
val session = WorkoutSession(
    // ... existing fields ...
    // NEW: Biomechanics summary from engine
    avgMcvMmS = biomechanicsSummary?.avgMcvMmS,
    avgAsymmetryPercent = biomechanicsSummary?.avgAsymmetryPercent,
    totalVelocityLossPercent = biomechanicsSummary?.totalVelocityLossPercent,
    dominantSide = biomechanicsSummary?.dominantSide,
    strengthProfile = biomechanicsSummary?.strengthProfile?.name
)
```

### Example 3: JSON Serialization for Force Curve (Reusing Existing Pattern)
```kotlin
// In BiomechanicsRepository, reuse RepMetricRepository helpers:
import com.devil.phoenixproject.data.repository.toJsonString
import com.devil.phoenixproject.data.repository.toFloatArrayFromJson

queries.insertRepBiomechanics(
    sessionId = sessionId,
    repNumber = result.repNumber.toLong(),
    normalizedForceN = result.forceCurve.normalizedForceN.toJsonString(),
    normalizedPositionPct = result.forceCurve.normalizedPositionPct.toJsonString(),
    // ... other fields ...
)
```

### Example 4: FeatureGate in History UI (GATE-04 Pattern)
```kotlin
// In WorkoutHistoryCard expanded section:
val tier = // resolve from UserProfile
if (FeatureGate.isEnabled(FeatureGate.Feature.VBT_METRICS, tier)) {
    BiomechanicsHistorySummary(
        avgMcv = session.avgMcvMmS,
        avgAsymmetry = session.avgAsymmetryPercent,
        velocityLoss = session.totalVelocityLossPercent,
        dominantSide = session.dominantSide,
        strengthProfile = session.strengthProfile
    )
} else {
    PremiumUpsellCard(feature = "Biomechanics Analysis")
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| BiomechanicsEngine computes, results discarded | Results persisted in RepBiomechanics + WorkoutSession | Phase 13 (this phase) | Users can review biomechanics in session history |
| RepMetric stores raw data only | RepBiomechanics stores derived analysis | Phase 13 (this phase) | Avoids re-running engine on history view; instant load |
| WorkoutSession has no biomechanics columns | 5 new summary columns | Phase 13 (this phase) | Set-level summary visible without loading per-rep data |

**Deprecated/outdated:**
- Nothing deprecated. This phase adds new capability on top of Phase 12.

## Open Questions

1. **Force Curve Average in Set Summary**
   - What we know: BiomechanicsSetSummary includes `avgForceCurve: ForceCurveResult?` which is a 101-point averaged curve across all reps. This is useful for the set-level view.
   - What's unclear: Should we persist the averaged force curve on WorkoutSession (as a JSON TEXT column) or compute it from per-rep data when needed?
   - Recommendation: Persist it. Computing element-wise average of N x 101-point arrays on every history view load is wasteful. One additional TEXT column (~500 bytes) on WorkoutSession is cheap. But this could also be deferred if the set-level summary (avg MCV, avg asymmetry, velocity loss trend) is sufficient for the "at a glance" view.

2. **Backup/Sync of RepBiomechanics**
   - What we know: The project has backup import/export and Supabase sync infrastructure. New tables typically need selectAll/insertIgnore queries.
   - What's unclear: Should Phase 13 add sync queries for RepBiomechanics?
   - Recommendation: Add the `selectAllRepBiomechanicsSync` query and `insertRepBiomechanicsIgnore` for backup compatibility. Sync to portal can be deferred to v0.5.5+ (PORTAL-04).

3. **RepBiomechanics Relationship to RepMetric**
   - What we know: Both tables use (sessionId, repNumber) as a logical key. They're not FK-linked.
   - What's unclear: Should RepBiomechanics have a FK to RepMetric?
   - Recommendation: No FK. RepMetric uses AUTOINCREMENT id, making FK linkage cumbersome. Instead, use (sessionId, repNumber) as the logical join key, consistent with how RepMetric is queried. Add a unique index on (sessionId, repNumber) to prevent duplicate inserts.

## Sources

### Primary (HIGH confidence)
- **VitruvianDatabase.sq** -- Full schema with 14 tables, 14 migrations, current version 15
- **BiomechanicsEngine.kt** -- processRep() and getSetSummary() output structures
- **BiomechanicsModels.kt** -- Domain models: VelocityResult, ForceCurveResult, AsymmetryResult, BiomechanicsRepResult, BiomechanicsSetSummary
- **RepMetricRepository.kt** -- JSON serialization pattern for FloatArray/LongArray
- **DriverFactory.ios.kt** -- 4-layer defense, manual schema, CURRENT_SCHEMA_VERSION = 15L
- **DriverFactory.android.kt** -- Resilient migration handler with getMigrationStatements()
- **ActiveSessionEngine.kt** -- handleSetCompletion() flow: save session, save rep metrics, capture biomechanics summary
- **FeatureGate.kt** -- Tier gating logic (FREE/PHOENIX/ELITE)
- **HistoryTab.kt** -- WorkoutHistoryCard with expandable sections
- **ForceSparkline.kt** -- Canvas-based sparkline composable for force curves

### Secondary (MEDIUM confidence)
- **Daem0n warning #155** -- DriverFactory.ios.kt must be synced for schema changes (3-location update)
- **GATE-04 pattern** -- Documented in RepMetricRepository, ActiveWorkoutScreen, ActiveSessionEngine

### Tertiary (LOW confidence)
- None. All findings verified against codebase.

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH -- all libraries and patterns already in use in the project
- Architecture: HIGH -- follows established patterns (RepMetricRepository, DriverFactory migrations, FeatureGate)
- Pitfalls: HIGH -- derived from documented project-specific issues (Daem0n warning #155, iOS migration crashes, GATE-04 pattern)

**Research date:** 2026-02-20
**Valid until:** 2026-03-20 (stable domain, no external dependencies changing)
