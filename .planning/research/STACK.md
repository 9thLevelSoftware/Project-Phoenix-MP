# Technology Stack: KMP BLE Testing & Mocking

**Project:** Project Phoenix MP (KMP BLE Decomposition Milestone)
**Focus:** Testing infrastructure for decomposed BLE modules
**Researched:** 2026-02-15
**Confidence:** HIGH

## Recommended Testing Stack

### Core Testing Framework
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| kotlinx-coroutines-test | 1.9.0 | Virtual time control + suspend testing | Already in shared module; provides runTest builder, virtual time advancement (critical for BLE timing), and TestDispatcher for coroutine isolation. Industry standard for Kotlin async testing. |
| kotlin-test | 2.0.21 | Cross-platform assertion library | Part of stdlib; works on all KMP targets; provides assertEquals, assertTrue, etc. without external dependencies. |
| app.cash.turbine:turbine | 1.2.1 | Flow/SharedFlow testing | Already in shared module; cleanly tests reactive streams without manual collection. Essential for testing BLE notification flows and state emissions. Prevents flaky tests. |
| junit | 4.13.2 | Test runner (Android) | Already in androidUnitTest; required for running tests in Gradle. Not needed for commonTest (Kotlin/Native uses native test runner). |

### Dependency Injection & Module Testing
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| io.insert-koin:koin-test | 4.1.1 | DI module verification + scope testing | Already in shared module; allows testing Koin module setup, injecting fakes into real modules (critical for testing BLE layers in isolation). No mocking framework needed—just swap real implementations for fakes. |

### Mocking Strategy (Fake Implementations, NOT Mocking Frameworks)

**Recommendation:** Do NOT add MockK, Mokkery, Mockative, or other mocking frameworks. **Use hand-crafted fake implementations instead.**

| Pattern | Technology | Purpose | Why |
|---------|-----------|---------|-----|
| Fake repositories | FakeBleRepository (existing) | Test BLE behavior without hardware | Already established in codebase. Provides controllable state, event simulation, and command tracking. Hand-written fakes are easier to debug, work on all KMP targets (no JVM-only reflection), and make tests more readable than mocked code. |
| Fake database | createTestDatabase() (existing) | In-memory SQLite for data layer tests | Already in place; SQLDelight provides native test support without mocking. |
| Test utilities | DWSMTestHarness, TestCoroutineRule | Reusable test infrastructure | Already in shared/src/commonTest/kotlin/testutil/. Provides coroutine scope management, database setup, and flow collection. |

