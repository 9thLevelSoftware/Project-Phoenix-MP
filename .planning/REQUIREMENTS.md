# Requirements: Project Phoenix MP

**Defined:** 2026-02-15
**Core Value:** Users can connect to their Vitruvian trainer and execute workouts reliably on both platforms.

## v1 Requirements

Requirements for v0.4.2 BLE Layer Decomposition. Each maps to roadmap phases.

### Constants & Parsing

- [ ] **CONST-01**: All BLE UUIDs and timing constants extracted to `BleProtocolConstants.kt`
- [ ] **PARSE-01**: Protocol parsing functions extracted to `ProtocolParser.kt` (stateless)
- [ ] **PARSE-02**: Unit tests verify byte parsing matches monolith behavior

### BLE Infrastructure

- [ ] **QUEUE-01**: `BleOperationQueue` serializes all BLE reads/writes via Mutex (Issue #222)
- [ ] **QUEUE-02**: Integration test verifies no fault 16384 under concurrent access

### Connection Management

- [ ] **CONN-01**: `KableBleConnectionManager` handles scan/connect/disconnect lifecycle
- [ ] **CONN-02**: Auto-reconnect after unexpected disconnect works correctly
- [ ] **CONN-03**: Connection retry logic (3 attempts) preserved

### Polling System

- [ ] **POLL-01**: `MetricPollingEngine` manages all 4 polling loops (monitor, diagnostic, heuristic, heartbeat)
- [ ] **POLL-02**: `stopMonitorOnly` preserves diagnostic/heartbeat (Issue #222)
- [ ] **POLL-03**: Timeout disconnect after MAX_CONSECUTIVE_TIMEOUTS works

### Data Processing

- [ ] **PROC-01**: `MonitorDataProcessor` handles position validation and velocity EMA
- [ ] **PROC-02**: Position jump filter doesn't cascade (Issue #210 fix preserved)
- [ ] **PROC-03**: Latency budget <5ms maintained

### Handle Detection

- [ ] **HAND-01**: `HandleStateDetector` implements 4-state machine
- [ ] **HAND-02**: Overhead pulley baseline tracking works (Issue #176 fix preserved)
- [ ] **HAND-03**: Just Lift autostart detects grab correctly

### Easter Egg & Interface

- [ ] **DISCO-01**: `DiscoMode` extracted as self-contained module
- [ ] **IFACE-01**: `setLastColorSchemeIndex` added to `BleRepository` interface
- [ ] **IFACE-02**: `SettingsManager` no longer casts to concrete type (Issue #144)

### Facade & Integration

- [ ] **FACADE-01**: `KableBleRepository` reduced to <400 lines (delegation only)
- [ ] **FACADE-02**: All existing tests pass without modification
- [ ] **FACADE-03**: Manual BLE testing on physical device passes

## Future Requirements

Deferred to future milestones.

### Premium Features
- **PREM-01**: Data foundation (spec-00)
- **PREM-02**: Biomechanics engine (spec-01)
- **PREM-03**: Intelligence layer (spec-02)

### Platform Enhancements
- **IOS-01**: iOS UI polish
- **PERF-01**: handleMonitorMetric hot path optimization

## Out of Scope

Explicitly excluded. Documented to prevent scope creep.

| Feature | Reason |
|---------|--------|
| New user-facing features | This milestone is purely architectural |
| BLE protocol changes | No hardware interaction changes |
| iOS-specific code | Focus is shared module extraction |
| DI module changes | KableBleRepository wires sub-components internally |

## Traceability

Which phases cover which requirements. Updated during roadmap creation.

| Requirement | Phase | Status |
|-------------|-------|--------|
| CONST-01 | Phase 5 | Pending |
| PARSE-01 | Phase 6 | Pending |
| PARSE-02 | Phase 6 | Pending |
| QUEUE-01 | Phase 7 | Pending |
| QUEUE-02 | Phase 7 | Pending |
| CONN-01 | Phase 12 | Pending |
| CONN-02 | Phase 12 | Pending |
| CONN-03 | Phase 12 | Pending |
| POLL-01 | Phase 11 | Pending |
| POLL-02 | Phase 11 | Pending |
| POLL-03 | Phase 11 | Pending |
| PROC-01 | Phase 10 | Pending |
| PROC-02 | Phase 10 | Pending |
| PROC-03 | Phase 10 | Pending |
| HAND-01 | Phase 9 | Pending |
| HAND-02 | Phase 9 | Pending |
| HAND-03 | Phase 9 | Pending |
| DISCO-01 | Phase 8 | Pending |
| IFACE-01 | Phase 8 | Pending |
| IFACE-02 | Phase 8 | Pending |
| FACADE-01 | Phase 12 | Pending |
| FACADE-02 | Phase 12 | Pending |
| FACADE-03 | Phase 12 | Pending |

**Coverage:**
- v1 requirements: 23 total
- Mapped to phases: 23
- Unmapped: 0 ✓

---
*Requirements defined: 2026-02-15*
*Last updated: 2026-02-15 after initial definition*
