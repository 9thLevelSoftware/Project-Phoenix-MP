# Phoenix App Regression Test Plan
## Issues Being Fixed

### Summary of Fix Areas
1. **BLE Lifecycle Fixes**: disconnect on background, BT state monitoring, observer race condition
2. **Performance Fixes**: Flow buffer size, batched INSERTs, dispatcher fixes, repository scope
3. **Migration Safety Fix**: INSERT OR IGNORE → proper conflict handling

---

## Unit Tests to Run

### Critical Schema/Database Tests
| Test File | Location | Purpose | Priority |
|-----------|----------|---------|----------|
| `SchemaParityTest.kt` | `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/local/` | Validates fresh install vs upgrade path parity - ensures migrations produce identical schema to fresh install | **CRITICAL** |
| `SchemaManifestTest.kt` | `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/local/` | Validates schema manifest integrity - ensures all columns have provenance | **CRITICAL** |
| `MigrationManagerTest.kt` | `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/migration/` | Tests migration execution logic and error handling | HIGH |
| `LegacySchemaReconciliationTest.kt` | `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/local/` | Tests legacy schema reconciliation during upgrades | MEDIUM |

### Dependency Injection Tests
| Test File | Location | Purpose | Priority |
|-----------|----------|---------|----------|
| `KoinModuleVerifyTest.kt` | `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/di/` | Verifies Koin DI module bindings and dependencies | **CRITICAL** |

### BLE Tests
| Test File | Location | Purpose | Priority |
|-----------|----------|---------|----------|
| `KableBleConnectionManagerTest.kt` | `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/` | Tests BLE connection manager callback routing and state management | **CRITICAL** |
| `BleOperationQueueTest.kt` | `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/` | Tests BLE operation queue serialization | HIGH |
| `BleConstantsTest.kt` | `shared/src/commonTest/kotlin/com/devil/phoenixproject/util/` | Validates BLE protocol constants and UUIDs | **CRITICAL** |
| `BleConnectionManagerTest.kt` | `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/presentation/manager/` | Android-specific BLE manager tests | HIGH |

### Additional Related Tests
| Test File | Location | Purpose | Priority |
|-----------|----------|---------|----------|
| `MetricPollingEngineTest.kt` | `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/` | Tests metric polling and batching | HIGH |
| `SqlDelightWorkoutRepositoryTest.kt` | `shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/repository/` | Tests repository SQL operations | HIGH |
| `RepMetricRepositoryTest.kt` | `shared/src/commonTest/kotlin/com/devil/phoenixproject/data/repository/` | Tests rep metric persistence | MEDIUM |

---

## Build Verification Commands

### 1. KMP Shared Library Build
**Command:**
```bash
./gradlew :shared:build
```

**Expected Results:**
- BUILD SUCCESSFUL
- No compilation errors
- No unresolved references
- No type mismatches
- SQLDelight generates interfaces successfully

**Failure Indicators:**
- Compilation errors in KableBleConnectionManager
- SQLDelight generation failures
- Kotlin multiplatform linkage errors

---

### 2. Android App Build
**Command:**
```bash
./gradlew :androidApp:assembleDebug
```

**Expected Results:**
- BUILD SUCCESSFUL
- APK generated in `androidApp/build/outputs/apk/debug/`
- No resource conflicts
- No manifest errors

---

### 3. SQLDelight Schema Compilation
**Command:**
```bash
./gradlew :shared:generateSqlDelightInterface
```

**Expected Results:**
- Schema generates without errors
- All 24 migrations are applied successfully
- Version 25 (1 + 24 migrations) validated

---

### 4. Schema Manifest Validation
**Command:**
```bash
./gradlew :shared:validateSchemaManifest
```

**Expected Results:**
```
Schema manifest validated: X columns across Y tables, all covered.
```

**Failure Indicators:**
```
Schema manifest validation FAILED: N column(s) lack provenance
```

---

## Unit Test Execution Commands

### Run All androidHostTest Tests
**Command:**
```bash
./gradlew :shared:testAndroidHostTest
```

### Run All commonTest Tests
**Command:**
```bash
./gradlew :shared:testCommonTest
```

### Run Specific Critical Tests

#### Schema Tests
```bash
./gradlew :shared:testAndroidHostTest --tests "com.devil.phoenixproject.data.local.SchemaParityTest"
./gradlew :shared:testAndroidHostTest --tests "com.devil.phoenixproject.data.local.SchemaManifestTest"
./gradlew :shared:testAndroidHostTest --tests "com.devil.phoenixproject.data.migration.MigrationManagerTest"
```

