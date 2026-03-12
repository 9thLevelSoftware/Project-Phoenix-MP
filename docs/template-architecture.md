# Template Architecture

## Module split (shared/commonMain)

Under `com.devil.phoenixproject` we use four logical layers:

- `framework/core`
  - Vendor-agnostic domain coordination contracts and use-case-facing abstractions.
  - Example: workout orchestration contracts (`WorkoutStateProvider`, `WorkoutLifecycleDelegate`).
- `framework/protocol`
  - Adapter contracts and packet/transport models shared by BLE/data implementations.
  - Example: `BleRepository`, `ScannedDevice`, `MonitorPacket`, `DiagnosticPacket`.
- `framework/ui`
  - Optional reusable UI building blocks that can be reused across products.
- `app/phoenix`
  - Phoenix-specific wiring and compatibility shims that preserve existing behavior while migrating to framework namespaces.

## Dependency boundaries

### Allowed imports

- `framework/core` -> may import `domain/*`, Kotlin stdlib/coroutines.
- `framework/protocol` -> may import `domain/model` types used in cross-adapter contracts.
- `framework/ui` -> may import `framework/core`, `framework/protocol`, shared UI/tooling.
- `app/phoenix` -> may import anything in `framework/*` plus Phoenix app packages.

### Disallowed imports

- `framework/core` **must not** import `app/phoenix` or other app-specific packages.
- `framework/protocol` must not import `app/phoenix`.
- `framework/ui` should not depend on `app/phoenix` defaults/config.

## Compatibility strategy

During migration, keep behavior stable by exposing typealiases and adapters in
`app/phoenix` (and temporary legacy package aliases where required), then move
call-sites gradually to `framework/*` namespaces.
