package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devil.phoenixproject.data.integration.ExternalMeasurementRepository
import com.devil.phoenixproject.data.integration.HealthBodyWeightSyncManager
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.MAX_RECENT_EXERCISE_SESSIONS
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.ProfileContextRecoveryException
import com.devil.phoenixproject.data.repository.ProfileContextUnavailableException
import com.devil.phoenixproject.data.repository.StaleProfileContextException
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.CoreProfilePreferences
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.LedPreferences
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.ProfileLocalSafetyPreferences
import com.devil.phoenixproject.domain.model.RackPreferences
import com.devil.phoenixproject.domain.model.VbtPreferences
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.usecase.CurrentOneRepMax
import com.devil.phoenixproject.domain.usecase.ResolveCurrentOneRepMaxUseCase
import com.devil.phoenixproject.presentation.components.canDeleteProfile
import com.devil.phoenixproject.presentation.components.ProfileMeasurementKey
import com.devil.phoenixproject.presentation.components.latestImportedBodyWeightMeasuredAt
import com.devil.phoenixproject.presentation.components.normalizedProfileColorIndex
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.yield

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

enum class ProfileIdentityMutationKind { UPDATE, DELETE }

data class ProfileIdentityMutation(
    val token: Long,
    val profileId: String,
    val kind: ProfileIdentityMutationKind,
)

enum class ProfilePreferenceSection {
    CORE,
    RACK,
    WORKOUT,
    LED,
    VBT,
    LOCAL_SAFETY,
}

enum class ProfilePreferenceMutationKind {
    UPDATE,
    ADULT_ENABLE,
    ADULT_DECLINE,
    DISCO_UNLOCK,
    DOMINATRIX_UNLOCK,
}

data class ProfilePreferenceMutation(
    val token: Long,
    val profileId: String,
    val sections: Set<ProfilePreferenceSection>,
    val kind: ProfilePreferenceMutationKind,
)

data class ProfileUiState(
    val context: ActiveProfileContext? = null,
    val selectedExercise: Exercise? = null,
    val missingExerciseId: String? = null,
    val selectionFailure: Throwable? = null,
    val currentOneRepMax: ProfileLoadable<CurrentOneRepMax> = ProfileLoadable.Empty,
    val prHighlights: ProfileLoadable<ProfilePrHighlights> = ProfileLoadable.Empty,
    val recentSessions: ProfileLoadable<List<WorkoutSession>> = ProfileLoadable.Empty,
    val identityMutation: ProfileIdentityMutation? = null,
    val preferenceMutations:
        Map<ProfilePreferenceSection, ProfilePreferenceMutation> = emptyMap(),
    val importedBodyWeightMeasuredAt: Long? = null,
) {
    val identityMutationInFlight: Boolean
        get() = identityMutation != null

    val busyPreferenceSections: Set<ProfilePreferenceSection>
        get() = preferenceMutations.keys
}

sealed interface ProfileUiEvent {
    data class PreferenceMutationSucceeded(
        val profileId: String,
        val token: Long,
        val kind: ProfilePreferenceMutationKind,
        val sections: Set<ProfilePreferenceSection>,
    ) : ProfileUiEvent

    data class PreferenceUpdateFailed(
        val profileId: String,
        val token: Long,
        val kind: ProfilePreferenceMutationKind,
        val sections: Set<ProfilePreferenceSection>,
        val committedSections: Set<ProfilePreferenceSection>,
    ) : ProfileUiEvent

    data class IdentityUpdateFailed(
        val profileId: String,
        val kind: ProfileIdentityMutationKind,
    ) : ProfileUiEvent
    data class IdentityUpdated(val profileId: String) : ProfileUiEvent
    data class ProfileDeleted(val profileId: String) : ProfileUiEvent
    data class ProfileRecoveryRequired(
        val profileId: String,
        val cause: ProfileContextRecoveryException,
    ) : ProfileUiEvent
}

