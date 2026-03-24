# Roadmap: Project Phoenix MP

## Milestones

- ✅ **v0.4.1 Architectural Cleanup** — Phases 1-4 (shipped 2026-02-13)
- ✅ **v0.4.5 Premium Features Phase 1** — Phases 1-5 (shipped 2026-02-14)
- ✅ **v0.4.6 Biomechanics MVP** — Phases 6-8 (shipped 2026-02-15)
- ✅ **v0.4.7 Mobile Platform Features** — Phases 9-12 (shipped 2026-02-15)
- ✅ **v0.5.0 Premium Mobile** — Phases 13-15 (shipped 2026-02-27)
- ✅ **v0.5.1 Board Polish & Premium UI** — Phases 16-22 (shipped 2026-02-28)
- ✅ **v0.6.0 Portal Sync Compatibility** — Phases 23-28 (shipped 2026-03-02)
- ✅ **v0.7.0 MVP Cloud Sync** — Phases 29-31 (shipped 2026-03-15)
- **v0.8.0 Beta Readiness** — Phases 32-36 (in progress)

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

<details>
<summary>✅ v0.6.0 Portal Sync Compatibility (Phases 23-28) — SHIPPED 2026-03-02</summary>

- [x] Phase 23: Portal DB Foundation + RLS (3/3 plans) — Schema migrations, INSERT RLS policies, mode wire format, gamification tables (completed 2026-03-02)
- [x] Phase 24: Supabase Auth Migration (3/3 plans) — GoTrue auth, token lifecycle, session persistence, duration fix (completed 2026-03-02)
- [x] Phase 25: Edge Functions (2/2 plans) — mobile-sync-push, mobile-sync-pull, CORS helper (completed 2026-03-02)
- [x] Phase 26: Mobile Push Wire-Up (2/2 plans) — SyncManager → PortalSyncAdapter → Edge Function (completed 2026-03-02)
- [x] Phase 27: Mobile Pull Wire-Up (2/2 plans) — Pull DTOs, PortalPullAdapter, routine/badge merge (completed 2026-03-02)
- [x] Phase 28: Integration Validation (3/3 plans) — 138 tests, deployment runbook, dead code cleanup (completed 2026-03-02)

See `.planning/milestones/v0.6.0-*` for archived phase details.

</details>

<details>
<summary>✅ v0.7.0 MVP Cloud Sync (Phases 29-31) — SHIPPED 2026-03-15</summary>

- [x] Phase 29: Core Sync UI (2/2 plans) — Enable sync UI on both platforms, ProGuard fix (completed 2026-03-15)
- [x] Phase 30: iOS Sync Launch (2/2 plans) — Credential injection, Darwin engine verification, TestFlight (completed 2026-03-15)
- [x] Phase 31: Polish & Validation (4/4 plans) — UX cleanup, sync discoverability, version bump, E2E validation (completed 2026-03-15)

See `.planning/milestones/v0.7.0-*` for archived phase details.

</details>

### v0.8.0 Beta Readiness (Phases 32-36)

**Branch:** TBD (from `MVP`)
**Scope:** 29 audit findings across BLE, Sync, Lifecycle/Security, iOS. One cross-repo fix (H6 in phoenix-portal).
**Source:** `.planning/exploration-beta-readiness-audit.md`
**Workflow:** Guided + Deep Analysis

- [x] Phase 32: BLE Reliability (3/3 plans) — completed 2026-03-23
- [x] Phase 33: Sync & Data Integrity (3/3 plans) — completed 2026-03-23
- [ ] Phase 34: Lifecycle & Security (0/3 plans)
- [ ] Phase 35: iOS Platform Parity (0/3 plans)
- [ ] Phase 36: Integration Validation (0/3 plans)

### Phase 32: BLE Reliability
**Goal**: Fix the BLE connection chain so devices can connect, reconnect, and maintain stable sessions
**Requirements**: BLE-01, BLE-02, BLE-03, BLE-04, BLE-05, BLE-06, BLE-07, BLE-08
**Recommended Agents**: Senior Developer, Mobile App Builder, Evidence Collector
**Success Criteria**:
- Device connection works from scan results screen (B1 fix verified)
- BLE reconnection triggers automatically on unexpected disconnect (B2 fix verified)
- Scan stops after timeout period (H4 fix verified)
- Permission denial shows appropriate action button per Android version (H10 fix verified)
- No uncaught exceptions from BLE collectors or init flows
**Plans**: 3

