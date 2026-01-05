# Phase 4: Automatic Sync Triggers Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Automatically sync data when workouts complete and when the app returns to foreground, silently and only when online.

**Architecture:** New `SyncTriggerManager` orchestrates automatic syncs with throttling (5 min minimum, bypassed for workout complete), connectivity checking, and failure tracking (surface error after 3 consecutive failures). Uses Compose lifecycle for foreground detection. `ConnectivityChecker` uses expect/actual for platform-specific network checks.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform Lifecycle, Koin DI, Coroutines/Flow

---

## Task 1: Create ConnectivityChecker (expect/actual)

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/util/ConnectivityChecker.kt`
- Create: `shared/src/androidMain/kotlin/com/devil/phoenixproject/util/ConnectivityChecker.android.kt`
- Create: `shared/src/iosMain/kotlin/com/devil/phoenixproject/util/ConnectivityChecker.ios.kt`

**Step 1: Create the expect declaration in commonMain**

```kotlin
package com.devil.phoenixproject.util

/**
 * Platform-specific connectivity checker.
 * Used by SyncTriggerManager to skip sync attempts when offline.
 */
expect class ConnectivityChecker {
    /**
     * Returns true if the device appears to be online.
     * This is a best-effort check - sync may still fail due to network issues.
     */
    fun isOnline(): Boolean
}
```

**Step 2: Create the Android actual implementation**

```kotlin
package com.devil.phoenixproject.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

