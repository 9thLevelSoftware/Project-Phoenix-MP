# Project Research Summary

**Project:** Project Phoenix MP — v0.6.0 Portal Sync Compatibility
**Domain:** KMP mobile app bidirectional sync with Supabase/React portal
**Researched:** 2026-03-02
**Confidence:** HIGH

## Executive Summary

The v0.6.0 milestone resolves 12 compatibility issues between the KMP mobile app and the Supabase-backed web portal. The core problem is that the existing sync infrastructure was built against a Railway backend that no longer exists, using a custom JWT identity system incompatible with Supabase GoTrue auth. Nearly all of the mobile-side transformation code (`PortalSyncAdapter`, `PortalSyncDtos`, `PortalMappings`) is already correct and complete — the gap is in the plumbing: `SyncManager` never calls the adapter, auth produces the wrong token format, the endpoint URLs point nowhere, and the portal has no Edge Functions to receive mobile data. The database schema is also missing several tables and columns that the mobile DTOs already reference.

The recommended approach is a layered build-out: portal DB schema migrations first (unblocks everything), then Supabase Edge Functions (the sync endpoints), then mobile auth migration (swap Railway custom JWT for GoTrue), then wire `SyncManager` to the already-written adapter and point it at the new Edge Functions. This ordering is strict — each phase is a hard dependency for the next. No new KMP dependencies are needed; raw Ktor HTTP calls to Supabase Auth REST and Edge Function URLs are sufficient and already the established pattern in this codebase. Adding `supabase-kt` would introduce version conflicts and is explicitly an anti-pattern here.

