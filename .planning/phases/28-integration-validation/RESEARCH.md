# Phase 28: Integration Validation — Research

## Domain Analysis

Phase 28 validates end-to-end bidirectional sync across both repos (Project-Phoenix-MP + phoenix-portal). All implementation is complete in phases 23-27. This phase verifies correctness, handles edge cases, and produces a deployment runbook.

## Current Sync Architecture

### Push Flow (Mobile → Portal)
1. `SyncManager.sync()` → `pushLocalChanges()`
2. Gathers: sessions (with reps), routines, RPG attributes, badges, gamification stats
3. `PortalSyncAdapter` transforms to 3-tier hierarchy (sessions → exercises → sets → rep_summaries)
4. `PortalApiClient.pushPortalPayload()` → POST `/functions/v1/mobile-sync-push`
5. Edge Function: JWT verify → hierarchical INSERT → compute exercise_progress + personal_records → upsert routines/gamification
6. Returns `PortalSyncPushResponse` with ISO 8601 syncTime + insert counters

### Pull Flow (Portal → Mobile)
1. `SyncManager.pullRemoteChanges()` (non-fatal on failure)
2. `PortalApiClient.pullPortalPayload()` → POST `/functions/v1/mobile-sync-pull`
3. Response: camelCase DTOs (separate from push's snake_case DTOs)
4. **Sessions SKIPPED** (immutable/push-only)
5. Routines: LWW with local preference (if local updatedAt > lastSync, keep local)
6. Badges: union merge (insert if not exists)
7. RPG attributes: server wins (overwrite)
8. Gamification stats: server wins (preserve local-only fields)

### Auth Flow
- GoTrue password auth → access_token (1h expiry) + refresh_token (single-use)
- Proactive refresh (60s buffer) + reactive refresh (on 401, retry once)
- Mutex-protected to prevent concurrent refresh races
- Session restoration on cold start

## Key Implementation Files

### Mobile (Project-Phoenix-MP)
- `SyncManager.kt` — Orchestrator (push/pull, auth, state machine)
- `PortalApiClient.kt` — HTTP client (GoTrue + Edge Functions, 30s timeout)
- `PortalSyncAdapter.kt` — Mobile → portal 3-tier transformation
- `PortalPullAdapter.kt` — Portal → legacy merge DTOs
- `PortalTokenStorage.kt` — Token persistence (Russhwolf Settings)
- `SyncTriggerManager.kt` — Auto-sync (throttle, failure tracking)
- `PortalSyncDtos.kt` — Push DTOs (@SerialName snake_case) + Pull DTOs (camelCase)
- `SyncRepository.kt` / `SqlDelightSyncRepository.kt` — DB merge operations
- `SyncModule.kt` — Koin DI wiring (5 dependencies)

### Portal (phoenix-portal)
- `supabase/functions/mobile-sync-push/` — Push Edge Function
- `supabase/functions/mobile-sync-pull/` — Pull Edge Function
- `supabase/migrations/` — 3 migration files (Phase 23)
- Portal uses service_role_key (bypasses RLS) for all DB operations

## Known Gaps & Edge Cases

### Functional Gaps
1. `checkStatus()` returns failure — Railway abandoned, no replacement status endpoint
2. Rep telemetry excluded from push (deferred to v0.6.1 chunked upload)
3. `muscleGroup` hardcoded to "General" on push (portal can derive from exercise name)
4. `totalTimeSeconds` in gamification stats defaults to 0
5. Routines always fully pulled (no delta — table lacks created_at/updated_at)

### Error Handling Gaps
1. No exponential backoff on transient network errors
2. No HTTP 429 rate-limit handling
3. Refresh token revocation vs server error indistinguishable
4. No mid-sync connectivity re-check
5. No email confirmation flow handling for sign-up

### Data Integrity Concerns
1. Push uses client-provided UUIDs — re-push same data = duplicate key errors?
2. Edge Function uses UPSERT for some tables, INSERT for others — idempotency varies
3. Pull syncTime is epoch millis, push syncTime is ISO 8601 string — conversion correctness
4. Routine exercises: delete-then-insert on push (FK cascade risk?)
5. Badge merge: earnedAt parse failure falls back to currentTimeMillis()

## Deployment Dependencies

**Correct order (schema → Edge Functions → mobile release):**
1. Phase 23: `supabase db push` — schema migrations (3 files)
2. Phase 25: `supabase functions deploy` — both Edge Functions
3. Phase 24+26+27: Mobile app release (auth + push + pull)

**Rollback considerations:**
- Schema migrations are additive (new columns, new tables) — safe to leave in place
- Edge Functions can be rolled back independently
- Mobile release requires store review cycle

## Success Criteria (from ROADMAP.md)
1. Full push round-trip: workout → push → portal dashboard (correct modes, weights, exercises)
2. Full pull round-trip: portal routine → pull → mobile (all advanced fields preserved)
3. Auth edge cases: token expiry → silent refresh → sync completes; bad creds → clear error
4. Deployment runbook executed in correct order with no rollback
