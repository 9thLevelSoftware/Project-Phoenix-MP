# Countdown Settings Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add two new configurable countdown settings to the Settings screen that dynamically control the Just Lift autostart timer and the post-workout summary timer.

**Architecture:** Extend the existing UserPreferences data class with two new integer fields, add corresponding persistence methods to PreferencesManager, create dropdown UI components in SettingsTab, and wire the values through to the existing countdown logic in MainViewModel and SetSummaryCard.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, multiplatform-settings library, StateFlow for reactive updates

---

## Task 1: Add Countdown Fields to UserPreferences Data Class

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/UserPreferences.kt`

**Step 1: Add the two new fields to the data class**

Open the file and add two new properties with defaults matching current hardcoded behavior:

```kotlin
data class UserPreferences(
    val weightUnit: WeightUnit = WeightUnit.LB,
    val autoplayEnabled: Boolean = true,
    val stopAtTop: Boolean = false,
    val enableVideoPlayback: Boolean = true,
    val beepsEnabled: Boolean = true,
    val colorScheme: Int = 0,
    val stallDetectionEnabled: Boolean = true,
    val discoModeUnlocked: Boolean = false,
    val audioRepCountEnabled: Boolean = false,
    // NEW: Countdown settings
    val summaryCountdownSeconds: Int = 10,      // 0-30 in 5s intervals, default 10
    val autoStartCountdownSeconds: Int = 5      // 2-10 in 1s intervals, default 5
)
```

**Step 2: Verify build succeeds**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/UserPreferences.kt
git commit -m "feat(settings): add countdown duration fields to UserPreferences"
```

---

## Task 2: Add Persistence Methods to PreferencesManager

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt`

**Step 1: Add storage key constants**

Find the companion object with KEY constants and add:

```kotlin
private const val KEY_SUMMARY_COUNTDOWN_SECONDS = "summary_countdown_seconds"
private const val KEY_AUTOSTART_COUNTDOWN_SECONDS = "autostart_countdown_seconds"
```

**Step 2: Add setter methods to the interface**

Find the `PreferencesManager` interface and add:

```kotlin
fun setSummaryCountdownSeconds(seconds: Int)
fun setAutoStartCountdownSeconds(seconds: Int)
```

**Step 3: Implement the setter methods in SettingsPreferencesManager**

Add these implementations following the existing pattern:

```kotlin
override fun setSummaryCountdownSeconds(seconds: Int) {
    settings.putInt(KEY_SUMMARY_COUNTDOWN_SECONDS, seconds)
    updateAndEmit()
}

override fun setAutoStartCountdownSeconds(seconds: Int) {
    settings.putInt(KEY_AUTOSTART_COUNTDOWN_SECONDS, seconds)
    updateAndEmit()
}
```

**Step 4: Update the loadPreferences() method**

Find the `loadPreferences()` method and add the new fields to the UserPreferences construction:

```kotlin
private fun loadPreferences(): UserPreferences {
    return UserPreferences(
        // ... existing fields ...
        summaryCountdownSeconds = settings.getInt(KEY_SUMMARY_COUNTDOWN_SECONDS, 10),
        autoStartCountdownSeconds = settings.getInt(KEY_AUTOSTART_COUNTDOWN_SECONDS, 5)
    )
}
```

**Step 5: Verify build succeeds**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt
git commit -m "feat(settings): add persistence for countdown duration settings"
```

---

## Task 3: Add ViewModel Methods for Countdown Settings

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt`

**Step 1: Add setter methods to MainViewModel**

Find the section with other preference setter methods (like `setAutoplayEnabled`) and add:

```kotlin
fun setSummaryCountdownSeconds(seconds: Int) {
    preferencesManager.setSummaryCountdownSeconds(seconds)
}

fun setAutoStartCountdownSeconds(seconds: Int) {
    preferencesManager.setAutoStartCountdownSeconds(seconds)
}
```

**Step 2: Verify build succeeds**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt
git commit -m "feat(settings): add ViewModel methods for countdown settings"
```

---

## Task 4: Create Countdown Dropdown Composable

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/CountdownDropdown.kt`

**Step 1: Create a reusable dropdown component**

Create a new file with a generic countdown dropdown that can be reused for both settings:

```kotlin
package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

@Composable
fun CountdownDropdown(
    label: String,
    selectedValue: Int,
    options: List<Int>,
    onValueSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    formatLabel: (Int) -> String = { "${it}s" }
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = formatLabel(selectedValue),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = formatLabel(option),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    },
                    onClick = {
                        onValueSelected(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                )
            }
        }
    }
}
```

**Step 2: Verify build succeeds**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/CountdownDropdown.kt
git commit -m "feat(ui): add reusable CountdownDropdown composable"
```

