# Set Summary History Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Persist set summary metrics to database and display them via expandable history cards.

**Architecture:** Add 16 nullable columns to WorkoutSession table for summary metrics. Populate during save, display using reused SetSummaryCard component with `isHistoryView` mode.

**Tech Stack:** SQLDelight, Kotlin Multiplatform, Compose Multiplatform

---

## Task 1: Update Database Schema

**Files:**
- Modify: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq:45-69` (WorkoutSession table)
- Modify: `shared/src/commonMain/sqldelight/com/devil/phoenixproject/database/VitruvianDatabase.sq:346-354` (insertSession query)

**Step 1: Add new columns to WorkoutSession table**

After line 68 (spotterActivations), add:

```sql
    -- Set Summary Metrics (added in v0.2.1)
    peakForceConcentricA REAL,
    peakForceConcentricB REAL,
    peakForceEccentricA REAL,
    peakForceEccentricB REAL,
    avgForceConcentricA REAL,
    avgForceConcentricB REAL,
    avgForceEccentricA REAL,
    avgForceEccentricB REAL,
    heaviestLiftKg REAL,
    totalVolumeKg REAL,
    estimatedCalories REAL,
    warmupAvgWeightKg REAL,
    workingAvgWeightKg REAL,
    burnoutAvgWeightKg REAL,
    peakWeightKg REAL,
    rpe INTEGER
```

**Step 2: Update insertSession query**

Replace the insertSession query (lines 346-354) with:

```sql
insertSession:
INSERT INTO WorkoutSession (
    id, timestamp, mode, targetReps, weightPerCableKg, progressionKg,
    duration, totalReps, warmupReps, workingReps,
    isJustLift, stopAtTop, eccentricLoad, echoLevel,
    exerciseId, exerciseName, routineSessionId, routineName,
    safetyFlags, deloadWarningCount, romViolationCount, spotterActivations,
    peakForceConcentricA, peakForceConcentricB, peakForceEccentricA, peakForceEccentricB,
    avgForceConcentricA, avgForceConcentricB, avgForceEccentricA, avgForceEccentricB,
    heaviestLiftKg, totalVolumeKg, estimatedCalories,
    warmupAvgWeightKg, workingAvgWeightKg, burnoutAvgWeightKg, peakWeightKg, rpe
)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
```

**Step 3: Rebuild project to verify schema compiles**

Run: `./gradlew :shared:generateCommonMainVitruvianDatabaseInterface`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/sqldelight/
git commit -m "feat(db): add set summary metrics columns to WorkoutSession"
```

---

## Task 2: Update Domain Model

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt:336-363` (WorkoutSession data class)

**Step 1: Add new properties to WorkoutSession**

Replace the WorkoutSession data class with:

```kotlin
/**
 * Workout session data (simplified for database storage)
 */
data class WorkoutSession(
    val id: String = generateUUID(),
    val timestamp: Long = currentTimeMillis(),
    val mode: String = "OldSchool",
    val reps: Int = 10,
    val weightPerCableKg: Float = 10f,
    val progressionKg: Float = 0f,
    val duration: Long = 0,
    val totalReps: Int = 0,
    val warmupReps: Int = 0,
    val workingReps: Int = 0,
    val isJustLift: Boolean = false,
    val stopAtTop: Boolean = false,
    // Echo mode configuration
    val eccentricLoad: Int = 100,
    val echoLevel: Int = 2,
    // Exercise tracking
    val exerciseId: String? = null,
    val exerciseName: String? = null,
    // Routine tracking
    val routineSessionId: String? = null,
    val routineName: String? = null,
    // Safety tracking
    val safetyFlags: Int = 0,
    val deloadWarningCount: Int = 0,
    val romViolationCount: Int = 0,
    val spotterActivations: Int = 0,
    // Set Summary Metrics (added in v0.2.1)
    val peakForceConcentricA: Float? = null,
    val peakForceConcentricB: Float? = null,
    val peakForceEccentricA: Float? = null,
    val peakForceEccentricB: Float? = null,
    val avgForceConcentricA: Float? = null,
    val avgForceConcentricB: Float? = null,
    val avgForceEccentricA: Float? = null,
    val avgForceEccentricB: Float? = null,
    val heaviestLiftKg: Float? = null,
    val totalVolumeKg: Float? = null,
    val estimatedCalories: Float? = null,
    val warmupAvgWeightKg: Float? = null,
    val workingAvgWeightKg: Float? = null,
    val burnoutAvgWeightKg: Float? = null,
    val peakWeightKg: Float? = null,
    val rpe: Int? = null
) {
    /** True if this session has detailed summary metrics (v0.2.1+) */
    val hasSummaryMetrics: Boolean
        get() = peakForceConcentricA != null || peakForceConcentricB != null
}
```

**Step 2: Add toSetSummary() extension function**

After the WorkoutSession data class, add:

```kotlin
/**
 * Convert WorkoutSession to SetSummary for display in history.
 * Returns null if session doesn't have summary metrics (pre-v0.2.1).
 */
