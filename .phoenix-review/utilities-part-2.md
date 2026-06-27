# Utilities Part 2 Review

Scope reviewed:
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BleConstants.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BlePacketFactory.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/ConnectivityChecker.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Constants.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DeviceInfo.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/EchoParams.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/HardwareDetection.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/WorkoutCommandValidator.kt`

## Summary

Findings: 11 total
- Critical: 0
- High: 3
- Medium: 6
- Low: 2

## Findings by file

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BleConstants.kt`

#### Finding 1
- Category: failure-point
- Severity: low
- Line numbers: 49-51
- Description: The device-name constants are internally inconsistent and stale. `DEVICE_NAME_PREFIX` is `"Vee"`, while `DEVICE_NAME_PATTERN` is `"^Vitruvian.*$"`; the active scanner/hardware detection code also treats `"Vee_"` and `"VIT"` as Vitruvian names. Any future code that relies on `DEVICE_NAME_PATTERN` would reject the same devices that the scanner currently accepts.
- Suggested fix direction: Replace the single regex with one canonical matcher shared by scanning and hardware detection, e.g. covering `Vee_`, `VIT`, and `Vitruvian`, or remove unused/stale constants so future filters cannot drift.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BlePacketFactory.kt`

#### Finding 2
- Category: bug
- Severity: medium
- Line numbers: 75-86
- Description: `createStopCommand()` is documented as the primary STOP command but emits legacy opcode `0x05`, while `BleConstants.Commands.STOP_COMMAND` and `createOfficialStopPacket()` use official stop opcode `0x50`. This creates an attractive but unsafe API footgun: callers choosing `createStopCommand()` by name/comment may send a different stop semantics than the official stop/clear-fault packet.
- Suggested fix direction: Rename/deprecate the legacy helper (for example `createLegacyStopCommand()`), make the primary helper delegate to `createOfficialStopPacket()`, and keep tests explicit about the legacy opcode only where it is intentionally required.

#### Finding 3
- Category: bug
- Severity: high
- Line numbers: 161-168, 363-365
- Description: Finite rep counts can serialize to `0xFF`, which the same packet builders use as the unlimited/Just Lift/AMRAP sentinel. In activation packets, `reps + warmupReps == 255` becomes `0xFF`; in Echo packets, `targetReps == 255` becomes `0xFF`. That makes a finite workout indistinguishable from an unlimited workout on the wire and can prevent automatic stop behavior.
- Suggested fix direction: Treat `0xFF` as reserved for sentinel-only use. Cap finite activation `reps + warmupReps` and finite Echo `targetReps` at `254`, or introduce an explicit protocol constant for the sentinel and reject finite values that encode to it.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/ConnectivityChecker.kt`

#### Finding 4
- Category: failure-point
- Severity: medium
- Line numbers: 7-12
- Description: The common contract only exposes a synchronous `isOnline()` method and has no lifecycle/dispose hook or observable connectivity-change signal. Platform implementations that allocate long-lived resources or background monitors cannot be cancelled through the common API, and common sync code cannot subscribe to a reconnect event after setting a waiting-for-connectivity state.
- Suggested fix direction: Add lifecycle and state-change semantics to the expect contract, such as `close()`/`dispose()` plus a `StateFlow<Boolean>` or callback registration, and make DI scopes own the checker lifecycle explicitly.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/Constants.kt`

#### Finding 5
- Category: bug
- Severity: low
- Line numbers: 69-79
- Description: `UnitConverter.formatDecimal()` truncates toward zero rather than formatting to the nearest one decimal place, and it loses the sign for negative fractional values between `-1` and `0`. Verified examples following the implementation: `1.99 -> 1.9`, `0.19 -> 0.1`, `-0.5 -> 0.5`, and `22.0462 -> 22`. This can under-report converted weights and produce misleading display text.
- Suggested fix direction: Use a real rounding/formatting path (`round(value * 10) / 10`, locale-stable formatting, or a DecimalFormat/Multiplatform equivalent) and preserve the sign for negative values.

#### Finding 6
- Category: bug
- Severity: medium
- Line numbers: 139-142
- Description: `ProtocolConstants` declares packet sizes that contradict the actual packet builders and tests. `ECHO_PACKET_SIZE` is `29`, but `BlePacketFactory.createEchoControl()` builds a 32-byte frame and tests assert 32. `ACTIVATION_PACKET_SIZE` is `97`, but `BleConstants.ActivationPacket.SIZE`, `BlePacketFactory.createProgramParams()`, model documentation, and tests all use 96 bytes. Any code using these constants for allocation, validation, or MTU checks would reject valid packets or allocate the wrong size.
- Suggested fix direction: Define packet sizes from a single source of truth (`BleConstants.ActivationPacket.SIZE` and the factory Echo size), update Echo to 32 and activation to 96, and add tests covering `ProtocolConstants` parity with factory output.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/DeviceInfo.kt`