#### DI Test
```bash
./gradlew :shared:testAndroidHostTest --tests "com.devil.phoenixproject.di.KoinModuleVerifyTest"
```

#### BLE Tests
```bash
./gradlew :shared:testCommonTest --tests "com.devil.phoenixproject.data.ble.KableBleConnectionManagerTest"
./gradlew :shared:testCommonTest --tests "com.devil.phoenixproject.data.ble.BleOperationQueueTest"
./gradlew :shared:testCommonTest --tests "com.devil.phoenixproject.util.BleConstantsTest"
./gradlew :shared:testAndroidHostTest --tests "com.devil.phoenixproject.presentation.manager.BleConnectionManagerTest"
```

#### Repository Tests
```bash
./gradlew :shared:testAndroidHostTest --tests "com.devil.phoenixproject.data.repository.SqlDelightWorkoutRepositoryTest"
./gradlew :shared:testCommonTest --tests "com.devil.phoenixproject.data.repository.RepMetricRepositoryTest"
./gradlew :shared:testCommonTest --tests "com.devil.phoenixproject.data.ble.MetricPollingEngineTest"
```

---

## Verification Checklist

### API Stability Checks
- [ ] **Nordic BLE UART UUIDs unchanged**
  - Service UUID: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
  - RX (Write): `6e400002-b5a3-f393-e0a9-e50e24dcca9e`
  - TX (Notify): `6e400003-b5a3-f393-e0a9-e50e24dcca9e`

- [ ] **Public API signatures unchanged**
  - No breaking changes to BleRepository interface
  - No breaking changes to WorkoutRepository interface
  - No breaking changes to MetricRepository interface

### Database Schema Checks
- [ ] **SQLDelight schema compiles correctly**
  - VitruvianDatabase.sq parses without errors
  - All 24 migrations (.sqm files) execute successfully
  - Version 25 is correct (initial schema + 24 migrations)

- [ ] **Migration 24 paths work correctly**
  - Fresh install path: Database created at version 25 directly
  - Upgrade path: All migrations 1-24 execute in order
  - Schema parity: Fresh install and upgrade produce identical schemas

### Performance Checks
- [ ] **Flow buffer sizes appropriate**
  - Metric polling Flow has sufficient buffer (64+)
  - No buffer overflow warnings in logs

- [ ] **Batch INSERTs functioning**
  - Multiple metrics batched into single transaction
  - RepMetricRepository uses batch operations

- [ ] **Dispatcher usage correct**
  - IO operations on Dispatchers.IO
  - UI updates on Dispatchers.Main
  - No blocking on Main thread

### BLE Lifecycle Checks
- [ ] **Disconnect on background**
  - App moving to background triggers disconnect()
  - No pending BLE operations left orphaned

- [ ] **BT state monitoring**
  - Bluetooth off detected and propagated
  - Reconnection attempts paused when BT disabled

- [ ] **Observer race condition**
  - No concurrent modification exceptions
  - State callbacks properly synchronized

### Migration Safety Checks
- [ ] **Conflict handling correct**
  - INSERT OR IGNORE replaced with proper UPSERT where needed
  - ON CONFLICT clause appropriate for each table
  - No silent data loss during conflicts

---

## Expected Test Results Summary

### Unit Tests
| Test Suite | Expected Pass Rate | Max Acceptable Failures |
|------------|-------------------|------------------------|
| SchemaParityTest | 100% | 0 |
| SchemaManifestTest | 100% | 0 |
| KoinModuleVerifyTest | 100% | 0 |
| KableBleConnectionManagerTest | 100% | 0 |
| BleOperationQueueTest | 100% | 0 |
| BleConstantsTest | 100% | 0 |
| MetricPollingEngineTest | 100% | 0 |
| SqlDelightWorkoutRepositoryTest | 100% | 0 |

### Build Results
| Build Target | Expected Result |
|--------------|-----------------|
| `:shared:build` | SUCCESSFUL |
| `:shared:validateSchemaManifest` | PASSED |
| `:androidApp:assembleDebug` | SUCCESSFUL |

---

## Issue-Specific Verification

### Issue 1: BLE Lifecycle Fixes
**Files likely modified:**
- `KableBleConnectionManager.kt`
- `BleExtensions.android.kt` / `BleExtensions.ios.kt`
- `BleOperationQueue.kt`

**Verification steps:**
1. Run `KableBleConnectionManagerTest` - all 12 tests pass
2. Run `BleOperationQueueTest` - all 8 tests pass
3. Verify `disconnect()` properly cancels pending operations
4. Verify `onCleared()` cleans up observers

