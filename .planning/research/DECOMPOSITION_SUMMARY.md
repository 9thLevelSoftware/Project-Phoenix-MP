# Research Summary: Safe Class Decomposition with Interface Preservation

**Project:** Project Phoenix MP — KableBleRepository God Class Refactoring
**Domain:** Class decomposition, incremental extraction, behavioral equivalence
**Researched:** 2026-02-15
**Overall Confidence:** HIGH

## Executive Summary

Decomposing a 2,886-line God class (KableBleRepository) into 8 focused modules while preserving the `BleRepository` interface is achievable through a three-pattern stack:

1. **Facade Pattern** — Thin delegation layer that maintains the interface contract while internally delegating to extracted modules
2. **Characterization Testing** — Capture baseline behavior before extraction; verify extracted modules produce identical outputs
3. **Strangler Fig / Feature Flags** — Run old and new code in parallel, gradually roll out to real devices with instant rollback capability

**Key finding:** The main risk is NOT the refactoring itself, but ensuring behavioral equivalence for timing-sensitive features (handle state machine, EMA smoothing, deload detection). This is solved by golden master testing + parallel validation before switching.

**Recommended timeline:** 10-11 weeks of structured, atomic extraction phases (8 phases total). Each phase is independently deployable and testable.

## Key Findings

### Stack

- **Facade Pattern:** Preserve `BleRepository` interface, delegate internally to 8 specialized modules
- **Characterization Testing:** Use approval tests (golden master) to capture and verify behavior
- **Feature Flags:** Shadow-test new modules against old code on production data
- **Dependency Ordering:** Extract utilities first (RepEventParser, MetricsParser) → then consumers (HandleStateEngine, MonitorPoller) → finally orchestrators (ConnectionManager)

### Features

The refactoring doesn't add user-facing features, but enables:
- **Testability:** Each module is independently unit-testable (~10 tests per module)
- **Maintainability:** Single-responsibility modules vs. multi-purpose God class
- **Incremental Delivery:** Partial completion doesn't block other feature work
- **Safety:** Behavioral equivalence tests eliminate regression risk

### Architecture

```
BleRepository Interface (unchanged)
    ↓
KableBleRepository Facade (300 lines of delegation)
    ├─ ConnectionManager (scanning, connection lifecycle)
    ├─ MetricsParser (parse raw BLE → WorkoutMetric)
    ├─ RepEventParser (parse rep notifications)
    ├─ HandleStateEngine (handle position state machine)
    ├─ CommandProtocol (send commands, manage responses)
    ├─ DiagnosticsPoller (heartbeat, firmware version)
    ├─ HeuristicPoller (phase statistics)
    └─ MonitorPoller (continuous metrics emission)
```

All consumers (WorkoutViewModel, MainViewModel) continue using BleRepository without change.

### Pitfalls

1. **Extracting interdependent modules together** → Use dependency graph; extract bottom-up
2. **Losing timing-sensitive behaviors** → Use characterization tests on real device data
3. **Thread safety issues** → Document all synchronization in God class; apply to extracted modules
4. **Incomplete facade delegation** → Use IDE refactoring; complete one module extraction per PR
5. **StateFlow timing assumptions** → Test consumer code paths as integration tests

## Implications for Roadmap

### Recommended Phase Structure

**Phase 1-2 (Weeks 1-2): Low-Risk Extraction**
- Extract RepEventParser, MetricsParser (stateless utilities)
- No state management, simple functions → low risk
- Addresses: Foundation for parsing subsystem
- Avoids: Complex state or timing issues

**Phase 3-4 (Weeks 3-4): Medium-Risk Extraction**
- Extract CommandProtocol, DiagnosticsPoller
- Addresses: Command routing, keep-alive logic
- Avoids: Stateful metrics emission

**Phase 5-7 (Weeks 5-7): High-Risk Extraction (Parallel Testing)**
- Extract HandleStateEngine (handle position state machine) — 2 weeks
- Extract MonitorPoller (metrics polling loop) — 2 weeks
- Extract ConnectionManager (connection lifecycle) — 2 weeks
- Extensive characterization tests + shadow testing
- Real device validation before full rollout
- Addresses: Core workout logic, auto-start detection, timing-sensitive features
- Avoids: Rushing; must validate thoroughly

**Phase 8 (Days 22-24): Integration & Cleanup**
- Integrate all modules into facade
- Remove dead code from God class
- Final contract testing + consumer validation
- Addresses: Complete refactoring, prepare for production
- Avoids: Incomplete cleanup; old code still present

