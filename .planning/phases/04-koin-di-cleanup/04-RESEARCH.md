# Phase 4: Koin DI Cleanup - Research

**Researched:** 2026-02-13
**Domain:** Koin Dependency Injection / Kotlin Multiplatform Module Organization
**Confidence:** HIGH

## Summary

The project uses Koin 4.1.1 with a single `commonModule` containing 28 bindings spanning database, repositories, sync, auth, use cases, and viewmodels. The phase goal is to split this monolith into feature-scoped modules using Koin's `includes()` API (available since Koin 3.2) and verify the result with `Module.verify()`.

The split is straightforward because the existing bindings already have clear logical groupings marked by comments in the code. The `includes()` API handles deduplication automatically -- even if a child module is included via multiple paths, Koin flattens the graph. The `verify()` API is JVM-only, so the verification test must live in `androidUnitTest` (not `commonTest`). Platform-specific types from `platformModule` (DriverFactory, Settings, BleRepository, CsvExporter, DataBackupManager, ConnectivityChecker) need to be listed as `extraTypes` in the verify call.

**Primary recommendation:** Split `commonModule` into 4 feature modules (dataModule, syncModule, domainModule, presentationModule) composed via a root `appModule` using `includes()`. Add a `verify()` test in `shared/src/androidUnitTest`. Keep `platformModule` as expect/actual unchanged.

## Standard Stack

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| io.insert-koin:koin-core | 4.1.1 | DI container | Already in use, KMP-compatible |
| io.insert-koin:koin-test | 4.1.1 | Module verification | Already in test dependencies |
| io.insert-koin:koin-test-junit4 | 4.1.1 | JUnit4 test runner integration | Already in androidUnitTest dependencies |
| io.insert-koin:koin-compose | 4.1.1 | Compose integration (koinInject) | Already in use |
| io.insert-koin:koin-compose-viewmodel | 4.1.1 | koinViewModel() | Already in use |

### Supporting
No new dependencies needed. Everything required is already in the project.

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| `verify()` | Koin Compiler Plugin (annotations) | Compile-time safety but requires full annotation migration -- overkill for this phase |
| `verify()` | `checkModules()` | Deprecated in Koin 4.0, replaced by `verify()` |
| Manual module split | Koin Annotations (@Module) | Would require annotation processing setup and is a larger migration |

## Architecture Patterns

### Current Module Structure (BEFORE)
```
di/
├── AppModule.kt          # commonModule (28 bindings, monolith)
├── KoinInit.kt           # initKoin() entry point
├── PlatformModule.android.kt  # expect/actual platformModule (7 bindings)
└── PlatformModule.ios.kt      # expect/actual platformModule (6 bindings)
```

### Target Module Structure (AFTER)
```
di/
├── DataModule.kt         # dataModule: database, repositories
├── SyncModule.kt         # syncModule: portal API, sync, auth
├── DomainModule.kt       # domainModule: use cases, migration
├── PresentationModule.kt # presentationModule: viewmodels
├── AppModule.kt          # appModule: includes() all feature modules
├── KoinInit.kt           # initKoin() -- change modules() call
├── PlatformModule.android.kt  # UNCHANGED
└── PlatformModule.ios.kt      # UNCHANGED
```

### Pattern 1: Module Composition via includes()
**What:** Split a large module into focused child modules, compose with a parent.
**When to use:** When a single module has 10+ bindings spanning multiple architectural layers.
**Example:**
```kotlin
// Source: https://insert-koin.io/docs/4.0/reference/koin-core/modules/
val dataModule = module {
    single { DatabaseFactory(get()).createDatabase() }
    single { ExerciseImporter(get()) }
    single<ExerciseRepository> { SqlDelightExerciseRepository(get(), get()) }
    // ... more repository bindings
}

val appModule = module {
    includes(dataModule, syncModule, domainModule, presentationModule)
}

// In KoinInit.kt:
startKoin {
    appDeclaration()
    modules(appModule, platformModule)
}
```

### Pattern 2: Module.verify() for DI Validation
**What:** Lightweight JVM-only test that validates all constructor dependencies resolve.
**When to use:** After any DI module restructuring.
**Example:**
```kotlin
// Source: https://insert-koin.io/docs/reference/koin-test/verify/
class KoinModuleVerifyTest {
    @Test
    fun verifyAllModules() {
        appModule.verify(
            extraTypes = listOf(
                // Platform types provided by platformModule (not visible to verify)
                DriverFactory::class,
                Settings::class,
                // Android Context types
                android.content.Context::class,
            )
        )
    }
}
```

