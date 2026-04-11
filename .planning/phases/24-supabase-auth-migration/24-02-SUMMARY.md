# Plan 24-02 Summary: Token Lifecycle & Session Persistence

**Status:** Complete
**Date:** 2026-03-02

## What was done
- Added `Mutex`-protected `ensureValidToken()` to `PortalApiClient` — proactive refresh before API calls with double-check-after-lock pattern
- Added `forceRefresh()` for 401 retry — one refresh attempt on server rejection
- Updated `authenticatedRequest()` — uses `ensureValidToken()` + retries once on 401
- Added `restoreSession()` to `PortalAuthRepository` — silently refreshes expired tokens on app startup
- Added `linkUserProfile()` — links active UserProfile to Supabase user_id after sign-in/sign-up
- Updated `PortalAuthRepository` constructor to accept `UserProfileRepository`
- Updated `SyncModule.kt` — wires `UserProfileRepository` into `PortalAuthRepository`
- Updated `SyncManager.sync()` — maps 401 from API client to `SyncState.NotAuthenticated`

## Files modified
- `PortalApiClient.kt` (Mutex, ensureValidToken, forceRefresh, authenticatedRequest)
- `PortalAuthRepository.kt` (restoreSession, linkUserProfile, UserProfileRepository dep)
- `SyncModule.kt` (3-arg PortalAuthRepository)
- `SyncManager.kt` (401 → NotAuthenticated)

## Build: PASS
