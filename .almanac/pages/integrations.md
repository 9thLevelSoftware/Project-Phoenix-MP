---
title: Integrations
summary: This page is the integrations hub, separating health-store behavior, local CSV interchange, and portal-fed provider sync before a future agent commits to the right branch.
topics: [systems, integrations, android, ios, flows]
sources:
  - id: integrations-vm
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/IntegrationsViewModel.kt
    note: Defines the shared UI boundary that presents health-store, CSV, and third-party provider integrations under one feature surface.
  - id: integrations-screen
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/IntegrationsScreen.kt
    note: Shows the separate Health, provider, and Strong or CSV cards in one screen.
  - id: integration-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/IntegrationManager.kt
    note: Defines the portal-fed third-party provider sync path.
  - id: health-common
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/HealthIntegration.kt
    note: Defines the platform health-store export and body-weight import path.
  - id: health-page
    type: file
    path: .almanac/pages/health-platform-integration.md
    note: Defines the health-store leaf page in this cluster.
  - id: provider-page
    type: file
    path: .almanac/pages/external-provider-sync.md
    note: Defines the portal-fed provider-sync leaf page in this cluster.
  - id: csv-page
    type: file
    path: .almanac/pages/csv-workout-import-export.md
    note: Defines the local file import or export path for Strong-compatible CSV and Hevy imports.
status: active
supersedes: health-and-external-integrations
verified: 2026-06-26
---
Phoenix exposes three different integration paths through one shared feature area. `IntegrationsScreen` and `IntegrationsViewModel` present platform health-store controls, a Strong or CSV file interchange card, and portal-fed third-party provider cards in one surface, but the underlying code still splits cleanly between `HealthIntegration`, local CSV import or export helpers, and `IntegrationManager` [@integrations-screen] [@integrations-vm] [@health-common] [@integration-manager].

Older wiki references may still use [[health-and-external-integrations]], which was the screen-title slug before this page became the integrations cluster hub. New links should point at this page directly.

## Cluster split

This hub is a bridge between local and remote clusters, not a synonym for remote sync. Premium gating, portal session state, and OAuth or token persistence still belong to [[premium-entitlements]], [[sync]], or [[auth]], while Health Connect or HealthKit behavior and CSV file interchange stay local even though the same screen surfaces all three branches [@integrations-screen] [@integrations-vm].

The fastest routing rule is transport, not UI. Open [[health-platform-integration]] when Phoenix talks directly to Google Health Connect or Apple HealthKit on the device; open [[csv-workout-import-export]] when the feature is reading or writing a local file; open [[external-provider-sync]] when Phoenix talks to the portal integration endpoint and only then repopulates local SQLDelight repositories [@health-common] [@integration-manager] [@csv-page].

The second routing rule is whether portal state can matter at all. If the symptom exists before login, survives logout, or only involves permission prompts, on-device reads or writes, or local CSV files, stay on the [[health-platform-integration]] or [[csv-workout-import-export]] branch. If the symptom depends on provider connection state, premium status, or imported provider entities appearing in local repositories, stay on the [[external-provider-sync]] branch and keep [[sync]] or [[premium-entitlements]] nearby [@health-common] [@integration-manager] [@csv-page].

The practical routing handoff with [[sync]] is simple. Start here when the symptom begins from the shared integrations screen or when "integration" is still the broadest accurate label; start in [[sync]] only when the failing boundary is already clearly auth, transport, entitlement, or Supabase configuration instead of one of this screen's three branches [@integrations-screen] [@integrations-vm] [@integration-manager].

Read [[frontend]] before this hub when the question is about shared route placement, screen-state wiring, or why one integrations card appears or disappears in Compose before the transport branch is even known.

Read [[settings-surface]] before this hub when the first symptom is only that the Settings tab entry path is wrong, missing, or badge state there disagrees with the integrations screen, because the user reaches this cluster through a separate route-entry surface before `IntegrationsScreen` becomes the right boundary [@integrations-screen].

## Which page to open next

Read [[health-platform-integration]] first for Health Connect, HealthKit, workout export markers, backfill, body-weight import, permission recovery, or Android-versus-iOS health differences [@health-page].

Read [[csv-workout-import-export]] first for Strong CSV export, Strong or Hevy import, weight-convention questions, import preview behavior, or why imported workouts appear in external activities without any provider connection [@csv-page].

Read [[external-provider-sync]] first for provider connection, cursor-based sync, disconnect cleanup, imported activities, provider routines or programs, exercise templates, or external body measurements [@provider-page].

## Shared surface, separate contracts

The three paths share the same menu and some local repository families, but they do not have the same transport contract. [[health-platform-integration]] is device-local and talks to Google Health Connect or Apple HealthKit directly, [[csv-workout-import-export]] is device-local file interchange through the platform file picker, and [[external-provider-sync]] talks only to the portal integration endpoint and then fans the response into local SQLDelight repositories [@integrations-screen] [@health-common] [@integration-manager] [@csv-page].

They also diverge in what "local state" means. Health-store integration writes or reads device-owned health records and then reflects selected data such as body weight back into Phoenix's local model, CSV interchange materializes external-activity rows from local or third-party files, and provider sync treats Phoenix's repositories as a cache of remote provider data that can be re-pulled or cleared on disconnect [@health-common] [@integration-manager] [@csv-page]. That difference is why imported-provider cleanup and cursor bugs belong to [[external-provider-sync]], duplicate workout export markers or missing body-weight imports belong to [[health-platform-integration]], and Strong or Hevy parse behavior belongs to [[csv-workout-import-export]].

The health branch also splits by platform earlier than the other two branches do. Android health failures often involve the Health Connect permission contract or its settings-recovery path after revocation, while iOS health failures usually start at direct HealthKit authorization or aggregate-workout export behavior rather than at a platform settings launcher [@health-page].

## Profile and entitlement handoff

The shared integrations surface is profile-reactive even when the transport branch is not. `IntegrationsViewModel` derives one `activeProfileId` from `UserProfileRepository.activeProfile`, observes external activities, routines, folders, programs, measurements, template counts, and per-provider status through that profile ID, and passes the same active profile into CSV import, provider connect, provider sync, provider disconnect, health backfill, and health body-weight import flows [@integrations-vm]. A profile switch can therefore make imported health rows, CSV rows, or provider-fed rows appear to disappear without any transport regression.

Paid-user state is also shared across branches, but not in the same way. `IntegrationsViewModel` treats either a local active profile subscription or a portal premium flag as sufficient for `isPaidUser`, then passes that flag into CSV parsing and provider sync, while pure health-permission toggles do not depend on it [@integrations-vm]. `IntegrationManager` uses that paid-user flag when it marks imported activities or provider tombstones for later sync behavior, so entitlement questions can still belong to [[premium-entitlements]] even when the first symptom was in an integrations screen [@integration-manager].

Read [[profiles]] early when imported activities, provider routines, health measurements, or connection status differ only after a profile switch, because the shared integrations UI is observing profile-scoped repositories rather than one global feed [@integrations-vm]. Read [[sync]] before this hub when the source of a remote bug is still unclear and might instead belong to auth, entitlement, or portal transport. Skip that detour for pure Health Connect, HealthKit, or CSV file symptoms with no sign of portal state. Read [[platform-hosts]] early when Android and iOS disagree about permission prompts, secure-store setup, native file pickers, or native wiring.
