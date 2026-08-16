package com.devil.phoenixproject.presentation.viewmodel

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.integration.ExternalActivityRepository
import com.devil.phoenixproject.data.integration.HealthIntegration
import com.devil.phoenixproject.data.integration.IntegrationSyncCursorRepository
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeRepository
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeResumeResult
import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.data.repository.BiomechanicsRepository
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.EquipmentRackRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.RepMetricRepository
import com.devil.phoenixproject.data.repository.ScannedDevice
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.domain.model.AppliedRoutineModifier
import com.devil.phoenixproject.domain.model.Badge
import com.devil.phoenixproject.domain.model.BleCompatibilitySetting
import com.devil.phoenixproject.domain.model.BodyweightVariantOption
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.PRCelebrationEvent
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackLoadAdjustment
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.RoutineGroup
import com.devil.phoenixproject.domain.model.RoutineLaunchOrigin
import com.devil.phoenixproject.domain.model.SessionBodyweightState
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.usecase.ApplyEquipmentRackLoadUseCase
import com.devil.phoenixproject.domain.usecase.ApplyRoutineModifierUseCase
import com.devil.phoenixproject.domain.usecase.BackfillVelocityOneRepMaxUseCase
import com.devil.phoenixproject.domain.usecase.ComputeVelocityOneRepMaxUseCase
import com.devil.phoenixproject.domain.usecase.CountVelocityOneRepMaxImprovementsUseCase
import com.devil.phoenixproject.domain.usecase.DropSetEligibilityPolicy
import com.devil.phoenixproject.domain.usecase.RecommendWeightAdjustmentUseCase
import com.devil.phoenixproject.domain.usecase.RecordPersonalMvtSampleUseCase
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.domain.usecase.ResolveRoutineWeightsUseCase
import com.devil.phoenixproject.presentation.components.exercisepicker.CompletedExerciseIdsState
import com.devil.phoenixproject.presentation.components.exercisepicker.completedExerciseIdsFromHistory
import com.devil.phoenixproject.presentation.manager.BleConnectionManager
import com.devil.phoenixproject.presentation.manager.DefaultWorkoutSessionManager
import com.devil.phoenixproject.presentation.manager.GamificationManager
import com.devil.phoenixproject.presentation.manager.HistoryItem
import com.devil.phoenixproject.presentation.manager.HistoryManager
import com.devil.phoenixproject.presentation.manager.JustLiftDefaults
import com.devil.phoenixproject.presentation.manager.MachineTeardownState
import com.devil.phoenixproject.presentation.manager.ResumableProgressInfo
import com.devil.phoenixproject.presentation.manager.RoutineResumeDiscardResult
import com.devil.phoenixproject.presentation.manager.RoutineResumeDiscovery
import com.devil.phoenixproject.presentation.manager.RoutineResumeHandle
import com.devil.phoenixproject.presentation.manager.SettingsManager
import com.devil.phoenixproject.presentation.manager.WorkoutServiceController
import com.devil.phoenixproject.presentation.manager.currentProfileTestSoundEvents
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.util.BackupDestination
import com.devil.phoenixproject.util.BackupStats
import com.devil.phoenixproject.util.DataBackupManager
import kotlin.coroutines.resume
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

// HistoryItem, SingleSessionHistoryItem, GroupedRoutineHistoryItem moved to
// com.devil.phoenixproject.presentation.manager.HistoryManager

/**
 * Represents a dynamic action for the top app bar.
 */
data class TopBarAction(val icon: ImageVector, val description: String, val onClick: () -> Unit)

data class SettingsGlobalUiState(
    val enableVideoPlayback: Boolean,
    val bleCompatibilityMode: BleCompatibilitySetting,
    val autoBackupEnabled: Boolean,
    val backupDestination: BackupDestination,
    val language: String,
)

private fun UserPreferences.toSettingsGlobalUiState() = SettingsGlobalUiState(
    enableVideoPlayback = enableVideoPlayback,
    bleCompatibilityMode = bleCompatibilityMode,
    autoBackupEnabled = autoBackupEnabled,
    backupDestination = backupDestination,
    language = language,
)

internal enum class RoutineResumeRetryAction {
    RESUME,
    DISCARD,
}

internal enum class RoutineResumeEntryPoint {
    DAILY_ROUTINES,
    HOME_CYCLE,
    TRAINING_CYCLES,
}

