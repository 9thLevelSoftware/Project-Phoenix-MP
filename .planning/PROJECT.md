# Project Phoenix MP

## What This Is

Kotlin Multiplatform app for controlling Vitruvian Trainer workout machines via BLE. Community rescue project to keep machines functional after company bankruptcy. Paired with Phoenix Portal (React + Supabase web companion) for cloud sync, analytics dashboards, community features, and third-party integrations.

## Core Value

Users can connect to their Vitruvian trainer and execute workouts with accurate rep counting, weight control, and progress tracking — reliably, on both platforms.

## Current State: v0.8.0 Beta Readiness

**Branch:** TBD (from `MVP`)
**Previous:** v0.7.0 shipped 2026-03-15

**What v0.8.0 ships:**
- BLE reliability fixes: connection, reconnection, state machine hardening, permission UX
- Sync data integrity: profile-scoped queries, PR DTO completeness, chunked first sync, push transaction safety
- Android lifecycle/security: foreground service crash fix, encrypted token storage, exception handling, release build hygiene
- iOS platform parity: schema version fix, missing columns, connectivity checker, feature gating
- Integration validation across all subsystems

**What v0.8.0 does NOT include:**
- SavedStateHandle process death recovery — deferred to v0.9.0 (foreground service mitigates during active workouts)
- New features — this is strictly a stability and correctness pass
- Portal-only changes beyond H6 (sync push transaction fix)

## Requirements

### Validated

<!-- Shipped and confirmed valuable. -->

- ✓ BLE connectivity and workout execution (v0.4.1)
- ✓ Rep counting, weight control, progress tracking (v0.4.1)
- ✓ LED biofeedback, rep quality scoring, smart suggestions (v0.4.5)
- ✓ Biomechanics engine (VBT, force curves, asymmetry) (v0.4.6)
- ✓ Strength assessment, exercise auto-detection, replay cards (v0.4.7)
- ✓ CV form check, biomechanics persistence (v0.5.0)
- ✓ Ghost racing, RPG attributes, readiness briefing, HUD customization (v0.5.1)
- ✓ WCAG accessibility, board conditions (v0.5.1)
- ✓ Portal sync adapter with correct hierarchy/unit conversions (v0.5.1)
- ✓ Bidirectional portal sync via Supabase Edge Functions (v0.6.0)
- ✓ Unified Supabase Auth across mobile and portal (v0.6.0)
- ✓ Cloud sync UI, iOS sync launch, sync polish (v0.7.0)

### Active (v0.8.0)

- **BLE-01**: Fix connectToDevice() dead StateFlow — primary connect path broken (B1)
- **BLE-02**: Wire auto-reconnect flow consumer — reconnection never triggers (B2)
- **BLE-03**: Cancel stale peripheral state observer on reconnect (H2)
- **BLE-04**: Add error handling to onDeviceReady() fire-and-forget launch (H3)
- **BLE-05**: Add scan timeout to startScanning() (H4)
- **BLE-06**: Fix BLE permission denied loop on Android 11+ (H10)
- **BLE-07**: Add .catch{} to permanent init collectors in ActiveSessionEngine (M1)
- **BLE-08**: Increase CONNECTION_RETRY_DELAY_MS from 100ms to 1500ms (M2)
- **SYNC-01**: Add profile_id filter to all sync push queries (B3)
- **SYNC-02**: Add prType/phase/volume to PersonalRecordSyncDto (B4)
- **SYNC-03**: Implement chunked first sync for large histories (H5)
- **SYNC-04**: Wrap routine_exercises push in database transaction (H6, cross-repo)
- **SYNC-05**: Fix updatedAt IS NULL perpetual re-push (M3)
- **SYNC-06**: Handle Instant.parse() failure in SyncManager (M4)
- **LIFE-01**: Fix START_STICKY null intent foreground service crash (B5)
- **LIFE-02**: Encrypt auth tokens with EncryptedSharedPreferences (B6)
- **LIFE-03**: Add top-level exception handler to workoutJob (H1)
- **LIFE-04**: Gate Coil DebugLogger on BuildConfig.DEBUG (H9)
- **LIFE-05**: Fix ActivityHolder WeakReference lifecycle (H11)
- **LIFE-06**: Set allowBackup=false in manifest (M6)
- **LIFE-07**: Uncomment ProGuard log-stripping rules (M7)
- **IOS-01**: Update CURRENT_SCHEMA_VERSION to 22 (B7)
- **IOS-02**: Add formScore column to iOS manual schema (B8)
- **IOS-03**: Implement ConnectivityChecker with NWPathMonitor (H12)
- **IOS-04**: Document/improve withPlatformLock global lock (H13)
- **IOS-05**: Gate Form Check feature behind platform check on iOS (H14)
- **IOS-06**: Verify no Vico chart imports leak to commonMain/iosMain (H15)
- **VAL-01**: BLE connect + reconnect scenario validation
- **VAL-02**: Sync E2E + profile isolation validation
- **VAL-03**: iOS launch + session load + schema validation

