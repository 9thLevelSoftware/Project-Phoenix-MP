# iOS/Android Crash & Export Fixes Plan - TestFlight v0.2.1

## Summary

Analysis of 4 TestFlight crash reports reveals **two distinct bugs** causing 100% crash rate:

| Crash Type | Occurrences | Root Cause | Severity |
|------------|-------------|------------|----------|
| SQLite Schema Migration | 3/4 | Missing migration for Training Cycles tables | Critical |
| CSV Export Threading | 1/4 | UIActivityViewController called from background thread | High |

---

## Issue 1: Missing SQLite Migration (Critical)

### Symptoms
- App crashes immediately on startup (3-5 seconds after launch)
- User feedback: "unable to start the app", "App doesn't work keeps crashing", "Trying to open cycles causes app to crash"
- Exception: `SIGABRT` with `SQLiteException`

### Root Cause
The following tables were added to `VitruvianDatabase.sq` but **no migration file was created**:

```sql
-- Lines 785-874 in VitruvianDatabase.sq (no corresponding migration!)
CREATE TABLE TrainingCycle (...)
CREATE TABLE CycleDay (...)
CREATE TABLE CycleProgress (...)
CREATE TABLE CycleProgression (...)
CREATE TABLE PlannedSet (...)
CREATE TABLE CompletedSet (...)
CREATE TABLE ProgressionEvent (...)
```

When users upgrade from a previous version:
1. SQLDelight sees database version matches (version 5)
2. New tables don't exist because no migration was created
3. `HomeScreen.kt:65` calls `cycleRepository.getActiveCycle()`
4. This triggers `selectCycleDaysByCycle` query → crash

### Affected Users
- All users who upgrade from any previous version
- Fresh installs work (new database has all tables)

### Fix Required

**Create migration file `6.sqm`:**

```
shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/6.sqm
```

```sql
-- Migration 6: Add Training Cycles feature tables
-- These tables support the new rolling training cycle feature

CREATE TABLE IF NOT EXISTS TrainingCycle (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    created_at INTEGER NOT NULL,
    is_active INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS CycleDay (
    id TEXT PRIMARY KEY NOT NULL,
    cycle_id TEXT NOT NULL,
    day_number INTEGER NOT NULL,
    name TEXT,
    routine_id TEXT,
    is_rest_day INTEGER NOT NULL DEFAULT 0,
    echo_level TEXT,
    eccentric_load_percent INTEGER,
    weight_progression_percent REAL,
    rep_modifier INTEGER,
    rest_time_override_seconds INTEGER,
    FOREIGN KEY (cycle_id) REFERENCES TrainingCycle(id) ON DELETE CASCADE,
    FOREIGN KEY (routine_id) REFERENCES Routine(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS CycleProgress (
    id TEXT PRIMARY KEY NOT NULL,
    cycle_id TEXT NOT NULL UNIQUE,
    current_day_number INTEGER NOT NULL DEFAULT 1,
    last_completed_date INTEGER,
    cycle_start_date INTEGER NOT NULL,
    last_advanced_at INTEGER,
    completed_days TEXT,
    missed_days TEXT,
    rotation_count INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (cycle_id) REFERENCES TrainingCycle(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS CycleProgression (
    cycle_id TEXT PRIMARY KEY NOT NULL,
    frequency_cycles INTEGER NOT NULL DEFAULT 2,
    weight_increase_percent REAL,
    echo_level_increase INTEGER NOT NULL DEFAULT 0,
    eccentric_load_increase_percent INTEGER,
    FOREIGN KEY (cycle_id) REFERENCES TrainingCycle(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS PlannedSet (
    id TEXT PRIMARY KEY NOT NULL,
    routine_exercise_id TEXT NOT NULL,
    set_number INTEGER NOT NULL,
    set_type TEXT NOT NULL DEFAULT 'STANDARD',
    target_reps INTEGER,
    target_weight_kg REAL,
    target_rpe INTEGER,
    rest_seconds INTEGER,
    FOREIGN KEY (routine_exercise_id) REFERENCES RoutineExercise(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS CompletedSet (
    id TEXT PRIMARY KEY NOT NULL,
    session_id TEXT NOT NULL,
    planned_set_id TEXT,
    set_number INTEGER NOT NULL,
    set_type TEXT NOT NULL DEFAULT 'STANDARD',
    actual_reps INTEGER NOT NULL,
    actual_weight_kg REAL NOT NULL,
    logged_rpe INTEGER,
    is_pr INTEGER NOT NULL DEFAULT 0,
    completed_at INTEGER NOT NULL,
    FOREIGN KEY (session_id) REFERENCES WorkoutSession(id) ON DELETE CASCADE,
    FOREIGN KEY (planned_set_id) REFERENCES PlannedSet(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS ProgressionEvent (
    id TEXT PRIMARY KEY NOT NULL,
    exercise_id TEXT NOT NULL,
    suggested_weight_kg REAL NOT NULL,
    previous_weight_kg REAL NOT NULL,
    reason TEXT NOT NULL,
    user_response TEXT,
    actual_weight_kg REAL,
    timestamp INTEGER NOT NULL,
    FOREIGN KEY (exercise_id) REFERENCES Exercise(id) ON DELETE CASCADE
);

-- Create indexes
CREATE INDEX IF NOT EXISTS idx_cycle_day_cycle ON CycleDay(cycle_id);
CREATE INDEX IF NOT EXISTS idx_cycle_progress_cycle ON CycleProgress(cycle_id);
CREATE INDEX IF NOT EXISTS idx_planned_set_exercise ON PlannedSet(routine_exercise_id);
CREATE INDEX IF NOT EXISTS idx_completed_set_session ON CompletedSet(session_id);
CREATE INDEX IF NOT EXISTS idx_progression_event_exercise ON ProgressionEvent(exercise_id);
```

