# Roadmap Implications: Class Decomposition Research

**For:** Milestone planning and roadmap Phase 7-14 structuring
**Researched:** 2026-02-15

---

## Key Finding: Facade Pattern Enables Parallel Execution

The KableBleRepository God class can be safely decomposed into 8 modules using a facade layer that preserves the BleRepository interface. This means:

1. Consumers (WorkoutViewModel, MainViewModel) need zero code changes
2. Extraction is incremental (one module at a time, independently deployable)
3. Behavioral equivalence is guaranteed through characterization testing
4. Feature flags enable safe rollout (shadow testing, gradual rollout, instant rollback)

**Implication for roadmap:** Class decomposition can proceed IN PARALLEL with other feature work. It doesn't block. It doesn't require changing consumers. New features can be added to individual modules without waiting for all 8 to be extracted.

---

## Recommended Phase Structure (8 Phases)

### Phase 1: Low-Risk Foundation (Week 1-2)
**Extract:** RepEventParser, MetricsParser
**What:** Stateless parsing modules, no complex state management
**Owner:** One developer
**Risk:** LOW
**Deliverable:** 2 extracted modules, characterization tests, golden master baseline

**Why this phase:**
- Utilities with no dependencies on other modules
- Easy to test (stateless parsing)
- Enables testing of downstream consumers (HandleStateEngine, MonitorPoller)
- Can be deployed immediately after Phase 1

**Blocks:** None (independent)
**Blocked by:** None

### Phase 2: Medium-Risk Foundation (Week 3-4)
**Extract:** CommandProtocol, DiagnosticsPoller
**What:** Command/response protocol, heartbeat/diagnostic polling
**Owner:** One developer (or shared with Phase 1 dev)
**Risk:** MEDIUM
**Deliverable:** 2 extracted modules, characterization tests, shadow testing enabled

**Why this phase:**
- Depends on constants and logging (available)
- Does NOT depend on MetricsParser, HandleStateEngine (can be extracted independently)
- Tests polling intervals, heartbeat timing, response handshake
- Moderate complexity but lower risk than state machines

**Blocks:** HeuristicPoller (depends on polling patterns)
**Blocked by:** Phase 1 (needs logging infrastructure)

### Phase 3: High-Risk State Machine (Week 5-6)
**Extract:** HandleStateEngine
**What:** Handle position state machine (WaitingForRest → Released → Grabbed → Moving)
**Owner:** One senior developer (complex logic)
**Risk:** HIGH
**Deliverable:** 1 extracted module, extensive characterization tests (real device data), shadow testing on 5+ devices

**Why this phase:**
- Most complex state machine in codebase
- Timing-sensitive (EMA smoothing, hysteresis delays, debouncing)
- Critical for Just Lift auto-start feature (if breaks, workout mode breaks)
- Must extract AFTER MetricsParser (depends on position data)
- Needs 2 full weeks for extensive testing

**Blocks:** MonitorPoller (depends on state machine output)
**Blocked by:** Phase 1 (needs MetricsParser)

**Testing depth:**
- Characterization: 50+ real device sessions (varied handle motion patterns)
- Stress: Rapid position changes, velocity spikes, edge cases
- Integration: Full workout lifecycle with auto-start enabled
- Shadow: Run on 5+ devices for 2 weeks, 0 divergence required

### Phase 4: High-Risk Polling (Week 7-8)
**Extract:** MonitorPoller
**What:** Continuous metrics polling loop, buffer overflow handling
**Owner:** One senior developer
**Risk:** HIGH
**Deliverable:** 1 extracted module, characterization tests, shadow testing

**Why this phase:**
- High-frequency emissions (10ms/sample) → needs buffer management
- Depends on MetricsParser (Phase 1), HandleStateEngine (Phase 3)
- Owns the emission flow to consumers
- Timing-sensitive (if polling slower, workout metrics lag)

**Blocks:** ConnectionManager final integration
**Blocked by:** Phase 1 + Phase 3