### Anti-Patterns to Avoid
- **Circular includes:** Module A includes B which includes A. Koin deduplicates but the intent is confusing.
- **Over-splitting:** Don't create a module per binding. 5-10 bindings per module is the sweet spot.
- **Forgetting platformModule:** The verify() test checks `appModule` (common) but platform bindings are in `platformModule`. Platform-provided types must go in `extraTypes`.

## Proposed Binding Distribution

### dataModule (10 bindings)
```
Database:    DatabaseFactory, ExerciseImporter
Repos:       ExerciseRepository, WorkoutRepository, PersonalRecordRepository,
             GamificationRepository, UserProfileRepository,
             TrainingCycleRepository, CompletedSetRepository, ProgressionRepository
```

### syncModule (7 bindings)
```
Portal:      PortalTokenStorage, PortalApiClient
Sync:        SyncRepository, SyncManager, SyncTriggerManager
Auth:        AuthRepository, SubscriptionManager
```

### domainModule (5 bindings)
```
Preferences: PreferencesManager
Use Cases:   RepCounterFromMachine, ProgressionUseCase, ResolveRoutineWeightsUseCase, TemplateConverter
Migration:   MigrationManager
```

### presentationModule (6 bindings)
```
ViewModels:  MainViewModel (factory), ConnectionLogsViewModel (factory),
             CycleEditorViewModel (factory), GamificationViewModel (factory),
             ThemeViewModel (single), EulaViewModel (single),
             LinkAccountViewModel (factory)
```

Note: 7 bindings in presentationModule actually (LinkAccountViewModel included).

## Don't Hand-Roll

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Module verification | Custom reflection-based DI checker | `Module.verify()` | Koin's verify already inspects constructors and cross-checks bindings |
| Module deduplication | Manual tracking of included modules | `includes()` built-in dedup | Koin automatically flattens and deduplicates the module graph |
| Platform type handling in verify | Mocking platform classes | `extraTypes` parameter | verify() accepts a whitelist of types it should not try to resolve |

**Key insight:** The entire phase is a reorganization of existing code with zero behavioral changes. No new DI patterns, no new libraries, no new dependency relationships. The risk is low.

## Common Pitfalls

### Pitfall 1: verify() is JVM-Only
**What goes wrong:** Putting the verify test in `commonTest` causes compilation failure on non-JVM targets.
**Why it happens:** `Module.verify()` uses JVM reflection internally.
**How to avoid:** Place the test in `shared/src/androidUnitTest/` (which is JVM) not `commonTest`.
**Warning signs:** "Unresolved reference: verify" compilation error in commonTest.

### Pitfall 2: Missing extraTypes for Platform Bindings
**What goes wrong:** verify() throws `MissingKoinDefinitionException` for types provided by `platformModule`.
**Why it happens:** verify() only sees the module it's called on, not modules composed at `startKoin` time. `platformModule` is a separate module loaded alongside `appModule`.
**How to avoid:** List all platform-provided types in `extraTypes`:
- `DriverFactory::class` (from platformModule)
- `Settings::class` (from platformModule via russhwolf)
- `BleRepository::class` should NOT be needed since it's resolved by interface, but if platformModule provides it, it's already in the graph.
- Actually: since `appModule` includes the feature modules but NOT `platformModule`, verify needs `extraTypes` for anything platformModule provides that feature modules depend on.
**Warning signs:** `MissingKoinDefinitionException` mentioning DriverFactory, Settings, BleRepository, etc.

### Pitfall 3: Breaking Existing Test Overrides
**What goes wrong:** E2E tests (AppE2ETest) reference `commonModule` by name. If renamed to `appModule`, tests break.
**Why it happens:** Test files import and use `commonModule` directly: `modules(commonModule, platformModule, testModule)`.
**How to avoid:** Either keep a `commonModule` alias or update all test references. The E2E test at `androidApp/src/androidTest/.../AppE2ETest.kt` uses `commonModule` on line 77.
**Warning signs:** Compilation error "Unresolved reference: commonModule" in test files.

