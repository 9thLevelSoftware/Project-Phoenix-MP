---
title: Auth
summary: This page is the narrower auth hub inside Phoenix's optional [[supabase]] remote cluster, covering GoTrue login flows, secure token storage, callback wiring, and account state before [[portal-sync-transport]] runs.
topics: [systems, auth, flows, sync]
sources:
  - id: auth-repo
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/PortalAuthRepository.kt
    note: Defines session restoration, PKCE OAuth, callback validation, and profile linking after login.
  - id: oauth-callback-tests
    type: file
    path: shared/src/commonTest/kotlin/com/devil/phoenixproject/data/repository/PortalAuthRepositoryOAuthCallbackTest.kt
    note: Pins the accepted mobile callback shapes and rejection behavior for malformed OAuth callback payloads.
  - id: token-storage
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalTokenStorage.kt
    note: Defines secure token persistence, auth event emission, subscription-tier caching, and stable device identity.
  - id: portal-api
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt
    note: Defines the GoTrue and subscription endpoints the shared auth layer calls.
  - id: android-platform
    type: file
    path: shared/src/androidMain/kotlin/com/devil/phoenixproject/di/PlatformModule.android.kt
    note: Defines Android encrypted storage, plaintext-to-encrypted token migration, and OAuth launcher wiring.
  - id: ios-platform
    type: file
    path: shared/src/iosMain/kotlin/com/devil/phoenixproject/di/PlatformModule.ios.kt
    note: Defines iOS Keychain-backed storage, legacy token migration, and runtime Supabase config loading.
  - id: sync-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt
    note: Defines where authenticated portal state is consumed by sync and premium-tier behavior.
status: active
verified: 2026-06-22
---
Phoenix auth is an optional shared subsystem on top of [[supabase]], not a boot prerequisite for the app. The local-first contract from [[project-phoenix]] still holds when no portal session exists, so auth exists to unlock remote sync, provider imports, and portal-backed premium state rather than to gate local workout control [@auth-repo] [@sync-manager].

## Boundary

The shared auth boundary lives in `PortalAuthRepository`. The repository restores sessions on construction, signs in with email or password against GoTrue REST endpoints, links the active local profile to the Supabase user ID after successful login, and clears auth state if refresh fails with an unauthorized or invalid-token response [@auth-repo] [@portal-api]. That linkage is profile-scoped local state, so read [[profiles]] when only one profile can log in, keeps the wrong entitlement history, or appears disconnected from the same portal account.

## OAuth and callback contract

OAuth is browser-based PKCE with a fixed mobile callback contract. `PortalAuthRepository` hard-codes `com.devil.phoenixproject://auth-callback`, validates that returned deep links still match that scheme and host before exchanging the code, and depends on platform `OAuthLauncher` bindings plus matching Supabase redirect allowlists to complete the flow [@auth-repo] [@android-platform] [@ios-platform].
`PortalAuthRepositoryOAuthCallbackTest` also locks in edge-case parsing behavior such as accepting the expected callback with query parameters and rejecting malformed or unexpected callback payloads before auth state is changed [@oauth-callback-tests].

## Token storage

Token storage is a platform boundary, not just a shared utility. `PortalTokenStorage` verifies that its backing store can round-trip data on initialization, persists access and refresh tokens plus cached entitlement fields, emits auth events for session expiry or explicit logout, and preserves a stable device ID even when auth is cleared [@token-storage]. Android binds that storage to `EncryptedSharedPreferences` and migrates legacy portal keys out of plaintext preferences before use [@android-platform]. iOS binds it to Keychain and migrates legacy values out of `NSUserDefaults` during Koin setup [@ios-platform].

## Relationship to premium and sync

Auth and premium are related but not identical. `PortalTokenStorage` caches both a broad `isPremium` flag and a subscription-tier string, and `SyncManager` uses tier-specific state for behaviors such as Inferno-only `50 Hz` telemetry sync [@token-storage] [@sync-manager]. A user can therefore be authenticated while entitlement-dependent behavior is still waiting on subscription resolution.

The boundary of this page is identity and session state, not remote data movement. This page ends once Phoenix has a valid GoTrue session in secure storage and has linked the active local profile to the Supabase user ID; [[portal-sync-transport]] starts where `SyncManager`, `SyncTriggerManager`, and `IntegrationManager` decide when that authenticated state should move remote data [@auth-repo] [@token-storage] [@sync-manager].

This page is also not the right first stop for backend-project configuration. Redirect allowlists, anon-key injection, GoTrue host shape, and Edge Function names belong to [[supabase]] even when the symptom first appears during login or callback handling.

## Reading order

Read [[getting-started]] first when the repo still feels broad and you need the shortest route to the right hub before narrowing into auth or sync.

Read this hub before the broader [[sync]] cluster when the symptom is login, logout, session restoration, OAuth callback mismatch, secure token storage, or account-linking state. Then branch by question:

- [[supabase]] for redirect allowlists, GoTrue endpoint shape, anon-key injection, and Edge Function names.
- [[portal-sync-transport]] for push or pull orchestration, sync backoff, and how portal auth feeds remote data movement.
- [[premium-entitlements]] for subscription rows, local profile subscription state, and feature gates.
- [[profiles]] for active-profile linkage, local subscription fields, delete-time reassignment, and profile-scope symptoms that can masquerade as auth bugs.
- [[platform-hosts]] for Android versus iOS storage, boot, and OAuth-launch differences.
