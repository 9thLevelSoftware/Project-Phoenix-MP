---
title: Equipment Rack
summary: Equipment Rack is Phoenix's local accessory-load subsystem, combining a settings-backed inventory with routine defaults, live-set selection, persisted workout snapshots, and backup or sync preservation of the recorded context.
topics: [systems, workouts, data, frontend, sync]
sources:
  - id: rack-models
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/EquipmentRack.kt
    note: Defines rack item categories, behaviors, active selections, and computed load-adjustment fields.
  - id: rack-repo
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/EquipmentRackRepository.kt
    note: Defines the settings-backed rack inventory, JSON storage key, and enabled-item resolution rules.
  - id: rack-usecase
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ApplyEquipmentRackLoadUseCase.kt
    note: Defines how selected rack items affect display load and machine weight, including Echo-mode behavior.
  - id: rack-screen
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EquipmentRackScreen.kt
    note: Defines the dedicated management screen reached from Settings and the item-editing UI.
  - id: routine-editor
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseEditBottomSheet.kt
    note: Shows that routine exercises can store default rack-item selections during workout editing.
  - id: routine-flow
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RoutineFlowManager.kt
    note: Defines how routine exercise defaults are resolved into a live rack snapshot before Set Ready.
  - id: active-session
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt
    note: Defines live selection updates, bodyweight recalculation, and workout-session persistence of rack context.
  - id: backup-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.kt
    note: Defines backup export or import of rack inventory and rack context stored on workout sessions.
  - id: sync-repo
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightSyncRepository.kt
    note: Defines how portal sync preserves local rack context on workout sessions because the server does not own those fields.
status: active
verified: 2026-06-24
---
Equipment Rack is a local accessory-load subsystem, not a trainer capability. Phoenix lets users define accessory items such as weighted vests, dip belts, chains, bands, assistance, and display-only attachments, then reuse those items in routines and live workouts without treating them as portal-managed entities [@rack-models] [@rack-repo] [@rack-screen].

## What it stores

The inventory itself lives in settings, not in SQLDelight. `SettingsEquipmentRackRepository` serializes the whole rack-item list into the `equipment_rack_items_v1` JSON key and exposes it as a shared `StateFlow`, so the canonical item catalog is device-local and can load even when no database migration or portal session has run yet [@rack-repo].

Each `RackItem` carries a stable ID, category, weight, behavior, enabled flag, sort order, and timestamps, while `ActiveRackSelection` keeps only a list of selected IDs and deduplicates them before use [@rack-models]. Disabled items stay in the inventory but are filtered out when the app resolves an active selection [@rack-repo].

## How load adjustment works

The load math distinguishes display weight from machine weight. `ApplyEquipmentRackLoadUseCase.calculate()` sums enabled added-resistance items separately from counterweights, adds or subtracts those totals from the displayed load, and then derives an adjusted per-cable machine weight that is clamped into Phoenix's normal machine-safe range [@rack-usecase].

Echo mode is the main exception. In Echo mode the use case leaves the programmed machine weight unchanged even when rack items are selected, so the rack still changes displayed effective load and workout-history context without mutating the machine target the way non-Echo cable workouts do [@rack-usecase].

Display-only accessories are metadata only. They appear in the selected-item snapshot and UI summaries, but the use case excludes them from both added-resistance and counterweight totals because only `ADDED_RESISTANCE` and `COUNTERWEIGHT` behaviors contribute to the load calculation [@rack-models] [@rack-usecase].

## Where selections come from

The subsystem has two authoring paths. [[routines-and-training-cycles]] can store default rack-item IDs per routine exercise through `ExerciseEditBottomSheet`, and the dedicated `EquipmentRackScreen` lets users manage the reusable item catalog itself [@routine-editor] [@rack-screen].

Before a routine-driven set reaches Set Ready, `RoutineFlowManager.applyDefaultRackSelectionForExercise()` resolves the exercise's default IDs against the current enabled inventory, computes a rack adjustment from the exercise's own programmed weight, and seeds the coordinator with both the selected IDs and a precomputed JSON snapshot of the resolved items [@routine-flow]. That pre-seeding exists because Set Ready can still be showing the previous set's mirrored workout parameters during the transition into the next live set [@routine-flow].

During active workouts, `ActiveSessionEngine.updateActiveRackSelection()` treats bodyweight and cable exercises differently. Bodyweight exercises recompute the active rack adjustment immediately so vest or counterweight changes affect effective-load calculations on the current flow, while cable exercises keep the active IDs for UI continuity but do not rewrite the already-started machine-load snapshot mid-set [@active-session].

## What gets persisted

The selected rack context is captured on workout history rows, not only in transient UI state. `ActiveSessionEngine` writes `externalAddedLoadKg`, `counterweightKg`, and `rackItemsJson` into each saved `WorkoutSession`, so history, analytics, backup export, and later sync can preserve what accessory context the user recorded for that session [@active-session].

That persistence boundary is why Equipment Rack belongs in both [[workouts]] and [[data]]. The reusable item catalog is settings-backed, but the resolved per-session snapshot becomes durable workout data after save time [@rack-repo] [@active-session].

## Backup and sync boundary

Backups preserve both layers. `DataBackupManager` exports or imports the shared rack inventory and also round-trips the per-session rack snapshot stored on workout sessions, so a restore can recover both the reusable accessory catalog and the historical context attached to completed workouts [@backup-manager].

Portal sync does not own rack context, but it preserves it locally. `SqlDelightSyncRepository` keeps `externalAddedLoadKg`, `counterweightKg`, and `rackItemsJson` from the existing local session when merging remote workout rows, which means sync treats those fields as local-only annotations rather than portal-authoritative data [@sync-repo]. Read [[sync]] only after this page when a rack-related mismatch appears across devices, because the first question is whether the context was ever stored locally on the device that created the session.

## Read next

Read [[workout-engine]] when the question is about Set Ready, bodyweight recalculation, or session-save timing around rack selections. Read [[routines-and-training-cycles]] when the question is about default rack items on programmed exercises. Read [[data-backup-and-repair]] when rack inventory or historical rack context changed after restore.