**Phase Ordering Rationale:**
- Utilities first (no dependencies) → enables testing of consumers
- Medium-risk next (depend on utilities) → builds foundation
- High-risk last (most complex state) → most test coverage available
- Integration last (all modules working) → smoothest final merge

### Research Flags for Phases

| Phase | Topic | Likely Needs Research | Why |
|-------|-------|----------------------|-----|
| 1-2 | Stateless parsing extraction | **NO** | Standard refactoring, well-understood patterns |
| 3-4 | CommandProtocol & diagnostics | **MAYBE** | Response protocol timing — verify with characterization tests |
| 5-7 | Handle state machine extraction | **YES** | Timing, EMA smoothing, hysteresis — critical for Just Lift auto-start |
| 5-7 | Monitor polling refactoring | **YES** | High-frequency metrics emission, buffer overflow handling |
| 8 | Final integration | **NO** | Straightforward delegation once modules are proven |

### Testing Strategy for Roadmap

1. **Before Phase 1:** Capture characterization test baseline for entire God class (50 real BLE packets, all code paths)
2. **Each Phase 1-7:** Write characterization tests for extracted module; verify against baseline
3. **Phase 5-7:** Enable feature flags for shadow testing on development devices; monitor for divergence
4. **Phase 8:** Run full integration tests; compare against baseline one final time

## Confidence Assessment

| Area | Confidence | Notes |
|------|-----------|-------|
| **Facade Pattern Feasibility** | HIGH | Well-proven pattern; multiple production examples |
| **Behavioral Equivalence Testing** | HIGH | Characterization tests are industry-standard for legacy refactoring |
| **Extraction Ordering** | HIGH | Dependency graph is clear; no hidden circular deps |
| **Thread Safety** | MEDIUM | KableBleRepository has mixed safety (Volatile, Mutex, suspend); must audit carefully |
| **Timing-Sensitive Features** | MEDIUM | Handle state machine and EMA smoothing are complex; need extensive testing |
| **Consumer Impact** | HIGH | No changes needed; Facade preserves interface perfectly |

## Gaps to Address

1. **Thread Safety Audit (Phase 1 preparation)**
   - Document all `@Volatile` fields and their invariants
   - Map all `synchronized(lock)` and `Mutex.withLock` usage
   - Verify extracted modules maintain same safety guarantees

2. **Characterization Test Data Collection (Phase 1)**
   - Capture 50+ real BLE packets from production devices
   - Include edge cases: invalid positions, timeouts, rapid repacked
   - Store in test resources as binary files

3. **Feature Flag Implementation (Phase 5 prerequisite)**
   - Decide on feature flag library (LaunchDarkly, Firebase Remote Config, or Koin feature flags)
   - Implement shadow testing harness for parallel validation
   - Define divergence thresholds (0.01% tolerance)

4. **Performance Validation (Phase 8)**
   - Measure CPU, memory, latency of extracted modules vs. God class
   - Target: no more than 5% regression on any metric
   - Run under sustained load (1-hour workout)

## Next Steps

### Immediate (Before Phase 1)
1. Read DECOMPOSITION_PATTERNS.md fully
2. Audit KableBleRepository for thread safety (map Volatile fields, locks)
3. Define 8 module interfaces (contracts)
4. Set up characterization test infrastructure

### Phase 1 Preparation
1. Collect real device BLE packet data
2. Create golden master baseline for all public methods
3. Set up approval test tooling
4. Create RepEventParser and MetricsParser modules (stub)

### Phase 1 Execution
1. Implement RepEventParser (move code from God class)
2. Implement MetricsParser (move code from God class)
3. Update facade to delegate to new modules
4. Verify characterization tests pass (100% equivalence)

### Phase 2+ (Follow DECOMPOSITION_PATTERNS.md Roadmap)

---

## Sources

- [How to refactor the God object class antipattern](https://www.theserverside.com/tip/How-to-refactor-the-God-object-antipattern)
- [Design Patterns For Refactoring: Façade](https://tommcfarlin.com/design-patterns-for-refactoring-facade/)
- [The Strangler Fig Pattern](https://softwarepatternslexicon.com/microservices/4/3/)
- [Characterization Tests for Legacy Code](https://learnagilepractices.substack.com/p/characterization-tests-for-legacy)
- [Kotlin Delegation Documentation](https://kotlinlang.org/docs/delegation.html)
- [StateFlow and SharedFlow | Android Developers](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow)

---

*Summary for KableBleRepository decomposition refactoring milestone*
*Focus: safe incremental extraction with interface preservation*
*Confidence: HIGH for approach; MEDIUM for execution (thread safety, timing)*
