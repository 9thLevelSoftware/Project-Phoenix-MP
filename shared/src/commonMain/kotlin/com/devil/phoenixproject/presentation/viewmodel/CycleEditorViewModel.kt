package com.devil.phoenixproject.presentation.viewmodel

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
    private val repository: TrainingCycleRepository
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