### Phase 33: Sync & Data Integrity
**Goal**: Fix data corruption paths in sync push/pull and ensure profile isolation
**Requirements**: SYNC-01, SYNC-02, SYNC-03, SYNC-04, SYNC-05, SYNC-06
**Recommended Agents**: Senior Developer, Backend Architect, Evidence Collector
**Success Criteria**:
- Multi-profile sync only pushes active profile's data (B3 fix verified)
- PR records survive round-trip push/pull with correct prType and phase (B4 fix verified)
- First sync completes for users with 6+ months of history (H5 fix verified)
- Routine exercises survive partial sync failures (H6 fix verified)
- No perpetual re-push of already-synced sessions (M3 fix verified)
**Plans**: 3

### Phase 34: Lifecycle & Security
**Goal**: Fix crash paths in Android lifecycle and harden security for beta users
**Requirements**: LIFE-01, LIFE-02, LIFE-03, LIFE-04, LIFE-05, LIFE-06, LIFE-07
**Recommended Agents**: Senior Developer, Security Engineer, Evidence Collector
**Success Criteria**:
- Foreground service handles system restart without crash (B5 fix verified)
- Auth tokens encrypted at rest (B6 fix verified)
- Workout session data preserved on DB/IO error with user notification (H1 fix verified)
- No debug logging in release builds (H9, M7 fixes verified)
- allowBackup=false in manifest (M6 fix verified)
**Plans**: 3

### Phase 35: iOS Platform Parity
**Goal**: Fix iOS-specific runtime failures and document platform feature gaps
**Requirements**: IOS-01, IOS-02, IOS-03, IOS-04, IOS-05, IOS-06
**Recommended Agents**: Mobile App Builder, Senior Developer, Evidence Collector
**Success Criteria**:
- iOS app launches without database wipe (B7 fix verified)
- Session history screen loads on iOS (B8 fix verified)
- Sync fails fast on airplane mode on iOS (H12 fix verified)
- Form Check camera button hidden on iOS (H14 fix verified)
- iOS build compiles with no unresolved references (H15 verified)
**Plans**: 3

### Phase 36: Integration Validation
**Goal**: End-to-end verification that all fixes work together across platforms
**Requirements**: VAL-01, VAL-02, VAL-03
**Recommended Agents**: Evidence Collector, Senior Developer, Mobile App Builder
**Success Criteria**:
- BLE connect + reconnect scenarios pass on physical device
- Sync E2E cycle completes (sign up → sync → verify on portal)
- Multi-profile sync isolation verified
- iOS cold launch + session load verified
- No regressions in existing unit tests
- Release build (Android) and TestFlight build (iOS) compile and run
**Plans**: 3

## Progress

| Milestone | Phases | Plans | Status | Shipped |
|-----------|--------|-------|--------|---------|
| v0.4.1 Architectural Cleanup | 1-4 | 10 | Complete | 2026-02-13 |
| v0.4.5 Premium Features Phase 1 | 1-5 | 11 | Complete | 2026-02-14 |
| v0.4.6 Biomechanics MVP | 6-8 | 10 | Complete | 2026-02-15 |
| v0.4.7 Mobile Platform Features | 9-12 | 13 | Complete | 2026-02-15 |
| v0.5.0 Premium Mobile | 13-15 | 7 | Complete | 2026-02-27 |
| v0.5.1 Board Polish & Premium UI | 16-22 | 16 | Complete | 2026-02-28 |
| v0.6.0 Portal Sync Compatibility | 23-28 | 13 | Complete | 2026-03-02 |
| v0.7.0 MVP Cloud Sync | 29-31 | 8 | Complete | 2026-03-15 |
| v0.8.0 Beta Readiness | 32-36 | 15 | In Progress | — |

**Last phase number:** 36

---
*Last updated: 2026-03-23 — v0.8.0 Beta Readiness initialized*
