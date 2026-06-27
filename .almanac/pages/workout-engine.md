---
title: Workout Engine
summary: The workout runtime is a manager-composed state machine where MainViewModel fronts BLE connection control, routine flow, session execution, history, settings, and gamification.
topics: [systems, workouts, flows, frontend]
sources:
  - id: main-vm
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt
    note: Shows manager composition and the state exposed to shared screens.
  - id: dwsm
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt
    note: Defines the orchestration boundary between coordinator, routine flow, and active session execution.
  - id: coordinator
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/WorkoutCoordinator.kt
    note: Defines the shared workout state bus and several cross-cutting invariants.
  - id: routine-flow
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/RoutineFlowManager.kt
    note: Defines routine CRUD, retrying profile-scoped loads, and superset navigation.
  - id: lifecycle-tests
    type: file
    path: shared/src/commonTest/kotlin/com/devil/phoenixproject/presentation/manager/DWSMWorkoutLifecycleTest.kt
    note: Characterizes workout start and stop transitions and BLE command expectations.
status: active
verified: 2026-06-22
---
`MainViewModel` is the UI-facing façade for the workout system. It constructs `SettingsManager`, `HistoryManager`, `GamificationManager`, `DefaultWorkoutSessionManager`, and `BleConnectionManager`, then re-exports the flows each screen needs instead of letting screens talk to those managers directly [@main-vm].

`DefaultWorkoutSessionManager` is the orchestration boundary for live workouts. Its constructor wires together repositories, settings, sync triggers, health export, [[data-backup-and-repair|backup]], [[equipment-rack]] logic, and a `WorkoutServiceController`, but the runtime behavior is split into a `WorkoutCoordinator` state bus, a `RoutineFlowManager`, and an `ActiveSessionEngine` [@dwsm].

`WorkoutCoordinator` contains shared state but intentionally no business logic methods. It owns workout state, routine-flow state, live metrics, auto-stop state, rest timers, [[equipment-rack|rack selection]], pending mid-set weight changes, and the derived `isInWorkoutSession` flow that stays true between sets even when `workoutState` is temporarily `Idle` on the Set Ready screen [@coordinator]. That distinction matters anywhere the app needs wake-lock or navigation behavior tied to the whole session instead of the active set.

Routine orchestration is profile-aware and retrying. `RoutineFlowManager` subscribes to routines and routine groups for the active profile, filters out `cycle_routine_` template routines from the user-visible routine list, and retries failed reactive loads up to three times with backoff so a transient migration-time database error does not permanently blank the routine UI until restart [@routine-flow].

The common characterization tests document several runtime contracts. `startWorkout()` sets `Initializing` synchronously before the coroutine runs, optionally emits a `5..1` countdown before `Active`, and sends at least one BLE workout command [@lifecycle-tests]. For non-Echo activation modes the tests expect exactly one activation config packet and no separate legacy start packet [@lifecycle-tests].

Stopping a workout is also intentionally asymmetric. `stopWorkout(exitingWorkout = true)` returns to `Idle`, while `stopWorkout(exitingWorkout = false)` enters `SetSummary`, and a guard flag makes a second stop request a no-op while the first is still in progress [@lifecycle-tests].

Read [[workouts]] first if the failure could still belong to the broader workout cluster instead of live session orchestration alone. Read [[routines-and-training-cycles]] next if the task is about programming workouts, [[vitruvian-ble-protocol]] if it is about trainer commands, rep counting, or packet shape changes, [[equipment-rack]] if the behavior changes with accessory load context, [[local-data-model]] if live-session behavior looks corrupted by stored routine or history state, [[gamification]] if the bug starts after workout completion rather than during a live set, or [[platform-hosts]] if Android and iOS diverge around foreground service or native lifecycle behavior.
