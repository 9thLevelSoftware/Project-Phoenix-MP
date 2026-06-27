---
title: Data
summary: This page is the hub for Phoenix's local data cluster, routing schema, profile scope, backup or restore, imported external data, routine persistence, analytics state, rack context, and sync-facing storage questions to the right synthesis page.
topics: [systems, data, flows]
sources:
  - id: shared-gradle
    type: file
    path: shared/build.gradle.kts
    note: Defines the SQLDelight database version and schema-manifest validation task.
  - id: schema-file
    type: file
    path: shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq
    note: Defines the broad shared schema, including profile-scoped workout, routine, sync, and analytics tables.
  - id: migration-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/MigrationManager.kt
    note: Defines startup repair, profile-scope audit, and orphan-repair behavior after migrations.
  - id: profile-repo
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/UserProfileRepository.kt
    note: Defines default-profile bootstrapping, active-profile state, and profile-deletion reassignment behavior.
  - id: backup-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt
    note: Defines full backup export, forward-compatible import, active-profile adoption, and auto-backup behavior.
  - id: sync-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt
    note: Shows that sync payloads and merge behavior are profile-tagged and depend on local row state.
  - id: csv-importer
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/CsvImporter.kt
    note: Defines CSV import as a local `ExternalActivity` write path rather than a portal fetch path.
  - id: gamification-page
    type: file
    path: .almanac/pages/gamification.md
    note: Defines the profile-scoped badges, streaks, aggregate stats, and RPG-summary subsystem that shares the local persistence layer.
  - id: settings-page
    type: file
    path: .almanac/pages/settings-surface.md
    note: Defines the shared Settings route where backup or restore, body-weight edits, and delete-all-workouts operations first surface.
status: active
verified: 2026-06-21
---
Phoenix has one shared persistence cluster, but future tasks usually hit it through the wrong edge. The same SQLDelight database stores workout history, routines, cycles, assessments, gamification, sync metadata, external activities, and user profiles, while startup repair, backup import, and profile reassignment can all rewrite those rows without any live workout or network request occurring [@shared-gradle] [@schema-file] [@migration-manager] [@profile-repo] [@backup-manager].

## What belongs here

Read this hub when the symptom is about where state lives, why rows moved, or why one feature is seeing data produced by another. The data cluster spans [[local-data-model]] for schema and repair mechanics, [[profiles]] for active-profile visibility and deletion reassignment, [[data-backup-and-repair]] for backup, restore, auto-backup, and import-time adoption rules, [[routines-and-training-cycles]] for persisted workout-programming state, [[strength-assessment-and-insights]] for stored 1RM and Smart Insights state, [[gamification]] for badges, streaks, and RPG-summary state, [[equipment-rack]] for settings-backed accessory inventory plus per-session rack snapshots, and [[csv-workout-import-export]] or [[external-provider-sync]] for external data that eventually lands in local tables [@schema-file] [@profile-repo] [@backup-manager] [@csv-importer] [@sync-manager] [@gamification-page].

The key boundary is that Phoenix data is shared even when features are not. `VitruvianDatabase.sq` keeps workout, routine, assessment, badge, streak, sync, and external-activity entities in one schema; `MigrationManager` then runs startup repair across that shared space; and `DataBackupManager` exports and imports nearly all of it as one backup surface [@schema-file] [@migration-manager] [@backup-manager].

## Default read order

Start with [[local-data-model]] when the task is about migrations, schema versioning, manifest heals, missing columns, or logically inconsistent rows after an upgrade [@shared-gradle] [@migration-manager].

Go to [[profiles]] next when the same database shows different history, routines, or assessments after switching profiles, because `SqlDelightUserProfileRepository` guarantees a default profile, keeps one active profile flag, and reassigns deleted-profile data into another profile instead of dropping it [@profile-repo].

Go to [[data-backup-and-repair]] next when rows changed after restore, auto-backup, manual import, or startup repair. `DataBackupManager` imports backups against the current active profile, preserves forward compatibility for newer backup versions, and adopts some legacy unscoped rows into the active profile instead of forcing them back to `"default"` [@backup-manager]. Read [[settings-surface]] first when that flow began from the Settings tab and you still need the UI-side branch map before you debug restore or delete behavior [@settings-page].

## Choose the branch

Use [[local-data-model]] for SQLDelight schema work, manifest validation, numbered migration changes, diagnostics-history persistence questions, or cross-feature corruption that still looks structural [@shared-gradle] [@schema-file] [@migration-manager].

Use [[profiles]] for active-profile filtering, delete-time reassignment, local-to-portal profile linking, or any report that "data disappeared" after a profile switch [@profile-repo] [@sync-manager].

Use [[data-backup-and-repair]] for manual backup and restore, session auto-backup, routine auto-backup, backup version compatibility, or import counts that differ from the visible rows after restore [@backup-manager].

Use [[routines-and-training-cycles]] for persisted programming state such as routines, supersets, routine groups, cycle days, and progression overrides that are wrong even when live workout execution is fine [@schema-file] [@backup-manager].

Use [[equipment-rack]] for persisted rack snapshots on workout history, local rack inventory restore behavior, or questions about why accessory context differs between the reusable catalog and saved sessions.

Use [[strength-assessment-and-insights]] for stored 1RM assessments, Smart Insights, readiness, or percentage-based programming inputs that look stale or profile-scoped incorrectly [@schema-file] [@sync-manager].

Use [[gamification]] for badges, streaks, PR celebrations, aggregate workout stats, or RPG-summary state that looks stale, profile-scoped incorrectly, or inconsistent across repair and sync flows [@schema-file] [@sync-manager] [@gamification-page].

Use [[csv-workout-import-export]] when Strong or Hevy files are the ingress path. `CsvImporter` parses those files locally into `ExternalActivity` rows with the current profile ID and an optional `needsSync` flag, so the first question is still local data shape before it is sync policy [@csv-importer].

Use [[external-provider-sync]] when the rows came from a portal-fed provider import rather than from a local file, but keep this hub in the loop because provider data still lands in the same local persistence layer that profiles, backups, and migrations can later rewrite [@sync-manager].

## Cross-cluster boundaries

This hub sits between [[workouts]] and [[sync]]. Workout bugs often turn out to be old local rows, repaired PRs, or profile-filtered routine state; sync bugs often turn out to be imported backup rows, active-profile mismatches, or local entities that were never eligible for upload in the first place [@migration-manager] [@backup-manager] [@sync-manager].

Read [[platform-hosts]] alongside this hub when Android and iOS disagree, because the schema is shared but backup destinations, secure storage, and startup timing are not. Read [[getting-started]] first when you reached this page from search and still need the repo-wide reading order rather than only the persistence cluster.
