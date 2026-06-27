---
title: Strength Assessment And Insights
summary: Phoenix computes one-rep-max assessments and Smart Insights locally from workout and BLE data, then reuses those results in routine programming and optional portal sync.
topics: [systems, workouts, data, frontend]
sources:
  - id: assessment-engine
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/assessment/AssessmentEngine.kt
    note: Defines the load-velocity regression, stop threshold, and next-weight suggestion logic.
  - id: assessment-models
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/assessment/AssessmentModels.kt
    note: Defines the assessment protocol defaults such as min sets, max sets, and 1RM velocity.
  - id: assessment-vm
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/AssessmentViewModel.kt
    note: Defines the wizard flow, BLE velocity capture, total-versus-per-cable conversions, and save behavior.
  - id: assessment-repo
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/AssessmentRepository.kt
    note: Defines the persistence contract for assessment sessions, results, and exercise 1RM updates.
  - id: assessment-repo-impl
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightAssessmentRepository.kt
    note: Shows the assessment session marker, SQLDelight persistence, and exercise 1RM mutation.
  - id: exercise-detail
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseDetailScreen.kt
    note: Shows where users launch 1RM assessment from the exercise detail screen.
  - id: insights-screen
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SmartInsightsTab.kt
    note: Defines the Smart Insights screen, its shared fetch anchor, and the insight cards it computes.
  - id: insights-engine
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/SmartSuggestionsEngine.kt
    note: Defines the weekly volume, balance, neglected exercise, plateau, and time-of-day computations.
  - id: readiness-engine
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/premium/ReadinessEngine.kt
    note: Defines the ACWR readiness computation and insufficient-data guards.
  - id: enhanced-main
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt
    note: Shows that Smart Insights is exposed directly from the main bottom navigation.
  - id: routine-weights
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/ResolveRoutineWeightsUseCase.kt
    note: Shows that stored exercise 1RM values feed percentage-based routine weight resolution.
  - id: sync-manager
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncManager.kt
    note: Shows that assessment rows are included in the final non-session portal sync payload.
status: active
verified: 2026-06-10
---
Phoenix has a local performance-intelligence layer that sits between [[workout-engine]] and [[premium-entitlements]] but is not the same thing as either. The app computes one-rep-max assessments from live trainer velocity data, computes Smart Insights from local workout history, reuses stored 1RM values in [[routines-and-training-cycles|routine programming]], and only later includes assessment rows in optional portal sync [@assessment-vm] [@insights-screen] [@routine-weights] [@sync-manager].

The assessment entry point is per exercise, not global. `ExerciseDetailScreen` shows an `Assess 1RM` button and only enables it while the trainer is connected, so the wizard is designed around live machine telemetry rather than manual data entry [@exercise-detail].

The wizard is a state machine in `AssessmentViewModel`: exercise selection, optional instruction media, progressive loading, results, saving, and completion [@assessment-vm]. The capture phase listens to the shared live metric flow, averages absolute cable velocities, ignores values at or below `50 mm/s`, and records fixed three-rep sets from that BLE stream instead of asking the user to type observed speeds [@assessment-vm].

The assessment math is pure domain logic. `AssessmentEngine` fits ordinary least squares over load versus mean concentric velocity, requires at least two sets, rejects non-decreasing slopes, extrapolates 1RM at `0.17 m/s`, stops when the latest set reaches `0.3 m/s` or lower, and caps the protocol at five sets with `10 kg` default jumps that shrink near the threshold [@assessment-engine] [@assessment-models].

The page boundary that future work must preserve is weight semantics. The wizard displays and records total machine weight, but `Exercise.oneRepMaxKg`, [[routines-and-training-cycles|routine percentages]], and the broader data model store per-cable values, so `AssessmentViewModel` divides the average captured load by two before persistence and the rest of the workout system consumes the stored per-cable number [@assessment-vm] [@routine-weights].

Assessment persistence updates more than one table. `AssessmentRepository.saveAssessmentSession()` creates a synthetic workout session whose `routineName` is `__ASSESSMENT__`, inserts an `AssessmentResult` row with the serialized load-velocity points, and updates the exercise's stored `oneRepMaxKg`, preferring a user override when present [@assessment-repo] [@assessment-repo-impl]. That means assessment changes affect history, progression charts, and future percentage-based routines even when no remote sync runs [@assessment-repo-impl] [@routine-weights].

Smart Insights is also local-first and snapshot-based. `SmartInsightsTab` takes one 28-day fetch anchored to a single `now` timestamp, then computes all cards from that shared anchor so query time and window math do not drift apart between sections [@insights-screen]. The screen computes weekly volume, push-pull-legs balance, neglected exercises, plateau detection, time-of-day preference, and ACWR readiness without calling the portal [@insights-screen] [@insights-engine] [@readiness-engine].

The plateau and readiness rules are stricter than their UI labels imply. `SmartSuggestionsEngine.detectPlateaus()` collapses multiple sets on the same local day into one data point per exercise before counting consecutive same-weight days, which avoids false plateaus from multi-set routines [@insights-engine]. `ReadinessEngine` returns insufficient data unless history spans at least 28 days, at least three sessions exist in the last 14 days, and the chronic weekly average is non-zero [@readiness-engine].

These features live in `domain.premium`, but the current mobile UI does not entitlement-gate them. `EnhancedMainScreen` always exposes Smart Insights in the main bottom bar, and the assessment flow is launched from exercise detail based on connection state rather than paid-user state [@enhanced-main] [@exercise-detail]. Read [[premium-entitlements]] only when the question is whether remote upload, portal state, or subscription policy should also gate this local analytics layer.

Portal sync carries assessment results as non-session data in the final batch, but the current sync path sends assessment rows rather than recomputing insights remotely [@sync-manager]. Read [[workouts]] first when search lands here but the root cause could still be in live session capture, routine execution, or broader workout-state handling instead of in the assessment or insight layer itself. Read [[sync]] when the issue is missing portal assessment history or batch payload shape. Read [[local-data-model]] when the issue is about where assessment rows or stored 1RM values live locally.
