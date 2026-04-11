# Phase 30: iOS Sync Launch — Context

## Phase Goal

Make cloud sync functional on iOS by injecting Supabase credentials and verifying the Ktor Darwin HTTP engine works for sync calls.

## Requirements

- **SYNC-IOS-01**: Inject Supabase credentials into iOS via Info.plist → NSBundle → PlatformModule.ios.kt
- **SYNC-IOS-02**: Verify Ktor Darwin engine works for GoTrue auth + Edge Function sync calls
- **SYNC-IOS-03**: TestFlight deployment pipeline verification

## Success Criteria

- PlatformModule.ios.kt reads SupabaseConfig from Info.plist (not hardcoded)
- iOS app can authenticate with Supabase GoTrue (login + signup)
- iOS app can push a workout session and pull routines via Edge Functions
- TestFlight build uploaded and accessible to beta testers

## Existing Assets

From Phase 29 (completed):
- LinkAccount route and SettingsTab button are enabled (shared code, applies to iOS too)
- ProGuard rules added (Android-only, not relevant for iOS)

From v0.6.0:
- SyncManager, PortalApiClient, PortalTokenStorage — all in commonMain (shared)
- Ktor client configured in commonMain — Darwin engine dependency in `shared/build.gradle.kts`
- LinkAccountScreen + LinkAccountViewModel — complete, shared Kotlin code

## Key Files

| File | Change | Notes |
|------|--------|-------|
| `iosApp/VitruvianPhoenix/VitruvianPhoenix/Info.plist` | Add SUPABASE_URL + SUPABASE_ANON_KEY entries | Currently has BLE, UI, file sharing permissions only |
| `shared/src/iosMain/.../di/PlatformModule.ios.kt` | Read credentials from NSBundle instead of empty strings | Currently stubs with empty `url=""` and `anonKey=""` |
| `.github/workflows/ios-testflight.yml` | Verify compatibility (read-only) | Existing CI workflow for TestFlight deployment |

## Constraints

- iOS builds require macOS (Xcode, codesigning) — cannot be fully verified on Windows
- Info.plist values are compile-time constants embedded in the app bundle
- Ktor Darwin engine uses URLSession under the hood — different behavior from OkHttp
- TestFlight deployment requires Apple Developer credentials (see mobile plan Section 8.3)

## Decisions

- Architecture proposals: skipped (approach is clear — Info.plist injection)
- Two waves: credential injection first (can be done on any OS), build verification second (requires macOS)

## Plan Structure

- **30-01**: iOS Credential Injection (Wave 1) — Info.plist + PlatformModule.ios.kt + compile check
- **30-02**: iOS Build & TestFlight Verification (Wave 2) — Darwin engine, CI workflow, blockers

## Branch

`MVP` — https://github.com/9thLevelSoftware/Project-Phoenix-MP/tree/MVP

## Reference

- `docs/plans/mvp-cloud-sync-mobile.md` Section 8 — iOS deployment details
- `.planning/exploration-mvp-cloud-sync.md` — crystallized scope
- Phase 29 summaries in `.planning/phases/29-core-sync-ui/`
