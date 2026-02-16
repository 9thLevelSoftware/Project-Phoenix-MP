# Roadmap: Project Phoenix MP

## Milestones

- **v0.4.0 Foundation** - Phases 0 (pre-GSD, shipped)
- **v0.4.1 Architectural Cleanup** - Phases 1-4 (shipped 2026-02-13)
- **v0.4.2 BLE Layer Decomposition** - Phases 5-13 (in progress)

## Overview

Decompose the 2,886-line KableBleRepository monolith into 8 focused, testable modules while preserving the BleRepository interface contract. Each phase extracts one logical subsystem, ordered by risk (zero-risk extractions first, high-risk connection management last). The facade pattern keeps the public API stable while internal complexity drops 80%+.

## Phases

**Phase Numbering:**
- Phases 1-4: v0.4.1 Architectural Cleanup (completed)
- Phases 5-12: v0.4.2 BLE Layer Decomposition (current milestone)
- Decimal phases (e.g., 5.1): Urgent insertions if needed

- [x] **Phase 5: BleProtocolConstants** - Extract UUIDs and timing constants (zero risk) ✓ 2026-02-15
- [x] **Phase 6: ProtocolParser** - Extract stateless byte parsing functions (zero risk) ✓ 2026-02-15
- [x] **Phase 7: BleOperationQueue** - Extract Mutex-based BLE serialization (low risk) ✓ 2026-02-15
- [ ] **Phase 8: DiscoMode + Interface** - Extract easter egg and fix concrete cast (zero risk)
- [ ] **Phase 9: HandleStateDetector** - Extract 4-state handle detection machine (low risk)
- [x] **Phase 10: MonitorDataProcessor** - Extract position validation and velocity EMA (medium risk)(completed 2026-02-15)
- [ ] **Phase 11: MetricPollingEngine** - Extract all polling loops (medium risk)
- [x] **Phase 12: KableBleConnectionManager + Facade** - Extract connection lifecycle, finalize facade (high risk) (completed 2026-02-16)
- [x] **Phase 13: MetricPollingEngine Verification** - Formal verification of Phase 11 (gap closure) (completed 2026-02-16)

## Phase Details

### Phase 5: BleProtocolConstants
**Goal**: All compile-time constants extracted to standalone object
**Depends on**: Nothing (first phase of milestone)
**Requirements**: CONST-01
**Success Criteria** (what must be TRUE):
  1. All UUIDs (NUS_SERVICE, NUS_TX, NUS_RX, etc.) accessible via `BleProtocolConstants.XXX`
  2. All timing constants (timeouts, intervals, thresholds) centralized in one file
  3. Build compiles on both Android and iOS targets
  4. No functional changes to BLE behavior
**Plans**: 1 plan
  - [ ] 05-01-PLAN.md - Extract constants to BleConstants.kt, update KableBleRepository imports

### Phase 6: ProtocolParser
**Goal**: Byte parsing functions extracted as stateless pure functions
**Depends on**: Phase 5
**Requirements**: PARSE-01, PARSE-02
**Success Criteria** (what must be TRUE):
  1. Protocol parsing functions (parseRepPacket, parseMonitorPacket, parseDiagnosticPacket, parseHeuristicPacket) callable without BLE connection
  2. Unit tests verify byte parsing matches monolith behavior for all packet formats
  3. Legacy 6-byte and modern 24-byte rep notification formats both parse correctly
  4. Byte utilities (getUInt16LE, getInt16LE, etc.) are pure functions
**Plans**: 2 plans
  - [ ] 06-01-PLAN.md (TDD) - Create ProtocolParser.kt with byte utilities and tests
  - [ ] 06-02-PLAN.md - Add packet parsers, update KableBleRepository to use extracted functions

