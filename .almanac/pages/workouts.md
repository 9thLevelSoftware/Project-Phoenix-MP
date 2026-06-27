---
title: Workouts
summary: This page is the hub for the workout cluster, routing BLE, live session, routine-programming, diagnostics, safety-feedback, assessment, equipment-rack, gamification, persistence, and platform-behavior questions to the right synthesis page.
topics: [systems, workouts, flows]
sources:
  - id: app-architecture-page
    type: file
    path: .almanac/pages/app-architecture.md
    note: Establishes that shared Compose UI talks through MainViewModel into the workout managers.
  - id: main-viewmodel
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt
    note: Shows that workout-facing UI state is composed from manager boundaries rather than handled directly in screens.
  - id: ble-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/KableBleConnectionManager.kt
    note: Shows the live BLE connection and command boundary that sits under the workout system.
  - id: session-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/DefaultWorkoutSessionManager.kt
    note: Shows the live session state machine and workout flow coordination boundary.
  - id: workout-engine-page
    type: file
    path: .almanac/pages/workout-engine.md
    note: Defines the live session orchestration boundary and coordinator contracts.
  - id: routine-repo
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/WorkoutRepository.kt
    note: Shows the programmable routine and routine-session persistence boundary above live session execution.
  - id: routines-page
    type: file
    path: .almanac/pages/routines-and-training-cycles.md
    note: Defines the programmable workout layer above live session control.
  - id: assessment-vm
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/AssessmentViewModel.kt
    note: Shows the local assessment workflow that consumes workout data and writes back durable performance state.
  - id: assessment-page
    type: file
    path: .almanac/pages/strength-assessment-and-insights.md
    note: Defines the local analytics and 1RM assessment layer that feeds routine programming.
  - id: safety-page
    type: file
    path: .almanac/pages/workout-safety-and-feedback.md
    note: Defines workout cue playback, voice-stop gating, and platform safety-feedback behavior.
  - id: ble-page
    type: file
    path: .almanac/pages/vitruvian-ble-protocol.md
    note: Defines trainer communication, packet parsing, and command semantics.
  - id: diagnostics-page
    type: file
    path: .almanac/pages/machine-diagnostics.md
    note: Defines the machine diagnostics screen, redacted export contract, and diagnostic packet decoding workflow.
  - id: data-page
    type: file
    path: .almanac/pages/local-data-model.md
    note: Defines the persistence contracts that routines, history, and sync depend on.
  - id: backup-repair-page
    type: file
    path: .almanac/pages/data-backup-and-repair.md
    note: Defines streamed backup or restore flows, auto-backup timing, and startup repair behavior that can change workout-visible state.
  - id: hosts-page
    type: file
    path: .almanac/pages/platform-hosts.md
    note: Defines the Android and iOS runtime differences that affect workout behavior.
  - id: rack-page
    type: file
    path: .almanac/pages/equipment-rack.md
    note: Defines local accessory-load inventory, routine defaults, live-set selection, and persisted rack snapshots.
  - id: gamification-page
    type: file
    path: .almanac/pages/gamification.md
    note: Defines profile-scoped badges, streaks, celebrations, and RPG summaries computed from workout history.
status: active
verified: 2026-06-21
---
Phoenix workout behavior is one cluster, but the code and wiki split it into nine boundaries: trainer communication in `KableBleConnectionManager`, live session orchestration in `DefaultWorkoutSessionManager`, programmable workout definitions in routine repositories and editors, machine diagnostics for fault and crash snapshots, safety and feedback behavior around cues and voice stop, local performance intelligence in the assessment or insight layer, local accessory-load handling in [[equipment-rack]], workout-derived reward state in [[gamification]], and persistence plus host-boundary constraints in [[local-data-model]], [[data-backup-and-repair]], and [[platform-hosts]] [@ble-manager] [@session-manager] [@routine-repo] [@assessment-vm] [@diagnostics-page] [@rack-page] [@gamification-page] [@data-page] [@backup-repair-page] [@hosts-page].

## Cluster map

The page links map directly onto those code boundaries: [[vitruvian-ble-protocol]] explains the BLE layer under `KableBleConnectionManager`, [[workout-engine]] explains the session state and control layer under `DefaultWorkoutSessionManager`, [[routines-and-training-cycles]] explains the editable programming layer above those runtime managers, [[machine-diagnostics]] explains the fault-snapshot and troubleshooting export path, [[workout-safety-and-feedback]] explains cue playback and voice-stop behavior around active workouts, [[strength-assessment-and-insights]] explains the local analytics layer that both consumes and informs workout data, [[equipment-rack]] explains accessory-load defaults and saved rack context, [[gamification]] explains workout-derived PR celebrations and badge state, and [[data-backup-and-repair]] explains the backup, restore, and startup-repair layer that can change workout-visible history without touching the session managers [@ble-page] [@workout-engine-page] [@routines-page] [@diagnostics-page] [@safety-page] [@assessment-page] [@rack-page] [@gamification-page] [@backup-repair-page].

