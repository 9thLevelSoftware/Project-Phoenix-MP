# Research Summary: BLE Layer Refactoring Pitfalls

**Project:** Project Phoenix MP — KableBleRepository Decomposition
**Domain:** Decomposing BLE communication layers in fitness/workout apps
**Researched:** 2026-02-15
**Overall confidence:** HIGH (based on codebase analysis + documented issue history + platform-specific research)

---

## Executive Summary

Refactoring BLE layers is not a standard "code cleanup" task. BLE is a **pipeline, not a modular system**. It consists of 4 tightly-coupled subsystems (scanning, connection, polling, parsing) that share hidden temporal dependencies and implicit contracts. Naive extraction along functional boundaries (separate manager per feature) breaks these contracts and causes:

1. **Operation Interleaving** (Issue #222): BLE reads/writes collide without serialization, causing fault codes 16384-32768 in machine logs
2. **State Machine Races**: Connection state transitions become non-atomic, devices stuck in intermediate states or reconnect loops
3. **Hot Path Latency Regression**: Monitor polling adds method call overhead, dropping frames at 10-20Hz polling rate
4. **Data Loss**: Metric collection and persistence boundaries diverge, losing workout metrics
5. **Baseline Context Loss**: Handle state machine loses position baseline when extracted, breaking phase tracking
6. **Platform Divergence**: Android Kable API is synchronous; iOS CoreBluetooth is async. Refactoring toward "unified" breaks iOS

The codebase's **documented issues provide clear warning signs**:
- **Issue #222**: Bodyweight exercise sends BLE stop → traced to operation interleaving → fixed via BleOperationQueue + Mutex
- **Issue #210**: First rep doesn't register → traced to Rep counter state carryover → required exhaustive tests
- **Issue #176**: Handle baseline tracking broken on overhead pulleys → requires explicit baseline lifecycle management

**Key Insight:** These issues all emerged during *composition* after extraction, not during the extraction itself. The refactored code was individually correct but broke when composed.

---

## Key Findings

### Stack
**Core Dependencies:**
- **Kable 0.33+** (KMP BLE library) — handles platform abstraction for Android (Nordic) and iOS (CoreBluetooth)
- **Kotlin 2.0.21 + Coroutines 1.9.0** — for cross-platform async
- **SQLDelight 2.0.2** — for metrics persistence (iOS has 996-line workaround for migration bugs)
- **Koin 4.0.0** — dependency injection

**Current Monolith:**
- **KableBleRepository** — 2,886 lines, 10 interleaved subsystems, ~50 mutable fields, zero explicit thread safety
- **Issue**: All state lives in one class. Extraction breaks implicit atomicity.

**Target Architecture (8 modules):**
1. `BleProtocolConstants` — UUIDs, timing, thresholds (immutable)
2. `BleOperationQueue` — Mutex-based serialization for all reads/writes (CRITICAL for #222)
3. `BleConnectionManager` — Scanning, connection, sequencing (high-risk state machine)
4. `ProtocolParser` — Pure byte→domain functions (must preserve latency budget <1ms)
5. `MonitorDataProcessor` — Position validation, velocity EMA (must preserve <5ms total hot path)
6. `MetricPollingEngine` — 4 polling loops + heartbeat (job cancellation order matters)
7. `HandleStateDetector` — 4-state machine, baseline tracking (must reset on workout end)
8. `DiscoMode` — Easter egg LED cycling (self-contained)

### Features
**What to Build in Each Phase:**

| Phase | Feature | Complexity | Risk |
|-------|---------|------------|------|
| **Phase 1** | BleOperationQueue (Mutex serialization) | Med | CRITICAL |
| **Phase 1** | BleConnectionManager (scanning, connection, discovery sequencing) | High | CRITICAL |
| **Phase 1** | BleProtocolConstants (extract all compile-time constants) | Low | Low |
| **Phase 2** | ProtocolParser (stateless byte parsing) | Med | MEDIUM (latency) |
| **Phase 2** | MonitorDataProcessor (position validation, EMA smoothing) | Med | HIGH (hot path) |
| **Phase 2** | MetricPollingEngine (4 loops + heartbeat) | High | MEDIUM (job isolation) |
| **Phase 3** | HandleStateDetector (baseline tracking state machine) | Med | MEDIUM-HIGH (lifecycle) |
| **Phase 3** | Remaining extraction (error handling, reconnection) | Low | Low |

**Dependencies:**
- MonitorDataProcessor depends on ProtocolParser
- MetricPollingEngine depends on BleOperationQueue (all polling reads go through queue)
- HandleStateDetector depends on MonitorDataProcessor (reads processed positions)

### Architecture
**Recommended Decomposition Pattern:**

```
┌─────────────────────────────────────────────┐
│  KableBleRepository (Thin Facade ~350L)    │  ← Orchestrates components
│  Implements BleRepository interface        │     Handles pub/sub of StateFlows
├─────────────────────────────────────────────┤
│ Input: Scanning + Connection Management    │
├─────────────────────────────────────────────┤
│  BleConnectionManager                       │  ← Separate class
│  - Scans for devices                        │     Handles sequential state
│  - Connects/disconnects                     │     Owns connectionState flow
│  - Discovers services/characteristics       │
│  - Negotiates MTU                           │
│  - Guards: explicitDisconnect flag          │
├─────────────────────────────────────────────┤
│ Core: Metric Collection (Hot Path)          │
├─────────────────────────────────────────────┤
│  BleOperationQueue (Mutex)                  │  ← CRITICAL
│  - Serializes ALL reads/writes              │     Every operation goes through
│  - Prevents concurrent access               │     this gate
├─────────────────────────────────────────────┤
│  MetricPollingEngine                        │  ← Drives the loops
│  - Manages 4 independent polling jobs       │
│  - Calls synchronized functions below       │
├─────────────────────────────────────────────┤
│  ProtocolParser (pure functions)            │  ← Stateless
│  + MonitorDataProcessor (EMA, validation)   │     Called synchronously
│  + HandleStateDetector (baseline SM)        │
├─────────────────────────────────────────────┤
│ Outputs: StateFlows                         │
├─────────────────────────────────────────────┤
│  monitorData: WorkoutMetric                 │  ← 10-20Hz updates
│  connectionState: ConnectionState           │
│  handleState: HandleState                   │
│  metrics: MetricSample (batch at set end)   │
└─────────────────────────────────────────────┘
```

**Critical Design Principle:**
- **BleOperationQueue is the serialization point for ALL BLE access** — every read/write goes through it
- **MonitorDataProcessor is the hot-path orchestrator** — it calls sub-managers synchronously in sequence
- **No sub-manager gets its own scope** — all share the parent viewModelScope; jobs are managed centrally
- **Connection state is atomic** — all transitions go through a single StateFlow, validated by state machine

### Pitfalls
**Critical (Cause Complete Regressions):**

1. **Operation Interleaving** — Without BleOperationQueue mutex, concurrent reads/writes corrupt packets (fault 16384). Visible as intermittent BLE faults.

2. **Breaking Hot Path** — MonitorDataProcessor split across 5 sub-managers breaks state consistency. Rep counter reads stale values, auto-stop triggers at wrong time.

3. **Connection State Races** — Refactoring connection into separate class loses atomicity. Auto-reconnect checks get out of sync with explicit disconnect flags. Device stuck connecting/disconnecting.

4. **Characteristic Discovery Timeout** — If connection sequence isn't strictly ordered (discover services → discover characteristics → request MTU → subscribe → poll), iOS gets stuck waiting for callbacks that don't fire.

5. **Baseline Context Loss** — Handle state detector loses position baseline when extracted. Next workout starts with stale baseline, handle state stuck in GRABBED.

6. **Metric Persistence Loss** — Extracting metrics collection into separate manager breaks the "collect during set → persist at set completion" contract. Metrics lost when buffer rolls over.

7. **Packet Parsing Latency Regression** — Extracting parser to stateless class adds allocations or Flow emissions in hot path. Latency increases 0.5ms → 2ms. Drops frames at 20Hz polling.

8. **iOS CoreBluetooth Async Mismatch** — Android's Kable is sync; iOS is async. Refactoring toward "unified layer" breaks iOS by not respecting callback sequencing.

**Moderate (Cause Subsystem Failures):**

9. **State Leakage Between Workouts** — Mutable state not reset on new workout (lastTopCounter, stallStartTime). Next workout starts with corrupted state.

10. **Job Cancellation Order** — Sub-managers with separate scopes create orphaned collectors. Job cancellation doesn't reach dependent jobs.

11. **Polling Loop Starvation** — Slow operation in one loop (monitor, diagnostic, heuristic, heartbeat) blocks others if they share dispatcher.

**Detection & Prevention:**
- **Measurement-driven**: Establish latency budgets before refactoring (<5ms for hot path)
- **Characterization tests**: Capture monolith behavior on real hardware, verify refactored version matches
- **Hardware-first**: Test with actual Vitruvian hardware + iOS device before merging
- **Explicit contracts**: Document invariants, ordering, lifecycle in code + comments
- **State machine validation**: Every transition logged + validated (can't go Connecting → Idle without Connected)

---

## Implications for Roadmap

### Phase Structure Recommendation

**Phase 1: Foundation (CRITICAL — Serialization + Connection)**
- Extract `BleProtocolConstants` (zero risk, defines UUIDs/timeouts)
- Extract `BleOperationQueue` with Mutex serialization
  - Every read/write MUST go through this gate
  - Test with concurrent stress (10+ threads hammering device)
- Extract `BleConnectionManager` with enforced sequencing
  - Scanning → Connecting → Connected → Notifications → Polling
  - iOS device required for testing
  - Validate state transitions (only allow legal transitions)
- **Why this order:** Serialization must exist before refactoring parsing/polling. Connection must be solid before polling starts.
- **Risk level:** CRITICAL. Get this wrong and all subsequent phases inherit the bug.
- **Test gate:** Must verify no fault 16384 in machine logs + connection success rate >99% with actual hardware

**Phase 2: Hot Path (HIGHEST RISK — Latency Sensitive)**
- Extract `ProtocolParser` (stateless, pure functions)
  - Measure latency before/after (must stay <1ms)
  - Test with real device polling at 20Hz
- Extract `MonitorDataProcessor` (velocity EMA, position validation)
  - Keep as synchronous call from monitor loop
  - Preserve EMA state (smoothed velocity survives between frames)
- Extract `MetricPollingEngine` (4 loops + heartbeat)
  - ALL loops share parent scope (no sub-manager scopes)
  - Document job cancellation order
  - Separate dispatchers for high-freq (monitor) vs low-freq (diagnostic)
- **Why this order:** Must have serialization queue + connection working before extracting polling
- **Risk level:** HIGH. Latency regressions are hard to detect in tests but obvious on real hardware.
- **Test gate:** Characterization test shows latency <5ms for handleMonitorMetric() on real device. Frame drop rate = 0.

**Phase 3: State Machines (MEDIUM RISK — Lifecycle Sensitive)**
- Extract `HandleStateDetector` (4-state machine, baseline tracking)
  - Baseline initialized once per workout, reset on workout end
  - Test baseline reset explicitly (finish exercise → start new → verify fresh baseline)
  - Characterize baseline behavior with actual machines (V-Form vs Trainer+)
- Remaining error handling, reconnection logic
- **Why this order:** Depends on working monitor polling. Baseline depends on position data from polling.
- **Risk level:** MEDIUM-HIGH. Baseline lifecycle is easy to get wrong.
- **Test gate:** Characterization test shows handle state matches monolith behavior. First rep always registered.

**Phase 4: Cleanup (LOW RISK — Cosmetic)**
- Extract remaining private methods into utilities
- Remove dead code from monolith
- Final refactoring of facade
- **Risk level:** LOW. At this point, core logic is extracted and tested.
- **Test gate:** None specific — just code review.

### Testing Strategy

**Pre-refactoring (Establish Baselines):**
1. Run monolithic version against real hardware for 1 hour straight
   - Capture: all BLE operations (read/write sequences), metric counts, handle state transitions
   - Establish baselines: latency of handleMonitorMetric(), connection success rate, fault codes
2. Create "characterization tests" that verify exact behavior

**During Each Phase:**
1. After merging code: verify characterization tests still pass
2. Hardware testing: real device, 30+ minutes of active workouts
3. Edge case testing: disconnect mid-polling, corrupted packets, rapid state changes, back-to-back workouts

**Post-refactoring (Verification):**
1. Side-by-side testing: monolith vs refactored, same hardware, same workout, compare metrics
2. Long-duration testing: 4+ hours of heavy workouts
3. iOS device testing: connection success, metrics, handle state (MUST use physical device, not simulator)

### Phase Ordering Rationale

1. **Start with serialization (Phase 1)**: Operation interleaving is the #1 blocker. Fix it first or everything else is built on broken foundations.

2. **Connection second (Phase 1)**: Hot path depends on stable connection. No point optimizing polling if connection is flaky.

3. **Polling third (Phase 2)**: Once connection is stable, extract polling. This is where latency matters most. Measure before/after.

4. **State machines last (Phase 3)**: Depend on working polling. Baseline detection needs position data flowing.

5. **Cleanup last (Phase 4)**: Only after core logic is extracted and verified.

### Risk Mitigation Strategies

| Risk | Phase | Mitigation |
|------|-------|-----------|
| Operation interleaving (fault 16384) | 1 | BleOperationQueue with Mutex. Test concurrent stress. Measure in production. |
| Hot path latency drop | 2 | Establish latency budget (<5ms). Measure before/after. Test on real device. Revert if budget exceeded. |
| Connection state races | 1 | StateFlow-based state machine with transition validation. Log all transitions. |
| Baseline context loss | 3 | Explicit baseline lifecycle. Reset on workout end. Characterize with actual machines. |
| Data loss (metrics) | 2 | Track metrics count. Add DB constraints. Test long sets (50+ reps). |
| iOS CoreBluetooth failures | 1 | Require iOS device testing before merging BleConnectionManager. Simulator is insufficient. |

### Rollback Criteria

Deploy refactored phase only if:
- Characterization tests pass (behavior matches monolith)
- No new BLE fault codes in device logs
- Connection success rate ≥99% (vs baseline)
- Latency regression <20% (vs baseline)
- iOS testing passed with physical device

If any criterion fails post-deployment:
- Immediate rollback to stable version
- A/B test refactored vs monolithic in beta
- Fix root cause, re-test, redeploy

---

## Confidence Assessment

| Area | Confidence | Evidence |
|------|------------|----------|
| **Stack** | HIGH | Codebase uses Kable 0.33+, explicit versions in CLAUDE.md, no ambiguity |
| **Critical pitfalls** | HIGH | Issue #222, #210, #176 documented + traced to root causes + fixed → learned lessons |
| **BLE platform differences** | HIGH | Research confirms Android async model differs from iOS, multiple sources agree |
| **Hot path latency budget** | MEDIUM-HIGH | Codebase comments mention "~20Hz polling rate," monitor loop polling code visible. Not measured explicitly. |
| **iOS testing feasibility** | MEDIUM | No iOS device confirmed. Simulator known to be unreliable for Bluetooth. |
| **Refactoring approach** | HIGH | Decomposition plan exists in codebase, detailed analysis of subsystem dependencies |

---

## Gaps to Address in Phase Planning

1. **Latency Measurement**: Establish exact latency budget for handleMonitorMetric(). Measure current monolith with real hardware, real polling rates.

2. **iOS Testing Access**: Confirm iOS device available for testing. If not, plan for simulator limitations or external testing partnership.

3. **Backward Compatibility**: Any users with in-progress workouts in older versions? Do they need data migration after refactoring?

4. **Rollback Automation**: Can production metrics (Crashlytics, analytics) automatically detect refactoring regressions? What's the alert threshold?

5. **Monitoring in Production**: What BLE-specific metrics should be added to production monitoring? Fault code frequency? Connection success rate?

---

## Key Takeaway

**BLE refactoring is not just "code organization." It's redefining the contracts between scanning, connection, polling, and parsing. Get the contracts wrong and you break regressions invisible in tests but obvious on real hardware. Measure before, measure after, test on actual devices.**

---

## Research Files

- **BLE-REFACTORING-PITFALLS.md** — Detailed analysis of 13 pitfalls with examples from this codebase
- **STACK.md** — Technology dependencies and versions
- **FEATURES.md** — What to build (phases and features)
- **ARCHITECTURE.md** — System structure and component boundaries