---

## Task 5: Add Countdown Settings UI to SettingsTab

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HistoryAndSettingsTabs.kt`

**Step 1: Add import for the new component**

At the top of the file, add:

```kotlin
import com.devil.phoenixproject.presentation.components.CountdownDropdown
```

**Step 2: Define the option lists as constants**

Near the top of the SettingsTab composable function, add:

```kotlin
// Countdown options
val summaryCountdownOptions = listOf(0, 5, 10, 15, 20, 25, 30)
val autoStartCountdownOptions = (2..10).toList()
```

**Step 3: Add Summary Countdown setting after Autoplay Routines toggle**

Find the Autoplay Routines toggle section (around line 1220-1270) and add the Summary Countdown dropdown immediately after it, inside the same card or as a new row. The Summary Countdown should only show when autoplayEnabled is true:

```kotlin
// Summary Countdown - only show when autoplay is enabled
if (userPreferences.autoplayEnabled) {
    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Summary Countdown",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Time before auto-advancing to next exercise",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.width(16.dp))

        CountdownDropdown(
            label = "",
            selectedValue = userPreferences.summaryCountdownSeconds,
            options = summaryCountdownOptions,
            onValueSelected = { viewModel.setSummaryCountdownSeconds(it) },
            modifier = Modifier.width(100.dp),
            formatLabel = { if (it == 0) "Off" else "${it}s" }
        )
    }
}
```

**Step 4: Add Autostart Countdown setting in a new section or after the Summary Countdown**

Add another dropdown for the autostart countdown (this one always visible since it applies to Just Lift mode regardless of autoplay):

```kotlin
Spacer(modifier = Modifier.height(16.dp))

Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically
) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = "Autostart Countdown",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Just Lift countdown when handles are grabbed",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(modifier = Modifier.width(16.dp))

    CountdownDropdown(
        label = "",
        selectedValue = userPreferences.autoStartCountdownSeconds,
        options = autoStartCountdownOptions,
        onValueSelected = { viewModel.setAutoStartCountdownSeconds(it) },
        modifier = Modifier.width(100.dp)
    )
}
```

**Step 5: Verify build succeeds**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HistoryAndSettingsTabs.kt
git commit -m "feat(settings): add countdown duration dropdowns to Settings UI"
```

---

## Task 6: Update Just Lift Autostart Countdown Logic

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt`

**Step 1: Find the startAutoStartCountdown method**

Locate the `startAutoStartCountdown()` method that has the hardcoded `for (i in 5 downTo 1)` loop.

**Step 2: Replace hardcoded value with preference**

Change the countdown to use the preference value:

```kotlin
fun startAutoStartCountdown() {
    cancelAutoStartCountdown()
    val countdownSeconds = _userPreferences.value.autoStartCountdownSeconds
    autoStartJob = viewModelScope.launch {
        for (i in countdownSeconds downTo 1) {
            _autoStartCountdown.value = i
            delay(1000)
        }
        _autoStartCountdown.value = null
        // ... rest of the existing logic for starting workout
    }
}
```

**Step 3: Verify build succeeds**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt
git commit -m "feat(workout): use configurable autostart countdown duration"
```

---

## Task 7: Update SetSummaryCard Countdown Logic

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt`

**Step 1: Locate the SetSummaryCard composable**

Find the SetSummaryCard composable (around line 1416-1693).

**Step 2: Add summaryCountdownSeconds parameter**

Update the function signature to accept the countdown duration:

```kotlin
@Composable
fun SetSummaryCard(
    // ... existing parameters ...
    autoplayEnabled: Boolean,
    summaryCountdownSeconds: Int,  // NEW PARAMETER
    onContinue: () -> Unit,
    // ... rest of parameters ...
)
```

**Step 3: Update the countdown initialization**

Find the line:
```kotlin
var autoCountdown by remember { mutableStateOf(if (autoplayEnabled) 10 else -1) }
```

Change it to:
```kotlin
var autoCountdown by remember { mutableStateOf(if (autoplayEnabled && summaryCountdownSeconds > 0) summaryCountdownSeconds else -1) }
```

**Step 4: Update the LaunchedEffect**

Find the LaunchedEffect that handles the countdown and update it:

```kotlin
LaunchedEffect(autoplayEnabled, summaryCountdownSeconds) {
    if (autoplayEnabled && summaryCountdownSeconds > 0) {
        autoCountdown = summaryCountdownSeconds
        while (autoCountdown > 0) {
            kotlinx.coroutines.delay(1000)
            autoCountdown--
        }
        if (autoCountdown == 0) {
            onContinue()
        }
    }
}
```

**Step 5: Update the button text logic (if summaryCountdownSeconds is 0, never show countdown)**

Find the button text logic and update:
```kotlin
text = if (autoplayEnabled && summaryCountdownSeconds > 0 && autoCountdown > 0) {
    "Done ($autoCountdown)"
} else {
    "Done"
}
```

**Step 6: Verify build succeeds**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL (may have errors due to call sites not updated yet)

**Step 7: Commit (if builds)**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt
git commit -m "feat(workout): add summaryCountdownSeconds parameter to SetSummaryCard"
```

