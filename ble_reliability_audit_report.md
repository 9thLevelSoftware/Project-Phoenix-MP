## BLE Reliability Audit Results

### 🔴 Critical Issues (Block Release)

| File | Issue | Impact | Fix |
|------|-------|--------|-----|
| `MetricPollingEngine.kt:173` | `consecutiveTimeouts` not reset on non-timeout exceptions | Connection may not auto-disconnect when it should | Add `consecutiveTimeouts = 0` in general exception catch block (line 175) |
| `MetricPollingEngine.kt:164-175` | Race condition: `consecutiveTimeouts` incremented both in null check AND TimeoutCancellationException handler | Double-counting timeouts, premature disconnect | Remove duplicate timeout handling - use only `withTimeoutOrNull` result check |
| `KableBleRepository.kt:76-79` | `_metricsFlow` uses `DROP_OLDEST` with only 64 slots | UI may miss critical rep completions during slow frame rendering | Increase to 128-256 slots, or use `SUSPEND` strategy for critical workout data |
| `BleOperationQueue.kt:56-68` | Busy error detection uses string matching on `e.message` | Fragile - may miss busy conditions on iOS where error messages differ | Use error type checking if Kable provides error codes; wrap and classify errors |
| `KableBleConnectionManager.kt` | Connection timeout (15s) and scan timeout (10s) hardcoded inline | Difficult to tune for gym environments with interference | Extract to BleConstants and make configurable via constructor |

### 🟠 Reliability Warnings (Address Soon)

| File | Concern | Risk | Mitigation |
|------|---------|------|------------|
| `HandleStateDetector.kt:210-214` | `activeHandlesMask` fallback to `else -> aReleased \|\| bReleased` could cause premature release detection in multi-handle scenarios | Incomplete set tracking | Remove fallback - require explicit mask values (1, 2, 3) only |
| `ProtocolParser.kt:85-88` | `parseMonitorPacket` doesn't validate tick counter monotonicity | Lost packets go undetected | Add sequence validation and counter-gap detection |
| `MonitorDataProcessor.kt:136-145` | Position jump filter (20mm) uses absolute comparison, doesn't account for workout mode | False filtering during explosive movements (Echo mode) | Make threshold mode-aware: Echo mode allows larger jumps |
| `KableBleRepository.kt:246-250` | `stopWorkout()` sends RESET but doesn't verify device acknowledgment | Device may not stop, user safety issue | Add confirmation polling or at least longer delay (200ms) before stopping polling |
| `BlePacketFactory.kt:93-97` | `createProgramParams` variant selection via `@Volatile` global | Race condition if variant changes mid-build | Pass variant as explicit parameter, remove global mutable state |
| `MetricPollingEngine.kt:83-121` | `startFakeJobs()` and test helpers mixed with production code | Risk of test code being called in production | Move to test-only module or use `internal expect/actual` pattern |
| `HardwareDetection.kt:30-33` | `getCapabilities()` returns DEFAULT for all devices | Trainer+ users may try to exceed 200kg | Implement VERSION characteristic reading (UUID documented) to detect actual model |

### 🟡 Recommendations (Best Practices)

1. **Bond State Persistence**: No bond state handling found - implement bonded device auto-reconnection for faster reconnect after app restart
2. **Packet Acknowledgment**: Protocol lacks ACK/NACK - consider adding sequence numbers and retry for critical commands (start/stop)
3. **CRC/Checksum Validation**: No checksum found in parsed packets - validate if firmware provides it; if not, document risk
4. **MTU Fallback Chain**: Current MTU request is all-or-nothing - implement stepped fallback (247 -> 185 -> 128 -> 23) for broader compatibility
5. **Connection State Audit Trail**: Add persistent connection event logging for post-incident analysis
6. **Metric Timestamp Validation**: Cross-check metric timestamps against device clock drift
7. **Flow Buffer Monitoring**: Add metrics flow buffer utilization telemetry to detect slow consumers
8. **Write Confirmation Timeout**: Add per-write timeout in BleOperationQueue instead of unlimited wait

### 🟢 Reliability Strengths

- **Mutex Serialization**: `BleOperationQueue` correctly serializes all BLE operations via Mutex - prevents race condition in packet writes (Issue #222)
- **Timeout Disconnection**: `MetricPollingEngine` implements POLL-03: disconnects after 5 consecutive timeouts
- **Position Clamping**: `MonitorDataProcessor` clamps out-of-range positions to last-good values (BLE noise recovery)
- **Dwell Timers**: `HandleStateDetector` uses 200ms hysteresis on state transitions (Task 14) - prevents flutter
- **Cascading Filter Fix**: Issue #210 properly addressed - position tracking updated BEFORE validation to prevent cascade failures
- **Firmware Velocity**: Hardware-validated firmware velocity used instead of client-side calculation (Issue #204 fix)
- **MTU Negotiation**: Explicit MTU request for Android (247 bytes) with iOS automatic handling via expect/actual
- **Command Retry**: `BleOperationQueue.write()` implements exponential backoff retry for "Busy" errors
- **Rep Packet Dual-Format**: `parseRepPacket` handles both legacy (6-byte) and modern (24-byte) formats
- **Strict Validation Toggle**: `MonitorDataProcessor.strictValidationEnabled` allows runtime disable for debugging
- **Deload Debouncing**: 2-second debounce on DELOAD_OCCURRED status flag prevents event spam

### Platform-Specific Notes

**Android**:
- MTU negotiation explicit via `AndroidPeripheral.requestMtu()`
- Connection priority high requested post-connection
- All timeouts and retry logic properly implemented

**iOS**:
- MTU automatic via CoreBluetooth
- No explicit connection priority API (handled by OS)
- Identical protocol parsing - parity maintained

### Hardware Detection Status

| Capability | Status | Notes |
|------------|--------|-------|
| V-Form vs Trainer+ | ✅ Working | Name prefix detection (`Vee_` vs `VIT`) |
| Max Weight Enforcement | ⚠️ Incomplete | Returns DEFAULT 200kg for all; should read VERSION char |
| Feature Gating | ⚠️ Incomplete | Assumes all features available; needs firmware version check |

---
*Audit completed: 2026-03-28*
*Files reviewed: 12 core BLE modules*
