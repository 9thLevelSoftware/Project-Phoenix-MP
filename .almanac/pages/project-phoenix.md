---
title: Project Phoenix
summary: Project Phoenix is a Kotlin Multiplatform rescue app that restores local-first control of Vitruvian Trainer hardware after the original company's shutdown.
topics: [product, systems]
sources:
  - id: repo-readme
    type: file
    path: README.md
    note: Describes the project's purpose, supported hardware, feature set, and platform scope.
  - id: shared-build
    type: file
    path: shared/build.gradle.kts
    note: Shows the shared multiplatform stack and platform targets.
  - id: app-constants
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Constants.kt
    note: Carries app-level constants including version and machine limits.
  - id: hardware-detection
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/util/HardwareDetection.kt
    note: Defines the current model-detection heuristic and the rule against inferring unsupported capability differences from the advertised name alone.
  - id: ble-scan
    type: file
    path: shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/KableBleConnectionManager.kt
    note: Defines the BLE discovery filters Phoenix uses to recognize Vitruvian devices in practice.
status: active
verified: 2026-06-20
---
Project Phoenix is a community-maintained control app for Vitruvian V-Form and Trainer+ machines after the original company closure. The repo positions the app as a way to keep the hardware usable instead of turning it into e-waste, and it ships for both Android and iOS [@repo-readme].

The app is a Kotlin Multiplatform codebase with a shared `shared` module and thin `androidApp` and `iosApp` hosts. The shared module uses Compose Multiplatform, Koin, SQLDelight, Ktor, Kable, and multiplatform settings so most behavior lives once in shared code [@shared-build].

## Product contract

The repo treats the app as local-first. The README describes offline use, local storage, and no account requirement as baseline behavior, while optional portal sync and third-party integrations layer on top of that local model instead of replacing it [@repo-readme]. Future work that changes default online requirements should be read against [[portal-sync-transport]] and [[local-data-model]].

That local-first contract is why the remote pages in [[sync]] are framed as optional layers instead of platform prerequisites. [[auth]], [[premium-entitlements]], and [[integrations]] all sit behind the same product rule: Phoenix must still control the machine and preserve local workout history when the portal is unavailable or the user never signs in [@repo-readme].

## Hardware contract

Phoenix only claims support for two hardware families in current project memory: V-Form machines advertised as `Vee_*` and Trainer+ machines advertised as `VIT*` [@repo-readme]. The BLE scanner first accepts `Vee_*`, `VIT*`, and `Vitruvian*` names, then falls back to advertisements that expose the `0000fef3` service UUID, the Nordic UART service UUID, or non-empty `0000fef3` service data so devices can still be discovered when the advertisement is incomplete or nameless [@ble-scan].

The hardware contract in this repo is per-cable weight, not total machine weight. `Constants` caps the default safe UI limit at `100 kg` per cable, carries a separate `110 kg` per-cable ceiling for Trainer+, and keeps the rest of the app's stored values and calculations in per-cable units rather than total machine load [@app-constants].

Model detection is intentionally conservative. `HardwareDetection.detectModel()` still maps `Vee_` to V-Form and `VIT` to Trainer+, but the same file says Phoenix should not infer deeper capability differences from the advertised name alone because reliable capability detection would require documented firmware or version parsing that the app does not currently have [@hardware-detection]. Future work that wants to disable features or change limits by model needs stronger evidence than the scan name prefix.

That conservative contract is why the repo preserves older compatibility behavior instead of narrowing to one firmware generation. The product promise is not only "connect to a machine"; it is "keep existing owner hardware usable" across the known V-Form and Trainer+ families [@repo-readme] [@hardware-detection].

## Reading boundary

Use this page for product frame and hardware constraints. Use [[getting-started]] when the repo feels broad and you need reading order more than product context.

The fastest rule is that most coding work should not start here. Open this page before a subsystem hub only when the task depends on supported machine families, per-cable load assumptions, offline-first product commitments, or why Phoenix preserves backward-compatible behavior that the narrower implementation pages may otherwise look ready to simplify [@repo-readme] [@app-constants] [@hardware-detection].

The fastest way into the code still starts from the cluster hubs unless the symptom is already narrow enough for a leaf page:

- Shared app structure and screen boundaries: [[app-architecture]]
- BLE, routine flow, diagnostics, or trainer communication: [[workouts]] then [[vitruvian-ble-protocol]] if the issue is already clearly in the transport layer
- Workout lifecycle and routine orchestration: [[workouts]]
- Persistence, migrations, backups, or profile-scoped visibility: [[data]] then [[local-data-model]] if the issue is already structural
- Supabase project configuration, redirect allowlists, or Edge Function surface: [[supabase]]
- Auth, premium, or portal sync behavior after configuration is in place: [[sync]]
- Health-store, CSV, or provider integrations: [[integrations]]
- Platform-specific host behavior: [[platform-hosts]]