### Out of Scope

<!-- Explicit boundaries. Includes reasoning to prevent re-adding. -->

- Portal community features (shared_routines, comments/votes) — Portal-only, no mobile sync needed
- Training cycles — Portal-only, future mobile feature
- External integrations (Strava/Fitbit/Garmin/Hevy) — Portal-only
- iOS CV implementation — Deferred to future milestone (H14 gates the UI in v0.8.0)
- SavedStateHandle process death recovery — Deferred to v0.9.0 (foreground service mitigates)
- Server-side subscription validation — Client-side FeatureGate accepted for pre-launch
- 50Hz ghost telemetry overlay — Performance TBD, separate milestone
- Real-time chat or social features — Not core to sync compatibility

## Context

- Mobile app has full bidirectional sync with portal via Supabase Edge Functions
- Auth uses Supabase GoTrue with automatic token refresh
- Push: SyncManager → PortalSyncAdapter → mobile-sync-push Edge Function
- Pull: mobile-sync-pull Edge Function → PortalPullAdapter → SyncRepository merge
- Merge strategy: sessions push-only, routines LWW by updatedAt, badges union
- Rep telemetry excluded from sync payload (deferred to chunked upload)

## Constraints

- **Tech stack (mobile)**: KMP, Ktor HTTP client, SQLDelight — must stay within existing stack
- **Tech stack (portal)**: React + Supabase, Edge Functions (Deno) — no new backend services
- **Auth**: Supabase GoTrue is the single identity system
- **RLS**: All portal table inserts must satisfy Supabase Row-Level Security policies
- **Backwards compat**: Mobile must handle graceful degradation when portal is unreachable
- **Two repos**: Changes split across Project-Phoenix-MP and phoenix-portal

<details>
<summary>v0.6.0 Key Decisions (archived)</summary>

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Supabase Edge Functions over Railway backend | No backend to maintain; portal already uses Edge Functions; matches existing architecture | Shipped |
| Supabase Auth as unified identity | Single user identity across mobile + portal; portal already uses it | Shipped |
| All 12 issues in v0.6.0 | Full compatibility pass avoids partial sync that could corrupt data | Shipped |
| Plan in mobile repo, execute both | Mobile repo has GSD planning infrastructure; portal doesn't | Shipped |
| Raw Ktor HTTP over supabase-kt | Version conflict with Kotlin 2.3.0; raw HTTP sufficient | Shipped |
| user_id injected server-side from JWT | Never trust client-provided user_id; Edge Functions extract from auth token | Shipped |
| Rep telemetry deferred to v0.6.1 | Body size concerns for long workouts; chunked upload needed | Deferred |

</details>

<details>
<summary>v0.7.0 Key Decisions (archived)</summary>

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Branch from new_ideas, not main | Sync infrastructure (phases 23-28) lives on new_ideas; cherry-picking not feasible | Shipped |
| Android + iOS together | Both platforms ship with working sync in same milestone | Shipped |
| Mobile-only milestone | Portal has its own planning in phoenix-portal repo | Shipped |
| Launch + quick wins scope | Ship existing sync + polish items, no new features | Shipped |
| Strava + Hevy live, Fitbit/Garmin Coming Soon | Strava/Hevy are instant approval; Fitbit (1-3 wk) and Garmin (2-6 wk) gate on portal side | Shipped |

</details>

<details>
<summary>v0.8.0 Key Decisions</summary>

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Single milestone for all 29 findings | Clean version boundary; "no beta until fixed" framing | In Progress |
| Phase by subsystem (BLE/Sync/Lifecycle/iOS) | Changes within each phase touch related files; minimizes cross-cutting risk | In Progress |
| H6 included despite cross-repo | Keeps all sync integrity fixes together; cross-repo established in v0.6.0 | In Progress |
| H8 SavedStateHandle deferred to v0.9.0 | Architectural refactor too risky for bug-fix milestone; foreground service mitigates | In Progress |
| 3 plans per phase | Blockers + high + medium/cleanup per phase; 15 plans total matches project conventions | In Progress |
| Guided + Deep Analysis workflow | Step-by-step with plan approval; deep analysis for interconnected BLE and sync fixes | In Progress |

</details>

---
*Last updated: 2026-03-23 — v0.8.0 Beta Readiness initialized*