---

## Issue 2: CSV Export Threading (High)

### Symptoms
- App crashes when exporting workout history
- User feedback: "Was trying to export workout history and it crashed"
- Exception: `EXC_BREAKPOINT (SIGTRAP)` with `dispatch_assert_queue_fail`

### Root Cause

`CsvExporter.ios.kt:140` - `UIActivityViewController` is created from a background coroutine thread:

```kotlin
// AnalyticsScreen.kt:522 - Called from Dispatchers.IO context
csvExporter.shareCSV(path, "workout_history.csv")

// CsvExporter.ios.kt:123-145 - Presents ViewController on background thread
override fun shareCSV(fileUri: String, fileName: String) {
    val url = NSURL.fileURLWithPath(fileUri)
    // ... gets rootViewController
    val activityVC = UIActivityViewController(...)
    rootViewController.presentViewController(activityVC, animated = true, completion = null)  // CRASH!
}
```

UIKit requires all UI operations on the main thread. The share sheet is being presented from a background dispatch queue.

### Fix Required

**Update `CsvExporter.ios.kt`:**

```kotlin
override fun shareCSV(fileUri: String, fileName: String) {
    val url = NSURL.fileURLWithPath(fileUri)

    // Dispatch to main thread for UI operations
    dispatch_async(dispatch_get_main_queue()) {
        @Suppress("UNCHECKED_CAST")
        val scenes = UIApplication.sharedApplication.connectedScenes as Set<*>
        val windowScene = scenes.firstOrNull {
            it is platform.UIKit.UIWindowScene
        } as? platform.UIKit.UIWindowScene

        val rootViewController = windowScene?.keyWindow?.rootViewController ?: return@dispatch_async

        val activityVC = UIActivityViewController(
            activityItems = listOf(url),
            applicationActivities = null
        )

        rootViewController.presentViewController(
            activityVC,
            animated = true,
            completion = null
        )
    }
}
```

