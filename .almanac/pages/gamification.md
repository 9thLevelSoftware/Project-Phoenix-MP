---
title: Gamification
summary: Gamification is Phoenix's profile-scoped badges, streaks, celebration, and RPG-summary subsystem, computed locally from workout history but partially merged through portal sync and surfaced through the Badges, Home, and workout-completion UI.
topics: [systems, workouts, data, sync, frontend]
sources:
  - id: gamification-repo
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/GamificationRepository.kt
    note: Defines the profile-scoped gamification contract for badges, streaks, stats, progress, and RPG persistence.
  - id: gamification-repo-impl
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightGamificationRepository.kt
    note: Defines local badge and streak persistence, stat recalculation, and streak-at-risk logic.
  - id: badge-definitions
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/BadgeDefinitions.kt
    note: Defines the local badge catalog and requirement metadata.
  - id: gamification-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/GamificationManager.kt
    note: Defines post-workout PR tracking, badge awarding, celebration events, and gamification-enabled gating.
  - id: user-preferences
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/UserPreferences.kt
    note: Defines the user-facing gamification enable or disable preference.
  - id: badges-screen
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/BadgesScreen.kt
    note: Defines the main gamification UI, streak widget, badge grid, and on-demand RPG card loading.
  - id: gamification-vm
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/GamificationViewModel.kt
    note: Defines profile-reactive badge, streak, stat, and RPG state for the badges UI.
  - id: sync-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt
    note: Defines pull-page preparation for badges, gamification stats, and RPG attributes during remote sync.
  - id: sync-repo
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightSyncRepository.kt
    note: Defines union merge for badges and server-wins merge for aggregate gamification stats.
  - id: migration-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/migration/MigrationManager.kt
    note: Defines post-repair recomputation of gamification stats and RPG attributes during startup repair paths.
status: active
verified: 2026-06-25
---
Gamification is a local-first workout-summary subsystem layered on top of [[workouts]] and [[data]], not a separate product area. Phoenix computes badges, streaks, aggregate workout stats, and an RPG-style attribute profile from saved workout history, then surfaces that state through the post-workout celebration flow, the Badges screen, and streak-oriented home UI [@gamification-repo] [@gamification-manager] [@badges-screen].

## Scope and profile boundary

The whole contract is profile-scoped. `GamificationRepository` requires a `profileId` for earned badges, streak info, aggregate stats, badge progress, and RPG persistence, and `GamificationViewModel` resolves the active profile reactively before loading badges or streak state [@gamification-repo] [@gamification-vm]. When gamification appears to "reset," read [[profiles]] before blaming the badge logic, because Phoenix isolates this state per local profile rather than globally across the app.

The badge catalog itself is local code. `BadgeDefinitions` is the source of truth for available badges and their requirement metadata, so adding, renaming, or reclassifying a badge is an app-code change rather than a portal-content change [@badge-definitions].

## When it updates

The main write path runs after workout persistence, not during live set control. `GamificationManager.processPostSaveEvents()` checks PRs, conditionally emits celebration events, recalculates aggregate stats, and then awards any newly satisfied badges after a workout session has already been saved [@gamification-manager]. That ordering is why badge or PR symptoms often need [[workout-engine]] and [[local-data-model]] in the same reading pass.

The manager also has explicit exclusions. It skips PR tracking when the completed workout has no exercise ID, when the mode is Just Lift, or when the mode is Echo, so a missing PR or badge in those paths can be expected behavior rather than a persistence bug [@gamification-manager].

Phoenix can disable the celebration layer without removing the stored data. `UserPreferences.gamificationEnabled` gates badge awarding, celebration sounds, and PR dialogs in `GamificationManager`, but the underlying repositories and screens still define the subsystem's storage and query surface [@user-preferences] [@gamification-manager].

## What the local repository computes

`SqlDelightGamificationRepository` recomputes aggregate stats from workout history instead of incrementing counters opportunistically. `updateStats()` recounts workouts, reps, volume, unique exercises, and PR totals from the database, then calculates current and longest streaks from stored workout dates before upserting one stats row for the active profile [@gamification-repo-impl].

The repository also derives streak risk locally. `getStreakInfo()` marks a streak as at risk when the last workout date is before today while the current streak is still positive, which is why the UI can warn about a pending streak break without any remote request [@gamification-repo-impl] [@badges-screen].

## UI surface

The main UI for this subsystem is `BadgesScreen`, which loads the RPG card on demand, renders a streak widget plus badge grid, and reads badge, stat, and uncelebrated-badge state through `GamificationViewModel` [@badges-screen] [@gamification-vm]. The view model computes the RPG profile only when that screen is opened, then persists the computed profile back through the repository, so RPG attributes are cached output of local workout data rather than an always-hot background job [@gamification-vm].

The workout-completion path is separate from the browsing UI. `GamificationManager` emits PR celebration events and newly earned badge lists directly into shared flows after post-save evaluation, which is why a badge can appear as a completion dialog before the user ever visits the Badges screen [@gamification-manager].

## Sync contract

Remote sync treats different gamification entities differently. `SyncManager.mergePullPage()` prepares pulled badges, gamification stats, and RPG attributes as separate payloads before handing them to the sync repository, so the remote contract already distinguishes additive achievements from aggregate counters [@sync-manager].

Badges use union semantics. `SqlDelightSyncRepository.mergeBadges()` inserts remote badges with `INSERT OR IGNORE`, preserving both local and remote-earned achievements across devices instead of overwriting one side with the other [@sync-repo].

Aggregate stats use a server-wins merge with local-field preservation. `mergeGamificationStats()` overwrites totals such as workouts, reps, volume, and streak counts from the server aggregate, but it keeps local-only fields such as `uniqueExercisesUsed`, `prsAchieved`, `lastWorkoutDate`, and `streakStartDate` from the existing row when present [@sync-repo]. Read [[sync]] with this page when badge counts differ across devices, because the repo intentionally uses different reconciliation rules for badges versus aggregate counters.

## Repair and recomputation

Startup repair paths can rewrite this subsystem even without a new workout. `MigrationManager` recomputes gamification stats and saves a fresh RPG profile after certain repair flows, so stale badges or streaks after an upgrade can be a migration aftermath problem rather than a workout-save regression [@migration-manager].

## Read next

Read [[workout-engine]] when the question is about when a workout becomes eligible for PR or badge processing. Read [[strength-assessment-and-insights]] when the symptom is really about the local analytics layer that sits beside gamification, not inside it. Read [[sync]] when the symptom only appears after multi-device use or portal pull.
