# Class Extraction Checklist: Safe, Incremental Decomposition

**Use this checklist for EACH module extraction phase.**

---

## Pre-Extraction (Do Before Starting Phase)

### Dependency Analysis
- [ ] Create dependency graph for target module (what it calls, what calls it)
- [ ] Verify NO circular dependencies with other extracted modules
- [ ] If circular deps found: create Coordinator pattern to mediate
- [ ] Document all state variables used by this module
- [ ] List all suspend/blocking operations (for thread safety audit)

### Safety Audit
- [ ] Identify all `@Volatile` fields touched by target module
- [ ] Identify all `synchronized(lock)` blocks in target methods
- [ ] Identify all `Mutex.withLock` usage
- [ ] Document thread safety invariants (what must be guarded, why)
- [ ] Check for suspend calls inside locks (UNSAFE pattern — should be async, not locking)

### Characterization Test Baseline
- [ ] Collect real BLE packet data that exercises this module
  - Normal case: typical position/metric sequences
  - Edge cases: truncated packets, extreme values, rapid changes
  - Error cases: invalid data, timeout conditions
- [ ] Implement golden master test for all public methods
- [ ] Verify baseline captures 100% of public methods at least once
- [ ] Store baseline in `src/.../resources/characterization/` as `.approved.txt` or binary

### Consumer Impact Analysis
- [ ] Search codebase for all callers of target module's public methods
- [ ] List every consumer (ViewModel, other repository, service)
- [ ] Verify none have direct state access (all go through facade)
- [ ] Identify any consumers that expect specific timing (e.g., `.first()` blocking on StateFlow)

---

## Module Creation

### Code Organization
- [ ] Create `src/commonMain/kotlin/com/devil/phoenixproject/data/[ModuleName].kt`
- [ ] Create `src/.../Test/kotlin/com/devil/phoenixproject/data/[ModuleName]Test.kt`
- [ ] Add module to git (no actual code yet, just shell)

### Interface Definition
- [ ] Define public interface (or keep as class if simple)
  ```kotlin
  // Example: public interface, or just public class
  interface IMetricsParser {
      fun parseMonitorData(data: ByteArray): WorkoutMetric?
  }
  ```
- [ ] Document all public methods (contracts, return values, side effects)
- [ ] List all state variables the module owns (if any)
- [ ] Specify thread safety guarantees (thread-safe? needs external lock?)

### Implementation (Copy-Paste from God Class)
- [ ] Extract target method(s) to new module
- [ ] DO NOT refactor yet; preserve byte-for-byte equivalence
- [ ] Move all helper methods and constants the target depends on
- [ ] Move all `@Volatile` fields and thread safety guards
- [ ] Move all Flow/StateFlow initialization if this module owns state
- [ ] Update package imports (copy will break them)
- [ ] Do NOT rename anything (preserve exact names)

### Dependency Injection
- [ ] Add logger: `private val log = Logger.withTag("ModuleName")`
- [ ] Accept external dependencies in constructor (if any)
- [ ] Mark immutable fields as `val`, mutable as `private var`
- [ ] Document constructor contract (required vs. optional params)

---

## Testing

### Characterization Tests
- [ ] Create characterization test: all public methods against baseline
  ```kotlin
  class MetricsParserCharacterizationTest {
      private val module = MetricsParser()

      @Test
      fun verifyParsingEquivalence() {
          val testPackets = loadCharacterizationData()
          val expectedResults = loadApprovedResults()

          for ((packet, expected) in testPackets.zip(expectedResults)) {
              val actual = module.parseMonitorData(packet)
              assertEquals(expected, actual, "Divergence on packet: $packet")
          }
      }
  }
  ```
- [ ] Run tests → all pass against baseline
- [ ] If any test fails, investigate why (bug in extraction, missing dependency, etc.)
- [ ] Fix and re-run until 100% pass

### Edge Case Tests
- [ ] Test null inputs
- [ ] Test empty/truncated data
- [ ] Test boundary values (0, MAX_VALUE, MIN_VALUE)
- [ ] Test rapid state changes (if applicable)
- [ ] Test timeout/error conditions (if applicable)

### Thread Safety Tests
- [ ] If module has mutable state:
  - Create concurrent test: 10 threads accessing simultaneously
  - Verify no data corruption, no missed updates
  - Use `synchronized(lock)` or `Mutex` as appropriate
- [ ] If module has `@Volatile` fields:
  - Verify visibility guarantees with happens-before tests
  - (Most Java/Kotlin frameworks make this easy; mainly verify usage)

---

## Integration with Facade

### Facade Delegation
- [ ] Add module instance to facade:
  ```kotlin
  class KableBleRepository : BleRepository {
      private val newModule = ModuleName()
  }
  ```
- [ ] Add delegation method (or property):
  ```kotlin
  private fun targetMethod(param: Type): ReturnType {
      return newModule.targetMethod(param)
  }
  ```
- [ ] If module has StateFlow, expose through facade:
  ```kotlin
  override val stateProperty: StateFlow<Type>
      get() = newModule.stateProperty
  ```
- [ ] Update ALL internal calls to use facade method
- [ ] Use IDE refactoring: Rename old method → force update all usages

### Lifecycle Management
- [ ] Initialize module in facade's init block (if it has setup)
- [ ] Add any cleanup code to facade's cleanup path
- [ ] Verify module doesn't start background jobs until explicitly called
- [ ] Map any coroutine scopes the module needs to facade's scope

