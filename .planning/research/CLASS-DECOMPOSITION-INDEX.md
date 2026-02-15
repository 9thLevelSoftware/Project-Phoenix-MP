# Class Decomposition Research Index

## Quick Start

You're refactoring KableBleRepository (2,886 lines, 10 subsystems) into 8 focused modules while preserving the `BleRepository` interface. This research package provides the patterns, verification strategies, and execution checklist.

**Time budget:** Read DECOMPOSITION_SUMMARY.md (5 min) + DECOMPOSITION_PATTERNS.md (15 min) = 20 min to understand the approach.

---

## Documents (In Reading Order)

### 1. DECOMPOSITION_SUMMARY.md (5 min read)
**What:** Executive summary of the entire approach
**For whom:** Team leads, architects, anyone deciding if this approach is viable

**Covers:**
- Three-pattern stack: Facade + Characterization Testing + Feature Flags
- 8-module decomposition with dependencies
- 10-11 week timeline (8 phases)
- Risk mitigation: where failures happen, how to prevent them
- Confidence assessment: HIGH for approach, MEDIUM for execution

**Key takeaway:** "Facade pattern + golden master testing = safe extraction"

### 2. DECOMPOSITION_PATTERNS.md (15 min deep read)
**What:** Detailed patterns, implementation strategies, code examples
**For whom:** Developers doing the extraction, architects validating the design

**Covers:**
- Pattern 1: Facade Layer (preserve interface, delegate internally)
- Pattern 2: Delegation with Gradual Extraction (atomic extraction steps)
- Pattern 3: Characterization Testing (golden master verification)
- Pattern 4: Module Boundaries (8-module architecture, responsibilities, dependencies)
- Feature Flags & Parallel Validation (shadow testing strategy)
- Testing pyramid (characterization → component → integration)
- Extraction roadmap: 8 phases, effort/risk/duration per phase
- 5 major pitfalls + prevention for each
- Coordinator Pattern (if circular dependencies discovered)

**Code examples:**
- Facade structure (300 lines of delegation vs. 2,886 God class)
- Module interfaces (IMetricsParser, IHandleStateEngine, ICommandProtocol)
- Characterization test structure (capture baseline, verify extracted)
- Feature flag implementation (shadow testing, rollout strategy)

**Key takeaway:** "Extract stateless utilities first, high-risk stateful systems last"

### 3. EXTRACTION_CHECKLIST.md (1 hour reference during execution)
**What:** Step-by-step checklist for EACH module extraction
**For whom:** Developers actively extracting modules

**Covers:**
- Pre-extraction (dependency analysis, safety audit, baseline capture)
- Module creation (interface, implementation, dependency injection)
- Testing (characterization, edge cases, thread safety)
- Facade integration (delegation, lifecycle, state coordination)
- Parallel validation (shadow testing, rollout plan)
- Code review checklist (for both author and reviewer)
- Verification before handoff (functional, non-functional, safety)
- Rollback procedure (if divergence found)
- Common mistakes + prevention

**Use:** Print this, check boxes as you extract each of 8 modules. When all boxes are checked, module is safe to deploy.

---

## Supporting Research (From Prior Work)

### ARCHITECTURE.md (existing)
Reference for KableBleRepository current architecture, state variables, subsystems. Use to understand what needs to be extracted.

### FEATURES.md (existing)
List of feature flags, testing infrastructure, decomposition-enabling features (approval tests, shadow testing harness).

### PITFALLS.md (existing)
Domain-specific pitfalls for BLE refactoring (handle state timing, EMA smoothing, thread safety).

---

## Quick Reference Tables

### 8-Module Decomposition

| Module | Responsibility | State | Effort | Risk | Duration |
|--------|---|---|---|---|---|
| RepEventParser | Parse rep notifications | None | Low | Low | 3 days |
| MetricsParser | Parse raw BLE → WorkoutMetric | Spike filter state | Low | Low | 1 week |
| CommandProtocol | Send commands, manage responses | Response flow | Medium | Medium | 1 week |
| HandleStateEngine | Handle position state machine | EMA smoothing, hysteresis | High | High | 2 weeks |
| DiagnosticsPoller | Heartbeat, firmware version | Poll job state | Medium | Medium | 1 week |
| HeuristicPoller | Phase statistics | Poll job state | Low | Low | 3 days |
| MonitorPoller | Metrics polling loop | Poll job state | High | High | 2 weeks |
| ConnectionManager | Device scan/connect/disconnect | Peripheral ref | High | High | 2 weeks |

