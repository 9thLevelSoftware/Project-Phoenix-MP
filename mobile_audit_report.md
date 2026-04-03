# Mobile App Audit Report: BLE/UI/Platform

**Project:** Phoenix App (Vitruvian Trainer)
**Audit Type:** Mobile-Specific Implementation Review
**Scope:** BLE lifecycle, Android BLE (Nordic/Kable), Compose UI, Platform Code Consistency
**Auditor:** Mobile App Builder Agent
**Date:** 2026-03-28

---

## Executive Summary

This audit examined the mobile-specific implementations of the Kotlin Multiplatform Phoenix workout app, focusing on BLE connectivity, UI patterns, and platform-specific code quality. The app uses Kable (multiplatform BLE library) rather than the Nordic Android BLE library directly, with Nordic UART Service UUIDs for device communication.

**Overall Grade: B+** - Solid implementation with proper architecture patterns, but some areas need attention for production hardening.

---

## 1. BLE Connection Lifecycle Management

### 1.1 Connection State Machine
**File:** `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/KableBleConnectionManager.kt`

**Strengths:**
- ✅ Proper sealed class `ConnectionState` with states: Disconnected, Scanning, Connecting, Connected, Error
- ✅ `wasEverConnected` flag prevents auto-reconnect on initial Peripheral creation (avoids phantom disconnects)
- ✅ `isExplicitDisconnect` flag distinguishes user-initiated vs unexpected disconnects
- ✅ State observer job cancellation before launching new ones (H2 fix for ghost callbacks)
- ✅ Connection retry logic with exponential backoff (3 attempts, 1.5s delay)

**Issues Found:**

#### 🔴 CRITICAL: Race Condition in `connect()` - State Observer Cancellation
```kotlin
// Line ~265-270: Cancel and relaunch state observer
stateObserverJob?.cancel()  // Cancel happens here
// ... peripheral creation ...
stateObserverJob = peripheral?.state?.onEach { ... }?.launchIn(scope)  // New observer launched
```
**Issue:** `cancel()` is not suspending - there's a potential race where the old observer processes a state event after `cancel()` but before reassignment. This could emit stale state events.

**Recommendation:** Use `stateObserverJob?.cancelAndJoin()` or ensure atomic job replacement.

#### 🔴 HIGH: Timeout Disconnect Logic Race Condition
```kotlin
// Line ~310-320 in startMonitorPolling()
if (consecutiveTimeouts >= BleConstants.Timing.MAX_CONSECUTIVE_TIMEOUTS) {
    scope.launch { onConnectionLost() }
    return@withLock
}
```
**Issue:** `onConnectionLost()` is launched in a new coroutine while holding the `monitorPollingMutex`. If `onConnectionLost()` triggers disconnection immediately, there could be a deadlock or reentrant mutex issue.

#### 🟡 MEDIUM: Missing Cleanup on App Termination
**Issue:** No `onCleared()` or app lifecycle observer to force-disconnect BLE when app is killed. This can leave dangling GATT connections on Android, causing issues on Pixel devices (noted in comments as Android 16/Pixel 7 specific issue).

**Recommendation:** Implement `Application.ActivityLifecycleCallbacks` or `ProcessLifecycleOwner` to detect app backgrounding/termination and force disconnect.

### 1.2 Scanning Lifecycle
**File:** `KableBleConnectionManager.kt` - `startScanning()` and `stopScanning()`

**Strengths:**
- ✅ `withTimeoutOrNull(BleConstants.SCAN_TIMEOUT_MS)` provides automatic scan timeout (30s)
- ✅ `scanJob?.cancel()` before starting new scan prevents duplicate scans
- ✅ Multiple device filtering strategies (name prefix + service UUID + service data)

**Issues Found:**

#### 🟡 MEDIUM: Scan Job Cancellation Doesn't Wait for Completion
```kotlin
suspend fun stopScanning() {
    scanJob?.cancel()  // Non-blocking cancel
    scanJob = null     // Immediate null assignment
    // ...
}
```
**Issue:** If the scan flow is in the middle of processing an advertisement when cancelled, the coroutine may not complete immediately. This could briefly leak scanner resources.

**Recommendation:** Consider `scanJob?.cancelAndJoin()` in suspend function.

### 1.3 Auto-Reconnect Logic
**File:** `KableBleConnectionManager.kt` - Line ~370-380

**Strengths:**
- ✅ Guarded by `wasEverConnected` and `!isExplicitDisconnect`
- ✅ Device address validation before reconnect request