The primary risks are auth correctness (two identity systems must be cleanly cut over, not merged), telemetry payload size (a full workout's rep data can reach 16 MB — well over Edge Function limits), and bidirectional merge semantics (pull sync must not overwrite portal-sourced data with stale mobile values). All three risks have clear mitigations documented in the pitfalls research and must be addressed in their respective phases, not deferred.

## Key Findings

### Recommended Stack

No new KMP dependencies are needed. The existing Ktor HTTP client with `kotlinx.serialization` is sufficient for all Supabase interactions — auth, push, and pull. The Supabase Auth REST API is two endpoints (`/auth/v1/token` for sign-in and refresh); the Edge Functions are standard HTTPS POST calls with a Bearer token. On the portal side, two new Deno Edge Functions are written in TypeScript using the existing `@supabase/supabase-js` v2 JSR import pattern already used by `process-sync-queue`.

See `.planning/research/STACK.md` for full details.

**Core technologies:**
- Ktor HTTP client (already present): Supabase Auth REST calls + Edge Function calls — no SDK needed, already proven in codebase
- `kotlinx.serialization` (already present): JSON serialization for all payloads — Supabase expects standard JSON
- Supabase GoTrue REST API: mobile auth sign-in, refresh, user identity — replaces Railway custom JWT
- Supabase Edge Functions (Deno/TypeScript): `mobile-sync-push` and `mobile-sync-pull` — the only new server-side artifacts
- Supabase PostgreSQL + RLS: existing portal DB with 3 new SQL migration files
- `multiplatform-settings` / `PortalTokenStorage` (already present): token persistence — extend to store `access_token`, `refresh_token`, and `expires_at`

**Critical version constraint:** Do NOT add `supabase-kt` — it requires Kotlin 2.3.10 and this project is on 2.3.0; adding it causes dependency conflicts with the existing Ktor setup.

**Edge Function size constraint:** Rep telemetry must not be included in the main sync payload. A full workout can produce 16 MB of JSON, exceeding the practical ~10 MB Edge Function body limit and the 2-second CPU cap.

### Expected Features

See `.planning/research/FEATURES.md` for full feature table with complexity ratings.

**Must have — the 12 compatibility issues (v0.6.0):**
- Supabase Auth on mobile (sign-in + refresh token storage + 401 auto-refresh) — nothing else works without this
- SyncManager wired to PortalSyncAdapter (adapter already written, just not called)
- `user_id` on all nested push DTOs (sets, rep_summaries, rep_telemetry) — injected server-side from JWT, not from DTO
- Duration unit fix verified (ms to seconds — already in adapter, needs wiring confirmation)
- Mode display mapping in portal UI (`OLD_SCHOOL` to "Old School" etc.)
- `mobile-sync-push` Edge Function (dual-client pattern: user JWT to identify, service_role to write)
- `mobile-sync-pull` Edge Function (user JWT for RLS-filtered reads, delta by `lastSync`)
- DB migrations: `routine_exercises` advanced columns, `workout_mode` on sets, `routine_session_id` on workout_sessions
- DB migrations: `rpg_attributes`, `earned_badges`, `gamification_stats` tables
- Gamification data (RPG attributes, badges, stats) in push payload and Edge Function upsert logic
- `exercise_progress` computed in `sync-push` Edge Function after set inserts (not mobile pre-computed)

**Should have (add in v0.6.0 if time allows, else v0.6.1):**
- `PortalPullAdapter` — reverse adapter for portal-native DTOs back to mobile models (pull sync is lower priority than push stabilization)
- Pagination for large pull history (only matters for users with 500+ sessions)
- Retry with exponential backoff in `PortalApiClient`
- Proactive token refresh at sync start (before the 1-hour expiry, not just on 401)

**Defer to v0.6.1+:**
- Rep telemetry in sync payload (requires separate chunked upload path)
- Pull gamification updates from portal
- Biometric re-auth
- Rep-level velocity in exercise_progress

**Never build (anti-features):**
- Real-time streaming sync during workout — BLE + sync contention is a safety risk
- WebSocket/Realtime for sync — batch REST is correct for workout data
- supabase-kt dependency — version conflict, raw Ktor covers 100% of the needed surface

**Conflict resolution strategy (important for roadmap):**
- Workout sessions: mobile is authoritative (immutable once completed, never flow portal-to-mobile)
- Routines: last-write-wins by `updatedAt` timestamp (only true bidirectional entity)
- PRs: append-only union (PRs from either side are merged, never deleted)
- RPG attributes / gamification stats: mobile is authoritative (portal stores what mobile sends)
- Badges: union by `(user_id, badge_id)` uniqueness

### Architecture Approach

The architecture is a clean separation between mobile transformation logic and portal persistence logic. Mobile owns data collection and transformation (via the already-complete `PortalSyncAdapter`); the portal owns data storage and serves as the source for pull sync. Edge Functions act as the security boundary: they validate JWTs, inject `user_id` server-side, and use service_role for writes while returning RLS-filtered data for reads. The existing `SyncTriggerManager` orchestration is correct and unchanged.

See `.planning/research/ARCHITECTURE.md` for full component diagram and data flow.

**Major components:**
1. `PortalApiClient` (MODIFY) — swap Railway URLs for Supabase Edge Function URLs; add Supabase Auth REST methods; add 401-triggered refresh
2. `PortalTokenStorage` (MODIFY) — add `KEY_REFRESH_TOKEN`, `KEY_TOKEN_EXPIRY`; populate `KEY_USER_ID` from GoTrue JWT sub claim
3. `SyncManager.pushLocalChanges()` (MODIFY) — replace `SyncPushRequest` with `PortalSyncAdapter` + `PortalSyncPayload`; atomic URL + format change in one commit
4. `mobile-sync-push` Edge Function (NEW) — dual-client pattern; validates JWT, injects `user_id`, upserts all tables with service_role; computes `exercise_progress` after set inserts
5. `mobile-sync-pull` Edge Function (NEW) — user JWT client for RLS-filtered reads; delta by `lastSync`; returns portal-native DTOs
6. Portal DB migrations (NEW) — 3 migration files: gamification tables, routine_exercises advanced cols, workout_sessions/sets missing cols
7. `PortalPullAdapter` (NEW) — reverse adapter from portal-native pull DTOs to mobile merge DTOs; deferred to after push stabilizes
8. `SqlDelightSyncRepository` (MODIFY) — add `getSessionsWithRepsSince()`, `getRpgAttributesForSync()`, `getEarnedBadgesForSync()`, `getGamificationStatsForSync()`

**Key patterns:**
- Dual-client pattern in Edge Functions: user JWT client for identity, service_role client for writes
- User ID always extracted from JWT (never trusted from request body) — prevents cross-user data injection
- Separate Edge Functions for push and pull — different RLS implications, different failure modes, independent retry
- `PortalSyncAdapter` as the single transformation layer — already written, only the call site changes
- Mobile-safe CORS helper in Edge Functions — native clients don't send Origin headers; must not fail on absent Origin

### Critical Pitfalls

See `.planning/research/PITFALLS.md` for all 9 pitfalls with full recovery strategies and phase mappings.

1. **Supabase GoTrue JWT vs custom token mismatch** — every Edge Function call will 401 until auth is migrated. On first launch after update, detect stale non-GoTrue tokens and force re-login rather than sending them. Wire auth to `/auth/v1/token?grant_type=password` before any other sync work. Address: Phase 1.

2. **Missing refresh token storage causes hourly forced re-login** — `PortalTokenStorage` currently stores only one token string; GoTrue tokens expire in 1 hour; background refresh is not automatic on KMP. Store both `access_token` and `refresh_token`; add 401-intercept-and-retry in `PortalApiClient`. Must be built alongside auth migration, not deferred. Address: Phase 1.

3. **Legacy flat DTO (`SyncPushRequest`) must be replaced atomically** — changing only the URL while leaving the body format as `SyncPushRequest` will produce 400 errors from the Edge Function. The URL change and the `PortalSyncPayload` wiring must land in the same commit. Address: Phase 2.

4. **Missing INSERT RLS policies on child tables** — `exercises`, `sets`, `rep_summaries`, `rep_telemetry` have SELECT policies via JOIN but no INSERT policies in the base schema. Edge Function inserts will fail with `42501` errors on these tables. Add INSERT policies in the DB migration phase; review every table before writing Edge Function upsert logic. Address: Phase 1 (migrations) + Phase 2 (Edge Function review).

5. **Rep telemetry payload exceeds Edge Function limits** — 16 MB for a full workout; practical limit ~10 MB. Exclude telemetry from the main sync payload entirely. Make telemetry a separate, WiFi-gated, chunked background upload (v0.6.1). Address: Phase 2 (architectural decision before writing sync-push).

6. **Pull sync overwrites portal-sourced data** — naive `INSERT OR REPLACE` semantics on pull will overwrite records created in the portal web UI. Implement timestamp-based merge: update local only if `portal.updatedAt > local.updatedAt`; never restore soft-deleted local records from portal pull. Address: Phase 3 (define merge strategy before implementing).

7. **CORS helper breaks mobile-facing Edge Functions** — existing `_shared/cors.ts` fails when `Origin` is absent (native clients). Create a `getMobileCorsHeaders()` helper that passes through when no Origin is present. Address: Phase 2 (before writing any sync Edge Functions).

## Implications for Roadmap

The cross-repo dependency structure dictates a strict phase order. Portal infrastructure must exist before mobile can be tested end-to-end. Auth must be correct before any Edge Function calls succeed. The research identifies 5-6 natural phases.

### Phase 1: Portal DB Foundation + RLS Cleanup
**Rationale:** Every other phase is blocked until the DB schema is correct and INSERT policies exist on all tables. This is zero-risk, no-app-code work that can ship independently.
**Delivers:** Complete portal schema for v0.6.0 data model; INSERT RLS policies on child tables; gamification tables created
**Addresses:** DB Schema category (all 6 items), Missing INSERT policies pitfall (Pitfall 4)
**Implements:** 3 migration files (gamification tables, routine_exercises advanced cols, workout_sessions/sets cols); INSERT policies on exercises/sets/rep_summaries/rep_telemetry
**Avoids:** Schema migration NOT NULL failure (Pitfall 8) — all new columns added as nullable with defaults; no table rewrites

### Phase 2: Supabase Auth Migration (Mobile)
**Rationale:** Auth is the dependency for every Edge Function call. Cannot test push or pull until mobile produces valid GoTrue JWTs. This phase is self-contained mobile work that does not require portal Edge Functions to be deployed yet.
**Delivers:** Mobile app authenticates via Supabase GoTrue; stores access_token + refresh_token + expires_at + user_id; silently refreshes on 401
**Addresses:** Auth category (all 5 table-stakes items); Supabase anon key in BuildConfig (not service_role)
**Implements:** `PortalApiClient` auth methods; `PortalTokenStorage` new keys; `PortalAuthRepository` swap; `SupabaseAuthResponse` DTO; `SyncModels` deprecate legacy auth DTOs
**Avoids:** Custom JWT vs GoTrue JWT mismatch (Pitfall 2); Missing refresh token (Pitfall 3); hourly forced re-login

### Phase 3: Edge Functions (Portal)
**Rationale:** Depends on Phase 1 (schema exists) and can proceed in parallel with Phase 2. Edge Functions must exist before mobile push/pull can be tested end-to-end.
**Delivers:** `mobile-sync-push` and `mobile-sync-pull` deployed to Supabase; `exercise_progress` computed server-side; gamification data persisted; mode display mapping in portal UI
**Addresses:** Edge Function category (all table-stakes items); Category 7 (exercise progress); Category 6 (gamification push path)
**Implements:** `mobile-sync-push/index.ts` (dual-client, service_role writes, user_id from JWT, exercise_progress computation); `mobile-sync-pull/index.ts` (user JWT, RLS-filtered, delta by lastSync); `_shared/mobile-cors.ts` (mobile-safe CORS helper)
**Avoids:** Legacy flat DTO sent to new endpoint (Pitfall 7) — Edge Function designed for PortalSyncPayload from day one; CORS blocks mobile client (Pitfall 1); user_id trusted from DTO body (Pitfall 3/Architecture anti-pattern 3); rep telemetry in main payload (Pitfall 5) — telemetry excluded from sync-push scope

### Phase 4: Mobile Push Wire-Up
**Rationale:** Depends on Phase 2 (valid auth tokens) and Phase 3 (Edge Function endpoint exists). This is the largest single mobile change: replacing the entire push path.
**Delivers:** `SyncManager.pushLocalChanges()` uses `PortalSyncAdapter` + `PortalSyncPayload`; gamification data included in push; workout sessions appear in portal after mobile workout
**Addresses:** Push Sync category (all table-stakes items); user_id on nested DTOs (injected by Edge Function, not DTO); duration unit fix verified
**Implements:** `SyncManager.pushLocalChanges()` refactor; `SyncRepository` new interface methods; `SqlDelightSyncRepository` implementations; `SyncModule` Supabase URL config; URL + format change in one atomic commit
**Avoids:** Legacy flat DTO atomic cutover (Pitfall 7) — must be a single commit; one giant Edge Function (Architecture anti-pattern 4 — already avoided by Phase 3 split design

### Phase 5: Mobile Pull Wire-Up + Merge Strategy
**Rationale:** Pull sync is lower priority than push (sessions originate on mobile; pull is only needed for portal-created routines and portal-awarded badges). Depends on Phase 3 (Edge Function exists) and Phase 4 (push validated). Bidirectional merge semantics must be defined before writing any merge code.
**Delivers:** Mobile downloads portal-created routines, PRs, gamification updates; `PortalPullAdapter` (reverse adapter); timestamp-based merge in `mergeSessions()`, `mergeRoutines()`
**Addresses:** Pull Sync category (all table-stakes items); gamification pull (Category 6 differentiator)
**Implements:** `PortalPullResponseDtos.kt` (new file); `PortalPullAdapter` reverse mapping; `SyncManager.pullRemoteChanges()` refactor; merge conflict resolution in SqlDelightSyncRepository
**Avoids:** Pull sync overwrites portal data (Pitfall 6) — timestamp merge required, defined before implementation begins; restoring soft-deleted local records from pull

### Phase 6: Integration Validation + Deployment
**Rationale:** Cross-repo deployment order is a real risk (old mobile hits new Edge Function, or vice versa). Must follow a defined runbook.
**Delivers:** End-to-end verified sync; deployment runbook documented; "looks done but isn't" checklist completed
**Addresses:** Deployment order mismatch (Pitfall 9); all items in PITFALLS.md "Looks Done But Isn't" checklist
**Implements:** Integration tests for push (verify in Supabase dashboard) and pull (verify merged into SQLDelight); deployment order: schema → Edge Functions → mobile release
**Avoids:** New mobile syncing against undeployed Edge Function; old mobile breaking against new Edge Function (old path already broken so no regression)

### Phase Ordering Rationale

- **Schema before code:** Edge Function inserts will 500-error on missing columns/tables. DB migrations are low-risk, high-unlock value.
- **Auth before sync:** GoTrue JWT is required for every Edge Function call. Auth correctness is a prerequisite, not a parallel track.
- **Push before pull:** Sessions originate on mobile; portal analytics only work when push is flowing. Pull is needed for routines and badges — important but not blocking portal value.
- **Edge Functions before mobile push wire-up:** Mobile needs a working endpoint to test against. The adapter is already written; the endpoint was the missing piece.
- **Merge strategy defined before implementation:** Bidirectional merge bugs (portal data overwritten) are HIGH recovery cost. Defining the strategy in writing before code is written is non-negotiable.
- **Telemetry is Phase 7+ (v0.6.1):** Payload size risk is too high to include in v0.6.0. Rep summaries (already synced) are sufficient for portal analytics; force curves are a premium feature.

### Research Flags

Phases needing deeper per-phase research during planning:
- **Phase 5 (Pull Merge Strategy):** Bidirectional merge conflict resolution is nuanced. `mergeRoutines()` needs LWW logic; `mergeSessions()` needs insert-only semantics. Recommend a focused research spike on the SQLDelight merge layer before implementation begins.
- **Phase 6 (Deployment Runbook):** Supabase Edge Function deployment via CI (if any) and the exact migration apply process against production needs confirmation. `supabase db push` vs `supabase migration up` semantics.

Phases with standard, well-documented patterns (skip research-phase):
- **Phase 1 (DB Migrations):** Standard PostgreSQL `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` + RLS policies. Pattern is identical to `20260228_rls_denormalization.sql` already in the codebase.
- **Phase 2 (Auth Migration):** Supabase Auth REST API is well-documented; raw Ktor call pattern is already established in `PortalApiClient`. Two endpoints, ~30 lines of new code.
- **Phase 3 (Edge Functions):** The existing `process-sync-queue/index.ts` provides an identical structural template. Dual-client pattern is documented by Supabase.
- **Phase 4 (Push Wire-Up):** Adapter already written and correct; only the call site changes in SyncManager.

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | Both codebases inspected directly; supabase-kt conflict confirmed against version requirements; Ktor pattern verified in existing PortalApiClient |
| Features | HIGH | Both codebases read directly; 12 compatibility issues traced to specific files and DTOs; gap analysis grounded in actual schema and code |
| Architecture | HIGH | Data flow traced through actual files; Edge Function pattern verified against existing process-sync-queue; Koin DI graph inspected |
| Pitfalls | HIGH | Verified against official Supabase docs for limits and auth semantics; RLS INSERT policy gap confirmed by reading base schema SQL |

**Overall confidence:** HIGH

### Gaps to Address

- **`updated_at` on `workout_sessions` in portal schema:** The base schema (`00002_base_schema.sql`) does not include `updated_at` on `workout_sessions`. Bidirectional timestamp-based merge for pull sync requires this column. A fourth migration file may be needed, or the conflict resolution strategy for sessions may need to be simplified (mobile-only append, no portal-to-mobile session merge). Clarify before Phase 5 begins.

- **`exercises` table INSERT RLS policy:** The base schema has only a SELECT policy via JOIN for `exercises`. Confirm during Phase 1 whether this needs an INSERT policy for the Edge Function's service_role writes (service_role bypasses RLS, so this may not block push — but needs to be confirmed explicitly rather than assumed).

- **Telemetry upload strategy for v0.6.1:** The decision to exclude telemetry from v0.6.0 is correct, but the chunked-per-set upload architecture needs design before v0.6.1 begins. The `sync-telemetry` Edge Function and the WiFi-gating mechanism on mobile are not designed yet.

- **Token storage security posture:** `multiplatform-settings` (SharedPreferences on Android) is accepted for v0.6.0. If a security review is triggered before v0.7, the expect/actual Keystore wrapper pattern needs to be designed.

- **Supabase project credentials in mobile BuildConfig:** The `anon_key` and project URL must be in `BuildConfig` (not hardcoded). The mechanism for injecting these (local.properties + buildConfigField) should be confirmed before Phase 2 implementation begins.

## Sources

### Primary (HIGH confidence)
- Direct codebase inspection: `PortalApiClient.kt`, `SyncManager.kt`, `PortalSyncAdapter.kt`, `PortalSyncDtos.kt`, `PortalTokenStorage.kt`, `SyncModels.kt`, `SyncTriggerManager.kt`, `di/SyncModule.kt`
- Direct portal inspection: `supabase/migrations/00002_base_schema.sql`, `20260217_phase10_tables.sql`, `20260221_exercise_progress_and_creator_stats.sql`, `20260228_rls_denormalization.sql`, `supabase/functions/process-sync-queue/index.ts`, `supabase/functions/_shared/cors.ts`
- [Supabase Edge Functions Limits](https://supabase.com/docs/guides/functions/limits) — CPU 2s, wall clock 150s, memory 256MB, practical body ~10MB
- [Supabase Auth Sessions](https://supabase.com/docs/guides/auth/sessions) — 1-hour access token, refresh token mechanics
- [Supabase RLS Performance](https://supabase.com/docs/guides/troubleshooting/rls-performance-and-best-practices-Z5Jjwv) — multi-hop JOIN anti-pattern; denormalization rationale
- [Supabase Edge Functions Auth](https://supabase.com/docs/guides/functions/auth) — JWT verification, dual-client setup

### Secondary (MEDIUM confidence)
- [Supabase Edge Function request size community report](https://github.com/orgs/supabase/discussions/20864) — practical 10MB body limit (community observation, not official doc)
- [GoTrue token refresh on mobile sleep](https://github.com/supabase/supabase/issues/6464) — timer doesn't survive sleep mode (KMP implication)
- [PowerSync offline-first analysis](https://www.powersync.com/blog/bringing-offline-first-to-supabase) — conflict resolution, tombstone patterns

### Tertiary (LOW confidence)
- supabase-kt v3 Kotlin version requirement (2.3.10) — inferred from library changelog; confirm before any future reconsideration of the dependency decision

---
*Research completed: 2026-03-02*
*Ready for roadmap: yes*