### Extraction Dependency Graph

```
Level 0 (stateless utilities, no dependencies):
  - RepEventParser

Level 1 (utilities + parsing):
  - MetricsParser (spike filter state)
  - CommandProtocol

Level 2 (uses Level 1):
  - HandleStateEngine (uses MetricsParser for position data)
  - DiagnosticsPoller (uses CommandProtocol for heartbeat)
  - HeuristicPoller

Level 3 (high-risk, orchestrates all):
  - MonitorPoller (uses MetricsParser, HandleStateEngine, RepEventParser)
  - ConnectionManager (uses all others)

Extract in this order ↑ to prevent circular dependencies.
```

### Verification Gates (Per Module)

| Gate | Criterion | Pass = |
|------|-----------|--------|
| **Characterization** | Extracted module produces identical output to God class | 100% test pass, 0 divergence |
| **Shadow Testing** | New code runs in parallel, compares against old | 0 divergence on 1000+ device-hours |
| **Thread Safety** | No race conditions under concurrent access | Concurrent test passes |
| **Consumer** | No consumer code needs modification | BleRepository interface unchanged |
| **Performance** | No latency/memory/CPU regression | Within 5% of God class |
| **Rollback** | Can disable via feature flag instantly | Feature flag works, old code path resumes |

---

## Risk Matrix (For Phasing)

**High Risk (Needs Extensive Testing):**
- HandleStateEngine (timing-sensitive EMA smoothing, hysteresis)
- MonitorPoller (high-frequency emission, buffer management)
- ConnectionManager (lifecycle, error handling, reconnection)

**Medium Risk (Standard Testing):**
- CommandProtocol (response handshake timing)
- DiagnosticsPoller (heartbeat interval sensitivity)

**Low Risk (Straightforward Extraction):**
- RepEventParser (stateless parsing)
- MetricsParser (mostly stateless, spike filter)
- HeuristicPoller (straightforward polling)

**Phase Extraction Strategy:**
- Week 1: Extract low-risk (RepEventParser, MetricsParser)
- Week 2: Extract medium-risk (CommandProtocol, DiagnosticsPoller)
- Weeks 3-5: Extract high-risk with extensive testing (HandleStateEngine, MonitorPoller, ConnectionManager)
- Week 6: Integrate + cleanup

---

## Testing Toolkit

### 1. Golden Master (Characterization Tests)
**Tool:** Approval tests (ApprovalTests or snapshot testing)
**Purpose:** Capture baseline behavior before extraction; verify extracted modules produce identical output

**Setup:**
```kotlin
// Capture baseline (God class, before extraction)
val results = godClass.parseMonitorData(realBlePackets)
Approvals.verify(results)  // Generates .approved.txt

// Verify extracted module against baseline
val newResults = newModule.parseMonitorData(realBlePackets)
Approvals.verify(newResults)  // Compares to .approved.txt
```

### 2. Shadow Testing
**Purpose:** Run new code in parallel with old code, compare outputs, use old code if divergence

**Setup:**
```kotlin
if (enableShadowTesting && useNewModule) {
    val oldResult = oldCode(data)
    val newResult = newModule(data)
    if (oldResult != newResult) {
        log.warn("Divergence: $oldResult vs $newResult")
        emit(oldResult)  // Use proven old code
    } else {
        emit(newResult)  // Use new code if equivalent
    }
}
```

### 3. Concurrent Stress Testing
**Purpose:** Verify thread safety under concurrent access

**Setup:**
```kotlin
@Test
fun testConcurrentAccess() {
    val module = NewModule()
    val threads = (1..10).map { i ->
        thread {
            repeat(1000) { module.publicMethod(...) }
        }
    }
    threads.forEach { it.join() }
    // Verify no corruption, no crashes
}
```

