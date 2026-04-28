# Project Phoenix MP

## What This Is

Kotlin Multiplatform app for controlling Vitruvian Trainer workout machines via BLE. Community rescue project to keep machines functional after company bankruptcy. Paired with Phoenix Portal (React + Supabase web companion) for cloud sync, analytics dashboards, community features, and third-party integrations.

## Core Value

Users can connect to their Vitruvian trainer and execute workouts with accurate rep counting, weight control, and progress tracking — reliably, on both platforms.

## Current State: v0.9.0 Enhancement Sweep (Complete)

**Branch:** feat/v0.9.0-enhancement-sweep
**Previous:** v0.8.0 shipped 2026-04-12, v0.7.0 shipped 2026-03-15

**What v0.9.0 shipped:**
- Exercise group management (muscle group organization, exercise metadata)
- Weight display layer (cable-aware formatting, per-cable → total conversion)
- Routine enhancements (superset/circuit support, group ID threading, AMRAP/PR-scaling)
- Analytics engine (volume tracking, PR history, strength trends)
- Haptic/audio feedback system (workout cues, PR celebrations, rep counting audio)
- Platform hardening (iOS bookmark handling, error propagation, SavedStateHandle scaffolding)
- Sound system (Android SoundPool + Fire OS fallback, iOS AVAudioPlayer framework)
- Cross-feature integration validation (1,682 tests, 0 regressions)

**What v0.9.0 does NOT include:**
- PersonalRecord.cableCount for accurate PR weight display (planned for tech debt pass)
- iOS .caf sound file bundling (requires macOS build environment)
- Server-side subscription validation (client-side FeatureGate accepted for pre-launch)

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
- ✓ BLE reliability, sync integrity, lifecycle/security, iOS parity (v0.8.0)
- ✓ Enhancement sweep: groups, weight display, routines, analytics, haptics, sounds, platform hardening (v0.9.0)

### Active (Post v0.9.0)

No active milestone. Tech debt resolution in progress on feat/v0.9.0-enhancement-sweep branch.

Pending next milestone:
- PersonalRecord.cableCount for accurate PR weight display
- iOS .caf sound file bundling (requires macOS build)
- Server-side subscription validation

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
| Single milestone for all 29 findings | Clean version boundary; "no beta until fixed" framing | Shipped |
| Phase by subsystem (BLE/Sync/Lifecycle/iOS) | Changes within each phase touch related files; minimizes cross-cutting risk | Shipped |
| H6 included despite cross-repo | Keeps all sync integrity fixes together; cross-repo established in v0.6.0 | Shipped |
| H8 SavedStateHandle deferred to v0.9.0 | Architectural refactor too risky for bug-fix milestone; foreground service mitigates | Shipped |
| 3 plans per phase | Blockers + high + medium/cleanup per phase; 15 plans total matches project conventions | Shipped |
| Guided + Deep Analysis workflow | Step-by-step with plan approval; deep analysis for interconnected BLE and sync fixes | Shipped |

</details>

<details>
<summary>v0.9.0 Key Decisions</summary>

| Decision | Rationale | Outcome |
|----------|-----------|---------|
| 8 phases for 12 GitHub issues | Group related enhancements to minimize cross-cutting risk | Shipped |
| Wave 1 build / Wave 2 test split | Prevent test agents from blocking build; tests reference actual output | Shipped |
| Dynamic review panels (3-4 reviewers) | Caught 2 data-loss blockers that 2-person review missed | Shipped |
| SchemaManifest validator | Catch unprovisioned DB columns at build time | Shipped |
| DWSMTestHarness standardization | 22-dependency test harness made integration tests feasible | Shipped |
| Phase 40 cross-repo portal fix | total_volume double-multiplication caught and fixed in-milestone | Shipped |

</details>

---
*Last updated: 2026-04-28 — v0.9.0 Enhancement Sweep complete, tech debt resolution*
