---
title: Premium Entitlements
summary: This page explains how Phoenix resolves premium entitlement from [[supabase]] subscription rows, token-cache state, and local profile fields before sync, telemetry upload, and UI gates consume it.
topics: [systems, sync, auth, concepts]
sources:
  - id: portal-api
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt
    note: Defines subscription tier precedence, premium checks, and network-failure behavior.
  - id: token-storage
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalTokenStorage.kt
    note: Defines cached premium state, cached tier state, and auth-clearing behavior.
  - id: sync-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt
    note: Defines login, refresh, logout, and Inferno-only telemetry gating behavior.
  - id: sync-trigger
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncTriggerManager.kt
    note: Defines automatic sync suppression for users confirmed as free after first sync discovery.
  - id: profiles
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/UserProfileRepository.kt
    note: Defines local profile subscription fields and ACTIVE/FREE/EXPIRED/GRACE_PERIOD states.
  - id: integrations-vm
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/IntegrationsViewModel.kt
    note: Defines the UI-facing paid-user check that combines local profile and portal state.
  - id: rep-metrics
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/RepMetricRepository.kt
    note: States that per-rep metric capture is stored for all users locally.
  - id: biomechanics
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/BiomechanicsRepository.kt
    note: States that per-rep biomechanics capture is stored for all users locally.
  - id: oauth-link
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/sync/LinkAccountViewModel.kt
    note: Defines the post-OAuth premium refresh contract.
status: active
verified: 2026-06-24
---
Premium state in Phoenix is not one boolean with one source of truth. The repo keeps [[supabase]]-backed portal auth state in [[portal-sync-transport]], local subscription fields on the active profile, and a separate cached subscription tier string for features that need finer-grained gating than broad paid/free status [@token-storage] [@profiles] [@sync-manager].

## Boundary

This page is about entitlement interpretation after Phoenix already knows who the user is. [[auth]] owns login, logout, callback validation, and secure token storage; [[portal-sync-transport]] owns request timing, retry, and push or pull policy; this page starts once those neighboring systems need to answer "is this user premium, and at what tier?" [@sync-manager] [@token-storage].

The key split is between authenticated state and paid-user state. A user can have a valid GoTrue session while entitlement is still unresolved, stale, or downgraded, and some UI paths also treat local profile subscription fields as meaningful even before the portal cache updates [@sync-manager] [@profiles] [@integrations-vm].

## Where entitlement comes from

The server-side entitlement model is tiered. `PortalApiClient` ranks active or trialing subscriptions as `INFERNO > FLAME > EMBER`, ignores unknown tier strings, and returns `null` when the user has no known active subscription rows [@portal-api]. The same client treats `402` and `403` as permanent errors for the current session rather than transient retry cases [@portal-api].

The portal cache is deliberately fail-safe across account switches and network failures. `SyncManager.login()` captures the previous user ID before overwriting auth, preserves premium or tier only when the new login is the same Supabase user, and otherwise clears entitlement until the server resolves the new account [@sync-manager]. A successful server response of `null` means a real downgrade and clears the cached tier, while only failed subscription checks preserve the prior values [@sync-manager].

OAuth sign-in has the same entitlement refresh requirement even though it bypasses `SyncManager.login()`. `LinkAccountViewModel.finishOAuthLink()` resets stale `NotAuthenticated` UI state and then calls `refreshPremiumStatusFromServer()` so the stored GoTrue session does not leave the UI looking free by default [@oauth-link].

Local profiles keep separate subscription fields. `UserProfile` stores `supabaseUserId`, `subscriptionStatus`, `subscriptionExpiresAt`, and `lastAuthAt`, with status values `FREE`, `ACTIVE`, `EXPIRED`, and `GRACE_PERIOD` [@profiles]. UI code does not always look only at portal auth state: `IntegrationsViewModel.isPaidUser` treats either a locally `ACTIVE` profile or a portal user with `isPremium == true` as sufficient for paid-user behavior [@integrations-vm].

## Where entitlement is enforced

Sync uses the portal cache more strictly than the integrations UI does. `SyncTriggerManager` skips automatic sync for users confirmed as non-premium once they already have a prior sync timestamp, but still allows the first sync attempt so premium status can be discovered from the server [@sync-trigger]. `SyncManager` also gates raw telemetry upload behind the `INFERNO` tier only; Ember and Flame users still sync session summaries, history, PRs, and analytics metadata without the large per-sample payload [@sync-manager].

Premium gating here is mostly about remote transfer and presentation, not local capture. `RepMetricRepository` and `BiomechanicsRepository` both state that per-rep metric and biomechanics data are stored locally for all users regardless of subscription status [@rep-metrics] [@biomechanics]. That means future work must distinguish capture, local persistence, UI exposure, and portal upload as separate entitlement boundaries.

The [[integrations]] surface applies a broader paid-user rule than sync transport. `IntegrationsViewModel` combines local `ACTIVE` profile state with portal `isPremium` state, so provider-connect or CSV-adjacent UI can still look paid in cases where automatic sync remains suppressed until the server refresh path completes [@integrations-vm] [@sync-trigger].

## Reading order

Read [[sync]] first when the bug still spans auth, entitlement, provider sync, and local data, because that page is the cluster hub.

Read [[auth]] first when the real question is still whether the user has a valid session at all, whether the callback completed, or whether secure token storage failed before any premium check could run.

Read [[portal-sync-transport]] next when the entitlement symptom is really about foreground refresh timing, sync suppression, retry policy, or telemetry upload eligibility [@sync-manager] [@sync-trigger].

Read [[external-provider-sync]] next when the bug mixes paid-user state with provider connection, imported activities, cursor movement, or disconnect cleanup.

Read [[local-data-model]] next when the remaining question is where subscription or telemetry-adjacent state lives locally, and read [[profiles]] next when premium behavior differs by active local profile rather than by portal account.