### 4. Integration Testing
**Purpose:** Verify module works in real workflow (e.g., scan → connect → workout)

**Setup:**
```kotlin
@Test
fun testFullWorkoutLifecycle() {
    // 1. Scan for device
    val result = facade.startScanning()
    // 2. Connect to device
    val connected = facade.connect(device)
    // 3. Start workout
    facade.startWorkout(params)
    // 4. Verify metrics flow
    val metric = metricsFlow.take(1).firstOrNull()
    assert(metric != null)
    // 5. Stop workout
    facade.stopWorkout()
    // 6. Disconnect
    facade.disconnect()
}
```

---

## Common Questions

### Q: Do I need to understand the entire God class before extracting?
**A:** No. Understand only the subsystem you're extracting. The characterization tests will catch any misunderstandings.

### Q: What if the God class has bugs? Do I preserve them?
**A:** Yes. In characterization testing, you capture current behavior (even if buggy). After extraction is complete and verified, you can fix bugs in a separate PR. Do not refactor while extracting.

### Q: Can I refactor while extracting (rename variables, improve logic)?
**A:** No. Extract 1:1, then refactor in a separate PR. Mixing extraction + refactoring makes it impossible to verify equivalence.

### Q: How long does this take?
**A:** 10-11 weeks for all 8 modules + integration. Each module: 3-14 days depending on complexity. High-risk modules (HandleStateEngine, MonitorPoller) take longest due to testing.

### Q: What if I find circular dependencies?
**A:** Create a Coordinator module that both depend on, breaking the cycle. See DECOMPOSITION_PATTERNS.md Coordinator Pattern section.

### Q: Can I deploy partial extraction (3 of 8 modules done)?
**A:** Yes. Each module extraction is independent. If you've extracted RepEventParser, MetricsParser, CommandProtocol and they all pass gates, you can deploy them (behind feature flags). Other modules can be extracted in parallel.

### Q: How do I verify behavioral equivalence?
**A:** Characterization tests (golden master). Feed real BLE packets into both God class and extracted module, compare outputs. If identical, equivalent.

### Q: Can I remove the God class implementation once extraction is done?
**A:** Yes, in Phase 8. But keep the old code for ~1 month after 100% rollout (quick rollback if needed).

---

## Links to Tools & References

- **Approval Tests (Golden Master):** https://github.com/approvalTests/ApprovalTests.Java
- **Kotlin Delegation:** https://kotlinlang.org/docs/delegation.html
- **StateFlow & SharedFlow:** https://developer.android.com/kotlin/flow/stateflow-and-sharedflow
- **Strangler Pattern:** https://martinfowler.com/bliki/StranglerFigApplication.html
- **Characterization Tests:** https://understandlegacycode.com/blog/characterization-tests-or-approval-tests/

---

## Success Criteria (End of Phase 8)

- [ ] All 8 modules extracted and integrated
- [ ] BleRepository interface unchanged (all consumers work without modification)
- [ ] 100% characterization tests pass
- [ ] 0 divergence on shadow testing
- [ ] Performance within 5% of original God class
- [ ] Old code removed from God class
- [ ] Feature flags removed
- [ ] Deployed to production (gradually: 1% → 10% → 50% → 100%)
- [ ] Stable for 1 month (0 critical bugs, 0 rollbacks)

---

## Questions or Blockers?

If you hit a blocker during extraction:

1. **Check DECOMPOSITION_PATTERNS.md** — Does it address your issue?
2. **Check EXTRACTION_CHECKLIST.md** — Are you missing a verification step?
3. **Check PITFALLS.md** — Is this a known pattern failure?
4. **Ask:** Create an issue with:
   - Which module you're extracting
   - What divergence/error you're seeing
   - The characterization test that's failing
   - Link to comparison (old vs. new output)

---

*Research package for KableBleRepository decomposition refactoring*
*Phase 6 deliverable for project roadmapping*
*Confidence: HIGH for approach, MEDIUM-HIGH for execution*

Start with DECOMPOSITION_SUMMARY.md, then DECOMPOSITION_PATTERNS.md.
During extraction, use EXTRACTION_CHECKLIST.md as your guide.
