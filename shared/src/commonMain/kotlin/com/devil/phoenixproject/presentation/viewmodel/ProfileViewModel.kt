package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devil.phoenixproject.data.integration.ExternalMeasurementRepository
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.MAX_RECENT_EXERCISE_SESSIONS
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.usecase.CurrentOneRepMax
import com.devil.phoenixproject.domain.usecase.ResolveCurrentOneRepMaxUseCase
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

sealed interface ProfileLoadable<out T> {
    data object Empty : ProfileLoadable<Nothing>
    data object Loading : ProfileLoadable<Nothing>
    data class Ready<T>(val value: T) : ProfileLoadable<T>
    data class Failed(val cause: Throwable) : ProfileLoadable<Nothing>
}

data class ProfilePrHighlights(
    val maxWeightPerCableKg: Float?,
    val estimatedOneRepMaxPerCableKg: Float?,
    val maxVolumeKg: Float?,
)

data class ProfileUiState(
    val context: ActiveProfileContext? = null,
    val selectedExercise: Exercise? = null,
    val missingExerciseId: String? = null,
    val selectionFailure: Throwable? = null,
    val currentOneRepMax: ProfileLoadable<CurrentOneRepMax> = ProfileLoadable.Empty,
    val prHighlights: ProfileLoadable<ProfilePrHighlights> = ProfileLoadable.Empty,
    val recentSessions: ProfileLoadable<List<WorkoutSession>> = ProfileLoadable.Empty,
)

sealed interface ProfileUiEvent {
    data object PreferenceUpdateFailed : ProfileUiEvent
    data object IdentityUpdateFailed : ProfileUiEvent
    data object IdentityUpdated : ProfileUiEvent
    data object ProfileDeleted : ProfileUiEvent
}