fun WorkoutSession.toSetSummary(): WorkoutState.SetSummary? {
    if (!hasSummaryMetrics) return null

    return WorkoutState.SetSummary(
        metrics = emptyList(),
        peakPower = 0f,
        averagePower = 0f,
        repCount = totalReps,
        durationMs = duration,
        totalVolumeKg = totalVolumeKg ?: 0f,
        heaviestLiftKgPerCable = heaviestLiftKg ?: 0f,
        peakForceConcentricA = peakForceConcentricA ?: 0f,
        peakForceConcentricB = peakForceConcentricB ?: 0f,
        peakForceEccentricA = peakForceEccentricA ?: 0f,
        peakForceEccentricB = peakForceEccentricB ?: 0f,
        avgForceConcentricA = avgForceConcentricA ?: 0f,
        avgForceConcentricB = avgForceConcentricB ?: 0f,
        avgForceEccentricA = avgForceEccentricA ?: 0f,
        avgForceEccentricB = avgForceEccentricB ?: 0f,
        estimatedCalories = estimatedCalories ?: 0f,
        isEchoMode = mode.contains("Echo", ignoreCase = true),
        warmupReps = warmupReps,
        workingReps = workingReps,
        burnoutReps = (totalReps - warmupReps - workingReps).coerceAtLeast(0),
        warmupAvgWeightKg = warmupAvgWeightKg ?: 0f,
        workingAvgWeightKg = workingAvgWeightKg ?: 0f,
        burnoutAvgWeightKg = burnoutAvgWeightKg ?: 0f,
        peakWeightKg = peakWeightKg ?: 0f
    )
}
```

**Step 3: Build to verify compilation**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/Models.kt
git commit -m "feat(model): add summary metrics to WorkoutSession with toSetSummary()"
```

---

## Task 3: Update Repository Layer

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt:41-89` (mapToSession function)
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt:301-328` (saveSession function)

**Step 1: Update mapToSession function signature and body**

The mapToSession function needs to accept the 16 new nullable parameters and map them. Update the function signature and body:

