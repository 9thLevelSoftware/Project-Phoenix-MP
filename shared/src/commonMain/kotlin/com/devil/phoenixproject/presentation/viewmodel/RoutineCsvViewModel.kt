package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.RoutineCsvImportConflictException
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.csv.RoutineCsvCodec
import com.devil.phoenixproject.domain.csv.RoutineCsvExportResult
import com.devil.phoenixproject.domain.csv.RoutineCsvImportMode
import com.devil.phoenixproject.domain.csv.RoutineCsvImportPlan
import com.devil.phoenixproject.domain.csv.RoutineCsvImportPlanner
import com.devil.phoenixproject.domain.csv.RoutineCsvIssue
import com.devil.phoenixproject.domain.csv.RoutineCsvParseResult
import com.devil.phoenixproject.domain.csv.RoutineCsvRoutineDraft
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Import dialog state (#772). */
sealed interface RoutineCsvImportUiState {
    /** The file could not be read as a v1 routine CSV; nothing was matched. */
    data class Unreadable(val issues: List<RoutineCsvIssue>) : RoutineCsvImportUiState

    /** A preview. Nothing is written until [RoutineCsvViewModel.confirmImport]. */
    data class Preview(
        val plan: RoutineCsvImportPlan,
        val committing: Boolean = false,
        /** The profile's routines changed after the preview; this is the refreshed plan. */
        val refreshed: Boolean = false,
        val commitFailed: Boolean = false,
    ) : RoutineCsvImportUiState

    data class Imported(val routineCount: Int) : RoutineCsvImportUiState
}

/** Export state for one routine (#772). */
sealed interface RoutineCsvExportUiState {
    data class Ready(val fileName: String, val content: String) : RoutineCsvExportUiState

    data class Blocked(val routineName: String, val reasons: List<String>) : RoutineCsvExportUiState
}

/**
 * Routine CSV import and export for Daily Routines (#772). The file is parsed and matched
 * against the active profile without writing anything; a confirmed import is written in one
 * repository transaction.
 */
class RoutineCsvViewModel(
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
    private val userProfileRepository: UserProfileRepository,
    private val planner: RoutineCsvImportPlanner = RoutineCsvImportPlanner(),
    private val nowMs: () -> Long = ::currentTimeMillis,
) : ViewModel() {
    private val _importState = MutableStateFlow<RoutineCsvImportUiState?>(null)
    val importState: StateFlow<RoutineCsvImportUiState?> = _importState.asStateFlow()

    private val _exportState = MutableStateFlow<RoutineCsvExportUiState?>(null)
    val exportState: StateFlow<RoutineCsvExportUiState?> = _exportState.asStateFlow()

    private var drafts: List<RoutineCsvRoutineDraft> = emptyList()
    private var previewProfileId: String? = null

    /** Parses [content] and shows a preview for the active profile. */
    fun previewImport(content: String) {
        viewModelScope.launch {
            when (val parsed = RoutineCsvCodec.parse(content)) {
                is RoutineCsvParseResult.Invalid -> _importState.value = RoutineCsvImportUiState.Unreadable(parsed.issues)

                is RoutineCsvParseResult.Parsed -> {
                    val profileId = activeProfileId() ?: run {
                        _importState.value = RoutineCsvImportUiState.Unreadable(listOf(PROFILE_SWITCHING))
                        return@launch
                    }
                    drafts = parsed.routines
                    previewProfileId = profileId
                    val plan = plan(profileId, RoutineCsvImportMode.CREATE_NEW)
                    // Matches exist: start on the choice that changes nothing already saved.
                    val initial = if (plan.hasMatches) plan(profileId, RoutineCsvImportMode.CREATE_COPIES) else plan
                    _importState.value = RoutineCsvImportUiState.Preview(initial)
                }
            }
        }
    }

    /** Re-plans the preview for [mode]. */
    fun selectMode(mode: RoutineCsvImportMode) {
        val profileId = previewProfileId ?: return
        if ((_importState.value as? RoutineCsvImportUiState.Preview)?.committing == true) return
        viewModelScope.launch {
            _importState.value = RoutineCsvImportUiState.Preview(plan(profileId, mode))
        }
    }

    /**
     * Writes the previewed import. The plan is rebuilt from current data first; when it no
     * longer matches what the user saw, the refreshed preview is shown instead of writing.
     */
    fun confirmImport() {
        val preview = _importState.value as? RoutineCsvImportUiState.Preview ?: return
        val profileId = previewProfileId ?: return
        if (preview.committing || !preview.plan.canCommit) return
        _importState.value = preview.copy(committing = true, commitFailed = false)
        viewModelScope.launch {
            try {
                if (activeProfileId() != profileId) {
                    _importState.value = RoutineCsvImportUiState.Unreadable(listOf(PROFILE_CHANGED))
                    return@launch
                }
                val fresh = plan(profileId, preview.plan.mode)
                if (fresh.routines != preview.plan.routines || !fresh.canCommit) {
                    _importState.value = RoutineCsvImportUiState.Preview(fresh, refreshed = true)
                    return@launch
                }
                workoutRepository.commitRoutineCsvImport(
                    profileId = profileId,
                    newGroups = fresh.newGroups,
                    routines = fresh.writes,
                    overwriteRoutineIds = fresh.overwriteRoutineIds,
                )
                drafts = emptyList()
                previewProfileId = null
                _importState.value = RoutineCsvImportUiState.Imported(fresh.writes.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RoutineCsvImportConflictException) {
                Logger.w(e) { "Routine CSV import target changed before commit" }
                _importState.value = RoutineCsvImportUiState.Preview(plan(profileId, preview.plan.mode), refreshed = true)
            } catch (e: Exception) {
                Logger.e(e) { "Routine CSV import failed; nothing was written" }
                _importState.value = preview.copy(committing = false, commitFailed = true)
            }
        }
    }

    /** The picked file could not be read at all. */
    fun onFileUnreadable() {
        _importState.value = RoutineCsvImportUiState.Unreadable(listOf(FILE_UNREADABLE))
    }

    fun dismissImport() {
        if ((_importState.value as? RoutineCsvImportUiState.Preview)?.committing == true) return
        drafts = emptyList()
        previewProfileId = null
        _importState.value = null
    }

    /** Builds the CSV for [routine], or the reasons it cannot be exported. */
    fun exportRoutine(routine: Routine) {
        viewModelScope.launch {
            val group = routine.groupId?.let { groupId ->
                runCatching { workoutRepository.getRoutineGroupsSnapshot(routine.profileId) }
                    .getOrDefault(emptyList())
                    .firstOrNull { it.id == groupId }
            }
            _exportState.value = when (val result = RoutineCsvCodec.encode(routine, group?.name, group?.orderIndex)) {
                is RoutineCsvExportResult.Exported -> RoutineCsvExportUiState.Ready(result.fileName, result.content)
                is RoutineCsvExportResult.Blocked -> RoutineCsvExportUiState.Blocked(routine.name, result.reasons)
            }
        }
    }

    fun clearExport() {
        _exportState.value = null
    }

    private suspend fun plan(profileId: String, mode: RoutineCsvImportMode): RoutineCsvImportPlan = planner.plan(
        drafts = drafts,
        mode = mode,
        profileId = profileId,
        existingRoutines = workoutRepository.getRoutineHeaders(profileId),
        existingGroups = workoutRepository.getRoutineGroupsSnapshot(profileId),
        exerciseLibrary = exerciseRepository.getAllExercises().first(),
        nowMs = nowMs(),
    )

    private fun activeProfileId(): String? =
        (userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready)?.profile?.id

    private companion object {
        val FILE_UNREADABLE = RoutineCsvIssue(null, "The file could not be read.")
        val PROFILE_SWITCHING = RoutineCsvIssue(null, "The profile is switching. Try the import again in a moment.")
        val PROFILE_CHANGED = RoutineCsvIssue(null, "The active profile changed. Pick the file again to import it into this profile.")
    }
}
