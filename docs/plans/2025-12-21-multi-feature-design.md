# Multi-Feature Design: Profiles, Sliders, Eccentric Load, Stall Detection

**Date:** 2025-12-21
**Status:** Approved

---

## Overview

This document covers the design for four features:

1. **Multiple User Profiles** - Profile switching with isolated stats
2. **Post-Set Adjustment Sliders** - Hybrid slider + button controls
3. **Eccentric Load Granularity** - Expanded percentage options with dropdown UI
4. **Stall Detection Feature Flag** - Move toggle from Just Lift to Settings

---

## Feature 1: Multiple User Profiles

### Data Model

```kotlin
data class UserProfile(
    val id: String,          // UUID
    val name: String,        // Display name
    val colorIndex: Int,     // 0-7 for 8 preset colors
    val createdAt: Long,
    val isActive: Boolean    // Currently selected profile
)
```

### Data Isolation

| Data Type | Scope |
|-----------|-------|
| Workout History | Per-profile |
| Personal Records | Per-profile |
| Badges/Achievements | Per-profile |
| Exercise Defaults | Per-profile |
| Routines | **Shared globally** |
| Settings (theme, weight unit, etc.) | **Shared globally** |

### UI Components

**Profile Speed Dial FAB** (bottom-right on Home & Just Lift screens):
- Collapsed: Shows current user's colored initial (e.g., blue "J" for John)
- Expanded: Fans out showing all profiles + "Add Profile" button
- Tapping a profile switches immediately

**Profile Management** in Settings:
- Add new profile
- Rename existing profile
- Delete profile (with confirmation)

### Database Changes

New SQLDelight table:
```sql
CREATE TABLE UserProfile (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    colorIndex INTEGER NOT NULL DEFAULT 0,
    createdAt INTEGER NOT NULL,
    isActive INTEGER NOT NULL DEFAULT 0
);
```

Add `profileId` foreign key to:
- `WorkoutSession`
- `PersonalRecord`
- `Badge` / gamification tables
- Exercise defaults (in PreferencesManager, keyed by `profileId_exerciseId_cableConfig`)

### Files to Create/Modify

**New files:**
- `shared/src/commonMain/sqldelight/.../UserProfile.sq`
- `shared/src/commonMain/kotlin/.../data/repository/UserProfileRepository.kt`
- `shared/src/commonMain/kotlin/.../presentation/components/ProfileSpeedDial.kt`
- `shared/src/commonMain/kotlin/.../presentation/viewmodel/ProfileViewModel.kt`

**Modified files:**
- `VitruvianDatabase.sq` - Add UserProfile table, add profileId to existing tables
- `WorkoutRepository.kt` - Filter by active profile
- `PersonalRecordRepository.kt` - Filter by active profile
- `GamificationRepository.kt` - Filter by active profile
- `PreferencesManager.kt` - Scope exercise defaults by profileId
- `EnhancedMainScreen.kt` - Add ProfileSpeedDial FAB
- `JustLiftScreen.kt` - Add ProfileSpeedDial FAB
- `HistoryAndSettingsTabs.kt` - Add profile management section

---

## Feature 2: Post-Set Weight/Rest Sliders

### Current State

`RestTimerCard.kt` uses button-based controls:
- `ParameterAdjuster` with +/- buttons for reps
- `WeightAdjustmentControls` with +/- buttons for weight

### New Implementation

Hybrid approach matching parent repo style with fine-tuning buttons:

```
┌─────────────────────────────────────────┐
│  ADJUST FOR NEXT SET                    │
├─────────────────────────────────────────┤
│  Weight: 25 kg/cable                    │
│  [-] ════════●══════════════════ [+]   │
│                                         │
│  Target Reps: 12                        │
│  [-] ══════════════●════════════ [+]   │
└─────────────────────────────────────────┘
```

### New Component: SliderWithButtons

```kotlin
@Composable
fun SliderWithButtons(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,              // Fine-tuning increment for buttons
    label: String,
    formatValue: (Float) -> String,
    modifier: Modifier = Modifier
)
```

- `ExpressiveSlider` in center for coarse adjustment (drag)
- Small `IconButton` with `-` on left, `+` on right for fine-tuning
- Weight: ±0.5kg (or ±1lb based on unit preference)
- Reps: ±1 per tap

### Files to Create/Modify

