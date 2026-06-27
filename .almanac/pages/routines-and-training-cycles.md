---
title: Routines And Training Cycles
summary: Routines are the programmable workout layer above live session control, with supersets, per-set overrides, and rolling training cycles that are keyed by day order rather than weekdays.
topics: [systems, workouts, concepts, data]
sources:
  - id: routine-flow-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RoutineFlowManager.kt
    note: Defines active-profile-scoped routine loading, group handling, superset traversal, and routine-specific lifecycle hooks.
  - id: db-schema
    type: file
    path: shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq
    note: Defines Routine, RoutineExercise, Superset, RoutineGroup, TrainingCycle, and related persistence columns.
  - id: cycle-models
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/TrainingCycleModels.kt
    note: Defines the day-numbered training cycle model and progress semantics.
  - id: cycle-repo
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightTrainingCycleRepository.kt
    note: Shows persistence behavior for cycle activation and progress initialization.
  - id: profile-repo
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/UserProfileRepository.kt
    note: Shows that deleting a profile reassigns routine and cycle rows instead of dropping them.
status: active
verified: 2026-06-24
---
Routines and training cycles are separate layers. Routines describe an ordered list of exercises, sets, and per-exercise configuration, while training cycles schedule routines across numbered days in a rolling sequence [@db-schema] [@cycle-models].

Routine persistence is richer than a simple exercise list. `RoutineExercise` stores set reps, per-set weights, mode, eccentric load, echo level, progression, rest timing, AMRAP flags, PR-percentage scaling, stall-detection overrides, rep-count timing, warmup sets, and default [[equipment-rack]] item IDs [@db-schema]. Those PR-percentage fields consume the stored exercise 1RM values described in [[strength-assessment-and-insights]], so routine weights depend on the local assessment layer even before a workout starts. Supersets are first-class rows with their own container table instead of being encoded only as exercise metadata [@db-schema].

Routine visibility is profile-scoped, not global. `RoutineFlowManager` subscribes to `userProfileRepository.activeProfile`, reloads routines and routine groups when that profile changes, and injects the active profile ID into routine save and update operations, while the schema and cycle repository both persist `profile_id` on routine and training-cycle rows [@routine-flow-manager] [@db-schema] [@cycle-repo]. Read [[profiles]] with this page when a routine or cycle appears to disappear after switching profiles, because the list usually changed with the active profile rather than with the live workout engine.

Routine groups are local-only organization buckets. `RoutineGroup` is a separate table, and `RoutineFlowManager` loads groups through a concrete `SqlDelightWorkoutRepository` cast because the generic repository interface does not expose group CRUD [@db-schema] [@routine-flow-manager].

Training cycles are explicitly not calendar-bound weekly programs. The `TrainingCycle` model says the system replaces weekday scheduling with `Day 1`, `Day 2`, and so on, and `CycleDay` can be either a workout day linked to a routine or a rest day with modifiers [@cycle-models].

Cycle progression is stateful. `CycleProgress` tracks current day, completed and missed day sets, `rotationCount`, the last completed date, and whether enough calendar days have passed to auto-advance the plan [@cycle-models]. The same model triggers a recovery dialog after three days away from training [@cycle-models].

Activating a cycle initializes progress in the repository if needed. `SqlDelightTrainingCycleRepository.saveCycle()` deactivates other cycles for the profile, marks the chosen cycle active, and inserts cycle progress with day `1` when no progress row exists yet [@cycle-repo].

`RoutineFlowManager` also treats cycle template routines specially. It loads all routines for the active profile but hides routines whose IDs start with `cycle_routine_` from the normal routine list, which keeps internal cycle templates from appearing as user-authored routines in the main routine UI [@routine-flow-manager].

Profile deletion does not drop routine programming on the floor. `UserProfileRepository.deleteProfile()` reassigns routines and training cycles into the surviving target profile inside the same transaction that reassigns other profile-scoped data, so post-delete routine surprises usually come from reassignment or active-profile changes rather than hard data loss [@profile-repo]. That behavior is part of the same profile-scope model described in [[profiles]].

Read [[workouts]] first if the issue could be in trainer communication or live-session orchestration rather than in routine data itself. Read [[workout-engine]] next for the shared runtime that consumes routine definitions, [[strength-assessment-and-insights]] for the stored 1RM values and local analytics that feed percentage-based programming, [[profiles]] for active-profile visibility or delete-time reassignment, or [[local-data-model]] for the persistence and migration layer behind routines and cycles.
