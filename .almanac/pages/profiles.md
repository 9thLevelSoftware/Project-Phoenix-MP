---
title: Profiles
summary: Profiles are Phoenix's local partition key for workout, routine, assessment, gamification, and sync-visible state, with a required `default` profile, one active profile at a time, delete-time reassignment, and startup repair for legacy default-scoped rows.
topics: [concepts, data, workouts, sync, auth]
sources:
  - id: profile-repo
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/UserProfileRepository.kt
    note: Defines the `UserProfile` model, default-profile bootstrap, active-profile state, Supabase linking, subscription fields, and delete-time reassignment behavior.
  - id: schema-file
    type: file
    path: shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq
    note: Defines the `UserProfile` table, the `profile_id` columns that partition many tables, and the backup-sync query that exports profile rows.
  - id: migration-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/MigrationManager.kt
    note: Defines startup profile-scope audit, automatic moves, manual choice state, and derived-stat recomputation after profile repair.
  - id: repair-state
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/ProfileScopeRepairState.kt
    note: Defines the explicit startup UI state for profile-scope repair.
  - id: routine-flow
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RoutineFlowManager.kt
    note: Shows that active-profile changes rebind routine and routine-group queries.
  - id: sync-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt
    note: Shows that sync payloads carry active-profile tagging plus a profile snapshot list.
  - id: profile-panel
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSidePanel.kt
    note: Shows that the shared UI exposes profile switching, add, edit, and delete actions from the side panel.
status: active
verified: 2026-06-25
---
Profiles are Phoenix's local identity boundary, not a cosmetic label on top of one shared history. The schema stores a `UserProfile` table and also stamps many workout, routine, assessment, progression, and gamification tables with `profile_id`, so switching the active profile changes which rows the shared UI reads even when the machine connection and app session stay the same [@schema-file] [@profile-repo].

## Core contract

Phoenix always expects one durable fallback profile named `default`. `SqlDelightUserProfileRepository` inserts that row on first boot if no profiles exist, refreshes shared `activeProfile` and `allProfiles` state on initialization, and re-activates an existing profile if corruption or migration history leaves no row marked active [@profile-repo].

The active profile is a live query input across the app. `RoutineFlowManager` uses `activeProfile.flatMapLatest` so routine and routine-group subscriptions rebind immediately when the user switches profiles, and the same `profile?.id ?: "default"` pattern appears throughout workout, analytics, integration, and settings code paths that read or write profile-scoped rows [@routine-flow].

The shared UI treats profile selection as a first-class control surface. `ProfileSidePanel` exposes profile switching directly from the main experience and only allows delete for non-`default` profiles, which matches the repository rule that `deleteProfile("default")` must fail [@profile-panel] [@profile-repo].

## What moves with a profile

Profile scope covers more than routines. The schema gives `profile_id` to workout sessions, PRs, routines, routine groups, training cycles, assessment results, progression events, earned badges, streak history, gamification stats, and RPG attributes, so "missing data after switching profiles" is often expected filtering rather than data loss [@schema-file].

Deleting a non-default profile does not cascade-delete that history. `deleteProfile()` reassigns routine, session, PR, training-cycle, badge, streak, assessment, and progression rows into either the still-active profile or `default`, deletes both profiles' derived gamification rows, and then recomputes gamification best-effort for the target profile [@profile-repo]. That makes profile deletion a merge operation for durable workout history, not a destructive archive boundary.

Backups preserve the profile model. `selectAllUserProfilesSync` exports every `UserProfile` row alongside the rest of the local backup payload, so restore can round-trip profile definitions as part of the local database rather than rebuilding them from auth or sync state later [@schema-file].

## Relationship to auth and sync

Profiles are local-first, but remote features attach to them. `linkToSupabase()` stores a `supabaseUserId` and `lastAuthAt` on the chosen profile, and the profile model also carries local subscription fields that [[premium-entitlements]] and integration UI can read without treating portal auth as the only entitlement source [@profile-repo].

[[portal-sync-transport|Portal sync transport]] still pushes one active-profile view of local data at a time. `SyncManager` derives `payloadProfileId` and `payloadProfileName` from the active profile, includes all known local profiles as `LocalProfileDto` values in the push payload, and falls back to `default` if no active profile is loaded [@sync-manager]. Remote symptoms that only affect one profile can therefore be profile-scope problems even when auth and sync are healthy.

## Startup repair and legacy data

Profiles were introduced after earlier single-profile histories already existed, so startup migration includes a profile-scope audit. `MigrationManager` compares row counts under `default` and the currently active non-default profile, auto-moves legacy default-scoped rows when the active profile is otherwise empty, and pauses in `NeedsChoice` state when both scopes already contain data [@migration-manager] [@repair-state].

That repair step exists because present-tense schema version does not prove present-tense profile correctness. A logically current database can still have durable workout history stranded under `default`, which is why [[data-backup-and-repair]] and [[local-data-model]] both treat profile-scope reconciliation as part of normal startup safety [@migration-manager].

Read [[getting-started]] first when the repo still feels broad. Read [[local-data-model]] for schema shape and reconciliation machinery, [[data-backup-and-repair]] for the user-visible repair or restore flows, [[workouts]] when the symptom is filtered workout history or routine visibility after a profile switch, and [[sync]] when only one local profile behaves differently during auth, premium, or remote sync.
