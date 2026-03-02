# Roadmap: Project Phoenix MP

## Milestones

- ✅ **v0.4.1 Architectural Cleanup** — Phases 1-4 (shipped 2026-02-13)
- ✅ **v0.4.5 Premium Features Phase 1** — Phases 1-5 (shipped 2026-02-14)
- ✅ **v0.4.6 Biomechanics MVP** — Phases 6-8 (shipped 2026-02-15)
- ✅ **v0.4.7 Mobile Platform Features** — Phases 9-12 (shipped 2026-02-15)
- ✅ **v0.5.0 Premium Mobile** — Phases 13-15 (shipped 2026-02-27)
- ✅ **v0.5.1 Board Polish & Premium UI** — Phases 16-22 (shipped 2026-02-28)
- 🔄 **v0.6.0 Portal Sync Compatibility** — Phases 23-28 (in progress)

## Phases

**Phase Numbering:**
- Integer phases (1, 2, 3): Planned milestone work
- Decimal phases (16.1, 16.2): Urgent insertions (marked with INSERTED)

Decimal phases appear between their surrounding integers in numeric order.

<details>
<summary>✅ v0.4.1 Architectural Cleanup (Phases 1-4) — SHIPPED 2026-02-13</summary>

See `.planning/milestones/v0.4.1-*` for archived phase details.

</details>

<details>
<summary>✅ v0.4.5 Premium Features Phase 1 (Phases 1-5) — SHIPPED 2026-02-14</summary>

See `.planning/milestones/v0.4.5-*` for archived phase details.

</details>

<details>
<summary>✅ v0.4.6 Biomechanics MVP (Phases 6-8) — SHIPPED 2026-02-15</summary>

See `.planning/milestones/v0.4.6-*` for archived phase details.

</details>

<details>
<summary>✅ v0.4.7 Mobile Platform Features (Phases 9-12) — SHIPPED 2026-02-15</summary>

See `.planning/milestones/v0.4.7-*` for archived phase details.

</details>

<details>
<summary>✅ v0.5.0 Premium Mobile (Phases 13-15) — SHIPPED 2026-02-27</summary>

See `.planning/milestones/v0.5.0-*` for archived phase details.

</details>

<details>
<summary>✅ v0.5.1 Board Polish & Premium UI (Phases 16-22) — SHIPPED 2026-02-28</summary>

- [x] Phase 16: Foundation & Board Conditions (2/2 plans) — FeatureGate entries, versionName, UTC fix, backup exclusion, camera rationale (completed 2026-02-27)
- [x] Phase 17: WCAG Accessibility (2/2 plans) — Color-blind mode toggle, secondary visual signals (completed 2026-02-28)
- [x] Phase 18: HUD Customization (2/2 plans) — Preset-based HUD page visibility (completed 2026-02-28)
- [x] Phase 19: CV Form Check UX & Persistence (3/3 plans) — Toggle UI, real-time warnings, form score persistence (completed 2026-02-28)
- [x] Phase 20: Readiness Briefing (2/2 plans) — ACWR engine, readiness card, Elite tier gate (completed 2026-02-28)
- [x] Phase 21: RPG Attributes (2/2 plans) — Attribute engine, character class, schema v17 (completed 2026-02-28)
- [x] Phase 22: Ghost Racing (3/3 plans) — Ghost engine, dual progress bars, workout lifecycle integration (completed 2026-02-28)

See `.planning/milestones/v0.5.1-*` for archived phase details.

</details>

### v0.6.0 Portal Sync Compatibility (Phases 23-28)

**Cross-repo milestone.** Each phase lists the target repo(s): mobile (`Project-Phoenix-MP`) or portal (`phoenix-portal`) or both.

- [x] **Phase 23: Portal DB Foundation + RLS** — Schema migrations and INSERT RLS policies that unblock every other phase *(portal)* (completed 2026-03-02)
- [x] **Phase 24: Supabase Auth Migration** — Replace custom JWT with GoTrue; mobile can now produce valid tokens for Edge Functions *(mobile)* (completed 2026-03-02)
- [x] **Phase 25: Edge Functions** — Deploy mobile-sync-push and mobile-sync-pull; portal can now receive and serve workout data *(portal)* (completed 2026-03-02)
- [ ] **Phase 26: Mobile Push Wire-Up** — SyncManager wired to PortalSyncAdapter; workouts flow from mobile to portal *(mobile)*
- [ ] **Phase 27: Mobile Pull Wire-Up** — PortalPullAdapter and merge strategy; portal-created routines and badges flow to mobile *(mobile)*
- [ ] **Phase 28: Integration Validation** — End-to-end verification of bidirectional sync across both repos *(both)*

## Phase Details

