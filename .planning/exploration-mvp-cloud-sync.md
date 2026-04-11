# Exploration: MVP Cloud Sync (Mobile)

**Date:** 2026-03-15
**Mode:** Crystallize
**Status:** Crystallized — ready for `/legion:start`

---

## Crystallized Summary

v0.7.0 ships the user-facing cloud sync experience on both Android and iOS. The sync infrastructure (SyncManager, Edge Functions, adapters, 138 tests) already shipped in v0.6.0 — this milestone is about enabling the UI, deploying to users, and polish. The web portal is planned separately in the phoenix-portal repo.

Work is done on the `MVP` branch (branched from `new_ideas`): https://github.com/9thLevelSoftware/Project-Phoenix-MP/tree/MVP

## Decisions Made

| Decision | Choice | Rationale |
|----------|--------|-----------|
| What v0.7.0 represents | Launch + quick wins | Ship existing sync + portal with polish items, no new features |
| Platform scope | Android + iOS together | Both platforms ship with working sync |
| Integration scope | Strava + Hevy live at launch | Both are instant/self-service approval. Fitbit (1-3 wk) and Garmin (2-6 wk) get "Coming Soon" badges |
| Polish scope | All recommended items | Sync error indicator, Coming Soon badges, dead footer links, Sentry, CD pipeline |
| Milestone structure | Mobile-only, 3 phases | Portal planned separately in its own repo |
| Phase structure | Three phases | Core UI → iOS launch → Polish & validation |

## Phase Structure

### Phase 29: Core Sync UI
- Uncomment LinkAccount route in NavGraph.kt (lines 650-670)
- Uncomment "Link Portal Account" button in SettingsTab.kt (lines 1310-1351)
- Add Ktor ProGuard keep rules to proguard-rules.pro
- Applies to both Android and iOS (shared Kotlin code)

### Phase 30: iOS Sync Launch
- Inject Supabase credentials via Info.plist
- Update PlatformModule.ios.kt to read from NSBundle.mainBundle
- Verify Ktor Darwin engine works for sync HTTP calls
- TestFlight deployment pipeline verification

### Phase 31: Polish & Validation
- Add sync error indicator in SettingsTab (observe SyncTriggerManager.hasPersistentError)
- Version bump to 0.7.0 (versionName + versionCode)
- Release builds for both platforms (Play Store + TestFlight)
- End-to-end sync validation (sign up → sync → verify data on portal)

## Knowns

- Sync infrastructure is complete and tested (v0.6.0, 138 tests)
- Mobile sync UI (LinkAccountScreen, LinkAccountViewModel) is complete — just commented out
- Portal is ~95% code-complete, never publicly deployed
- Android Supabase credentials already configured in local.properties
- iOS has empty SupabaseConfig stubs in PlatformModule.ios.kt
- Ktor classes missing from ProGuard rules (will crash in release builds)
- No feature flag system exists
- RevenueCat KMP dependency is disabled; subscription state syncs via database

## Unknowns

- iOS Ktor Darwin engine behavior for sync (needs testing) → Phase 30
- Whether large sync payloads (1000+ workouts) time out → Monitor post-launch
- Exact TestFlight review timeline → Apple-dependent
- Fitbit/Garmin developer program approval timelines → Submit immediately, enable when approved

## Out of Scope (Mobile v0.7.0)

- Rep telemetry chunked upload (PUSH-05 deferral)
- Server-side RPG XP computation (PORTAL-ADV-02)
- iOS CV implementation (IOS-CV-01, IOS-CV-02)
- Ghost racing enhancements (PORTAL-ADV-01, PORTAL-ADV-05)
- Feature flag system
- Portal deployment (separate repo/plan)
- Fitbit/Garmin integration activation (waiting on approvals)

## Source Plans

- `docs/plans/mvp-cloud-sync-mobile.md` — detailed mobile implementation plan
- `phoenix-portal/docs/plans/mvp-cloud-sync-portal.md` — detailed portal implementation plan (separate repo)

---
*Crystallized via `/legion:explore` on 2026-03-15*
