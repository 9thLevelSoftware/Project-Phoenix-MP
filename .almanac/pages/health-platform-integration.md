---
title: Health Platform Integration
summary: Phoenix writes completed workouts to the device health store and imports the latest eligible body weight back into local state, with materially different Android Health Connect and iOS HealthKit behavior and no portal transport in the path.
topics: [systems, integrations, android, ios, flows]
sources:
  - id: health-common
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/HealthIntegration.kt
    note: Defines the shared health payload model, export markers, and health-store contract.
  - id: health-weight-sync
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/HealthBodyWeightSyncManager.kt
    note: Defines body-weight import eligibility, range checks, local persistence, and status updates.
  - id: health-backfill
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/integration/HealthBackfillManager.kt
    note: Defines historical workout export backfill behavior.
  - id: active-session-engine
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/manager/ActiveSessionEngine.kt
    note: Defines the fire-and-forget automatic export path after completed standalone and routine workouts.
  - id: health-android
    type: file
    path: shared/src/androidMain/kotlin/com/devil/phoenixproject/data/integration/HealthIntegration.android.kt
    note: Defines Android Health Connect availability, permission, segment, calorie, and body-weight behavior.
  - id: health-ios
    type: file
    path: shared/src/iosMain/kotlin/com/devil/phoenixproject/data/integration/HealthIntegration.ios.kt
    note: Defines iOS HealthKit authorization, aggregate workout export, and body-mass query behavior.
  - id: integrations-vm
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/IntegrationsViewModel.kt
    note: Defines UI-facing connection toggles and Android permission-settings recovery.
  - id: health-permission-requester-common
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/util/HealthPermissionRequester.kt
    note: Contains the stale shared comment that still says iOS no-ops.
  - id: health-permission-requester-ios
    type: file
    path: shared/src/iosMain/kotlin/com/devil/phoenixproject/util/HealthPermissionRequester.ios.kt
    note: Shows that iOS now requests HealthKit authorization through the concrete HealthIntegration.
status: active
verified: 2026-06-24
---
Phoenix health integration is a local device path, not a portal path. `HealthIntegration` writes completed Phoenix workouts to the platform health store and reads the latest eligible body-weight sample back into Phoenix, while `HealthBodyWeightSyncManager` persists imported body weight into both external-measurement storage and the app preference used by workout logic [@health-common] [@health-weight-sync]. Read [[integrations]] first when the task is only "integrations" so you can choose between this device-local path and [[external-provider-sync]].

The data directions are intentionally asymmetric. Workout export is Phoenix -> health store, while body weight is health store -> Phoenix in kilograms [@health-common]. `HealthBodyWeightSyncManager` updates the connected provider status, rejects values outside the `20-300 kg` range, and stores accepted reads as `ExternalBodyMeasurement` rows plus the shared body-weight preference [@health-weight-sync].

Workout export uses one shared payload model with platform-specific fidelity. `HealthWorkoutData` carries aggregate workout fields plus per-set `segments`; Android persists those segments into Health Connect exercise records, while iOS writes only one aggregate `HKWorkout` because HealthKit does not expose a comparable public per-set strength API [@health-common] [@health-android] [@health-ios].

Automatic export happens inside the workout runtime. `ActiveSessionEngine` checks whether the platform health provider is connected, builds either a standalone or routine-scoped health payload from completed sets, skips already-exported records through `HealthExportMarkers`, and treats write failures as non-fatal so local workout completion still succeeds [@active-session-engine] [@health-common]. Historical export is a separate operation: `HealthBackfillManager` scans prior workout candidates, rebuilds routine and standalone payloads, skips records already marked in the cursor repository, and writes the remaining workouts in chronological order [@health-backfill].

Health export deduplication reuses the same cursor repository family that third-party integrations use. `HealthExportMarkers` writes cursor keys shaped as `health_export:<externalId>`, so health export state sits beside provider sync cursors instead of in a separate ledger [@health-common]. This is a local persistence coupling with [[external-provider-sync]] and [[local-data-model]], not a sign that both systems share one transport path or one topic boundary.

Android and iOS differ at the permission boundary. Android Health Connect uses explicit availability and permission checks, can persist set-level segments, and treats calorie writes as optional [@health-android]. iOS HealthKit uses the system authorization dialog, queries body mass because per-type read authorization status is not exposed reliably, and writes one aggregate strength workout with optional active-energy metadata [@health-ios].

The UI recovery path is also platform-specific. `IntegrationsViewModel` emits `OpenHealthPermissionSettings` when Android Health Connect suppresses repeated prompts after revocation, because the user may need Phoenix's app-specific Health Connect settings screen instead of another launcher attempt [@integrations-vm]. Future agents should trust the concrete iOS requester over the stale shared expect comment here: `HealthPermissionRequester.ios.kt` calls `healthIntegration.requestPermissions()` even though the common `HealthPermissionRequester` comment still says iOS no-ops because HealthKit is not wired up yet [@health-permission-requester-ios] [@health-permission-requester-common].

Read [[platform-hosts]] when Android and iOS disagree about permission prompts or native health wiring. Read [[workouts]] when the symptom starts from completed-set capture or routine-session construction rather than from the health API boundary. Read [[sync]] only when the same symptom also involves portal auth state, premium gating, or third-party provider import.
