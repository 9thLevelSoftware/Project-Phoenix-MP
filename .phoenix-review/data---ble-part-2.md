# Data - BLE Part 2 Review

Scope reviewed from task t_fa884bd4. Repository path on disk is `/Users/christopherwilloughby/Project-Phoenix-MP` (the task's lowercase `~/project-phoenix-mp` path was not present). The two assigned files `BleRepositoryImpl.kt` and `HardwareValidation.kt` were not present at the requested paths, so those are documented as review-blocking findings.

## Summary

- Files assigned: 8
- Files found and reviewed: 6
- Files missing at assigned path: 2
- Findings: 18
- Severity breakdown: critical 0, high 0, medium 13, low 5

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/BleRepositoryImpl.kt

### Finding 1
- Category: error
- Severity: medium
- Line numbers: N/A (file missing)
- Description: The assigned file `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/BleRepositoryImpl.kt` does not exist in the repository. A similarly purposed implementation exists as `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt`, but it is outside this task's explicit file list.
- Suggested fix direction: Restore the expected file if it was accidentally removed, or update the review/task manifest to point at the current repository implementation file so the BLE repository implementation is actually covered.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/DiagnosticFaultDecoder.kt

### Finding 2
- Category: failure-point
- Severity: medium
- Line numbers: 84-92
- Description: `decodeFlaggedFault` drops unknown fault bits whenever at least one known bit is also present. For example, a fault word with a known motor bit plus an undocumented high bit will display only the known label, hiding evidence of an additional active fault.
- Suggested fix direction: Track the union of known masks, compute `unknownBits = code and knownMask.inv()`, and append an `Unknown bits 0x....` label whenever unknown bits are non-zero.

### Finding 3
- Category: bug
- Severity: low
- Line numbers: 50-55, 87-90
- Description: Vitruvian fault flags `8` and `16` both use the label `Message failure`, and the final `.distinct()` collapses them. If both bits are set, the decoded label reports only one message failure and loses which bit(s) were active.
- Suggested fix direction: Give the two flags distinct labels if the protocol differentiates them, or include the bit mask in duplicate labels so combined faults remain diagnosable.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/DiscoMode.kt

### Finding 4
- Category: bug
- Severity: medium
- Line numbers: 46-64
- Description: If `sendCommand` throws a non-cancellation exception, the loop breaks and the coroutine ends, but `_isActive` remains `true`. Consumers of `isActive` can continue to show disco mode as active even though no cycling job is running.
- Suggested fix direction: Reset `_isActive.value = false` in a `finally` block when the cycling job exits unexpectedly, or explicitly reset it in the catch path before breaking.

### Finding 5
- Category: failure-point
- Severity: medium
- Line numbers: 76-84
- Description: `stop()` cancels `discoJob` and immediately launches the restore-color command without waiting for the old job to finish. If the old job is in the middle of a BLE write when cancellation occurs, a late disco color write can race with and override the restore command.
- Suggested fix direction: Serialize disco writes through the same job or a mutex, and cancel-and-join the cycling job before sending the restore command.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/HandleStateDetector.kt

### Finding 6
- Category: bug
- Severity: medium
- Line numbers: 69-72, 155-156, 185-187
- Description: `maxPositionSeen` is initialized and reset with `Double.MIN_VALUE`, which is the smallest positive Double, not the most negative Double. If all observed positions are negative, `maxPositionSeen` never updates and workout diagnostics can report an invalid positive near-zero maximum or skip analysis checks that depend on the sentinel.
- Suggested fix direction: Initialize/reset `maxPositionSeen` to `Double.NEGATIVE_INFINITY` or `-Double.MAX_VALUE`, and initialize/reset `minPositionSeen` to `Double.POSITIVE_INFINITY` for symmetry.

### Finding 7
- Category: bug
- Severity: medium
- Line numbers: 119-125
- Description: `disable()` only clears `isEnabled`, `isAutoStartMode`, and baselines. It leaves `_handleState`, `_handleDetection`, pending dwell timers, active handle mask, and waiting-for-rest timer unchanged. UI or downstream observers can retain stale `Grabbed`/detected state after detection has been disabled.
- Suggested fix direction: Have `disable()` call `resetInternalState()`, reset `_handleState` to `WaitingForRest` or another explicit disabled/rest state, and reset `_handleDetection` to `HandleDetection()`.

### Finding 8
- Category: failure-point
- Severity: low
- Line numbers: 371-394
- Description: Baseline-relative grab/release checks only compare `position - baseline` against positive thresholds. A setup or calibration where meaningful handle movement is represented as a negative delta from baseline will not be recognized as grabbed, while release checks may be overly permissive for large negative movement.
- Suggested fix direction: Confirm the protocol sign convention for all pulley/cable orientations; if either direction can represent extension, compare `abs(position - baseline)` for relative movement or track direction per handle.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/HardwareValidation.kt

### Finding 9
- Category: error
- Severity: medium
- Line numbers: N/A (file missing)
- Description: The assigned file `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/HardwareValidation.kt` does not exist in the repository. The repository contains `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/HardwareValidationTest.kt`, but no commonMain hardware validation implementation at the requested path.
- Suggested fix direction: Restore the missing implementation file, or update the task/review manifest to reference the actual hardware-validation artifact intended for review.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/MetricPollingEngine.kt

### Finding 10
- Category: failure-point
- Severity: medium
- Line numbers: 200-219
- Description: `startMonitorPolling()` resets `monitorProcessor` before the previous monitor job has actually exited or released `monitorPollingMutex`. Because cancellation is cooperative, an old polling loop can still parse one more read after the reset and contaminate the freshly reset session state or emit a stale metric into the new session.
- Suggested fix direction: Cancel and join the previous monitor job before resetting session state, or perform the reset inside the mutex after the prior loop has fully exited.

### Finding 11
- Category: error
- Severity: medium
- Line numbers: 526-542, 548-566, 572-578
- Description: The parser callback wrappers catch `Exception` but do not call `rethrowIfCancellation()`. `CancellationException` is an `Exception` in Kotlin, so cancellation from `monitorProcessor`, `handleDetector`, `onMetricEmit`, `onDiagnosticData`, or `onHeuristicData` can be swallowed and converted into a log message.
- Suggested fix direction: Add `e.rethrowIfCancellation()` to these catch blocks before logging, matching the surrounding polling-loop catch blocks.

### Finding 12
- Category: bug
- Severity: low
- Line numbers: 264-267
- Description: The explicit `TimeoutCancellationException` catch path increments `consecutiveTimeouts` and delays, but it never checks `MAX_CONSECUTIVE_TIMEOUTS` or invokes `onConnectionLost()`. Although the main `withTimeoutOrNull` path handles null timeouts, any timeout exception reaching this catch can avoid the disconnect threshold indefinitely.
- Suggested fix direction: Reuse the same threshold check as the null-timeout branch after incrementing `consecutiveTimeouts`, or remove the catch if `withTimeoutOrNull` is the only expected timeout path.

### Finding 13
- Category: failure-point
- Severity: medium
- Line numbers: 257-260
- Description: When monitor timeouts exceed the threshold, `onConnectionLost()` is launched asynchronously on the same `scope` and the monitor loop exits. If that scope is already cancelling or if `onConnectionLost()` throws, the disconnect request can be lost while diagnostic, heuristic, and heartbeat loops may continue until some other owner stops them.
- Suggested fix direction: Invoke `onConnectionLost()` in a supervised/handled context, log failures, and ensure all polling loops are stopped as part of the timeout-disconnect path.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/MonitorDataProcessor.kt

### Finding 14
- Category: failure-point
- Severity: medium
- Line numbers: 126-140, 347-368
- Description: Out-of-range positions are replaced with `lastGoodPosA/B`, and `resetForNewSession()` intentionally preserves those last-good values. The first corrupt packet of a new session can therefore emit stale positions from a previous workout; if no prior good packet exists, it emits the default `0.0f` positions as if they were valid.
- Suggested fix direction: Reset or invalidate last-good positions at session boundaries, and drop out-of-range samples until a valid in-session position has established a current fallback.

### Finding 15
- Category: stub
- Severity: low
- Line numbers: 236-250
- Description: `calculateRawVelocity()` is now unused after firmware velocity became authoritative. Keeping this stale private implementation increases the chance of future code accidentally reintroducing client-side velocity behavior that contradicts the current parser comments and tests.
- Suggested fix direction: Remove the dead function or mark it as test-only/reference documentation outside the production hot path.

## shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/ProtocolParser.kt

### Finding 16
- Category: bug
- Severity: medium
- Line numbers: 216-226
- Description: Monitor ticks are reconstructed into an `Int` with `f0 + (f1 shl 16)`. When the unsigned 32-bit device tick counter reaches values above `Int.MAX_VALUE`, this wraps negative. Downstream ordering, elapsed-time calculations, or diagnostics that assume monotonic non-negative ticks can fail around wrap/high-bit values.
- Suggested fix direction: Parse ticks as an unsigned 32-bit value (`getUInt32LE`) and store it as `Long`/`UInt`, or explicitly document and handle wraparound at the model boundary.

### Finding 17
- Category: failure-point
- Severity: medium
- Line numbers: 348-375
- Description: `parseHeuristicPacket()` accepts every 48-byte payload and directly exposes twelve raw floats. It does not reject `NaN`, infinities, or physically impossible values, so a corrupted BLE frame can propagate invalid force/velocity/power telemetry into UI or analytics.
- Suggested fix direction: Validate each parsed float with `isFinite()` and plausible protocol ranges before constructing `HeuristicStatistics`; return null or clamp/log when values are invalid.

### Finding 18
- Category: failure-point
- Severity: low
- Line numbers: 28-89
- Description: The public byte-reader helpers (`getUInt16LE`, `getInt16LE`, `getUInt16BE`, `getInt32LE`, `getUInt32LE`, `getFloatLE`) perform unchecked indexing. Callers outside the guarded parser functions can crash with `IndexOutOfBoundsException` on short buffers.
- Suggested fix direction: Either make the helpers private/internal to safe parser code, or add bounds-checked variants that return null/Result for public use.