**Issues Found:**

#### 🟡 MEDIUM: No Reconnect Attempt Limit
**Issue:** `onReconnectionRequested()` fires on every unexpected disconnect, with no maximum retry count. A flaky connection could lead to infinite reconnection loops, draining battery.

**Recommendation:** Add exponential backoff with max attempts (e.g., 3 attempts with 5s, 15s, 30s delays).

---

## 2. Android BLE Implementation (Kable Library)

### 2.1 Platform-Specific Extensions
**File:** `shared/src/androidMain/kotlin/com/devil/phoenixproject/data/ble/BleExtensions.android.kt`

**Strengths:**
- ✅ Proper expect/actual pattern for `requestHighPriority()` and `requestMtuIfSupported()`
- ✅ Safe casting with `as? AndroidPeripheral` with null check
- ✅ 100ms delay after priority change for propagation (matching parent repo behavior)

**Issues Found:**

#### 🟡 MEDIUM: MTU Request Failure Not Propagated
```kotlin
actual suspend fun Peripheral.requestMtuIfSupported(mtu: Int): Int? {
    // ...
    return try {
        val negotiatedMtu = androidPeripheral.requestMtu(mtu)
        // ...
        negotiatedMtu
    } catch (e: Exception) {
        Logger.w { "❌ MTU negotiation failed: ${e.message}" }
        null  // Silent failure - caller doesn't know it failed
    }
}
```
**Issue:** MTU negotiation failure returns `null`, same as iOS "system default" case. Caller cannot distinguish between "iOS doesn't need explicit MTU" and "Android MTU request failed."

**Recommendation:** Return `Result<Int>` or throw specific exception on Android MTU failure.

### 2.2 BLE Operation Queue
**File:** `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/BleOperationQueue.kt`

**Strengths:**
- ✅ Single Mutex for all BLE operations (prevents interleaving that causes fault 16384)
- ✅ Write retry logic with exponential backoff on "Busy" errors
- ✅ Non-reentrant design explicitly documented

**Issues Found:**

#### 🔴 HIGH: Non-Reentrant Mutex Can Deadlock on Nested Operations
```kotlin
// Line ~20 - Documented but not enforced:
suspend fun <T> read(operation: suspend () -> T): T = mutex.withLock { operation() }

// In KableBleConnectionManager.kt, onDeviceReady():
p.requestHighPriority()  // Calls mutex.withLock internally
p.requestMtuIfSupported(...)  // Another mutex acquisition
```
**Issue:** While the comment warns against nesting, the code does call `requestHighPriority()` and `requestMtuIfSupported()` which may internally use the same queue. If these methods call `bleQueue.read()` or `bleQueue.write()`, it will deadlock.

**Actual Code Inspection:** Both extension functions do NOT use `bleQueue`, they use `peripheral` directly, so this is safe. ✅

#### 🟡 MEDIUM: No Timeout on Mutex Lock Acquisition
```kotlin
suspend fun write(...): Result<Unit> {
    for (attempt in 0 until maxRetries) {
        try {
            mutex.withLock {  // Can hang indefinitely if mutex is held
                peripheral.write(...)
            }
        }
    }
}
```
**Issue:** If the mutex is held by a stalled operation, subsequent calls will wait indefinitely.

**Recommendation:** Add `withTimeout()` around `mutex.withLock()` for operations.

### 2.3 Metric Polling Engine
**File:** `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/MetricPollingEngine.kt`

**Strengths:**
- ✅ Independent Job references for each polling loop (monitor, diagnostic, heuristic, heartbeat)
- ✅ Selective stop capability (`stopMonitorOnly()` preserves heartbeat)
- ✅ Consecutive timeout tracking (POLL-03) triggers disconnect after 5 timeouts
- ✅ Mutex-guarded monitor polling prevents concurrent loops

**Issues Found:**

#### 🟡 MEDIUM: No Backpressure Handling on Monitor Data
```kotlin
// Line ~208-214 in startMonitorPolling()
while (isActive) {
    try {
        val data = withTimeoutOrNull(...) { bleQueue.read { ... } }
        if (data != null) {
            // ... parse and emit
            // NO DELAY on success - BLE response time rate-limits
        }
    }
}
```
**Issue:** The polling loop has no delay on successful reads, relying on BLE response time. However, if `parseMonitorData()` or downstream `onMetricEmit()` is slow, the loop could backpressure and fill memory with pending coroutines.

