# Kable tvOS source variant for Phoenix

This is a source-built KMP variant of the project-pinned upstream Kable `0.43.1` release. Upstream tag `0.43.1` resolves to commit `635970107aa3123b5fd29bca2cd6ac8955fd1cf` (Apache-2.0; see `LICENSE.txt`). It adds only `tvosArm64` and `tvosSimulatorArm64` targets to the exact upstream `commonMain` and `appleMain` sources. The upstream Apple actuals use CoreBluetooth for scanning, connection, GATT discovery, reads, notifications, and writes.

This module publishes **tvOS targets only** and is consumed only by `shared-tvos`. Phones retain their existing dependency resolution: the published iOS Kable artifact and Phoenix's unmodified patched Android module. The module keeps the existing Kable API, allowing the same `KableBleRepository`, `WorkoutCommandValidator`, `BlePacketFactory`, and session/safety sources to remain authoritative without routing phone builds through this integration.

## Runtime provenance and TV-only divergence

The [upstream version catalog at commit `635970107aa3123b5fd29bca2cd6ac8955fd1cf`](https://raw.githubusercontent.com/JuulLabs/kable/635970107aa3123b5fd29bca2cd6ac8955fd1cf/gradle/libs.versions.toml) used by Kable `0.43.1` declares:

- `atomicfu = "0.33.0"`, with `org.jetbrains.kotlinx:atomicfu` using that version reference.
- `org.jetbrains.kotlinx:kotlinx-io-core` version `0.9.0`.
- `kotlin = "2.4.0"` for the Kotlin multiplatform plugin.

These are upstream's own versions, not TV-specific upgrades to Kable. Phoenix's catalog retains them as `kable-atomicfu` / `kable-kotlinx-io` with the existing Kotlin pin; `build.gradle.kts` exposes the runtimes via `api(libs.kable.atomicfu.runtime)` and `api(libs.kable.kotlinx.io.core)`. Retaining that upstream baseline avoids introducing an unvalidated downgrade of this source variant.

This deliberately differs from the legacy phone catalog pins (`atomicfu` `0.29.0`, `kotlinx-io` `0.8.0`) used by the patched Android module. Phone declarations and published iOS Kable resolution are unchanged. The shared Phoenix Kotlin sources remain the authoritative implementation, but TV and phone do **not** claim identical runtime dependency graphs. `shared-tvos` explicitly declares `implementation(libs.kable.atomicfu.runtime)` because the reused common sources import atomicfu directly; it matches the TV runtime already exposed by Kable rather than declaring the legacy phone version and relying on conflict resolution. This does not remove that direct dependency or change a phone pin. The catalog remains the source of truth for future updates; the versions above record this vendored release's provenance.

## Atomicfu on Kotlin/Native

The [atomicfu `0.33.0` README, Features](https://github.com/Kotlin/kotlinx-atomicfu/blob/0.33.0/README.md#features) states: "For **Native**: atomic operations are delegated to Kotlin/Native atomic intrinsics." Its [Locks section](https://github.com/Kotlin/kotlinx-atomicfu/blob/0.33.0/README.md#locks) also describes a Kotlin/Native library implementation for locking primitives.

This integration builds only `tvosArm64` and `tvosSimulatorArm64`, with no JVM or JS targets. It keeps the atomicfu Native runtime dependency; it does not apply the atomicfu Gradle plugin or request its optional IR transformations. The unused `atomicfu-kable` plugin alias was removed rather than applying unused JVM/JS transformation machinery to a Native-only module. This is not a claim that Native atomics become non-atomic or that an untransformed JVM binary is being shipped. No vendored Kotlin source changes are required for this build integration.

This is a local build integration, not an upstream Kable release or proof of physical Apple TV/Vitruvian behavior. Both tvOS target compiles and the full Android/iOS regression gates are required before accepting it. If it cannot resolve or compiles with behavioral regressions, replace this route with a dedicated CoreBluetooth adapter behind Phoenix's `BleRepository` and keep the same safety/domain contract.
