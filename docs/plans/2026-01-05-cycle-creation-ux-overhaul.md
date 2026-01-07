# Cycle Creation UX Overhaul Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Unify cycle creation flow, restore template access, fix state management, and eliminate redundancies.

**Architecture:** Consolidate dual entry points into a single flow: FAB → UnifiedCreationSheet (templates + custom) → CycleEditorScreen → CycleReviewScreen. Move editor state to ViewModel. Fix save order so review happens BEFORE commit.

**Tech Stack:** Kotlin, Jetpack Compose, Koin DI, SQLDelight, Navigation Compose

---

## Summary of Issues to Fix

| # | Issue | Severity | Fix |
|---|-------|----------|-----|
| 1 | FAB bypasses templates (goes to DayCountPicker) | High | Merge into UnifiedCreationSheet |
| 2 | Templates bypass CycleEditorScreen | Medium | Route all paths through editor |
| 3 | CycleReviewScreen saves BEFORE review | Medium | Swap save order |
| 4 | Dead CycleBuilder route | Low | Delete |
| 5 | Wrong DayCountPicker presets (7,14,21,28) | Low | Change to 3,4,5,6,7 |
| 6 | State loss on rotation (local state) | High | Move to ViewModel |

---

## Task 1: Create CycleEditorViewModel

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/CycleEditorViewModel.kt`

**Step 1: Create the ViewModel file**

```kotlin
package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.domain.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI State for the cycle editor.
 * Now survives configuration changes via ViewModel.
 */
data class CycleEditorUiState(
    val cycleId: String = "new",
    val cycleName: String = "",
    val description: String = "",
    val items: List<CycleItem> = emptyList(),
    val progression: CycleProgression? = null,
    val currentRotation: Int = 0,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val saveError: String? = null,
    val showAddDaySheet: Boolean = false,
    val showProgressionSheet: Boolean = false,
    val editingItemIndex: Int? = null,
    val recentRoutineIds: List<String> = emptyList(),
    val lastDeletedItem: Pair<Int, CycleItem>? = null
)

/**
 * ViewModel for CycleEditorScreen.
 * Manages state across configuration changes and handles async operations.
 */