**Recommendation:** Add `yield()` or small delay if processing time exceeds threshold.

#### 🟡 LOW: Uncaught CancellationException in Polling Loops
```kotlin
catch (e: Exception) {
    // CancellationException IS an Exception, will be caught here
    log.w { "Monitor poll FAILED #$failCount: ${e.message}" }
    delay(50)
}
```
**Issue:** CancellationException extends Exception, so it gets caught and delays instead of immediately terminating. This delays coroutine cancellation by up to 50ms per iteration.

**Fix (already partially present):**
```kotlin
catch (_: kotlinx.coroutines.TimeoutCancellationException) {
    // properly rethrows
}
// But missing: catch (e: CancellationException) { throw e }
```

---

## 3. Compose UI Patterns and State Management

### 3.1 Android App Architecture
**Files:**
- `androidApp/src/main/kotlin/com/devil/phoenixproject/MainActivity.kt`
- `androidApp/src/main/kotlin/com/devil/phoenixproject/VitruvianApp.kt`

**Strengths:**
- ✅ Proper Koin DI initialization in `VitruvianApp.onCreate()`
- ✅ Activity lifecycle callbacks for `ActivityHolder` (auto-registration pattern)
- ✅ Edge-to-edge enabled via `enableEdgeToEdge()`
- ✅ Locale applied before composition to prevent first-frame flicker

**Issues Found:**

#### 🟡 MEDIUM: No LifecycleObserver for BLE in Activity
```kotlin
// MainActivity.kt
setContent {
    RequireBlePermissions {
        App()
    }
}
```
**Issue:** No `DisposableEffect` or lifecycle observer to disconnect BLE when app backgrounds or activity is destroyed. This can leave BLE connections active when app is backgrounded, violating BLE best practices and draining battery.

**Recommendation:** Add lifecycle-aware BLE disconnection:
```kotlin
DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_PAUSE) {
            viewModel.onAppBackgrounded()
        }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
}
```

#### 🟡 LOW: Missing ProGuard Rules Comment
**File:** `androidApp/build.gradle.kts`

**Issue:** ProGuard is enabled (`isMinifyEnabled = true`), but there's no mention of BLE-specific keep rules in the config. Kable and other BLE libraries may use reflection that ProGuard could strip.

**Recommendation:** Add `proguard-rules.pro` with BLE library keep rules:
```proguard
-keep class com.juul.kable.** { *; }
-keep class com.devil.phoenixproject.data.ble.** { *; }
```

### 3.2 ViewModel State Management
**File:** `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/` (pattern analysis)

**Strengths:**
- ✅ Clean Architecture with domain models in commonMain
- ✅ StateFlow/SharedFlow for reactive streams
- ✅ Koin ViewModel injection

**Issues Found:**

#### 🟡 MEDIUM: Potential StateFlow Buffer Overflow
In `KableBleConnectionManager.kt`:
```kotlin
private val _commandResponses = MutableSharedFlow<UByte>(
    replay = 0,
    extraBufferCapacity = 16,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
```
**Issue:** Buffer overflow drops oldest responses. If command volume exceeds processing rate, responses may be lost without logging.

**Recommendation:** Add logging when `tryEmit` returns false (buffer full).

---

## 4. Platform-Specific Code Consistency

### 4.1 Android vs iOS Expect/Actual
**Files:**
- `shared/src/androidMain/kotlin/com/devil/phoenixproject/data/ble/BleExtensions.android.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/data/ble/BleExtensions.ios.kt`
- `shared/src/androidMain/kotlin/com/devil/phoenixproject/domain/model/UUIDGeneration.android.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/domain/model/UUIDGeneration.ios.kt`

**Strengths:**
- ✅ Consistent expect/actual pattern across BLE extensions
- ✅ iOS extensions properly documented as no-ops where CoreBluetooth handles automatically
- ✅ UUID generation properly platform-abstracted

**Issues Found:**

#### 🟢 LOW: Minor iOS MTU Documentation Gap
**File:** `BleExtensions.ios.kt`
```kotlin
actual suspend fun Peripheral.requestMtuIfSupported(mtu: Int): Int? {
    // iOS CoreBluetooth negotiates MTU automatically during connection
    return null
}
```
**Note:** This is correct behavior, but could benefit from clarifying that iOS MTU is typically 185 bytes negotiated automatically.

### 4.2 PlatformUtils Implementation
**File:** `shared/src/iosMain/kotlin/com/devil/phoenixproject/domain/model/PlatformUtils.ios.kt`

