# Multi-Feature Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement four features: stall detection settings toggle, eccentric load granularity, hybrid post-set sliders, and multiple user profiles.

**Architecture:** Features are implemented in dependency order - simplest first (settings toggle), then enum changes, then new UI component, finally the complex profile system with database changes.

**Tech Stack:** Kotlin Multiplatform, Compose Multiplatform, SQLDelight, multiplatform-settings, Koin DI

---

## Task 1: Stall Detection - Add to UserPreferences

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt`

**Step 1: Add stallDetectionEnabled to UserPreferences data class**

In `PreferencesManager.kt`, update the `UserPreferences` data class (around line 15-22):

```kotlin
data class UserPreferences(
    val weightUnit: WeightUnit = WeightUnit.LB,
    val autoplayEnabled: Boolean = true,
    val stopAtTop: Boolean = false,
    val enableVideoPlayback: Boolean = true,
    val beepsEnabled: Boolean = true,
    val colorScheme: Int = 0,
    val stallDetectionEnabled: Boolean = true  // NEW - default enabled
)
```

**Step 2: Add interface method**

In the `PreferencesManager` interface (around line 110-127), add:

```kotlin
suspend fun setStallDetectionEnabled(enabled: Boolean)
```

**Step 3: Add constant and implementation in SettingsPreferencesManager**

Add constant in companion object (around line 144-154):

```kotlin
private const val KEY_STALL_DETECTION = "stall_detection_enabled"
```

Update `loadPreferences()` to include stall detection:

```kotlin
private fun loadPreferences(): UserPreferences {
    return UserPreferences(
        weightUnit = settings.getStringOrNull(KEY_WEIGHT_UNIT)?.let {
            WeightUnit.entries.find { unit -> unit.name == it }
        } ?: WeightUnit.LB,
        autoplayEnabled = settings.getBoolean(KEY_AUTOPLAY_ENABLED, true),
        stopAtTop = settings.getBoolean(KEY_STOP_AT_TOP, false),
        enableVideoPlayback = settings.getBoolean(KEY_VIDEO_PLAYBACK, true),
        beepsEnabled = settings.getBoolean(KEY_BEEPS_ENABLED, true),
        colorScheme = settings.getInt(KEY_COLOR_SCHEME, 0),
        stallDetectionEnabled = settings.getBoolean(KEY_STALL_DETECTION, true)
    )
}
```

Add the setter method after `setColorScheme`:

```kotlin
override suspend fun setStallDetectionEnabled(enabled: Boolean) {
    settings.putBoolean(KEY_STALL_DETECTION, enabled)
    updateAndEmit { copy(stallDetectionEnabled = enabled) }
}
```

**Step 4: Update StubPreferencesManager**

Add to `StubPreferencesManager` class:

```kotlin
override suspend fun setStallDetectionEnabled(enabled: Boolean) {
    _preferencesFlow.value = _preferencesFlow.value.copy(stallDetectionEnabled = enabled)
}
```

**Step 5: Build to verify**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt
git commit -m "feat: add stallDetectionEnabled to UserPreferences"
```

---

## Task 2: Stall Detection - Add Toggle to Settings UI

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HistoryAndSettingsTabs.kt`

**Step 1: Add parameters to SettingsTab**

Update the `SettingsTab` function signature (around line 772-794) to add:

```kotlin
@Composable
fun SettingsTab(
    weightUnit: WeightUnit,
    autoplayEnabled: Boolean,
    stopAtTop: Boolean,
    enableVideoPlayback: Boolean,
    darkModeEnabled: Boolean,
    stallDetectionEnabled: Boolean = true,  // NEW
    selectedColorSchemeIndex: Int = 0,
    onWeightUnitChange: (WeightUnit) -> Unit,
    onAutoplayChange: (Boolean) -> Unit,
    onStopAtTopChange: (Boolean) -> Unit,
    onEnableVideoPlaybackChange: (Boolean) -> Unit,
    onDarkModeChange: (Boolean) -> Unit,
    onStallDetectionChange: (Boolean) -> Unit,  // NEW
    onColorSchemeChange: (Int) -> Unit,
    // ... rest of parameters
)
```

**Step 2: Add toggle UI after "Show Exercise Videos" toggle**

Find the "Show Exercise Videos" toggle (around line 1152-1178) and add after it:

```kotlin
Spacer(modifier = Modifier.height(Spacing.medium))

