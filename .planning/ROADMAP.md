# Roadmap: Project Phoenix MP

## Milestones

- ✅ **v0.4.1 Architectural Cleanup** — Phases 1-4 (shipped 2026-02-13)
- ✅ **v0.4.5 Premium Features Phase 1** — Phases 1-5 (shipped 2026-02-14)
- ✅ **v0.4.6 Biomechanics MVP** — Phases 6-8 (shipped 2026-02-15)
- ✅ **v0.4.7 Mobile Platform Features** — Phases 9-12 (shipped 2026-02-15)
- ✅ **v0.5.0 Premium Mobile** — Phases 13-15 (shipped 2026-02-27)
- ✅ **v0.5.1 Board Polish & Premium UI** — Phases 16-22 (shipped 2026-02-28)
- ✅ **v0.6.0 Portal Sync Compatibility** — Phases 23-28 (shipped 2026-03-02)
- 🔄 **v0.7.0 MVP Cloud Sync** — Phases 29-31 (in progress)

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

### v0.7.0 MVP Cloud Sync (Phases 29-31)

**Branch:** `MVP` — https://github.com/9thLevelSoftware/Project-Phoenix-MP/tree/MVP
**Scope:** Mobile-only. Portal planned separately in phoenix-portal repo.
**Source:** `.planning/exploration-mvp-cloud-sync.md`, `docs/plans/mvp-cloud-sync-mobile.md`

- [ ] Phase 29: Core Sync UI (2 plans) — Enable sync UI on both platforms, ProGuard fix
- [ ] Phase 30: iOS Sync Launch (2 plans) — Credential injection, Darwin engine verification, TestFlight
- [ ] Phase 31: Polish & Validation (3 plans) — Error indicator, version bump, release builds, E2E test

### Phase 29: Core Sync UI
**Goal**: Enable the user-facing sync experience that's been built but commented out since v0.6.0
**Requirements**: SYNC-UI-01, SYNC-UI-02
**Recommended Agents**: Senior Developer, Frontend Developer
**Success Criteria**:
- LinkAccount route is navigable from Settings → "Link Portal Account"
- LinkAccountScreen shows login/signup/sync controls
- Release build (Android) does not crash on sync due to ProGuard stripping
- Debug build compiles cleanly on both Android and iOS
**Plans**: 2

### Phase 30: iOS Sync Launch
**Goal**: Make cloud sync functional on iOS by injecting Supabase credentials and verifying the Ktor Darwin HTTP engine
**Requirements**: SYNC-IOS-01, SYNC-IOS-02, SYNC-IOS-03
**Recommended Agents**: Mobile App Builder, Senior Developer
**Success Criteria**:
- PlatformModule.ios.kt reads SupabaseConfig from Info.plist (not hardcoded)
- iOS app can authenticate with Supabase GoTrue (login + signup)
- iOS app can push a workout session and pull routines via Edge Functions
- TestFlight build uploaded and accessible to beta testers
**Plans**: 2

### Phase 31: Polish & Validation
**Goal**: Add sync status visibility, bump version, build releases for both platforms, and validate end-to-end sync
**Requirements**: SYNC-POLISH-01, SYNC-POLISH-02, SYNC-POLISH-03
**Recommended Agents**: Senior Developer, Frontend Developer, Evidence Collector
**Success Criteria**:
- Sync error indicator visible in SettingsTab when hasPersistentError is true
- versionName = "0.7.0", versionCode incremented
- Signed release APK/AAB builds successfully
- TestFlight build submitted
- E2E test: sign up on portal → sign in on mobile → complete workout → sync → verify data on portal dashboard
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
| v0.7.0 MVP Cloud Sync | 29-31 | 7 | In Progress | — |

**Last phase number:** 31

---
*Last updated: 2026-03-15 — v0.7.0 initialized*
