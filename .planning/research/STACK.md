# Stack Research: v0.6.0 Portal Sync Compatibility

**Date:** 2026-03-02
**Confidence:** HIGH — derived from architecture/features/pitfalls research + direct codebase inspection

## Stack Decisions

### No New KMP Dependencies Required

The architecture researcher confirmed that **supabase-kt SDK is NOT needed**. The existing Ktor HTTP client is sufficient for all Supabase interactions:

| Capability | Approach | Rationale |
|-----------|----------|-----------|
| Supabase Auth | Raw Ktor POST to `/auth/v1/token` | Just 2 REST calls (sign-in + refresh). Adding supabase-kt creates dependency conflicts with existing Ktor setup. |
| Edge Function calls | Raw Ktor POST to `/functions/v1/{name}` | Standard HTTP with Authorization header. No SDK needed. |
| Token storage | Existing `PortalTokenStorage.kt` | Already handles token persistence. Swap JWT format from custom → GoTrue. |
| JSON serialization | Existing `kotlinx.serialization` | Already used throughout the codebase. Supabase expects standard JSON. |

### Portal Stack Additions (Deno/TypeScript)

| Component | Technology | Version | Notes |
|-----------|-----------|---------|-------|
| Edge Functions runtime | Deno | Supabase-managed | No version pinning needed |
| Supabase client (server) | `@supabase/supabase-js` | v2.x | Already available in Edge Functions |
| CORS helper | Custom (mobile-aware) | N/A | Existing `_shared/cors.ts` won't work for native clients (no Origin header) |

### Edge Function Architecture

Two separate functions, not one combined:

| Function | Purpose | Auth Mode | Why Separate |
|----------|---------|-----------|--------------|
| `mobile-sync-push` | Receive mobile workout data, write to all tables | `service_role` key (bypass RLS, inject user_id) | Heavy writes, needs service_role |
| `mobile-sync-pull` | Query portal data for mobile download | User JWT (RLS does filtering) | Read-only, standard auth |

### Supabase Auth REST API (GoTrue)

Endpoints the mobile app needs to call:

| Endpoint | Method | Purpose |
|----------|--------|---------|
| `/auth/v1/token?grant_type=password` | POST | Email/password sign-in |
| `/auth/v1/token?grant_type=refresh_token` | POST | Token refresh |
| `/auth/v1/signup` | POST | New account registration |
| `/auth/v1/user` | GET | Get current user profile |

Headers: `apikey: {anon_key}`, `Authorization: Bearer {access_token}` (for authenticated calls)

### Portal DB Migration Stack

| Tool | Purpose |
|------|---------|
| Supabase CLI (`supabase migration new`) | Generate timestamped SQL migration files |
| PostgreSQL DDL | `ALTER TABLE ADD COLUMN IF NOT EXISTS`, `CREATE TABLE IF NOT EXISTS` |
| RLS policies | `CREATE POLICY ... FOR INSERT WITH CHECK (auth.uid() = user_id)` |

### What NOT to Add

| Library | Why Not |
|---------|---------|
| supabase-kt | Dependency conflicts with existing Ktor; raw HTTP is simpler and already proven |
| Supabase Realtime | Not needed for sync (batch push/pull is sufficient) |
| Railway backend | Adds infrastructure to maintain; Edge Functions are zero-ops |
| GraphQL | Portal uses REST/PostgREST; no benefit to adding another query layer |
| WebSocket sync | Over-engineered for batch workout sync; REST is sufficient |

## Key Constraints

### Edge Function Limits (from Supabase docs)

| Limit | Value | Impact |
|-------|-------|--------|
| CPU time | 2 seconds | Rep telemetry must be chunked (full workout exceeds this) |
| Wall clock | 150 seconds | Sufficient for batch inserts |
| Memory | 256 MB | Sufficient |
| Request body | ~10 MB practical | Full telemetry payload exceeds this; chunk per-set |

### Telemetry Size Budget

At 50Hz BLE sampling:
- 1 rep ≈ 3-5 seconds → 150-250 samples
- 10-rep set ≈ ~2,000 rows
- 5-exercise workout (3 sets each) ≈ ~30,000 rows
- JSON payload: **~16 MB** (exceeds Edge Function limits)

**Decision needed:** Telemetry must be chunked per-set or deferred to WiFi-only background sync.

## Integration Points

### Mobile (Project-Phoenix-MP)

| File | Change Type | Description |
|------|------------|-------------|
| `PortalApiClient.kt` | MODIFY | Swap Railway URLs → Supabase Edge Function URLs + GoTrue auth |
| `PortalTokenStorage.kt` | MODIFY | Store GoTrue JWT format (access_token + refresh_token) |
| `SyncManager.kt` | MODIFY | Wire to PortalSyncAdapter, build PortalSyncPayload |
| `PortalSyncDtos.kt` | MODIFY | Add user_id to nested DTOs, add exercise_progress + personal_records DTOs |
| `PortalSyncAdapter.kt` | MODIFY | Add exercise_progress computation, personal_records mapping, rep telemetry wiring |
| New: pull response DTOs | CREATE | Portal-native pull DTOs + reverse adapter |

### Portal (phoenix-portal)

| File | Change Type | Description |
|------|------------|-------------|
| `supabase/functions/mobile-sync-push/` | CREATE | Edge Function for receiving mobile sync payloads |
| `supabase/functions/mobile-sync-pull/` | CREATE | Edge Function for serving portal data to mobile |
| `supabase/functions/_shared/mobile-cors.ts` | CREATE | Mobile-aware CORS helper (no Origin header) |
| `supabase/migrations/YYYYMMDD_*.sql` | CREATE | Schema migrations for missing tables + columns |
| `src/utils/workoutModes.ts` (or similar) | MODIFY | Mode display mapping (SCREAMING_SNAKE → human readable) |

---
*Research completed: 2026-03-02*
*Sources: Architecture, Features, and Pitfalls research agents + direct codebase inspection*