---

## Task 8: Update All SetSummaryCard Call Sites

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt` (and any other files that call SetSummaryCard)

**Step 1: Search for all usages of SetSummaryCard**

Use grep or IDE search to find all places where SetSummaryCard is called.

**Step 2: Update each call site to pass summaryCountdownSeconds**

At each call site, add the new parameter. The value should come from userPreferences:

```kotlin
SetSummaryCard(
    // ... existing parameters ...
    autoplayEnabled = userPreferences.autoplayEnabled,
    summaryCountdownSeconds = userPreferences.summaryCountdownSeconds,  // ADD THIS
    onContinue = { /* ... */ },
    // ... rest of parameters ...
)
```

**Step 3: Verify build succeeds**

Run: `./gradlew :shared:compileKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add .
git commit -m "feat(workout): wire summaryCountdownSeconds to all SetSummaryCard usages"
```

---

## Task 9: Full Build and Integration Test

**Files:**
- None (testing only)

**Step 1: Run full Android build**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 2: Install and test on device/emulator**

Run: `./gradlew :androidApp:installDebug`

**Step 3: Manual testing checklist**

1. **Settings Screen:**
   - [ ] Summary Countdown dropdown appears under Autoplay Routines (only when enabled)
   - [ ] Summary Countdown shows options: Off, 5s, 10s, 15s, 20s, 25s, 30s
   - [ ] Default is 10s
   - [ ] Autostart Countdown dropdown always visible
   - [ ] Autostart Countdown shows options: 2s through 10s
   - [ ] Default is 5s
   - [ ] Both selections persist after app restart

2. **Just Lift Mode:**
   - [ ] Set Autostart Countdown to 3s in Settings
   - [ ] Go to Just Lift mode
   - [ ] Grab handles
   - [ ] Countdown should be 3-2-1 (not 5-4-3-2-1)

3. **Post-Workout Summary:**
   - [ ] Enable Autoplay Routines
   - [ ] Set Summary Countdown to 5s
   - [ ] Complete a set
   - [ ] Summary card button should show "Done (5)" counting down
   - [ ] Set Summary Countdown to "Off" (0)
   - [ ] Complete a set
   - [ ] Summary card should NOT auto-advance (button just shows "Done")

**Step 4: Commit final changes if any fixes needed**

```bash
git add .
git commit -m "fix: address integration test findings"
```

---

## Task 10: Final Commit and Summary

**Step 1: Ensure all changes are committed**

Run: `git status`
Expected: "nothing to commit, working tree clean"

**Step 2: Create summary commit (optional squash)**

If you want a single feature commit:
```bash
git rebase -i HEAD~9
# squash all commits into one
```

Or leave as separate commits for better git history.

**Step 3: Document completion**

The feature is complete when:
- [ ] UserPreferences has `summaryCountdownSeconds` and `autoStartCountdownSeconds`
- [ ] PreferencesManager persists both values
- [ ] MainViewModel exposes setter methods
- [ ] SettingsTab shows both dropdowns
- [ ] Just Lift autostart uses configurable countdown
- [ ] SetSummaryCard uses configurable countdown
- [ ] All manual tests pass

---

## File Reference Summary

| File | Changes |
|------|---------|
| `domain/model/UserPreferences.kt` | Add 2 new Int fields |
| `data/preferences/PreferencesManager.kt` | Add 2 keys, 2 interface methods, 2 implementations, update loadPreferences |
| `presentation/viewmodel/MainViewModel.kt` | Add 2 setter methods, update startAutoStartCountdown to use preference |
| `presentation/components/CountdownDropdown.kt` | NEW FILE - reusable dropdown |
| `presentation/screen/HistoryAndSettingsTabs.kt` | Add 2 dropdown UIs in SettingsTab |
| `presentation/screen/WorkoutTab.kt` | Add parameter to SetSummaryCard, update countdown logic |

---

## Notes

- **Summary Countdown = 0** means "Off" - no auto-advance even with Autoplay enabled
- **Autostart Countdown** minimum is 2 seconds for safety
- Both settings persist via multiplatform-settings (SharedPreferences on Android, NSUserDefaults on iOS)
- The CountdownDropdown component can be reused for future similar settings
