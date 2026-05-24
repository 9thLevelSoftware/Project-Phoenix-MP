package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.devil.phoenixproject.data.integration.ExternalMeasurementRepository
import com.devil.phoenixproject.data.integration.ExternalProgramRepository
import com.devil.phoenixproject.data.integration.ExternalRoutineRepository
import com.devil.phoenixproject.data.integration.ExternalActivityRepository
import com.devil.phoenixproject.data.integration.IntegrationManager
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.ExternalActivity
import com.devil.phoenixproject.domain.model.ExternalBodyMeasurement
import com.devil.phoenixproject.domain.model.ExternalPlaygroundPreview
import com.devil.phoenixproject.domain.model.ExternalProgram
import com.devil.phoenixproject.domain.model.ExternalProgramStats
import com.devil.phoenixproject.domain.model.ExternalRoutine
import com.devil.phoenixproject.domain.model.ExternalRoutineFolder
import com.devil.phoenixproject.domain.model.IntegrationProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ExternalActivitiesUiState(
    val activities: List<ExternalActivity> = emptyList(),
)

class ExternalActivitiesViewModel(
    repository: ExternalActivityRepository,
    userProfileRepository: UserProfileRepository,
) : ViewModel() {
    private val activeProfileId = userProfileRepository.activeProfile
        .map { it?.id ?: "default" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "default")

    val uiState: StateFlow<ExternalActivitiesUiState> = activeProfileId.flatMapLatest { profileId ->
        repository.getAll(profileId)
            .map { activities -> ExternalActivitiesUiState(activities = activities) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ExternalActivitiesUiState())
}

data class ExternalRoutinesUiState(
    val routines: List<ExternalRoutine> = emptyList(),
    val folders: List<ExternalRoutineFolder> = emptyList(),
)

class ExternalRoutinesViewModel(
    private val repository: ExternalRoutineRepository,
    userProfileRepository: UserProfileRepository,
) : ViewModel() {
    private val activeProfileId = userProfileRepository.activeProfile
        .map { it?.id ?: "default" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "default")

    val uiState: StateFlow<ExternalRoutinesUiState> = activeProfileId.flatMapLatest { profileId ->
        combine(
            repository.observeRoutines(profileId),
            repository.observeFolders(profileId),
        ) { routines, folders ->
            ExternalRoutinesUiState(routines = routines, folders = folders)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ExternalRoutinesUiState())
}

data class ExternalProgramsUiState(
    val programs: List<ExternalProgram> = emptyList(),
    val statsByProgramId: Map<String, ExternalProgramStats> = emptyMap(),
    val currentProgram: ExternalProgram? = null,
    val playgroundPreview: ExternalPlaygroundPreview? = null,
    val isSimulating: Boolean = false,
)

class ExternalProgramsViewModel(
    private val repository: ExternalProgramRepository,
    private val integrationManager: IntegrationManager,
    userProfileRepository: UserProfileRepository,
) : ViewModel() {
    private val activeProfileId = userProfileRepository.activeProfile
        .map { it?.id ?: "default" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "default")

    private val _uiState = MutableStateFlow(ExternalProgramsUiState())
    val uiState: StateFlow<ExternalProgramsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<IntegrationUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<IntegrationUiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            activeProfileId.flatMapLatest { profileId ->
                combine(
                    repository.observePrograms(profileId),
                    repository.observeProgramStats(profileId),
                    repository.observeCurrentProgram(profileId, IntegrationProvider.LIFTOSAUR),
                ) { programs, stats, current ->
                    Triple(programs, stats, current)
                }
            }.collect { (programs, stats, current) ->
                _uiState.value = _uiState.value.copy(
                    programs = programs,
                    statsByProgramId = stats,
                    currentProgram = current,
                )
            }
        }
    }

    fun simulateProgram(program: ExternalProgram, commands: List<String> = emptyList()) {
        val scriptText = program.scriptText ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSimulating = true)
            integrationManager.simulatePlayground(
                provider = program.provider,
                externalProgramId = program.externalId,
                scriptText = scriptText,
                commands = commands,
                profileId = activeProfileId.value,
            ).onSuccess { preview ->
                _uiState.value = _uiState.value.copy(playgroundPreview = preview)
            }.onFailure { error ->
                _events.emit(IntegrationUiEvent.Snackbar("Simulation failed: ${error.message}"))
            }
            _uiState.value = _uiState.value.copy(isSimulating = false)
        }
    }

    fun commitPreview(program: ExternalProgram) {
        val preview = _uiState.value.playgroundPreview ?: return
        if (preview.programExternalId != program.externalId) {
            _uiState.value = _uiState.value.copy(playgroundPreview = null)
            return
        }
        val text = preview.updatedProgramText ?: return
        viewModelScope.launch {
            integrationManager.commitProgramText(program.id, text)
            _uiState.value = _uiState.value.copy(playgroundPreview = null)
            _events.emit(IntegrationUiEvent.Snackbar("Program text updated"))
        }
    }

    fun clearPreview() {
        _uiState.value = _uiState.value.copy(playgroundPreview = null)
    }
}

data class ExternalMeasurementsUiState(
    val measurements: List<ExternalBodyMeasurement> = emptyList(),
    val measurementTypes: List<String> = emptyList(),
    val selectedType: String? = null,
)

class ExternalMeasurementsViewModel(
    repository: ExternalMeasurementRepository,
    userProfileRepository: UserProfileRepository,
) : ViewModel() {
    private val selectedType = MutableStateFlow<String?>(null)
    private val activeProfileId = userProfileRepository.activeProfile
        .map { it?.id ?: "default" }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "default")

    val uiState: StateFlow<ExternalMeasurementsUiState> = combine(
        activeProfileId.flatMapLatest { profileId -> repository.observeMeasurements(profileId) },
        selectedType,
    ) { measurements, type ->
        ExternalMeasurementsUiState(
            measurements = if (type == null) measurements else measurements.filter { it.measurementType == type },
            measurementTypes = measurements.map { it.measurementType }.distinct(),
            selectedType = type,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ExternalMeasurementsUiState())

    fun selectType(type: String?) {
        selectedType.value = type
    }
}