Required imports:
```kotlin
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
```

---

## Crash Log Details

### Crash 1 (tf_feedback_0)
- **Device:** iPhone 15,5 (iOS 26.0.1)
- **Comment:** "unable to start the app"
- **Crash time:** 3 seconds after launch
- **Stack:** `SqlDelightTrainingCycleRepository.kt:326` → `selectCycleDaysByCycle`

### Crash 2 (tf_feedback_1)
- **Device:** iPhone 14,3 (iOS 26.2)
- **Comment:** "App doesn't work keeps crashing"
- **Crash time:** 3 seconds after launch
- **Stack:** Same as Crash 1

### Crash 3 (tf_feedback_2)
- **Device:** iPhone 17,1 (iOS 26.1)
- **Comment:** "Trying to open cycles causes app to crash"
- **Crash time:** 5 seconds after launch
- **Stack:** Same as Crash 1

### Crash 4 (tf_feedback_3)
- **Device:** iPhone 18,2 (iOS 26.2)
- **Comment:** "Was trying to export workout history and it crashed"
- **Crash time:** 18 minutes after launch (user was navigating app)
- **Stack:** `AnalyticsScreen.kt:522` → `CsvExporter.ios.kt:140` → `dispatch_assert_queue_fail`

---

---

## Issue 3: Android FileProvider Missing (GitHub #88)

### Symptoms
- Export completes but share dialog never appears
- Snackbar shows cache path: `/data/user/0/.../cache/exports/...`
- User feedback: "defaults to the user cache folder of the device"

### Root Cause

`AndroidManifest.xml` is missing the FileProvider declaration, and no `file_paths.xml` exists. When `FileProvider.getUriForFile()` is called, it throws an exception that is silently caught.

### Fix Required

**1. Create `androidApp/src/main/res/xml/file_paths.xml`:**

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="exports" path="exports/" />
</paths>
```

**2. Add to `androidApp/src/main/AndroidManifest.xml` inside `<application>`:**

```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>
```

**3. Update `CsvExporter.android.kt` with proper error handling:**

```kotlin
override fun shareCSV(fileUri: String, fileName: String) {
    try {
        val file = File(fileUri)
        if (!file.exists()) {
            android.util.Log.e("CsvExporter", "File does not exist: $fileUri")
            return
        }

        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Vitruvian Export: $fileName")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val chooser = Intent.createChooser(shareIntent, "Share CSV").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    } catch (e: Exception) {
        android.util.Log.e("CsvExporter", "Failed to share CSV", e)
        // TODO: Show user-facing error via callback or snackbar
    }
}
```

---

## Implementation Steps

1. [ ] Create `migrations/6.sqm` with Training Cycles tables (fixes iOS + Android startup crash)
2. [ ] Update `CsvExporter.ios.kt` to dispatch to main queue (fixes iOS export crash)
3. [ ] Create `file_paths.xml` and update `AndroidManifest.xml` (fixes Android export - #88)
4. [ ] Add error logging to Android `shareCSV`
5. [ ] Test migration on devices with older database
6. [ ] Test CSV export functionality on both platforms
7. [ ] Build and deploy hotfix to TestFlight / Play Store

---

## Testing Checklist

### Startup Crash Fix (Migration)
- [ ] Fresh install on iOS: app launches, all features work
- [ ] Fresh install on Android: app launches, all features work
- [ ] Upgrade from v0.2.0 on iOS: app launches without crash
- [ ] Upgrade from v0.2.0 on Android: app launches without crash
- [ ] Open Training Cycles screen: no crash

### Export Fix (iOS Threading + Android FileProvider)
- [ ] iOS: Export workout history → share sheet appears
- [ ] iOS: Export personal records → share sheet appears
- [ ] Android: Export workout history → share chooser appears
- [ ] Android: Export personal records → share chooser appears
- [ ] Both: Can share to email, files, etc.
