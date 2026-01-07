# Resume/Restart Dialog Design

**Issue:** #101 - Exiting daily routine
**Date:** 2026-01-06
**Status:** Approved

## Problem

When a user exits a routine mid-workout and returns to start it again, the app resumes at the previous set instead of starting fresh. Users expect either:
1. A fresh start, or
2. An explicit choice between resume and restart

## Solution

Add a dialog that appears when clicking "Start Workout" for a routine that has existing in-progress state.

## Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Trigger | On "Start Workout" click | Clean UX - decision at point of action |
| Detection | Same routine with progress | Different routine = implicit abandon |
| Dialog options | Continue / Restart with progress info | Informed choice with context |

## Implementation

### 1. Detection Logic (MainViewModel.kt)

```kotlin
fun hasResumableProgress(routineId: String): Boolean {
    val loaded = _loadedRoutine.value ?: return false
    return loaded.id == routineId &&
           (_currentSetIndex.value > 0 || _currentExerciseIndex.value > 0)
}

fun getResumableProgressInfo(): ResumableProgressInfo? {
    val routine = _loadedRoutine.value ?: return null
    val exercise = routine.exercises.getOrNull(_currentExerciseIndex.value) ?: return null
    return ResumableProgressInfo(
        exerciseName = exercise.exercise.displayName,
        currentSet = _currentSetIndex.value + 1,
        totalSets = exercise.setReps.size,
        currentExercise = _currentExerciseIndex.value + 1,
        totalExercises = routine.exercises.size
    )
}

data class ResumableProgressInfo(
    val exerciseName: String,
    val currentSet: Int,
    val totalSets: Int,
    val currentExercise: Int,
    val totalExercises: Int
)
```

### 2. Dialog Component (ResumeRoutineDialog.kt)

```kotlin
@Composable
fun ResumeRoutineDialog(
    progressInfo: ResumableProgressInfo,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Resume Workout?") },
        text = {
            Column {
                Text("You have progress saved:")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Exercise ${progressInfo.currentExercise} of ${progressInfo.totalExercises}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${progressInfo.exerciseName} - Set ${progressInfo.currentSet} of ${progressInfo.totalSets}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onResume) { Text("Continue") }
        },
        dismissButton = {
            OutlinedButton(onClick = onRestart) { Text("Restart from Set 1") }
        }
    )
}
```

### 3. Integration Points

- **TrainingCyclesScreen.kt** - Primary entry for cycle workouts
- **DailyRoutinesScreen.kt** - Entry for standalone routines
- **UpNextWidget.kt** - Quick start from home screen

### 4. Edge Cases

1. **Connection lost mid-resume**: Existing `ensureConnection` error handling applies
2. **Routine modified externally**: Add index validity check before showing dialog
3. **App killed/restarted**: ViewModel cleared, fresh start automatically

## Files to Create/Modify

| File | Action |
|------|--------|
| `MainViewModel.kt` | Add detection functions and data class |
| `ResumeRoutineDialog.kt` | Create new dialog component |
| `TrainingCyclesScreen.kt` | Add dialog state and handler |
| `DailyRoutinesScreen.kt` | Add dialog state and handler |

## Testing

1. Start routine, complete 1 set, exit via back button
2. Return and click "Start Workout" - dialog should appear
3. Test "Continue" - should resume at set 2
4. Test "Restart from Set 1" - should start fresh
5. Start different routine - no dialog, fresh start