### Pitfall 4: Module Loading Order with includes()
**What goes wrong:** Bindings that depend on other bindings from sibling modules fail to resolve.
**Why it happens:** With `includes()`, all child modules are flattened and loaded together. Order within `includes()` does NOT matter because Koin resolves lazily. However, the `commonModule` has a comment "Order matters: ExerciseRepository must be created before WorkoutRepository" which suggests there may be an initialization-order dependency.
**How to avoid:** Koin resolves lazily (at `get()` time), so declaration order is irrelevant. The comment in the existing code is misleading -- Koin doesn't create instances at module-load time. Verify this is safe by checking that no `single` definition triggers side effects at construction that another depends on.
**Warning signs:** If WorkoutRepository constructor calls ExerciseRepository methods during init, order would matter at resolution time, but Koin handles this by resolving the dependency chain.

### Pitfall 5: KoinInit.kt iOS Entry Point
**What goes wrong:** `doInitKoin()` (called from Swift) stops working after the rename.
**Why it happens:** KoinInit.kt currently calls `modules(commonModule, platformModule)`. If `commonModule` is renamed without updating this file, iOS builds break.
**How to avoid:** Update KoinInit.kt to use the new `appModule` name.
**Warning signs:** iOS crash at startup with Koin initialization failure.

## Code Examples

### Example 1: Feature Module Definition
```kotlin
// Source: verified against Koin 4.1.1 docs
// File: shared/src/commonMain/kotlin/com/devil/phoenixproject/di/DataModule.kt
package com.devil.phoenixproject.di

import org.koin.dsl.module

val dataModule = module {
    // Database
    single { DatabaseFactory(get()).createDatabase() }
    single { ExerciseImporter(get()) }

    // Repositories
    single<ExerciseRepository> { SqlDelightExerciseRepository(get(), get()) }
    single<WorkoutRepository> { SqlDelightWorkoutRepository(get(), get()) }
    single<PersonalRecordRepository> { SqlDelightPersonalRecordRepository(get()) }
    single<GamificationRepository> { SqlDelightGamificationRepository(get()) }
    single<UserProfileRepository> { SqlDelightUserProfileRepository(get()) }
    single<TrainingCycleRepository> { SqlDelightTrainingCycleRepository(get()) }
    single<CompletedSetRepository> { SqlDelightCompletedSetRepository(get()) }
    single<ProgressionRepository> { SqlDelightProgressionRepository(get()) }
}
```

### Example 2: Root Module with includes()
```kotlin
// File: shared/src/commonMain/kotlin/com/devil/phoenixproject/di/AppModule.kt
package com.devil.phoenixproject.di

import org.koin.dsl.module

expect val platformModule: org.koin.core.module.Module

val appModule = module {
    includes(dataModule, syncModule, domainModule, presentationModule)
}
```

### Example 3: verify() Test
```kotlin
// File: shared/src/androidUnitTest/kotlin/com/devil/phoenixproject/di/KoinModuleVerifyTest.kt
package com.devil.phoenixproject.di

import com.devil.phoenixproject.data.local.DriverFactory
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.util.ConnectivityChecker
import com.devil.phoenixproject.util.CsvExporter
import com.devil.phoenixproject.util.DataBackupManager
import com.russhwolf.settings.Settings
import org.junit.Test
import org.koin.test.verify.verify

class KoinModuleVerifyTest {

    @Test
    fun verifyAppModule() {
        appModule.verify(
            extraTypes = listOf(
                DriverFactory::class,
                Settings::class,
                BleRepository::class,
                CsvExporter::class,
                DataBackupManager::class,
                ConnectivityChecker::class,
            )
        )
    }
}
```

