# Kable tvOS source variant for Phoenix

This is a source-built KMP variant of the project-pinned upstream Kable `0.43.1` release. Upstream tag `0.43.1` resolves to commit `635970107aa3123b5fd29bca2cd6ac8955fd1cf` (Apache-2.0; see `LICENSE.txt`). It adds only `tvosArm64` and `tvosSimulatorArm64` targets to the exact upstream `commonMain` and `appleMain` sources. The upstream Apple actuals use CoreBluetooth for scanning, connection, GATT discovery, reads, notifications, and writes.

This module publishes **tvOS targets only** and is consumed only by `shared-tvos`. Phones retain their existing dependency resolution: the published iOS Kable artifact and Phoenix's unmodified patched Android module. The module keeps the existing Kable API, allowing the same `KableBleRepository`, `WorkoutCommandValidator`, `BlePacketFactory`, and session/safety sources to remain authoritative without routing phone builds through this integration.

This is a local build integration, not an upstream Kable release or proof of physical Apple TV/Vitruvian behavior. Both tvOS target compiles and the full Android/iOS regression gates are required before accepting it. If it cannot resolve or compiles with behavioral regressions, replace this route with a dedicated CoreBluetooth adapter behind Phoenix's `BleRepository` and keep the same safety/domain contract.