**Observation:** PlatformUtils.ios.kt was not found in the glob results - need to verify it exists or check if platform utilities are in a different location.

### 4.3 Database Driver Configuration
**File:** `shared/build.gradle.kts`

**Strengths:**
- ✅ Proper SQLDelight driver configuration for both platforms
- ✅ Android uses Android driver, iOS uses Native driver
- ✅ Schema manifest validation Gradle task for migration safety

---

## 5. Workout Session State Management

### 5.1 Workout State Sealed Class
**File:** `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt`

**Strengths:**
- ✅ Comprehensive state representation: Idle, Initializing, Countdown, Active, SetSummary, Paused, Completed, etc.
- ✅ Rich SetSummary with 26 fields for analytics
- ✅ Echo mode specific tracking (warmup/working/burnout phases)

**Issues Found:**

#### 🟡 MEDIUM: Mutable State in Data Class (RepCount)
```kotlin
data class RepCount(
    val warmupReps: Int = 0,
    val workingReps: Int = 0,
    // ... 11 more fields
)
```
**Issue:** While RepCount is immutable as a data class, it's frequently recreated during workouts. Each metric update could trigger RepCount recreation and UI recomposition.

**Recommendation:** Consider using `ComposeRuntime` derived state or `SnapshotState` for high-frequency fields like `phaseProgress` and `pendingRepProgress`.

#### 🟡 LOW: Default Values Could Mask Uninitialized State
```kotlin
data class WorkoutSession(
    val id: String = generateUUID(),  // Auto-generated
    val timestamp: Long = currentTimeMillis(),  // Auto-generated
    // ... many nullable fields with defaults
)
```
**Issue:** Default values make it easy to create partially-initialized sessions. Some fields like `exerciseId`, `exerciseName` are nullable when they should likely be required for valid sessions.

### 5.2 ProgramMode vs WorkoutMode Duality
**File:** `Models.kt`

**Issue:** Two parallel sealed class hierarchies exist:
- `ProgramMode` (with modeValue Int) - used for protocol
- `WorkoutMode` (with displayName String) - used for UI

This creates conversion overhead and potential for sync issues between UI and protocol layers.

**Recommendation:** Consolidate to single sealed class with both protocol and UI properties.

---

## 6. Build Configuration Issues

### 6.1 Android Build Configuration
**File:** `androidApp/build.gradle.kts`

**Strengths:**
- ✅ Min SDK 26 (Android 8.0) - good BLE 5.0 support baseline
- ✅ Target SDK 36 - up to date
- ✅ Supabase credentials validation (fail-fast)
- ✅ Version code injection support for CI

**Issues Found:**

#### 🟡 MEDIUM: Missing BLE Permission Declaration Check
**Issue:** While `RequireBlePermissions` composable exists, the audit couldn't locate the AndroidManifest.xml to verify:
- `BLUETOOTH_CONNECT` permission (Android 12+)
- `BLUETOOTH_SCAN` permission (Android 12+)
- `ACCESS_FINE_LOCATION` (for BLE scanning)
- `BLUETOOTH_ADMIN` (legacy)

**Recommendation:** Verify manifest contains all required BLE permissions with proper `android:usesPermissionFlags="neverForLocation"` for `BLUETOOTH_SCAN` if location isn't needed.

### 6.2 Dependency Versions
**File:** `shared/build.gradle.kts`

**Observations:**
- ✅ Kable 1.x (BLE multiplatform) - appropriate choice over Nordic Android-only
- ✅ SQLDelight 2.2.1 - current stable
- ✅ Kotlin 2.3.0 - current stable
- ✅ Compose Multiplatform 1.10.1 - current stable

---

## 7. Missing Null-Safety & Edge Cases

### 7.1 Null Safety Issues

#### 🟡 MEDIUM: Advertisement Name Can Be Null
```kotlin
// KableBleConnectionManager.kt - startScanning()
.filter { advertisement ->
    val name = advertisement.name
    if (name != null) {
        val isVitruvian = name.startsWith("Vee_", ignoreCase = true) || ...
    }
    // Also checks service UUIDs and serviceData
}
```
**Issue:** While null name is handled, the fallback naming `"Vitruvian ($identifier)"` could create duplicate display names if multiple devices have null names.

