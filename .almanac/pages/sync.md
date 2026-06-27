---
title: Sync
summary: This page is the hub for Phoenix's optional remote cluster around [[supabase]], routing auth, portal sync transport, entitlement, gamification reconciliation, remote provider import, persistence, and platform-storage questions to the right synthesis page.
topics: [systems, sync, auth, flows, integrations]
sources:
  - id: app-content
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/App.kt
    note: Shows that app foreground transitions invoke SyncTriggerManager from the shared app lifecycle.
  - id: sync-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt
    note: Defines the authenticated push and pull layer, premium refresh, and telemetry-tier gating.
  - id: sync-trigger
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncTriggerManager.kt
    note: Defines app-foreground, workout-complete, and backoff-triggered remote behavior.
  - id: integration-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/IntegrationManager.kt
    note: Defines provider sync as a separate portal-fed integration path inside the broader remote cluster.
  - id: portal-page
    type: file
    path: .almanac/pages/portal-sync-transport.md
    note: Defines the authenticated remote transport, trigger policy, and portal request layer.
  - id: auth-page
    type: file
    path: .almanac/pages/auth.md
    note: Defines session establishment, OAuth callback validation, secure token storage, and account-linking behavior.
  - id: premium-page
    type: file
    path: .almanac/pages/premium-entitlements.md
    note: Defines subscription-tier resolution and where premium state is enforced.
  - id: integrations-page
    type: file
    path: .almanac/pages/integrations.md
    note: Defines the integrations hub that separates platform health-store behavior from portal-fed provider imports.
  - id: csv-page
    type: file
    path: .almanac/pages/csv-workout-import-export.md
    note: Defines the local CSV import or export path that shares the integrations UI but not the remote transport stack.
  - id: data-hub-page
    type: file
    path: .almanac/pages/data.md
    note: Defines the broader local-data hub that routes profile, backup, and storage questions before remote symptoms are over-attributed to transport.
  - id: local-data-page
    type: file
    path: .almanac/pages/local-data-model.md
    note: Defines the local storage contracts that remote sync reads from and writes into.
  - id: backup-repair-page
    type: file
    path: .almanac/pages/data-backup-and-repair.md
    note: Defines backup, restore, and startup repair behavior that can make sync symptoms look remote even when local state changed first.
  - id: hosts-page
    type: file
    path: .almanac/pages/platform-hosts.md
    note: Defines platform-specific secure storage and Supabase configuration behavior.
  - id: gamification-page
    type: file
    path: .almanac/pages/gamification.md
    note: Defines how badges, streaks, and RPG summaries are computed locally but partially reconciled through portal sync.
  - id: settings-page
    type: file
    path: .almanac/pages/settings-surface.md
    note: Defines the shared Settings route that surfaces link-account and integrations entry points before the remote branch is narrowed.
status: active
verified: 2026-06-22
---
Phoenix is local-first, so sync is an optional layer on top of local storage rather than the app's primary source of truth. The shared app lifecycle still calls `SyncTriggerManager.onAppForeground()` from `AppContent`, and the remote cluster then fans out into authenticated push or pull orchestration in `SyncManager`, provider-data movement in `IntegrationManager`, and entitlement refresh around that transport layer [@app-content] [@sync-trigger] [@sync-manager] [@integration-manager].

## Cluster boundary

That remote cluster sits on [[supabase]] and spans more than one concern: session establishment in [[auth]], authenticated transport and trigger policy in [[portal-sync-transport]], entitlement policy in [[premium-entitlements]], the remote-provider branch behind [[integrations]], and the local or platform boundaries in [[data]], [[local-data-model]], [[data-backup-and-repair]], and [[platform-hosts]] [@auth-page] [@portal-page] [@premium-page] [@integrations-page] [@data-hub-page] [@local-data-page] [@backup-repair-page] [@hosts-page]. The integrations hub stays in this reading neighborhood because auth state, premium state, and the shared integrations screen often determine whether the provider-sync branch can run, not because every integration path itself talks to Supabase [@auth-page] [@premium-page] [@integrations-page].

The default remote reading order is narrower than the UI surface suggests. Establish whether a valid session exists first, then whether transport is eligible to run, then whether entitlement or integration policy is the real gate, and only then drop into local-state or platform pages if the remote explanation stops fitting. That sequence maps to [[auth]] -> [[portal-sync-transport]] -> [[premium-entitlements]], with [[integrations]] as the branch point only when the symptom still starts from the shared integrations surface rather than from auth or transport [@auth-page] [@portal-page] [@premium-page] [@integrations-page].

## Fast triage

The quickest triage rule is by boundary, not by feature name. Open [[supabase]] first for project configuration, redirect allowlists, GoTrue path shape, anon-key injection, or Edge Function naming, because those failures can present as auth or sync bugs even when the mobile logic is correct; open [[auth]] for login, logout, callback validation, secure token storage, or account-linking state; open [[portal-sync-transport]] when a valid session already exists and the question is about sync eligibility, trigger timing, backoff, or push and pull ordering; open [[premium-entitlements]] when the same account behaves differently by tier; open [[integrations]] when the symptom begins on the shared integrations screen or the word "integration" still needs to be split into health-store versus local CSV versus provider-sync behavior [@auth-page] [@portal-page] [@premium-page] [@integrations-page].

