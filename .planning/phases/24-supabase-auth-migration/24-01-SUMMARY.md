# Plan 24-01 Summary: GoTrue Auth Foundation

**Status:** Complete
**Date:** 2026-03-02

## What was done
- Created `GoTrueModels.kt` — 7 @Serializable DTOs matching Supabase GoTrue REST wire format + `toPortalAuthResponse()` mapping
- Created `SupabaseConfig.kt` — Config data class with `url`, `anonKey`, computed `authUrl`
- Updated `local.properties` with placeholder `supabase.url` and `supabase.anon.key`
- Updated `androidApp/build.gradle.kts` — reads local.properties, injects `SUPABASE_URL` and `SUPABASE_ANON_KEY` via `buildConfigField`
- Updated `VitruvianApp.kt` — provides `SupabaseConfig` singleton from BuildConfig into Koin
- Updated `PlatformModule.ios.kt` — placeholder `SupabaseConfig` for iOS
- Updated `PortalTokenStorage.kt` — added `KEY_REFRESH_TOKEN`, `KEY_EXPIRES_AT`, `saveGoTrueAuth()`, `getRefreshToken()`, `getExpiresAt()`, `isTokenExpired()`
- Refactored `PortalApiClient.kt` — constructor takes `SupabaseConfig` + `PortalTokenStorage`; replaced Railway auth endpoints with GoTrue (`signIn`, `signUp`, `refreshToken`, `getUser`, `signOut`); sync endpoints unchanged (Railway)
- Updated `PortalAuthRepository.kt` — uses GoTrue methods and `saveGoTrueAuth()`
- Updated `SyncModule.kt` — wires `SupabaseConfig` into `PortalApiClient`
- Updated `SyncManager.kt` — `login()`/`signup()` delegate to `signIn()`/`signUp()`

## Files created
- `shared/.../data/sync/GoTrueModels.kt`
- `shared/.../data/sync/SupabaseConfig.kt`

## Files modified
- `local.properties`, `androidApp/build.gradle.kts`, `VitruvianApp.kt`
- `PlatformModule.ios.kt`, `PortalTokenStorage.kt`, `PortalApiClient.kt`
- `PortalAuthRepository.kt`, `SyncModule.kt`, `SyncManager.kt`

## Build: PASS