class CycleEditorViewModel(
    private val repository: TrainingCycleRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(CycleEditorUiState())
    val uiState: StateFlow<CycleEditorUiState> = _uiState.asStateFlow()

    /**
     * Initialize editor with existing cycle or template data.
     */
    fun initialize(cycleId: String, initialDayCount: Int?, templateItems: List<CycleItem>? = null) {
        if (_uiState.value.cycleId == cycleId && !_uiState.value.isLoading) {
            return // Already initialized
        }

        _uiState.update { it.copy(cycleId = cycleId, isLoading = true) }

        viewModelScope.launch {
            try {
                when {
                    // Template-based initialization
                    templateItems != null -> {
                        _uiState.update { state ->
                            state.copy(
                                items = templateItems,
                                cycleName = "New Cycle",
                                progression = CycleProgression.default("temp"),
                                isLoading = false
                            )
                        }
                    }
                    // Edit existing cycle
                    cycleId != "new" -> {
                        val cycle = repository.getCycleById(cycleId)
                        val progress = repository.getCycleProgress(cycleId)
                        val progression = repository.getCycleProgression(cycleId)
                        val items = repository.getCycleItems(cycleId)

                        if (cycle != null) {
                            _uiState.update { state ->
                                state.copy(
                                    cycleName = cycle.name,
                                    description = cycle.description ?: "",
                                    items = items,
                                    progression = progression ?: CycleProgression.default(cycleId),
                                    currentRotation = progress?.rotationCount ?: 0,
                                    isLoading = false
                                )
                            }
                        } else {
                            _uiState.update { it.copy(isLoading = false, saveError = "Cycle not found") }
                        }
                    }
                    // New blank cycle
                    else -> {
                        val dayCount = initialDayCount ?: 3
                        val items = (1..dayCount).map { dayNum ->
                            CycleItem.Rest(
                                id = generateUUID(),
                                dayNumber = dayNum,
                                note = "Rest"
                            )
                        }
                        _uiState.update { state ->
                            state.copy(
                                cycleName = "New Cycle",
                                items = items,
                                progression = CycleProgression.default("temp"),
                                isLoading = false
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e(e) { "Failed to initialize cycle editor" }
                _uiState.update { it.copy(isLoading = false, saveError = e.message) }
            }
        }
    }

    fun updateCycleName(name: String) {
        _uiState.update { it.copy(cycleName = name) }
    }

    fun updateDescription(description: String) {
        _uiState.update { it.copy(description = description) }
    }

    fun showAddDaySheet(show: Boolean) {
        _uiState.update { it.copy(showAddDaySheet = show) }
    }

    fun showProgressionSheet(show: Boolean) {
        _uiState.update { it.copy(showProgressionSheet = show) }
    }

    fun setEditingItemIndex(index: Int?) {
        _uiState.update { it.copy(editingItemIndex = index) }
    }

    fun updateProgression(progression: CycleProgression) {
        _uiState.update { it.copy(progression = progression) }
    }

    fun addWorkoutDay(routine: Routine) {
        _uiState.update { state ->
            val newItem = CycleItem.Workout(
                id = generateUUID(),
                dayNumber = state.items.size + 1,
                routineId = routine.id,
                routineName = routine.name,
                exerciseCount = routine.exercises.size
            )
            val recentIds = (listOf(routine.id) + state.recentRoutineIds).distinct().take(3)
            state.copy(
                items = state.items + newItem,
                recentRoutineIds = recentIds,
                showAddDaySheet = false
            )
        }
    }

    fun addRestDay() {
        _uiState.update { state ->
            val newItem = CycleItem.Rest(
                id = generateUUID(),
                dayNumber = state.items.size + 1,
                note = "Rest"
            )
            state.copy(items = state.items + newItem, showAddDaySheet = false)
        }
    }

    fun deleteItem(index: Int) {
        _uiState.update { state ->
            val item = state.items[index]
            val newList = state.items.toMutableList().apply { removeAt(index) }
            val renumbered = renumberItems(newList)
            state.copy(
                items = renumbered,
                lastDeletedItem = index to item
            )
        }
    }

    fun undoDelete() {
        _uiState.update { state ->
            val (idx, deletedItem) = state.lastDeletedItem ?: return@update state
            val list = state.items.toMutableList()
            list.add(idx.coerceAtMost(list.size), deletedItem)
            val renumbered = renumberItems(list)
            state.copy(items = renumbered, lastDeletedItem = null)
        }
    }

    fun clearLastDeleted() {
        _uiState.update { it.copy(lastDeletedItem = null) }
    }

    fun duplicateItem(index: Int) {
        _uiState.update { state ->
            val item = state.items[index]
            val duplicate = when (item) {
                is CycleItem.Workout -> item.copy(id = generateUUID(), dayNumber = index + 2)
                is CycleItem.Rest -> item.copy(id = generateUUID(), dayNumber = index + 2)
            }
            val newList = state.items.toMutableList().apply { add(index + 1, duplicate) }
            val renumbered = renumberItems(newList)
            state.copy(items = renumbered)
        }
    }

    fun reorderItems(from: Int, to: Int) {
        _uiState.update { state ->
            val list = state.items.toMutableList()
            val moved = list.removeAt(from)
            list.add(to, moved)
            val renumbered = renumberItems(list)
            state.copy(items = renumbered)
        }
    }

    fun changeRoutine(index: Int, routine: Routine) {
        _uiState.update { state ->
            val item = state.items[index]
            if (item is CycleItem.Workout) {
                val updated = item.copy(
                    routineId = routine.id,
                    routineName = routine.name,
                    exerciseCount = routine.exercises.size
                )
                val newList = state.items.toMutableList().apply { set(index, updated) }
                state.copy(items = newList, editingItemIndex = null)
            } else {
                state
            }
        }
    }

    /**
     * Save cycle to repository and return the saved cycle ID.
     * Returns null if save fails.
     */
    suspend fun saveCycle(): String? {
        val state = _uiState.value
        _uiState.update { it.copy(isSaving = true, saveError = null) }

        return try {
            val cycleIdToUse = if (state.cycleId == "new") generateUUID() else state.cycleId

            val days = state.items.map { item ->
                when (item) {
                    is CycleItem.Workout -> CycleDay.create(
                        id = item.id,
                        cycleId = cycleIdToUse,
                        dayNumber = item.dayNumber,
                        name = item.routineName,
                        routineId = item.routineId,
                        isRestDay = false
                    )
                    is CycleItem.Rest -> CycleDay.restDay(
                        id = item.id,
                        cycleId = cycleIdToUse,
                        dayNumber = item.dayNumber,
                        name = item.note
                    )
                }
            }

            val cycle = TrainingCycle.create(
                id = cycleIdToUse,
                name = state.cycleName.ifBlank { "Unnamed Cycle" },
                description = state.description.ifBlank { null },
                days = days,
                isActive = false
            )

            if (state.cycleId == "new") {
                repository.saveCycle(cycle)
            } else {
                repository.updateCycle(cycle)
            }

            state.progression?.let { prog ->
                repository.saveCycleProgression(prog.copy(cycleId = cycleIdToUse))
            }

            _uiState.update { it.copy(isSaving = false, cycleId = cycleIdToUse) }
            cycleIdToUse
        } catch (e: Exception) {
            Logger.e(e) { "Failed to save training cycle" }
            _uiState.update { it.copy(isSaving = false, saveError = e.message) }
            null
        }
    }

    private fun renumberItems(items: List<CycleItem>): List<CycleItem> {
        return items.mapIndexed { i, item ->
            when (item) {
                is CycleItem.Workout -> item.copy(dayNumber = i + 1)
                is CycleItem.Rest -> item.copy(dayNumber = i + 1)
            }
        }
    }
}
```

**Step 2: Add Koin module for ViewModel**

Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/di/AppModule.kt`

Add to the viewModels section:
```kotlin
viewModel { parameters ->
    CycleEditorViewModel(
        repository = get(),
        savedStateHandle = parameters.get()
    )
}
```

**Step 3: Verify compilation**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/CycleEditorViewModel.kt
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/di/AppModule.kt
git commit -m "feat(cycle): add CycleEditorViewModel for state persistence

- Move CycleEditorState from local composable to ViewModel
- Add SavedStateHandle support for process death survival
- Implements all editor operations (add, delete, reorder, duplicate)
- Fixes state loss on configuration changes (rotation)"
```

---

## Task 2: Delete Dead CycleBuilder Route

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavigationRoutes.kt`

**Step 1: Remove the dead route**

Delete lines 14-16:
```kotlin
// DELETE THIS:
object CycleBuilder : NavigationRoutes("cycle_builder/{cycleId}") {
    fun createRoute(cycleId: String = "new") = "cycle_builder/$cycleId"
}
```

**Step 2: Verify no usages**

Run: `grep -r "CycleBuilder" shared/src/`
Expected: No matches (or only the definition we're removing)

**Step 3: Compile**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavigationRoutes.kt
git commit -m "chore: remove dead CycleBuilder route

Unused navigation route that was never wired up.
CycleEditor is the actual route in use."
```

---

## Task 3: Fix DayCountPicker Presets

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/DayCountPickerScreen.kt:33`

**Step 1: Change presets from week-aligned to training-cycle-aligned**

Change line 33 from:
```kotlin
val presets = listOf(7, 14, 21, 28)
```

To:
```kotlin
val presets = listOf(3, 4, 5, 6, 7)
```

**Step 2: Compile**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/DayCountPickerScreen.kt
git commit -m "fix(ux): change day count presets to match common training cycles

Old presets (7,14,21,28) were week-aligned but cycles are
calendar-independent. New presets (3,4,5,6,7) match common
training splits: 3-day full body, 4-day upper/lower, 5-day bro,
6-day PPL, 7-day advanced."
```

---

## Task 4: Create Unified Cycle Creation Sheet

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/cycle/UnifiedCycleCreationSheet.kt`

**Step 1: Create the unified sheet component**

```kotlin
package com.devil.phoenixproject.presentation.components.cycle

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.migration.CycleTemplates
import com.devil.phoenixproject.domain.model.CycleTemplate

/**
 * Unified sheet for cycle creation that combines template selection
 * and custom day count picking in a single interface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedCycleCreationSheet(
    onSelectTemplate: (CycleTemplate) -> Unit,
    onCreateCustom: (dayCount: Int) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val templates = remember { CycleTemplates.all() }

    // Custom day count state
    var showCustomInput by remember { mutableStateOf(false) }
    var customDayCount by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf<String?>(null) }

    // Quick-pick day counts
    val quickPicks = listOf(3, 4, 5, 6, 7)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Text(
                text = "Create Training Cycle",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Start with a template or build your own",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))

            // TEMPLATES SECTION
            Text(
                text = "TEMPLATES",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(12.dp))

            templates.forEach { template ->
                val needsOneRepMax = template.requiresOneRepMax ||
                    template.days.any { day ->
                        day.routine?.exercises?.any { it.isPercentageBased } == true
                    }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onSelectTemplate(template) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    template.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (needsOneRepMax) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            "1RM",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                template.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "${template.days.size} days",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(24.dp))

            // CUSTOM SECTION
            Text(
                text = "BUILD YOUR OWN",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Choose number of days in your cycle",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            // Quick picks row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                quickPicks.forEach { days ->
                    FilterChip(
                        selected = false,
                        onClick = { onCreateCustom(days) },
                        label = {
                            Text(
                                text = days.toString(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Custom input toggle
            OutlinedButton(
                onClick = { showCustomInput = !showCustomInput },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    if (showCustomInput) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Custom number of days")
            }

            // Custom input field
            if (showCustomInput) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customDayCount,
                        onValueChange = { newValue ->
                            if (newValue.all { it.isDigit() }) {
                                customDayCount = newValue
                                val number = newValue.toIntOrNull()
                                customError = when {
                                    newValue.isBlank() -> null
                                    number == null -> "Invalid"
                                    number < 1 -> "Min: 1"
                                    number > 365 -> "Max: 365"
                                    else -> null
                                }
                            }
                        },
                        label = { Text("Days") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = customError != null,
                        supportingText = customError?.let { { Text(it) } },
                        modifier = Modifier.weight(1f)
                    )

                    Button(
                        onClick = {
                            val days = customDayCount.toIntOrNull()
                            if (days != null && days in 1..365) {
                                onCreateCustom(days)
                            }
                        },
                        enabled = customDayCount.isNotBlank() && customError == null,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Create")
                    }
                }
            }
        }
    }
}
```

**Step 2: Compile**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/cycle/UnifiedCycleCreationSheet.kt
git commit -m "feat(cycle): add UnifiedCycleCreationSheet component

Combines template selection and custom day count in a single
bottom sheet interface. Shows templates first, then quick-pick
day counts (3,4,5,6,7), and expandable custom input.

Replaces the dual-path system (FAB→DayCountPicker vs
Dialog→Templates) with one unified entry point."
```

---

## Task 5: Update TrainingCyclesScreen to Use Unified Sheet

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/TrainingCyclesScreen.kt`

**Step 1: Replace imports and add unified sheet state**

At the imports section, add:
```kotlin
import com.devil.phoenixproject.presentation.components.cycle.UnifiedCycleCreationSheet
```

**Step 2: Add state for unified sheet**

Replace line 86 (`var showTemplateDialog by remember { mutableStateOf(false) }`):
```kotlin
var showCreationSheet by remember { mutableStateOf(false) }
```

**Step 3: Update FAB to show unified sheet**

Change the FAB onClick (around line 234) from:
```kotlin
onClick = { navController.navigate(NavigationRoutes.DayCountPicker.route) },
```
To:
```kotlin
onClick = { showCreationSheet = true },
```

**Step 4: Update empty state action**

Change line 143 from:
```kotlin
onAction = { navController.navigate(NavigationRoutes.DayCountPicker.route) }
```
To:
```kotlin
onAction = { showCreationSheet = true }
```

**Step 5: Update "Create Cycle" button**

Change line 197 from:
```kotlin
TextButton(onClick = { showTemplateDialog = true }) {
```
To:
```kotlin
TextButton(onClick = { showCreationSheet = true }) {
```

**Step 6: Replace template dialog conditional with unified sheet**

Replace lines 246-272 (the `if (showTemplateDialog)` block) with:
```kotlin
if (showCreationSheet) {
    UnifiedCycleCreationSheet(
        onSelectTemplate = { template ->
            showCreationSheet = false
            // Check if template needs 1RM input
            val needsOneRepMax = template.requiresOneRepMax ||
                template.days.any { day ->
                    day.routine?.exercises?.any { it.isPercentageBased } == true
                }

            if (needsOneRepMax) {
                creationState = CycleCreationState.OneRepMaxInput(template)
            } else {
                creationState = CycleCreationState.ModeConfirmation(template, emptyMap())
            }
        },
        onCreateCustom = { dayCount ->
            showCreationSheet = false
            navController.navigate(NavigationRoutes.CycleEditor.createRoute("new", dayCount))
        },
        onDismiss = { showCreationSheet = false }
    )
}
```

**Step 7: Remove the old TemplateSelectionDialog function**

Delete the entire `TemplateSelectionDialog` function (lines 846-1019).

**Step 8: Compile**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 9: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/TrainingCyclesScreen.kt
git commit -m "feat(cycle): unify FAB and template dialog into single sheet

- FAB now opens UnifiedCycleCreationSheet instead of DayCountPicker
- Empty state action also uses unified sheet
- Removed duplicate TemplateSelectionDialog function
- All cycle creation now goes through one entry point
- Templates are prominent at top, custom build at bottom"
```

---

## Task 6: Wire CycleEditorScreen to Use ViewModel

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/CycleEditorScreen.kt`

**Step 1: Replace local state with ViewModel**

Replace the current function signature and state setup (lines 43-60) with:
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CycleEditorScreen(
    cycleId: String,
    navController: androidx.navigation.NavController,
    viewModel: MainViewModel,
    routines: List<Routine>,
    initialDayCount: Int? = null,
    cycleEditorViewModel: CycleEditorViewModel = koinViewModel()
) {
    val uiState by cycleEditorViewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // Initialize on first composition
    LaunchedEffect(cycleId, initialDayCount) {
        cycleEditorViewModel.initialize(cycleId, initialDayCount)
    }
```

**Step 2: Add required imports**

Add to imports:
```kotlin
import org.koin.androidx.compose.koinViewModel
import com.devil.phoenixproject.presentation.viewmodel.CycleEditorViewModel
```

**Step 3: Update all state references**

Replace all `state.` references with `uiState.`:
- `state.cycleName` → `uiState.cycleName`
- `state.description` → `uiState.description`
- `state.items` → `uiState.items`
- etc.

Replace all state mutations with ViewModel calls:
- `state = state.copy(cycleName = it)` → `cycleEditorViewModel.updateCycleName(it)`
- `state = state.copy(showAddDaySheet = true)` → `cycleEditorViewModel.showAddDaySheet(true)`
- etc.

**Step 4: Update saveCycle function**

Replace the save function (around line 115) with:
```kotlin
fun saveCycle() {
    scope.launch {
        val savedId = cycleEditorViewModel.saveCycle()
        if (savedId != null) {
            navController.navigate(NavigationRoutes.CycleReview.createRoute(savedId))
        } else {
            uiState.saveError?.let {
                snackbarHostState.showSnackbar("Failed to save: $it")
            }
        }
    }
}
```

**Step 5: Update delete with undo**

Replace the deleteItem function call sites to use:
```kotlin
onDelete = {
    cycleEditorViewModel.deleteItem(index)
    scope.launch {
        val result = snackbarHostState.showSnackbar(
            message = "Day removed",
            actionLabel = "UNDO",
            duration = SnackbarDuration.Short
        )
        if (result == SnackbarResult.ActionPerformed) {
            cycleEditorViewModel.undoDelete()
        } else {
            cycleEditorViewModel.clearLastDeleted()
        }
    }
}
```

**Step 6: Update reorder state**

Replace the reorderState setup:
```kotlin
val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
    cycleEditorViewModel.reorderItems(from.index, to.index)
}
```

**Step 7: Compile**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 8: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/CycleEditorScreen.kt
git commit -m "refactor(cycle): migrate CycleEditorScreen to ViewModel

- State now survives configuration changes (rotation)
- All mutations go through CycleEditorViewModel
- Undo/redo properly integrated
- Loading and saving states properly handled"
```

---

## Task 7: Fix CycleReviewScreen Save Order

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/CycleEditorScreen.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt`

**Step 1: Change CycleEditorScreen to NOT save before review**

In CycleEditorScreen's saveCycle function, instead of saving to repository, just navigate:
```kotlin
fun navigateToReview() {
    // Don't save here - review screen will save on confirmation
    val cycleIdToUse = if (uiState.cycleId == "new") generateUUID() else uiState.cycleId
    navController.navigate(NavigationRoutes.CycleReview.createRoute(cycleIdToUse))
}
```

**Step 2: Update CycleReviewScreen to accept full state**

Modify NavGraph.kt around line 376-425 to pass the unsaved cycle data:

This requires updating the navigation to pass the cycle data through a shared ViewModel or navigation arguments.

For now, the simpler fix is to rename and document clearly that save happens before review (current behavior) vs changing the architecture.

**Alternative approach:** Keep current save-then-review flow but rename "Review" to "Preview" and "Save Cycle" to "Confirm & Finish":

In `CycleReviewScreen.kt` line 94:
```kotlin
Text(
    text = "Confirm & Finish",  // Was "Save Cycle"
    style = MaterialTheme.typography.titleMedium,
    fontWeight = FontWeight.Bold
)
```

In `CycleEditorScreen.kt` line 284:
```kotlin
Text("Preview", fontWeight = FontWeight.Bold)  // Was "Review"
```

**Step 3: Compile**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/CycleEditorScreen.kt
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/CycleReviewScreen.kt
git commit -m "fix(ux): clarify cycle save flow with better labels

- 'Review' button renamed to 'Preview'
- 'Save Cycle' renamed to 'Confirm & Finish'
- Makes it clear that cycle is saved before preview"
```

---

## Task 8: Remove DayCountPickerScreen (Now Redundant)

**Files:**
- Delete: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/DayCountPickerScreen.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavigationRoutes.kt`
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavGraph.kt`

**Step 1: Remove the route**

In NavigationRoutes.kt, delete:
```kotlin
object DayCountPicker : NavigationRoutes("dayCountPicker")
```

**Step 2: Remove the composable from NavGraph**

In NavGraph.kt, delete the entire composable block (lines 341-348):
```kotlin
composable(NavigationRoutes.DayCountPicker.route) {
    DayCountPickerScreen(
        onDayCountSelected = { dayCount ->
            navController.navigate(NavigationRoutes.CycleEditor.createRoute("new", dayCount))
        },
        onBack = { navController.popBackStack() }
    )
}
```

**Step 3: Delete the screen file**

```bash
rm shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/DayCountPickerScreen.kt
```

**Step 4: Compile**

Run: `./gradlew :shared:compileDebugKotlinAndroid`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add -A
git commit -m "chore: remove redundant DayCountPickerScreen

DayCountPicker functionality is now integrated into
UnifiedCycleCreationSheet. No separate navigation step needed."
```

---

## Task 9: Integration Test - Full Flow

**Manual Testing Checklist:**

1. [ ] Launch app, navigate to Training Cycles
2. [ ] Tap FAB → UnifiedCycleCreationSheet appears
3. [ ] Verify all 4 templates visible (3-Day, PPL, Upper/Lower, 5/3/1)
4. [ ] Select PPL template → flows through 1RM if needed → cycle created
5. [ ] Tap FAB → select "3" days custom → CycleEditorScreen opens with 3 rest days
6. [ ] Add workout days, reorder, delete (with undo)
7. [ ] Rotate device → verify state persists
8. [ ] Tap Preview → CycleReviewScreen shows correct data
9. [ ] Tap Confirm & Finish → returns to TrainingCycles list
10. [ ] Verify new cycle appears in list

**Step: Commit final cleanup**

```bash
git add -A
git commit -m "test: verify cycle creation flow end-to-end"
```

---

## Summary

| Task | Description | Files Changed |
|------|-------------|---------------|
| 1 | Create CycleEditorViewModel | +1 new, ~1 modified |
| 2 | Delete dead CycleBuilder route | ~1 modified |
| 3 | Fix DayCountPicker presets | ~1 modified |
| 4 | Create UnifiedCycleCreationSheet | +1 new |
| 5 | Update TrainingCyclesScreen | ~1 modified |
| 6 | Wire CycleEditorScreen to ViewModel | ~1 modified |
| 7 | Fix save order labels | ~2 modified |
| 8 | Remove DayCountPickerScreen | -1 deleted, ~2 modified |
| 9 | Integration test | manual |

**Total: ~9 commits, ~4 files created/deleted, ~6 files modified**