**Testing depth:**
- Characterization: 20+ workout sessions with various exercise types
- Stress: Rapid sampling, dropout handling, buffer overflow
- Integration: Full workout + metrics recording + real-time display
- Shadow: Run on live devices for 2 weeks, compare latency

### Phase 5: High-Risk Connection Management (Week 9-10)
**Extract:** ConnectionManager
**What:** BLE scanning, connection lifecycle, MTU negotiation, retry logic
**Owner:** One senior developer (complex error handling)
**Risk:** HIGH
**Deliverable:** 1 extracted module, characterization tests, integration tests

**Why this phase:**
- Depends on all other modules being extracted (coordinates them)
- Handles error cases, reconnection logic, retry strategies
- Must maintain very high reliability (if breaks, no workouts possible)
- Needs extensive error case testing

**Blocks:** Complete decomposition
**Blocked by:** All Phase 1-4

**Testing depth:**
- Characterization: Device discovery, successful connections, timeouts
- Error cases: BLE drops, MTU negotiation failures, rapid reconnection
- Integration: Full lifecycle from cold start to sustained workout
- Real device: Test on iOS + Android, different Vitruvian hardware

### Phase 6: Medium-Risk Secondary Polling (Week 11)
**Extract:** HeuristicPoller
**What:** Phase statistics polling (4Hz)
**Owner:** One developer (simpler polling logic)
**Risk:** LOW-MEDIUM
**Deliverable:** 1 extracted module, characterization tests

**Why this phase:**
- Depends on Phase 2 (polling pattern established)
- Lower risk (diagnostic polling, not critical for core function)
- Can be partially parallelized with Phase 3-5 if needed

**Blocks:** None (independent feature)
**Blocked by:** Phase 2

### Phase 7: Integration & Testing (Week 12)
**Task:** Integrate all 8 modules into facade, run full system tests
**Owner:** One developer (integration focus)
**Deliverable:** Integrated system, full regression test suite, feature flag cleanup

**Why this phase:**
- All modules extracted and tested individually
- Now verify they work together (no state conflicts, correct handoff)
- Run full workout lifecycle tests
- Test error scenarios (device disconnects during workout, etc.)

**Blocks:** Cleanup
**Blocked by:** Phase 1-6

### Phase 8: Cleanup & Deprecation (Week 12-13)
**Task:** Remove old code, remove feature flags, final verification
**Owner:** One developer
**Deliverable:** Clean codebase, production-ready decomposed architecture

**Why this phase:**
- Old code removal (no rollback needed after 1 month stable)
- Feature flag cleanup
- Final documentation
- Prepare for production rollout

---

## Parallel Execution Opportunities

**Phases that can run in parallel:**
- Phase 1 (foundational parsing) + Phase 2 (protocol) = 2 developers, 2 weeks
- Phase 3 (handle state) + Phase 4 (monitor polling) = 2 developers, 2 weeks (both depend on Phase 1, so can start together)
- Phase 5 (connection manager) = must wait for others (depends on all)

**Realistic timeline with 2 developers:**
- Weeks 1-2: Phases 1+2 (in parallel)
- Weeks 3-4: Phases 3+4 (in parallel, after Phase 1 complete)
- Week 5: Phase 5 (waiting for Phase 3+4)
- Week 6: Phase 6 (while Phase 5 ongoing, independent)
- Weeks 7-8: Phases 7+8 (integration + cleanup)

**Total: 8 weeks with 2 developers** (vs. 12 weeks with 1 developer)

---

## Feature Flag & Rollout Strategy

### Feature Flag Per Module
```kotlin
// In Koin DI or @Provides
val featureFlags = mapOf(
    "use_new_metrics_parser" to false,      // Phase 1
    "use_new_rep_event_parser" to false,    // Phase 1
    "use_new_command_protocol" to false,    // Phase 2
    "use_new_diagnostics_poller" to false,  // Phase 2
    "use_new_handle_state_engine" to false, // Phase 3
    "use_new_monitor_poller" to false,      // Phase 4
    "use_new_heuristic_poller" to false,    // Phase 6
    "use_new_connection_manager" to false,  // Phase 5
)
```

