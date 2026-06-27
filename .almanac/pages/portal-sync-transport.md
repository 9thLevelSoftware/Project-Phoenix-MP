---
title: Portal Sync Transport
summary: This page covers Phoenix's authenticated remote transport after a valid portal session exists, including token-backed requests, automatic triggers, tier-gated telemetry sync, and retry/backoff rules.
topics: [systems, sync, flows]
sources:
  - id: sync-module
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/di/SyncModule.kt
    note: Shows the auth and sync object graph.
  - id: sync-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt
    note: Defines push/pull orchestration, premium tier handling, and sync limits.
  - id: sync-trigger
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncTriggerManager.kt
    note: Defines automatic sync triggers, backoff, and connectivity behavior.
  - id: portal-api
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalApiClient.kt
    note: Defines GoTrue REST calls, HTTP timeouts, and error classification.
  - id: auth-repo
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/PortalAuthRepository.kt
    note: Defines PKCE OAuth, callback validation, and session restoration.
  - id: token-storage
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/PortalTokenStorage.kt
    note: Defines secure token persistence, storage verification, and auth event emission.
  - id: sync-tests
    type: file
    path: shared/src/commonTest/kotlin/com/devil/phoenixproject/data/sync/SyncManagerTest.kt
    note: Characterizes auth state transitions and sync orchestration order.
status: active
supersedes: portal-sync-and-auth
verified: 2026-06-22
---
This page covers the transport layer that starts after [[auth]] has already established a valid portal session. `syncModule` constructs `PortalTokenStorage`, `PortalApiClient`, `SyncManager`, `SyncTriggerManager`, `IntegrationManager`, and `PortalAuthRepository` in one object graph, but the boundary here is authenticated remote movement rather than login or callback handling [@sync-module].

Older wiki references may still use [[portal-sync-and-auth]], which was the earlier slug before this page narrowed to transport after login succeeds.

## Boundary

`PortalAuthRepository` and `PortalTokenStorage` are still part of the object graph because sync depends on their session state, but login UX, OAuth callback validation, and account-linking behavior belong to [[auth]] rather than to this page's main boundary [@auth-repo] [@token-storage].

## Portal transport surfaces

Portal requests still depend on Supabase GoTrue over REST instead of a platform SDK. `PortalApiClient` uses the token-backed GoTrue session for `/user`, refresh, and logout flows, and `PortalTokenStorage` keeps the access token, refresh token, and user identity available to the rest of the remote stack [@portal-api] [@token-storage].

Changes to redirect URLs, callback schemes, or auth-host shape still need the neighboring pages. Read [[auth]] for the PKCE browser flow and callback contract, [[supabase]] for redirect allowlists and GoTrue endpoint shape, and [[platform-hosts]] for the platform secure-store bindings that back `PortalTokenStorage` [@auth-repo] [@token-storage].

This page is not the cluster hub. Read [[sync]] first when the source of a remote problem is still unclear and you need the broader map across session state, transport eligibility, premium gating, provider sync, and local persistence.

## Sync policy

Sync itself is push/pull orchestration with client-side limits. `SyncManager` caps payload sizes and request rates, keeps consecutive full-batch retry counters, and reserves raw `50 Hz` rep-telemetry sync for the `INFERNO` subscription tier while lower tiers still sync higher-level workout data [@sync-manager].

Automatic sync is intentionally selective. `SyncTriggerManager` attempts sync on workout completion and app foreground, refreshes premium status on foreground, performs body-weight sync from the connected health platform before portal sync, and applies a `5 -> 15 -> 30 -> 60` minute backoff schedule for transient failures [@sync-trigger].

## Profile and local-state boundary

Portal transport is profile-scoped even though the portal payload also carries a device-wide profile list. `SyncManager` gathers sessions, PRs, cycles, gamification data, assessments, and other push entities from the active profile scope, tags the payload with `profileId` and `profileName` from the current active profile, and only uses `allProfiles` as metadata for the portal to understand the device's local profile map [@sync-manager].

That boundary means a remote bug that affects only one local profile usually does not start in token storage or HTTP transport. Read [[profiles]] when one profile uploads or pulls differently from another, and read [[local-data-model]] or [[data-backup-and-repair]] before blaming the portal when restore, migration-time repair, or profile reassignment changed which local rows belong to the active profile [@sync-manager].

## Test coverage

The tests pin several contracts that are easy to break accidentally. `SyncManagerTest` verifies that unauthenticated sync returns `NotAuthenticated`, successful login stores auth, and historical phase-specific PR backfill runs before collecting the dedicated PR payload for push [@sync-tests].

## Reading order

Read [[sync]] first if the source of a remote bug is unclear and you need the cluster map. Read [[premium-entitlements]] next if the task touches subscription tiers, premium-state refresh, or telemetry gating. Read [[profiles]] next when only one local profile diverges, and read [[local-data-model]] or [[data-backup-and-repair]] next when sync churn started after restore, migration repair, or other local reconciliation. If the task touches external provider imports, read [[external-provider-sync]] next. If it touches health export markers or body-weight import, read [[health-platform-integration]] next.