**New files:**
- `shared/src/commonMain/kotlin/.../presentation/components/SliderWithButtons.kt`

**Modified files:**
- `RestTimerCard.kt` - Replace `ParameterAdjuster` and `WeightAdjustmentControls` with `SliderWithButtons`

---

## Feature 3: Eccentric Load Granularity

### Current Values

```kotlin
enum class EccentricLoad(val percentage: Int, val displayName: String) {
    LOAD_0(0, "0%"),
    LOAD_50(50, "50%"),
    LOAD_75(75, "75%"),
    LOAD_100(100, "100%"),
    LOAD_125(125, "125%"),
    LOAD_150(150, "150%")
}
```

### New Values

```kotlin
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

Changes:
- Keep: 0%, 50%, 75%, 100%, 150%
- Remove: 125%
- Add: 110%, 120%, 130%, 140%

### UI Change

Replace slider with dropdown picker (`ExposedDropdownMenuBox`):

```
┌─────────────────────────────────────────┐
│  Eccentric Load                         │
│  ┌─────────────────────────────────┐   │
│  │  100%                         ▼ │   │
│  └─────────────────────────────────┘   │
│  Load during eccentric (lowering) phase │
└─────────────────────────────────────────┘
```

### Files to Modify

- `Models.kt` - Update `EccentricLoad` enum (add new values, remove LOAD_125)
- `JustLiftScreen.kt` - Replace slider with `ExposedDropdownMenuBox`
- `PreferencesManager.kt` - Update `getEccentricLoad()` fallback logic
- `ExerciseEditBottomSheet.kt` - Update if eccentric load selection exists there
- `SingleExerciseScreen.kt` - Update if eccentric load selection exists there

### Migration Note

Existing saved preferences with `125%` should gracefully fall back to `120%` or `130%`.

---

## Feature 4: Stall Detection Feature Flag

### Current Location

Toggle exists on `JustLiftScreen.kt` (lines 260-293) as a card with Switch.

### New Location

Move to Settings screen under "Workout Preferences" card.

### Settings UI

Add to `SettingsTab` in the "Workout Preferences" section:

```
┌─────────────────────────────────────────┐
│  Workout Preferences                    │
├─────────────────────────────────────────┤
│  Autoplay Routines                  [✓] │
│  Automatically advance after rest       │
│                                         │
│  Stop At Top                        [ ] │
│  Release at contracted position         │
│                                         │
│  Show Exercise Videos               [✓] │
│  Display demonstration videos           │
│                                         │
│  Stall Detection                    [✓] │  ← NEW
│  Auto-stop set when movement pauses     │
│  for 5 seconds (Just Lift/AMRAP modes)  │
└─────────────────────────────────────────┘
```

### Data Model Changes

Add to `UserPreferences`:
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

Add to `PreferencesManager` interface:
```kotlin
suspend fun setStallDetectionEnabled(enabled: Boolean)
```

### Files to Modify

- `PreferencesManager.kt`:
  - Add `stallDetectionEnabled` to `UserPreferences`
  - Add `KEY_STALL_DETECTION` constant
  - Add `setStallDetectionEnabled()` method
  - Update `loadPreferences()` to read stall detection setting

- `HistoryAndSettingsTabs.kt`:
  - Add `stallDetectionEnabled: Boolean` parameter to `SettingsTab`
  - Add `onStallDetectionChange: (Boolean) -> Unit` callback
  - Add toggle row in "Workout Preferences" card

- `JustLiftScreen.kt`:
  - Remove the Stall Detection toggle card (lines 260-293)
  - Read `stallDetectionEnabled` from preferences/viewmodel instead of local state

- `MainViewModel.kt`:
  - Expose stall detection preference from `PreferencesManager`

---

## Implementation Order

Recommended order based on dependencies and complexity:

1. **Stall Detection Flag** (simplest, no database changes)
2. **Eccentric Load Granularity** (enum + UI changes only)
3. **Post-Set Sliders** (new component + RestTimerCard update)
4. **Multiple User Profiles** (most complex, database migration required)

---

## Testing Considerations

- **Profiles**: Test data isolation, profile switching, deletion with data
- **Sliders**: Test slider + button interaction, value bounds, unit conversion
- **Eccentric Load**: Test 125% migration, dropdown selection, BLE packet generation
- **Stall Detection**: Test preference persistence, behavior in Just Lift/AMRAP modes