### Phase 23: Portal DB Foundation + RLS
**Repo:** phoenix-portal
**Goal:** Portal database has the complete schema required for v0.6.0 data model, with INSERT RLS policies on all tables that Edge Functions will write to
**Depends on:** Nothing (first phase of v0.6.0)
**Requirements:** PORTAL-01, PORTAL-02, PORTAL-03
**Success Criteria** (what must be TRUE):
  1. Supabase dashboard shows `rpg_attributes`, `earned_badges`, and `gamification_stats` tables with correct columns and RLS policies
  2. `routine_exercises` table has all 12 advanced columns (superset_id, superset_color, superset_order, per_set_weights, per_set_rest, is_amrap, pr_percentage, rep_count_timing, stop_at_position, stall_detection, eccentric_load, echo_level) present as nullable columns with no migration failures
  3. INSERT WITH CHECK policies exist on `exercises`, `sets`, `rep_summaries`, and `rep_telemetry` tables and are visible in Supabase Auth policies panel
  4. Running `supabase db push` against the production project applies all 3 migration files with no errors and no data loss on existing rows
**Plans:**
  3/3 plans complete
  - Plan 02 (Wave 1): Mode string data migration (display names → wire format)
  - Plan 03 (Wave 1): Quality fixes on existing sync-compat migrations (auth.uid() wrapper, TO authenticated, column types)

### Phase 24: Supabase Auth Migration
**Repo:** Project-Phoenix-MP (mobile)
**Goal:** Mobile app authenticates via Supabase GoTrue, producing access tokens that portal Edge Functions will accept, with automatic silent refresh so sessions survive restarts and background sync
**Depends on:** Nothing (self-contained mobile work; no deployed Edge Functions needed to test auth against Supabase Auth REST directly)
**Requirements:** AUTH-01, AUTH-02, AUTH-03, FIX-01
**Success Criteria** (what must be TRUE):
  1. User can sign in from the mobile app using email and password; the app stores a GoTrue access_token, refresh_token, expires_at, and user_id (extracted from JWT sub claim)
  2. User can register a new account from mobile; the same credentials work to log in to the Phoenix Portal web UI without a separate sign-up step
  3. After 1 hour of inactivity (or simulated token expiry), the next sync attempt silently refreshes the token and completes without prompting the user to re-login
  4. App restart with a stored session resumes without a login prompt; stored access_token is valid for API calls
  5. `PortalSyncAdapter.estimateRoutineDuration()` returns a value in seconds (not minutes) confirmed by inspecting the push payload in a debug build before sending
**Plans:**
  3/3 plans complete
  - Plan 01 (Wave 1): GoTrue Auth Foundation — config injection, DTOs, API client migration, token storage
  - Plan 02 (Wave 2): Token Lifecycle & Session Persistence — Mutex refresh, 401 retry, app restart, UserProfile linking
  - Plan 03 (Wave 1): FIX-01 Duration Unit Fix — remove / 60 from estimateRoutineDuration()

### Phase 25: Edge Functions
**Repo:** phoenix-portal
**Goal:** Two deployed Supabase Edge Functions act as the sync boundary: mobile-sync-push writes all workout data server-side, mobile-sync-pull returns delta data since a given timestamp, and neither breaks on mobile clients that send no Origin header
**Depends on:** Phase 23 (schema and RLS policies must exist before Edge Functions insert data)
**Requirements:** EDGE-01, EDGE-02, EDGE-03, PORTAL-04
**Success Criteria** (what must be TRUE):
  1. A cURL POST to `mobile-sync-push` with a valid GoTrue Bearer token and a well-formed PortalSyncPayload body returns HTTP 200 and the matching rows appear in Supabase dashboard tables (sessions, exercises, sets, rep_summaries, exercise_progress, personal_records, routines, gamification)
  2. A cURL POST to `mobile-sync-pull` with a valid Bearer token and a `lastSync` timestamp returns HTTP 200 with only records modified after that timestamp (no records from other users visible)
  3. Both Edge Functions return HTTP 200 when called with no Origin header (simulating a native mobile client) — not rejected with CORS error
  4. Portal workout history page displays "Old School", "Eccentric", "Concentric", and other human-readable mode names instead of raw SCREAMING_SNAKE_CASE strings
**Plans:**
  2/2 plans complete
  - Plan 01 (Wave 1): mobile-sync-push Edge Function — JWT auth, hierarchical INSERT, exercise_progress, personal_records
  - Plan 02 (Wave 1): mobile-sync-pull Edge Function — JWT auth, delta queries, nested response assembly