class ProfileViewModel(
    private val profiles: UserProfileRepository,
    private val exercises: ExerciseRepository,
    private val workouts: WorkoutRepository,
    private val personalRecords: PersonalRecordRepository,
    private val resolveCurrentOneRepMax: ResolveCurrentOneRepMaxUseCase,
    private val externalMeasurements: ExternalMeasurementRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()
    private val _events = Channel<ProfileUiEvent>(Channel.BUFFERED)
    val events: Flow<ProfileUiEvent> = _events.receiveAsFlow()

    private val selectedExerciseIds = mutableMapOf<String, String>()
    private var resolvedSelectionProfileId: String? = null
    private var insightsJob: Job? = null
    private var identityJob: Job? = null
    private var nextIdentityToken = 0L
    private var nextPreferenceToken = 0L
    private var measurementJob: Job? = null
    private var currentMeasurementKey: ProfileMeasurementKey? = null
    private var currentMeasurementToken = 0L
    private var nextMeasurementToken = 0L

    init {
        viewModelScope.launch {
            profiles.activeProfileContext.collectLatest { context ->
                when (context) {
                    is ActiveProfileContext.Switching -> {
                        insightsJob?.cancel()
                        if (_uiState.value.identityMutation?.kind == ProfileIdentityMutationKind.UPDATE) {
                            identityJob?.cancel()
                        }
                        invalidateMeasurementAttribution()
                        resolvedSelectionProfileId = null
                        _uiState.update { state ->
                            ProfileUiState(
                                context = context,
                                identityMutation = state.identityMutation,
                                preferenceMutations = state.preferenceMutations,
                            )
                        }
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

    fun updateCore(value: CoreProfilePreferences): Long? = startSinglePreferenceMutation(
        section = ProfilePreferenceSection.CORE,
    ) { profileId ->
        profiles.updateCore(profileId, value)
    }

    fun updateRack(value: RackPreferences): Long? = startSinglePreferenceMutation(
        section = ProfilePreferenceSection.RACK,
    ) { profileId ->
        profiles.updateRack(profileId, value)
    }

    fun updateWorkout(value: WorkoutPreferences): Long? = startSinglePreferenceMutation(
        section = ProfilePreferenceSection.WORKOUT,
    ) { profileId ->
        profiles.updateWorkout(profileId, value)
    }

    fun updateLed(value: LedPreferences): Long? = startSinglePreferenceMutation(
        section = ProfilePreferenceSection.LED,
    ) { profileId ->
        profiles.updateLed(profileId, value)
    }

    fun updateVbt(value: VbtPreferences): Long? = startSinglePreferenceMutation(
        section = ProfilePreferenceSection.VBT,
    ) { profileId ->
        profiles.updateVbt(profileId, value)
    }

    fun updateLocalSafety(value: ProfileLocalSafetyPreferences): Long? =
        startSinglePreferenceMutation(
            section = ProfilePreferenceSection.LOCAL_SAFETY,
        ) { profileId ->
            profiles.updateLocalSafety(profileId, value)
        }

    fun confirmAdultsOnlyAndEnableVulgar(): Long? {
        val ready = currentReadyForPreferenceMutation() ?: return null
        val sections = when {
            !ready.localSafety.adultsOnlyConfirmed -> setOf(
                ProfilePreferenceSection.LOCAL_SAFETY,
                ProfilePreferenceSection.VBT,
            )
            !ready.preferences.vbt.value.vulgarModeEnabled -> setOf(
                ProfilePreferenceSection.VBT,
            )
            else -> return null
        }
        return startPreferenceMutation(
            sections = sections,
            kind = ProfilePreferenceMutationKind.ADULT_ENABLE,
        ) { mutation, committedSections ->
            var authoritative = refreshAuthoritativeReady(mutation)
            if (ProfilePreferenceSection.LOCAL_SAFETY in mutation.sections) {
                val confirmed = authoritative.localSafety.copy(
                    adultsOnlyConfirmed = true,
                    adultsOnlyPrompted = true,
                )
                authoritative = commitPreferenceWrite(
                    mutation = mutation,
                    section = ProfilePreferenceSection.LOCAL_SAFETY,
                    committedSections = committedSections,
                ) {
                    profiles.updateLocalSafety(mutation.profileId, confirmed)
                }
            }
            if (ProfilePreferenceSection.VBT in mutation.sections) {
                val enabled = authoritative.preferences.vbt.value.copy(
                    vulgarModeEnabled = true,
                )
                commitPreferenceWrite(
                    mutation = mutation,
                    section = ProfilePreferenceSection.VBT,
                    committedSections = committedSections,
                ) {
                    profiles.updateVbt(mutation.profileId, enabled)
                }
            }
        }
    }

    fun declineAdultsOnly(): Long? = startPreferenceMutation(
        sections = setOf(ProfilePreferenceSection.LOCAL_SAFETY),
        kind = ProfilePreferenceMutationKind.ADULT_DECLINE,
    ) { mutation, committedSections ->
        val authoritative = refreshAuthoritativeReady(mutation)
        val declined = authoritative.localSafety.copy(
            adultsOnlyConfirmed = false,
            adultsOnlyPrompted = true,
        )
        commitPreferenceWrite(
            mutation = mutation,
            section = ProfilePreferenceSection.LOCAL_SAFETY,
            committedSections = committedSections,
        ) {
            profiles.updateLocalSafety(mutation.profileId, declined)
        }
    }

    fun unlockDiscoMode(): Long? {
        val ready = currentReadyForPreferenceMutation() ?: return null
        if (ready.preferences.led.value.discoModeUnlocked) return null
        return startPreferenceMutation(
            sections = setOf(ProfilePreferenceSection.LED),
            kind = ProfilePreferenceMutationKind.DISCO_UNLOCK,
        ) { mutation, committedSections ->
            val authoritative = refreshAuthoritativeReady(mutation)
            check(!authoritative.preferences.led.value.discoModeUnlocked)
            val unlocked = authoritative.preferences.led.value.copy(
                discoModeUnlocked = true,
            )
            commitPreferenceWrite(
                mutation = mutation,
                section = ProfilePreferenceSection.LED,
                committedSections = committedSections,
            ) {
                profiles.updateLed(mutation.profileId, unlocked)
            }
        }
    }

    fun unlockDominatrixMode(): Long? {
        val ready = currentReadyForPreferenceMutation() ?: return null
        if (!isDominatrixUnlockEligible(ready)) return null
        return startPreferenceMutation(
            sections = setOf(ProfilePreferenceSection.VBT),
            kind = ProfilePreferenceMutationKind.DOMINATRIX_UNLOCK,
        ) { mutation, committedSections ->
            val authoritative = refreshAuthoritativeReady(mutation)
            check(isDominatrixUnlockEligible(authoritative))
            val unlocked = authoritative.preferences.vbt.value.copy(
                dominatrixModeUnlocked = true,
            )
            commitPreferenceWrite(
                mutation = mutation,
                section = ProfilePreferenceSection.VBT,
                committedSections = committedSections,
            ) {
                profiles.updateVbt(mutation.profileId, unlocked)
            }
        }
    }

    fun updateIdentity(name: String, colorIndex: Int) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return
        val profileId = currentMutationProfileId() ?: return
        startIdentityMutation(profileId, ProfileIdentityMutationKind.UPDATE) {
            if (currentMutationProfileId() != profileId) return@startIdentityMutation null
            profiles.updateProfile(
                id = profileId,
                name = trimmedName,
                colorIndex = normalizedProfileColorIndex(colorIndex),
            )
            if (currentMutationProfileId() == profileId) {
                ProfileUiEvent.IdentityUpdated(profileId)
            } else {
                null
            }
        }
    }

    fun deleteActiveProfile() {
        val uiReady = uiState.value.context as? ActiveProfileContext.Ready ?: return
        val profileId = currentMutationProfileId() ?: return
        if (!canDeleteProfile(uiReady.profile)) return
        startIdentityMutation(profileId, ProfileIdentityMutationKind.DELETE) {
            if (profiles.deleteActiveProfile(profileId)) {
                ProfileUiEvent.ProfileDeleted(profileId)
            } else {
                ProfileUiEvent.IdentityUpdateFailed(
                    profileId,
                    ProfileIdentityMutationKind.DELETE,
                )
            }
        }
    }

    private fun startSinglePreferenceMutation(
        section: ProfilePreferenceSection,
        write: suspend (String) -> Unit,
    ): Long? = startPreferenceMutation(
        sections = setOf(section),
        kind = ProfilePreferenceMutationKind.UPDATE,
    ) { mutation, committedSections ->
        commitPreferenceWrite(
            mutation = mutation,
            section = section,
            committedSections = committedSections,
        ) {
            write(mutation.profileId)
        }
    }

    private fun startPreferenceMutation(
        sections: Set<ProfilePreferenceSection>,
        kind: ProfilePreferenceMutationKind,
        action: suspend (
            ProfilePreferenceMutation,
            MutableSet<ProfilePreferenceSection>,
        ) -> Unit,
    ): Long? {
        val mutation = claimPreferenceMutation(sections, kind) ?: return null
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val committedSections = linkedSetOf<ProfilePreferenceSection>()
            var terminalEvent: ProfileUiEvent? = null
            try {
                // Even Main.immediate must return the accepted token before a terminal event can
                // fire, while cancellation at this yield still reaches owner cleanup below.
                yield()
                action(mutation, committedSections)
                terminalEvent = ProfileUiEvent.PreferenceMutationSucceeded(
                    profileId = mutation.profileId,
                    token = mutation.token,
                    kind = mutation.kind,
                    sections = mutation.sections,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                terminalEvent = ProfileUiEvent.PreferenceUpdateFailed(
                    profileId = mutation.profileId,
                    token = mutation.token,
                    kind = mutation.kind,
                    sections = mutation.sections,
                    committedSections = committedSections.toSet(),
                )
            } finally {
                val ownsMutation = clearPreferenceMutation(mutation.token)
                if (ownsMutation) terminalEvent?.let(_events::trySend)
            }
        }
        job.start()
        return mutation.token
    }

    private fun claimPreferenceMutation(
        sections: Set<ProfilePreferenceSection>,
        kind: ProfilePreferenceMutationKind,
    ): ProfilePreferenceMutation? {
        require(sections.isNotEmpty())
        val profileId = currentMutationProfileId() ?: return null
        val mutation = ProfilePreferenceMutation(
            token = ++nextPreferenceToken,
            profileId = profileId,
            sections = sections.toSet(),
            kind = kind,
        )
        while (true) {
            val state = _uiState.value
            val ready = state.context as? ActiveProfileContext.Ready ?: return null
            if (
                state.identityMutation != null ||
                sections.any(state.preferenceMutations::containsKey) ||
                ready.profile.id != profileId ||
                currentMutationProfileId() != profileId
            ) {
                return null
            }

            val claimed = state.copy(
                preferenceMutations = state.preferenceMutations +
                    sections.associateWith { mutation },
            )
            if (_uiState.compareAndSet(state, claimed)) return mutation
        }
    }

    private fun clearPreferenceMutation(token: Long): Boolean {
        while (true) {
            val state = _uiState.value
            if (state.preferenceMutations.values.none { it.token == token }) return false
            val cleared = state.copy(
                preferenceMutations = state.preferenceMutations.filterValues {
                    it.token != token
                },
            )
            if (_uiState.compareAndSet(state, cleared)) return true
        }
    }

    private suspend fun commitPreferenceWrite(
        mutation: ProfilePreferenceMutation,
        section: ProfilePreferenceSection,
        committedSections: MutableSet<ProfilePreferenceSection>,
        write: suspend () -> Unit,
    ): ActiveProfileContext.Ready {
        write()
        committedSections += section
        return refreshAuthoritativeReady(mutation)
    }

    private suspend fun refreshAuthoritativeReady(
        mutation: ProfilePreferenceMutation,
    ): ActiveProfileContext.Ready {
        while (true) {
            val ready = requireAuthoritativeReady(mutation.profileId)
            applyReadyContext(ready)
            val repositoryReady = requireAuthoritativeReady(mutation.profileId)
            if (repositoryReady != ready) continue
            val uiContext = _uiState.value.context
            val uiReady = uiContext as? ActiveProfileContext.Ready
                ?: throw ProfileContextUnavailableException()
            if (uiReady.profile.id != mutation.profileId) {
                throw StaleProfileContextException(mutation.profileId, uiReady.profile.id)
            }
            if (uiReady != repositoryReady) continue
            return repositoryReady
        }
    }

    private fun requireAuthoritativeReady(profileId: String): ActiveProfileContext.Ready =
        when (val context = profiles.activeProfileContext.value) {
            is ActiveProfileContext.Switching -> throw ProfileContextUnavailableException()
            is ActiveProfileContext.Ready -> {
                if (context.profile.id != profileId) {
                    throw StaleProfileContextException(profileId, context.profile.id)
                }
                context
            }
        }

    private fun currentReadyForPreferenceMutation(): ActiveProfileContext.Ready? {
        val profileId = currentMutationProfileId() ?: return null
        val ready = profiles.activeProfileContext.value as? ActiveProfileContext.Ready ?: return null
        return ready.takeIf { it.profile.id == profileId }
    }

    private fun isDominatrixUnlockEligible(context: ActiveProfileContext.Ready): Boolean {
        val vbt = context.preferences.vbt.value
        return context.localSafety.adultsOnlyConfirmed &&
            vbt.verbalEncouragementEnabled &&
            vbt.vulgarModeEnabled &&
            !vbt.dominatrixModeUnlocked
    }

    private fun currentMutationProfileId(): String? {
        val uiReady = uiState.value.context as? ActiveProfileContext.Ready ?: return null
        val repositoryReady =
            profiles.activeProfileContext.value as? ActiveProfileContext.Ready ?: return null
        return uiReady.profile.id.takeIf {
            it.isNotBlank() &&
                it == repositoryReady.profile.id &&
                uiReady.profile.isActive &&
                repositoryReady.profile.isActive
        }
    }

    private fun startIdentityMutation(
        profileId: String,
        kind: ProfileIdentityMutationKind,
        action: suspend () -> ProfileUiEvent?,
    ) {
        val mutation = ProfileIdentityMutation(
            token = ++nextIdentityToken,
            profileId = profileId,
            kind = kind,
        )
        while (true) {
            val state = _uiState.value
            val ready = state.context as? ActiveProfileContext.Ready ?: return
            if (
                state.identityMutation != null ||
                state.preferenceMutations.isNotEmpty() ||
                ready.profile.id != profileId ||
                currentMutationProfileId() != profileId
            ) {
                return
            }
            if (_uiState.compareAndSet(state, state.copy(identityMutation = mutation))) break
        }

        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            var terminalEvent: ProfileUiEvent? = null
            try {
                terminalEvent = action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: ProfileContextRecoveryException) {
                terminalEvent = ProfileUiEvent.ProfileRecoveryRequired(profileId, error)
            } catch (error: Exception) {
                terminalEvent = ProfileUiEvent.IdentityUpdateFailed(profileId, kind)
            } finally {
                val ownsMutation = clearIdentityMutation(mutation.token)
                val currentEnoughToPublish =
                    kind != ProfileIdentityMutationKind.UPDATE || currentMutationProfileId() == profileId
                if (ownsMutation && currentEnoughToPublish) {
                    terminalEvent?.let(_events::trySend)
                }
            }
        }
        identityJob = job
        job.start()
    }

    private fun clearIdentityMutation(token: Long): Boolean {
        var cleared = false
        _uiState.update { state ->
            if (state.identityMutation?.token == token) {
                cleared = true
                state.copy(identityMutation = null)
            } else {
                state
            }
        }
        return cleared
    }

    private suspend fun applyReadyContext(context: ActiveProfileContext.Ready) {
        val profileId = context.profile.id
        val currentProfileId =
            (_uiState.value.context as? ActiveProfileContext.Ready)?.profile?.id

        if (currentProfileId == profileId && resolvedSelectionProfileId == profileId) {
            updateIfProfileCurrent(profileId) { state -> state.copy(context = context) }
            restartMeasurementAttribution(context)
            return
        }

        insightsJob?.cancel()
        _uiState.update { state ->
            ProfileUiState(
                context = context,
                identityMutation = state.identityMutation,
                preferenceMutations = state.preferenceMutations,
            )
        }
        restartMeasurementAttribution(context)
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

    private fun restartMeasurementAttribution(context: ActiveProfileContext.Ready) {
        val key = ProfileMeasurementKey(
            profileId = context.profile.id,
            coreLocalGeneration = context.preferences.core.metadata.localGeneration,
            bodyWeightKg = context.preferences.core.value.bodyWeightKg,
        )
        if (currentMeasurementKey == key) return

        val token = ++nextMeasurementToken
        currentMeasurementToken = token
        currentMeasurementKey = key
        measurementJob?.cancel()
        _uiState.update { state -> state.copy(importedBodyWeightMeasuredAt = null) }
        measurementJob = viewModelScope.launch {
            externalMeasurements.observeMeasurementsByType(
                profileId = key.profileId,
                measurementType = HealthBodyWeightSyncManager.MEASUREMENT_TYPE_WEIGHT,
            ).collect { measurements ->
                val measuredAt = latestImportedBodyWeightMeasuredAt(
                    profileId = key.profileId,
                    bodyWeightKg = key.bodyWeightKg,
                    measurements = measurements,
                )
                publishMeasurementAttribution(token, key, measuredAt)
            }
        }
    }

    private fun invalidateMeasurementAttribution() {
        currentMeasurementToken = ++nextMeasurementToken
        currentMeasurementKey = null
        measurementJob?.cancel()
        measurementJob = null
        _uiState.update { state -> state.copy(importedBodyWeightMeasuredAt = null) }
    }

    private fun publishMeasurementAttribution(
        token: Long,
        key: ProfileMeasurementKey,
        measuredAt: Long?,
    ) {
        _uiState.update { state ->
            val repositoryReady =
                profiles.activeProfileContext.value as? ActiveProfileContext.Ready
            val uiReady = state.context as? ActiveProfileContext.Ready
            val repositoryCore = repositoryReady?.preferences?.core
            val uiCore = uiReady?.preferences?.core
            if (
                currentMeasurementToken == token &&
                currentMeasurementKey == key &&
                repositoryReady?.profile?.id == key.profileId &&
                uiReady?.profile?.id == key.profileId &&
                repositoryCore?.metadata?.localGeneration == key.coreLocalGeneration &&
                uiCore?.metadata?.localGeneration == key.coreLocalGeneration &&
                repositoryCore?.value?.bodyWeightKg == key.bodyWeightKg &&
                uiCore?.value?.bodyWeightKg == key.bodyWeightKg
            ) {
                state.copy(importedBodyWeightMeasuredAt = measuredAt)
            } else {
                state
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
