# Project Research Summary

**Project:** Project Phoenix MP - KableBleRepository Decomposition
**Domain:** Kotlin Multiplatform BLE communication layer refactoring
**Researched:** 2026-02-15
**Confidence:** HIGH

## Executive Summary

This project is decomposing a 2,886-line `KableBleRepository` monolith into 8 focused, testable modules within `commonMain`. The domain is BLE-based workout machine control for Vitruvian fitness equipment, where the communication layer manages scanning, connection, real-time metric polling at 10-20Hz, protocol parsing, and command serialization. Expert practice for BLE refactoring emphasizes **serialization-first architecture** (all reads/writes through a single queue), **characterization testing before extraction** (lock in packet sequences and timing behavior), and **hardware-first validation** (fakes can't catch platform-specific callback ordering or MTU negotiation edge cases).

The recommended approach is **conservative modular extraction** following the established v0.4.1 manager decomposition pattern: extract serialization infrastructure (BleOperationQueue) and connection sequencing (BleConnectionManager) first, then hot-path processing (ProtocolParser, MonitorDataProcessor, MetricPollingEngine) second, and state machines (HandleStateDetector) last. All modules stay in `commonMain` because BLE logic is platform-agnostic—only Kable's Peripheral wrapper is platform-specific. The tech stack is production-ready: no new dependencies needed; existing kotlinx-coroutines-test, Turbine, and hand-written fakes provide complete test coverage for KMP.

The key risks are **operation interleaving** (Issue #222: concurrent read+write corrupts BLE packets causing fault 16384), **hot-path latency regression** (adding overhead to 10-20Hz polling drops frames and breaks rep counting), and **iOS callback sequencing** (CoreBluetooth's async contract differs from Android's synchronous Kable API). Mitigation: (1) BleOperationQueue with Mutex is the foundational extraction—get it right or all phases inherit the bug; (2) measure handleMonitorMetric latency before/after extraction with <5ms budget; (3) require iOS device testing before merging connection manager changes—simulator is unreliable for Bluetooth. Follow the **measure-extract-verify-on-hardware** cycle for each phase.

## Key Findings

### Recommended Stack

**Verdict: No new dependencies required.** The project already has the right testing infrastructure for BLE decomposition.

**Core technologies:**
- **kotlinx-coroutines-test 1.9.0** — Virtual time control for testing BLE timeout logic, polling intervals, and async operations without delays. Already in shared module; provides runTest, advanceTimeBy, and TestDispatcher for coroutine isolation.
- **Turbine 1.2.1** — Flow/SharedFlow testing with awaitItem() and cancelAndIgnoreRemainingEvents(). Already in shared module; essential for testing BLE notification flows and state emissions at 10-20Hz without manual collection.
- **Koin Test 4.1.1** — DI module verification with verify() API. Enables testing Koin module setup and injecting fakes into real modules for isolated BLE layer testing.
- **Hand-written fakes (FakeBleRepository pattern)** — KMP-compatible test doubles work on all platforms without reflection. Project already has FakeBleRepository with controllable state, event simulation, and command tracking. Pattern proven in existing codebase; extend to 8 new BLE modules.
- **SQLDelight 2.0.2** — In-memory SQLite for data layer tests (no mocking needed). Already configured for Android/iOS with native test support.

**Why fakes over mocking frameworks:** MockK is JVM-only (blocks Kotlin/Native), and KSP-based mocking (Mockative/Mokkery/KMock) generates NullPointerExceptions with Kotlin 2.0's source separation. Hand-written fakes are easier to debug, work on all KMP targets, and make tests more readable than mock spy chains.

**Testing patterns established:**
- `runTest { }` for suspend function testing with virtual time
- `flow.test { awaitItem(); cancelAndIgnoreRemainingEvents() }` for StateFlow/SharedFlow testing
- `advanceTimeBy(350.milliseconds)` for timing-sensitive BLE polling validation
- `koin.verify()` for DI module testing after extraction

### Expected Features

**Must have (table stakes):**
- **8-module decomposition of KableBleRepository** — 2,886 lines with 10 interleaved subsystems is unmaintainable; any bug fix in polling logic risks breaking connection sequencing
- **BleOperationQueue with Mutex serialization** — ALL reads/writes through single gate to prevent Issue #222 regression (concurrent operations corrupt packets, fault 16384)
- **Preserved public API surface (BleRepository interface)** — MainViewModel and DefaultWorkoutSessionManager delegate to BleRepository; 15+ call sites must not change
- **Zero behavior regression** — Connection lifecycle, metric polling, rep counting, handle state detection, auto-stop, workout history must work identically
- **Characterization tests for BLE behavior** — Lock in connection sequence (scan→connect→discover→MTU→subscribe→poll), packet parsing edge cases, handle state transitions, polling timing
- **Hardware validation for each phase** — Test with actual Vitruvian hardware (V-Form/Trainer+) before merging; fakes can't catch MTU negotiation, iOS callback ordering, or timing-sensitive polling edge cases

**Should have (competitive):**
- **Interface-based module boundaries** — BleOperationQueue, BleConnectionManager, ProtocolParser as interfaces enable swapping implementations in tests without fakes
- **Explicit state machine validation** — ConnectionState transitions validated (can't go Connecting→Idle without Connected first); log all transitions with timestamps for race condition debugging
- **Latency instrumentation** — Timing measurements around handleMonitorMetric() in debug builds to detect hot-path regressions
- **Metrics count validation** — Before persisting workout, verify metric count matches expected (duration * 20Hz); detect buffer overflow/data loss early
- **iOS-specific connection tests** — Separate test suite for iOS CoreBluetooth sequencing (async callback contracts differ from Android)

**Defer (v2+):**
- **Complete BLE abstraction layer** — Wrapping all Kable Peripheral APIs in custom interfaces is premature; only wrap what tests need to substitute
- **State pattern refactor** — Converting imperative state management to formal state pattern would touch every BLE function; keep existing style
- **Multi-dispatcher isolation** — Separate dispatchers for monitor (high-freq) vs diagnostic (low-freq) polling is optimization, not correctness; defer until performance profiling shows need
- **Packet-level checksums** — Detecting corrupted packets is valuable but adds protocol complexity; BleOperationQueue prevents corruption at source

### Architecture Approach

**Target: 8 modules in shared/src/commonMain/kotlin/data/ble/ coordinated by thin KableBleRepository facade.**

The architecture mirrors the successful v0.4.1 management layer decomposition (MainViewModel → 5 managers). All modules stay in `commonMain` because BLE communication logic (parsing, queueing, state machines) is platform-agnostic—only Kable.Peripheral (not business logic wrapping it) is platform-dependent. This allows JVM tests without iOS/Android hardware.

**Major components:**

1. **BleProtocolConstants** — Immutable UUIDs, timeouts, thresholds (zero risk extraction)
2. **BleOperationQueue** — Mutex-based serialization for ALL reads/writes; prevents Issue #222 operation interleaving; CRITICAL foundation for all other modules
3. **BleConnectionManager** — Scanning, connection lifecycle, service/characteristic discovery sequencing, MTU negotiation; high-risk state machine with iOS platform quirks
4. **ProtocolParser** — Pure byte→domain parsing functions (parseRepNotification, parseMonitorData); stateless, <1ms latency budget
5. **MonitorDataProcessor** — Cable validation, position tracking, velocity EMA smoothing; stateful but synchronous, <5ms total hot-path budget
6. **MetricPollingEngine** — 4 independent polling loops (monitor 10-20Hz, diagnostic 1Hz, heuristic 4Hz, heartbeat 0.5Hz) + heartbeat; job lifecycle management
7. **HandleStateDetector** — 4-state machine (WaitingForRest→Released→Grabbed→Moving) with position baseline tracking; lifecycle-sensitive (baseline initialized once per workout)
8. **KableBleRepository (refactored)** — Thin facade (~350 lines) orchestrating components; implements BleRepository interface; exposes StateFlows; handles pub/sub

**Critical architectural patterns:**

- **Interface + constructor injection** — Services needing behavior substitution (queuing, I/O, Kable wrapping) use interfaces; enables test substitution
- **Pure functions for data transformation** — ProtocolParser is object with pure functions; input→output, no side effects, trivial to test without mocks
- **Callback-based polling** — MetricPollingEngine takes PollingCallback interface; decouples polling from data processing; inject test handlers to verify behavior
- **State machines with explicit transitions** — HandleStateDetector with clear contracts: WaitingForRest can only transition to Released when forceN < 5N; edge cases are testable
- **All modules in commonMain** — No expect/actual split for business logic; use for platform logging/schedulers only; Kable abstracts hard platform differences

### Critical Pitfalls

1. **Operation Interleaving — Losing Serialization Guarantees** — When BLE write/read pipeline is split into separate classes, each thinks it "owns" the characteristic and can access independently. Nordic UART's single TX characteristic re-uses write buffer internally; Thread A (polling monitor) + Thread B (writing stop command) collide → corrupted packet (fault 16384). **Prevention:** BleOperationQueue with Mutex that ALL read/write operations pass through; never call peripheral.write/read directly from multiple coroutines. **Detection:** Fault 16384/32768 in machine logs, "characteristic write failed" in Logcat, intermittent bodyweight exercise failures.

2. **Breaking the Monitor Polling Hot Path — State Consistency Loss** — handleMonitorMetric() called ~10-20Hz reads state from 5 subsystems (rep counter, auto-stop, position tracker, metrics collector, phase animator) and processes sequentially. Extracting into sub-managers breaks atomicity: autoStopManager reads repCount that repManager updated 1ms ago, but positionTracker just triggered phase change requiring OLD repCount → state inconsistent. **Prevention:** Keep handleMonitorMetric() as single orchestration point; sub-managers expose pure functions called synchronously in sequence; do NOT have sub-managers collect independently. **Detection:** Rep count lags by 1-2 reps, auto-stop triggers at wrong rep, position bar freezes.

3. **Connection State Machine Races — Silent Disconnect/Reconnect Loops** — Connection state has 4 explicit states (Disconnected→Connecting→Connected→Disconnecting) + implicit transitions (auto-reconnect on disconnect, explicitDisconnect flag). Extracting to separate BleConnectionManager loses atomicity: onDisconnect fires while disconnect() is called → race between "should I reconnect?" check and "reconnect" execution → stuck in Connecting while trying to Disconnect. **Prevention:** Make connection state transitions atomic via single StateFlow; all mutations through one method; guard flags (explicitDisconnect, isActive) part of ConnectionState sealed class, not separate vars. **Detection:** Impossible state transitions in logs (Disconnecting→Connecting), device connected after user stops workout, reconnect loop every 2s.

4. **Characteristic Discovery Timeout — Extraction Breaks MTU Negotiation Sequencing** — BLE connection sequence strictly ordered: connect→discover services→discover characteristics→request MTU→subscribe→poll. Extracting these into separate methods without preserving sequencing causes: characteristic discovery starts before services discovered (list empty), MTU negotiation before characteristics discovered (characteristic not available), polling starts before MTU completes (writes fail). **Prevention:** Use suspend fun for each step; chain explicitly with sequential await; validate state transitions (can't discover characteristics until services discovered); StateFlow guards. **Detection:** "Characteristic not found" in logs, polling receives "characteristic write failed," MTU stays low (20 instead of 247).

5. **Losing Handle State Baseline Context — Cascading Phase Tracking Failures** — Handle state detector is 4-state machine with position baseline set once when handle first goes UNKNOWN→AT_REST. Baseline persists across entire workout, changes between phases (warmup vs working), used to detect grabbed (position > baseline+8mm). Extracting loses baseline lifecycle: HandleStateDetector doesn't know when workout ends (depends on parent to call reset()), doesn't know current rep phase, first samples that should set baseline are lost if position data is buffered. **Prevention:** Make baseline separate PositionBaseline class initialized once per workout; HandleStateDetector is pure: (position, baseline, phase)→HandleState; parent owns baseline lifecycle. **Detection:** First rep doesn't register (baseline stale), handle state stuck in GRABBED (baseline from previous workout), overhead pulleys show GRABBED immediately (baseline set too high).

6. **Losing the Metric Persistence Contract — Data Loss in Workout History** — handleMonitorMetric() collects metrics into collectedMetrics list; handleSetCompletion() reads list and saves to DB. Extracting metric collection into sub-manager breaks reference: if getAndClearMetrics() isn't called before list rolls over (max 10K samples), oldest lost; if called at wrong time (during set, not completion), cleared prematurely; if manager crashes, lost without persist. **Prevention:** Keep metrics collection and persistence together OR use MetricsSnapshot pattern; add metrics count validation (before save, verify count matches expected); test with long sets (50+ reps). **Detection:** Workout history shows fewer samples than expected, analytics charts have gaps, metrics collection logs show buffer overflow.

7. **Packet Parsing in Hot Path — Hidden Performance Regression** — Packet parsing happens inside monitor polling loop at 10-20Hz. Extracting to separate ProtocolParser class accidentally adds allocations, Flow emissions, or coroutine context switches → latency increases 0.5ms→2ms per parse → at 20Hz lose 30ms/sec = visible frame drops. **Prevention:** Measure latency: add timing instrumentation around handleMonitorMetric(); establish latency budget (<1ms for parsing, <5ms total); test hot path: run 30s polling at 20Hz, measure 99th percentile latency; use inline functions for parser if needed. **Detection:** Characterization tests show latency increased, frame drops visible during high-rep workouts, rep counter UI lags actual reps by 1+.

8. **iOS Specific — Async Callback Hell From CoreBluetooth** — Android's Kable provides relatively synchronous API; iOS CoreBluetooth is fully async (every operation requires callback). Refactoring that assumes "all platforms work the same" breaks iOS: waiting for callback that never comes (UUID matching bug), reading characteristics before didDiscoverServices fired (list empty), requesting MTU without discovering characteristics first (ignored). **Prevention:** Test iOS separately with actual device (not simulator); use separate implementations for platform-specific sequencing (actual class BleConnectionManager); understand CoreBluetooth contract (operation→callback→next operation strictly serial); use 30s timeouts per operation; log all callbacks. **Detection:** iOS app hangs during connection, didDiscoverServices not called in logs, iOS app crashes after 15min (watchdog timeout waiting for callback).

## Implications for Roadmap

Based on research, suggested 3-phase structure with **7-9 week timeline estimate**:

### Phase 1: Foundation (Serialization + Connection) — 2-3 weeks
**Rationale:** Serialization is foundational—get BleOperationQueue wrong and all subsequent phases inherit Issue #222 bugs. Connection must be solid before polling starts. This is the highest-risk phase requiring iOS device testing.

**Delivers:**
- BleProtocolConstants (UUIDs, timeouts extracted to object)
- BleOperationQueue with Mutex (all BLE read/write through single gate)
- BleConnectionManager (scanning, connection lifecycle, discovery sequencing, MTU negotiation)
- Characterization tests for connection sequence (scan→connect→discover→MTU→subscribe)

**Addresses:**
- Operation interleaving pitfall (BleOperationQueue prevents concurrent access)
- Connection state machine races (atomic state transitions via StateFlow)
- Characteristic discovery timeout (enforced sequencing)
- iOS async mismatch (separate CoreBluetooth testing required)

**Avoids:**
- Issue #222 regression (bodyweight exercise BLE stop command corruption)
- Fault codes 16384/32768 in machine logs
- iOS connection hangs or reconnect loops

**Test gate:**
- No fault 16384 in machine logs after 30min hardware testing
- Connection success rate ≥99% with actual Vitruvian hardware
- iOS device testing passed (simulator insufficient for Bluetooth)
- Concurrent stress test (10+ threads hammering device) passes without packet corruption

### Phase 2: Hot Path (Parsing + Polling) — 3-4 weeks
**Rationale:** Monitor polling at 10-20Hz is where latency matters most. Must preserve <5ms total handleMonitorMetric() latency budget. Parser extraction is stateless (low risk), but MonitorDataProcessor has EMA state and MetricPollingEngine has complex job lifecycle. This phase has highest risk for silent performance regressions.

**Delivers:**
- ProtocolParser (pure byte→domain functions: parseRepNotification, parseMonitorData)
- MonitorDataProcessor (position validation, velocity EMA smoothing, cable data processing)
- MetricPollingEngine (4 polling loops: monitor 10-20Hz, diagnostic 1Hz, heuristic 4Hz, heartbeat 0.5Hz)
- Characterization tests for packet parsing edge cases (legacy 6-byte vs official 24-byte rep notifications)
- Latency instrumentation for hot-path monitoring

**Uses:**
- kotlinx-coroutines-test with virtual time (advanceTimeBy for polling interval validation)
- Turbine for flow testing (monitorData emissions at 20Hz)
- Pure function pattern (ProtocolParser object with no state)
- Callback-based polling (PollingCallback interface decouples engine from processing)

**Implements:**
- Single orchestration point pattern (handleMonitorMetric() stays in repository, calls sub-managers synchronously)
- Latency budget enforcement (<1ms for parsing, <5ms total hot path)

**Avoids:**
- Breaking hot path (state consistency preserved via synchronous sequential calls)
- Packet parsing latency regression (measure before/after, inline if needed)
- Metric persistence loss (collection and saving stay coordinated)
- Polling loop starvation (job isolation, separate dispatchers if profiling shows need)

**Test gate:**
- Characterization tests show latency <5ms for handleMonitorMetric() at 20Hz on real device
- Frame drop rate = 0 during 30min high-rep workout
- Metrics count validation passes (expected count = duration * 20Hz)
- All packet parsing edge cases covered (legacy formats, corrupted packets)

### Phase 3: State Machines (Handle Detection + Cleanup) — 2 weeks
**Rationale:** Handle state detector depends on working monitor polling (needs position data flowing). Baseline lifecycle is medium-risk but well-understood from Issue #176. This is the lowest-risk phase since core BLE infrastructure is solid.

**Delivers:**
- HandleStateDetector (4-state machine: WaitingForRest→Released→Grabbed→Moving)
- Position baseline tracking (initialized once per workout, phase-aware thresholds)
- Remaining extraction (error handling, reconnection logic, DiscoMode easter egg)
- Final refactoring of KableBleRepository as thin facade (~350 lines)

**Uses:**
- State machine pattern with explicit transitions
- Baseline as separate PositionBaseline class (parent owns lifecycle)
- Pure function for state detection: (position, baseline, phase)→HandleState

**Implements:**
- Explicit baseline lifecycle management (initialized on first position samples, reset on workout end)
- Phase-aware thresholds (overhead pulleys vs leg press have different grabbed detection)

**Avoids:**
- Baseline context loss (baseline lives in parent, passed to detector)
- State leakage between workouts (reset() called explicitly on workout end)

**Test gate:**
- Characterization tests show handle state matches monolith behavior
- First rep always registered after workout start (baseline initialized correctly)
- Baseline reset test passes (finish exercise → start new → verify fresh baseline)
- Different machines tested (V-Form vs Trainer+ with different ROMs)

### Phase 4: Integration & Hardening — 1 week (overlap with Phase 3)
**Rationale:** Final verification that all modules compose correctly, no regressions in production scenarios, iOS device validation complete.

**Delivers:**
- Side-by-side testing (monolith vs refactored, same hardware, same workout)
- Long-duration testing (4+ hours heavy workouts)
- iOS device validation (connection, metrics, handle state on physical iPhone)
- Production metrics instrumentation (connection success rate, BLE fault frequency, metric loss detection)

**Test gate:**
- Side-by-side test shows identical behavior (same BLE packets, same state transitions, same metric counts)
- Long-duration test completes without memory leaks, job leaks, or state corruption
- iOS physical device testing passed for all 3 phases
- Production rollback criteria defined (fault 16384 appears → immediate revert)

### Phase Ordering Rationale

**Why Phase 1 (Serialization + Connection) must come first:**
- BleOperationQueue is the foundation preventing Issue #222 regression
- Connection must be stable before polling starts (can't test polling without connection)
- iOS callback sequencing is iOS-specific and high-risk; validate early before building on top

**Why Phase 2 (Hot Path) must come second:**
- Depends on BleOperationQueue (all polling reads/writes go through queue)
- Depends on stable connection (can't poll without connection)
- Latency is critical but only measurable with real hardware and real connection

**Why Phase 3 (State Machines) comes last:**
- HandleStateDetector depends on working monitor polling (needs position data)
- Baseline detection only works when position samples flow correctly
- Lowest risk phase (cleanup and state machines are well-understood)

**Why 4 phases instead of 8 (one per module):**
- Related modules grouped by risk and dependency (connection + serialization are foundational)
- Hot-path modules (parsing + processing + polling) tested together for latency validation
- Avoids merge churn from 8 separate PRs
- Each phase has clear test gate and rollback point

### Research Flags

**Phases needing deeper research during planning:**

- **Phase 1 (BleConnectionManager):** iOS CoreBluetooth callback sequencing edge cases may need deeper platform-specific investigation; existing research is high-confidence but iOS-specific bugs are probabilistic and timing-dependent
- **Phase 2 (MetricPollingEngine):** Job cancellation order and lifecycle dependencies may need code walkthrough to document which jobs are cancelled by which state transitions (currently implicit knowledge spread across stopWorkout, handleSetCompletion, resetForNewWorkout, cleanup)
- **Phase 2 (MonitorDataProcessor):** EMA smoothing state (lastSmoothedVelocity) needs explicit lifecycle documentation (when initialized, when reset, how state survives between frames)

**Phases with standard patterns (skip research-phase):**

- **Phase 1 (BleProtocolConstants):** Pure extraction of compile-time constants; zero risk, standard refactoring pattern
- **Phase 1 (BleOperationQueue):** Mutex serialization pattern is well-established; existing Issue #222 provides exact requirements
- **Phase 2 (ProtocolParser):** Pure function extraction is mechanical; byte parsing logic already isolated
- **Phase 3 (HandleStateDetector):** Issue #176 provides exact requirements for baseline tracking; state machine pattern is standard

### Timeline Estimate: 7-9 weeks total

- **Phase 1:** 2-3 weeks (high complexity: iOS testing, state machine validation, characterization tests)
- **Phase 2:** 3-4 weeks (highest risk: hot-path latency testing, complex polling engine, metric persistence validation)
- **Phase 3:** 2 weeks (medium complexity: state machine extraction, baseline lifecycle)
- **Phase 4:** 1 week overlap with Phase 3 (integration testing, iOS validation, production instrumentation)

**Assumptions:**
- iOS device available for testing (simulator insufficient)
- Actual Vitruvian hardware available (V-Form or Trainer+)
- Characterization tests written before extraction begins (not counted in timeline)
- One developer full-time equivalent
- No major scope creep (additional features deferred to v0.4.3+)

## Confidence Assessment

| Area | Confidence | Notes |
|------|------------|-------|
| Stack | HIGH | Codebase uses kotlinx-coroutines-test 1.9.0, Turbine 1.2.1, Koin 4.1.1 explicitly; version catalog confirms compatibility; FakeBleRepository pattern proven in existing tests; no new dependencies needed |
| Features | HIGH | 8-module decomposition matches codebase analysis (2,886 lines KableBleRepository); Issue #222, #210, #176 documented and traced to root causes; BleOperationQueue and HandleStateDetector requirements explicit |
| Architecture | HIGH | All-in-commonMain pattern proven in v0.4.1 manager decomposition (SettingsManager, HistoryManager, GamificationManager in commonMain); interface+constructor injection matches BleConnectionManager pattern; Kable abstracts platform differences |
| Pitfalls | HIGH | Issue #222 (operation interleaving) root cause confirmed via codebase; Issue #210 (rep counter timing) explicitly mentions exhaustive tests; Issue #176 (handle baseline) provides exact state machine requirements; BLE platform differences confirmed via research (Android synchronous Kable vs iOS async CoreBluetooth) |

**Overall confidence:** HIGH

Research based on direct codebase analysis (KableBleRepository.kt 2,886 lines, existing test infrastructure, documented issues) + authoritative BLE platform documentation (Nordic BLE patterns, CoreBluetooth contracts, Kable GitHub) + established KMP testing patterns (kotlinx-coroutines-test official docs, Turbine best practices).

### Gaps to Address

**Gap 1: Exact latency budget for handleMonitorMetric() hot path**
- Research suggests <5ms total budget based on 10-20Hz polling rate
- Codebase comments mention "~20Hz polling rate" but exact performance requirements not documented
- **Mitigation:** Phase 2 planning must include profiling monolith on actual hardware to establish baseline latency; use Layout Inspector recomposition counter + battery drain comparison as secondary signals

**Gap 2: iOS device testing availability**
- iOS-specific pitfalls (callback sequencing, MTU negotiation, watchdog timeouts) are high-confidence from research
- Simulator known to be unreliable for Bluetooth testing
- **Mitigation:** Confirm iOS device availability before Phase 1 planning; if unavailable, plan for external iOS testing partnership or accept iOS testing deferred to beta rollout

**Gap 3: Metric persistence contract documentation**
- collectedMetrics list is appended in handleMonitorMetric(), read in handleSetCompletion()
- Buffer size limits (MAX_METRICS_PER_SET) mentioned in research but exact value not confirmed
- **Mitigation:** Phase 2 planning must include code walkthrough to document exact persistence contract; add explicit buffer overflow detection/logging

**Gap 4: Job cancellation order across polling loops**
- 4 independent polling loops (monitor, diagnostic, heuristic, heartbeat) + multiple tracked jobs (workoutJob, monitorDataCollectionJob, autoStartJob, restTimerJob, bodyweightTimerJob)
- Cancellation order currently implicit (spread across stopWorkout, handleSetCompletion, resetForNewWorkout, cleanup)
- **Mitigation:** Phase 2 planning must include documentation of job lifecycle and cancellation dependencies before MetricPollingEngine extraction

**Gap 5: Backward compatibility with in-progress workouts**
- No information on whether refactored BLE layer requires data migration for users with incomplete workouts
- Database schema (WorkoutSession, MetricSample) unchanged but BLE state serialization might differ
- **Mitigation:** Phase 1 planning must include analysis of any persisted BLE state (connection logs, cached device info); verify migration not needed or plan migration script

## Sources

### Primary (HIGH confidence)

**Codebase Analysis:**
- KableBleRepository.kt (2,886 lines) — direct analysis of monolithic structure, state management, BLE interaction points
- Issue #222 (bodyweight exercise BLE stop corruption) — root cause traced to operation interleaving, fixed via serialization queue
- Issue #210 (rep counter timing sensitivity) — exhaustive tests required, timing-sensitive state management documented
- Issue #176 (handle state baseline tracking) — exact state machine requirements, baseline lifecycle specified
- FakeBleRepository.kt (existing test infrastructure) — proven pattern for hand-written fakes in KMP
- libs.versions.toml (version catalog) — kotlinx-coroutines-test 1.9.0, Turbine 1.2.1, Koin 4.1.1 confirmed

**Official Documentation:**
- [kotlinx-coroutines-test Official API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/) — runTest, advanceTimeBy, TestDispatcher patterns
- [Testing Kotlin Coroutines (Android Developers)](https://developer.android.com/kotlin/coroutines/test) — virtual time testing, suspend function testing best practices
- [Turbine GitHub Repository](https://github.com/cashapp/turbine) — Flow testing patterns, awaitItem() API
- [Kable GitHub Repository](https://github.com/JuulLabs/kable) — KMP BLE library coroutine-based API, platform abstraction architecture
- [Kable SensorTag Sample](https://github.com/JuulLabs/sensortag) — Real-world KMP BLE app pattern, characteristic discovery sequencing

### Secondary (MEDIUM confidence)

**KMP Testing Best Practices:**
- [Kotlin Multiplatform Testing in 2025 (KMPship)](https://www.kmpship.app/blog/kotlin-multiplatform-testing-guide-2025) — Fake-first approach validated, MockK limitations on Kotlin/Native confirmed
- [Testing Best Practices (akjaw.com)](https://akjaw.com/testing-on-kotlin-multiplatform-and-strategy-to-speed-up-development/) — Hand-written fakes vs KSP code generation trade-offs
- [Mokkery Documentation](https://mokkery.dev/) — KSP-based mocking for KMP; Kotlin 2.0 source separation issues noted
- [Mockative GitHub](https://github.com/mockative/mockative) — Alternative KSP mocking; NullPointerException issues with Kotlin 2.0 confirmed

**BLE Platform-Specific Patterns:**
- [Race condition between 'onCharacteristicWrite()' and 'onCharacteristicChanged()' operations on single characteristic - RxAndroidBle Issue #694](https://github.com/dariuszseweryn/RxAndroidBle/issues/694) — Android BLE write buffer re-use pattern confirmed
- [Making Android BLE Work — Part 3 - Martijn van Welie](https://medium.com/@martijn.van.welie/making-android-ble-work-part-3-117d3a8aee23) — Nordic UART service bidirectional characteristic patterns
- [4 Tips To Make Android BLE Actually Work - Punch Through](https://punchthrough.com/android-ble-development-tips/) — Operation serialization best practices
- [Navigating iOS Bluetooth: Lessons on Background Processing, Pitfalls, and Personal Reflections - Medium](https://medium.com/@sanjaynelagadde1992/navigating-ios-bluetooth-lessons-on-background-processing-pitfalls-and-personal-reflections-5e5379a26e02) — iOS CoreBluetooth async callback sequencing, watchdog timeout patterns

**Kotlin Coroutines and Concurrency:**
- [Solving problem of race condition in Kotlin coroutines - Medium](https://medium.com/@1mailanton/solving-problem-of-race-condition-in-kotlin-coroutines-958abfceab37) — Mutex serialization pattern for preventing concurrent access
- [How to Prevent Race Conditions in Coroutines - Dave Leeds on Kotlin](https://typealias.com/articles/prevent-race-conditions-in-coroutines/) — StateFlow atomicity patterns
- [Best practices for coroutines in Android - Android Developers](https://developer.android.com/kotlin/coroutines/coroutines-best-practices) — Job lifecycle management, scope sharing

### Tertiary (LOW confidence)

**Emerging BLE Patterns:**
- [BLE App Development in 2026: Trends, Opportunities & Best Practices - BLE App Developers](https://blogs.bleappdevelopers.com/ble-app-development-in-2026-trends-opportunities-best-practices/) — General BLE trends (not specific to refactoring)
- [State Machine Mutation-based Testing Framework for Wireless Communication Protocols - arXiv](https://arxiv.org/html/2409.02905v4) — Academic state machine testing (not production-proven)

---
*Research completed: 2026-02-15*
*Ready for roadmap: yes*
