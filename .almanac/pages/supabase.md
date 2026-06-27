---
title: Supabase
summary: Supabase is Phoenix's remote backend for GoTrue auth, subscription reads, and Edge Function sync endpoints, with runtime config injected differently on Android and iOS.
topics: [stack, sync, auth]
sources:
  - id: config-model
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SupabaseConfig.kt
    note: Defines the shared Supabase runtime config and derives the GoTrue auth base URL from the project URL.
  - id: portal-api
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt
    note: Shows the REST endpoints Phoenix calls for auth, subscriptions, portal sync, and provider sync.
  - id: auth-repo
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/PortalAuthRepository.kt
    note: Defines PKCE OAuth, the mobile callback scheme, and the requirement to align redirect allowlists with the Supabase project.
  - id: android-build
    type: file
    path: androidApp/build.gradle.kts
    note: Shows Android build-time credential injection and the fail-fast check for missing Supabase URL or anon key.
  - id: android-app
    type: file
    path: androidApp/src/main/kotlin/com/devil/phoenixproject/VitruvianApp.kt
    note: Shows Android runtime injection of SupabaseConfig into the shared Koin graph.
  - id: ios-platform
    type: file
    path: shared/src/iosMain/kotlin/com/devil/phoenixproject/di/PlatformModule.ios.kt
    note: Shows iOS Info.plist-based config loading and fail-fast behavior when Supabase values are missing.
  - id: ios-readme
    type: file
    path: iosApp/README.md
    note: Documents the ignored local Supabase.xcconfig file and the CI secret-writing path for iOS builds.
  - id: backup-strings
    type: file
    path: shared/src/commonMain/composeResources/values/strings.xml
    note: States that data backups exclude auth or session tokens, Supabase configuration, and keystore data.
status: active
verified: 2026-06-24
---
[[project-phoenix]] uses Supabase as its entire remote backend surface. The shared `SupabaseConfig` model stores one project URL plus one anon key, and derives the GoTrue auth base URL as `"$url/auth/v1"` instead of keeping a separate auth-host setting [@config-model].

## Remote surfaces Phoenix uses

Phoenix uses Supabase over direct HTTP calls, not a Supabase mobile SDK. `PortalApiClient` signs in with `POST /token?grant_type=password`, signs up with `POST /signup`, exchanges PKCE codes with `POST /token?grant_type=pkce`, refreshes with `POST /token?grant_type=refresh_token`, reads the current user from `GET /user`, and signs out with `POST /logout` against the derived GoTrue base URL [@portal-api].

The same client uses two more Supabase surfaces. Subscription checks query `GET /rest/v1/subscriptions` with the bearer token and anon key, while sync and provider flows call the Edge Functions `mobile-sync-push`, `mobile-sync-pull`, `mobile-integration-sync`, and `mobile-integration-playground` under `/functions/v1/` [@portal-api]. `mobile-integration-sync` is the backend boundary behind [[external-provider-sync]], while the other function names stay in the core [[portal-sync-transport]] path.

This page is the backend-contract page for that cluster, not the main remote-symptom hub. Open [[sync]] first when the question is "why didn't remote behavior happen," then come here if the answer might still be project configuration, redirect wiring, or endpoint shape rather than shared mobile logic.

## Mobile callback contract

Mobile OAuth is constrained by Supabase project configuration. `PortalAuthRepository` hard-codes the callback URL `com.devil.phoenixproject://auth-callback`, requires callbacks to match that scheme on return, and documents that the same URL must stay aligned with Android intent filters, iOS authentication-session wiring, and the Supabase Auth redirect allowlist [@auth-repo].

## Platform configuration inputs

Android and iOS inject Supabase config differently into the same shared graph. Android reads `supabase.url` and `supabase.anon.key` from `local.properties`, falls back to `SUPABASE_URL` and `SUPABASE_ANON_KEY` environment variables for CI, and refuses app builds unless values are present or `-Pskip.supabase.check=true` is set for test-only work [@android-build]. `VitruvianApp` then binds those values into Koin as `SupabaseConfig` [@android-app].

iOS loads the same values from `SUPABASE_URL` and `SUPABASE_ANON_KEY` entries in the app bundle and throws during Koin setup if either value is missing [@ios-platform]. The tracked `SupabaseBase.xcconfig` can include a local ignored `Supabase.xcconfig`, and the iOS README says GitHub Actions writes that ignored file from repository secrets during CI builds [@ios-readme].

## Backup and restore boundary

Supabase config and auth state are intentionally excluded from user backup data. The iOS setup docs keep the runtime values in ignored config files [@ios-readme], and the shared backup UX states that exports do not include auth or session tokens, Supabase config, or keystore data in restored user data [@backup-strings].

## Reading order

Read [[getting-started]] first when the repo still feels broad and you need the shortest route into Phoenix before narrowing into the remote stack.

Read [[sync]] first when the source of a remote problem is still unclear and you need the cluster map before narrowing into dependency details.

Read [[auth]] for login, logout, session restoration, callback validation, and account-linking behavior that sits directly on top of this dependency. Read [[portal-sync-transport]] for the authenticated transport and trigger layer that starts once `PortalAuthRepository` has established a valid session. Read [[premium-entitlements]] for how subscription rows are interpreted into Phoenix feature gates. Read [[platform-hosts]] for the platform-specific storage and boot behavior that surrounds Supabase config and token persistence.