Read [[settings-surface]] before the narrower remote pages when the first user-visible symptom is still only "the Settings tab link-account or integrations card is wrong". That route is the UI entry surface, but the owning boundary still usually becomes [[auth]], [[integrations]], or [[platform-hosts]] after one more split [@settings-page].

Not every integrations-screen issue belongs in the remote cluster. [[csv-workout-import-export]] stays outside this hub because Strong or Hevy file import and Strong CSV export are local file operations even though imported activities can later appear beside provider-fed data [@csv-page].

Read [[frontend]] before this hub when the symptom is still about auth-screen routing, shared settings entry points, or Compose-state projection and you have not yet established that a remote request or token boundary is involved.

Older wiki references may still use [[portal-sync-and-auth]], but that archived slug now exists only to redirect to [[portal-sync-transport]]. Future edits should link the transport page directly.

[[auth]] is the narrower hub for login state, OAuth callbacks, secure token storage, and account-linking behavior. Start there before this page when the remote symptom happens before a sync request is even eligible to run [@auth-page].

Read [[project-phoenix]] before this cluster when the question is whether a remote behavior is actually required. The rescue-app contract is why offline control stays primary and why auth, premium, and provider sync are layered on instead of treated as mandatory boot prerequisites.

## Which page to open next

[[supabase]] is the dependency page for this cluster, not just background context. Read it first when the task is about redirect URLs, GoTrue endpoint shape, Edge Function names, anon-key injection, or whether a failure belongs to project configuration rather than the mobile sync code.

Read [[portal-sync-transport]] first when the question is about sync eligibility after login, retry or backoff behavior, app-foreground or workout-complete triggers, or push or pull orchestration [@portal-page].

Read [[premium-entitlements]] first when the symptom is premium UI state, subscription downgrade or upgrade handling, Inferno-only telemetry upload, or conflicts between portal auth state and local profile subscription fields [@premium-page].

Read [[integrations]] first when the task is clearly in the integrations cluster or when the symptom starts on that shared screen and you still need to choose between platform health-store behavior, local CSV interchange, and portal-fed provider sync [@integrations-page]. That branch point matters because [[health-platform-integration]] and [[csv-workout-import-export]] are device-local even though they share UI surface and entitlement-adjacent policy with the remote provider path [@integrations-page] [@csv-page].

Read [[health-platform-integration]] first when the task involves Health Connect, HealthKit, body-weight imports, workout export markers, or health permission recovery and there is no evidence yet that portal auth or provider sync is implicated.

Read [[external-provider-sync]] first when the task involves provider connection, imported activities, external routines or programs, templates, measurements, cursors, or disconnect cleanup.

Read [[data]] first when the remote issue may still be local persistence policy rather than transport, especially after profile changes, backup import, or external-data ingestion. Read [[local-data-model]] next when the remaining question is specifically about bad cursors, damaged rows, or a migration that left the local database logically behind the sync layer [@data-hub-page] [@local-data-page].

Read [[profiles]] when the same account syncs differently across local profiles, only one profile is missing remote-visible history, or the symptom started right after switching or deleting profiles.

Read [[data-backup-and-repair]] when sync churn starts after restore, backup import, startup repair, or profile-scope reconciliation, because those flows can rewrite or normalize the same local rows that sync later reads as upload candidates [@backup-repair-page].

Read [[strength-assessment-and-insights]] when the remote symptom is missing VBT assessment history, stale stored 1RM values after device changes, or confusion about which analytics are computed locally versus only shipped as sync payload data.

Read [[gamification]] when badge counts, streak totals, or RPG summaries diverge across devices, because sync uses different merge rules for additive badges and aggregate gamification stats [@gamification-page].

Read [[platform-hosts]] when Android and iOS disagree about token persistence, secure-store migration, bundle configuration, or background behavior that affects sync timing [@hosts-page].

## How the pages relate

The remote pages in this cluster are coupled by policy rather than by one code module. [[auth]] owns identity and session establishment, [[portal-sync-transport]] owns the authenticated transport and trigger layer that starts after that session exists, [[premium-entitlements]] owns entitlement interpretation, and [[integrations]] routes between the two integration paths, while [[data]], [[local-data-model]], [[data-backup-and-repair]], and [[platform-hosts]] constrain all four without belonging to only one of them [@auth-page] [@portal-page] [@premium-page] [@integrations-page] [@data-hub-page] [@local-data-page] [@backup-repair-page] [@hosts-page].

The most common mis-triage in this cluster is to open a transport page for a symptom that is still local. A restore, profile switch, startup repair, or platform secure-store migration can make the remote cluster look wrong even when the portal request layer is behaving correctly, which is why [[profiles]], [[data-backup-and-repair]], and [[platform-hosts]] stay in this hub's core map instead of being treated as side references [@backup-repair-page] [@hosts-page] [@data-hub-page].

Read [[getting-started]] first when you reached this page through search and still need the broader Phoenix reading order rather than only the remote cluster map.