### Phase 7: BleOperationQueue
**Goal**: BLE read/write serialization extracted with Mutex pattern
**Depends on**: Phase 6
**Requirements**: QUEUE-01, QUEUE-02
**Success Criteria** (what must be TRUE):
  1. All BLE reads and writes pass through single BleOperationQueue
  2. Concurrent BLE operations cannot interleave (Issue #222 prevention)
  3. Integration test verifies no fault 16384 under concurrent access
  4. Write retry logic (3 attempts) preserved
**Plans**: 1 plan
  - [ ] 07-01-PLAN.md - Extract BleOperationQueue, update KableBleRepository to use queue

### Phase 8: DiscoMode + Interface
**Goal**: Disco mode easter egg self-contained, concrete cast eliminated
**Depends on**: Phase 7
**Requirements**: DISCO-01, IFACE-01, IFACE-02
**Success Criteria** (what must be TRUE):
  1. DiscoMode.start() cycles LED colors correctly on connected device
  2. DiscoMode.stop() restores last color scheme
  3. setLastColorSchemeIndex() available on BleRepository interface
  4. SettingsManager no longer casts to KableBleRepository (Issue #144 fixed)
**Plans**: 1 plan
  - [ ] 08-01-PLAN.md — Extract DiscoMode.kt, add interface method, fix SettingsManager cast

### Phase 9: HandleStateDetector
**Goal**: 4-state handle detection machine extracted and testable
**Depends on**: Phase 8
**Requirements**: HAND-01, HAND-02, HAND-03
**Success Criteria** (what must be TRUE):
  1. HandleStateDetector implements WaitingForRest -> Released -> Grabbed -> Moving state machine
  2. Overhead pulley baseline tracking works (Issue #176 fix preserved)
  3. Just Lift autostart mode detects handle grab correctly
  4. First rep always registers after workout start (baseline initialized correctly)
  5. Unit tests cover all state transitions with synthetic metrics
**Plans**: 2 plans
  - [ ] 09-01-PLAN.md (TDD) — Create HandleStateDetector with 25+ unit tests for all state transitions
  - [ ] 09-02-PLAN.md — Wire KableBleRepository delegation, remove inline handle state logic

### Phase 10: MonitorDataProcessor
**Goal**: Position validation and velocity EMA extracted to focused module
**Depends on**: Phase 9
**Requirements**: PROC-01, PROC-02, PROC-03
**Success Criteria** (what must be TRUE):
  1. MonitorDataProcessor handles position validation, jump filtering, velocity EMA
  2. Position jump filter does not cascade to next sample (Issue #210 fix preserved)
  3. Latency budget <5ms maintained for handleMonitorMetric hot path
  4. Status flag processing (deload, ROM violation) works correctly
**Plans**: 2 plans
  - [ ] 10-01-PLAN.md (TDD) -- Create MonitorDataProcessor with ~30 unit tests for processing pipeline
  - [ ] 10-02-PLAN.md -- Wire KableBleRepository delegation, remove inline processing logic

### Phase 11: MetricPollingEngine
**Goal**: All 4 polling loops managed by single component
**Depends on**: Phase 10
**Requirements**: POLL-01, POLL-02, POLL-03
**Success Criteria** (what must be TRUE):
  1. MetricPollingEngine manages monitor (10-20Hz), diagnostic (1Hz), heuristic (4Hz), heartbeat (0.5Hz) loops
  2. stopMonitorOnly preserves diagnostic and heartbeat polling (Issue #222)
  3. Timeout disconnect after MAX_CONSECUTIVE_TIMEOUTS works correctly
  4. Job lifecycle (start/stop/restart) managed atomically
**Plans**: 2 plans
  - [ ] 11-01-PLAN.md (TDD) -- Create MetricPollingEngine with polling loops, lifecycle tests
  - [ ] 11-02-PLAN.md -- Wire KableBleRepository delegation, remove ~300 lines of polling code

### Phase 12: KableBleConnectionManager + Facade
**Goal**: Connection lifecycle extracted, KableBleRepository reduced to thin facade
**Depends on**: Phase 11
**Requirements**: CONN-01, CONN-02, CONN-03, FACADE-01, FACADE-02, FACADE-03
**Success Criteria** (what must be TRUE):
  1. KableBleConnectionManager handles scan, connect, disconnect lifecycle
  2. Auto-reconnect after unexpected disconnect works correctly
  3. Connection retry logic (3 attempts) preserved
  4. KableBleRepository reduced to <400 lines (delegation only)
  5. All existing tests pass without modification
  6. Manual BLE testing on physical Vitruvian device passes all scenarios
**Plans**: 2 plans
Plans:
- [ ] 12-01-PLAN.md -- Create KableBleConnectionManager with all connection lifecycle code + tests
- [ ] 12-02-PLAN.md -- Wire facade delegation, reduce KableBleRepository to <400 lines, manual BLE testing

### Phase 13: MetricPollingEngine Verification
**Goal**: Formal verification of Phase 11 success criteria (gap closure from milestone audit)
**Depends on**: Phase 11
**Requirements**: POLL-01, POLL-02, POLL-03
**Gap Closure**: Closes 3 requirement gaps from v0.4.2 milestone audit
**Success Criteria** (what must be TRUE):
  1. VERIFICATION.md generated with evidence for all Phase 11 success criteria
  2. POLL-01 verified: MetricPollingEngine manages monitor, diagnostic, heuristic, heartbeat loops
  3. POLL-02 verified: stopMonitorOnly preserves diagnostic and heartbeat polling (Issue #222)
  4. POLL-03 verified: Timeout disconnect after MAX_CONSECUTIVE_TIMEOUTS works correctly
**Plans**: 1 plan
  - [ ] 13-01-PLAN.md - Verify Phase 11 success criteria, generate VERIFICATION.md

## Progress

**Execution Order:**
Phases 5 -> 6 -> 7 -> 8 -> 9 -> 10 -> 11 -> 12 -> 13

| Phase | Milestone | Plans Complete | Status | Completed |
|-------|-----------|----------------|--------|-----------|
| 5. BleProtocolConstants | v0.4.2 | 1/1 | Complete | 2026-02-15 |
| 6. ProtocolParser | v0.4.2 | 2/2 | Complete | 2026-02-15 |
| 7. BleOperationQueue | v0.4.2 | 1/1 | Complete | 2026-02-15 |
| 8. DiscoMode + Interface | v0.4.2 | 0/1 | Not started | - |
| 9. HandleStateDetector | v0.4.2 | 0/? | Not started | - |
| 10. MonitorDataProcessor | v0.4.2 | Complete    | 2026-02-15 | - |
| 11. MetricPollingEngine | v0.4.2 | 0/? | Not started | - |
| 12. KableBleConnectionManager + Facade | v0.4.2 | Complete    | 2026-02-16 | - |
| 13. MetricPollingEngine Verification | v0.4.2 | Complete    | 2026-02-16 | - |

---
*Roadmap created: 2026-02-15*
*Milestone: v0.4.2 BLE Layer Decomposition*
