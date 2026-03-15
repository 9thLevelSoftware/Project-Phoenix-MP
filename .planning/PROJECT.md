# Project Phoenix MP

## What This Is

Kotlin Multiplatform app for controlling Vitruvian Trainer workout machines via BLE. Community rescue project to keep machines functional after company bankruptcy. Paired with Phoenix Portal (React + Supabase web companion) for cloud sync, analytics dashboards, community features, and third-party integrations.

## Core Value

Users can connect to their Vitruvian trainer and execute workouts with accurate rep counting, weight control, and progress tracking — reliably, on both platforms.

## Current State: v0.7.0 In Progress

**Branch:** `MVP` (from `new_ideas`): https://github.com/9thLevelSoftware/Project-Phoenix-MP/tree/MVP
**Previous:** v0.6.0 shipped 2026-03-02

**What v0.7.0 ships:**
- User-facing cloud sync UI on Android and iOS (LinkAccountScreen, "Link Portal Account" button)
- iOS Supabase credential injection (Info.plist → PlatformModule.ios.kt)
- Ktor ProGuard rules for release builds
- Sync error indicator in SettingsTab (hasPersistentError)
- Version bump to 0.7.0
- Release builds for Play Store + TestFlight

**What v0.7.0 does NOT include (portal planned separately):**
- Portal deployment, Vercel setup, Edge Function secrets — planned in phoenix-portal repo
- Third-party integrations (Strava/Fitbit/Garmin/Hevy) — portal-only
- No new features — this is about shipping what v0.6.0 built

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

### Active (v0.7.0)

- **SYNC-UI-01**: Enable LinkAccount route and "Link Portal Account" button in mobile app
- **SYNC-UI-02**: Add Ktor ProGuard keep rules to prevent release build crashes
- **SYNC-IOS-01**: Inject Supabase credentials into iOS via Info.plist / NSBundle
- **SYNC-IOS-02**: Verify Ktor Darwin engine for sync HTTP calls on iOS
- **SYNC-IOS-03**: TestFlight deployment pipeline verification
- **SYNC-POLISH-01**: Sync error indicator in SettingsTab (hasPersistentError)
- **SYNC-POLISH-02**: Version bump to 0.7.0 + release builds (Play Store + TestFlight)
- **SYNC-POLISH-03**: End-to-end sync validation (sign up → sync → verify on portal)

### Out of Scope

<!-- Explicit boundaries. Includes reasoning to prevent re-adding. -->

- Portal community features (shared_routines, comments/votes) — Portal-only, no mobile sync needed
- Training cycles — Portal-only, future mobile feature
- External integrations (Strava/Fitbit/Garmin/Hevy) — Portal-only
- iOS CV implementation — Deferred to future milestone
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
<summary>v0.7.0 Key Decisions</summary>

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| Branch from new_ideas, not main | Sync infrastructure (phases 23-28) lives on new_ideas; cherry-picking not feasible | In Progress |
| Android + iOS together | Both platforms ship with working sync in same milestone | In Progress |
| Mobile-only milestone | Portal has its own planning in phoenix-portal repo | In Progress |
| Launch + quick wins scope | Ship existing sync + polish items, no new features | In Progress |
| Strava + Hevy live, Fitbit/Garmin Coming Soon | Strava/Hevy are instant approval; Fitbit (1-3 wk) and Garmin (2-6 wk) gate on portal side | In Progress |

</details>

---
*Last updated: 2026-03-15 — v0.7.0 MVP Cloud Sync initialized*