**Why fakes over mocks for KMP:**
- MockK only supports JVM (blocks Kotlin/Native)
- Mockative/Mokkery/KMock use KSP code generation (breaks with Kotlin 2.0's source separation; generates NullPointerExceptions)
- Hand-written fakes work on all platforms without reflection
- FakeBleRepository pattern (already in codebase) is easier to debug and modify
- More readable test code (explicit state management vs. mock spy chains)

### Platform-Specific Testing
| Technology | Version | Purpose | Why |
|------------|---------|---------|-----|
| sqldelight.sqlite.driver | 2.0.2 | In-memory SQLite (Android unit tests) | Already in androidUnitTest; allows testing database layer without mocking |
| sqldelight.native.driver | 2.0.2 | Native SQLite (iOS tests) | Already in iosTest; Kable operations don't need mocking—fake Peripheral impl is better |

## Testing Coroutines + Flow (Patterns)

### Pattern 1: Testing Suspend Functions
```kotlin
@Test
fun `connect with timeout succeeds`() = runTest {
    val result = bleRepository.connect(device)
    assertTrue(result.isSuccess)
    // Virtual time—no real delays
}
```

### Pattern 2: Testing Flows (Turbine)
```kotlin
@Test
fun `metricsFlow emits WorkoutMetric`() = runTest {
    bleRepository.metricsFlow.test {
        fakePeripheral.emitMetric(testMetric)

        assertEquals(testMetric, awaitItem())
        cancelAndIgnoreRemainingEvents()
    }
}
```

### Pattern 3: Testing StateFlow
```kotlin
@Test
fun `connectionState reflects device lifecycle`() = runTest {
    bleRepository.connectionState.test {
        assertEquals(ConnectionState.Disconnected, awaitItem())

        bleRepository.connect(device)
        assertEquals(ConnectionState.Connecting, awaitItem())
        assertEquals(ConnectionState.Connected("Vee_1"), awaitItem())

        cancelAndIgnoreRemainingEvents()
    }
}
```

### Pattern 4: Virtual Time Advancement
```kotlin
@Test
fun `metric polling respects 100ms interval`() = runTest {
    metricPollingEngine.startPolling(interval = 100.milliseconds).launchIn(this)

    advanceTimeBy(350.milliseconds)
    runCurrent()

    assertEquals(3, polledValues.size)
}
```

### Pattern 5: Testing Koin DI Module
```kotlin
@Test
fun `BLE module correctly wires dependencies`() = runTest {
    val testModule = module {
        single<BleRepository> { FakeBleRepository() }
        single { ProtocolParser() }
        single { MetricPollingEngine(get()) }
    }

    loadModules(testModule)
    val engine = get<MetricPollingEngine>()

    assertTrue(engine.bleRepository is FakeBleRepository)
}
```

## Kable-Specific Considerations

### What You CAN Test Without Hardware
| Feature | Testing Approach |
|---------|-----------------|
| Protocol parsing | Parse static byte arrays |
| State management | Fake state transitions |
| Flow handling | Turbine + FakeBleRepository |
| Timeout logic | advanceTimeBy() virtual time |
| Coroutine cancellation | runTest scope cancellation |

### What You CANNOT Test Without Hardware
| Feature | Notes |
|---------|-------|
| Actual BLE scanning | Cannot mock Kable Scanner without reimplementing Kable |
| Physical connection | Cannot fake AndroidPeripheral/NativePeripheral casts |
| MTU negotiation | Platform-specific BLE negotiation |
| Hardware notifications | Actual device notify characteristics |

**Recommendation:** Keep unit tests (fake-based) fast; keep integration tests (hardware-based) separate.

## Confidence Assessment

| Area | Confidence | Rationale |
|------|------------|-----------|
| Coroutine testing | HIGH | kotlinx-coroutines-test is official Kotlin stdlib; all patterns verified |
| Flow/Turbine testing | HIGH | Turbine 1.2.1 widely used; already in project |
| Fake vs. Mock decision | HIGH | Explicit KMP limitations with mocking; fake pattern proven in existing codebase |
| Kable-specific patterns | MEDIUM | Kable does not document testing patterns; inferred from SDK design |
| Virtual time patterns | HIGH | Official documentation; core to coroutines test lib |

## Integration with Existing Test Infrastructure

The project already has:
- FakeBleRepository (pattern to extend)
- createTestDatabase() (pattern to follow)
- Turbine + kotlinx-coroutines-test (no additions needed)
- Koin test module (already wired)

**Recommendation:** Do not add new dependencies. Extend existing fake patterns to the 8 new BLE modules.

## Sources

- [kotlinx-coroutines-test Official API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/)
- [Testing Kotlin Coroutines (Android Developers)](https://developer.android.com/kotlin/coroutines/test)
- [Turbine GitHub Repository](https://github.com/cashapp/turbine)
- [Kotlin Multiplatform Testing in 2025 (KMPship)](https://www.kmpship.app/blog/kotlin-multiplatform-testing-guide-2025)
- [Mokkery Documentation](https://mokkery.dev/)
- [Mockative GitHub](https://github.com/mockative/mockative)
- [Testing Best Practices (akjaw.com)](https://akjaw.com/testing-on-kotlin-multiplatform-and-strategy-to-speed-up-development/)
- [Kable GitHub Repository](https://github.com/JuulLabs/kable)
- [Kable SensorTag Sample](https://github.com/JuulLabs/sensortag)