internal class RoutineResumeActionAuthority(
    val entryPoint: RoutineResumeEntryPoint,
    private val actionToken: Int,
    private val currentToken: () -> Int,
    contextIsCurrent: () -> Boolean,
) {
    private val contextPredicate = contextIsCurrent

    fun tokenIsCurrent(): Boolean = currentToken() == actionToken

    fun contextIsCurrent(): Boolean = contextPredicate()

    fun isCurrent(): Boolean = tokenIsCurrent() && contextIsCurrent()

    suspend fun awaitCurrentPublication(
        load: suspend (publicationStillCurrent: () -> Boolean) -> Boolean,
    ): Boolean {
        if (!isCurrent()) return false
        val loaded = try {
            load(::isCurrent)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        return loaded && isCurrent()
    }

    fun mayCommitInMemory(handleStillCurrent: Boolean): Boolean = isCurrent() && handleStillCurrent

    fun validateCurrentContext(
        contextIsValid: Boolean,
        onCurrentInvalid: () -> Unit,
    ): Boolean {
        if (!isCurrent()) return false
        if (!contextIsValid) {
            onCurrentInvalid()
            return false
        }
        return true
    }
}

internal class RoutineResumeOperationGate {
    private var token: Int = 0
    private var job: Job? = null

    val currentToken: Int
        get() = token

    fun launch(
        scope: CoroutineScope,
        block: suspend (actionToken: Int) -> Unit,
    ): Int {
        job?.cancel()
        token += 1
        val actionToken = token
        job = scope.launch { block(actionToken) }
        return actionToken
    }

    fun supersede() {
        job?.cancel()
        job = null
        token += 1
    }
}

internal sealed interface RoutineResumeUiDecision {
    data object ResumeInMemory : RoutineResumeUiDecision
    data object NavigateActiveWorkout : RoutineResumeUiDecision
    data class NavigateManualSetReady(
        val exerciseIndex: Int,
        val setIndex: Int,
    ) : RoutineResumeUiDecision
    data object EnterFreshRoutine : RoutineResumeUiDecision
    data class RetainDialog(val retryAction: RoutineResumeRetryAction) : RoutineResumeUiDecision
    data object DismissDialog : RoutineResumeUiDecision
}

internal sealed interface RoutineResumeUiOperation {
    val handle: RoutineResumeHandle

    data class Resume(override val handle: RoutineResumeHandle) : RoutineResumeUiOperation
    data class Restart(override val handle: RoutineResumeHandle) : RoutineResumeUiOperation
    data class RetryManualLoad(
        override val handle: RoutineResumeHandle.Persisted,
        val exerciseIndex: Int,
        val setIndex: Int,
    ) : RoutineResumeUiOperation
}

internal sealed interface RoutineResumeUiOutcome {
    data object NavigateActiveWorkout : RoutineResumeUiOutcome
    data object StartAndNavigateActiveWorkout : RoutineResumeUiOutcome
    data class EnterSetReady(val exerciseIndex: Int, val setIndex: Int) : RoutineResumeUiOutcome
    data class EnterDailyOverview(val routine: Routine) : RoutineResumeUiOutcome
    data class RetainDialog(val retryAction: RoutineResumeRetryAction) : RoutineResumeUiOutcome
    data object DismissDialog : RoutineResumeUiOutcome
    data object ConnectionFailed : RoutineResumeUiOutcome
    data class LoadFailed(
        val retryOperation: RoutineResumeUiOperation.RetryManualLoad? = null,
    ) : RoutineResumeUiOutcome
    data object StaleNoOp : RoutineResumeUiOutcome
}

internal sealed interface RoutineResumeCompletionDisposition {
    data object IgnoreStaleToken : RoutineResumeCompletionDisposition
    data object UnlockRetainedDialog : RoutineResumeCompletionDisposition
    data class Apply(val outcome: RoutineResumeUiOutcome) : RoutineResumeCompletionDisposition
}

internal fun classifyRoutineResumeCompletion(
    tokenCurrent: Boolean,
    contextCurrent: Boolean,
    outcome: RoutineResumeUiOutcome,
): RoutineResumeCompletionDisposition = when {
    !tokenCurrent -> RoutineResumeCompletionDisposition.IgnoreStaleToken

    !contextCurrent || outcome == RoutineResumeUiOutcome.StaleNoOp ->
        RoutineResumeCompletionDisposition.UnlockRetainedDialog

    else -> RoutineResumeCompletionDisposition.Apply(outcome)
}

internal interface RoutineResumeUiPort {
    suspend fun resume(handle: RoutineResumeHandle): ActiveWorkoutRuntimeResumeResult
    suspend fun discard(handle: RoutineResumeHandle): RoutineResumeDiscardResult
    suspend fun awaitConnection(): Boolean
    fun isInMemoryHandleCurrent(handle: RoutineResumeHandle.InMemory): Boolean
    suspend fun loadDailyRoutine(
        routine: Routine,
        publicationStillCurrent: () -> Boolean,
    ): Boolean
    suspend fun loadCycleRoutine(
        routine: Routine,
        cycleId: String,
        dayNumber: Int,
        publicationStillCurrent: () -> Boolean,
    ): Boolean
}

internal suspend fun runRoutineResumeUiOperation(
    operation: RoutineResumeUiOperation,
    authority: RoutineResumeActionAuthority,
    port: RoutineResumeUiPort,
): RoutineResumeUiOutcome {
    if (!authority.isCurrent()) return RoutineResumeUiOutcome.StaleNoOp
    val decision = when (operation) {
        is RoutineResumeUiOperation.Resume ->
            routineResumeUiDecision(operation.handle, port.resume(operation.handle))

        is RoutineResumeUiOperation.Restart ->
            routineResumeDiscardUiDecision(port.discard(operation.handle))

        is RoutineResumeUiOperation.RetryManualLoad ->
            RoutineResumeUiDecision.NavigateManualSetReady(
                exerciseIndex = operation.exerciseIndex,
                setIndex = operation.setIndex,
            )
    }
    if (!authority.isCurrent()) return RoutineResumeUiOutcome.StaleNoOp
    return routeRoutineResumeUiDecision(
        handle = operation.handle,
        decision = decision,
        authority = authority,
        port = port,
    )
}

private suspend fun routeRoutineResumeUiDecision(
    handle: RoutineResumeHandle,
    decision: RoutineResumeUiDecision,
    authority: RoutineResumeActionAuthority,
    port: RoutineResumeUiPort,
): RoutineResumeUiOutcome = when (decision) {
    RoutineResumeUiDecision.NavigateActiveWorkout ->
        RoutineResumeUiOutcome.NavigateActiveWorkout

    is RoutineResumeUiDecision.NavigateManualSetReady -> {
        val loaded = when (authority.entryPoint) {
            RoutineResumeEntryPoint.DAILY_ROUTINES -> authority.awaitCurrentPublication { stillCurrent ->
                port.loadDailyRoutine(handle.selectedRoutine, stillCurrent)
            }

            RoutineResumeEntryPoint.HOME_CYCLE,
            RoutineResumeEntryPoint.TRAINING_CYCLES,
            -> {
                val cycleId = handle.cycleId?.takeIf(String::isNotBlank)
                val dayNumber = handle.cycleDayNumber?.takeIf { it > 0 }
                if (cycleId == null || dayNumber == null) {
                    return if (authority.isCurrent()) {
                        RoutineResumeUiOutcome.DismissDialog
                    } else {
                        RoutineResumeUiOutcome.StaleNoOp
                    }
                }
                authority.awaitCurrentPublication { stillCurrent ->
                    port.loadCycleRoutine(
                        routine = handle.selectedRoutine,
                        cycleId = cycleId,
                        dayNumber = dayNumber,
                        publicationStillCurrent = stillCurrent,
                    )
                }
            }
        }
        when {
            !authority.isCurrent() -> RoutineResumeUiOutcome.StaleNoOp

            !loaded -> RoutineResumeUiOutcome.LoadFailed(
                retryOperation = (handle as? RoutineResumeHandle.Persisted)?.let { persisted ->
                    RoutineResumeUiOperation.RetryManualLoad(
                        handle = persisted,
                        exerciseIndex = decision.exerciseIndex,
                        setIndex = decision.setIndex,
                    )
                },
            )

            else -> RoutineResumeUiOutcome.EnterSetReady(decision.exerciseIndex, decision.setIndex)
        }
    }

    RoutineResumeUiDecision.ResumeInMemory -> {
        val inMemory = handle as? RoutineResumeHandle.InMemory
            ?: return RoutineResumeUiOutcome.DismissDialog
        if (!port.isInMemoryHandleCurrent(inMemory)) {
            return RoutineResumeUiOutcome.DismissDialog
        }
        val connected = port.awaitConnection()
        when {
            !authority.isCurrent() -> RoutineResumeUiOutcome.StaleNoOp

            !port.isInMemoryHandleCurrent(inMemory) -> RoutineResumeUiOutcome.DismissDialog

            !connected -> RoutineResumeUiOutcome.ConnectionFailed

            authority.entryPoint == RoutineResumeEntryPoint.DAILY_ROUTINES ->
                RoutineResumeUiOutcome.StartAndNavigateActiveWorkout

            else -> RoutineResumeUiOutcome.EnterSetReady(inMemory.exerciseIndex, inMemory.setIndex)
        }
    }

    RoutineResumeUiDecision.EnterFreshRoutine -> enterFreshRoutineFromResume(
        handle = handle,
        authority = authority,
        port = port,
    )

    is RoutineResumeUiDecision.RetainDialog ->
        RoutineResumeUiOutcome.RetainDialog(decision.retryAction)

    RoutineResumeUiDecision.DismissDialog -> RoutineResumeUiOutcome.DismissDialog
}

private suspend fun enterFreshRoutineFromResume(
    handle: RoutineResumeHandle,
    authority: RoutineResumeActionAuthority,
    port: RoutineResumeUiPort,
): RoutineResumeUiOutcome {
    if (!authority.isCurrent()) return RoutineResumeUiOutcome.StaleNoOp
    if (authority.entryPoint == RoutineResumeEntryPoint.DAILY_ROUTINES) {
        return RoutineResumeUiOutcome.EnterDailyOverview(handle.selectedRoutine)
    }
    return runFreshCycleUiOperation(
        routine = handle.selectedRoutine,
        cycleId = handle.cycleId,
        dayNumber = handle.cycleDayNumber,
        authority = authority,
        port = port,
    )
}

internal suspend fun runFreshCycleUiOperation(
    routine: Routine,
    cycleId: String?,
    dayNumber: Int?,
    authority: RoutineResumeActionAuthority,
    port: RoutineResumeUiPort,
): RoutineResumeUiOutcome {
    if (!authority.isCurrent()) return RoutineResumeUiOutcome.StaleNoOp
    if (authority.entryPoint == RoutineResumeEntryPoint.DAILY_ROUTINES) {
        return RoutineResumeUiOutcome.DismissDialog
    }
    val exactCycleId = cycleId?.takeIf(String::isNotBlank)
        ?: return RoutineResumeUiOutcome.DismissDialog
    val exactDayNumber = dayNumber?.takeIf { it > 0 }
        ?: return RoutineResumeUiOutcome.DismissDialog
    val connected = port.awaitConnection()
    if (!authority.isCurrent()) return RoutineResumeUiOutcome.StaleNoOp
    if (!connected) return RoutineResumeUiOutcome.ConnectionFailed
    val loaded = authority.awaitCurrentPublication { stillCurrent ->
        port.loadCycleRoutine(
            routine = routine,
            cycleId = exactCycleId,
            dayNumber = exactDayNumber,
            publicationStillCurrent = stillCurrent,
        )
    }
    return when {
        !authority.isCurrent() -> RoutineResumeUiOutcome.StaleNoOp
        !loaded -> RoutineResumeUiOutcome.LoadFailed()
        else -> RoutineResumeUiOutcome.EnterSetReady(0, 0)
    }
}

internal fun routineResumeUiDecision(
    handle: RoutineResumeHandle,
    result: ActiveWorkoutRuntimeResumeResult,
): RoutineResumeUiDecision = when (result) {
    ActiveWorkoutRuntimeResumeResult.RestoredRest ->
        if (handle is RoutineResumeHandle.Persisted) {
            RoutineResumeUiDecision.NavigateActiveWorkout
        } else {
            RoutineResumeUiDecision.DismissDialog
        }

    is ActiveWorkoutRuntimeResumeResult.ManualSetReady ->
        if (handle is RoutineResumeHandle.Persisted) {
            RoutineResumeUiDecision.NavigateManualSetReady(
                exerciseIndex = result.exerciseIndex,
                setIndex = result.setIndex,
            )
        } else {
            RoutineResumeUiDecision.DismissDialog
        }

    ActiveWorkoutRuntimeResumeResult.FreshStart ->
        if (handle is RoutineResumeHandle.Persisted) {
            RoutineResumeUiDecision.EnterFreshRoutine
        } else {
            RoutineResumeUiDecision.DismissDialog
        }

    ActiveWorkoutRuntimeResumeResult.Missing -> when (handle) {
        is RoutineResumeHandle.InMemory -> RoutineResumeUiDecision.ResumeInMemory
        is RoutineResumeHandle.Persisted -> RoutineResumeUiDecision.EnterFreshRoutine
    }

    ActiveWorkoutRuntimeResumeResult.RetryableFailure ->
        RoutineResumeUiDecision.RetainDialog(RoutineResumeRetryAction.RESUME)

    ActiveWorkoutRuntimeResumeResult.Superseded -> RoutineResumeUiDecision.DismissDialog
}

internal fun routineResumeDiscardUiDecision(
    result: RoutineResumeDiscardResult,
): RoutineResumeUiDecision = when (result) {
    RoutineResumeDiscardResult.Discarded,
    RoutineResumeDiscardResult.Missing,
    -> RoutineResumeUiDecision.EnterFreshRoutine

    RoutineResumeDiscardResult.RetryableFailure ->
        RoutineResumeUiDecision.RetainDialog(RoutineResumeRetryAction.DISCARD)

    RoutineResumeDiscardResult.Superseded -> RoutineResumeUiDecision.DismissDialog
}

class MainViewModel(
    private val bleRepository: BleRepository,
    private val workoutRepository: WorkoutRepository,
    val exerciseRepository: ExerciseRepository,
    val personalRecordRepository: PersonalRecordRepository,
    private val repCounter: RepCounterFromMachine,
    private val preferencesManager: PreferencesManager,
    private val gamificationRepository: GamificationRepository,
    private val trainingCycleRepository: TrainingCycleRepository,
    private val completedSetRepository: CompletedSetRepository,
    private val activeWorkoutRuntimeRepository: ActiveWorkoutRuntimeRepository,
    private val dropSetEligibilityPolicy: DropSetEligibilityPolicy,
    private val syncTriggerManager: SyncTriggerManager? = null,
    private val repMetricRepository: RepMetricRepository,
    private val biomechanicsRepository: BiomechanicsRepository,
    private val resolveWeightsUseCase: ResolveRoutineWeightsUseCase,
    private val applyRoutineModifierUseCase: ApplyRoutineModifierUseCase = ApplyRoutineModifierUseCase(personalRecordRepository, exerciseRepository),
    private val recommendWeightAdjustmentUseCase: RecommendWeightAdjustmentUseCase,
    private val equipmentRackRepository: EquipmentRackRepository,
    private val applyEquipmentRackLoadUseCase: ApplyEquipmentRackLoadUseCase,
    private val dataBackupManager: DataBackupManager,
    private val userProfileRepository: UserProfileRepository,
    private val healthIntegration: HealthIntegration? = null,
    private val externalActivityRepository: ExternalActivityRepository? = null,
    private val workoutServiceController: WorkoutServiceController,
    private val healthExportCursorRepository: IntegrationSyncCursorRepository? = null,
    // Velocity-based 1RM (issue #517): computed via GamificationManager's post-save hook.
    private val computeVelocityOneRepMaxUseCase: ComputeVelocityOneRepMaxUseCase,
    private val recordPersonalMvtSampleUseCase: RecordPersonalMvtSampleUseCase,
    // Exposed as a public val so ExerciseDetailScreen can query the latest passing estimate.
    val velocityOneRepMaxRepository: VelocityOneRepMaxRepository,
    private val countVelocityOneRepMaxImprovementsUseCase: CountVelocityOneRepMaxImprovementsUseCase,
    // Issue #517: one-time startup backfill of velocity-1RM estimates for historical data.
    private val backfillVelocityOneRepMaxUseCase: BackfillVelocityOneRepMaxUseCase,
) : ViewModel() {

    // Shared haptic events flow - created here, passed to both GamificationManager and WorkoutSessionManager
    private val _hapticEvents = MutableSharedFlow<HapticEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.SUSPEND,
    )

    // === Phase 1b: SettingsManager (extracted from this class) ===
    private val settingsManager = SettingsManager(preferencesManager, userProfileRepository, viewModelScope)

    // === Phase 1a: HistoryManager (extracted from this class) ===
    val historyManager = HistoryManager(workoutRepository, personalRecordRepository, userProfileRepository, viewModelScope)

    // Active profile id, exposed publicly so profile-scoped reads (e.g. velocity-1RM on
    // ExerciseDetailScreen) query the correct profile instead of a hardcoded "default".
    val activeProfileId: StateFlow<String> =
        userProfileRepository.activeProfile
            .map { it?.id ?: "default" }
            .stateIn(viewModelScope, SharingStarted.Eagerly, "default")

    /**
     * Picker-safe completed IDs.  The tag and loading sentinel prevent a picker from ever
     * using a prior profile's history during an active-profile transition.
     */
    val completedExerciseIdsState: StateFlow<CompletedExerciseIdsState> =
        userProfileRepository.activeProfileContext
            .flatMapLatest { context ->
                when (context) {
                    is ActiveProfileContext.Switching -> flowOf(
                        CompletedExerciseIdsState(
                            profileId = context.targetProfileId,
                            isLoading = true,
                        ),
                    )

                    is ActiveProfileContext.Ready ->
                        workoutRepository.getHistoryVisibleSessions(context.profile.id)
                            .map { sessions ->
                                CompletedExerciseIdsState(
                                    profileId = context.profile.id,
                                    ids = completedExerciseIdsFromHistory(sessions),
                                    isLoading = false,
                                )
                            }
                            .onStart {
                                emit(
                                    CompletedExerciseIdsState(
                                        profileId = context.profile.id,
                                        isLoading = true,
                                    ),
                                )
                            }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = CompletedExerciseIdsState(profileId = null, isLoading = true),
            )

    // === Phase 2b: GamificationManager (extracted from this class) ===
    val gamificationManager: GamificationManager = GamificationManager(
        gamificationRepository,
        personalRecordRepository,
        exerciseRepository,
        _hapticEvents,
        viewModelScope,
        settingsManager.gamificationEnabled,
        // Velocity-based 1RM post-save hook (issue #517): capture personalized-MVT sample then
        // recompute the velocity-1RM estimate for the just-saved exercise.
        onPostSaveComputed = { exId, profile, mcv ->
            mcv?.let { v ->
                exerciseRepository.getExerciseById(exId)?.let { ex ->
                    recordPersonalMvtSampleUseCase(exId, profile, ex.name, ex.muscleGroups, v)
                }
            }
            computeVelocityOneRepMaxUseCase(exId, profile, com.devil.phoenixproject.domain.model.currentTimeMillis())
            val improvements = countVelocityOneRepMaxImprovementsUseCase(
                velocityOneRepMaxRepository.getAllPassing(profile),
            )
            gamificationManager.checkVelocityOneRepMaxBadges(improvements, profile)
        },
    )

    // === Phase 3: WorkoutSessionManager (extracted from this class) ===
    val workoutSessionManager = DefaultWorkoutSessionManager(
        bleRepository = bleRepository,
        workoutRepository = workoutRepository,
        exerciseRepository = exerciseRepository,
        personalRecordRepository = personalRecordRepository,
        repCounter = repCounter,
        preferencesManager = preferencesManager,
        gamificationManager = gamificationManager,
        trainingCycleRepository = trainingCycleRepository,
        completedSetRepository = completedSetRepository,
        activeWorkoutRuntimeRepository = activeWorkoutRuntimeRepository,
        dropSetEligibilityPolicy = dropSetEligibilityPolicy,
        syncTriggerManager = syncTriggerManager,
        repMetricRepository = repMetricRepository,
        biomechanicsRepository = biomechanicsRepository,
        resolveWeightsUseCase = resolveWeightsUseCase,
        applyRoutineModifierUseCase = applyRoutineModifierUseCase,
        recommendWeightAdjustmentUseCase = recommendWeightAdjustmentUseCase,
        equipmentRackRepository = equipmentRackRepository,
        applyEquipmentRackLoadUseCase = applyEquipmentRackLoadUseCase,
        settingsManager = settingsManager,
        dataBackupManager = dataBackupManager,
        userProfileRepository = userProfileRepository,
        healthIntegration = healthIntegration,
        externalActivityRepository = externalActivityRepository,
        healthExportCursorRepository = healthExportCursorRepository,
        workoutServiceController = workoutServiceController,
        scope = viewModelScope,
        _hapticEvents = _hapticEvents,
    )

    // === Phase 2a: BleConnectionManager (extracted from this class) ===
    // Must be after workoutSessionManager since it implements WorkoutStateProvider
    // BLE errors flow one-way via coordinator.bleErrorEvents (no circular dependency)
    val bleConnectionManager = BleConnectionManager(
        bleRepository,
        settingsManager,
        workoutSessionManager,
        workoutSessionManager.coordinator.bleErrorEvents,
        viewModelScope,
    )

    // ===== Workout State Delegation =====

    val workoutState: StateFlow<WorkoutState> get() = workoutSessionManager.coordinator.workoutState
    val machineTeardownState: StateFlow<MachineTeardownState>
        get() = workoutSessionManager.machineTeardownState
    val isWorkoutActive: Boolean get() = workoutSessionManager.coordinator.isWorkoutActive
    val routineFlowState: StateFlow<RoutineFlowState> get() = workoutSessionManager.coordinator.routineFlowState

    /** Issue #348: Session-scoped flag covering active sets AND between-set routine screens */
    val isInWorkoutSession get() = workoutSessionManager.coordinator.isInWorkoutSession
    val currentMetric: StateFlow<WorkoutMetric?> get() = workoutSessionManager.coordinator.currentMetric
    val currentHeuristicKgMax: StateFlow<Float> get() = workoutSessionManager.coordinator.currentHeuristicKgMax
    val loadBaselineA: StateFlow<Float> get() = workoutSessionManager.coordinator.loadBaselineA
    val loadBaselineB: StateFlow<Float> get() = workoutSessionManager.coordinator.loadBaselineB
    val workoutParameters: StateFlow<WorkoutParameters> get() = workoutSessionManager.coordinator.workoutParameters
    val rackItems get() = equipmentRackRepository.rackItems
    val activeRackItemIds: StateFlow<List<String>> get() = workoutSessionManager.coordinator.activeRackItemIds
    val activeRackBehaviorOverrides: StateFlow<Map<String, RackItemBehavior>> get() = workoutSessionManager.coordinator.activeRackBehaviorOverrides
    val currentRackLoadAdjustment: StateFlow<RackLoadAdjustment> get() = workoutSessionManager.coordinator.currentRackLoadAdjustment
    val repCount: StateFlow<RepCount> get() = workoutSessionManager.coordinator.repCount
    val timedExerciseRemainingSeconds: StateFlow<Int?> get() = workoutSessionManager.coordinator.timedExerciseRemainingSeconds
    val repRanges: StateFlow<com.devil.phoenixproject.domain.usecase.RepRanges?> get() = workoutSessionManager.coordinator.repRanges
    val autoStopState: StateFlow<AutoStopUiState> get() = workoutSessionManager.coordinator.autoStopState
    val autoStartCountdown: StateFlow<Int?> get() = workoutSessionManager.coordinator.autoStartCountdown
    val hapticEvents: SharedFlow<HapticEvent> get() = workoutSessionManager.coordinator.hapticEvents
    val userFeedbackEvents: SharedFlow<String> get() = workoutSessionManager.coordinator.userFeedbackEvents
    val routines: StateFlow<List<Routine>> get() = workoutSessionManager.coordinator.routines
    val routineGroups: StateFlow<List<RoutineGroup>> get() = workoutSessionManager.coordinator.routineGroups
    val loadedRoutine: StateFlow<Routine?> get() = workoutSessionManager.coordinator.loadedRoutine
    val currentExerciseIndex: StateFlow<Int> get() = workoutSessionManager.coordinator.currentExerciseIndex
    val currentSetIndex: StateFlow<Int> get() = workoutSessionManager.coordinator.currentSetIndex
    val skippedExercises: StateFlow<Set<Int>> get() = workoutSessionManager.coordinator.skippedExercises
    val completedExercises: StateFlow<Set<Int>> get() = workoutSessionManager.coordinator.completedExercises
    val currentSetRpe: StateFlow<Int?> get() = workoutSessionManager.coordinator.currentSetRpe
    val isCurrentExerciseBodyweight: StateFlow<Boolean> get() = workoutSessionManager.coordinator.isCurrentExerciseBodyweight
    val selectedBodyweightVariants: StateFlow<Map<String, BodyweightVariantOption>> get() = workoutSessionManager.selectedBodyweightVariants
    val sessionBodyweightState: StateFlow<SessionBodyweightState> get() = workoutSessionManager.sessionBodyweightState
    val resolvedBodyWeightKg: Float get() = workoutSessionManager.resolvedBodyWeightKg()
    val latestRepQuality get() = workoutSessionManager.coordinator.latestRepQuality
    val latestBiomechanicsResult get() = workoutSessionManager.coordinator.latestBiomechanicsResult
    val motionStartHoldProgress: StateFlow<Float?> get() = workoutSessionManager.coordinator.motionStartHoldProgress
    val justLiftRestCountdown: StateFlow<Int?> get() = workoutSessionManager.coordinator.justLiftRestCountdown
    val cycleDayCompletionEvent get() = workoutSessionManager.coordinator.cycleDayCompletionEvent
    fun clearCycleDayCompletionEvent() = workoutSessionManager.clearCycleDayCompletionEvent()

    suspend fun tagJustLiftSessionExercise(sessionId: String, exercise: Exercise, isAmrap: Boolean) = workoutSessionManager.tagJustLiftSessionExercise(sessionId, exercise, isAmrap)

    // ===== BLE Connection Delegation =====

    val connectionState: StateFlow<ConnectionState> get() = bleConnectionManager.connectionState
    val scannedDevices: StateFlow<List<ScannedDevice>> get() = bleConnectionManager.scannedDevices
    val isAutoConnecting: StateFlow<Boolean> get() = bleConnectionManager.isAutoConnecting
    val connectionError: StateFlow<String?> get() = bleConnectionManager.connectionError
    val connectionLostDuringWorkout: StateFlow<Boolean> get() = bleConnectionManager.connectionLostDuringWorkout

    fun startScanning() = bleConnectionManager.startScanning()
    fun stopScanning() = bleConnectionManager.stopScanning()
    fun cancelScanOrConnection() = bleConnectionManager.cancelScanOrConnection()
    fun connectToDevice(deviceAddress: String) = bleConnectionManager.connectToDevice(deviceAddress)
    fun disconnect() = bleConnectionManager.disconnect()
    fun clearConnectionError() = bleConnectionManager.clearConnectionError()
    fun dismissConnectionLostAlert() = bleConnectionManager.dismissConnectionLostAlert()
    fun ensureConnection(onConnected: () -> Unit, onFailed: () -> Unit = {}) = bleConnectionManager.ensureConnection(onConnected, onFailed)
    fun reconnectInterruptedWorkout() {
        bleConnectionManager.dismissConnectionLostAlert()
        bleConnectionManager.ensureConnection(
            onConnected = { workoutSessionManager.reconnectInterruptedWorkout() },
            onFailed = {},
        )
    }
    fun cancelConnection() = bleConnectionManager.cancelConnection()

    // ===== History Delegation =====

    val workoutHistory: StateFlow<List<WorkoutSession>> get() = historyManager.workoutHistory
    val allWorkoutSessions: StateFlow<List<WorkoutSession>> get() = historyManager.allWorkoutSessions

    /**
     * Recent sessions for a specific exercise, filtered by profile.
     * Returns the latest [limit] sessions sorted by timestamp descending.
     * Used by ExerciseQuickHistoryCard in SetReadyScreen.
     */
    fun recentSessionsForExercise(
        exerciseId: String?,
        profileId: String?,
        limit: Int = 5,
    ): StateFlow<List<WorkoutSession>> = historyManager.allWorkoutSessions
        .map { sessions ->
            if (profileId == null || exerciseId == null) {
                emptyList()
            } else {
                sessions
                    .filter { it.profileId == profileId && it.exerciseId == exerciseId }
                    .filter { it.workingReps > 0 || it.totalReps > 0 }
                    .sortedWith(
                        compareByDescending<WorkoutSession> { it.timestamp }
                            .thenByDescending { it.id },
                    )
                    .take(limit)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groupedWorkoutHistory: StateFlow<List<HistoryItem>> get() = historyManager.groupedWorkoutHistory
    val isHistoryLoading: StateFlow<Boolean> get() = historyManager.isHistoryLoading
    val allPersonalRecords: StateFlow<List<PersonalRecord>> get() = historyManager.allPersonalRecords

    val completedWorkouts: StateFlow<Int?> get() = historyManager.completedWorkouts
    val workoutStreak: StateFlow<Int?> get() = historyManager.workoutStreak
    val progressPercentage: StateFlow<Int?> get() = historyManager.progressPercentage
    fun deleteWorkout(sessionId: String) = historyManager.deleteWorkout(sessionId)

    /**
     * Issue #591 follow-up (chatgpt-codex-connector P2): route the
     * History "Delete All Sets" group action through the HistoryManager
     * so zero-rep ghost rows hidden by `getHistoryVisibleSessions` are
     * soft-deleted along with the visible sets.
     */
    fun deleteRoutineWorkouts(routineSessionId: String) = historyManager.deleteRoutineWorkouts(routineSessionId)

    fun deleteAllWorkouts() = historyManager.deleteAllWorkouts()

    // ===== Settings Delegation =====

    val userPreferences: StateFlow<UserPreferences> get() = settingsManager.userPreferences
    val weightUnit: StateFlow<WeightUnit> get() = settingsManager.weightUnit
    val enableVideoPlayback: StateFlow<Boolean> get() = settingsManager.enableVideoPlayback
    val autoplayEnabled: StateFlow<Boolean> get() = settingsManager.autoplayEnabled

    val globalSettings: StateFlow<SettingsGlobalUiState> =
        preferencesManager.preferencesFlow
            .map(UserPreferences::toSettingsGlobalUiState)
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                preferencesManager.preferencesFlow.value.toSettingsGlobalUiState(),
            )

    fun setEnableVideoPlayback(enabled: Boolean) = settingsManager.setEnableVideoPlayback(enabled)

    // Issue #333: BLE small-MTU compatibility path (Auto/On/Off)
    fun setBleCompatibilityMode(setting: BleCompatibilitySetting) = settingsManager.setBleCompatibilityMode(setting)
    fun setAutoBackupEnabled(enabled: Boolean) {
        settingsManager.setAutoBackupEnabled(enabled)
        refreshBackupStats()
    }

    fun setBackupDestination(destination: BackupDestination) {
        settingsManager.setBackupDestination(destination)
    }

    fun setLanguage(language: String) {
        settingsManager.setLanguage(language)
    }

    // Backup stats for Settings UI
    private val _backupStats = kotlinx.coroutines.flow.MutableStateFlow<BackupStats?>(null)
    val backupStats: kotlinx.coroutines.flow.StateFlow<BackupStats?> = _backupStats

    fun refreshBackupStats() {
        viewModelScope.launch {
            _backupStats.value = dataBackupManager.getBackupStats()
        }
    }

    fun openBackupFolder() {
        dataBackupManager.openBackupFolder()
    }

    fun kgToDisplay(kg: Float, unit: WeightUnit) = settingsManager.kgToDisplay(kg, unit)
    fun displayToKg(display: Float, unit: WeightUnit) = settingsManager.displayToKg(display, unit)
    fun formatWeight(kg: Float, unit: WeightUnit) = settingsManager.formatWeight(kg, unit)

    // ===== Gamification Delegation =====

    val prCelebrationEvent: SharedFlow<PRCelebrationEvent> get() = gamificationManager.prCelebrationEvent
    val badgeEarnedEvents: SharedFlow<List<Badge>> get() = gamificationManager.badgeEarnedEvents
    fun emitBadgeSound() = gamificationManager.emitBadgeSound()
    fun emitPRSound() = gamificationManager.emitPRSound()

    // ===== Workout Lifecycle Delegation =====

    fun updateWorkoutParameters(params: WorkoutParameters) = workoutSessionManager.updateWorkoutParameters(params)
    fun updateActiveRackSelection(itemIds: List<String>) = workoutSessionManager.updateActiveRackSelection(itemIds)
    fun updateActiveRackBehaviorOverrides(overrides: Map<String, RackItemBehavior>) = workoutSessionManager.updateActiveRackBehaviorOverrides(overrides)
    fun clearActiveRackSelection() = workoutSessionManager.clearActiveRackSelection()
    fun saveRackItem(item: RackItem) {
        val mutation = workoutSessionManager.beginConfigurationInputMutation()
        val job = viewModelScope.launch {
            try {
                equipmentRackRepository.upsert(item)
            } finally {
                workoutSessionManager.endConfigurationInputMutation(mutation)
            }
        }
        job.invokeOnCompletion { workoutSessionManager.endConfigurationInputMutation(mutation) }
    }

    fun deleteRackItem(id: String) {
        val mutation = workoutSessionManager.beginConfigurationInputMutation()
        val job = viewModelScope.launch {
            try {
                equipmentRackRepository.delete(id)
                val activeIds = activeRackItemIds.value
                val remainingActiveIds = activeIds.filterNot { it == id }
                if (remainingActiveIds.size != activeIds.size) {
                    updateActiveRackSelection(remainingActiveIds)
                }
            } finally {
                workoutSessionManager.endConfigurationInputMutation(mutation)
            }
        }
        job.invokeOnCompletion { workoutSessionManager.endConfigurationInputMutation(mutation) }
    }

    fun startWorkout(skipCountdown: Boolean = false, isJustLiftMode: Boolean = false) = workoutSessionManager.startWorkout(skipCountdown, isJustLiftMode)
    fun stopWorkout(exitingWorkout: Boolean = false) = workoutSessionManager.stopWorkout(exitingWorkout)
    fun retryWorkoutTeardown() = workoutSessionManager.retryMachineTeardown()
    fun reconnectWorkoutTeardown() = workoutSessionManager.reconnectWorkoutTeardown(bleConnectionManager)

    // Issue #627: Delegates read-only stop-in-progress flag to suppress resume-bounce.
    fun isStoppingWorkout(): Boolean = workoutSessionManager.isStoppingWorkout

    fun stopAndReturnToSetReady() = workoutSessionManager.stopAndReturnToSetReady()
    fun stopAndSkipCurrentExercise() = workoutSessionManager.stopAndSkipCurrentExercise()
    fun pauseWorkout() = workoutSessionManager.pauseWorkout()
    fun resumeWorkout() = workoutSessionManager.resumeWorkout()
    fun skipCountdown() = workoutSessionManager.skipCountdown()
    fun resetForNewWorkout() = workoutSessionManager.resetForNewWorkout()
    fun recaptureLoadBaseline() = workoutSessionManager.recaptureLoadBaseline()
    fun resetLoadBaseline() = workoutSessionManager.resetLoadBaseline()
    fun proceedFromSummary() = workoutSessionManager.proceedFromSummary()
    fun skipRest() = workoutSessionManager.skipRest()
    fun extendRestTime(seconds: Int) = workoutSessionManager.extendRestTime(seconds)
    fun toggleRestPause() = workoutSessionManager.toggleRestPause()
    fun resetRestTimer() = workoutSessionManager.resetRestTimer()
    val isRestPaused get() = workoutSessionManager.coordinator.isRestPaused

    // Issue #190: Exercise timer controls for timed exercises (TUT/Echo/bodyweight)
    fun pauseExerciseTimer() = workoutSessionManager.pauseExerciseTimer()
    fun resumeExerciseTimer() = workoutSessionManager.resumeExerciseTimer()
    fun resetExerciseTimer() = workoutSessionManager.resetExerciseTimer()
    val isExerciseTimerPaused get() = workoutSessionManager.coordinator.isExerciseTimerPaused

    // Phase 35C: Variable warm-up set state
    val currentWarmupSetIndex: StateFlow<Int> get() = workoutSessionManager.coordinator.currentWarmupSetIndex
    val totalWarmupSets: StateFlow<Int> get() = workoutSessionManager.coordinator.totalWarmupSets
    val weightAdjustmentRecommendation get() = workoutSessionManager.coordinator.weightAdjustmentRecommendation
    fun startNextSet() = workoutSessionManager.startNextSet()
    fun logRpeForCurrentSet(rpe: Int) = workoutSessionManager.logRpeForCurrentSet(rpe)

    // ===== Routine Management Delegation =====

    /**
     * Resolve a routine by ID. Checks the in-memory routines StateFlow first, then falls
     * back to the DB — the StateFlow intentionally filters out "cycle_routine_"-prefixed
     * template-cycle routines (issue #620), which must still be loadable for editing.
     */
    suspend fun getRoutineById(routineId: String): Routine? = workoutSessionManager.getRoutineById(routineId)
        ?: workoutRepository.getRoutineById(routineId)
    fun saveRoutine(routine: Routine) = workoutSessionManager.saveRoutine(routine)
    fun updateRoutine(routine: Routine) = workoutSessionManager.updateRoutine(routine)
    fun saveRackBehaviorOverridesForExercise(
        exerciseIndex: Int,
        overrides: Map<String, RackItemBehavior>,
    ) {
        workoutSessionManager.supersedeConfigurationInputIntent()
        val routine = loadedRoutine.value ?: return
        val exercise = routine.exercises.getOrNull(exerciseIndex) ?: return
        val updatedActiveRoutine = routine.withRackBehaviorOverrides(
            exerciseIndex = exerciseIndex,
            exerciseId = exercise.id,
            overrides = overrides,
        ) ?: return
        workoutSessionManager.updateLoadedRoutineRackBehaviorOverrides(updatedActiveRoutine, overrides)
        viewModelScope.launch {
            try {
                val storedRoutine = workoutRepository.getRoutineById(routine.id)
                if (storedRoutine == null) {
                    Logger.w { "Rack override save skipped: stored routine not found for id=${routine.id}" }
                    return@launch
                }
                val updatedStoredRoutine = storedRoutine.withRackBehaviorOverrides(
                    exerciseIndex = exerciseIndex,
                    exerciseId = exercise.id,
                    overrides = overrides,
                )
                if (updatedStoredRoutine == null) {
                    Logger.w { "Rack override save skipped: exercise id=${exercise.id} not found in stored routine id=${routine.id}" }
                    return@launch
                }
                workoutRepository.updateRoutine(updatedStoredRoutine)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(e) { "Failed to save rack behavior overrides for routine id=${routine.id}, exercise id=${exercise.id}" }
            }
        }
    }
    private fun Routine.withRackBehaviorOverrides(
        exerciseIndex: Int,
        exerciseId: String,
        overrides: Map<String, RackItemBehavior>,
    ): Routine? {
        val targetIndex = exercises.indexOfFirst { it.id == exerciseId }
            .takeIf { it >= 0 }
            ?: if (exerciseId.isBlank()) {
                exerciseIndex.takeIf { it in exercises.indices }
            } else {
                null
            }
            ?: return null

        return copy(
            exercises = exercises.mapIndexed { index, routineExercise ->
                if (index == targetIndex) {
                    routineExercise.copy(rackBehaviorOverrides = overrides)
                } else {
                    routineExercise
                }
            },
        )
    }

    fun deleteRoutine(routineId: String) = workoutSessionManager.deleteRoutine(routineId)
    fun deleteRoutines(routineIds: Set<String>) = workoutSessionManager.deleteRoutines(routineIds)
    fun moveRoutinesToProfile(routineIds: Set<String>, targetProfileId: String) = workoutSessionManager.moveRoutinesToProfile(routineIds, targetProfileId)
    fun saveRoutineToProfile(routine: Routine, targetProfileId: String) = workoutSessionManager.saveRoutineToProfile(routine, targetProfileId)

    // Routine Group CRUD
    fun createGroup(name: String) = workoutSessionManager.createGroup(name)
    fun renameGroup(groupId: String, newName: String) = workoutSessionManager.renameGroup(groupId, newName)
    fun deleteGroup(groupId: String) = workoutSessionManager.deleteGroup(groupId)
    fun moveRoutinesToGroup(routineIds: Set<String>, groupId: String?) = workoutSessionManager.moveRoutinesToGroup(routineIds, groupId)

    fun loadRoutine(routine: Routine) = workoutSessionManager.loadRoutine(routine)

    /** Issue #2 Fix: Suspend version that completes after routine is fully loaded (including PR weight resolution) */
    suspend fun loadRoutineAsync(routine: Routine) = workoutSessionManager.loadRoutineAsync(routine)
    fun loadRoutineById(routineId: String) = workoutSessionManager.loadRoutineById(routineId)
    fun enterRoutineOverview(routine: Routine) = workoutSessionManager.enterRoutineOverview(routine)
    fun enterRoutineOverview(routine: Routine, modifier: AppliedRoutineModifier) = workoutSessionManager.enterRoutineOverview(routine, modifier)
    fun selectExerciseInOverview(index: Int) = workoutSessionManager.selectExerciseInOverview(index)
    fun enterSetReady(exerciseIndex: Int, setIndex: Int) = workoutSessionManager.enterSetReady(exerciseIndex, setIndex)
    fun enterSetReadyWithAdjustments(exerciseIndex: Int, setIndex: Int, adjustedWeight: Float, adjustedReps: Int) = workoutSessionManager.enterSetReadyWithAdjustments(exerciseIndex, setIndex, adjustedWeight, adjustedReps)
    fun updateSetReadyWeight(weight: Float) = workoutSessionManager.updateSetReadyWeight(weight)
    fun updateSetReadyReps(reps: Int) = workoutSessionManager.updateSetReadyReps(reps)
    fun updateSetReadyProgressionKg(valueKg: Float) = workoutSessionManager.updateSetReadyProgressionKg(valueKg)
    fun updateSetReadyEchoLevel(level: EchoLevel) = workoutSessionManager.updateSetReadyEchoLevel(level)
    fun updateSetReadyEccentricLoad(percent: Int) = workoutSessionManager.updateSetReadyEccentricLoad(percent)
    fun startSetFromReady() = workoutSessionManager.startSetFromReady()
    fun applyWeightRecommendation() = workoutSessionManager.applyWeightRecommendation()
    fun dismissWeightRecommendation() = workoutSessionManager.dismissWeightRecommendation()
    fun bodyweightVariantKey(exercise: RoutineExercise): String = workoutSessionManager.bodyweightVariantKey(exercise)
    fun selectBodyweightVariant(exerciseKey: String, variant: BodyweightVariantOption) = workoutSessionManager.selectBodyweightVariant(exerciseKey, variant)
    fun confirmBodyweightSetResult(reps: Int, variant: BodyweightVariantOption) = workoutSessionManager.confirmBodyweightSetResult(reps, variant)
    fun confirmSessionBodyWeight(weightKg: Float?, saveToProfile: Boolean) = workoutSessionManager.confirmSessionBodyWeight(weightKg, saveToProfile)
    fun skipSessionBodyWeightPrompt() = workoutSessionManager.skipSessionBodyWeightPrompt()
    fun returnToOverview() = workoutSessionManager.returnToOverview()

    /**
     * Returns the navigation route to pop to when exiting the current routine flow.
     *
     * *** ORDERING CONTRACT — READ THIS BEFORE CALLING exitRoutineFlow() ***
     * This function MUST be called BEFORE [exitRoutineFlow] (or [stopWorkout] with
     * exitingWorkout=true). Both paths clear [routineLaunchOrigin] to null; calling
     * routineExitDestination() afterwards always returns the default DailyRoutines
     * destination, silently breaking cycle return.
     *
     * Correct call pattern at every exit site:
     *   val dest = viewModel.routineExitDestination()   // 1. read origin FIRST
     *   viewModel.exitRoutineFlow()                     // 2. clears origin
     *   navController.popBackStack(dest, false)         // 3. navigate
     */
    fun routineExitDestination(): String = if (workoutSessionManager.coordinator.routineLaunchOrigin == RoutineLaunchOrigin.TRAINING_CYCLES) {
        NavigationRoutes.TrainingCycles.route
    } else {
        NavigationRoutes.DailyRoutines.route
    }

    fun exitRoutineFlow() = workoutSessionManager.exitRoutineFlow()
    fun showRoutineComplete() = workoutSessionManager.showRoutineComplete()
    fun clearLoadedRoutine() = workoutSessionManager.clearLoadedRoutine()
    fun getCurrentExercise(): RoutineExercise? = workoutSessionManager.getCurrentExercise()
    fun hasResumableProgress(routineId: String): Boolean = workoutSessionManager.hasResumableProgress(routineId)
    fun getResumableProgressInfo(): ResumableProgressInfo? = workoutSessionManager.getResumableProgressInfo()
    suspend fun discoverRoutineResume(
        routine: Routine,
        launchOrigin: RoutineLaunchOrigin,
        cycleId: String? = null,
        cycleDayNumber: Int? = null,
    ): RoutineResumeDiscovery = workoutSessionManager.discoverRoutineResume(
        routine = routine,
        launchOrigin = launchOrigin,
        cycleId = cycleId,
        cycleDayNumber = cycleDayNumber,
    )
    suspend fun resumeRoutine(handle: RoutineResumeHandle): ActiveWorkoutRuntimeResumeResult = workoutSessionManager.resumeRoutine(handle)
    fun isRoutineResumeHandleCurrent(handle: RoutineResumeHandle.InMemory): Boolean = workoutSessionManager.isRoutineResumeHandleCurrent(handle)
    internal fun isRoutineResumeProfileCurrent(profileId: String): Boolean = (userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready)
        ?.profile?.id == profileId
    suspend fun discardRoutineResume(handle: RoutineResumeHandle): RoutineResumeDiscardResult = workoutSessionManager.discardRoutineResume(handle)

    internal fun routineResumeUiPort(): RoutineResumeUiPort = object : RoutineResumeUiPort {
        override suspend fun resume(handle: RoutineResumeHandle): ActiveWorkoutRuntimeResumeResult = resumeRoutine(handle)

        override suspend fun discard(handle: RoutineResumeHandle): RoutineResumeDiscardResult = discardRoutineResume(handle)

        override suspend fun awaitConnection(): Boolean = suspendCancellableCoroutine { continuation ->
            val completed = atomic(false)
            ensureConnection(
                onConnected = {
                    if (completed.compareAndSet(expect = false, update = true)) {
                        continuation.resume(true)
                    }
                },
                onFailed = {
                    if (completed.compareAndSet(expect = false, update = true)) {
                        continuation.resume(false)
                    }
                },
            )
        }

        override fun isInMemoryHandleCurrent(handle: RoutineResumeHandle.InMemory): Boolean = isRoutineResumeHandleCurrent(handle)

        override suspend fun loadDailyRoutine(
            routine: Routine,
            publicationStillCurrent: () -> Boolean,
        ): Boolean = workoutSessionManager.loadRoutineForResumeAsync(
            routine = routine,
            publicationStillCurrent = publicationStillCurrent,
        )

        override suspend fun loadCycleRoutine(
            routine: Routine,
            cycleId: String,
            dayNumber: Int,
            publicationStillCurrent: () -> Boolean,
        ): Boolean = workoutSessionManager.loadRoutineFromCycleForResumeAsync(
            routine = routine,
            cycleId = cycleId,
            dayNumber = dayNumber,
            publicationStillCurrent = publicationStillCurrent,
        )
    }
    fun hasNextStep(exerciseIndex: Int, setIndex: Int): Boolean = workoutSessionManager.hasNextStep(exerciseIndex, setIndex)
    fun hasPreviousStep(exerciseIndex: Int, setIndex: Int): Boolean = workoutSessionManager.hasPreviousStep(exerciseIndex, setIndex)
    fun setReadyPrev() = workoutSessionManager.setReadyPrev()
    fun setReadySkip() = workoutSessionManager.setReadySkip()

    // ===== Exercise Navigation Delegation =====

    fun advanceToNextExercise() = workoutSessionManager.advanceToNextExercise()
    fun jumpToExercise(index: Int) = workoutSessionManager.jumpToExercise(index)
    fun skipCurrentExercise() = workoutSessionManager.skipCurrentExercise()
    fun goToPreviousExercise() = workoutSessionManager.goToPreviousExercise()
    fun canGoBack(): Boolean = workoutSessionManager.canGoBack()
    fun canSkipForward(): Boolean = workoutSessionManager.canSkipForward()
    fun getRoutineExerciseNames(): List<String> = workoutSessionManager.getRoutineExerciseNames()

    // ===== Weight Adjustment Delegation =====

    fun adjustWeight(newWeightKg: Float, sendToMachine: Boolean = true) = workoutSessionManager.adjustWeight(newWeightKg, sendToMachine)
    fun incrementWeight(amount: Float = 0.5f) = workoutSessionManager.incrementWeight(amount)
    fun decrementWeight(amount: Float = 0.5f) = workoutSessionManager.decrementWeight(amount)
    fun setWeightPreset(presetWeightKg: Float) = workoutSessionManager.setWeightPreset(presetWeightKg)
    suspend fun getLastWeightForExercise(exerciseId: String): Float? = workoutSessionManager.getLastWeightForExercise(exerciseId)
    suspend fun getPrWeightForExercise(exerciseId: String): Float? = workoutSessionManager.getPrWeightForExercise(exerciseId)

    // ===== Just Lift / Handle Detection Delegation =====

    fun enableHandleDetection() = workoutSessionManager.enableHandleDetection()
    fun disableHandleDetection() = workoutSessionManager.disableHandleDetection()
    fun prepareForJustLift() = workoutSessionManager.prepareForJustLift()
    suspend fun getJustLiftDefaults(): JustLiftDefaults = workoutSessionManager.getJustLiftDefaults()
    fun saveJustLiftDefaults(defaults: JustLiftDefaults) = workoutSessionManager.saveJustLiftDefaults(defaults)
    suspend fun getSingleExerciseDefaults(exerciseId: String): com.devil.phoenixproject.data.preferences.SingleExerciseDefaults? = workoutSessionManager.getSingleExerciseDefaults(exerciseId)
    fun saveSingleExerciseDefaults(defaults: com.devil.phoenixproject.data.preferences.SingleExerciseDefaults) = workoutSessionManager.saveSingleExerciseDefaults(defaults)

    // ===== Superset CRUD Delegation =====

    suspend fun createSuperset(routineId: String, name: String? = null, exercises: List<RoutineExercise> = emptyList()) = workoutSessionManager.createSuperset(routineId, name, exercises)
    suspend fun updateSuperset(routineId: String, superset: Superset) = workoutSessionManager.updateSuperset(routineId, superset)
    suspend fun deleteSuperset(routineId: String, supersetId: String) = workoutSessionManager.deleteSuperset(routineId, supersetId)
    suspend fun addExerciseToSuperset(routineId: String, exerciseId: String, supersetId: String) = workoutSessionManager.addExerciseToSuperset(routineId, exerciseId, supersetId)
    suspend fun removeExerciseFromSuperset(routineId: String, exerciseId: String) = workoutSessionManager.removeExerciseFromSuperset(routineId, exerciseId)

    // ===== Training Cycle Delegation =====

    fun loadRoutineFromCycle(routineId: String, cycleId: String, dayNumber: Int) = workoutSessionManager.loadRoutineFromCycle(routineId, cycleId, dayNumber)
    suspend fun loadRoutineFromCycleAsync(routineId: String, cycleId: String, dayNumber: Int) = workoutSessionManager.loadRoutineFromCycleAsync(routineId, cycleId, dayNumber)
    fun clearCycleContext() = workoutSessionManager.clearCycleContext()

    // ===== Top Bar State (stays here - pure UI scaffolding) =====

    private val _topBarTitle = MutableStateFlow("Project Phoenix")
    val topBarTitle: StateFlow<String> = _topBarTitle.asStateFlow()

    fun updateTopBarTitle(title: String) {
        _topBarTitle.value = title
    }

    private val _topBarActions = MutableStateFlow<List<TopBarAction>>(emptyList())
    val topBarActions: StateFlow<List<TopBarAction>> = _topBarActions.asStateFlow()

    fun setTopBarActions(actions: List<TopBarAction>) {
        _topBarActions.value = actions
    }

    fun clearTopBarActions() {
        _topBarActions.value = emptyList()
    }

    private val _topBarBackAction = MutableStateFlow<(() -> Unit)?>(null)
    val topBarBackAction: StateFlow<(() -> Unit)?> = _topBarBackAction.asStateFlow()

    fun setTopBarBackAction(action: () -> Unit) {
        _topBarBackAction.value = action
    }

    fun clearTopBarBackAction() {
        _topBarBackAction.value = null
    }

    // ===== Workout Setup Dialog (stays here - pure UI state) =====

    private val _isWorkoutSetupDialogVisible = MutableStateFlow(false)
    val isWorkoutSetupDialogVisible: StateFlow<Boolean> = _isWorkoutSetupDialogVisible.asStateFlow()

    // ===== Disco Mode (Easter Egg - stays here) =====

    val discoModeActive: StateFlow<Boolean> = bleRepository.discoModeActive

    fun toggleDiscoMode(enabled: Boolean) {
        if (enabled) {
            bleRepository.startDiscoMode()
        } else {
            bleRepository.stopDiscoMode()
        }
    }

    fun emitDiscoSound() {
        viewModelScope.launch {
            _hapticEvents.emit(HapticEvent.DISCO_MODE_UNLOCKED)
        }
    }

    /** Issue #611: Dominatrix 7-tap unlock → emit whip-crack SFX */
    fun emitDominatrixUnlockSound() {
        viewModelScope.launch {
            _hapticEvents.emit(HapticEvent.DOMINATRIX_MODE_UNLOCKED)
        }
    }

    // ===== Test Sounds (stays here - developer utility) =====

    fun testSounds() {
        viewModelScope.launch {
            val events = currentProfileTestSoundEvents(settingsManager.userPreferences.value)
            events.forEachIndexed { index, event ->
                _hapticEvents.emit(event)
                if (index < events.lastIndex) {
                    kotlinx.coroutines.delay(if (event == HapticEvent.REP_COMPLETED) 800 else 1000)
                }
            }
        }
    }

    // ===== Velocity-1RM Backfill (Issue #517) =====

    init {
        // Run once at startup: backfill velocity-1RM estimates for historical sets.
        // Gated by a run-once preference flag so it never re-runs after the first successful pass.
        //
        // F050: launch as a normal child of viewModelScope (NOT NonCancellable).
        // Passing NonCancellable as the launch context replaces structured
        // cancellation, so this long DB backfill would keep running after the
        // ViewModel is cleared and hold repository/ViewModel references past the
        // lifecycle. The work is idempotent (run-once flag set only on success,
        // per-profile try/catch), so cancelling on clear is safe — it resumes on
        // the next launch.
        viewModelScope.launch {
            try {
                if (!settingsManager.velocityOneRepMaxBackfillDone.value) {
                    // Backfill EVERY profile's history (not just the active one): the run-once flag
                    // is global, so a profile skipped here would never be backfilled. Await the
                    // loaded profile list (10s timeout fallback to the active profile id for genuine
                    // single-profile installs whose list stays empty).
                    val profileIds = (
                        kotlinx.coroutines.withTimeoutOrNull(10_000) {
                            userProfileRepository.allProfiles.first { it.isNotEmpty() }
                        } ?: userProfileRepository.allProfiles.value
                        ).map { it.id }.ifEmpty { listOf(activeProfileId.value) }
                    val now = com.devil.phoenixproject.domain.model.currentTimeMillis()
                    // Per-profile try/catch so one profile's failure can't abort the rest, and the
                    // run-once flag is still set afterwards (a failed profile is covered later by its
                    // own new workouts / hasEstimates idempotency rather than blocking every launch).
                    for (profileId in profileIds) {
                        try {
                            backfillVelocityOneRepMaxUseCase(profileId, now)
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            Logger.w(e) { "VELOCITY_1RM: backfill failed for profile=$profileId" }
                        }
                    }
                    preferencesManager.setVelocityOneRepMaxBackfillDone(true)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Logger.w(e) { "VELOCITY_1RM: backfill failed" }
            }
        }
    }

    // ===== Cleanup =====

    override fun onCleared() {
        super.onCleared()
        workoutSessionManager.cleanup()
        bleConnectionManager.cancelConnectionJob()

        // Issue: BLE resource leak - Disconnect BLE when ViewModel is cleared
        // to prevent battery drain and orphaned connections.
        // Use NonCancellable context since viewModelScope may be cancelled during onCleared
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
            try {
                bleRepository.disconnect()
                Logger.i { "BLE disconnected during ViewModel cleanup" }
            } catch (e: Exception) {
                Logger.e { "Failed to disconnect BLE during cleanup: ${e.message}" }
            }
        }

        Logger.i { "MainViewModel cleared, all jobs cancelled" }
    }
}