class ProfileViewModel(
    private val profiles: UserProfileRepository,
    private val exercises: ExerciseRepository,
    private val workouts: WorkoutRepository,
    private val personalRecords: PersonalRecordRepository,
    private val resolveCurrentOneRepMax: ResolveCurrentOneRepMaxUseCase,
    @Suppress("unused")
    private val externalMeasurements: ExternalMeasurementRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private val selectedExerciseIds = mutableMapOf<String, String>()
    private var resolvedSelectionProfileId: String? = null
    private var insightsJob: Job? = null

    init {
        viewModelScope.launch {
            profiles.activeProfileContext.collectLatest { context ->
                when (context) {
                    is ActiveProfileContext.Switching -> {
                        insightsJob?.cancel()
                        resolvedSelectionProfileId = null
                        _uiState.value = ProfileUiState(context = context)
                    }

                    is ActiveProfileContext.Ready -> applyReadyContext(context)
                }
            }
        }
    }

    fun selectExercise(exercise: Exercise) {
        val exerciseId = exercise.id?.takeIf { it.isNotBlank() } ?: return
        val uiReady = uiState.value.context as? ActiveProfileContext.Ready ?: return
        val repositoryReady =
            profiles.activeProfileContext.value as? ActiveProfileContext.Ready ?: return
        val profileId = uiReady.profile.id
        if (repositoryReady.profile.id != profileId) return

        selectedExerciseIds[profileId] = exerciseId
        resolvedSelectionProfileId = profileId
        updateIfProfileCurrent(profileId) { state ->
            state.copy(
                selectedExercise = exercise,
                missingExerciseId = null,
                selectionFailure = null,
            )
        }
        if (isSelectionCurrent(profileId, exerciseId)) {
            loadInsights(profileId, exercise)
        }
    }

    private suspend fun applyReadyContext(context: ActiveProfileContext.Ready) {
        val profileId = context.profile.id
        val currentProfileId =
            (_uiState.value.context as? ActiveProfileContext.Ready)?.profile?.id

        if (currentProfileId == profileId && resolvedSelectionProfileId == profileId) {
            updateIfProfileCurrent(profileId) { state -> state.copy(context = context) }
            return
        }

        insightsJob?.cancel()
        _uiState.value = ProfileUiState(context = context)
        try {
            require(profileId.isNotBlank()) { "Ready profile ID must not be blank" }
            val savedId = selectedExerciseIds[profileId]
            val saved = savedId?.let { validExercise(it) }
            val fallbackId = if (saved == null) {
                workouts.getMostRecentCompletedExerciseId(profileId)
            } else {
                null
            }
            val fallback = fallbackId?.let { validExercise(it) }
            val selected = saved ?: fallback
            if (!isProfileCurrent(profileId)) return

            resolvedSelectionProfileId = profileId
            selected?.id?.let { selectedExerciseIds[profileId] = it }
            updateIfProfileCurrent(profileId) { state ->
                state.copy(
                    context = context,
                    selectedExercise = selected,
                    missingExerciseId = (fallbackId ?: savedId)
                        ?.takeIf { it.isNotBlank() && selected == null },
                    selectionFailure = null,
                )
            }
            selected?.let { loadInsights(profileId, it) }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            resolvedSelectionProfileId = null
            updateIfProfileCurrent(profileId) { state ->
                state.copy(
                    context = context,
                    selectedExercise = null,
                    missingExerciseId = null,
                    selectionFailure = error,
                )
            }
        }
    }

    private suspend fun validExercise(requestedId: String): Exercise? {
        if (requestedId.isBlank()) return null
        return exercises.getExerciseById(requestedId)
            ?.takeIf { it.id == requestedId && !it.id.isNullOrBlank() }
    }

    private fun loadInsights(profileId: String, exercise: Exercise) {
        val exerciseId = exercise.id?.takeIf { it.isNotBlank() } ?: return
        insightsJob?.cancel()
        updateIfSelectionCurrent(profileId, exerciseId) { state ->
            state.copy(
                currentOneRepMax = ProfileLoadable.Loading,
                prHighlights = ProfileLoadable.Loading,
                recentSessions = ProfileLoadable.Loading,
            )
        }
        if (!isSelectionCurrent(profileId, exerciseId)) return

        insightsJob = viewModelScope.launch {
            supervisorScope {
                launch {
                    loadBranch(
                        profileId = profileId,
                        exerciseId = exerciseId,
                        load = {
                            resolveCurrentOneRepMax(exerciseId, profileId)
                                ?.let { ProfileLoadable.Ready(it) }
                                ?: ProfileLoadable.Empty
                        },
                        publish = { state, value -> state.copy(currentOneRepMax = value) },
                    )
                }
                launch {
                    loadBranch(
                        profileId = profileId,
                        exerciseId = exerciseId,
                        load = {
                            ProfileLoadable.Ready(
                                personalRecords.getAllPRsForExercise(exerciseId, profileId)
                                    .toHighlights(),
                            )
                        },
                        publish = { state, value -> state.copy(prHighlights = value) },
                    )
                }
                launch {
                    loadBranch(
                        profileId = profileId,
                        exerciseId = exerciseId,
                        load = {
                            ProfileLoadable.Ready(
                                workouts.getRecentCompletedSessionsForExercise(
                                    exerciseId = exerciseId,
                                    profileId = profileId,
                                    limit = MAX_RECENT_EXERCISE_SESSIONS,
                                ),
                            )
                        },
                        publish = { state, value -> state.copy(recentSessions = value) },
                    )
                }
            }
        }
    }

    private suspend fun <T> loadBranch(
        profileId: String,
        exerciseId: String,
        load: suspend () -> ProfileLoadable<T>,
        publish: (ProfileUiState, ProfileLoadable<T>) -> ProfileUiState,
    ) {
        val result: ProfileLoadable<T> = try {
            load()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ProfileLoadable.Failed(error)
        }
        updateIfSelectionCurrent(profileId, exerciseId) { state ->
            publish(state, result)
        }
    }

    private fun isProfileCurrent(profileId: String): Boolean {
        val repositoryReady =
            profiles.activeProfileContext.value as? ActiveProfileContext.Ready ?: return false
        val uiReady = uiState.value.context as? ActiveProfileContext.Ready ?: return false
        return repositoryReady.profile.id == profileId && uiReady.profile.id == profileId
    }

    private fun isSelectionCurrent(profileId: String, exerciseId: String): Boolean =
        isProfileCurrent(profileId) &&
            uiState.value.selectedExercise?.id == exerciseId &&
            selectedExerciseIds[profileId] == exerciseId

    private inline fun updateIfProfileCurrent(
        profileId: String,
        transform: (ProfileUiState) -> ProfileUiState,
    ) {
        _uiState.update { state ->
            val repositoryReady =
                profiles.activeProfileContext.value as? ActiveProfileContext.Ready
            val uiReady = state.context as? ActiveProfileContext.Ready
            if (
                repositoryReady?.profile?.id == profileId &&
                uiReady?.profile?.id == profileId
            ) {
                transform(state)
            } else {
                state
            }
        }
    }

    private inline fun updateIfSelectionCurrent(
        profileId: String,
        exerciseId: String,
        transform: (ProfileUiState) -> ProfileUiState,
    ) {
        _uiState.update { state ->
            val repositoryReady =
                profiles.activeProfileContext.value as? ActiveProfileContext.Ready
            val uiReady = state.context as? ActiveProfileContext.Ready
            if (
                repositoryReady?.profile?.id == profileId &&
                uiReady?.profile?.id == profileId &&
                state.selectedExercise?.id == exerciseId &&
                selectedExerciseIds[profileId] == exerciseId
            ) {
                transform(state)
            } else {
                state
            }
        }
    }
}

private fun List<PersonalRecord>.toHighlights() = ProfilePrHighlights(
    maxWeightPerCableKg = asSequence()
        .filter { it.prType == PRType.MAX_WEIGHT }
        .map { it.weightPerCableKg }
        .filter { it.isFinite() && it > 0f }
        .maxOrNull(),
    estimatedOneRepMaxPerCableKg = asSequence()
        .map { it.oneRepMax }
        .filter { it.isFinite() && it > 0f }
        .maxOrNull(),
    maxVolumeKg = asSequence()
        .filter { it.prType == PRType.MAX_VOLUME }
        .map { it.volume }
        .filter { it.isFinite() && it > 0f }
        .maxOrNull(),
)
