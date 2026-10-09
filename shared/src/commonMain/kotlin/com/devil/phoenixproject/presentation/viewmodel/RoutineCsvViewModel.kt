package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.csv.CsvImportIntakeOwnership
import com.devil.phoenixproject.data.csv.CsvImportIntakePhase
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.RoutineCsvImportConflictException
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.csv.RoutineCsvCodec
import com.devil.phoenixproject.domain.csv.RoutineCsvExportResult
import com.devil.phoenixproject.domain.csv.RoutineCsvFormat
import com.devil.phoenixproject.domain.csv.RoutineCsvImportMode
import com.devil.phoenixproject.domain.csv.RoutineCsvImportPlan
import com.devil.phoenixproject.domain.csv.RoutineCsvImportPlanner
import com.devil.phoenixproject.domain.csv.RoutineCsvIssue
import com.devil.phoenixproject.domain.csv.RoutineCsvParseResult
import com.devil.phoenixproject.domain.csv.RoutineCsvRoutineDraft
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
        /** The mode the user just picked while its plan is built; [plan] is still the previous one. */
        val replanningTo: RoutineCsvImportMode? = null,
    ) : RoutineCsvImportUiState

    data class Imported(val routineCount: Int) : RoutineCsvImportUiState
}

/**
 * Host-only result of an intent-driven import (#1242): where to navigate after the commit.
 * The Daily Routines import dialog ignores this; only the intent host reads it.
 */