### Example 4: Updated KoinInit.kt
```kotlin
// File: shared/src/commonMain/kotlin/com/devil/phoenixproject/di/KoinInit.kt
fun initKoin(appDeclaration: KoinAppDeclaration = {}) = startKoin {
    appDeclaration()
    modules(appModule, platformModule)
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| `checkModules()` | `Module.verify()` | Koin 3.3 (2022) | verify() is lighter, no mock setup needed |
| Single monolith module | `includes()` composition | Koin 3.2 (2022) | Better organization, visibility control |
| Runtime-only DI validation | Koin Compiler Plugin | Koin 4.0 (2024) | Compile-time safety, but requires annotations migration |

**Deprecated/outdated:**
- `checkModules()`: Deprecated in Koin 4.0. Use `verify()` instead.
- Both `verify()` and `checkModules()` are on a deprecation path toward the Koin Compiler Plugin, but for non-annotation codebases, `verify()` is the current recommended approach.

## Key Prior Decisions Affecting This Phase

1. **Managers stay OUT of Koin** (manual construction in MainViewModel) -- No manager bindings to worry about. WorkoutCoordinator, RoutineFlowManager, ActiveSessionEngine are constructed inside MainViewModel, not in Koin.
2. **Concrete classes for sub-managers, not interfaces** -- Means no interface bindings for managers needed.
3. **Phase 2 completed** -- WorkoutCoordinator + RoutineFlowManager + ActiveSessionEngine extracted from DWSM. MainViewModel constructor parameters are the final shape.

## Files That Need Modification

| File | Change |
|------|--------|
| `shared/src/commonMain/.../di/AppModule.kt` | Split into appModule with includes(); move bindings to feature modules |
| `shared/src/commonMain/.../di/DataModule.kt` | NEW: dataModule (10 bindings) |
| `shared/src/commonMain/.../di/SyncModule.kt` | NEW: syncModule (7 bindings) |
| `shared/src/commonMain/.../di/DomainModule.kt` | NEW: domainModule (5 bindings) |
| `shared/src/commonMain/.../di/PresentationModule.kt` | NEW: presentationModule (7 bindings) |
| `shared/src/commonMain/.../di/KoinInit.kt` | Change `commonModule` to `appModule` in modules() call |
| `shared/src/androidUnitTest/.../di/KoinModuleVerifyTest.kt` | NEW: verify() test |
| `androidApp/src/androidTest/.../e2e/AppE2ETest.kt` | Update `commonModule` reference to `appModule` |

## Open Questions

1. **Should `commonModule` be kept as an alias?**
   - What we know: AppE2ETest imports `commonModule` directly. Renaming breaks it.
   - What's unclear: Whether other test files or documentation reference `commonModule`.
   - Recommendation: Rename to `appModule` everywhere and update references in one pass. A grep for `commonModule` across the codebase will catch all references.

2. **Should `platformModule` be split too?**
   - What we know: platformModule has 6-7 bindings (DriverFactory, Settings, BleRepository, CsvExporter, DataBackupManager, ConnectivityChecker).
   - What's unclear: Whether the phase scope includes platformModule.
   - Recommendation: Leave platformModule as-is. It's already platform-scoped and small. The phase requirements only mention the commonModule split.

3. **Should the empty `androidApp/.../AppModule.kt` be cleaned up?**
   - What we know: `androidApp/src/main/.../AppModule.kt` exists with an empty module. It's not referenced anywhere in KoinInit.
   - Recommendation: Delete it as part of cleanup. It's dead code.

## Sources

### Primary (HIGH confidence)
- [Koin Modules Documentation](https://insert-koin.io/docs/4.0/reference/koin-core/modules/) - includes() API, module composition
- [Koin verify() Documentation](https://insert-koin.io/docs/reference/koin-test/verify/) - verify API, extraTypes, injectedParameters
- [Koin KMP Advanced Patterns](https://insert-koin.io/docs/reference/koin-mp/kmp/) - expect/actual module pattern

### Secondary (MEDIUM confidence)
- [Koin 4.1 Release Blog](https://blog.kotzilla.io/koin-4.1-is-here) - New features in 4.1
- [Koin 4.0 Release Blog](https://blog.insert-koin.io/koin-4-0-official-release-f4827bbcfce3) - Deprecation of checkModules

### Codebase (HIGH confidence - direct inspection)
- `shared/src/commonMain/.../di/AppModule.kt` - Current monolith module (28 bindings)
- `shared/src/commonMain/.../di/KoinInit.kt` - Entry point for Koin initialization
- `shared/src/androidMain/.../di/PlatformModule.android.kt` - Android platform bindings (7 bindings)
- `shared/src/iosMain/.../di/PlatformModule.ios.kt` - iOS platform bindings (6 bindings)
- `androidApp/src/androidTest/.../e2e/AppE2ETest.kt` - Existing test that references commonModule
- `gradle/libs.versions.toml` - Koin version 4.1.1 confirmed

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - Already using Koin 4.1.1, all dependencies present
- Architecture: HIGH - includes() is well-documented, straightforward split
- Pitfalls: HIGH - Identified from direct codebase inspection and official docs
- verify() API: HIGH - Confirmed JVM-only, confirmed extraTypes pattern

**Research date:** 2026-02-13
**Valid until:** 2026-04-13 (stable Koin API, no breaking changes expected)
