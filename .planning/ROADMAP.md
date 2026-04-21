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
- ✅ **v0.8.0 Beta Readiness** — Phases 32-36 (shipped 2026-03-24)

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

<details>
<summary>✅ v0.8.0 Beta Readiness (Phases 32-36) — SHIPPED 2026-03-24</summary>

- [x] Phase 32: BLE Reliability (3/3 plans) — B1/B2 blockers, H2-H4/H10 high, M1/M2 medium (completed 2026-03-23)
- [x] Phase 33: Sync & Data Integrity (3/3 plans) — B3/B4 blockers, H5/H6 high, M3/M4 medium (completed 2026-03-23)
- [x] Phase 34: Lifecycle & Security (3/3 plans) — B5/B6 blockers, H1/H9/H11 high, M6/M7 medium (completed 2026-03-23)
- [x] Phase 35: iOS Platform Parity (3/3 plans) — B7/B8 blockers, H12-H15 high (completed 2026-03-24)
- [x] Phase 36: Integration Validation (3/3 plans) — VAL-01/02/03 E2E verification (completed 2026-03-24)

See `.planning/milestones/v0.8.0-*` for archived phase details.

</details>

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
| v0.8.0 Beta Readiness | 32-36 | 15 | Complete | 2026-03-24 |

**Last phase number:** 36

---
*Last updated: 2026-03-23 — v0.8.0 Beta Readiness initialized*