### Rollout Strategy
1. **Shadow Testing Phase (Days 0-7):** Feature flag disabled, new code runs in parallel, divergence logged
   - Target: 0 divergence on 1000+ device-hours
2. **Canary Phase (Days 8-14):** 1% of users on new code
   - Monitor: crash rate, error rate, metrics divergence
3. **Ramp Phase (Days 15-28):** 1% → 10% → 50% → 100%
   - Each step: 48 hours clean before advancing
4. **Stabilization Phase (Days 29-60):** 100% on new code, feature flag still active for rollback
5. **Cleanup Phase (Day 60+):** Remove feature flag, remove old code

**Total rollout time: 60 days per module**
**For 8 modules staggered: Can complete first 3-4 modules within project timeline**

---

## Roadmap Phase Structuring

### Option A: Full Decomposition First (Recommended for Long-term)
1. **Milestone: Architectural Cleanup (Phases 1-8)** — 8-12 weeks
   - All 8 modules extracted and integrated
   - Full regression testing
   - Production rollout (60 days staggered)
2. Downstream: Future phases can now use individual modules directly

**Pros:**
- Clean foundation for all future features
- Parallelizable (2 developers)
- Each module can be deployed independently
- Enables future optimization of specific modules

**Cons:**
- Blocks other feature work during extraction (if only 1 developer available)
- Requires thorough testing discipline

### Option B: Phased Decomposition (Lower Risk, Slower)
1. **Phase 1: Foundation (Weeks 1-2)** — Extract MetricsParser, RepEventParser
   - Light testing, quick validation
   - Deploy to production (low risk, stateless)
2. **Other feature work:** Meanwhile, develop other features using God class
3. **Phase 2: Medium-risk (Weeks 5-6)** — Extract CommandProtocol, DiagnosticsPoller
   - Parallel with feature work
   - Deploy when ready
4. **Phase 3+:** Extract remaining modules as time allows
5. **Deadline:** Complete all extraction by end of Q1 2026

