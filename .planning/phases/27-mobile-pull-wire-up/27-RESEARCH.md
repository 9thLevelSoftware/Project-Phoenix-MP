# Phase 27: Mobile Pull Wire-Up - Research

**Researched:** 2026-03-02
**Domain:** Kotlin Multiplatform — Ktor HTTP client, Koin DI, SQLDelight, Supabase Edge Functions
**Confidence:** HIGH

## Summary

Phase 27 is the inverse of Phase 26 (push). The `mobile-sync-pull` Edge Function is already deployed (Phase 25). SyncManager.sync() currently short-circuits pull at line 87-88 with a TODO comment. The task is to: (1) add pull-specific DTOs for the camelCase Edge Function response, (2) add a pullPortalPayload() API method, (3) create a PortalPullAdapter to convert portal DTOs to legacy merge DTOs, (4) extend routine merge to handle exercises, and (5) wire pullRemoteChanges() into SyncManager.sync().

**Critical discovery:** The push DTOs use `@SerialName("snake_case")` annotations (matching portal DB columns), but the pull Edge Function returns **camelCase** JSON keys (e.g., `userId` not `user_id`, `startedAt` not `started_at`). The existing push DTOs CANNOT be reused for pull deserialization. New pull-specific DTOs without @SerialName are required.

**Merge strategy (from STATE.md + REQUIREMENTS.md PULL-03):**
- Sessions: **immutable / push-only** — skip entirely during pull (mobile creates sessions locally)
- Routines: **LWW with local preference** — if local `updatedAt > lastSync`, keep local; otherwise accept portal version
- Badges: **union merge** — insert if not exists (by badgeId)
- RPG attributes: **server wins** — overwrite local with portal values
- Gamification stats: **server wins** — overwrite local, preserve local-only fields

**Simplification:** Sessions are explicitly immutable/push-only per PULL-03. The pull response includes sessions but mobile SKIPS them. This eliminates the most complex conversion (portal hierarchy → flat mobile sessions).

<phase_requirements>
## Phase Requirements

| ID | Description | Research Support |
|----|-------------|-----------------|
| PULL-01 | Mobile app can pull portal-native workout data via sync-pull Edge Function and deserialize using portal-format DTOs | New pull DTOs match Edge Function camelCase response; pullPortalPayload() targets /functions/v1/mobile-sync-pull |
| PULL-02 | PortalPullAdapter converts portal-native DTOs back to mobile domain models | Adapter converts PullRoutineDto → RoutineSyncDto, PullBadgeDto → EarnedBadgeSyncDto, etc. |
| PULL-03 | Pull sync uses timestamp-based merge strategy: sessions immutable (push-only), routines LWW by updatedAt, badges union merge | Sessions skipped; routines use local updatedAt vs lastSync; badges use existing mergeBadges() |
</phase_requirements>

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Ktor Client | 3.x | HTTP POST to pull Edge Function | Already in use in PortalApiClient |
| kotlinx.serialization | 1.x | JSON decode pull response | Already configured with ignoreUnknownKeys=true |
| Koin | 4.1.1 | DI — no new bindings needed | All dependencies already wired from Phase 26 |
| SQLDelight | 2.2.1 | Merge routines, badges, stats | Already project-standard |

**Installation:** No new dependencies required.

## Architecture Patterns

### Pattern 1: Pull-Specific DTOs (camelCase)

**What:** The pull Edge Function returns camelCase JSON keys (e.g., `userId`, `startedAt`). The push DTOs use `@SerialName("user_id")` for snake_case. These are incompatible for deserialization. Create pull-specific DTOs without @SerialName.

**Pull Response DTO:**
```kotlin
@Serializable
data class PortalSyncPullResponse(
    val syncTime: Long,  // epoch millis (Date.now() in Edge Function)
    val sessions: List<PullWorkoutSessionDto> = emptyList(),  // SKIPPED during merge
    val routines: List<PullRoutineDto> = emptyList(),
    val rpgAttributes: PullRpgAttributesDto? = null,
    val badges: List<PullBadgeDto> = emptyList(),
    val gamificationStats: PullGamificationStatsDto? = null
)
```

**Key difference from push:** syncTime is `Long` (epoch millis from `Date.now()`), not `String` (ISO 8601).