// Stall Detection toggle
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
) {
    Column(
        modifier = Modifier.weight(1f)
    ) {
        Text(
            "Stall Detection",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Auto-stop set when movement pauses for 5 seconds (Just Lift/AMRAP)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    Switch(
        checked = stallDetectionEnabled,
        onCheckedChange = onStallDetectionChange
    )
}
```

**Step 3: Build to verify**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HistoryAndSettingsTabs.kt
git commit -m "feat: add stall detection toggle to Settings UI"
```

---

## Task 3: Stall Detection - Wire Up and Remove from JustLift

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt`

**Step 1: Find where SettingsTab is called in EnhancedMainScreen.kt and add the new parameters**

Search for `SettingsTab(` and update to include:

```kotlin
stallDetectionEnabled = preferences.stallDetectionEnabled,
onStallDetectionChange = { enabled ->
    scope.launch { preferencesManager.setStallDetectionEnabled(enabled) }
},
```

**Step 2: Remove Stall Detection toggle from JustLiftScreen**

In `JustLiftScreen.kt`, remove the entire Stall Detection Card (approximately lines 260-293):

Delete this block:
```kotlin
// Stall Detection Toggle Card
Card(
    modifier = Modifier.fillMaxWidth(),
    // ... entire card through closing brace
) {
    // ... all content
}
```

**Step 3: Update JustLiftScreen to read stall detection from preferences**

The screen already has access to `stallDetectionEnabled` via `workoutParameters`. Verify it reads from the viewModel's preference-backed value rather than local state.

In the `LaunchedEffect` that loads defaults (around line 84-114), ensure it uses the preference value:

```kotlin
// Remove this local state variable if it exists as override:
// var stallDetectionEnabled by remember { mutableStateOf(true) }

// Instead, read from viewModel or pass via parameters
```

**Step 4: Build to verify**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt
git commit -m "feat: wire stall detection to Settings, remove from JustLift"
```

---

## Task 4: Eccentric Load - Update Enum Values

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt`

**Step 1: Update EccentricLoad enum**

Replace the existing enum (around lines 196-203):

```kotlin
/**
 * Eccentric load percentage for Echo mode
 * Machine hardware limit: 150% maximum
 */
enum class EccentricLoad(val percentage: Int, val displayName: String) {
    LOAD_0(0, "0%"),
    LOAD_50(50, "50%"),
    LOAD_75(75, "75%"),
    LOAD_100(100, "100%"),
    LOAD_110(110, "110%"),
    LOAD_120(120, "120%"),
    LOAD_130(130, "130%"),
    LOAD_140(140, "140%"),
    LOAD_150(150, "150%")
}
```

**Step 2: Build to verify**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt
git commit -m "feat: add granular eccentric load values (110%, 120%, 130%, 140%)"
```

---

## Task 5: Eccentric Load - Update PreferencesManager Fallback

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt`

**Step 1: Update getEccentricLoad() in SingleExerciseDefaults**

Find `getEccentricLoad()` in `SingleExerciseDefaults` (around line 48-51) and update:

```kotlin
fun getEccentricLoad(): com.devil.phoenixproject.domain.model.EccentricLoad {
    // Handle legacy 125% -> fall back to 120%
    val percentage = if (eccentricLoadPercentage == 125) 120 else eccentricLoadPercentage
    return com.devil.phoenixproject.domain.model.EccentricLoad.entries.find { it.percentage == percentage }
        ?: com.devil.phoenixproject.domain.model.EccentricLoad.LOAD_100
}
```

**Step 2: Update getEccentricLoad() in JustLiftDefaults**

Find `getEccentricLoad()` in `JustLiftDefaults` (around line 83-86) and update:

```kotlin
fun getEccentricLoad(): com.devil.phoenixproject.domain.model.EccentricLoad {
    // Handle legacy 125% -> fall back to 120%
    val percentage = if (eccentricLoadPercentage == 125) 120 else eccentricLoadPercentage
    return com.devil.phoenixproject.domain.model.EccentricLoad.entries.find { it.percentage == percentage }
        ?: com.devil.phoenixproject.domain.model.EccentricLoad.LOAD_100
}
```

**Step 3: Build to verify**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/preferences/PreferencesManager.kt
git commit -m "fix: handle legacy 125% eccentric load fallback to 120%"
```

---

## Task 6: Eccentric Load - Replace Slider with Dropdown in JustLiftScreen

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt`

**Step 1: Add imports**

Add at top of file:

```kotlin
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
```

**Step 2: Replace eccentric load slider with dropdown**

Find the Eccentric Load Card (around lines 374-432) and replace the slider section with:

```kotlin
// Eccentric Load Card
Card(
    modifier = Modifier
        .fillMaxWidth()
        .weight(1f),
    colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ),
    shape = RoundedCornerShape(16.dp)
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.medium),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        Text(
            "Eccentric Load",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        var expanded by remember { mutableStateOf(false) }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = eccentricLoad.displayName,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors()
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                EccentricLoad.entries.forEach { load ->
                    DropdownMenuItem(
                        text = { Text(load.displayName) },
                        onClick = {
                            eccentricLoad = load
                            expanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }

        Text(
            "Load during eccentric (lowering) phase",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
```

**Step 3: Add mutableStateOf import if needed**

Ensure this import exists:
```kotlin
import androidx.compose.runtime.mutableStateOf
```

**Step 4: Build to verify**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt
git commit -m "feat: replace eccentric load slider with dropdown picker"
```

---

## Task 7: Post-Set Sliders - Create SliderWithButtons Component

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/SliderWithButtons.kt`

**Step 1: Create the new component file**

```kotlin
package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.ui.theme.Spacing

/**
 * Hybrid slider with fine-tuning +/- buttons
 * Slider for coarse adjustment, buttons for precise increments
 */
@Composable
fun SliderWithButtons(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    label: String,
    formatValue: (Float) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.small)
    ) {
        // Label and current value
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = formatValue(value),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Slider with +/- buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small)
        ) {
            // Decrease button
            FilledIconButton(
                onClick = {
                    val newValue = (value - step).coerceIn(valueRange)
                    onValueChange(newValue)
                },
                modifier = Modifier.size(36.dp),
                enabled = enabled && value > valueRange.start,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = CircleShape
            ) {
                Icon(
                    Icons.Default.Remove,
                    contentDescription = "Decrease",
                    modifier = Modifier.size(18.dp)
                )
            }

            // Slider
            ExpressiveSlider(
                value = value,
                onValueChange = onValueChange,
                valueRange = valueRange,
                modifier = Modifier.weight(1f),
                enabled = enabled
            )

            // Increase button
            FilledIconButton(
                onClick = {
                    val newValue = (value + step).coerceIn(valueRange)
                    onValueChange(newValue)
                },
                modifier = Modifier.size(36.dp),
                enabled = enabled && value < valueRange.endInclusive,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                ),
                shape = CircleShape
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Increase",
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
```

**Step 2: Build to verify**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/SliderWithButtons.kt
git commit -m "feat: add SliderWithButtons hybrid component"
```

---

## Task 8: Post-Set Sliders - Update RestTimerCard

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RestTimerCard.kt`

**Step 1: Add import**

```kotlin
import com.devil.phoenixproject.presentation.components.SliderWithButtons
```

**Step 2: Replace ParameterAdjuster for reps with SliderWithButtons**

Find the reps adjuster section (around lines 225-236) and replace with:

```kotlin
// Reps adjuster with hybrid slider
if (nextExerciseReps != null) {
    SliderWithButtons(
        value = editedReps.toFloat(),
        onValueChange = { newValue ->
            editedReps = newValue.toInt().coerceIn(1, 50)
            onUpdateReps?.invoke(editedReps)
        },
        valueRange = 1f..50f,
        step = 1f,
        label = "Target Reps",
        formatValue = { it.toInt().toString() }
    )
}
```

**Step 3: Replace WeightAdjustmentControls with SliderWithButtons**

Find the weight adjustment section (around lines 239-276) and replace with:

```kotlin
// Weight adjuster with hybrid slider
if (nextExerciseWeight != null && formatWeightWithUnit != null) {
    val maxWeight = if (weightUnit == WeightUnit.LB) 220f else 100f
    val weightStep = if (weightUnit == WeightUnit.LB) 1f else 0.5f

    SliderWithButtons(
        value = editedWeight,
        onValueChange = { newWeight ->
            editedWeight = newWeight.coerceIn(0f, maxWeight)
            onUpdateWeight?.invoke(editedWeight)
        },
        valueRange = 0f..maxWeight,
        step = weightStep,
        label = "Weight per cable",
        formatValue = { formatWeightWithUnit(it, weightUnit) }
    )
}
```

**Step 4: Remove unused imports**

Remove if no longer needed:
```kotlin
// Remove: import com.devil.phoenixproject.presentation.components.WeightAdjustmentControls
```

**Step 5: Remove the private ParameterAdjuster composable**

Delete the `ParameterAdjuster` function (around lines 381-445) if it's no longer used elsewhere.

**Step 6: Build to verify**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL

**Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RestTimerCard.kt
git commit -m "feat: use SliderWithButtons for post-set weight/reps adjustment"
```

---

## Task 9: User Profiles - Create Database Schema

**Files:**
- Modify: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/VitruvianDatabase.sq`

**Step 1: Add UserProfile table**

Add at the end of the schema file:

```sql
-- User Profiles for multi-user support
CREATE TABLE UserProfile (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    colorIndex INTEGER NOT NULL DEFAULT 0,
    createdAt INTEGER NOT NULL,
    isActive INTEGER NOT NULL DEFAULT 0
);

-- Create default profile if none exists
INSERT OR IGNORE INTO UserProfile (id, name, colorIndex, createdAt, isActive)
VALUES ('default', 'Default', 0, 0, 1);

-- Queries for UserProfile
getActiveProfile:
SELECT * FROM UserProfile WHERE isActive = 1 LIMIT 1;

getAllProfiles:
SELECT * FROM UserProfile ORDER BY createdAt ASC;

getProfileById:
SELECT * FROM UserProfile WHERE id = ?;

insertProfile:
INSERT INTO UserProfile (id, name, colorIndex, createdAt, isActive)
VALUES (?, ?, ?, ?, ?);

updateProfile:
UPDATE UserProfile SET name = ?, colorIndex = ? WHERE id = ?;

setActiveProfile:
UPDATE UserProfile SET isActive = CASE WHEN id = ? THEN 1 ELSE 0 END;

deleteProfile:
DELETE FROM UserProfile WHERE id = ? AND id != 'default';

countProfiles:
SELECT COUNT(*) FROM UserProfile;
```

**Step 2: Add profileId to WorkoutSession table**

Find the WorkoutSession table definition and add:

```sql
-- Add profileId column (nullable for migration)
ALTER TABLE WorkoutSession ADD COLUMN profileId TEXT DEFAULT 'default';
```

Note: For SQLDelight, you may need to handle this with a migration file. Check existing migration patterns in the project.

**Step 3: Build to verify**

Run: `./gradlew :shared:generateSqlDelightInterface`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/sqldelight/
git commit -m "feat: add UserProfile table schema"
```

---

## Task 10: User Profiles - Create Repository

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/UserProfileRepository.kt`

**Step 1: Create the repository**

```kotlin
package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.VitruvianDatabase
import com.devil.phoenixproject.domain.model.generateUUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserProfile(
    val id: String,
    val name: String,
    val colorIndex: Int,
    val createdAt: Long,
    val isActive: Boolean
)

interface UserProfileRepository {
    val activeProfile: StateFlow<UserProfile?>
    val allProfiles: StateFlow<List<UserProfile>>

    suspend fun createProfile(name: String, colorIndex: Int): UserProfile
    suspend fun updateProfile(id: String, name: String, colorIndex: Int)
    suspend fun deleteProfile(id: String): Boolean
    suspend fun setActiveProfile(id: String)
    suspend fun refreshProfiles()
}

class SqlDelightUserProfileRepository(
    private val database: VitruvianDatabase
) : UserProfileRepository {

    private val queries = database.vitruvianDatabaseQueries

    private val _activeProfile = MutableStateFlow<UserProfile?>(null)
    override val activeProfile: StateFlow<UserProfile?> = _activeProfile.asStateFlow()

    private val _allProfiles = MutableStateFlow<List<UserProfile>>(emptyList())
    override val allProfiles: StateFlow<List<UserProfile>> = _allProfiles.asStateFlow()

    init {
        refreshProfilesSync()
    }

    private fun refreshProfilesSync() {
        val profiles = queries.getAllProfiles().executeAsList().map { it.toUserProfile() }
        _allProfiles.value = profiles
        _activeProfile.value = profiles.find { it.isActive }
    }

    override suspend fun refreshProfiles() {
        refreshProfilesSync()
    }

    override suspend fun createProfile(name: String, colorIndex: Int): UserProfile {
        val id = generateUUID()
        val createdAt = System.currentTimeMillis()
        queries.insertProfile(id, name, colorIndex.toLong(), createdAt, 0)
        refreshProfilesSync()
        return UserProfile(id, name, colorIndex, createdAt, false)
    }

    override suspend fun updateProfile(id: String, name: String, colorIndex: Int) {
        queries.updateProfile(name, colorIndex.toLong(), id)
        refreshProfilesSync()
    }

    override suspend fun deleteProfile(id: String): Boolean {
        if (id == "default") return false
        val wasActive = _activeProfile.value?.id == id
        queries.deleteProfile(id)
        if (wasActive) {
            queries.setActiveProfile("default")
        }
        refreshProfilesSync()
        return true
    }

    override suspend fun setActiveProfile(id: String) {
        queries.setActiveProfile(id)
        refreshProfilesSync()
    }

    private fun com.devil.phoenixproject.UserProfile.toUserProfile(): UserProfile {
        return UserProfile(
            id = id,
            name = name,
            colorIndex = colorIndex.toInt(),
            createdAt = createdAt,
            isActive = isActive == 1L
        )
    }
}
```

**Step 2: Build to verify**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/UserProfileRepository.kt
git commit -m "feat: add UserProfileRepository"
```

---

## Task 11: User Profiles - Create Speed Dial FAB Component

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSpeedDial.kt`

**Step 1: Create the component**

```kotlin
package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.ui.theme.Spacing

// Profile color palette
val ProfileColors = listOf(
    Color(0xFF3B82F6), // Blue
    Color(0xFF10B981), // Green
    Color(0xFFF59E0B), // Amber
    Color(0xFFEF4444), // Red
    Color(0xFF8B5CF6), // Purple
    Color(0xFFEC4899), // Pink
    Color(0xFF06B6D4), // Cyan
    Color(0xFFF97316)  // Orange
)

@Composable
fun ProfileSpeedDial(
    profiles: List<UserProfile>,
    activeProfile: UserProfile?,
    onProfileSelected: (UserProfile) -> Unit,
    onAddProfile: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 45f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "rotation"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomEnd
    ) {
        // Expanded menu items
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
        ) {
            Column(
                modifier = Modifier.padding(bottom = 64.dp, end = 4.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(Spacing.small)
            ) {
                // Add profile button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small)
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shadowElevation = 4.dp
                    ) {
                        Text(
                            "Add Profile",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    FloatingActionButton(
                        onClick = {
                            expanded = false
                            onAddProfile()
                        },
                        modifier = Modifier.size(40.dp),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add profile")
                    }
                }

                // Profile list
                profiles.forEach { profile ->
                    val isActive = profile.id == activeProfile?.id
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shadowElevation = 4.dp
                        ) {
                            Text(
                                profile.name,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                color = if (isActive) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        ProfileAvatar(
                            profile = profile,
                            isActive = isActive,
                            onClick = {
                                expanded = false
                                onProfileSelected(profile)
                            },
                            size = 40
                        )
                    }
                }
            }
        }

        // Main FAB showing active profile
        FloatingActionButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.size(56.dp),
            containerColor = if (expanded) MaterialTheme.colorScheme.surfaceContainerHighest
                            else ProfileColors.getOrElse(activeProfile?.colorIndex ?: 0) { ProfileColors[0] },
            contentColor = Color.White
        ) {
            if (expanded) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close menu",
                    modifier = Modifier.rotate(rotation),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            } else {
                Text(
                    text = activeProfile?.name?.take(1)?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun ProfileAvatar(
    profile: UserProfile,
    isActive: Boolean,
    onClick: () -> Unit,
    size: Int = 40,
    modifier: Modifier = Modifier
) {
    val color = ProfileColors.getOrElse(profile.colorIndex) { ProfileColors[0] }

    Surface(
        onClick = onClick,
        modifier = modifier
            .size(size.dp)
            .shadow(if (isActive) 8.dp else 4.dp, CircleShape),
        shape = CircleShape,
        color = color,
        border = if (isActive) androidx.compose.foundation.BorderStroke(
            2.dp, MaterialTheme.colorScheme.primary
        ) else null
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = profile.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}
```

**Step 2: Build to verify**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileSpeedDial.kt
git commit -m "feat: add ProfileSpeedDial FAB component"
```

---

## Task 12: User Profiles - Register in DI and Wire to UI

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/AppModule.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt`

**Step 1: Add repository to Koin module**

In `AppModule.kt`, add:

```kotlin
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.SqlDelightUserProfileRepository

// In the module definition, add:
single<UserProfileRepository> { SqlDelightUserProfileRepository(get()) }
```

**Step 2: Add ProfileSpeedDial to EnhancedMainScreen**

Add imports:
```kotlin
import com.devil.phoenixproject.presentation.components.ProfileSpeedDial
import com.devil.phoenixproject.data.repository.UserProfileRepository
```

Inject the repository and add the FAB to the Scaffold:

```kotlin
// Get repository
val profileRepository: UserProfileRepository = koinInject()
val profiles by profileRepository.allProfiles.collectAsState()
val activeProfile by profileRepository.activeProfile.collectAsState()

// Add state for profile dialog
var showAddProfileDialog by remember { mutableStateOf(false) }

// In Scaffold, add floatingActionButton:
Scaffold(
    // ... existing parameters
    floatingActionButton = {
        ProfileSpeedDial(
            profiles = profiles,
            activeProfile = activeProfile,
            onProfileSelected = { profile ->
                scope.launch { profileRepository.setActiveProfile(profile.id) }
            },
            onAddProfile = { showAddProfileDialog = true }
        )
    }
) { ... }

// Add dialog for creating new profile (simple implementation)
if (showAddProfileDialog) {
    var newProfileName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { showAddProfileDialog = false },
        title = { Text("Add Profile") },
        text = {
            OutlinedTextField(
                value = newProfileName,
                onValueChange = { newProfileName = it },
                label = { Text("Name") },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (newProfileName.isNotBlank()) {
                        scope.launch {
                            val colorIndex = profiles.size % 8
                            profileRepository.createProfile(newProfileName.trim(), colorIndex)
                        }
                        showAddProfileDialog = false
                    }
                }
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = { showAddProfileDialog = false }) {
                Text("Cancel")
            }
        }
    )
}
```

**Step 3: Build to verify**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/di/AppModule.kt
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt
git commit -m "feat: wire ProfileSpeedDial to main screen with DI"
```

---

## Task 13: Final Integration - Add Profile to JustLiftScreen

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt`

**Step 1: Add ProfileSpeedDial to JustLiftScreen**

Follow the same pattern as EnhancedMainScreen - add the ProfileSpeedDial FAB to the Scaffold.

**Step 2: Build full project**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/JustLiftScreen.kt
git commit -m "feat: add ProfileSpeedDial to JustLiftScreen"
```

---

## Task 14: Final Verification

**Step 1: Run full build**

```bash
./gradlew clean build
```

**Step 2: Run tests**

```bash
./gradlew :shared:allTests
```

**Step 3: Verify on device/emulator**

Test each feature:
- [ ] Stall detection toggle appears in Settings
- [ ] Stall detection toggle removed from Just Lift
- [ ] Eccentric load dropdown shows all 9 values
- [ ] Post-set sliders work with hybrid controls
- [ ] Profile FAB appears on Home and Just Lift
- [ ] Profile switching works
- [ ] New profile creation works

**Step 4: Final commit**

```bash
git add .
git commit -m "chore: final cleanup and verification"
```

---

## Summary

| Task | Feature | Status |
|------|---------|--------|
| 1-3 | Stall Detection Settings | |
| 4-6 | Eccentric Load Granularity | |
| 7-8 | Post-Set Sliders | |
| 9-13 | User Profiles | |
| 14 | Final Verification | |
