---
title: Local Data Model
summary: Local persistence uses SQLDelight plus a second schema-reconciliation layer and startup repair passes for databases that may be logically current but physically incomplete or historically inconsistent.
topics: [systems, data, flows, workouts, sync]
sources:
  - id: shared-gradle
    type: file
    path: shared/build.gradle.kts
    note: Defines SQLDelight schema versioning and the schema manifest validation task.
  - id: schema-file
    type: file
    path: shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq
    note: Defines the current database schema and many migration-added columns.
  - id: migration-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/MigrationManager.kt
    note: Defines startup migration and repair passes, including profile-scope and orphan repair.
  - id: schema-manifest
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/SchemaManifest.kt
    note: Defines table, column, and index reconciliation beyond numbered SQLDelight migrations.
  - id: backup-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt
    note: Defines backup/export/import contracts and streaming safeguards.
  - id: kable-repo
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt
    note: Shows that current diagnostics publication is a live in-memory BLE flow plus logs rather than a database write path.
  - id: diagnostics-vm
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/DiagnosticsViewModel.kt
    note: Shows that current diagnostics UI reads live `BleRepository.diagnostics` state rather than local history queries.
status: active
verified: 2026-06-22
---
The shared database uses SQLDelight, but numbered migrations are not the whole persistence story. `shared/build.gradle.kts` sets the database version to `35`, describes that as the initial schema plus `34` numbered migrations, and adds a custom `validateSchemaManifest` task that fails the build if any schema column lacks provenance in the main schema, migrations, or manifest heal operations [@shared-gradle].

## Persistence contract

The schema itself is broad and profile-aware. `VitruvianDatabase.sq` persists workout sessions, metric samples, PRs, routines, supersets, routine groups, training cycles, completed sets, progressions, gamification state, connection logs, diagnostics snapshots, sync metadata, and external integration entities, with many tables carrying `profile_id`, `updatedAt`, `serverId`, and `deletedAt` fields [@schema-file]. Read [[profiles]] alongside this page when the bug is really about active-profile filtering, delete-time reassignment, or why the same local database can show different slices of data after a profile switch.

## Repair layers

Startup repair runs after migrations, not instead of them. `MigrationManager.runMigrations()` refreshes profiles, cleans fabricated routine session IDs, normalizes legacy workout modes, backfills routine names into old workout rows, repairs PRs from workout history, audits profile-scoped data, and checks for orphaned records after all other passes [@migration-manager]. The profile audit is not a side detail here; it is the reason a database can be structurally current but still need row movement or a user choice before the active profile sees the expected data, which [[profiles]] covers as a separate concept page.

The repo also maintains a second schema-reconciliation layer for databases that may be logically versioned but physically incomplete. `SchemaManifest.kt` says it replaced multiple fragmented preflight mechanisms with one manifest covering bootstrap tables, migration-created tables, and initial-schema tables [@schema-manifest]. The column-heal path deliberately uses blind `ALTER TABLE ADD COLUMN` with duplicate-column handling because iOS `NativeSqliteDriver` can make reader-backed existence checks stale across connection pools [@schema-manifest].

This makes database safety here more about idempotent repair than about trusting a single perfect migration chain. If a future change adds tables, columns, or indexes, it needs to consider numbered migration SQL, the manifest, and the startup repair path together [@shared-gradle] [@schema-manifest] [@migration-manager]. [[workouts]] and [[sync]] both converge on this layer, so persistence bugs that look feature-specific can still be shared database-state failures. Read [[data]] first when the symptom might still be profile scope, backup import, or external-data ingress rather than schema mechanics.

## Adjacent flows

Backup, restore, and startup-repair behavior are first-class parts of this persistence layer, but they now have their own retrieval page. Read [[data-backup-and-repair]] when the task is about streamed export or import, auto-backup timing, profile-scope repair, or other user-visible data recovery behavior [@backup-manager] [@migration-manager].

`DiagnosticsHistory` is currently reserved schema, not an active feature path. `VitruvianDatabase.sq` and `SchemaManifest.kt` still define the table plus recent or fault-only queries, but the live diagnostics flow in `KableBleRepository` only updates the in-memory `BleRepository.diagnostics` state and connection-log stream, and `DiagnosticsViewModel` renders directly from that live state instead of reading SQLDelight history rows [@schema-file] [@kable-repo] [@diagnostics-vm]. Read [[machine-diagnostics]] with this in mind when a future task proposes persisting diagnostic snapshots, because the schema surface already exists but the current product path is live-only.

## Reading boundary

For tasks that involve remote state, read [[sync]] and then [[portal-sync-transport]] after this page. For tasks that affect user-visible program editing, pair this page with [[workouts]] and [[routines-and-training-cycles]]. Read [[data-backup-and-repair]] when the symptom is restore, import, migration-time repair, or missing backup files. Read [[platform-hosts]] when a persistence issue differs between Android and iOS because backup paths, secure storage, and startup timing are host-specific even though the schema is shared.
