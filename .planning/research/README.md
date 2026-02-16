# BLE Refactoring Research — Complete Findings

**Research Date:** 2026-02-15
**Status:** COMPLETE
**Confidence:** HIGH

## Files Generated

This research consists of 3 main documents analyzing BLE/networking layer refactoring pitfalls:

### 1. **BLE-REFACTORING-PITFALLS.md** (MAIN DELIVERABLE)
Complete analysis of 13 critical and moderate pitfalls specific to BLE layer decomposition.

**Content:**
- 8 Critical Pitfalls (cause regressions, need deep fixes)
  - Operation Interleaving (Issue #222)
  - Breaking Hot Path (Issue #210)
  - Connection State Races
  - Characteristic Discovery Timeout
  - Baseline Context Loss (Issue #176)
  - Metric Persistence Loss
  - Packet Parsing Latency
  - iOS CoreBluetooth Async Mismatch
- 5 Moderate Pitfalls (subsystem failures)
- Detection & recovery strategies
- Prevention strategies (measurement, hardware testing, characterization tests)

**For:** Phase planners, architects
**Use:** Before planning any extraction phase

### 2. **REFACTORING-SUMMARY.md** (EXECUTIVE SUMMARY)
High-level findings synthesized for roadmap planning.

**Content:**
- Executive summary (why BLE is different)
- Key findings from research (stack, features, pitfalls)
- Implications for roadmap (phase structure, testing, risk mitigation)
- Confidence assessment per area
- Gaps to address

**For:** Project leads, roadmap planners
**Use:** For milestone planning decisions

### 3. **REFACTORING-ARCHITECTURE.md** (SYSTEM DESIGN)
Detailed system design showing component boundaries and patterns.

**Content:**
- Current monolithic structure
- Target 8-module architecture
- Each component's responsibility, state, dependencies
- Data flow diagrams
- Patterns to follow (serialization, state machines, hot path)
- Anti-patterns to avoid

**For:** Developers implementing extraction
**Use:** During code implementation

## Key Findings Summary

### Most Critical Insight
**BLE refactoring is not standard code cleanup.** BLE is a pipeline with hidden temporal dependencies. Naive extraction along functional boundaries breaks implicit contracts and causes:

1. **Operation Interleaving** — Concurrent reads/writes corrupt packets (fault 16384)
2. **State Machine Races** — Connection state becomes non-atomic
3. **Hot Path Latency** — Monitor polling adds overhead, drops frames
4. **Data Loss** — Metrics persistence and collection diverge
5. **Baseline Loss** — Handle state detector loses position context
6. **Platform Divergence** — Android ≠ iOS async patterns

### Lessons from This Codebase
- **Issue #222 (BLE serialization)** → Solution: BleOperationQueue with Mutex
- **Issue #210 (rep counting)** → Solution: Synchronous hot path, explicit state ownership
- **Issue #176 (handle baseline)** → Solution: Explicit lifecycle, reset on workout start

### Testing Strategy
**No unit tests are sufficient.** Must have:
1. **Characterization tests** — Capture monolith behavior, verify refactored matches
2. **Hardware tests** — Real device, real polling rates
3. **Latency measurement** — Establish budget (<5ms), measure before/after
4. **iOS device testing** — Simulator insufficient for BLE

## Phase Structure Recommendation

| Phase | Work | Risk | Duration | Gate |
|-------|------|------|----------|------|
| **Phase 1** | BleOperationQueue + Connection | CRITICAL | 2-3w | Serialization works, iOS connects |
| **Phase 2** | Parser + Processor + Polling | HIGHEST | 2-3w | Latency <5ms, rep counting works |
| **Phase 3** | Handle detector + State machines | MEDIUM-HIGH | 1-2w | State matches monolith behavior |
| **Phase 4** | Cleanup & final refactoring | LOW | 1w | No regressions |

**Total estimate:** 7-9 weeks (23 dev days, 10 QA days, 6 risk days)

## Critical Success Factors

1. **Serialization first** — BleOperationQueue MUST exist before anything else
2. **Hardware testing mandatory** — Every phase needs real device validation
3. **Latency budgets explicit** — Measure before/after, establish acceptable regression
4. **Rollback ready** — Can deploy stable version within 1 hour if issues found
5. **Monitoring in production** — Track connection success, metrics quality, BLE faults

## Where to Use This Research

### For Roadmap Planning
- Read **REFACTORING-SUMMARY.md** section "Implications for Roadmap"
- Use **Phase Structure Recommendation** above
- Plan with **7-9 week estimate** minimum

### For Architects
- Read **REFACTORING-ARCHITECTURE.md** for system design
- Understand component boundaries
- Learn patterns to follow and anti-patterns to avoid

### For Developers Implementing
- Read **BLE-REFACTORING-PITFALLS.md** section on your phase
- Check "Detection" and "Prevention" for your component
- Refer to **REFACTORING-ARCHITECTURE.md** for interfaces and patterns

### For QA/Testing
- Read **REFACTORING-SUMMARY.md** section "Testing Strategy"
- Establish characterization test baselines before Phase 1
- Run hardware tests after every phase
- Watch for rollback triggers (latency regression, fault codes, rep counting breaks)

## Open Questions

1. **iOS Device Access** — Can we guarantee iOS device for testing all phases?
2. **Hardware Access** — How often can we test on real Vitruvian machines?
3. **Production Monitoring** — What metrics should be tracked for early warning?
4. **Rollback Authority** — Who decides to rollback, what's the threshold?
5. **User Communication** — How do we communicate rollbacks to beta users?

## Bottom Line

**This refactoring is achievable, but only with:**
- Explicit serialization guarantees
- Strict hardware testing discipline
- Latency measurement at every hot path
- Fast rollback capability

**Do not rush phases. Measure everything. Test on real hardware. Rollback fast.**

---

Generated by Phase 6 Research (GSD)