```kotlin
private fun mapToSession(
    id: String,
    timestamp: Long,
    mode: String,
    targetReps: Long,
    weightPerCableKg: Double,
    progressionKg: Double,
    duration: Long,
    totalReps: Long,
    warmupReps: Long,
    workingReps: Long,
    isJustLift: Long,
    stopAtTop: Long,
    eccentricLoad: Long,
    echoLevel: Long,
    exerciseId: String?,
    exerciseName: String?,
    routineSessionId: String?,
    routineName: String?,
    safetyFlags: Long,
    deloadWarningCount: Long,
    romViolationCount: Long,
    spotterActivations: Long,
    // New summary metrics
    peakForceConcentricA: Double?,
    peakForceConcentricB: Double?,
    peakForceEccentricA: Double?,
    peakForceEccentricB: Double?,
    avgForceConcentricA: Double?,
    avgForceConcentricB: Double?,
    avgForceEccentricA: Double?,
    avgForceEccentricB: Double?,
    heaviestLiftKg: Double?,
    totalVolumeKg: Double?,
    estimatedCalories: Double?,
    warmupAvgWeightKg: Double?,
    workingAvgWeightKg: Double?,
    burnoutAvgWeightKg: Double?,
    peakWeightKg: Double?,
    rpe: Long?
): WorkoutSession {
    return WorkoutSession(
        id = id,
        timestamp = timestamp,
        mode = mode,
        reps = targetReps.toInt(),
        weightPerCableKg = weightPerCableKg.toFloat(),
        progressionKg = progressionKg.toFloat(),
        duration = duration,
        totalReps = totalReps.toInt(),
        warmupReps = warmupReps.toInt(),
        workingReps = workingReps.toInt(),
        isJustLift = isJustLift == 1L,
        stopAtTop = stopAtTop == 1L,
        eccentricLoad = eccentricLoad.toInt(),
        echoLevel = echoLevel.toInt(),
        exerciseId = exerciseId,
        exerciseName = exerciseName,
        routineSessionId = routineSessionId,
        routineName = routineName,
        safetyFlags = safetyFlags.toInt(),
        deloadWarningCount = deloadWarningCount.toInt(),
        romViolationCount = romViolationCount.toInt(),
        spotterActivations = spotterActivations.toInt(),
        // New summary metrics
        peakForceConcentricA = peakForceConcentricA?.toFloat(),
        peakForceConcentricB = peakForceConcentricB?.toFloat(),
        peakForceEccentricA = peakForceEccentricA?.toFloat(),
        peakForceEccentricB = peakForceEccentricB?.toFloat(),
        avgForceConcentricA = avgForceConcentricA?.toFloat(),
        avgForceConcentricB = avgForceConcentricB?.toFloat(),
        avgForceEccentricA = avgForceEccentricA?.toFloat(),
        avgForceEccentricB = avgForceEccentricB?.toFloat(),
        heaviestLiftKg = heaviestLiftKg?.toFloat(),
        totalVolumeKg = totalVolumeKg?.toFloat(),
        estimatedCalories = estimatedCalories?.toFloat(),
        warmupAvgWeightKg = warmupAvgWeightKg?.toFloat(),
        workingAvgWeightKg = workingAvgWeightKg?.toFloat(),
        burnoutAvgWeightKg = burnoutAvgWeightKg?.toFloat(),
        peakWeightKg = peakWeightKg?.toFloat(),
        rpe = rpe?.toInt()
    )
}
```

**Step 2: Update saveSession function**

Add the new parameters to the insertSession call:

```kotlin
override suspend fun saveSession(session: WorkoutSession) {
    withContext(Dispatchers.IO) {
        queries.insertSession(
            id = session.id,
            timestamp = session.timestamp,
            mode = session.mode,
            targetReps = session.reps.toLong(),
            weightPerCableKg = session.weightPerCableKg.toDouble(),
            progressionKg = session.progressionKg.toDouble(),
            duration = session.duration,
            totalReps = session.totalReps.toLong(),
            warmupReps = session.warmupReps.toLong(),
            workingReps = session.workingReps.toLong(),
            isJustLift = if (session.isJustLift) 1L else 0L,
            stopAtTop = if (session.stopAtTop) 1L else 0L,
            eccentricLoad = session.eccentricLoad.toLong(),
            echoLevel = session.echoLevel.toLong(),
            exerciseId = session.exerciseId,
            exerciseName = session.exerciseName,
            routineSessionId = session.routineSessionId,
            routineName = session.routineName,
            safetyFlags = session.safetyFlags.toLong(),
            deloadWarningCount = session.deloadWarningCount.toLong(),
            romViolationCount = session.romViolationCount.toLong(),
            spotterActivations = session.spotterActivations.toLong(),
            // New summary metrics
            peakForceConcentricA = session.peakForceConcentricA?.toDouble(),
            peakForceConcentricB = session.peakForceConcentricB?.toDouble(),
            peakForceEccentricA = session.peakForceEccentricA?.toDouble(),
            peakForceEccentricB = session.peakForceEccentricB?.toDouble(),
            avgForceConcentricA = session.avgForceConcentricA?.toDouble(),
            avgForceConcentricB = session.avgForceConcentricB?.toDouble(),
            avgForceEccentricA = session.avgForceEccentricA?.toDouble(),
            avgForceEccentricB = session.avgForceEccentricB?.toDouble(),
            heaviestLiftKg = session.heaviestLiftKg?.toDouble(),
            totalVolumeKg = session.totalVolumeKg?.toDouble(),
            estimatedCalories = session.estimatedCalories?.toDouble(),
            warmupAvgWeightKg = session.warmupAvgWeightKg?.toDouble(),
            workingAvgWeightKg = session.workingAvgWeightKg?.toDouble(),
            burnoutAvgWeightKg = session.burnoutAvgWeightKg?.toDouble(),
            peakWeightKg = session.peakWeightKg?.toDouble(),
            rpe = session.rpe?.toLong()
        )
    }
}
```