data class RoutineCsvImportHostResult(
    val profileId: String,
    val firstRoutineId: String,
    val firstName: String,
    val routineCount: Int,
    /** The committed routine's `updatedAt`; the navigation wait requires it (B3). */
    val committedUpdatedAt: Long,
    /** True when the first written routine replaced an existing one (its id pre-existed). */
    val overwrite: Boolean,
)

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

    /** Host-only intent import result (#1242); cleared by the host after navigation. */
    private val _hostResult = MutableStateFlow<RoutineCsvImportHostResult?>(null)
    val hostResult: StateFlow<RoutineCsvImportHostResult?> = _hostResult.asStateFlow()

    /** B4 delivery ownership for the intent intake: one active delivery, at most one commit. */
    val intakeOwnership = CsvImportIntakeOwnership()

    private var drafts: List<RoutineCsvRoutineDraft> = emptyList()
    private var previewProfileId: String? = null

    /** The one parse or re-plan in flight; a newer request cancels it so only the latest lands. */
    private var planJob: Job? = null

    /** Parses [content] and shows a preview for the active profile. */
    fun previewImport(content: String) {
        planJob?.cancel()
        planJob = viewModelScope.launch {
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

    /**
     * Intent-driven import (#1242): same parse/plan/commit machinery as [previewImport], but a
     * clean `CREATE_NEW` plan with no issues commits immediately and skips the preview. Name
     * collisions, ambiguous names and planner issues land in the existing preview dialog before
     * anything is written. The commit publishes a host-only [RoutineCsvImportHostResult].
     */
    fun importIncoming(content: String) {
        planJob?.cancel()
        planJob = viewModelScope.launch {
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
                    if (!plan.hasMatches && plan.canCommit) {
                        commitIncoming(profileId, plan)
                    } else {
                        val initial = if (plan.hasMatches) plan(profileId, RoutineCsvImportMode.CREATE_COPIES) else plan
                        _importState.value = RoutineCsvImportUiState.Preview(initial)
                    }
                }
            }
        }
    }

    /**
     * The clean-create auto-commit of [importIncoming]. Rebuilds the plan from current data
     * first; a plan that changed under it falls back to the preview instead of writing.
     */
    private suspend fun commitIncoming(profileId: String, initial: RoutineCsvImportPlan) {
        intakeOwnership.moveTo(CsvImportIntakePhase.COMMITTING)
        try {
            if (activeProfileId() != profileId) {
                _importState.value = RoutineCsvImportUiState.Unreadable(listOf(PROFILE_CHANGED))
                return
            }
            val fresh = plan(profileId, initial.mode)
            if (fresh.routines != initial.routines || !fresh.canCommit) {
                _importState.value = RoutineCsvImportUiState.Preview(fresh, refreshed = true)
                return
            }
            workoutRepository.commitRoutineCsvImport(
                profileId = profileId,
                newGroups = fresh.newGroups,
                routines = fresh.writes,
                overwriteRoutineIds = fresh.overwriteRoutineIds,
            )
            drafts = emptyList()
            previewProfileId = null
            // No Preview, no Imported-count dialog on this path: the host navigates instead.
            _importState.value = null
            publishHostResult(profileId, fresh)
            intakeOwnership.moveTo(CsvImportIntakePhase.RESULT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RoutineCsvImportConflictException) {
            Logger.w(e) { "Routine CSV import target changed before commit" }
            _importState.value = RoutineCsvImportUiState.Preview(plan(profileId, initial.mode), refreshed = true)
        } catch (e: Exception) {
            Logger.e(e) { "Routine CSV import failed; nothing was written" }
            _importState.value = RoutineCsvImportUiState.Preview(initial, commitFailed = true)
        }
    }

    private fun publishHostResult(profileId: String, plan: RoutineCsvImportPlan) {
        val first = plan.writes.firstOrNull() ?: return
        _hostResult.value = RoutineCsvImportHostResult(
            profileId = profileId,
            firstRoutineId = first.id,
            firstName = first.name,
            routineCount = plan.writes.size,
            committedUpdatedAt = first.updatedAt ?: nowMs(),
            overwrite = first.id in plan.overwriteRoutineIds,
        )
    }

    /** Clears the consumed host result so a later navigation never replays it. */
    fun clearHostResult() {
        _hostResult.value = null
    }

    /**
     * Re-plans the preview for [mode]. Until that plan is shown the preview cannot be confirmed,
     * so a quick tap on Import never writes the plan of the previously selected mode.
     */
    fun selectMode(mode: RoutineCsvImportMode) {
        val profileId = previewProfileId ?: return
        val preview = _importState.value as? RoutineCsvImportUiState.Preview ?: return
        if (preview.committing) return
        planJob?.cancel()
        _importState.value = preview.copy(replanningTo = mode, refreshed = false, commitFailed = false)
        planJob = viewModelScope.launch {
            _importState.value = try {
                RoutineCsvImportUiState.Preview(plan(profileId, mode))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e) { "Routine CSV import could not be re-planned" }
                preview
            }
        }
    }

    /**
     * Writes the previewed import. The plan is rebuilt from current data first; when it no
     * longer matches what the user saw, including which routine each overwrite replaces, the
     * refreshed preview is shown instead of writing.
     */
    fun confirmImport() {
        val preview = _importState.value as? RoutineCsvImportUiState.Preview ?: return
        val profileId = previewProfileId ?: return
        if (preview.committing || preview.replanningTo != null || !preview.plan.canCommit) return
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
                publishHostResult(profileId, fresh)
                intakeOwnership.moveTo(CsvImportIntakePhase.RESULT)
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

    /** The picked file is over [RoutineCsvFormat.MAX_BYTES]; it was not read. */
    fun onFileTooLarge() {
        _importState.value = RoutineCsvImportUiState.Unreadable(listOf(RoutineCsvIssue(null, RoutineCsvFormat.TOO_LARGE_MESSAGE)))
    }

    /**
     * The active profile changed while an intent import waited for display readiness (B3).
     * The commit stands (or never happened); navigation is cancelled with this explanation
     * instead of retargeting another profile.
     */
    fun onProfileChangedDuringWait() {
        _importState.value = RoutineCsvImportUiState.Unreadable(listOf(PROFILE_CHANGED))
    }

    fun dismissImport() {
        if ((_importState.value as? RoutineCsvImportUiState.Preview)?.committing == true) return
        planJob?.cancel()
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