**Pros:**
- Doesn't block other feature work
- Can adjust based on discovered complexity
- Lower risk (one mistake doesn't block everything)

**Cons:**
- Takes longer (stretched over 12+ weeks vs. 8-12 focused)
- Coordination overhead (people context-switch)
- Feature interactions with partially-decomposed code

---

## Recommendation: Option A with 2 Developers

**Justification:**
1. **Parallelizable:** Low-, medium-, high-risk phases can run in parallel (Phases 1+2, then 3+4)
2. **High Value:** Once complete, enables future optimization and feature work
3. **Time-bound:** 8-12 weeks is acceptable for architectural work
4. **Risk Mitigation:** Characterization testing + shadow testing reduce bug risk
5. **Deployable Increments:** Each phase can be independently tested and deployed

**Timeline:**
- **Weeks 1-2:** Phases 1+2 (2 developers, ~100 lines code + 200 lines tests each)
- **Weeks 3-4:** Phases 3+4 (2 developers, high-risk but thoroughly tested)
- **Week 5:** Phase 5 (1 developer, critical path)
- **Week 6:** Phase 6 parallel with Phase 5 (1 developer, independent)
- **Weeks 7-8:** Phases 7+8 (integration, cleanup, 1 developer)

**Staffing:**
- **Senior developer:** Leads Phases 3, 4, 5 (high-risk, complex logic)
- **Mid-level developer:** Handles Phases 1, 2, 6 (lower complexity)
- **QA/Testing:** Runs characterization tests, shadow testing, integration tests

**Blockage risk:** LOW (decomposition doesn't block consumer code)
**Value realization:** HIGH (cleaner codebase enables future optimization)

---

## What This Enables in Future Phases

Once KableBleRepository is decomposed:

1. **Module-specific Optimization** (Phase 10?)
   - Optimize HandleStateEngine separately (state machine complexity)
   - Optimize MonitorPoller separately (latency, buffer management)
   - No need to refactor entire God class

2. **Easier Testing** (Phases 9+)
   - Unit test individual modules in isolation
   - Inject mock modules for consumer tests
   - Faster test execution (no need to initialize entire BLE stack)

3. **Parallel Feature Development** (Phases 9+)
   - Team A: New feature in MetricsParser (e.g., ROM violation detection)
   - Team B: New feature in HandleStateEngine (e.g., new state detection)
   - No conflicts (separate modules)

4. **iOS Parity** (Phase XX)
   - Easier to port modules individually to native iOS
   - Each module has clear interface
   - Easier to verify behavior equivalence between iOS/Android

5. **Legacy Codebase Migration** (Phase YY)
   - Once modular, easier to replace entire modules if needed
   - Example: Replace Kable with different BLE library (swap ConnectionManager only)

---

## Risks & Mitigation

| Risk | Impact | Mitigation |
|------|--------|-----------|
| **Characterization test baseline is incomplete** | Extracted modules diverge silently | Capture real device data (50+ sessions), test all public methods |
| **High-risk modules break during extraction** | Causes regression in core features | 2 weeks testing per high-risk module, shadow testing on 5+ devices |
| **Circular dependencies in modules** | Extraction blocked, must redesign | Map dependencies before extraction, use Coordinator pattern |
| **Thread safety bugs introduced** | Race conditions in production | Concurrent stress tests, code review for locks/Volatile usage |
| **Feature flags incomplete, can't rollback** | Can't disable new code if divergence found | Test feature flag mechanism in Phase 1, automated flag toggle tests |
| **Timing-sensitive features (EMA, hysteresis) break** | Just Lift auto-start fails | Extensive characterization tests for HandleStateEngine, 2-week validation period |
| **Consumers have undocumented timing assumptions** | Latency-sensitive code breaks | Review all consumer code paths in Phase 7 integration |

---

## Success Metrics (Phase 8 Completion)

| Metric | Target | How Measured |
|--------|--------|--------------|
| **Code coverage** | 90%+ per module | Code coverage tools (Jacoco) |
| **Characterization tests** | 100% pass | All golden master tests pass |
| **Shadow testing divergence** | 0 incidents | Feature flag logging + monitoring |
| **Performance** | Within 5% of baseline | Latency profiling, memory profiling |
| **Integration tests** | 100% pass | Full workout lifecycle tests |
| **Production stability** | 0 critical bugs in 30 days | Bug tracking + monitoring |
| **Consumer readiness** | 0 compile errors | All consumers work unchanged |

---

## Stakeholder Communication

### For Product/Leadership
"We're investing 8-12 weeks in architectural cleanup that enables 2-4x faster feature development and easier testing. No new user features this period, but solid foundation for Q2-Q3 work. Zero impact on consumers (they don't see anything change)."

### For QA
"Testing this refactoring is critical. We need you to:
1. Capture real device baselines (50+ workout sessions)
2. Run characterization tests for each module
3. Execute stress tests (concurrent access, rapid state changes)
4. Shadow test on real devices (compare old vs. new outputs)
5. Validate integrated system before production rollout"

### For Developers
"You'll extract 1-2 modules in 1-2 weeks. Use the EXTRACTION_CHECKLIST. Verify characterization tests 100% pass before you call it done. Feature flags let you deploy safely (shadow test first, then gradual rollout)."

---

## Conclusion

**Decomposing KableBleRepository is achievable, safe, and high-value.** The facade pattern + characterization testing + feature flags eliminate the main risks. Parallel execution with 2 developers can complete it in 8 weeks.

**Recommendation: Include this as dedicated Phase 7-8 work in Q1 2026 roadmap, with subsequent phases benefiting from modular architecture.**

---

*For Milestone planning and roadmap structuring*
*Research phase complete, ready for Phase 7 execution planning*