### Phase 26: Mobile Push Wire-Up
**Repo:** Project-Phoenix-MP (mobile)
**Goal:** SyncManager.pushLocalChanges() sends a PortalSyncPayload (built by the already-written PortalSyncAdapter) to the deployed mobile-sync-push Edge Function, so completed workouts appear in the portal automatically
**Depends on:** Phase 24 (valid GoTrue auth token required), Phase 25 (Edge Function endpoint must exist)
**Requirements:** PUSH-01, PUSH-02, PUSH-03, PUSH-04, PUSH-05
**Success Criteria** (what must be TRUE):
  1. After completing a workout on mobile, the session, exercises, and sets appear in the Supabase `workout_sessions` and related tables within 30 seconds of the sync trigger firing
  2. `exercise_progress` rows are present in the portal DB after a push sync (computed server-side by the Edge Function from the inserted set data)
  3. `personal_records` in the portal match the records stored in the mobile app's SQLDelight database for the same user
  4. Rep telemetry is NOT included in the main sync payload; the sync completes without body-size errors even for long workouts with many reps
  5. Gamification data (RPG attributes, earned badges, gamification stats) is present in the portal's gamification tables after a push sync from mobile
**Plans:** TBD

### Phase 27: Mobile Pull Wire-Up
**Repo:** Project-Phoenix-MP (mobile)
**Goal:** Mobile app downloads portal-created routines, PRs, and badges via the sync-pull Edge Function and merges them into the local SQLDelight database using a defined conflict resolution strategy that never overwrites newer local data with stale portal data
**Depends on:** Phase 25 (Edge Function endpoint must exist), Phase 26 (push flow validated first so mobile data is in portal to pull)
**Requirements:** PULL-01, PULL-02, PULL-03
**Success Criteria** (what must be TRUE):
  1. A routine created or modified in the Phoenix Portal web UI appears in the mobile app's routine list after the next pull sync
  2. Badges awarded by the portal (e.g. from a challenge or admin grant) appear in the mobile app's RPG/gamification UI after pull sync
  3. Pull sync does not overwrite a locally-modified routine with an older portal version: if mobile's `updatedAt` is newer than the portal's `updatedAt` for the same routine, the local version is preserved
  4. Running pull sync on a fresh install (empty local DB) populates the app with the user's full routine library from the portal without duplicates or foreign-key errors
**Plans:** TBD

### Phase 28: Integration Validation
**Repo:** Both (Project-Phoenix-MP + phoenix-portal)
**Goal:** End-to-end bidirectional sync is verified to work correctly across both repos in sequence, with a deployment runbook that ensures schema, Edge Functions, and mobile release land in the correct order
**Depends on:** Phase 27 (all sync paths must be implemented before validating the complete flow)
**Requirements:** (none unique — validates delivery of all prior phase requirements end-to-end)
**Success Criteria** (what must be TRUE):
  1. Full round-trip verified: complete a workout on mobile, push sync fires, workout appears in portal dashboard with correct mode names, weight units, and exercise names — no missing fields
  2. Pull sync round-trip verified: create a routine in portal web UI, trigger pull on mobile, routine is usable in the mobile app with all advanced fields preserved
  3. Auth edge cases pass: token expiry during sync triggers silent refresh and sync completes; bad credentials show a clear error rather than a silent failure or crash
  4. Deployment runbook executed in correct order (schema migrations → Edge Functions → mobile release) with no rollback required
**Plans:** TBD

## Progress

| Milestone | Phases | Plans | Status | Shipped |
|-----------|--------|-------|--------|---------|
| v0.4.1 Architectural Cleanup | 1-4 | 10 | Complete | 2026-02-13 |
| v0.4.5 Premium Features Phase 1 | 1-5 | 11 | Complete | 2026-02-14 |
| v0.4.6 Biomechanics MVP | 6-8 | 10 | Complete | 2026-02-15 |
| v0.4.7 Mobile Platform Features | 9-12 | 13 | Complete | 2026-02-15 |
| v0.5.0 Premium Mobile | 13-15 | 7 | Complete | 2026-02-27 |
| v0.5.1 Board Polish & Premium UI | 16-22 | 16 | Complete | 2026-02-28 |
| v0.6.0 Portal Sync Compatibility | 23-28 | 6/TBD | In progress | — |

| Phase | Plans Complete | Status | Completed |
|-------|----------------|--------|-----------|
| 23. Portal DB Foundation + RLS | 3/3 | Complete   | 2026-03-02 |
| 24. Supabase Auth Migration | 3/3 | Complete | 2026-03-02 |
| 25. Edge Functions | 2/2 | Complete | 2026-03-02 |
| 26. Mobile Push Wire-Up | 0/TBD | Not started | - |
| 27. Mobile Pull Wire-Up | 0/TBD | Not started | - |
| 28. Integration Validation | 0/TBD | Not started | - |

**Last phase number:** 28

---
*Last updated: 2026-03-02 — Phase 25 complete*