#### 🟡 MEDIUM: Peripheral State Flow Null Safety
```kotlin
stateObserverJob = peripheral?.state
    ?.onEach { state -> ... }
    ?.launchIn(scope)
```
**Issue:** If `peripheral` becomes null between the Elvis check and `launchIn`, this could NPE. In practice, this is safe because `peripheral` is set synchronously before this call, but it's fragile.

### 7.2 Edge Cases Not Handled

#### 🔴 HIGH: No Handling for Airplane Mode
**Issue:** No check for airplane mode before scanning. On Android, starting BLE scan in airplane mode throws `IllegalStateException` or fails silently depending on Android version.

#### 🟡 MEDIUM: No Bluetooth State Monitoring
**Issue:** No observer for Bluetooth adapter state changes. If user disables Bluetooth mid-connection, the app may not detect it until next operation fails.

**Recommendation:** Use `BluetoothManager` to observe adapter state and trigger disconnect when BT is disabled.

#### 🟡 LOW: No Handling for Bond State Changes
**Issue:** Android BLE devices can become unbonded. No handling for `ACTION_BOND_STATE_CHANGED` broadcasts to detect when a previously-paired device is forgotten.

---

## 8. Performance Concerns

### 8.1 Memory Allocation

#### 🟡 MEDIUM: ByteArray Allocations in Protocol Parser
**File:** `ProtocolParser.kt`

```kotlin
fun Byte.toVitruvianHex(): String {
    val hex = "0123456789ABCDEF"
    val value = this.toInt() and 0xFF
    return "${hex[value shr 4]}${hex[value and 0x0F]}"
}
```
**Issue:** String creation for hex conversion creates garbage during high-frequency monitor polling (10-20Hz).

**Recommendation:** Cache hex strings or use `StringBuilder` for batch conversions.

### 8.2 Coroutine Usage

#### 🟡 MEDIUM: Unconfined Coroutines in Notification Handlers
```kotlin
// In startObservingNotifications()
scope.launch {
    p.observe(repsCharacteristic)
        .catch { ... }
        .collect { data ->
            onRepEventFromCharacteristic(data)
        }
}
```
**Issue:** Each characteristic observation launches in `scope` without dispatcher specification. On Android, this uses the default dispatcher which may be the thread pool. BLE callbacks should ideally use a dedicated single-thread dispatcher for ordering guarantees.

---

## Summary of Recommendations by Priority

### 🔴 Critical (Fix Immediately)
1. **Race condition in state observer cancellation** - Use `cancelAndJoin()` or atomic replacement
2. **Add Airplane Mode check** before BLE operations
3. **Add Bluetooth state monitoring** for mid-session BT disable

### 🟡 High (Fix Before Production)
4. **Add BLE disconnect on app backgrounding** via lifecycle observer
5. **Add reconnect attempt limits** with exponential backoff
6. **Fix CancellationException catching** in polling loops
7. **Add ProGuard keep rules** for BLE libraries
8. **Verify AndroidManifest BLE permissions** are complete

### 🟢 Medium (Quality Improvements)
9. **Improve MTU failure propagation** (return Result instead of nullable)
10. **Add mutex acquisition timeout** to prevent indefinite hangs
11. **Optimize hex string allocations** in protocol parser
12. **Consolidate ProgramMode/WorkoutMode** hierarchies
13. **Add backpressure handling** in monitor polling
14. **Improve buffer overflow logging** for command responses

### 🟢 Low (Nice to Have)
15. **iOS MTU documentation** clarifying automatic negotiation
16. **Add bond state change monitoring** for Android
17. **Reduce default value usage** in WorkoutSession
18. **Use SnapshotState** for high-frequency UI fields

---

## File Locations for Review

### Core BLE Implementation:
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/KableBleConnectionManager.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/BleOperationQueue.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/MetricPollingEngine.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/ble/ProtocolParser.kt`
- `shared/src/androidMain/kotlin/com/devil/phoenixproject/data/ble/BleExtensions.android.kt`
- `shared/src/iosMain/kotlin/com/devil/phoenixproject/data/ble/BleExtensions.ios.kt`

### Domain Models:
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt`
- `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/BleConstants.kt`

### Android App:
- `androidApp/src/main/kotlin/com/devil/phoenixproject/MainActivity.kt`
- `androidApp/src/main/kotlin/com/devil/phoenixproject/VitruvianApp.kt`
- `androidApp/src/main/kotlin/com/devil/phoenixproject/ui/theme/AndroidTheme.kt`

### Build Configuration:
- `androidApp/build.gradle.kts`
- `shared/build.gradle.kts`

---

**End of Report**