### Pattern 2: PortalPullAdapter (Portal → Mobile)

**What:** Convert pull DTOs to legacy merge DTOs. Only routines, badges, and gamification need conversion (sessions skipped, RPG handled via GamificationRepository).

**Routine conversion:** PullRoutineDto → RoutineSyncDto + routine exercise handling
**Badge conversion:** PullBadgeDto → EarnedBadgeSyncDto
**Gamification conversion:** PullGamificationStatsDto → GamificationStatsSyncDto

### Pattern 3: Routine Merge with Exercises

**What:** The existing `mergeRoutines()` only handles routine metadata (name, description). It does NOT handle routine exercises. Portal returns full routines with exercises. Need to extend the merge to handle exercises.

**Approach:** Add `mergePortalRoutines()` to SyncRepository that:
1. For each portal routine, check if it exists locally by ID
2. If not exists → insert routine + all exercises
3. If exists AND local `updatedAt > lastSync` → skip (keep local, satisfies PULL-03)
4. If exists AND local `updatedAt <= lastSync` → update metadata + replace exercises

### Pattern 4: Pull Failure Is Non-Fatal

**What:** If pull fails (network error, server error), sync should still succeed with just the push result. Pull failure is logged but does not fail the sync.

### Anti-Patterns to Avoid

- **Reusing push DTOs for pull deserialization:** @SerialName snake_case won't match camelCase response
- **Pulling sessions:** Sessions are push-only/immutable per PULL-03
- **Blocking sync on pull failure:** Pull is best-effort; push success is sufficient
- **Overwriting locally-modified routines:** Must check local updatedAt vs lastSync

## Common Pitfalls

### Pitfall 1: Push DTO @SerialName vs Pull camelCase
**What goes wrong:** Deserializing pull response with push DTOs fails silently (fields default to 0/null/empty) because @SerialName("user_id") doesn't match JSON key "userId".
**How to avoid:** Dedicated pull DTOs without @SerialName.

### Pitfall 2: syncTime Type Mismatch
**What goes wrong:** Push response has syncTime as ISO 8601 String. Pull response has syncTime as Long (epoch millis). Using the wrong type causes deserialization failure.
**How to avoid:** PortalSyncPullResponse.syncTime is Long. No Instant.parse() needed.

### Pitfall 3: Missing apikey Header
**What goes wrong:** Same as push — Edge Functions require both Bearer token and apikey header.
**How to avoid:** Copy the pattern from pushPortalPayload().

### Pitfall 4: Routine Exercises Not Merged
**What goes wrong:** Existing mergeRoutines() only handles metadata. Portal routines with exercises appear in mobile as empty routines.
**How to avoid:** New mergePortalRoutines() that handles both metadata and exercises.

### Pitfall 5: Fresh Install Pull (lastSync=0)
**What goes wrong:** On first sync, ALL portal routines are returned. If merge doesn't handle bulk insert properly, duplicates or FK errors occur.
**How to avoid:** Use INSERT OR REPLACE pattern. Test with empty local DB.

## Sources

### Primary (HIGH confidence)
- `shared/.../data/sync/SyncManager.kt` — current sync() with pull short-circuit at line 87
- `shared/.../data/sync/PortalApiClient.kt` — pushPortalPayload() pattern to copy for pull
- `shared/.../data/sync/PortalSyncDtos.kt` — push DTOs (can't reuse for pull due to @SerialName)
- `shared/.../data/sync/SyncModels.kt` — legacy sync DTOs used by merge methods
- `shared/.../data/repository/SyncRepository.kt` — merge method interfaces
- `shared/.../data/repository/SqlDelightSyncRepository.kt` — merge implementations
- `phoenix-portal/supabase/functions/mobile-sync-pull/index.ts` — deployed Edge Function, exact response shape

### Secondary (MEDIUM confidence)
- `shared/.../data/repository/GamificationRepository.kt` — badge/RPG save methods
- `.planning/STATE.md` — merge strategy decisions, open flags

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH — no new dependencies
- Architecture: HIGH — Edge Function source read, all mobile files read, exact wire format confirmed
- Pitfalls: HIGH — derived from push experience (Phase 26) and direct code inspection

**Research date:** 2026-03-02
**Valid until:** 2026-04-02