### State Coordination
- [ ] If multiple modules share state (e.g., MonitorPoller needs HandleStateEngine state):
  - Verify facade coordinates them (doesn't let them call each other directly)
  - Consider Coordinator pattern if complex

---

## Parallel Validation (Feature Flags)

### Shadow Testing Setup
- [ ] Add feature flag for this module:
  ```kotlin
  var useNewMetricsParser = false  // or from config
  ```
- [ ] Implement shadow testing:
  ```kotlin
  private fun processMetrics(data: ByteArray) {
      if (enableParallelValidation && useNewMetricsParser) {
          val oldResult = oldParseMonitorData(data)
          val newResult = newModule.parseMonitorData(data)
          if (oldResult != newResult) {
              log.warn("Divergence: $oldResult vs $newResult")
              // Use old result, but log the issue
          }
      }
  }
  ```
- [ ] Keep old implementation until feature flag is removed
- [ ] Metrics to monitor:
  - Divergence count/percentage
  - Latency (old vs new)
  - Error rates
  - Memory usage

### Rollout Plan
- [ ] Phase 1: Shadow testing only (new code runs, but old code is used)
  - Target: 0 divergences on real device data
  - Duration: 1 week or 1000 device-hours, whichever is longer
- [ ] Phase 2: Canary rollout (1% of users on new code)
  - Monitor for crashes, errors, divergences
  - If clean after 48 hours, increase to 10%
- [ ] Phase 3: Ramp to 100%
  - 10% → 50% → 100% (each step: 48 hours clean)
  - Keep feature flag for 1 month after 100% (quick rollback)
- [ ] Phase 4: Remove old code + flag (after 1 month stable)

---

## Code Review Checklist

### For Pull Request Author
- [ ] All tests pass locally
- [ ] No lint errors
- [ ] No new compiler warnings
- [ ] Module interface is clear (public vs. private)
- [ ] Thread safety verified (locks, Mutex, Volatile usage correct)
- [ ] No breaking changes to BleRepository interface
- [ ] Characterization tests document expected behavior

### For Code Reviewer
- [ ] Extracted code is byte-for-byte equivalent to original
  - Exception: extract into separate method only if identical logic
  - No refactoring, renaming, or logic changes yet
- [ ] All public methods documented (contract, exceptions, side effects)
- [ ] All dependencies are injected or local
- [ ] No direct mutation of shared state without locks
- [ ] Facade delegation is complete (all callers updated)
- [ ] Tests cover all public methods
- [ ] No hidden dependencies on execution order

---

## Verification Before Handoff

### Functional
- [ ] Characterization tests: 100% pass
- [ ] Edge case tests: 100% pass
- [ ] Thread safety tests: 100% pass (if applicable)
- [ ] Integration tests: module works in workflow
- [ ] Shadow testing: 0 divergence on real data (minimum 1 week or 1000 device-hours)

### Non-Functional
- [ ] Memory: No increase (measure with real device profiler)
- [ ] Latency: Within 5% of original (profile hot paths)
- [ ] CPU: No new hot spots (profile under load)
- [ ] Battery: No regression (run sustained test)

### Code Quality
- [ ] Lint score: Same or better than God class
- [ ] Test coverage: ≥90% for module methods
- [ ] Documentation: All public methods have KDoc comments
- [ ] No dead code or TODOs left behind

### Safety
- [ ] Feature flag is disabled by default
- [ ] Rollback is instant (feature flag toggle)
- [ ] No consumers were modified (facade preserves interface)
- [ ] All changes are atomic (single PR per module)

---

## Rollback Procedure (If Divergence Found)

1. **Immediate:**
   - [ ] Disable feature flag → old code path resumes
   - [ ] Notify team of divergence
   - [ ] Stop rollout (don't progress to next percentage)

2. **Investigation:**
   - [ ] Check shadow testing logs for divergence pattern
   - [ ] Isolate which inputs cause divergence
   - [ ] Compare old code vs new code side-by-side
   - [ ] Create minimal test case that reproduces divergence

3. **Fix:**
   - [ ] Update module implementation
   - [ ] Add test case to prevent regression
   - [ ] Re-run characterization tests → must pass 100%
   - [ ] Re-enable shadow testing for 1 week

4. **Resume Rollout:**
   - [ ] If clean after 1 week, resume canary rollout
   - [ ] Document what divergence was and how fixed

---

## Post-Extraction (When All 8 Modules Done)

- [ ] Remove all old implementations from God class
- [ ] Remove feature flags (all code paths now use new modules)
- [ ] Remove shadow testing harness
- [ ] Final integration test: full workout lifecycle
- [ ] Final performance test: sustained load (1 hour workout)
- [ ] Deploy to production with confidence

---

## Common Mistakes to Avoid

| Mistake | Impact | Prevention |
|---------|--------|-----------|
| Refactoring while extracting | Introduces new bugs | Extract 1:1, refactor later in separate PR |
| Forgetting to move helper methods | Module doesn't compile | Extract dependencies first, verify compilation |
| Not updating all callers | Some code uses old method | IDE refactoring: rename old method globally |
| Extracting interdependent modules | Circular deps, doesn't work | Extract bottom-up; map dependencies first |
| Skipping characterization tests | New code silently diverges | Baseline before extraction, verify after |
| Removing old code too early | Rollback impossible | Keep old code until feature flag is removed |
| Not testing concurrency | Race conditions in production | Create concurrent stress tests |
| Breaking consumer code | Consumers must recompile | Only extract, don't change BleRepository interface |

---

*Use this checklist for each of the 8 module extraction phases.*
*Print it, check boxes as you go.*
*When all boxes are checked, module is safe to deploy.*
