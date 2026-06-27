---
title: External Provider Sync
summary: Third-party provider integrations sync only through the portal endpoint, then populate provider-scoped local repositories for activities, routines, programs, playground edits, templates, measurements, and cursors.
topics: [systems, integrations, sync, flows, data]
sources:
  - id: integration-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/IntegrationManager.kt
    note: Defines provider connect, incremental sync, disconnect cleanup, paging limits, and response mapping.
  - id: external-viewmodels
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/ExternalIntegrationViewModels.kt
    note: Defines the profile-reactive UI state surfaces for imported provider activities, routines, programs, and measurements after sync has populated local repositories.
  - id: nav-graph
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt
    note: Shows that imported provider data is exposed through dedicated external-data routes, not only through the Integrations screen.
  - id: external-screens
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExternalIntegrationScreens.kt
    note: Shows the dedicated imported-program detail and playground screens that sit behind the provider-data hub.
  - id: data-module
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt
    note: Shows the dedicated SQLDelight repositories that store imported provider entities and cursors.
  - id: external-repos
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/ExternalIntegrationRepositories.kt
    note: Defines the repository contracts for provider-scoped activities, routines, programs, measurements, templates, and cursors.
  - id: external-repos-impl
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/SqlDelightExternalIntegrationRepositories.kt
    note: Shows the SQLDelight-backed storage for imported provider entities.
  - id: activity-repo-impl
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/SqlDelightExternalActivityRepository.kt
    note: Shows activity storage, provider status storage, and provider-scoped deletes.
  - id: db-schema
    type: file
    path: shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq
    note: Defines integration status and cursor tables and the provider-scoped local schema surface.
status: active
verified: 2026-06-25
---
External provider integrations are portal-brokered, not direct mobile SDK integrations. `IntegrationManager` still talks only to the portal integration endpoint, and the mobile app then maps that response into dedicated local repositories for activities, routines, routine folders, programs, exercise templates, body measurements, and sync cursors [@integration-manager] [@data-module]. Read [[integrations]] first when the task is only "integrations" so you can choose between this portal path and [[health-platform-integration]].

## Local storage boundary

The local storage model is intentionally provider-scoped. `DataModule` binds dedicated SQLDelight repositories for external activities, routines, programs, measurements, templates, and cursors, and the schema carries provider-specific status and cursor tables so imported data can coexist across providers and profiles [@data-module] [@db-schema]. The repository interfaces expose provider-filtered observation and provider-scoped delete operations for each entity family [@external-repos].

Imported provider data is separate from Phoenix's native workout and sync schema. The provider repositories live in the same database as the rest of the app, but they are not merged through `SyncRepository`'s workout-history path; they stay in their own entity tables and cursor family [@data-module] [@external-repos-impl]. That boundary matters when debugging missing imported routines or programs that never appear in the main workout repository, and it is why [[local-data-model]] is the right neighboring page when the issue is storage shape or migration state rather than portal transport.

## Transport and pagination

Sync is incremental and multi-entity. `IntegrationManager.syncProvider()` starts from the stored `integration_sync` cursor when one exists, requests all entity types together, persists the latest `providerSyncCursor`, and stops after `50` pages to avoid infinite loops while marking the result partial with a warning if the cap is hit [@integration-manager].

The sync response is expanded, not activity-only. `IntegrationManager.persistResponse()` maps activities, routine folders, routines with nested exercises and sets, exercise templates, body measurements, programs, and program stats into their local domain rows, and it can also mark provider-deleted external IDs without immediately erasing the activity row [@integration-manager]. Program stats are applied only after the imported programs can be matched back to local IDs [@integration-manager].

## UI surface after sync

The Integrations screen is the control surface for connection and manual sync, not the only place imported provider data appears. `NavGraph` routes synced provider entities into dedicated external-data screens for activities, routines, routine details, programs, program details, program playground, and measurements after the initial integration entrypoint [@nav-graph].

Those screens read local repository state through separate view models, not through `IntegrationsViewModel`. `ExternalActivitiesViewModel`, `ExternalRoutinesViewModel`, `ExternalProgramsViewModel`, and `ExternalMeasurementsViewModel` each derive the active profile ID and observe the imported provider repositories directly, so a symptom that only affects the downstream browsing screens can still be a repository, profile-scope, or cursor problem even when the connect or sync button path looked healthy [@external-viewmodels].

Imported programs are not read-only once they land locally. `ExternalProgramsViewModel` observes the current `LIFTOSAUR` program separately from the general program list, calls `IntegrationManager.simulatePlayground()` with the cached `scriptText`, and stores the returned preview for `ExternalProgramPlaygroundScreen` to render before any local write is committed [@external-viewmodels] [@integration-manager] [@external-screens].

Committing a playground preview still writes only to Phoenix's local provider cache. `commitPreview()` calls `IntegrationManager.commitProgramText()`, which updates the local external-program row through `ExternalProgramRepository.updateProgramText()` and marks that row `needsSync` rather than issuing a separate immediate portal write from the screen layer [@external-viewmodels] [@integration-manager] [@external-repos-impl].

## Disconnect and cleanup

Disconnect is destructive for the local provider cache. `disconnectProvider()` calls the portal with a best-effort `disconnect` action, then deletes provider-scoped activities, routines, programs, measurements, templates, and cursors locally before marking the provider status disconnected [@integration-manager] [@activity-repo-impl]. A portal disconnect failure is logged as non-fatal, so local cleanup still happens [@integration-manager].

## Reading boundary

Read [[portal-sync-transport]] when the failure might still be in session state, auth-backed portal transport, or foreground trigger policy before provider sync runs. Read [[premium-entitlements]] when provider behavior changes by paid-user state, because `IntegrationManager` still threads `isPaidUser` into imported activity tombstone behavior [@integration-manager]. Read [[profiles]] when only one local profile sees missing provider data, stale cursors, or disconnect cleanup that does not match another profile, because the imported rows coexist in the same database alongside profile-scoped Phoenix state even though provider entities use their own repository family [@data-module] [@db-schema].