### Issue 2: Performance Fixes
**Files likely modified:**
- `RepMetricRepository.kt`
- `MetricPollingEngine.kt`
- `SqlDelightWorkoutRepository.kt`
- `LinkAccountViewModel.kt`

**Verification steps:**
1. Run `MetricPollingEngineTest` - verify batching works
2. Run `RepMetricRepositoryTest` - verify batch inserts work
3. Run `SqlDelightWorkoutRepositoryTest` - verify dispatcher usage
4. Check Flow buffer sizes in code review

### Issue 3: Migration Safety Fix
**Files likely modified:**
- `MigrationStatements.kt`

**Verification steps:**
1. Run `SchemaParityTest` - fresh vs upgrade paths identical
2. Run `SchemaManifestTest` - all columns have provenance
3. Run `MigrationManagerTest` - migration logic correct
4. Inspect `MigrationStatements.kt` for proper conflict handling

---

## Regression Test Execution Log Template

```
Date: ___________
Executed by: ___________
Branch: mvp

[ ] Step 1: Clean build
    ./gradlew clean
    Result: ___________

[ ] Step 2: Schema manifest validation
    ./gradlew :shared:validateSchemaManifest
    Result: ___________

[ ] Step 3: Shared module build
    ./gradlew :shared:build
    Result: ___________
    Duration: ___________

[ ] Step 4: Android app build
    ./gradlew :androidApp:assembleDebug
    Result: ___________
    Duration: ___________

[ ] Step 5: Run critical unit tests
    ./gradlew :shared:testAndroidHostTest --tests "com.devil.phoenixproject.data.local.SchemaParityTest"
    Result: ___________
    
    ./gradlew :shared:testAndroidHostTest --tests "com.devil.phoenixproject.data.local.SchemaManifestTest"
    Result: ___________
    
    ./gradlew :shared:testAndroidHostTest --tests "com.devil.phoenixproject.di.KoinModuleVerifyTest"
    Result: ___________

[ ] Step 6: Run BLE tests
    ./gradlew :shared:testCommonTest --tests "com.devil.phoenixproject.data.ble.*"
    Result: ___________

[ ] Step 7: Run repository tests
    ./gradlew :shared:testAndroidHostTest --tests "com.devil.phoenixproject.data.repository.SqlDelight*Test"
    Result: ___________

[ ] Step 8: Full test suite
    ./gradlew :shared:test
    Result: ___________
    Failed tests: ___________

Overall Result: [ ] PASSED [ ] FAILED

Issues Found:
1. ___________
2. ___________
3. ___________

Sign-off: ___________
```

---

## File Locations Reference

### Test Files
```
shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/local/SchemaParityTest.kt
shared/src/androidHostTest/kotlin/com/devil/phoenixproject/data/local/SchemaManifestTest.kt
shared/src/androidHostTest/kotlin/com/devil/phoenixproject/di/KoinModuleVerifyTest.kt
shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/KableBleConnectionManagerTest.kt
shared/src/commonTest/kotlin/com/devil/phoenixproject/data/ble/BleOperationQueueTest.kt
shared/src/commonTest/kotlin/com/devil/phoenixproject/util/BleConstantsTest.kt
```

### Source Files Being Fixed
```
shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/BleExtensions.android.kt
shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/BleOperationQueue.kt
shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/KableBleConnectionManager.kt
shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/MetricPollingEngine.kt
shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/MigrationStatements.kt
shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/KableBleRepository.kt
shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/RepMetricRepository.kt
shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt
shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/sync/LinkAccountViewModel.kt
shared/src/iosMain/kotlin/com/devil/phoenixproject/data/ble/BleExtensions.ios.kt
```

### Schema Files
```
shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq
shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/*.sqm
shared/src/commonMain/kotlin/com/devil/phoenixproject/data/local/SchemaManifest.kt
```

---

## Critical Constants to Verify

### Nordic UART Service (NUS) UUIDs
```kotlin
const val NUS_SERVICE_UUID_STRING = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
const val NUS_RX_CHAR_UUID_STRING = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"  // Write
const val NUS_TX_CHAR_UUID_STRING = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"  // Notify (standard NUS)
```

### BLE Timeouts
```kotlin
const val CONNECTION_TIMEOUT_MS = 15000L
const val GATT_OPERATION_TIMEOUT_MS = 5000L
const val SCAN_TIMEOUT_MS = 30000L
```

### Weight Limits
```kotlin
const val MAX_WEIGHT_KG = 100f  // Per cable
const val WEIGHT_INCREMENT_KG = 0.5f
```
