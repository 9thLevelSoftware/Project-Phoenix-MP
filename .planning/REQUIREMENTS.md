# Requirements: Project Phoenix MP

**Defined:** 2026-03-02
**Core Value:** Users can connect to their Vitruvian trainer and execute workouts with accurate rep counting, weight control, and progress tracking -- reliably, on both platforms.

## Shipped Requirements

All requirements through v0.6.0 have shipped.
See `.planning/milestones/` for archived requirement details by version.

## v0.7.0 MVP Cloud Sync (Active)

Ship the user-facing cloud sync experience on Android and iOS. No new features — enables the UI and infrastructure built in v0.6.0.

### Core Sync UI (Phase 29)

- [ ] **SYNC-UI-01**: Enable LinkAccount route in NavGraph.kt and "Link Portal Account" button in SettingsTab.kt
- [ ] **SYNC-UI-02**: Add Ktor ProGuard keep rules to prevent release build crashes from R8 stripping

### iOS Sync Launch (Phase 30)

- [ ] **SYNC-IOS-01**: Inject Supabase credentials into iOS via Info.plist → NSBundle → PlatformModule.ios.kt
- [ ] **SYNC-IOS-02**: Verify Ktor Darwin engine works for GoTrue auth + Edge Function sync calls
- [ ] **SYNC-IOS-03**: TestFlight deployment pipeline verification

### Polish & Validation (Phase 31)

- [ ] **SYNC-POLISH-01**: Sync error indicator in SettingsTab observing SyncTriggerManager.hasPersistentError
- [ ] **SYNC-POLISH-02**: Version bump to 0.7.0 (versionName + versionCode) + signed release builds
- [ ] **SYNC-POLISH-03**: End-to-end sync validation (sign up → sync → verify data on portal)

## v0.8.0+ Requirements

Deferred to future release. Tracked but not yet in a milestone roadmap.

### Portal Integration (Advanced)

- **PORTAL-ADV-01**: Ghost racing against portal-matched sessions (best session across all devices)
- **PORTAL-ADV-02**: RPG XP computed server-side via compute-attributes Edge Function
- **PORTAL-ADV-03**: Readiness briefing powered by Bannister FFM + HRV/sleep biometric data
- **PORTAL-ADV-04**: CV form data synced to portal for post-workout postural analytics dashboard
- **PORTAL-ADV-05**: Ghost racing with full 50Hz telemetry overlay (Elite tier)

### iOS CV

- **IOS-CV-01**: iOS pose estimation via Apple Vision framework or MediaPipe iOS SDK
- **IOS-CV-02**: iOS camera preview with skeleton overlay

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| Supabase Realtime subscriptions | Batch push/pull sufficient for workout sync; realtime adds complexity without clear benefit |
| supabase-kt SDK | Raw Ktor HTTP calls sufficient; SDK would create dependency conflicts with existing Ktor setup |
| Railway backend service | Edge Functions are zero-ops and match portal's existing architecture |
| Portal community features sync | Community sharing/comments/votes are portal-only features |
| Training cycles sync | Portal-only feature, future mobile feature candidate |
| External integrations sync (Strava/Fitbit/Garmin) | Portal-only; third-party OAuth handled entirely by portal |
| GraphQL layer | Portal uses REST/PostgREST; no benefit to adding query layer |
| WebSocket real-time sync | Over-engineered for batch workout sync |
| Server-side subscription validation | Client-side FeatureGate accepted for pre-launch |
| Supabase database webhooks | Not needed for mobile-initiated sync |

---
*Last updated: 2026-03-15 — v0.7.0 requirements defined, deferred items moved to v0.8.0+*