Read [[project-phoenix]] before this cluster when the task depends on supported hardware, per-cable load assumptions, or the repo's preservation goals after the original Vitruvian shutdown. Those product constraints explain why backward-compatible firmware handling and local-first workout behavior are treated as design requirements rather than legacy baggage.

Read [[frontend]] before this cluster when the symptom is clearly in shared screen ownership, route placement, or Compose state projection and you still do not know which workout leaf page owns the underlying behavior.

## Default read order

Read this cluster in dependency order when the bug source is unclear. [[vitruvian-ble-protocol]] explains what the machine sends and accepts, [[workout-engine]] explains how shared runtime state reacts to that data, [[machine-diagnostics]] explains the live fault and crash snapshot branch under the same BLE connection, [[workout-safety-and-feedback]] explains cue and safe-stop behavior wrapped around that session runtime, [[routines-and-training-cycles]] explains how pre-authored plans feed the live session, [[equipment-rack]] explains how accessory context modifies or annotates that flow, [[gamification]] explains how saved workouts turn into PR or badge state, and [[strength-assessment-and-insights]] explains the local 1RM and analytics layer that feeds percentage-based programming and exercise detail history [@ble-page] [@workout-engine-page] [@diagnostics-page] [@safety-page] [@routines-page] [@rack-page] [@gamification-page] [@assessment-page].

`MainViewModel` is the UI entry point, not the system boundary. [[app-architecture]] explains that screens mostly talk to one shared façade, but `MainViewModel` itself composes manager-owned flows instead of implementing workout behavior directly, so debugging usually belongs in one of the lower pages in this cluster rather than in the screen tree [@app-architecture-page] [@main-viewmodel] [@workout-engine-page].

[[data]], [[local-data-model]], [[data-backup-and-repair]], and [[platform-hosts]] are cross-cutting constraints, not leaf pages. Shared schema shape, streamed restore behavior, startup repair, profile-scoped repository behavior, and imported external rows can change workout outcomes without touching workout managers, while host-specific BLE permissions, secure storage, and background-service behavior can change workout reliability without changing shared workout code [@data-page] [@backup-repair-page] [@hosts-page].

## Choose the leaf page

Use [[vitruvian-ble-protocol]] first for scan behavior, reconnects, packet parsing, mode IDs, command formats, or trainer firmware mismatches [@ble-page].

Use [[workout-engine]] first for countdowns, stop-state transitions, set-ready behavior, rest timers, active session state, and command timing [@workout-engine-page].

Use [[machine-diagnostics]] first for machine fault codes, temperatures, crash snapshots, the copyable troubleshooting export, or questions about how the diagnostics screen gets populated from BLE state [@diagnostics-page].

Use [[workout-safety-and-feedback]] first for beeps, haptic cues, rep-count announcements, countdown tick playback, voice emergency stop, or differences between cue behavior and the actual workout-state transition that follows [@safety-page].

Use [[routines-and-training-cycles]] first for routine editor screens, supersets, template routine filtering, routine groups, cycle day advancement, day-numbered program logic, or percentage-based programming setup that is wrong before the live session engine starts consuming the plan [@routines-page].

Use [[equipment-rack]] first for weighted vests, counterweights, default rack items on routine exercises, displayed effective load versus machine load, or saved accessory context in workout history [@rack-page].

Use [[gamification]] first for PR celebrations, missing badges, streak state, or the RPG profile and badge-progress surface that is computed from completed workout history [@gamification-page].

Use [[strength-assessment-and-insights]] first for 1RM assessment, Smart Insights, readiness calculations, plateau detection, or percentage-based routine weights that depend on stored exercise maxes [@assessment-page].

Use [[data]] first when the symptom looks like shared persistence drift across workouts, profiles, backup import, or sync-visible rows. Use [[local-data-model]] next when the issue is specifically schema, migration, or damaged-row state [@data-page].

Use [[data-backup-and-repair]] when the symptom starts at session completion, missing auto-backup files, restore counts, post-upgrade repair prompts, or workout history that changed after backup import rather than after live workout execution [@backup-repair-page].

Use [[platform-hosts]] when the symptom differs between Android and iOS, especially around BLE permissions, secure storage, boot order, or background workout continuity [@hosts-page].

## Wider context

Read [[getting-started]] first when you need the shortest repo-wide reading order before deciding whether the symptom belongs in workouts, sync, or a cross-cutting page.