**Step 3: Build to verify compilation**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/data/repository/SqlDelightWorkoutRepository.kt
git commit -m "feat(repo): persist and retrieve summary metrics in WorkoutSession"
```

---

## Task 4: Update ViewModel Save Logic

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt` (stopWorkout function and private saveWorkoutSession function)

**Step 1: Find the save logic**

Search for where WorkoutSession is created and the summary is calculated. The summary is calculated using `calculateSetSummaryMetrics()` and then the session is saved.

**Step 2: Populate summary metrics when creating WorkoutSession**

Find where `WorkoutSession(...)` is constructed during save, and add the summary fields. The summary is already calculated at that point, so copy the values:

```kotlin
val session = WorkoutSession(
    // ... existing fields ...

    // Populate from calculated summary
    peakForceConcentricA = summary.peakForceConcentricA,
    peakForceConcentricB = summary.peakForceConcentricB,
    peakForceEccentricA = summary.peakForceEccentricA,
    peakForceEccentricB = summary.peakForceEccentricB,
    avgForceConcentricA = summary.avgForceConcentricA,
    avgForceConcentricB = summary.avgForceConcentricB,
    avgForceEccentricA = summary.avgForceEccentricA,
    avgForceEccentricB = summary.avgForceEccentricB,
    heaviestLiftKg = summary.heaviestLiftKgPerCable,
    totalVolumeKg = summary.totalVolumeKg,
    estimatedCalories = summary.estimatedCalories,
    warmupAvgWeightKg = if (summary.isEchoMode) summary.warmupAvgWeightKg else null,
    workingAvgWeightKg = if (summary.isEchoMode) summary.workingAvgWeightKg else null,
    burnoutAvgWeightKg = if (summary.isEchoMode) summary.burnoutAvgWeightKg else null,
    peakWeightKg = if (summary.isEchoMode) summary.peakWeightKg else null,
    rpe = _currentSetRpe.value
)
```

**Step 3: Build to verify compilation**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt
git commit -m "feat(vm): populate summary metrics when saving WorkoutSession"
```

---

## Task 5: Add isHistoryView Parameter to SetSummaryCard

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt:1307-1316` (SetSummaryCard function)

**Step 1: Add isHistoryView parameter**

Update the SetSummaryCard function signature:

```kotlin
@Composable
fun SetSummaryCard(
    summary: WorkoutState.SetSummary,
    workoutMode: String,
    weightUnit: WeightUnit,
    kgToDisplay: (Float, WeightUnit) -> Float,
    formatWeight: (Float, WeightUnit) -> String,
    onContinue: () -> Unit,
    autoplayEnabled: Boolean,
    onRpeLogged: ((Int) -> Unit)? = null,
    isHistoryView: Boolean = false,  // NEW: Hide interactive elements when viewing from history
    savedRpe: Int? = null  // NEW: Show saved RPE value in history view
)
```

**Step 2: Conditionally hide interactive elements**

Wrap the countdown/continue button logic with `if (!isHistoryView)`:

```kotlin
// Only show countdown and continue button in live view
if (!isHistoryView) {
    // ... existing countdown logic and Done button ...
}
```

For the RPE section, show as read-only text in history view:

```kotlin
// RPE section
if (isHistoryView && savedRpe != null) {
    // Show saved RPE as read-only
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("RPE", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("$savedRpe/10", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }
} else if (!isHistoryView && onRpeLogged != null) {
    // ... existing interactive RPE slider ...
}
```

**Step 3: Build to verify compilation**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt
git commit -m "feat(ui): add isHistoryView mode to SetSummaryCard"
```

---

## Task 6: Add Expandable History Cards

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HistoryAndSettingsTabs.kt:1-45` (imports)
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HistoryAndSettingsTabs.kt:147-393` (WorkoutHistoryCard)

**Step 1: Add required imports**

At the top of the file, add:

```kotlin
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import com.devil.phoenixproject.domain.model.toSetSummary
```

**Step 2: Update WorkoutHistoryCard to be expandable**

Add expansion state and modify the card:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutHistoryCard(
    session: WorkoutSession,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    kgToDisplay: (Float, WeightUnit) -> Float,  // NEW parameter
    exerciseRepository: com.devil.phoenixproject.data.repository.ExerciseRepository,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isExpanded by remember { mutableStateOf(false) }  // NEW
    var isPressed by remember { mutableStateOf(false) }

    // ... existing scale animation ...

    // Chevron rotation animation
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = tween(300),
        label = "chevron"
    )

    val exerciseName = session.exerciseName ?: if (session.isJustLift) "Just Lift" else "Unknown Exercise"

    Card(
        onClick = { isExpanded = !isExpanded },  // Toggle expansion on click
        // ... existing modifiers ...
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium)
        ) {
            // Header row with chevron
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Single Exercise",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    modifier = Modifier.rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // ... existing content (exercise name, date, basic stats) ...

            // Expandable summary section
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(top = Spacing.medium)
                ) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Spacer(modifier = Modifier.height(Spacing.medium))

                    val summary = session.toSetSummary()
                    if (summary != null) {
                        SetSummaryCard(
                            summary = summary,
                            workoutMode = session.mode,
                            weightUnit = weightUnit,
                            kgToDisplay = kgToDisplay,
                            formatWeight = formatWeight,
                            onContinue = { },  // No-op for history view
                            autoplayEnabled = false,
                            isHistoryView = true,
                            savedRpe = session.rpe
                        )
                    } else {
                        // Pre-v0.2.1 session - show message
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Spacing.medium),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(Spacing.small))
                                Text(
                                    "Detailed metrics available for workouts after v0.2.1",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // ... existing delete button section ...
        }
    }

    // ... existing delete dialog ...
}
```

**Step 3: Update HistoryTab to pass kgToDisplay**

Add the `kgToDisplay` parameter to HistoryTab and pass it through:

```kotlin
@Composable
fun HistoryTab(
    groupedWorkoutHistory: List<HistoryItem>,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    kgToDisplay: (Float, WeightUnit) -> Float,  // NEW
    onDeleteWorkout: (String) -> Unit,
    exerciseRepository: ExerciseRepository,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
)
```

Pass it to WorkoutHistoryCard:

```kotlin
WorkoutHistoryCard(
    session = item.session,
    weightUnit = weightUnit,
    formatWeight = formatWeight,
    kgToDisplay = kgToDisplay,  // NEW
    exerciseRepository = exerciseRepository,
    onDelete = { onDeleteWorkout(item.session.id) }
)
```

**Step 4: Build to verify compilation**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HistoryAndSettingsTabs.kt
git commit -m "feat(history): add expandable set summary to history cards"
```

---

## Task 7: Update GroupedRoutineCard Similarly

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HistoryAndSettingsTabs.kt:407-706` (GroupedRoutineCard)

**Step 1: Add expansion state and chevron**

Apply the same pattern as WorkoutHistoryCard:
- Add `isExpanded` state
- Add chevron icon with rotation animation
- Click to toggle expansion
- AnimatedVisibility for expanded content

**Step 2: Show SetSummaryCard for each exercise session**

In the expanded section, iterate through sessions and show SetSummaryCard for each:

```kotlin
AnimatedVisibility(
    visible = isExpanded,
    enter = expandVertically(),
    exit = shrinkVertically()
) {
    Column(modifier = Modifier.padding(top = Spacing.medium)) {
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(Spacing.medium))

        groupedItem.sessions.forEachIndexed { index, session ->
            val summary = session.toSetSummary()

            Text(
                session.exerciseName ?: "Unknown Exercise",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(Spacing.small))

            if (summary != null) {
                SetSummaryCard(
                    summary = summary,
                    workoutMode = session.mode,
                    weightUnit = weightUnit,
                    kgToDisplay = kgToDisplay,
                    formatWeight = formatWeight,
                    onContinue = { },
                    autoplayEnabled = false,
                    isHistoryView = true,
                    savedRpe = session.rpe
                )
            } else {
                // Pre-v0.2.1 message
                // ... same as Task 6 ...
            }

            if (index < groupedItem.sessions.size - 1) {
                Spacer(modifier = Modifier.height(Spacing.medium))
            }
        }
    }
}
```

**Step 3: Update GroupedRoutineCard signature**

Add `kgToDisplay` parameter:

```kotlin
@Composable
fun GroupedRoutineCard(
    groupedItem: com.devil.phoenixproject.presentation.viewmodel.GroupedRoutineHistoryItem,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    kgToDisplay: (Float, WeightUnit) -> Float,  // NEW
    exerciseRepository: com.devil.phoenixproject.data.repository.ExerciseRepository,
    onDelete: (String) -> Unit
)
```

**Step 4: Build to verify compilation**

Run: `./gradlew :shared:compileKotlinMetadata`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/HistoryAndSettingsTabs.kt
git commit -m "feat(history): add expandable set summary to routine history cards"
```

---

## Task 8: Update Call Sites

**Files:**
- Search for usages of `HistoryTab(` and add the new `kgToDisplay` parameter
- Likely in: `MainScreen.kt` or `App.kt`

**Step 1: Find HistoryTab call sites**

Search: `HistoryTab(`

**Step 2: Add kgToDisplay parameter**

The `kgToDisplay` function should already exist in the ViewModel or as a utility. Pass it to HistoryTab.

If it doesn't exist, add a simple conversion:

```kotlin
val kgToDisplay: (Float, WeightUnit) -> Float = { kg, unit ->
    if (unit == WeightUnit.LB) kg * 2.20462f else kg
}
```

**Step 3: Build full app**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add .
git commit -m "feat(history): wire up kgToDisplay for history tab"
```

---

## Task 9: Full Integration Test

**Step 1: Build Android app**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 2: Manual testing checklist**

- [ ] Complete a Just Lift set
- [ ] Verify SetSummaryCard shows after set
- [ ] Navigate to History tab
- [ ] Tap workout card - verify it expands with chevron rotation
- [ ] Verify SetSummaryCard content matches what was shown live
- [ ] Verify RPE is shown if it was logged
- [ ] Test with Echo mode workout - verify phase breakdown shows
- [ ] Test old history entries (if any) - verify "metrics unavailable" message
- [ ] Tap expanded card again - verify it collapses

**Step 3: Commit final state**

```bash
git add .
git commit -m "feat: complete set summary history feature

Addresses user feedback: set summary data is now persisted and can be
viewed in history by tapping workout cards.

- Added 16 new columns to WorkoutSession for summary metrics
- SetSummaryCard now supports isHistoryView mode
- History cards expand on tap to show detailed metrics
- Pre-v0.2.1 workouts show informational message"
```

---

## Summary

| Task | Description | Files Modified |
|------|-------------|----------------|
| 1 | Database schema | VitruvianDatabase.sq |
| 2 | Domain model | Models.kt |
| 3 | Repository layer | SqlDelightWorkoutRepository.kt |
| 4 | ViewModel save logic | MainViewModel.kt |
| 5 | SetSummaryCard isHistoryView | WorkoutTab.kt |
| 6 | WorkoutHistoryCard expansion | HistoryAndSettingsTabs.kt |
| 7 | GroupedRoutineCard expansion | HistoryAndSettingsTabs.kt |
| 8 | Wire up call sites | MainScreen.kt or similar |
| 9 | Integration test | Manual testing |