#### Finding 7
- Category: failure-point
- Severity: medium
- Line numbers: 71-73
- Description: The common API promises device info as a JSON `String`, but the contract does not require escaping or a structured serializer-backed result. Platform values such as model names, device names, build strings, or bundle values can contain quotes, backslashes, or control characters; manual string-building actual implementations can then return invalid JSON or malformed metadata.
- Suggested fix direction: Replace `toJson(): String` with a serializable data model or require all actual implementations to use `kotlinx.serialization`/a shared escaping helper. Add tests with quotes, backslashes, and newlines in representative fields.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/EchoParams.kt`

No direct issues found in the assigned file. The data class is a simple immutable parameter carrier and its documented offsets match the current 32-byte Echo packet layout used by `BlePacketFactory.createEchoControl()`.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/HardwareDetection.kt`

#### Finding 8
- Category: bug
- Severity: medium
- Line numbers: 8-13, 20-29
- Description: The file-level documentation says name-prefix hardware detection was flawed and should be avoided, but `detectModel()` still infers `VFormTrainer`/`TrainerPlus` from `Vee_` and `VIT` prefixes. That stale detection result is used when reporting `ConnectionState.Connected`, so UI or downstream logic can still receive a model classification the comments say is unreliable.
- Suggested fix direction: Either remove model inference and return `Unknown` until firmware-backed detection exists, or update the documentation and tests to explain why these prefixes are now reliable. Prefer firmware/version-characteristic detection before exposing a concrete hardware model.

#### Finding 9
- Category: stub
- Severity: medium
- Line numbers: 37-43, 52-62
- Description: `getCapabilities(deviceName)` ignores its input and always returns `HardwareCapabilities.DEFAULT`, which enables Echo and eccentric mode and sets `maxResistanceKg = 200f` for every device. This is placeholder capability logic; it can both over-enable features on unsupported devices and under-represent Trainer+ resistance while giving callers no indication that the result is assumed rather than detected.
- Suggested fix direction: Return an explicit unknown/assumed capability state, wire the VERSION/firmware characteristic into capability detection, and make capability consumers handle unknowns conservatively instead of treating defaults as detected hardware facts.

### `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/WorkoutCommandValidator.kt`

#### Finding 10
- Category: bug
- Severity: high
- Line numbers: 8-10, 44-50, 74-78, 109-112
- Description: The validator allows finite rep counts up to `255`, but packet builders reserve byte value `0xFF` as the Just Lift/AMRAP/unlimited sentinel. For normal program packets, `reps + warmupReps == 255` passes validation; for normal Echo packets, `targetReps == 255` passes validation. Both cases serialize to the sentinel byte and can turn a finite workout into an unlimited one.
- Suggested fix direction: Reserve `255` exclusively for sentinel semantics. Change finite rep validation to max `254`, validate activation `reps + warmupReps` as `1..254`, and add boundary tests for 254 accepted / 255 rejected in both activation and Echo paths.

#### Finding 11
- Category: failure-point
- Severity: high
- Line numbers: 97-103
- Description: Weight validation applies `Constants.MAX_WEIGHT_PER_CABLE_KG` (`110kg`) globally without taking detected hardware capabilities into account. The constants and hardware comments still identify 100kg per cable / 200kg total as the conservative V-Form/default limit, so this validator can allow packets above the safe default for devices whose capability has not been reliably detected.
- Suggested fix direction: Pass a hardware capability/model context into validation, default unknown hardware to the conservative per-cable limit, and only permit 110kg per cable once Trainer+ capability is positively detected. Add tests for Unknown/V-Form rejecting >100kg and Trainer+ allowing up to 110kg.