actual class ConnectivityChecker(private val context: Context) {
    actual fun isOnline(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
```

**Step 3: Create the iOS actual implementation**

```kotlin
package com.devil.phoenixproject.util

/**
 * iOS connectivity checker.
 * Returns true optimistically - we let the HTTP request timeout handle true offline.
 * This avoids complex NWPathMonitor setup while still being correct behavior.
 */
actual class ConnectivityChecker {
    actual fun isOnline(): Boolean {
        // Optimistic: attempt sync, let HTTP timeout handle offline
        // Full iOS implementation would use NWPathMonitor
        return true
    }
}
```

**Step 4: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid :shared:compileKotlinIosArm64`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/util/ConnectivityChecker.kt
git add shared/src/androidMain/kotlin/com/devil/phoenixproject/util/ConnectivityChecker.android.kt
git add shared/src/iosMain/kotlin/com/devil/phoenixproject/util/ConnectivityChecker.ios.kt
git commit -m "feat(sync): add ConnectivityChecker expect/actual for network detection"
```

---

## Task 2: Create SyncTriggerManager

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncTriggerManager.kt`

**Step 1: Create the SyncTriggerManager class**

```kotlin
package com.devil.phoenixproject.data.sync

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.util.ConnectivityChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Clock

/**
 * Manages automatic sync triggers with throttling and failure tracking.
 *
 * Sync is triggered:
 * - On workout complete (bypasses throttle)
 * - On app foreground (respects 5-minute throttle)
 *
 * Sync is skipped if:
 * - Device is offline
 * - User is not authenticated
 * - Throttle period hasn't elapsed (for foreground trigger)
 *
 * Error handling:
 * - Tracks consecutive failures
 * - Exposes hasPersistentError after 3 failures
 * - Resets on successful sync
 */
class SyncTriggerManager(
    private val syncManager: SyncManager,
    private val connectivityChecker: ConnectivityChecker
) {
    companion object {
        private const val THROTTLE_MILLIS = 5 * 60 * 1000L // 5 minutes
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }

    private var lastSyncAttemptMillis: Long = 0
    private var consecutiveFailures: Int = 0

    private val _hasPersistentError = MutableStateFlow(false)
    val hasPersistentError: StateFlow<Boolean> = _hasPersistentError.asStateFlow()

    /**
     * Called when a workout is completed and saved.
     * Always attempts sync (bypasses throttle) since workout data is critical.
     */
    suspend fun onWorkoutCompleted() {
        Logger.d { "SyncTrigger: Workout completed, attempting sync" }
        attemptSync(bypassThrottle = true)
    }

    /**
     * Called when the app returns to foreground.
     * Respects throttle to avoid excessive sync attempts.
     */
    suspend fun onAppForeground() {
        Logger.d { "SyncTrigger: App foreground, checking if sync needed" }
        attemptSync(bypassThrottle = false)
    }

    /**
     * Clears the persistent error state.
     * Called when user acknowledges the error or manually triggers sync.
     */
    fun clearError() {
        consecutiveFailures = 0
        _hasPersistentError.value = false
    }

    private suspend fun attemptSync(bypassThrottle: Boolean) {
        // Check authentication
        if (!syncManager.isAuthenticated.value) {
            Logger.d { "SyncTrigger: Skipping sync - not authenticated" }
            return
        }

        // Check connectivity
        if (!connectivityChecker.isOnline()) {
            Logger.d { "SyncTrigger: Skipping sync - offline" }
            return
        }

        // Check throttle (unless bypassed for workout complete)
        val now = Clock.System.now().toEpochMilliseconds()
        if (!bypassThrottle && (now - lastSyncAttemptMillis) < THROTTLE_MILLIS) {
            Logger.d { "SyncTrigger: Skipping sync - throttled" }
            return
        }

        // Attempt sync
        lastSyncAttemptMillis = now
        val result = syncManager.sync()

        if (result.isSuccess) {
            Logger.d { "SyncTrigger: Sync successful" }
            consecutiveFailures = 0
            _hasPersistentError.value = false
        } else {
            consecutiveFailures++
            Logger.w { "SyncTrigger: Sync failed (attempt $consecutiveFailures)" }
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                _hasPersistentError.value = true
                Logger.e { "SyncTrigger: Persistent error - $MAX_CONSECUTIVE_FAILURES consecutive failures" }
            }
        }
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/sync/SyncTriggerManager.kt
git commit -m "feat(sync): add SyncTriggerManager for automatic sync orchestration"
```

---

## Task 3: Wire ConnectivityChecker and SyncTriggerManager into Koin DI

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/AppModule.kt`
- Modify: `shared/src/androidMain/kotlin/com/devil/phoenixproject/di/PlatformModule.android.kt`
- Modify: `shared/src/iosMain/kotlin/com/devil/phoenixproject/di/PlatformModule.ios.kt`

**Step 1: Add ConnectivityChecker to Android platformModule**

In `PlatformModule.android.kt`, add after the CsvExporter line:

```kotlin
import com.devil.phoenixproject.util.ConnectivityChecker

// Inside the module block, add:
single { ConnectivityChecker(androidContext()) }
```

**Step 2: Add ConnectivityChecker to iOS platformModule**

In `PlatformModule.ios.kt`, add after the CsvExporter line:

```kotlin
import com.devil.phoenixproject.util.ConnectivityChecker

// Inside the module block, add:
single { ConnectivityChecker() }
```

**Step 3: Add SyncTriggerManager to commonModule**

In `AppModule.kt`, add the import and binding:

```kotlin
import com.devil.phoenixproject.data.sync.SyncTriggerManager

// In commonModule, after SyncManager line (around line 60), add:
single { SyncTriggerManager(get(), get()) }
```

**Step 4: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid :shared:compileKotlinIosArm64`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/di/AppModule.kt
git add shared/src/androidMain/kotlin/com/devil/phoenixproject/di/PlatformModule.android.kt
git add shared/src/iosMain/kotlin/com/devil/phoenixproject/di/PlatformModule.ios.kt
git commit -m "feat(sync): wire ConnectivityChecker and SyncTriggerManager into Koin DI"
```

---

## Task 4: Add Lifecycle Observer to App Composable

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/App.kt`

**Step 1: Add the lifecycle observer composable and integrate it**

Replace the entire `App.kt` file:

```kotlin
package com.devil.phoenixproject

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.presentation.screen.EnhancedMainScreen
import com.devil.phoenixproject.presentation.screen.SplashScreen
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.ThemeViewModel
import com.devil.phoenixproject.ui.theme.VitruvianTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.compose.koinInject

/**
 * Observes app lifecycle and triggers sync on foreground.
 */
@Composable
private fun AppLifecycleObserver(syncTriggerManager: SyncTriggerManager) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch {
                    syncTriggerManager.onAppForeground()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

@Composable
fun App() {
    val viewModel = koinViewModel<MainViewModel>()
    val themeViewModel = koinViewModel<ThemeViewModel>()
    val exerciseRepository = koinInject<ExerciseRepository>()
    val syncTriggerManager = koinInject<SyncTriggerManager>()

    // Theme state - persisted via ThemeViewModel
    val themeMode by themeViewModel.themeMode.collectAsState()

    // Splash screen state
    var showSplash by remember { mutableStateOf(true) }

    // Hide splash after animation completes (2500ms for full effect)
    LaunchedEffect(Unit) {
        delay(2500)
        showSplash = false
    }

    // Lifecycle observer for foreground sync
    AppLifecycleObserver(syncTriggerManager)

    VitruvianTheme(themeMode = themeMode) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Main content (always rendered, splash overlays it)
            if (!showSplash) {
                EnhancedMainScreen(
                    viewModel = viewModel,
                    exerciseRepository = exerciseRepository,
                    themeMode = themeMode,
                    onThemeModeChange = { themeViewModel.setThemeMode(it) }
                )
            }

            // Splash screen overlay with fade animation
            SplashScreen(visible = showSplash)
        }
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/App.kt
git commit -m "feat(sync): add lifecycle observer to trigger sync on app foreground"
```

---

## Task 5: Add Workout Complete Trigger to MainViewModel

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt`

**Step 1: Add SyncTriggerManager to MainViewModel constructor**

Find the MainViewModel class definition (around line 100) and add the new parameter.

Current constructor (approximately):
```kotlin
class MainViewModel(
    private val bleRepository: BleRepository,
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val gamificationRepository: GamificationRepository,
    private val preferencesManager: PreferencesManager,
    private val trainingCycleRepository: TrainingCycleRepository,
    private val repCounter: RepCounterFromMachine
) : ViewModel() {
```

Add `syncTriggerManager` as the last parameter:
```kotlin
class MainViewModel(
    private val bleRepository: BleRepository,
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val gamificationRepository: GamificationRepository,
    private val preferencesManager: PreferencesManager,
    private val trainingCycleRepository: TrainingCycleRepository,
    private val repCounter: RepCounterFromMachine,
    private val syncTriggerManager: SyncTriggerManager
) : ViewModel() {
```

**Step 2: Add import for SyncTriggerManager**

Add at the top of the file with other imports:
```kotlin
import com.devil.phoenixproject.data.sync.SyncTriggerManager
```

**Step 3: Add sync trigger after workout saves**

Find the `saveWorkoutSession` function (around line 2447 based on earlier grep).

After the line `workoutRepository.saveSession(session)`, add the sync trigger:
```kotlin
workoutRepository.saveSession(session)

// Trigger sync after workout saved
viewModelScope.launch {
    syncTriggerManager.onWorkoutCompleted()
}
```

Also find the other `saveSession` call (around line 979 for auto-complete) and add the same trigger:
```kotlin
workoutRepository.saveSession(session)

// Trigger sync after workout saved
viewModelScope.launch {
    syncTriggerManager.onWorkoutCompleted()
}
```

**Step 4: Update AppModule.kt to pass SyncTriggerManager to MainViewModel**

In `AppModule.kt`, find the MainViewModel factory (line 75) and add `get()` for the new parameter:

Current:
```kotlin
factory { MainViewModel(get(), get(), get(), get(), get(), get(), get(), get()) }
```

Updated (add one more `get()`):
```kotlin
factory { MainViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
```

**Step 5: Verify compilation**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/di/AppModule.kt
git commit -m "feat(sync): trigger sync on workout complete via MainViewModel"
```

---

## Task 6: Build and Test Full App

**Step 1: Build full Android app**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 2: Build iOS framework**

Run: `./gradlew :shared:compileKotlinIosArm64`
Expected: BUILD SUCCESSFUL

**Step 3: Final commit**

```bash
git add .
git commit -m "feat(sync): Phase 4 complete - automatic sync triggers"
```

---

## Summary

Phase 4 adds automatic sync triggers:

| Component | Purpose |
|-----------|---------|
| `ConnectivityChecker` (expect/actual) | Platform-specific network detection |
| `SyncTriggerManager` | Orchestrates auto-sync with throttling and failure tracking |
| `App.kt` lifecycle observer | Triggers sync on app foreground |
| `MainViewModel` integration | Triggers sync on workout complete |
| DI updates | Wires new components into Koin |

**Trigger behavior:**
- Workout complete: Always syncs (bypasses throttle)
- App foreground: Syncs if 5+ minutes since last attempt
- Both: Skip if offline or not authenticated

**Error handling:**
- Silent by default
- `hasPersistentError` exposed after 3 consecutive failures
- Can be observed by Settings UI if desired

**Next Phase:** Phase 5 could add sync status indicator in Settings and manual retry button.
