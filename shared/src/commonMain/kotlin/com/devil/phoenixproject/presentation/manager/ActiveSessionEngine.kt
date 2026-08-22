package com.devil.phoenixproject.presentation.manager

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.integration.ExternalActivityRepository
import com.devil.phoenixproject.data.integration.HealthExportMarkers
import com.devil.phoenixproject.data.integration.HealthIntegration
import com.devil.phoenixproject.data.integration.HealthWorkoutExportBuilder
import com.devil.phoenixproject.data.integration.IntegrationSyncCursorRepository
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.preferences.toDocument
import com.devil.phoenixproject.data.preferences.toLegacySingleExerciseDefaults
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeDiscoveryResult
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeDocument
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeLoadResult
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeLookupKey
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeRepository
import com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeResumeResult
import com.devil.phoenixproject.data.repository.AutoStopUiState
import com.devil.phoenixproject.data.repository.BiomechanicsRepository
import com.devil.phoenixproject.data.repository.BleRepository
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.ConnectionLogRepository
import com.devil.phoenixproject.data.repository.EquipmentRackRepository
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.HandleState
import com.devil.phoenixproject.data.repository.LogEventType
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.ProfileEquipmentRackRepository
import com.devil.phoenixproject.data.repository.RepMetricRepository
import com.devil.phoenixproject.data.repository.RepNotification
import com.devil.phoenixproject.data.repository.RestoredTeardownSeedSnapshot
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.data.sync.SyncTriggerManager
import com.devil.phoenixproject.domain.model.ActiveRackSelection
import com.devil.phoenixproject.domain.model.BiomechanicsRepResult
import com.devil.phoenixproject.domain.model.BiomechanicsSetSummary
import com.devil.phoenixproject.domain.model.BodyweightVariantOption
import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.ConnectionStatus
import com.devil.phoenixproject.domain.model.DropPercentage
import com.devil.phoenixproject.domain.model.DropSetCandidateResolution
import com.devil.phoenixproject.domain.model.DropSetConfiguration
import com.devil.phoenixproject.domain.model.ExerciseLoadOverlay
import com.devil.phoenixproject.domain.model.FiveThreeOneRoutineDetector
import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.JustLiftDefaultsDocument
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.PlannedSetAttemptState
import com.devil.phoenixproject.domain.model.ProfileSectionMetadata
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RepCount
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.RepEvent
import com.devil.phoenixproject.domain.model.RepMetricData
import com.devil.phoenixproject.domain.model.RepType
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExecutionIdentity
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.RoutineItem
import com.devil.phoenixproject.domain.model.RoutineLaunchOrigin
import com.devil.phoenixproject.domain.model.SessionBodyweightState
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetQualitySummary
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.SingleExerciseDefaultsDocument
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.WeightAdjustmentInput
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.model.WorkoutState
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.elapsedRealtimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.domain.premium.BiomechanicsEngine
import com.devil.phoenixproject.domain.replay.RepBoundaryDetector
import com.devil.phoenixproject.domain.usecase.ApplyEquipmentRackLoadUseCase
import com.devil.phoenixproject.domain.usecase.BodyweightVolumeCalculator
import com.devil.phoenixproject.domain.usecase.DropSetCandidateRequest
import com.devil.phoenixproject.domain.usecase.DropSetCandidateResolver
import com.devil.phoenixproject.domain.usecase.DropSetEligibilityPolicy
import com.devil.phoenixproject.domain.usecase.DropSetEligibilityRequest
import com.devil.phoenixproject.domain.usecase.RecommendWeightAdjustmentUseCase
import com.devil.phoenixproject.domain.usecase.RegenerateFiveThreeOneRoutinesUseCase
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.domain.usecase.RoutineSetWeightRequest
import com.devil.phoenixproject.domain.usecase.RoutineSetWeightResolver
import com.devil.phoenixproject.getPlatform
import com.devil.phoenixproject.util.BleConstants
import com.devil.phoenixproject.util.BlePacketFactory
import com.devil.phoenixproject.util.Constants
import com.devil.phoenixproject.util.DataBackupManager
import com.devil.phoenixproject.util.KmpUtils
import com.devil.phoenixproject.util.WorkoutCommandValidator
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

internal fun UserPreferences.verbalEncouragementEventOrNull(): HapticEvent.VERBAL_ENCOURAGEMENT? {
    if (!beepsEnabled || !verbalEncouragementEnabled) return null
    val effectiveVulgar = vulgarModeEnabled && adultsOnlyConfirmed
    val effectiveDominatrix = effectiveVulgar &&
        dominatrixModeUnlocked && dominatrixModeActive
    return HapticEvent.VERBAL_ENCOURAGEMENT(
        vulgarTier = vulgarTier,
        dominatrixMode = effectiveDominatrix,
        vulgarMode = effectiveVulgar,
    )
}

data class BiomechanicsRepInput(
    val repNumber: Int,
    val concentricMetrics: List<WorkoutMetric>,
    val allRepMetrics: List<WorkoutMetric>,
    val timestamp: Long,
)

fun interface BiomechanicsRepProcessor {
    suspend fun process(
        engine: BiomechanicsEngine,
        input: BiomechanicsRepInput,
    ): BiomechanicsRepResult

    companion object {
        val Default = BiomechanicsRepProcessor { engine, input ->
            engine.processRep(
                repNumber = input.repNumber,
                concentricMetrics = input.concentricMetrics,
                allRepMetrics = input.allRepMetrics,
                timestamp = input.timestamp,
            )
        }
    }
}

private class ExecutionBiomechanicsContext(
    val lease: ExecutionLease,
    val engine: BiomechanicsEngine,
    var velocityThresholdAlertEmitted: Boolean = false,
    var consecutiveThresholdReps: Int = 0,
)

private data class PendingTeardownReadyContinuation(
    val lease: ExecutionLease,
    val callback: () -> Unit,
)

private data class ResetMachineTeardownOwner(
    val id: Long,
    val lease: ExecutionLease,
)

private data class ExternalCommandInputStamp(
    val profileId: String,
    val profileRackItems: List<RackItem>,
    val profileRackMetadata: ProfileSectionMetadata,
    val repositoryRackItems: List<RackItem>,
)

private data class QueuedStartCandidate(
    val profileId: String,
    val loadedRoutineId: String?,
    val routineId: String?,
    val routineSessionId: String?,
    val routineName: String?,
    val cycleId: String?,
    val cycleDayNumber: Int?,
    val routineExercise: RoutineExercise?,
    val selectedExerciseId: String?,
    val exerciseIndex: Int,
    val setIndex: Int,
    val warmupSetIndex: Int,
    val requiresMachine: Boolean,
    val workoutParameters: WorkoutParameters,
    val activeRackItemIds: List<String>,
    val activeRackBehaviorOverrides: Map<String, RackItemBehavior>,
    val externalCommandInputStamp: ExternalCommandInputStamp,
)

private data class PendingResetStart(
    val owner: ResetMachineTeardownOwner,
    val successorToken: NoCurrentSuccessorToken,
    val candidate: QueuedStartCandidate,
    val skipCountdown: Boolean,
    val isJustLiftMode: Boolean,
)

private data class DeferredMachineConfigurationTeardown(
    val lease: ExecutionLease,
    val reason: TeardownReason,
    val attempt: Int,
    val afterReady: (() -> Unit)?,
)

private data class PersistedRestTimerOwner(
    val transitionId: String,
    val sourceExecutionId: String,
    val job: Job,
    var navigationParametersPublished: Boolean = false,
)

private data class PersistedRestTimerClaim(
    val previousJob: Job?,
)

private data class PersistedRestTimerActionAuthority(
    val timerJob: Job,
    val transitionId: String,
    val sourceExecutionId: String,
    val plan: RestTransitionPlan,
    val documentVersion: Long,
    val deadlineEpochMs: Long?,
    val isPaused: Boolean,
    val requiresExpiredDeadline: Boolean,
)

private data class AuthorizedPersistedRestTimerAction(
    val command: RestTransitionCommand.SkipRest,
    val authority: PersistedRestTimerActionAuthority,
)

private data class RestTransitionApplicationResult(
    val reduction: RestTransitionReduction,
    val normalTransitionConsumed: Boolean,
)

private data class RestoredRuntimeOwner(
    val handle: RoutineResumeHandle.Persisted,
    val document: ActiveWorkoutRuntimeDocument,
    val documentVersion: Long,
    val guardOwner: RestoredRuntimeOwnerToken,
    val sourceContext: RestoredRetrySourceContext,
    val rackBehaviorOverrides: Map<String, RackItemBehavior>,
    val externalCommandInputStamp: ExternalCommandInputStamp,
)

private data class RestoredRestTimerOwner(
    val guardOwner: RestoredRuntimeOwnerToken,
    val document: ActiveWorkoutRuntimeDocument,
    val documentVersion: Long,
    val transitionId: String,
    val sourceExecutionId: String,
    val monotonicDeadlineElapsedRealtimeMs: Long?,
    val job: Job?,
)

private data class RestoredRestTimerPublication(
    val jobToStart: Job?,
    val jobsToCancel: List<Job>,
)

private sealed interface RestoredRestTimerMutation {
    data class Extend(val seconds: Int) : RestoredRestTimerMutation
    data object TogglePause : RestoredRestTimerMutation
    data object Reset : RestoredRestTimerMutation
}

private data class RestoredNormalDispatch(
    val owner: RestoredRuntimeOwner,
    val plan: RestTransitionPlan.NormalAdvance,
    val routine: Routine,
    val exerciseIndex: Int,
    val setIndex: Int,
)

private data class RestoredActionApplication(
    val reduction: RestTransitionReduction,
    val normalDispatch: RestoredNormalDispatch? = null,
    val acceptedOwner: RestoredRuntimeOwner? = null,
)

private data class PendingRuntimeResume(
    val handle: RoutineResumeHandle.Persisted,
    val externalCommandInputStamp: ExternalCommandInputStamp,
    val result: CompletableDeferred<ActiveWorkoutRuntimeResumeResult>,
)

internal enum class RuntimeCleanupReason {
    ROUTINE_COMPLETED,
    END_WORKOUT,
    EXPLICIT_RESTART,
    PROFILE_CHANGED,
    IDENTITY_MISMATCH,
    INVALID_DOCUMENT,
    DISCARD_RECOVERY,
}

private enum class RuntimeCleanupPlanVariant { NONE, NORMAL, UNRESOLVED, ACCEPTED, DECLINED }

private sealed interface RuntimeCleanupSource {
    data class ActiveDocument(
        val engineVersion: Long,
        val sourceExecutionId: String,
        val sourceStableSessionId: String,
    ) : RuntimeCleanupSource

    data class PendingInitialReplace(val candidateToken: Long) : RuntimeCleanupSource

    data class PendingEngineReplace(
        val candidateToken: Long,
        val expectedPublishedEngineVersion: Long?,
    ) : RuntimeCleanupSource

    data class RestoredOwner(
        val owner: RestoredRuntimeOwnerToken,
        val engineVersion: Long,
    ) : RuntimeCleanupSource

    data class ColdHandle(
        val rowRevision: com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeRowRevision,
    ) : RuntimeCleanupSource
}

private data class RuntimeCleanupCandidate(
    val document: ActiveWorkoutRuntimeDocument,
    val source: RuntimeCleanupSource,
)

private data class PendingRuntimeReplaceCandidate(
    val candidateToken: Long,
    val document: ActiveWorkoutRuntimeDocument,
    val origin: RuntimeCleanupCandidate,
    val expectedPublishedEngineVersion: Long?,
)

private data class RuntimeCleanupTarget(
    val reason: RuntimeCleanupReason,
    val lookupKey: ActiveWorkoutRuntimeLookupKey,
    val initialRowRevision: com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeRowRevision?,
    val expectedDocument: ActiveWorkoutRuntimeDocument?,
    val source: RuntimeCleanupSource,
    val planVariant: RuntimeCleanupPlanVariant,
    val exitPresentation: Boolean,
)

private enum class RuntimeCleanupResult { CLEARED, RETRYABLE_FAILURE, SUPERSEDED }

private data class RuntimeCleanupDetachedEffects(
    val completeNavigationFollower: (() -> Unit)? = null,
    val jobs: List<Job> = emptyList(),
)

private data class CompletedNoLeasePresentation(
    val loadedRoutine: Routine,
    val routineId: String?,
    val routineSessionId: String?,
    val workoutState: WorkoutState,
    val routineFlowState: RoutineFlowState.Complete,
)

private sealed interface RuntimeHydrationValidation {
    data class Valid(
        val document: ActiveWorkoutRuntimeDocument,
        val preparation: RoutineRecoveryPreparation,
        val sourceContext: RestoredRetrySourceContext,
    ) : RuntimeHydrationValidation

    data object Invalid : RuntimeHydrationValidation
    data object RetryableFailure : RuntimeHydrationValidation
}

private data class PlanOwnedRestPresentation(
    val documentVersion: Long,
    val remainingSeconds: Int,
    val originalDurationSeconds: Int,
    val isPaused: Boolean,
    val restingState: WorkoutState.Resting,
    val navigationParameters: WorkoutParameters?,
)

private data class AcceptedRetryPermission(
    val transitionId: String,
    val sourceExecutionId: String,
    val documentVersion: Long,
    val source: AcceptedRetryPermissionSource,
)

private sealed interface AcceptedRetryPermissionSource {
    data object Manual : AcceptedRetryPermissionSource

    data class Timer(
        val ownerJob: Job,
        val deadlineEpochMs: Long?,
    ) : AcceptedRetryPermissionSource

    data class Restored(
        val owner: RestoredRuntimeOwnerToken,
    ) : AcceptedRetryPermissionSource
}

private data class AcceptedRetryGateSnapshot(
    val gate: RetryPersistenceGate,
    val document: ActiveWorkoutRuntimeDocument,
    val documentVersion: Long,
    val plan: RestTransitionPlan.AcceptedRetry,
    val permission: AcceptedRetryPermission?,
    val expectedSource: ExecutionLease?,
    val restoredOwner: RestoredRuntimeOwner?,
    val completion: SetExecutionCompletion?,
    val commandTemplate: WorkoutParameters,
    val recoveryPublicationEpoch: Long,
    val configurationInputEpoch: Long,
    val externalCommandInputStamp: ExternalCommandInputStamp,
)

private data class AcceptedRetryStartClaim(
    val token: String,
    val transitionId: String,
    val sourceStableSessionId: String,
    val sourceExecutionId: String,
    val attemptNumber: Int,
)

private data class RetryStartRequest(
    val startClaim: AcceptedRetryStartClaim? = null,
    val expectedSource: ExecutionLease?,
    val restoredOwnerToken: RestoredRuntimeOwnerToken? = null,
    val sourceStableSessionId: String,
    val sourceAttemptNumber: Int,
    val requiresLivePersistedClaim: Boolean,
    val runtimeDocumentVersion: Long,
    val recoveryPublicationEpoch: Long,
    val configurationInputEpoch: Long,
    val externalCommandInputStamp: ExternalCommandInputStamp?,
    val profileId: String,
    val routineId: String,
    val routineSessionId: String,
    val routineExerciseId: String,
    val logicalSetKey: LogicalSetKey,
    val plannedSetId: String?,
    val plannedTargetReps: Int?,
    val plannedTargetWeightKg: Float?,
    val exerciseIndex: Int,
    val setIndex: Int,
    val attemptNumber: Int,
    val acceptedDropCount: Int,
    val percentage: DropPercentage,
    val sourceConfiguredStartWeightPerCableKg: Float,
    val sourceCommandTemplate: WorkoutParameters,
    val sourceIsTimed: Boolean,
    val sourceIsBodyweight: Boolean,
    val sourceIsCableExercise: Boolean,
    val sourcePhysicalCableCount: Int?,
    val occurrenceMultiplier: Float,
    val expectedWeightPerCableKg: Float,
    val programmedBaseWeightPerCableKg: Float,
    val params: WorkoutParameters,
    val rackBehaviorOverrides: Map<String, RackItemBehavior>,
)

private sealed interface RetryGateCapture {
    data class Ready(val snapshot: AcceptedRetryGateSnapshot) : RetryGateCapture
    data object Wait : RetryGateCapture
    data class FailClosed(
        val plan: RestTransitionPlan.AcceptedRetry,
        val documentVersion: Long,
        val authority: RetryFailClosedAuthority,
    ) : RetryGateCapture
}

private sealed interface RetryFailClosedAuthority {
    val recoveryPublicationEpoch: Long

    data class Live(
        val source: ExecutionLease,
        override val recoveryPublicationEpoch: Long,
    ) : RetryFailClosedAuthority

    data class Restored(
        val owner: RestoredRuntimeOwnerToken,
        override val recoveryPublicationEpoch: Long,
    ) : RetryFailClosedAuthority
}

private sealed interface RetryRequestBuildResult {
    data class Ready(val request: RetryStartRequest) : RetryRequestBuildResult
    data class FailClosed(val recovery: RetryStartRequest?) : RetryRequestBuildResult
    data object Wait : RetryRequestBuildResult
}

/**
 * Handles all workout lifecycle logic: start/stop, rep processing, auto-stop,
 * BLE commands, rest timer, session persistence, weight adjustment, Just Lift,
 * and training cycles.
 *
 * Extracted from DefaultWorkoutSessionManager during Phase 2 (Manager Decomposition) Plan 04.
 *
 * Communication:
 * - Reads/writes all state through [coordinator] (WorkoutCoordinator)
 * - NEVER holds references to RoutineFlowManager
 * - For operations requiring routine navigation, uses [WorkoutFlowDelegate]
 *
 * Scope: Receives the SAME CoroutineScope as DWSM for TestScope compatibility.
 */
class ActiveSessionEngine(
    val coordinator: WorkoutCoordinator,
    private val bleRepository: BleRepository,
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val repCounter: RepCounterFromMachine,
    private val preferencesManager: PreferencesManager,
    private val gamificationManager: GamificationManager,
    private val trainingCycleRepository: TrainingCycleRepository,
    private val completedSetRepository: CompletedSetRepository,
    private val activeWorkoutRuntimeRepository: ActiveWorkoutRuntimeRepository,
    private val dropSetEligibilityPolicy: DropSetEligibilityPolicy,
    private val dropSetConfigurationProvider: (RoutineExercise) -> DropSetConfiguration,
    private val transitionIdGenerator: () -> String,
    private val offerIdGenerator: () -> String,
    private val syncTriggerManager: SyncTriggerManager?,
    private val repMetricRepository: RepMetricRepository,
    private val biomechanicsRepository: BiomechanicsRepository,
    private val recommendWeightAdjustmentUseCase: RecommendWeightAdjustmentUseCase,
    private val equipmentRackRepository: EquipmentRackRepository,
    private val applyEquipmentRackLoadUseCase: ApplyEquipmentRackLoadUseCase,
    private val settingsManager: SettingsManager,
    private val userProfileRepository: UserProfileRepository,
    private val scope: CoroutineScope,
    private val biomechanicsDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val biomechanicsRepProcessor: BiomechanicsRepProcessor = BiomechanicsRepProcessor.Default,
    private val beforeVbtCommit: (executionId: Long, sessionId: String, repNumber: Int) -> Unit = { _, _, _ -> },
    private val afterVbtDecisionCommit: (executionId: Long, sessionId: String, repNumber: Int) -> Unit = { _, _, _ -> },
    private val afterCompletionClaim: (executionId: Long, sessionId: String, reason: SetEndReason) -> Unit = { _, _, _ -> },
    private val beforeBodyweightCompletionClaim: (executionId: Long, sessionId: String) -> Unit = { _, _ -> },
    private val afterBodyweightCompletionConsume: (executionId: Long, sessionId: String) -> Unit = { _, _ -> },
    private val afterResetInvalidation: (executionId: Long, sessionId: String) -> Unit = { _, _ -> },
    private val afterExecutionBegin: (outgoingExecutionId: Long?, executionId: Long) -> Unit = { _, _ -> },
    private val regenerateFiveThreeOneUseCase: RegenerateFiveThreeOneRoutinesUseCase? = null,
    private val dataBackupManager: DataBackupManager? = null,
    private val healthIntegration: HealthIntegration? = null,
    private val externalActivityRepository: ExternalActivityRepository? = null,
    private val healthExportCursorRepository: IntegrationSyncCursorRepository? = null,
    private val elapsedRealtimeProvider: () -> Long = ::elapsedRealtimeMillis,
    private val wallClockMillisProvider: () -> Long = ::currentTimeMillis,
) {
    internal suspend fun discoverRoutineResume(
        routine: Routine,
        inMemoryProgress: InMemoryRoutineProgressSnapshot?,
        launchOrigin: RoutineLaunchOrigin,
        cycleId: String?,
        cycleDayNumber: Int?,
    ): RoutineResumeDiscovery {
        val readyProfile = userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready
            ?: return RoutineResumeDiscovery.Superseded
        if (routine.profileId != readyProfile.profile.id) return RoutineResumeDiscovery.Superseded
        if (launchOrigin == RoutineLaunchOrigin.TRAINING_CYCLES &&
            (cycleId.isNullOrBlank() || cycleDayNumber == null || cycleDayNumber <= 0)
        ) {
            return RoutineResumeDiscovery.Superseded
        }
        val generation = captureRoutineResumeGeneration()
        if (inMemoryProgress != null) {
            if (inMemoryProgress.loadedRoutine.id != routine.id ||
                inMemoryProgress.loadedRoutine.profileId != readyProfile.profile.id ||
                inMemoryProgress.configurationInputEpoch != generation.configurationInputEpoch
            ) {
                return RoutineResumeDiscovery.Superseded
            }
            return RoutineResumeDiscovery.Candidate(
                RoutineResumeHandle.InMemory(
                    selectedProfileId = readyProfile.profile.id,
                    selectedRoutine = routine,
                    activeRoutineSnapshot = inMemoryProgress.loadedRoutine,
                    progressInfo = inMemoryProgress.progressInfo,
                    launchOrigin = launchOrigin,
                    cycleId = cycleId,
                    cycleDayNumber = cycleDayNumber,
                    managerGeneration = generation,
                    exerciseIndex = inMemoryProgress.exerciseIndex,
                    setIndex = inMemoryProgress.setIndex,
                    routineSessionId = inMemoryProgress.routineSessionId,
                    activeLaunchOrigin = inMemoryProgress.launchOrigin,
                    activeCycleId = inMemoryProgress.cycleId,
                    activeCycleDayNumber = inMemoryProgress.cycleDayNumber,
                ),
            )
        }
        val discovery = try {
            activeWorkoutRuntimeRepository.discover(readyProfile.profile.id, routine.id)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return RoutineResumeDiscovery.RetryableFailure
        }
        currentCoroutineContext().ensureActive()
        val currentProfile = userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready
        if (currentProfile?.profile?.id != readyProfile.profile.id ||
            captureRoutineResumeGeneration() != generation
        ) {
            return RoutineResumeDiscovery.Superseded
        }
        val found = discovery as? ActiveWorkoutRuntimeDiscoveryResult.Found
            ?: return RoutineResumeDiscovery.Missing
        val rowRevision: com.devil.phoenixproject.data.repository.ActiveWorkoutRuntimeRowRevision
        val progressInfo: ResumableProgressInfo
        val manualRecoveryCoordinates: RestTransitionPlan.Coordinates?
        when (val loadResult = found.loadResult) {
            ActiveWorkoutRuntimeLoadResult.Missing -> return RoutineResumeDiscovery.Missing

            is ActiveWorkoutRuntimeLoadResult.Loaded -> {
                rowRevision = loadResult.rowRevision
                manualRecoveryCoordinates = validatedManualRecoveryCoordinates(
                    routine = routine,
                    routineExerciseId = loadResult.document.routineExerciseId,
                    exerciseIndex = loadResult.document.sourceExerciseIndex,
                    setIndex = loadResult.document.sourceSetIndex,
                )
                progressInfo = persistedProgressInfo(routine, manualRecoveryCoordinates)
            }

            is ActiveWorkoutRuntimeLoadResult.Rejected -> {
                val attribution = loadResult.attribution
                    ?: return RoutineResumeDiscovery.Missing
                rowRevision = loadResult.rowRevision
                manualRecoveryCoordinates = validatedManualRecoveryCoordinates(
                    routine = routine,
                    routineExerciseId = attribution.routineExerciseId,
                    exerciseIndex = attribution.sourceExerciseIndex,
                    setIndex = attribution.sourceSetIndex,
                )
                progressInfo = persistedProgressInfo(routine, manualRecoveryCoordinates)
            }
        }
        return RoutineResumeDiscovery.Candidate(
            RoutineResumeHandle.Persisted(
                selectedProfileId = readyProfile.profile.id,
                selectedRoutine = routine,
                lookupKey = found.lookupKey,
                rowRevision = rowRevision,
                progressInfo = progressInfo,
                launchOrigin = launchOrigin,
                cycleId = cycleId,
                cycleDayNumber = cycleDayNumber,
                managerGeneration = generation,
                manualRecoveryCoordinates = manualRecoveryCoordinates,
            ),
        )
    }

    private fun inMemoryRoutineHandleIsCurrent(handle: RoutineResumeHandle.InMemory): Boolean {
        val profile = userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready
            ?: return false
        return handle.selectedProfileId == profile.profile.id &&
            handle.selectedRoutine.profileId == profile.profile.id &&
            handle.activeRoutineSnapshot.profileId == profile.profile.id &&
            captureRoutineResumeGeneration() == handle.managerGeneration &&
            coordinator._loadedRoutine.value == handle.activeRoutineSnapshot &&
            coordinator.currentRoutineSessionId == handle.routineSessionId &&
            coordinator._currentExerciseIndex.value == handle.exerciseIndex &&
            coordinator._currentSetIndex.value == handle.setIndex &&
            coordinator.routineLaunchOrigin == handle.activeLaunchOrigin &&
            coordinator.activeCycleId == handle.activeCycleId &&
            coordinator.activeCycleDayNumber == handle.activeCycleDayNumber
    }

    internal fun resumeInMemoryRoutine(
        handle: RoutineResumeHandle.InMemory,
    ): ActiveWorkoutRuntimeResumeResult = if (inMemoryRoutineHandleIsCurrent(handle)) {
        ActiveWorkoutRuntimeResumeResult.Missing
    } else {
        ActiveWorkoutRuntimeResumeResult.Superseded
    }

    internal fun isRoutineResumeHandleCurrent(
        handle: RoutineResumeHandle.InMemory,
    ): Boolean = inMemoryRoutineHandleIsCurrent(handle)

    internal fun discardInMemoryRoutine(
        handle: RoutineResumeHandle.InMemory,
    ): RoutineResumeDiscardResult = if (inMemoryRoutineHandleIsCurrent(handle)) {
        RoutineResumeDiscardResult.Missing
    } else {
        RoutineResumeDiscardResult.Superseded
    }

    private fun captureRoutineResumeGeneration() = RoutineResumeManagerGeneration(
        configurationInputEpoch = executionGuard.captureConfigurationInputEpoch(),
        recoveryPublicationEpoch = executionGuard.captureRecoveryPublicationEpoch(),
    )

    private fun validatedManualRecoveryCoordinates(
        routine: Routine,
        routineExerciseId: String?,
        exerciseIndex: Int?,
        setIndex: Int?,
    ): RestTransitionPlan.Coordinates? {
        if (routineExerciseId == null || exerciseIndex == null || setIndex == null) return null
        val orderedExercises = routine.getItems().flatMap { item ->
            when (item) {
                is RoutineItem.Single -> listOf(item.exercise)
                is RoutineItem.SupersetItem -> item.superset.exercises
            }
        }
        val exercise = orderedExercises.getOrNull(exerciseIndex) ?: return null
        if (exercise.id != routineExerciseId || setIndex !in exercise.setReps.indices) return null
        return RestTransitionPlan.Coordinates(exerciseIndex, setIndex)
    }

    private fun persistedProgressInfo(
        routine: Routine,
        coordinates: RestTransitionPlan.Coordinates?,
    ): ResumableProgressInfo {
        val exercise = coordinates?.let { coordinate ->
            routine.getItems().flatMap { item ->
                when (item) {
                    is RoutineItem.Single -> listOf(item.exercise)
                    is RoutineItem.SupersetItem -> item.superset.exercises
                }
            }.getOrNull(coordinate.exerciseIndex)
        }
        return ResumableProgressInfo(
            exerciseName = exercise?.exercise?.displayName.orEmpty(),
            currentSet = coordinates?.setIndex?.plus(1) ?: 0,
            totalSets = exercise?.setReps?.size ?: 0,
            currentExercise = coordinates?.exerciseIndex?.plus(1) ?: 0,
            totalExercises = routine.exercises.size,
        )
    }

    internal suspend fun resumeRoutine(
        handle: RoutineResumeHandle.Persisted,
    ): ActiveWorkoutRuntimeResumeResult {
        pendingColdRuntimeCleanupTarget(handle)?.let { target ->
            if (target.reason == RuntimeCleanupReason.DISCARD_RECOVERY) {
                return ActiveWorkoutRuntimeResumeResult.RetryableFailure
            }
        }
        val externalCommandInputStamp = captureExternalCommandInputStamp()
            ?: return ActiveWorkoutRuntimeResumeResult.RetryableFailure
        var leadsAttempt = false
        var supersededPending: PendingRuntimeResume? = null
        val pending = runtimeResumeMutex.withLock {
            pendingRuntimeResume?.takeIf { it.handle == handle } ?: PendingRuntimeResume(
                handle = handle,
                externalCommandInputStamp = externalCommandInputStamp,
                result = CompletableDeferred(),
            ).also {
                supersededPending = pendingRuntimeResume
                pendingRuntimeResume = it
                leadsAttempt = true
            }
        }
        supersededPending?.result?.complete(ActiveWorkoutRuntimeResumeResult.Superseded)
        if (!leadsAttempt) return pending.result.await()
        afterRuntimeResumeSelectionForTest?.invoke(handle)

        try {
            val existingOwner = restTransitionMutex.withLock {
                restoredRuntimeOwner?.takeIf { owner ->
                    owner.handle == handle &&
                        owner.document == activeRuntimeDocument &&
                        coordinator._restTransitionPlan.value == owner.document.restTransitionPlan &&
                        pending.externalCommandInputStamp == owner.externalCommandInputStamp &&
                        hasRestoredOwnerContextAuthority(owner, owner.document)
                }
            }
            val resumed = if (existingOwner != null) {
                ActiveWorkoutRuntimeResumeResult.RestoredRest
            } else {
                resumePersistedRoutine(handle, pending)
            }
            pending.result.complete(resumed)
            return pending.result.await()
        } catch (error: CancellationException) {
            pending.result.completeExceptionally(error)
            throw error
        } catch (error: Throwable) {
            pending.result.completeExceptionally(error)
            throw error
        } finally {
            withContext(NonCancellable) {
                runtimeResumeMutex.withLock {
                    if (pendingRuntimeResume === pending) pendingRuntimeResume = null
                }
            }
        }
    }

    private suspend fun resumePersistedRoutine(
        handle: RoutineResumeHandle.Persisted,
        pending: PendingRuntimeResume,
    ): ActiveWorkoutRuntimeResumeResult {
        if (!runtimeResumeAuthorityIsCurrent(handle, pending)) {
            return ActiveWorkoutRuntimeResumeResult.Superseded
        }

        val loaded = try {
            activeWorkoutRuntimeRepository.load(
                profileId = handle.lookupKey.profileId,
                routineSessionId = handle.lookupKey.routineSessionId,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return ActiveWorkoutRuntimeResumeResult.RetryableFailure
        }
        currentCoroutineContext().ensureActive()
        if (!runtimeResumeAuthorityIsCurrent(handle, pending)) return ActiveWorkoutRuntimeResumeResult.Superseded

        val document = when (loaded) {
            ActiveWorkoutRuntimeLoadResult.Missing -> return ActiveWorkoutRuntimeResumeResult.Missing

            is ActiveWorkoutRuntimeLoadResult.Rejected -> {
                if (loaded.rowRevision != handle.rowRevision) {
                    return ActiveWorkoutRuntimeResumeResult.Superseded
                }
                return deleteInvalidDiscoveredRuntime(handle, pending)
            }

            is ActiveWorkoutRuntimeLoadResult.Loaded -> {
                if (loaded.rowRevision != handle.rowRevision) {
                    return ActiveWorkoutRuntimeResumeResult.Superseded
                }
                pendingTrackedRuntimeCleanupTarget(handle, loaded.document)?.let { target ->
                    val cleanupResult = clearActiveWorkoutRuntime(target)
                    currentCoroutineContext().ensureActive()
                    if (!runtimeResumeAuthorityIsCurrent(handle, pending)) {
                        return ActiveWorkoutRuntimeResumeResult.Superseded
                    }
                    return when (cleanupResult) {
                        RuntimeCleanupResult.CLEARED -> ActiveWorkoutRuntimeResumeResult.Missing

                        RuntimeCleanupResult.RETRYABLE_FAILURE ->
                            ActiveWorkoutRuntimeResumeResult.RetryableFailure

                        RuntimeCleanupResult.SUPERSEDED -> ActiveWorkoutRuntimeResumeResult.Superseded
                    }
                }
                loaded.document
            }
        }

        if (!runtimeResumeAuthorityIsCurrent(handle, pending)) {
            return ActiveWorkoutRuntimeResumeResult.Superseded
        }
        val source = validateRuntimeEnvelopeBeforePreparation(document, handle)
            ?: return deleteInvalidDiscoveredRuntime(handle, pending)
        val preparation = try {
            recoveryPreparationCallsForTest++
            flowDelegate?.prepareRoutineForRecovery(
                routine = handle.selectedRoutine,
                exerciseIndex = document.sourceExerciseIndex,
                setIndex = document.sourceSetIndex,
                launchOrigin = handle.launchOrigin,
                cycleId = handle.cycleId,
                cycleDayNumber = handle.cycleDayNumber,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return ActiveWorkoutRuntimeResumeResult.RetryableFailure
        } ?: return deleteInvalidDiscoveredRuntime(handle, pending)
        currentCoroutineContext().ensureActive()
        if (!runtimeResumeAuthorityIsCurrent(handle, pending)) return ActiveWorkoutRuntimeResumeResult.Superseded

        if (!validateRuntimePreparationStructure(document, preparation)) {
            return deleteInvalidDiscoveredRuntime(handle, pending)
        }

        val plannedSets = try {
            completedSetRepository.getPlannedSets(document.routineExerciseId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return ActiveWorkoutRuntimeResumeResult.RetryableFailure
        }
        currentCoroutineContext().ensureActive()
        if (!runtimeResumeAuthorityIsCurrent(handle, pending)) return ActiveWorkoutRuntimeResumeResult.Superseded

        if (!validateRuntimeHydrationBeforeDurability(document, preparation, source, plannedSets)) {
            return deleteInvalidDiscoveredRuntime(handle, pending)
        }

        val durable = try {
            completedSetRepository.isAttemptDurable(
                stableSessionId = document.sourceStableSessionId,
                key = document.logicalSetKey,
                attemptNumber = document.sourceAttemptNumber,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return ActiveWorkoutRuntimeResumeResult.RetryableFailure
        }
        currentCoroutineContext().ensureActive()
        if (!runtimeResumeAuthorityIsCurrent(handle, pending)) return ActiveWorkoutRuntimeResumeResult.Superseded
        if (!durable) return deleteInvalidDiscoveredRuntime(handle, pending)
        if (!runtimeResumeAuthorityIsCurrent(handle, pending)) return ActiveWorkoutRuntimeResumeResult.Superseded

        val plan = document.restTransitionPlan
            ?: return deleteInvalidDiscoveredRuntime(handle, pending)
        if (!validateRuntimeCurrentAuthority(document, preparation, source) ||
            !planMatchesRestoredDocument(plan, document, source, preparation)
        ) {
            return deleteInvalidDiscoveredRuntime(handle, pending)
        }
        val validation = RuntimeHydrationValidation.Valid(document, preparation, source)

        var restoredOwner: RestoredRuntimeOwner? = null
        var restoredTimerPublication: RestoredRestTimerPublication? = null
        val publicationResult = try {
            restTransitionMutex.withLock {
                val currentRestoredOwner = restoredRuntimeOwner
                if (currentRestoredOwner?.handle == handle &&
                    currentRestoredOwner.document == document &&
                    activeRuntimeDocument == document &&
                    coordinator._restTransitionPlan.value == document.restTransitionPlan &&
                    pending.externalCommandInputStamp == currentRestoredOwner.externalCommandInputStamp &&
                    hasRestoredOwnerContextAuthority(currentRestoredOwner, document)
                ) {
                    return@withLock ActiveWorkoutRuntimeResumeResult.RestoredRest
                }
                if (!runtimeResumeAuthorityIsCurrent(handle, pending)) {
                    return@withLock ActiveWorkoutRuntimeResumeResult.Superseded
                }
                val finalLoad = activeWorkoutRuntimeRepository.load(
                    profileId = handle.lookupKey.profileId,
                    routineSessionId = handle.lookupKey.routineSessionId,
                ) as? ActiveWorkoutRuntimeLoadResult.Loaded
                    ?: return@withLock ActiveWorkoutRuntimeResumeResult.Superseded
                currentCoroutineContext().ensureActive()
                if (!runtimeResumeAuthorityIsCurrent(handle, pending)) {
                    return@withLock ActiveWorkoutRuntimeResumeResult.Superseded
                }
                if (finalLoad.rowRevision != handle.rowRevision || finalLoad.document != document) {
                    return@withLock ActiveWorkoutRuntimeResumeResult.Superseded
                }
                val publicationClaim = executionGuard.beginRecoveryPublication(
                    expectedLease = null,
                    expectedSupersessionEpoch = handle.managerGeneration.recoveryPublicationEpoch,
                    allowNoCurrentAfterOwnedInvalidation = false,
                ) ?: return@withLock ActiveWorkoutRuntimeResumeResult.Superseded
                val guardOwner = executionGuard.commitRestoredRuntimePublication(
                    claim = publicationClaim,
                    seed = document.teardownSeed.toRestoredTeardownSeed(),
                    expectedConfigurationInputEpoch = handle.managerGeneration.configurationInputEpoch,
                ) { owner ->
                    restoredTimerPublication = publishRestoredRuntime(
                        handle = handle,
                        validation = validation,
                        guardOwner = owner,
                        externalCommandInputStamp = pending.externalCommandInputStamp,
                    )
                    restoredOwner = restoredRuntimeOwner
                } ?: return@withLock ActiveWorkoutRuntimeResumeResult.Superseded
                check(restoredOwner?.guardOwner == guardOwner)
                ActiveWorkoutRuntimeResumeResult.RestoredRest
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return ActiveWorkoutRuntimeResumeResult.RetryableFailure
        }

        if (publicationResult is ActiveWorkoutRuntimeResumeResult.RestoredRest) {
            restoredTimerPublication?.jobsToCancel?.forEach { it.cancel() }
            restoredTimerPublication?.jobToStart?.let(::startRestoredRestTimerIfOwned)
            restoredOwner?.takeIf { it.guardOwner.seed.requiresMachine }
                ?.guardOwner
                ?.let(::launchRestoredMachineTeardownReset)
        }
        return publicationResult
    }

    private fun routineResumeHandleProfileIsCurrent(handle: RoutineResumeHandle.Persisted): Boolean {
        val profile = userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready
            ?: return false
        return handle.selectedProfileId == profile.profile.id &&
            handle.lookupKey.profileId == profile.profile.id &&
            handle.selectedRoutine.profileId == profile.profile.id
    }

    private fun routineResumeHandleIsCurrent(handle: RoutineResumeHandle.Persisted): Boolean = routineResumeHandleProfileIsCurrent(handle) &&
        captureRoutineResumeGeneration() == handle.managerGeneration

    private suspend fun runtimeResumeAttemptIsCurrent(pending: PendingRuntimeResume): Boolean = runtimeResumeMutex.withLock { pendingRuntimeResume === pending }

    private suspend fun runtimeResumeAuthorityIsCurrent(
        handle: RoutineResumeHandle.Persisted,
        pending: PendingRuntimeResume,
    ): Boolean = routineResumeHandleIsCurrent(handle) &&
        runtimeResumeAttemptIsCurrent(pending) &&
        captureExternalCommandInputStamp() == pending.externalCommandInputStamp

    private fun pendingColdRuntimeCleanupTarget(
        handle: RoutineResumeHandle.Persisted,
    ): RuntimeCleanupTarget? = pendingRuntimeCleanupRef.value.firstOrNull { target ->
        target.lookupKey == handle.lookupKey &&
            target.initialRowRevision == handle.rowRevision &&
            (target.source as? RuntimeCleanupSource.ColdHandle)?.rowRevision == handle.rowRevision
    }

    private fun pendingTrackedRuntimeCleanupTarget(
        handle: RoutineResumeHandle.Persisted,
        document: ActiveWorkoutRuntimeDocument,
    ): RuntimeCleanupTarget? = pendingRuntimeCleanupRef.value.firstOrNull { target ->
        target.source !is RuntimeCleanupSource.ColdHandle &&
            target.lookupKey == handle.lookupKey &&
            target.expectedDocument == document
    }

    internal suspend fun discardRoutineResume(
        handle: RoutineResumeHandle.Persisted,
    ): RoutineResumeDiscardResult {
        val existingTarget = pendingColdRuntimeCleanupTarget(handle)
        if (existingTarget != null) {
            if (!routineResumeHandleProfileIsCurrent(handle)) return RoutineResumeDiscardResult.Superseded
            return when (clearActiveWorkoutRuntime(existingTarget)) {
                RuntimeCleanupResult.CLEARED -> RoutineResumeDiscardResult.Discarded
                RuntimeCleanupResult.RETRYABLE_FAILURE -> RoutineResumeDiscardResult.RetryableFailure
                RuntimeCleanupResult.SUPERSEDED -> RoutineResumeDiscardResult.Superseded
            }
        }
        if (!routineResumeHandleIsCurrent(handle)) return RoutineResumeDiscardResult.Superseded
        val loaded = try {
            activeWorkoutRuntimeRepository.load(
                profileId = handle.lookupKey.profileId,
                routineSessionId = handle.lookupKey.routineSessionId,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return RoutineResumeDiscardResult.RetryableFailure
        }
        currentCoroutineContext().ensureActive()
        if (!routineResumeHandleIsCurrent(handle)) return RoutineResumeDiscardResult.Superseded
        when (loaded) {
            ActiveWorkoutRuntimeLoadResult.Missing -> return RoutineResumeDiscardResult.Missing

            is ActiveWorkoutRuntimeLoadResult.Loaded ->
                if (loaded.rowRevision != handle.rowRevision) return RoutineResumeDiscardResult.Superseded

            is ActiveWorkoutRuntimeLoadResult.Rejected ->
                if (loaded.rowRevision != handle.rowRevision) return RoutineResumeDiscardResult.Superseded
        }
        if (loaded is ActiveWorkoutRuntimeLoadResult.Loaded) {
            pendingTrackedRuntimeCleanupTarget(handle, loaded.document)?.let { target ->
                return when (clearActiveWorkoutRuntime(target)) {
                    RuntimeCleanupResult.CLEARED -> RoutineResumeDiscardResult.Discarded
                    RuntimeCleanupResult.RETRYABLE_FAILURE -> RoutineResumeDiscardResult.RetryableFailure
                    RuntimeCleanupResult.SUPERSEDED -> RoutineResumeDiscardResult.Superseded
                }
            }
        }
        val candidate = captureColdRuntimeCleanupTarget(
            handle = handle,
            loadResult = loaded,
            reason = RuntimeCleanupReason.DISCARD_RECOVERY,
        )
        val installed = installRuntimeCleanupIntent(candidate)
            ?: pendingRuntimeCleanupRef.value.firstOrNull { target ->
                sameRuntimeCleanupTargetIdentity(target, candidate)
            }
            ?: return RoutineResumeDiscardResult.Superseded
        val supersededPending = runtimeResumeMutex.withLock {
            pendingRuntimeResume.also { pendingRuntimeResume = null }
        }
        supersededPending?.result?.complete(ActiveWorkoutRuntimeResumeResult.Superseded)
        executionGuard.supersedeRecoveryPublication()
        executionGuard.supersedeQueuedSuccessors()
        supersedePendingResetStart()
        return when (clearActiveWorkoutRuntime(installed)) {
            RuntimeCleanupResult.CLEARED -> RoutineResumeDiscardResult.Discarded
            RuntimeCleanupResult.RETRYABLE_FAILURE -> RoutineResumeDiscardResult.RetryableFailure
            RuntimeCleanupResult.SUPERSEDED -> RoutineResumeDiscardResult.Superseded
        }
    }

    private suspend fun deleteInvalidDiscoveredRuntime(
        handle: RoutineResumeHandle.Persisted,
        pending: PendingRuntimeResume,
    ): ActiveWorkoutRuntimeResumeResult = try {
        if (!runtimeResumeAuthorityIsCurrent(handle, pending)) {
            return ActiveWorkoutRuntimeResumeResult.Superseded
        }
        val existingTarget = pendingColdRuntimeCleanupTarget(handle)
        if (existingTarget?.reason == RuntimeCleanupReason.DISCARD_RECOVERY) {
            return ActiveWorkoutRuntimeResumeResult.RetryableFailure
        }
        val target = existingTarget ?: run {
            val current = activeWorkoutRuntimeRepository.load(
                profileId = handle.lookupKey.profileId,
                routineSessionId = handle.lookupKey.routineSessionId,
            )
            currentCoroutineContext().ensureActive()
            if (!runtimeResumeAuthorityIsCurrent(handle, pending)) {
                return ActiveWorkoutRuntimeResumeResult.Superseded
            }
            val currentRevision = when (current) {
                ActiveWorkoutRuntimeLoadResult.Missing -> return ActiveWorkoutRuntimeResumeResult.Missing
                is ActiveWorkoutRuntimeLoadResult.Loaded -> current.rowRevision
                is ActiveWorkoutRuntimeLoadResult.Rejected -> current.rowRevision
            }
            if (currentRevision != handle.rowRevision) {
                return ActiveWorkoutRuntimeResumeResult.Superseded
            }
            val candidate = captureColdRuntimeCleanupTarget(
                handle = handle,
                loadResult = current,
                reason = RuntimeCleanupReason.INVALID_DOCUMENT,
            )
            installRuntimeCleanupIntent(candidate)
                ?: pendingRuntimeCleanupRef.value.firstOrNull { cleanupTarget ->
                    sameRuntimeCleanupTargetIdentity(cleanupTarget, candidate)
                }
                ?: return ActiveWorkoutRuntimeResumeResult.Superseded
        }
        when (clearActiveWorkoutRuntime(target)) {
            RuntimeCleanupResult.CLEARED ->
                handle.manualRecoveryCoordinates?.let { coordinates ->
                    ActiveWorkoutRuntimeResumeResult.ManualSetReady(
                        exerciseIndex = coordinates.exerciseIndex,
                        setIndex = coordinates.setIndex,
                    )
                } ?: ActiveWorkoutRuntimeResumeResult.FreshStart

            RuntimeCleanupResult.RETRYABLE_FAILURE -> ActiveWorkoutRuntimeResumeResult.RetryableFailure

            RuntimeCleanupResult.SUPERSEDED -> ActiveWorkoutRuntimeResumeResult.Superseded
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        ActiveWorkoutRuntimeResumeResult.RetryableFailure
    }

    private fun validateRuntimeHydrationBeforeDurability(
        document: ActiveWorkoutRuntimeDocument,
        preparation: RoutineRecoveryPreparation,
        source: RestoredRetrySourceContext,
        plannedSets: List<com.devil.phoenixproject.domain.model.PlannedSet>,
    ): Boolean {
        val coordinatePlans = plannedSets.filter { it.setNumber == document.sourceSetIndex }
        if (coordinatePlans.size > 1) return false
        val plannedSet = coordinatePlans.singleOrNull()
        val plannedSetMatches = if (document.plannedSetId == null) {
            plannedSet == null &&
                document.logicalSetKey.setKind == preparation.semanticSetType &&
                source.targetReps == preparation.targetReps &&
                source.isAmrap == (preparation.semanticSetType == SetType.AMRAP)
        } else {
            plannedSet?.id == document.plannedSetId &&
                plannedSet.routineExerciseId == document.routineExerciseId &&
                plannedSet.setType == document.logicalSetKey.setKind &&
                plannedSetTargetMatchesSourceCommand(plannedSet, source) &&
                source.targetReps == plannedSet.executionTargetReps(source) &&
                source.isAmrap == (plannedSet.setType == SetType.AMRAP) &&
                sameNullableMachineWeight(plannedSet.targetWeightKg, source.programmedBaseWeightPerCableKg)
        }
        if (!plannedSetMatches) {
            return false
        }

        val plan = document.restTransitionPlan ?: return false
        return planMatchesRestoredDocumentStructure(plan, document, source)
    }

    private fun validateRuntimeEnvelopeBeforePreparation(
        document: ActiveWorkoutRuntimeDocument,
        handle: RoutineResumeHandle.Persisted,
    ): RestoredRetrySourceContext? {
        val source = try {
            document.sourceAuthority.toRestoredRetrySourceContext()
        } catch (_: Exception) {
            return null
        }
        if (handle.lookupKey.profileId != document.profileId ||
            handle.lookupKey.routineSessionId != document.routineSessionId ||
            handle.selectedProfileId != document.profileId ||
            handle.selectedRoutine.id != document.routineId ||
            handle.selectedRoutine.profileId != document.profileId ||
            document.logicalSetKey.routineSessionId != document.routineSessionId ||
            document.logicalSetKey.routineExerciseId != document.routineExerciseId ||
            document.logicalSetKey.setIndex != document.sourceSetIndex ||
            source.routineIdentity != document.sourceAuthority.routineIdentity ||
            source.routineIdentity.profileId != document.profileId ||
            source.routineIdentity.routineId != document.routineId ||
            source.routineIdentity.routineSessionId != document.routineSessionId ||
            source.routineIdentity.routineExerciseId != document.routineExerciseId ||
            source.routineIdentity.logicalSetKey != document.logicalSetKey ||
            source.routineIdentity.plannedSetId != document.plannedSetId ||
            source.routineIdentity.exerciseIndex != document.sourceExerciseIndex ||
            source.routineIdentity.setIndex != document.sourceSetIndex ||
            source.sourceStableSessionId != document.sourceStableSessionId ||
            source.sourceExecutionId != document.sourceExecutionId ||
            source.attemptNumber != document.sourceAttemptNumber ||
            source.plannedSetType != document.logicalSetKey.setKind ||
            document.teardownSeed.sourceExecutionId.toString() != document.sourceExecutionId ||
            document.teardownSeed.sourceStableSessionId != document.sourceStableSessionId ||
            document.teardownSeed.profileId != document.profileId ||
            document.teardownSeed.requiresMachine != source.isCableExercise ||
            source.isCableExercise == source.isBodyweight
        ) {
            return null
        }
        return source
    }

    private fun validateRuntimePreparationStructure(
        document: ActiveWorkoutRuntimeDocument,
        preparation: RoutineRecoveryPreparation,
    ): Boolean {
        val routine = preparation.resolvedRoutine
        val exercise = preparation.sourceExercise
        if (routine.id != document.routineId ||
            routine.profileId != document.profileId ||
            preparation.sourceExerciseIndex != document.sourceExerciseIndex ||
            preparation.sourceSetIndex != document.sourceSetIndex ||
            routine.exercises.getOrNull(document.sourceExerciseIndex) !== exercise ||
            exercise.id != document.routineExerciseId ||
            document.sourceSetIndex !in exercise.setReps.indices
        ) {
            return false
        }

        if (document.exerciseLoadOverlays.groupingBy { it.routineExerciseId }.eachCount().values.any { it != 1 } ||
            document.attemptStates.groupingBy { it.logicalSetKey }.eachCount().values.any { it != 1 } ||
            document.exerciseLoadOverlays.any { overlay -> routine.exercises.count { it.id == overlay.routineExerciseId } != 1 } ||
            document.attemptStates.any { state ->
                val stateExercise = routine.exercises.singleOrNull {
                    it.id == state.logicalSetKey.routineExerciseId
                }
                state.logicalSetKey.routineSessionId != document.routineSessionId ||
                    stateExercise == null ||
                    state.logicalSetKey.setIndex !in stateExercise.setReps.indices ||
                    (
                        state.logicalSetKey != document.logicalSetKey &&
                            semanticSetType(stateExercise, state.logicalSetKey.setIndex) != state.logicalSetKey.setKind
                        )
            }
        ) {
            return false
        }
        return true
    }

    private fun validateRuntimeCurrentAuthority(
        document: ActiveWorkoutRuntimeDocument,
        preparation: RoutineRecoveryPreparation,
        source: RestoredRetrySourceContext,
    ): Boolean {
        val exercise = preparation.sourceExercise
        return source.isCableExercise != source.isBodyweight &&
            source.programMode == exercise.programMode &&
            sameMachineWeight(source.programmedBaseWeightPerCableKg, preparation.programmedBaseWeightPerCableKg) &&
            !source.isWarmup &&
            !source.isJustLift &&
            source.isEcho == (exercise.programMode == ProgramMode.Echo) &&
            source.isTimed == (exercise.duration?.takeIf { it > 0 } != null) &&
            source.isBodyweight == exercise.exercise.isBodyweight &&
            source.isCableExercise == !exercise.exercise.isBodyweight &&
            source.physicalCableCount == exercise.exercise.preferredCableCount &&
            sourceCommandMatchesPreparation(source, preparation) &&
            document.restTransitionPlan != null
    }

    private fun plannedSetTargetMatchesSourceCommand(
        plannedSet: com.devil.phoenixproject.domain.model.PlannedSet,
        source: RestoredRetrySourceContext,
    ): Boolean = when (plannedSet.setType) {
        SetType.AMRAP -> plannedSet.targetReps == null
        else -> plannedSet.targetReps == source.commandTemplate.reps
    }

    private fun com.devil.phoenixproject.domain.model.PlannedSet.executionTargetReps(
        source: RestoredRetrySourceContext,
    ): Int? = targetReps.takeUnless {
        setType == SetType.AMRAP || source.isTimed || source.isBodyweight
    }

    private fun sourceCommandMatchesPreparation(
        source: RestoredRetrySourceContext,
        preparation: RoutineRecoveryPreparation,
    ): Boolean {
        val exercise = preparation.sourceExercise
        val template = source.commandTemplate
        val expectedAmrap = preparation.semanticSetType == SetType.AMRAP
        val expectedReps = exercise.setReps.getOrNull(preparation.sourceSetIndex) ?: exercise.reps
        val expectedWarmupReps = if (source.isBodyweight) 0 else Constants.DEFAULT_WARMUP_REPS
        return template.programMode == exercise.programMode &&
            template.reps == expectedReps &&
            template.echoLevel == exercise.getEchoLevelForSet(preparation.sourceSetIndex) &&
            template.eccentricLoad == exercise.eccentricLoad &&
            sameMachineWeight(template.progressionRegressionKg, exercise.progressionKg) &&
            template.selectedExerciseId == exercise.exercise.id &&
            template.stopAtTop == exercise.stopAtTop &&
            template.stallDetectionEnabled == exercise.stallDetectionEnabled &&
            template.repCountTiming == exercise.repCountTiming &&
            template.isAMRAP == expectedAmrap &&
            !template.isJustLift &&
            !template.useAutoStart &&
            template.warmupReps == expectedWarmupReps &&
            template.activeRackItemIds == preparation.rackSelection.itemIds &&
            sameMachineWeight(template.externalAddedLoadKg, preparation.rackSelection.adjustment.externalAddedLoadKg) &&
            sameMachineWeight(template.counterweightKg, preparation.rackSelection.adjustment.counterweightKg) &&
            sameMachineWeight(template.weightPerCableKg, source.configuredStartWeightPerCableKg) &&
            sameMachineWeight(source.progressionKg, template.progressionRegressionKg)
    }

    private fun planMatchesRestoredDocument(
        plan: RestTransitionPlan,
        document: ActiveWorkoutRuntimeDocument,
        source: RestoredRetrySourceContext,
        preparation: RoutineRecoveryPreparation,
    ): Boolean {
        if (!planMatchesRestoredDocumentStructure(plan, document, source)) {
            return false
        }
        return when (plan) {
            is RestTransitionPlan.NormalAdvance,
            is RestTransitionPlan.Declined,
            -> true

            is RestTransitionPlan.UnresolvedDropOffer ->
                unresolvedPlanMatchesCurrentEligibilityPolicy(plan, document, source, preparation)

            is RestTransitionPlan.AcceptedRetry -> retryCandidateMatchesCurrentPolicy(
                exercise = preparation.sourceExercise,
                percentage = plan.percentage,
                sourceConfiguredStartWeightPerCableKg = source.configuredStartWeightPerCableKg,
                programmedBaseWeightPerCableKg = source.programmedBaseWeightPerCableKg,
                sourceCommandTemplate = source.commandTemplate,
                expectedWeightPerCableKg = plan.resolvedWeightPerCableKg,
                expectedMultiplier = plan.resultingExerciseMultiplier,
            )
        }
    }

    private fun planMatchesRestoredDocumentStructure(
        plan: RestTransitionPlan,
        document: ActiveWorkoutRuntimeDocument,
        source: RestoredRetrySourceContext,
    ): Boolean {
        if (plan.sourceExecutionId != document.sourceExecutionId ||
            plan.logicalSetKey != document.logicalSetKey ||
            plan.actionIdentity().plannedSetId != document.plannedSetId
        ) {
            return false
        }
        val coordinates = RestTransitionPlan.Coordinates(document.sourceExerciseIndex, document.sourceSetIndex)
        val sourceState = document.attemptStates.singleOrNull { it.logicalSetKey == document.logicalSetKey }
            ?: return false
        return when (plan) {
            is RestTransitionPlan.NormalAdvance ->
                plan.sourceCoordinates == coordinates &&
                    plan.restDurationSeconds == document.originalRestDurationSeconds &&
                    sourceState.nextAttemptNumber == source.attemptNumber + 1 &&
                    sourceState.acceptedDropCount == source.acceptedDropCount

            is RestTransitionPlan.UnresolvedDropOffer ->
                plan.normalAdvance.sourceCoordinates == coordinates &&
                    plan.normalAdvance.restDurationSeconds == document.originalRestDurationSeconds &&
                    sourceState.nextAttemptNumber == source.attemptNumber + 1 &&
                    sourceState.acceptedDropCount == source.acceptedDropCount

            is RestTransitionPlan.Declined ->
                source.reason == SetEndReason.STALL_FAILURE &&
                    plan.normalAdvance.sourceCoordinates == coordinates &&
                    plan.normalAdvance.restDurationSeconds == document.originalRestDurationSeconds &&
                    sourceState.nextAttemptNumber == source.attemptNumber + 1 &&
                    sourceState.acceptedDropCount == source.acceptedDropCount

            is RestTransitionPlan.AcceptedRetry -> {
                val sourceOverlay = document.exerciseLoadOverlays.singleOrNull {
                    it.routineExerciseId == document.routineExerciseId
                }
                source.reason == SetEndReason.STALL_FAILURE &&
                    plan.sourceCoordinates == coordinates &&
                    plan.nextAttemptNumber == source.attemptNumber + 1 &&
                    sourceState.nextAttemptNumber == plan.nextAttemptNumber + 1 &&
                    sourceState.acceptedDropCount == source.acceptedDropCount + 1 &&
                    sourceState.acceptedDropCount in 1..2 &&
                    sourceOverlay != null &&
                    sameMachineWeight(sourceOverlay.multiplier, plan.resultingExerciseMultiplier)
            }
        }
    }

    private fun unresolvedPlanMatchesCurrentEligibilityPolicy(
        plan: RestTransitionPlan.UnresolvedDropOffer,
        document: ActiveWorkoutRuntimeDocument,
        source: RestoredRetrySourceContext,
        preparation: RoutineRecoveryPreparation,
    ): Boolean = try {
        val completion = SetExecutionCompletion(
            lease = ExecutionLease(
                executionId = document.teardownSeed.sourceExecutionId,
                sessionId = source.sourceStableSessionId,
                profileId = source.profileId,
                requiresMachine = document.teardownSeed.requiresMachine,
                workingRepTarget = if (source.isBodyweight || (source.isTimed && source.isCableExercise)) {
                    0
                } else {
                    source.commandTemplate.reps
                },
                isBodyweight = source.isBodyweight,
                isJustLift = source.isJustLift,
                isAmrap = source.isAmrap,
                isTimedCable = source.isTimed && source.isCableExercise,
            ),
            reason = source.reason,
            routineIdentity = source.routineIdentity,
            attemptNumber = source.attemptNumber,
            acceptedDropCount = source.acceptedDropCount,
            plannedSetType = source.plannedSetType,
            programMode = source.programMode,
            programmedBaseWeightPerCableKg = source.programmedBaseWeightPerCableKg,
            configuredStartWeightPerCableKg = source.configuredStartWeightPerCableKg,
            progressionKg = source.progressionKg,
            actualReps = source.actualReps,
            targetReps = source.targetReps,
            isWarmup = source.isWarmup,
            isEcho = source.isEcho,
            isJustLift = source.isJustLift,
            isBodyweight = source.isBodyweight,
            isTimed = source.isTimed,
            isAmrap = source.isAmrap,
            isCableExercise = source.isCableExercise,
            physicalCableCount = source.physicalCableCount,
            logicalPreRackCommandTemplate = source.commandTemplate,
        )
        val eligibility = dropSetEligibilityPolicy.evaluate(
            DropSetEligibilityRequest(
                offerId = plan.offerId,
                completion = completion,
                configuration = dropSetConfigurationProvider(preparation.sourceExercise),
                expectedLiveIdentity = source.routineIdentity,
                commandTemplate = source.commandTemplate,
            ),
        )
        buildRestTransitionPlan(plan.normalAdvance, eligibility) == plan
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }

    private fun publishRestoredRuntime(
        handle: RoutineResumeHandle.Persisted,
        validation: RuntimeHydrationValidation.Valid,
        guardOwner: RestoredRuntimeOwnerToken,
        externalCommandInputStamp: ExternalCommandInputStamp,
    ): RestoredRestTimerPublication {
        beforeRestoredRuntimeOwnerPublicationForTest?.invoke()
        val document = validation.document
        val preparation = validation.preparation
        val source = validation.sourceContext
        val remainingSeconds = RestDeadlineCalculator.remainingSeconds(document, wallClockMillisProvider())
        val plan = requireNotNull(document.restTransitionPlan)
        val monotonicDeadline = if (!document.isRestPaused && remainingSeconds > 0) {
            saturatingAddMilliseconds(
                baseMs = elapsedRealtimeProvider(),
                durationMs = remainingSeconds.toLong() * 1_000L,
            )
        } else {
            null
        }
        val timerJob = monotonicDeadline?.let {
            createRestoredRestTimerJob(
                guardOwner = guardOwner,
                transitionId = plan.transitionId,
                sourceExecutionId = plan.sourceExecutionId,
            )
        }

        val legacyBodyweightTimerJob = coordinator.bodyweightTimerJob
        coordinator.bodyweightTimerJob = null
        coordinator._skippedExercises.value = emptySet()
        coordinator._completedExercises.value = emptySet()
        coordinator._completedRoutineSetKeys.value = emptySet()
        coordinator._weightAdjustmentRecommendation.value = null
        coordinator._sessionBodyweightState.value = SessionBodyweightState(
            routineHasBodyweight = preparation.resolvedRoutine.exercises.any { it.exercise.isBodyweight },
        )
        coordinator.bodyweightSetsCompletedInRoutine = 0
        coordinator._selectedBodyweightVariants.value = emptyMap()
        coordinator.bodyweightCompletionVariantOverride = null
        coordinator.previousExerciseWasBodyweight = false
        coordinator.routineStartTime = 0L
        coordinator.routineAccumulatedCalories = 0f
        coordinator.currentSessionId = null
        coordinator.workoutStartTime = 0L
        coordinator.warmupCompleteTimeMs = 0L
        coordinator.collectedMetrics.value = emptyList()
        coordinator.setRepMetrics.value = emptyList()
        coordinator._currentSetRpe.value = null
        coordinator._userAdjustedWeightDuringRest = false
        coordinator.pendingWeightChangeKg = null

        coordinator._loadedRoutine.value = preparation.resolvedRoutine
        coordinator._currentExerciseIndex.value = document.sourceExerciseIndex
        coordinator._currentSetIndex.value = document.sourceSetIndex
        coordinator._currentWarmupSetIndex.value = -1
        coordinator.currentRoutineId = document.routineId
        coordinator.currentRoutineName = preparation.resolvedRoutine.name
        coordinator.currentRoutineSessionId = document.routineSessionId
        coordinator.routineLaunchOrigin = preparation.launchOrigin
        coordinator.activeCycleId = preparation.cycleId
        coordinator.activeCycleDayNumber = preparation.cycleDayNumber
        coordinator._workoutParameters.value = source.commandTemplate
        coordinator.setActiveRackSelection(
            itemIds = preparation.rackSelection.itemIds,
            precomputedAdjustment = preparation.rackSelection.adjustment,
            precomputedItemsJson = preparation.rackSelection.itemsJson,
        )
        coordinator._activeRackBehaviorOverrides.value = preparation.rackSelection.behaviorOverrides
        setActiveRuntimeDocument(document)
        coordinator._restTransitionPlan.value = document.restTransitionPlan
        acceptedRetryPermission = null
        persistedRestTimerOwner = null
        val legacyTimerJob = coordinator.restTimerJob
        coordinator.restTimerJob = null
        coordinator.restDeadlineElapsedRealtimeMs = null
        coordinator._isRestPaused.value = document.isRestPaused
        coordinator._restOriginalDuration.value = document.originalRestDurationSeconds
        coordinator._restSecondsRemaining.value = remainingSeconds
        restoredRuntimeOwner = RestoredRuntimeOwner(
            handle = handle,
            document = document,
            documentVersion = activeRuntimeDocumentVersion,
            guardOwner = guardOwner,
            sourceContext = source,
            rackBehaviorOverrides = preparation.rackSelection.behaviorOverrides.toMap(),
            externalCommandInputStamp = externalCommandInputStamp,
        )
        val replacedRestoredTimer = replaceRestoredRestTimerOwner(
            RestoredRestTimerOwner(
                guardOwner = guardOwner,
                document = document,
                documentVersion = activeRuntimeDocumentVersion,
                transitionId = plan.transitionId,
                sourceExecutionId = plan.sourceExecutionId,
                monotonicDeadlineElapsedRealtimeMs = monotonicDeadline,
                job = timerJob,
            ),
        )
        restoredTeardownRetryOwner = guardOwner
        coordinator._workoutState.value = WorkoutState.Resting(
            restSecondsRemaining = remainingSeconds,
            nextExerciseName = preparation.sourceExercise.exercise.displayName,
            isLastExercise = false,
            currentSet = document.sourceSetIndex + 1,
            totalSets = preparation.sourceExercise.setReps.size,
        )
        beforeRestoredRoutineFlowPublicationForTest?.invoke()
        coordinator._routineFlowState.value = RoutineFlowState.SetReady(
            exerciseIndex = document.sourceExerciseIndex,
            setIndex = document.sourceSetIndex,
            adjustedWeight = source.commandTemplate.weightPerCableKg,
            adjustedReps = source.commandTemplate.reps,
            adjustedProgressionKg = source.commandTemplate.progressionRegressionKg,
            echoLevel = source.commandTemplate.echoLevel,
            eccentricLoadPercent = source.commandTemplate.eccentricLoad.percentage,
        )
        return RestoredRestTimerPublication(
            jobToStart = timerJob,
            jobsToCancel = listOfNotNull(
                legacyTimerJob,
                legacyBodyweightTimerJob,
                replacedRestoredTimer?.job,
            )
                .distinct(),
        )
    }

    private fun RestoredTeardownSeedSnapshot.toRestoredTeardownSeed() = RestoredTeardownSeed(
        sourceExecutionId = sourceExecutionId,
        sourceStableSessionId = sourceStableSessionId,
        profileId = profileId,
        requiresMachine = requiresMachine,
    )

    private val rackJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val connectionLogRepository = ConnectionLogRepository.instance
    internal val executionGuard = WorkoutExecutionGuard(::logExecutionEvent)
    private var executionContext: WorkoutExecutionContext? = null
    private val bodyweightCompletionGate = BodyweightCompletionGate()
    private val biomechanicsContext = atomic<ExecutionBiomechanicsContext?>(null)
    private val pendingTeardownReadyContinuation = atomic<PendingTeardownReadyContinuation?>(null)
    private val deferredMachineConfigurationTeardown = atomic<DeferredMachineConfigurationTeardown?>(null)
    private val resetMachineTeardownOwnerSequence = atomic(0L)
    private val resetMachineTeardownOwner = atomic<ResetMachineTeardownOwner?>(null)
    private val pendingResetStart = atomic<PendingResetStart?>(null)
    private val exitSnapshotStore = WorkoutExitSnapshotStore()
    private val restTransitionMutex = Mutex()
    private val runtimeResumeMutex = Mutex()
    private var pendingRuntimeResume: PendingRuntimeResume? = null
    private val pendingRuntimeCleanupRef = atomic<List<RuntimeCleanupTarget>>(emptyList())
    private val stopWorkoutCleanupTargetRef = atomic<RuntimeCleanupTarget?>(null)
    private val runtimeCleanupCandidateRef = atomic<RuntimeCleanupCandidate?>(null)
    private val pendingInitialRuntimeCandidateSequence = atomic(0L)
    private val pendingRuntimeReplaceCandidateRef = atomic<PendingRuntimeReplaceCandidate?>(null)
    private val pendingRuntimeReplaceCandidateSequence = atomic(0L)
    private val restoredRuntimeOwnerRef = atomic<RestoredRuntimeOwner?>(null)
    private var restoredRuntimeOwner: RestoredRuntimeOwner?
        get() = restoredRuntimeOwnerRef.value
        set(value) {
            restoredRuntimeOwnerRef.value = value
        }
    private val restoredTeardownRetryOwnerRef = atomic<RestoredRuntimeOwnerToken?>(null)
    private var restoredTeardownRetryOwner: RestoredRuntimeOwnerToken?
        get() = restoredTeardownRetryOwnerRef.value
        set(value) {
            restoredTeardownRetryOwnerRef.value = value
        }
    private var activeRuntimeDocument: ActiveWorkoutRuntimeDocument? = null
    private var activeRuntimeDocumentVersion: Long = 0L
    private var pendingInitialRestRuntimeDocument: ActiveWorkoutRuntimeDocument? = null
    private data class CachedTransitionNavigation(
        val transitionId: String,
        val sourceExecutionId: String,
        val nextStep: Pair<Int, Int>?,
        val cycleId: String?,
        val cycleDayNumber: Int?,
    )
    private data class PendingTransitionNavigation(
        val transitionId: String,
        val sourceExecutionId: String,
        val result: CompletableDeferred<CachedTransitionNavigation?>,
    )
    private data class TransitionNavigationResolutionContext(
        val routine: Routine,
        val exerciseIndex: Int,
        val setIndex: Int,
        val cycleId: String?,
        val cycleDayNumber: Int?,
    )
    private var cachedTransitionNavigation: CachedTransitionNavigation? = null
    private var pendingTransitionNavigation: PendingTransitionNavigation? = null
    private var persistedRestTimerOwner: PersistedRestTimerOwner? = null
    private val restoredRestTimerOwnerRef = atomic<RestoredRestTimerOwner?>(null)
    private val acceptedRetryPermissionRef = atomic<AcceptedRetryPermission?>(null)
    private var acceptedRetryPermission: AcceptedRetryPermission?
        get() = acceptedRetryPermissionRef.value
        set(value) {
            acceptedRetryPermissionRef.value = value
        }
    private val acceptedRetryStartClaim = atomic<AcceptedRetryStartClaim?>(null)
    private val dropSetCandidateResolver = DropSetCandidateResolver()

    /**
     * Apply a UI rest action only after verifying the current lease, full routine
     * identity, and durable runtime envelope.  Accepted retries deliberately stop
     * here: this task records the decision; a later orchestration task owns retry
     * activation and machine configuration.
     */
    internal fun applyRestTransition(command: RestTransitionCommand) {
        scope.launch {
            applyRestTransitionAwait(command)
        }
    }

    /** Structured command seam for callers that need the reducer/persistence outcome. */
    internal suspend fun applyRestTransitionAwait(command: RestTransitionCommand): RestTransitionReduction = applyRestTransitionAwait(command, timerAuthority = null).reduction

    private suspend fun applyRestTransitionAwait(
        command: RestTransitionCommand,
        timerAuthority: PersistedRestTimerActionAuthority?,
    ): RestTransitionApplicationResult {
        val recoveryPublicationEpoch = executionGuard.captureRecoveryPublicationEpoch()
        var acceptedRetryToFailClosed: Triple<RestTransitionPlan.AcceptedRetry, Long, RetryFailClosedAuthority>? = null
        var restoredActionApplied = false
        var restoredNormalDispatch: RestoredNormalDispatch? = null
        var restoredAcceptedOwner: RestoredRuntimeOwner? = null
        val reduction = restTransitionMutex.withLock {
            val lease = executionGuard.currentLease
            if (lease == null) {
                if (timerAuthority != null) {
                    return@withLock RestTransitionReduction.NoOp(RestTransitionNoOpReason.AUTHORITY_CHANGED)
                }
                val restored = applyRestoredActionLocked(command)
                restoredActionApplied = restored.reduction is RestTransitionReduction.Changed ||
                    restored.normalDispatch != null ||
                    restored.acceptedOwner != null
                restoredNormalDispatch = restored.normalDispatch
                restoredAcceptedOwner = restored.acceptedOwner
                return@withLock restored.reduction
            }
            val plan = coordinator._restTransitionPlan.value
                ?: return@withLock RestTransitionReduction.NoOp(RestTransitionNoOpReason.NO_CURRENT_PLAN)
            val document = activeRuntimeDocument
                ?: return@withLock RestTransitionReduction.NoOp(RestTransitionNoOpReason.AUTHORITY_CHANGED)
            if (timerAuthority != null &&
                !hasPersistedRestTimerActionAuthorityLocked(timerAuthority, document, plan, lease)
            ) {
                return@withLock RestTransitionReduction.NoOp(RestTransitionNoOpReason.AUTHORITY_CHANGED)
            }
            if (!hasRestTransitionAuthority(document, plan, lease)) {
                if (plan is RestTransitionPlan.AcceptedRetry &&
                    command is RestTransitionCommand.SkipRest &&
                    command.identity == plan.actionIdentity() &&
                    leaseMatchesRetrySource(lease, document) &&
                    !liveRestTransitionIsTerminallySuperseded(document) &&
                    liveRestTransitionProfileIsCurrent(document)
                ) {
                    acceptedRetryToFailClosed = Triple(
                        plan,
                        activeRuntimeDocumentVersion,
                        RetryFailClosedAuthority.Live(lease, recoveryPublicationEpoch),
                    )
                }
                return@withLock RestTransitionReduction.NoOp(RestTransitionNoOpReason.LIVE_IDENTITY_MISMATCH)
            }
            if (command is RestTransitionCommand.Accept &&
                (
                    document.attemptStates.groupingBy { it.logicalSetKey }.eachCount().values.any { it != 1 } ||
                        document.exerciseLoadOverlays.groupingBy { it.routineExerciseId }.eachCount().values.any { it != 1 }
                    )
            ) {
                return@withLock RestTransitionReduction.NoOp(RestTransitionNoOpReason.AUTHORITY_CHANGED)
            }

            val reduced = reduceRestTransition(
                RestTransitionReducerState(
                    plan = plan,
                    currentSourceExecutionId = lease.executionId.toString(),
                    attemptStates = document.attemptStates,
                ),
                command,
            )
            if (reduced is RestTransitionReduction.PendingAcceptedRetry) {
                acceptedRetryPermission = AcceptedRetryPermission(
                    transitionId = plan.transitionId,
                    sourceExecutionId = plan.sourceExecutionId,
                    documentVersion = activeRuntimeDocumentVersion,
                    source = timerAuthority?.let {
                        AcceptedRetryPermissionSource.Timer(
                            ownerJob = it.timerJob,
                            deadlineEpochMs = it.deadlineEpochMs,
                        )
                    } ?: AcceptedRetryPermissionSource.Manual,
                )
                return@withLock reduced
            }
            val changed = reduced as? RestTransitionReduction.Changed ?: return@withLock reduced
            val acceptedOverlay = (changed.plan as? RestTransitionPlan.AcceptedRetry)?.let {
                ExerciseLoadOverlay(
                    routineExerciseId = document.routineExerciseId,
                    multiplier = it.resultingExerciseMultiplier,
                )
            }
            val updatedDocument = document.copy(
                restTransitionPlan = changed.plan,
                attemptStates = canonicalAttemptStates(changed.attemptStates ?: document.attemptStates),
                exerciseLoadOverlays = acceptedOverlay?.let { overlay ->
                    canonicalOverlays(
                        document.exerciseLoadOverlays.filterNot {
                            it.routineExerciseId == overlay.routineExerciseId
                        } + overlay,
                    )
                } ?: canonicalOverlays(document.exerciseLoadOverlays),
            )
            if (!replaceRuntimeDocument(updatedDocument)) {
                return@withLock RestTransitionReduction.NoOp(RestTransitionNoOpReason.PERSISTENCE_FAILURE)
            }
            if (!hasRestTransitionAuthority(updatedDocument, changed.plan, lease)) {
                return@withLock RestTransitionReduction.NoOp(RestTransitionNoOpReason.AUTHORITY_CHANGED)
            }
            setActiveRuntimeDocument(updatedDocument)
            coordinator._restTransitionPlan.value = changed.plan
            acceptedRetryPermission = if (
                changed.plan is RestTransitionPlan.AcceptedRetry &&
                settingsManager.autoplayEnabled.value &&
                !updatedDocument.isRestPaused &&
                RestDeadlineCalculator.remainingSeconds(updatedDocument, wallClockMillisProvider()) <= 0
            ) {
                persistedRestTimerOwner?.takeIf { owner ->
                    owner.transitionId == changed.plan.transitionId &&
                        owner.sourceExecutionId == changed.plan.sourceExecutionId &&
                        owner.job.isActive &&
                        coordinator.restTimerJob === owner.job
                }?.let { owner ->
                    AcceptedRetryPermission(
                        transitionId = changed.plan.transitionId,
                        sourceExecutionId = changed.plan.sourceExecutionId,
                        documentVersion = activeRuntimeDocumentVersion,
                        source = AcceptedRetryPermissionSource.Timer(
                            ownerJob = owner.job,
                            deadlineEpochMs = updatedDocument.restDeadlineEpochMs,
                        ),
                    )
                }
            } else {
                null
            }
            changed
        }
        acceptedRetryToFailClosed?.let { (plan, documentVersion, authority) ->
            failAcceptedRetryClosed(plan, documentVersion, authority)
        }

        if (reduction is RestTransitionReduction.PendingAcceptedRetry) {
            beforeAcceptedRetryGateCaptureForTest?.invoke()
        }
        val acceptedRetryStarted = restoredAcceptedOwner?.let { owner ->
            tryStartCurrentAcceptedRetryRestored(owner.guardOwner)
        } ?: if (restoredActionApplied) {
            false
        } else {
            when (reduction) {
                is RestTransitionReduction.PendingAcceptedRetry,
                is RestTransitionReduction.Changed,
                -> tryStartCurrentAcceptedRetryLive()

                else -> false
            }
        }

        val restoredNormalConsumed = restoredNormalDispatch?.let { dispatch ->
            beforeRestoredNormalDispatchForTest?.invoke()
            dispatchRestoredNormalAdvance(dispatch)
        } ?: false
        val normalTransitionConsumed = when (reduction) {
            // A decline closes only the offer.  The captured normal transition stays
            // in rest until its timer or an identity-bearing Skip Rest dispatches it.
            is RestTransitionReduction.Changed -> {
                if (!restoredActionApplied) {
                    (reduction.plan as? RestTransitionPlan.Declined)?.let { declined ->
                        resolveNavigationOnce(declined.normalAdvance)
                    }
                }
                false
            }

            is RestTransitionReduction.DispatchNormal -> if (restoredNormalDispatch != null) {
                restoredNormalConsumed
            } else if (timerAuthority == null) {
                consumeNormalTransitionAndDispatch(reduction.plan)
                false
            } else {
                consumeNormalTransitionAndDispatchAwait(reduction.plan, timerAuthority)
            }

            is RestTransitionReduction.NoOp -> if (reduction.reason == RestTransitionNoOpReason.PERSISTENCE_FAILURE) {
                coordinator._userFeedbackEvents.emit("Unable to update rest transition. Please try again.")
                false
            } else {
                false
            }

            is RestTransitionReduction.PendingAcceptedRetry -> acceptedRetryStarted
        }
        return RestTransitionApplicationResult(
            reduction = reduction,
            normalTransitionConsumed = normalTransitionConsumed,
        )
    }

    private suspend fun applyRestoredActionLocked(
        command: RestTransitionCommand,
    ): RestoredActionApplication {
        val owner = restoredRuntimeOwner
            ?: return RestoredActionApplication(RestTransitionReduction.NoOp(RestTransitionNoOpReason.AUTHORITY_CHANGED))
        val document = activeRuntimeDocument
            ?: return RestoredActionApplication(
                RestTransitionReduction.NoOp(RestTransitionNoOpReason.AUTHORITY_CHANGED),
            ).also {
                revokeRestoredActionAuthorityLocked(owner.guardOwner)
            }
        val plan = coordinator._restTransitionPlan.value
            ?: return RestoredActionApplication(RestTransitionReduction.NoOp(RestTransitionNoOpReason.NO_CURRENT_PLAN))
        if (!hasRestoredRestTransitionAuthority(owner, document, plan)) {
            afterRestoredActionProfileAuthorityFailureForTest?.invoke()
            val retainAuthority = hasRestoredRestTransitionAuthorityIgnoringReadyProfile(owner, document, plan) &&
                restoredOwnerContextCanBeRetainedAfterAuthorityFailure(owner, document)
            if (!retainAuthority) {
                revokeRestoredActionAuthorityLocked(owner.guardOwner)
            }
            return RestoredActionApplication(RestTransitionReduction.NoOp(RestTransitionNoOpReason.AUTHORITY_CHANGED))
        }
        if (command is RestTransitionCommand.Accept &&
            (
                document.attemptStates.groupingBy { it.logicalSetKey }.eachCount().values.any { it != 1 } ||
                    document.exerciseLoadOverlays.groupingBy { it.routineExerciseId }.eachCount().values.any { it != 1 }
                )
        ) {
            return RestoredActionApplication(RestTransitionReduction.NoOp(RestTransitionNoOpReason.AUTHORITY_CHANGED))
        }

        val reduced = reduceRestTransition(
            RestTransitionReducerState(
                plan = plan,
                currentSourceExecutionId = document.sourceExecutionId,
                attemptStates = document.attemptStates,
            ),
            command,
        )
        if (reduced is RestTransitionReduction.PendingAcceptedRetry) {
            acceptedRetryPermission = AcceptedRetryPermission(
                transitionId = plan.transitionId,
                sourceExecutionId = plan.sourceExecutionId,
                documentVersion = activeRuntimeDocumentVersion,
                source = AcceptedRetryPermissionSource.Restored(owner.guardOwner),
            )
            return RestoredActionApplication(
                reduction = reduced,
                acceptedOwner = owner,
            )
        }
        if (reduced is RestTransitionReduction.DispatchNormal) {
            val routine = coordinator._loadedRoutine.value
                ?: return RestoredActionApplication(
                    RestTransitionReduction.NoOp(RestTransitionNoOpReason.AUTHORITY_CHANGED),
                ).also {
                    revokeRestoredActionAuthorityLocked(owner.guardOwner)
                }
            val clearedDocument = document.copy(restTransitionPlan = null)
            val priorDocumentVersion = activeRuntimeDocumentVersion
            try {
                if (!replaceRuntimeDocument(clearedDocument)) {
                    return RestoredActionApplication(RestTransitionReduction.NoOp(RestTransitionNoOpReason.PERSISTENCE_FAILURE))
                }
            } catch (error: CancellationException) {
                when (runtimeDocumentCommitStatus(clearedDocument, document)) {
                    RuntimeDocumentCommitStatus.COMMITTED -> try {
                        withContext(NonCancellable) {
                            val reconciledOwner = reconcileRestoredDocumentCommitLocked(
                                owner = owner,
                                priorDocument = document,
                                priorDocumentVersion = priorDocumentVersion,
                                priorPlan = plan,
                                committedDocument = clearedDocument,
                            )
                            if (reconciledOwner != null) {
                                dispatchRestoredNormalAdvanceLocked(
                                    RestoredNormalDispatch(
                                        owner = reconciledOwner,
                                        plan = reduced.plan,
                                        routine = routine,
                                        exerciseIndex = document.sourceExerciseIndex,
                                        setIndex = document.sourceSetIndex,
                                    ),
                                )
                            }
                        }
                    } catch (_: Throwable) {
                        retireRestoredActionAuthorityPreservingCancellation(owner.guardOwner)
                    }

                    RuntimeDocumentCommitStatus.UNCHANGED_PRIOR -> Unit

                    RuntimeDocumentCommitStatus.DIVERGED,
                    RuntimeDocumentCommitStatus.UNKNOWN,
                    ->
                        retireRestoredActionAuthorityPreservingCancellation(owner.guardOwner)
                }
                throw error
            }
            val updatedOwner = reconcileRestoredDocumentCommitLocked(
                owner = owner,
                priorDocument = document,
                priorDocumentVersion = priorDocumentVersion,
                priorPlan = plan,
                committedDocument = clearedDocument,
            ) ?: return RestoredActionApplication(
                RestTransitionReduction.NoOp(RestTransitionNoOpReason.AUTHORITY_CHANGED),
            )
            return RestoredActionApplication(
                reduction = reduced,
                normalDispatch = RestoredNormalDispatch(
                    owner = updatedOwner,
                    plan = reduced.plan,
                    routine = routine,
                    exerciseIndex = document.sourceExerciseIndex,
                    setIndex = document.sourceSetIndex,
                ),
            )
        }
        val changed = reduced as? RestTransitionReduction.Changed
            ?: return RestoredActionApplication(reduced)
        val acceptedOverlay = (changed.plan as? RestTransitionPlan.AcceptedRetry)?.let {
            ExerciseLoadOverlay(
                routineExerciseId = document.routineExerciseId,
                multiplier = it.resultingExerciseMultiplier,
            )
        }
        val updatedDocument = document.copy(
            restTransitionPlan = changed.plan,
            attemptStates = canonicalAttemptStates(changed.attemptStates ?: document.attemptStates),
            exerciseLoadOverlays = acceptedOverlay?.let { overlay ->
                canonicalOverlays(
                    document.exerciseLoadOverlays.filterNot {
                        it.routineExerciseId == overlay.routineExerciseId
                    } + overlay,
                )
            } ?: canonicalOverlays(document.exerciseLoadOverlays),
        )
        val priorDocumentVersion = activeRuntimeDocumentVersion
        try {
            if (!replaceRuntimeDocument(updatedDocument)) {
                return RestoredActionApplication(RestTransitionReduction.NoOp(RestTransitionNoOpReason.PERSISTENCE_FAILURE))
            }
        } catch (error: CancellationException) {
            when (runtimeDocumentCommitStatus(updatedDocument, document)) {
                RuntimeDocumentCommitStatus.COMMITTED -> try {
                    withContext(NonCancellable) {
                        reconcileRestoredDocumentCommitLocked(
                            owner = owner,
                            priorDocument = document,
                            priorDocumentVersion = priorDocumentVersion,
                            priorPlan = plan,
                            committedDocument = updatedDocument,
                        )
                    }
                } catch (_: Throwable) {
                    retireRestoredActionAuthorityPreservingCancellation(owner.guardOwner)
                }

                RuntimeDocumentCommitStatus.UNCHANGED_PRIOR -> Unit

                RuntimeDocumentCommitStatus.DIVERGED,
                RuntimeDocumentCommitStatus.UNKNOWN,
                ->
                    retireRestoredActionAuthorityPreservingCancellation(owner.guardOwner)
            }
            throw error
        }
        val updatedOwner = reconcileRestoredDocumentCommitLocked(
            owner = owner,
            priorDocument = document,
            priorDocumentVersion = priorDocumentVersion,
            priorPlan = plan,
            committedDocument = updatedDocument,
        ) ?: return RestoredActionApplication(
            RestTransitionReduction.NoOp(RestTransitionNoOpReason.AUTHORITY_CHANGED),
        )
        if (!hasRestoredRestTransitionAuthority(updatedOwner, updatedDocument, changed.plan)) {
            revokeRestoredActionAuthorityLocked(updatedOwner.guardOwner)
            return RestoredActionApplication(RestTransitionReduction.NoOp(RestTransitionNoOpReason.AUTHORITY_CHANGED))
        }
        return RestoredActionApplication(changed)
    }

    private fun reconcileRestoredDocumentCommitLocked(
        owner: RestoredRuntimeOwner,
        priorDocument: ActiveWorkoutRuntimeDocument,
        priorDocumentVersion: Long,
        priorPlan: RestTransitionPlan,
        committedDocument: ActiveWorkoutRuntimeDocument,
    ): RestoredRuntimeOwner? {
        if (activeRuntimeDocument != priorDocument ||
            activeRuntimeDocumentVersion != priorDocumentVersion ||
            coordinator._restTransitionPlan.value != priorPlan ||
            restoredRuntimeOwner != owner
        ) {
            if (restoredRuntimeOwner == owner) {
                revokeRestoredActionAuthorityLocked(owner.guardOwner)
            }
            return null
        }
        val retainActionOwner = hasRestoredRestTransitionAuthority(owner, priorDocument, priorPlan)
        setActiveRuntimeDocument(committedDocument)
        coordinator._restTransitionPlan.value = committedDocument.restTransitionPlan
        acceptedRetryPermission = null
        persistedRestTimerOwner = null
        coordinator.restTimerJob = null
        val restoredTimerJobToCancel = reconcileRestoredRestTimerDocumentLocked(
            owner = owner.guardOwner,
            priorDocument = priorDocument,
            priorDocumentVersion = priorDocumentVersion,
            committedDocument = committedDocument,
            committedDocumentVersion = activeRuntimeDocumentVersion,
            retainOwner = retainActionOwner,
        )
        val updatedOwner = if (retainActionOwner) {
            owner.copy(
                document = committedDocument,
                documentVersion = activeRuntimeDocumentVersion,
            )
        } else {
            null
        }
        restoredRuntimeOwner = updatedOwner
        if (updatedOwner == null) {
            revokeRestoredActionAuthorityLocked(owner.guardOwner)
        }
        restoredTimerJobToCancel?.cancel()
        return updatedOwner
    }

    private fun revokeRestoredActionAuthorityLocked(owner: RestoredRuntimeOwnerToken) {
        executionGuard.revokeRestoredRuntime(owner)
        clearRestoredRuntimeOwnerIfOwned(owner)
        if (executionGuard.machineTeardownState.value is MachineTeardownState.Ready) {
            clearRestoredTeardownRetryOwnerIfOwned(owner)
        }
        clearRestoredAcceptedRetryPermissionIfOwned(owner)
        detachRestoredRestTimerIfOwned(owner)?.cancel()
    }

    private fun clearRestoredRuntimeOwnerIfOwned(owner: RestoredRuntimeOwnerToken) {
        while (true) {
            val current = restoredRuntimeOwnerRef.value
            if (current?.guardOwner != owner) return
            beforeRestoredOwnerCompareAndClearForTest?.invoke(owner)
            if (restoredRuntimeOwnerRef.compareAndSet(current, null)) return
        }
    }

    private fun clearRestoredAcceptedRetryPermissionIfOwned(owner: RestoredRuntimeOwnerToken) {
        while (true) {
            val current = acceptedRetryPermissionRef.value ?: return
            if ((current.source as? AcceptedRetryPermissionSource.Restored)?.owner != owner) return
            if (acceptedRetryPermissionRef.compareAndSet(current, null)) return
        }
    }

    private fun clearRestoredTeardownRetryOwnerIfOwned(owner: RestoredRuntimeOwnerToken) {
        while (true) {
            val current = restoredTeardownRetryOwnerRef.value ?: return
            if (current != owner) return
            if (restoredTeardownRetryOwnerRef.compareAndSet(current, null)) return
        }
    }

    private fun replaceRestoredRestTimerOwner(
        replacement: RestoredRestTimerOwner,
    ): RestoredRestTimerOwner? {
        while (true) {
            val current = restoredRestTimerOwnerRef.value
            if (restoredRestTimerOwnerRef.compareAndSet(current, replacement)) return current
        }
    }

    private fun detachRestoredRestTimerIfOwned(owner: RestoredRuntimeOwnerToken): Job? {
        while (true) {
            val current = restoredRestTimerOwnerRef.value ?: return null
            if (current.guardOwner != owner) return null
            beforeRestoredRestTimerOwnerCompareAndClearForTest?.invoke(owner)
            if (restoredRestTimerOwnerRef.compareAndSet(current, null)) return current.job
        }
    }

    private fun detachRestoredRestTimerIfExact(owner: RestoredRestTimerOwner): Job? {
        if (restoredRestTimerOwnerRef.value !== owner) return null
        beforeRestoredRestTimerOwnerCompareAndClearForTest?.invoke(owner.guardOwner)
        return if (restoredRestTimerOwnerRef.compareAndSet(owner, null)) owner.job else null
    }

    private fun reconcileRestoredRestTimerDocumentLocked(
        owner: RestoredRuntimeOwnerToken,
        priorDocument: ActiveWorkoutRuntimeDocument,
        priorDocumentVersion: Long,
        committedDocument: ActiveWorkoutRuntimeDocument,
        committedDocumentVersion: Long,
        retainOwner: Boolean,
    ): Job? {
        while (true) {
            val current = restoredRestTimerOwnerRef.value ?: return null
            if (current.guardOwner != owner ||
                current.document != priorDocument ||
                current.documentVersion != priorDocumentVersion
            ) {
                return null
            }
            val committedPlan = committedDocument.restTransitionPlan
            val replacement = committedPlan?.takeIf {
                retainOwner &&
                    it.transitionId == current.transitionId &&
                    it.sourceExecutionId == current.sourceExecutionId
            }?.let {
                current.copy(
                    document = committedDocument,
                    documentVersion = committedDocumentVersion,
                )
            }
            if (restoredRestTimerOwnerRef.compareAndSet(current, replacement)) {
                return if (replacement == null) current.job else null
            }
        }
    }

    private fun clearRestoredRestTimerIfOwned(
        owner: RestoredRuntimeOwnerToken,
        timerJob: Job,
    ): Boolean {
        while (true) {
            val current = restoredRestTimerOwnerRef.value ?: return false
            if (current.guardOwner != owner || current.job !== timerJob) return false
            if (restoredRestTimerOwnerRef.compareAndSet(current, null)) return true
        }
    }

    private fun startRestoredRestTimerIfOwned(timerJob: Job) {
        val owner = restoredRestTimerOwnerRef.value
        if (owner?.job === timerJob && executionGuard.isRestoredRuntimeCurrent(owner.guardOwner)) {
            timerJob.start()
        } else {
            timerJob.cancel()
        }
    }

    private fun createRestoredRestTimerJob(
        guardOwner: RestoredRuntimeOwnerToken,
        transitionId: String,
        sourceExecutionId: String,
    ): Job {
        lateinit var timerJob: Job
        timerJob = scope.launch(start = CoroutineStart.LAZY) {
            runRestoredRestTimer(
                guardOwner = guardOwner,
                transitionId = transitionId,
                sourceExecutionId = sourceExecutionId,
                timerJob = timerJob,
            )
        }
        return timerJob
    }

    private suspend fun runRestoredRestTimer(
        guardOwner: RestoredRuntimeOwnerToken,
        transitionId: String,
        sourceExecutionId: String,
        timerJob: Job,
    ) {
        try {
            while (currentCoroutineContext().isActive) {
                delay(100L)
                val owner = restoredRestTimerOwnerRef.value?.takeIf { current ->
                    current.guardOwner == guardOwner &&
                        current.transitionId == transitionId &&
                        current.sourceExecutionId == sourceExecutionId &&
                        current.job === timerJob
                } ?: return
                val deadline = owner.monotonicDeadlineElapsedRealtimeMs ?: return
                val remainingSeconds = computeRemainingSeconds(deadline)
                beforeRestoredRestTimerTickPublishForTest?.invoke(owner.guardOwner, remainingSeconds)
                if (!publishRestoredRestTimerTick(owner, remainingSeconds)) {
                    afterRestoredRestTimerProfileAuthorityFailureForTest?.invoke()
                    val retainForTransientProfile = restTransitionMutex.withLock {
                        restoredRestTimerOwnerRef.value === owner &&
                            restoredTimerPresentationCanBeRetainedAfterAuthorityFailure(owner)
                    }
                    if (retainForTransientProfile) continue
                    if (restoredRestTimerOwnerRef.compareAndSet(owner, null)) {
                        revokeRestoredActionAuthorityLocked(owner.guardOwner)
                        return
                    }
                    continue
                }
                if (remainingSeconds <= 0) {
                    afterRestoredRestTimerZeroPublishForTest?.invoke(owner.guardOwner)
                    val expiredOwner = owner.copy(
                        monotonicDeadlineElapsedRealtimeMs = null,
                        job = null,
                    )
                    if (restoredRestTimerOwnerRef.compareAndSet(
                            owner,
                            expiredOwner,
                        )
                    ) {
                        return
                    }
                    val migratedOwner = restoredRestTimerOwnerRef.value
                    if (migratedOwner?.guardOwner == guardOwner &&
                        migratedOwner.transitionId == transitionId &&
                        migratedOwner.sourceExecutionId == sourceExecutionId &&
                        migratedOwner.job === timerJob &&
                        migratedOwner.monotonicDeadlineElapsedRealtimeMs != null
                    ) {
                        continue
                    }
                    return
                }
            }
        } catch (error: CancellationException) {
            clearRestoredRestTimerIfOwned(guardOwner, timerJob)
            throw error
        }
    }

    private suspend fun publishRestoredRestTimerTick(
        owner: RestoredRestTimerOwner,
        remainingSeconds: Int,
    ): Boolean = restTransitionMutex.withLock {
        executionGuard.commitRestoredTimerPublication(
            owner = owner.guardOwner,
            candidateStillCurrent = {
                restoredRestTimerOwnerRef.value === owner &&
                    restoredTimerPresentationIsCurrent(owner)
            },
        ) {
            coordinator._restSecondsRemaining.value = remainingSeconds
            val resting = coordinator._workoutState.value as WorkoutState.Resting
            coordinator._workoutState.value = resting.copy(restSecondsRemaining = remainingSeconds)
        }
    }

    private fun restoredTimerPresentationIsCurrent(owner: RestoredRestTimerOwner): Boolean {
        val restoredOwner = restoredRuntimeOwner ?: return false
        val document = activeRuntimeDocument ?: return false
        val plan = coordinator._restTransitionPlan.value ?: return false
        return restoredOwner.guardOwner == owner.guardOwner &&
            restoredOwner.document == owner.document &&
            restoredOwner.documentVersion == owner.documentVersion &&
            document == owner.document &&
            activeRuntimeDocumentVersion == owner.documentVersion &&
            plan.transitionId == owner.transitionId &&
            plan.sourceExecutionId == owner.sourceExecutionId &&
            !document.isRestPaused &&
            coordinator._workoutState.value is WorkoutState.Resting &&
            hasRestoredOwnerContextAuthority(restoredOwner, document)
    }

    private fun restoredTimerPresentationCanBeRetainedAfterAuthorityFailure(
        owner: RestoredRestTimerOwner,
    ): Boolean {
        val restoredOwner = restoredRuntimeOwner ?: return false
        val document = activeRuntimeDocument ?: return false
        val plan = coordinator._restTransitionPlan.value ?: return false
        return restoredOwner.guardOwner == owner.guardOwner &&
            restoredOwner.document == owner.document &&
            restoredOwner.documentVersion == owner.documentVersion &&
            document == owner.document &&
            activeRuntimeDocumentVersion == owner.documentVersion &&
            plan.transitionId == owner.transitionId &&
            plan.sourceExecutionId == owner.sourceExecutionId &&
            !document.isRestPaused &&
            coordinator._workoutState.value is WorkoutState.Resting &&
            restoredOwnerContextCanBeRetainedAfterAuthorityFailure(restoredOwner, document)
    }

    private suspend fun dispatchRestoredNormalAdvance(dispatch: RestoredNormalDispatch): Boolean = restTransitionMutex.withLock { dispatchRestoredNormalAdvanceLocked(dispatch) }

    private fun dispatchRestoredNormalAdvanceLocked(dispatch: RestoredNormalDispatch): Boolean {
        val owner = restoredRuntimeOwner ?: return false.also {
            revokeRestoredActionAuthorityLocked(dispatch.owner.guardOwner)
        }
        val document = activeRuntimeDocument ?: return false.also {
            revokeRestoredActionAuthorityLocked(dispatch.owner.guardOwner)
        }
        if (owner != dispatch.owner ||
            document.restTransitionPlan != null ||
            coordinator._restTransitionPlan.value != null ||
            document.sourceExerciseIndex != dispatch.exerciseIndex ||
            document.sourceSetIndex != dispatch.setIndex ||
            !hasRestoredOwnerContextAuthority(owner, document)
        ) {
            revokeRestoredActionAuthorityLocked(dispatch.owner.guardOwner)
            return false
        }

        val nextStep = try {
            flowDelegate?.getNextStep(
                dispatch.routine,
                dispatch.exerciseIndex,
                dispatch.setIndex,
            )
        } catch (error: Throwable) {
            revokeRestoredActionAuthorityLocked(owner.guardOwner)
            throw error
        }
        if (restoredRuntimeOwner != owner ||
            activeRuntimeDocument != document ||
            activeRuntimeDocumentVersion != owner.documentVersion ||
            coordinator._restTransitionPlan.value != null ||
            document.restTransitionPlan != null ||
            !hasRestoredOwnerContextAuthority(owner, document)
        ) {
            revokeRestoredActionAuthorityLocked(owner.guardOwner)
            return false
        }
        revokeRestoredActionAuthorityLocked(owner.guardOwner)
        coordinator._isRestPaused.value = false
        coordinator.restTimerJob?.cancel()
        coordinator.restTimerJob = null
        coordinator._workoutState.value = WorkoutState.Idle
        if (nextStep == null) {
            flowDelegate?.showRoutineComplete()
        } else {
            flowDelegate?.enterSetReady(nextStep.first, nextStep.second)
        }
        return true
    }

    private fun hasPersistedRestTimerActionAuthorityLocked(
        authority: PersistedRestTimerActionAuthority,
        document: ActiveWorkoutRuntimeDocument,
        plan: RestTransitionPlan,
        lease: ExecutionLease,
    ): Boolean {
        val owner = persistedRestTimerOwner ?: return false
        if (owner.job !== authority.timerJob ||
            !owner.job.isActive ||
            coordinator.restTimerJob !== authority.timerJob ||
            owner.transitionId != authority.transitionId ||
            owner.sourceExecutionId != authority.sourceExecutionId ||
            activeRuntimeDocumentVersion != authority.documentVersion ||
            plan != authority.plan ||
            document.restDeadlineEpochMs != authority.deadlineEpochMs ||
            document.isRestPaused != authority.isPaused ||
            !hasRestTransitionAuthority(document, plan, lease)
        ) {
            return false
        }
        return !authority.requiresExpiredDeadline ||
            (!document.isRestPaused && RestDeadlineCalculator.remainingSeconds(document, wallClockMillisProvider()) <= 0)
    }

    private fun hasPersistedRestTimerDispatchAuthorityLocked(
        authority: PersistedRestTimerActionAuthority,
        plan: RestTransitionPlan.NormalAdvance,
        clearedDocumentVersion: Long,
    ): Boolean {
        val owner = persistedRestTimerOwner ?: return false
        val lease = executionGuard.currentLease ?: return false
        val document = activeRuntimeDocument ?: return false
        return owner.job === authority.timerJob &&
            owner.job.isActive &&
            coordinator.restTimerJob === authority.timerJob &&
            owner.transitionId == authority.transitionId &&
            owner.sourceExecutionId == authority.sourceExecutionId &&
            plan.transitionId == authority.transitionId &&
            plan.sourceExecutionId == authority.sourceExecutionId &&
            activeRuntimeDocumentVersion == clearedDocumentVersion &&
            document.restTransitionPlan == null &&
            executionGuard.isCurrent(lease) &&
            lease.executionId.toString() == authority.sourceExecutionId &&
            coordinator.currentRoutineId == document.routineId &&
            coordinator.currentRoutineSessionId == document.routineSessionId &&
            coordinator._currentExerciseIndex.value == document.sourceExerciseIndex &&
            coordinator._currentSetIndex.value == document.sourceSetIndex
    }

    private fun consumeNormalTransitionAndDispatch(plan: RestTransitionPlan.NormalAdvance) {
        scope.launch {
            consumeNormalTransitionAndDispatchAwait(plan, timerAuthority = null)
        }
    }

    private suspend fun consumeNormalTransitionAndDispatchAwait(
        plan: RestTransitionPlan.NormalAdvance,
        timerAuthority: PersistedRestTimerActionAuthority?,
    ): Boolean {
        val cachedNavigation = resolveNavigationOnce(plan) ?: return false
        val clearedDocumentVersion = restTransitionMutex.withLock {
            val lease = executionGuard.currentLease ?: return@withLock null
            val document = activeRuntimeDocument ?: return@withLock null
            val currentPlan = coordinator._restTransitionPlan.value
            if (currentPlan !is RestTransitionPlan.NormalAdvance && currentPlan !is RestTransitionPlan.Declined) {
                return@withLock null
            }
            if (currentPlan.transitionId != plan.transitionId || !hasRestTransitionAuthority(document, currentPlan, lease)) {
                return@withLock null
            }
            if (timerAuthority != null &&
                !hasPersistedRestTimerActionAuthorityLocked(timerAuthority, document, currentPlan, lease)
            ) {
                return@withLock null
            }
            val cleared = document.copy(restTransitionPlan = null)
            if (!replaceRuntimeDocument(cleared)) return@withLock null
            if (timerAuthority != null &&
                !hasPersistedRestTimerActionAuthorityLocked(timerAuthority, document, currentPlan, lease)
            ) {
                return@withLock null
            }
            if (!executionGuard.isCurrent(lease) ||
                coordinator.currentRoutineId != cleared.routineId ||
                coordinator.currentRoutineSessionId != cleared.routineSessionId ||
                coordinator._currentExerciseIndex.value != cleared.sourceExerciseIndex ||
                coordinator._currentSetIndex.value != cleared.sourceSetIndex
            ) {
                return@withLock null
            }
            setActiveRuntimeDocument(cleared)
            coordinator._restTransitionPlan.value = null
            activeRuntimeDocumentVersion
        } ?: return false
        afterDurableRestPlanClearForTest?.invoke()
        if (timerAuthority != null) {
            val canDispatch = restTransitionMutex.withLock {
                hasPersistedRestTimerDispatchAuthorityLocked(timerAuthority, plan, clearedDocumentVersion)
            }
            if (!canDispatch) return false
        }
        dispatchNormalAdvance(plan, cachedNavigation)
        return true
    }

    private fun dispatchNormalAdvance(
        plan: RestTransitionPlan.NormalAdvance,
        cachedNavigation: CachedTransitionNavigation,
    ) {
        val lease = executionGuard.currentLease ?: return
        if (plan.sourceExecutionId != lease.executionId.toString()) return
        if (coordinator._restTransitionPlan.value != null) return
        if (cachedNavigation.transitionId != plan.transitionId || cachedNavigation.sourceExecutionId != plan.sourceExecutionId) return
        coordinator._isRestPaused.value = false
        coordinator.restTimerJob?.cancel()
        coordinator.restTimerJob = null
        if (isSingleExerciseMode(coordinator)) {
            advanceToNextSetInSingleExercise(lease)
        } else {
            startNextSetOrExerciseFor(lease, cachedNavigation)
        }
    }

    /** Resolve exactly once, only after the transition plan has been durably installed. */
    private suspend fun resolveNavigationOnce(plan: RestTransitionPlan.NormalAdvance): CachedTransitionNavigation? {
        beforeRestTransitionNavigationClaimForTest?.invoke()
        var ownsResolution = false
        var resolutionContext: TransitionNavigationResolutionContext? = null
        val pending = restTransitionMutex.withLock {
            val lease = executionGuard.currentLease ?: return@withLock null
            val document = activeRuntimeDocument ?: return@withLock null
            val currentPlan = coordinator._restTransitionPlan.value
            val authoritativePlan = when (currentPlan) {
                plan -> currentPlan
                is RestTransitionPlan.Declined -> currentPlan.takeIf { it.normalAdvance == plan }
                else -> null
            } ?: return@withLock null
            if (!hasRestTransitionAuthority(document, authoritativePlan, lease)) return@withLock null

            onRestTransitionNavigationCacheReadForTest?.invoke()
            cachedTransitionNavigation
                ?.takeIf { it.transitionId == plan.transitionId && it.sourceExecutionId == plan.sourceExecutionId }
                ?.let { return it }
            onRestTransitionNavigationContextReadForTest?.invoke()
            val routine = coordinator._loadedRoutine.value ?: return@withLock null
            resolutionContext = TransitionNavigationResolutionContext(
                routine = routine,
                exerciseIndex = plan.sourceCoordinates.exerciseIndex,
                setIndex = plan.sourceCoordinates.setIndex,
                cycleId = coordinator.activeCycleId,
                cycleDayNumber = coordinator.activeCycleDayNumber,
            )
            pendingTransitionNavigation
                ?.takeIf { it.transitionId == plan.transitionId && it.sourceExecutionId == plan.sourceExecutionId }
                ?: PendingTransitionNavigation(
                    transitionId = plan.transitionId,
                    sourceExecutionId = plan.sourceExecutionId,
                    result = CompletableDeferred(),
                ).also {
                    pendingTransitionNavigation = it
                    ownsResolution = true
                }
        }
        if (pending == null) return null
        if (!ownsResolution) return pending.result.await()

        return try {
            beforeRestTransitionNavigationResolutionForTest?.invoke()
            val context = requireNotNull(resolutionContext)
            val resolved = CachedTransitionNavigation(
                transitionId = plan.transitionId,
                sourceExecutionId = plan.sourceExecutionId,
                nextStep = flowDelegate?.getNextStep(
                    context.routine,
                    context.exerciseIndex,
                    context.setIndex,
                ),
                cycleId = context.cycleId,
                cycleDayNumber = context.cycleDayNumber,
            )
            afterRestTransitionNavigationResolutionForTest?.invoke()
            withContext(NonCancellable) {
                restTransitionMutex.withLock {
                    val lease = executionGuard.currentLease
                    val document = activeRuntimeDocument
                    val currentPlan = coordinator._restTransitionPlan.value
                    val authoritativePlan = when (currentPlan) {
                        plan -> currentPlan
                        is RestTransitionPlan.Declined -> currentPlan.takeIf { it.normalAdvance == plan }
                        else -> null
                    }
                    val canPublish = pendingTransitionNavigation === pending &&
                        lease != null &&
                        document != null &&
                        authoritativePlan != null &&
                        hasRestTransitionAuthority(document, authoritativePlan, lease)
                    if (canPublish) {
                        cachedTransitionNavigation = resolved
                        pendingTransitionNavigation = null
                        pending.result.complete(resolved)
                        refreshActivePlanOwnedRestPresentationLocked()
                    } else {
                        if (pendingTransitionNavigation === pending) pendingTransitionNavigation = null
                        pending.result.complete(null)
                    }
                }
                pending.result.await()
            }
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                restTransitionMutex.withLock {
                    if (pendingTransitionNavigation === pending) pendingTransitionNavigation = null
                    pending.result.completeExceptionally(error)
                }
            }
            throw error
        }
    }

    private fun hasRestTransitionAuthority(
        document: ActiveWorkoutRuntimeDocument,
        plan: RestTransitionPlan,
        lease: ExecutionLease,
    ): Boolean = liveRestTransitionProfileIsCurrent(document) &&
        hasRestTransitionAuthorityIgnoringReadyProfile(document, plan, lease)

    private fun hasRestTransitionAuthorityIgnoringReadyProfile(
        document: ActiveWorkoutRuntimeDocument,
        plan: RestTransitionPlan,
        lease: ExecutionLease,
    ): Boolean {
        if (liveRestTransitionIsTerminallySuperseded(document) ||
            !executionGuard.isCurrent(lease) ||
            document.sourceExecutionId != lease.executionId.toString() ||
            document.sourceStableSessionId != lease.sessionId ||
            document.profileId != lease.profileId ||
            document.restTransitionPlan != plan ||
            document.logicalSetKey != plan.logicalSetKey ||
            document.plannedSetId != plan.actionIdentity().plannedSetId
        ) {
            return false
        }
        val routine = coordinator._loadedRoutine.value ?: return false
        val sourceExercise = routine.exercises.getOrNull(document.sourceExerciseIndex) ?: return false
        return (routine.profileId ?: "default") == document.profileId &&
            coordinator.currentRoutineId == document.routineId &&
            coordinator.currentRoutineSessionId == document.routineSessionId &&
            coordinator._currentExerciseIndex.value == document.sourceExerciseIndex &&
            coordinator._currentSetIndex.value == document.sourceSetIndex &&
            sourceExercise.id == document.routineExerciseId
    }

    private fun liveRestTransitionProfileIsCurrent(document: ActiveWorkoutRuntimeDocument): Boolean = (userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready)
        ?.profile
        ?.id == document.profileId

    private fun liveRestTransitionIsTerminallySuperseded(
        document: ActiveWorkoutRuntimeDocument,
    ): Boolean {
        val readyProfile = userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready
        return (readyProfile != null && readyProfile.profile.id != document.profileId) ||
            runtimeCleanupFencesLiveDocument(document)
    }

    private fun runtimeCleanupFencesLiveDocument(document: ActiveWorkoutRuntimeDocument): Boolean {
        val currentDocumentVersion = activeRuntimeDocumentVersion
        return pendingRuntimeCleanupRef.value.any { target ->
            target.expectedDocument == document &&
                when (val source = target.source) {
                    is RuntimeCleanupSource.ActiveDocument ->
                        source.engineVersion == currentDocumentVersion

                    is RuntimeCleanupSource.RestoredOwner ->
                        source.engineVersion == currentDocumentVersion

                    is RuntimeCleanupSource.PendingEngineReplace ->
                        source.expectedPublishedEngineVersion == currentDocumentVersion ||
                            source.expectedPublishedEngineVersion == currentDocumentVersion + 1L

                    is RuntimeCleanupSource.PendingInitialReplace ->
                        pendingInitialRestRuntimeDocument === document

                    is RuntimeCleanupSource.ColdHandle -> false
                }
        }
    }

    private fun hasRestoredRestTransitionAuthority(
        owner: RestoredRuntimeOwner,
        document: ActiveWorkoutRuntimeDocument,
        plan: RestTransitionPlan,
    ): Boolean = hasRestoredRestTransitionAuthorityIgnoringReadyProfile(owner, document, plan) &&
        hasRestoredOwnerContextAuthority(owner, document)

    private fun hasRestoredRestTransitionAuthorityIgnoringReadyProfile(
        owner: RestoredRuntimeOwner,
        document: ActiveWorkoutRuntimeDocument,
        plan: RestTransitionPlan,
    ): Boolean = document.restTransitionPlan == plan &&
        document.logicalSetKey == plan.logicalSetKey &&
        document.plannedSetId == plan.actionIdentity().plannedSetId &&
        hasRestoredOwnerContextAuthorityIgnoringReadyProfile(owner, document)

    private fun hasRestoredOwnerContextAuthority(
        owner: RestoredRuntimeOwner,
        document: ActiveWorkoutRuntimeDocument,
    ): Boolean {
        if (!hasRestoredOwnerContextAuthorityIgnoringReadyProfile(owner, document) ||
            captureExternalCommandInputStamp() != owner.externalCommandInputStamp
        ) {
            return false
        }
        val readyProfile = userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready
            ?: return false
        return readyProfile.profile.id == document.profileId
    }

    // A failed authority read under Switching may be followed immediately by Ready for the
    // same profile. Retain only while every non-profile identity remains exact; a stable
    // external-input mismatch or a definitively different Ready profile still revokes.
    private fun restoredOwnerContextCanBeRetainedAfterAuthorityFailure(
        owner: RestoredRuntimeOwner,
        document: ActiveWorkoutRuntimeDocument,
    ): Boolean {
        val profileContext = userProfileRepository.activeProfileContext.value
        val profileMayStillMatch = when (profileContext) {
            is ActiveProfileContext.Switching -> true
            is ActiveProfileContext.Ready -> profileContext.profile.id == document.profileId
        }
        if (!profileMayStillMatch) return false
        if (profileContext is ActiveProfileContext.Ready) {
            val currentStamp = captureExternalCommandInputStamp()
            if (currentStamp != null && currentStamp != owner.externalCommandInputStamp) return false
        }
        return hasRestoredOwnerContextAuthorityIgnoringReadyProfile(owner, document)
    }

    private fun hasRestoredOwnerContextAuthorityIgnoringReadyProfile(
        owner: RestoredRuntimeOwner,
        document: ActiveWorkoutRuntimeDocument,
    ): Boolean {
        if (restoredRuntimeOwner != owner ||
            owner.document != document ||
            owner.documentVersion != activeRuntimeDocumentVersion ||
            activeRuntimeDocument != document ||
            executionGuard.currentLease != null ||
            !executionGuard.isRestoredRuntimeCurrent(owner.guardOwner)
        ) {
            return false
        }
        val routine = coordinator._loadedRoutine.value ?: return false
        val sourceExercise = routine.exercises.getOrNull(document.sourceExerciseIndex) ?: return false
        return routine.profileId == document.profileId &&
            routine.id == document.routineId &&
            coordinator.currentRoutineId == document.routineId &&
            coordinator.currentRoutineSessionId == document.routineSessionId &&
            coordinator._currentExerciseIndex.value == document.sourceExerciseIndex &&
            coordinator._currentSetIndex.value == document.sourceSetIndex &&
            sourceExercise.id == document.routineExerciseId
    }

    private suspend fun tryStartCurrentAcceptedRetryLive(): Boolean {
        val sourceStableSessionId = restTransitionMutex.withLock {
            val plan = coordinator._restTransitionPlan.value as? RestTransitionPlan.AcceptedRetry
                ?: return@withLock null
            val document = activeRuntimeDocument ?: return@withLock null
            if (document.restTransitionPlan != plan) return@withLock null
            document.sourceStableSessionId
        } ?: return false
        return tryStartAcceptedRetry(RetryPersistenceGate.Live(sourceStableSessionId))
    }

    private suspend fun tryStartCurrentAcceptedRetryRestored(
        expectedOwner: RestoredRuntimeOwnerToken,
    ): Boolean {
        val gate = restTransitionMutex.withLock {
            val owner = restoredRuntimeOwner
                ?.takeIf { it.guardOwner == expectedOwner }
                ?: return@withLock null
            val plan = coordinator._restTransitionPlan.value as? RestTransitionPlan.AcceptedRetry
                ?: return@withLock null
            val document = activeRuntimeDocument ?: return@withLock null
            val permission = acceptedRetryPermission ?: return@withLock null
            if (owner.document != document ||
                owner.documentVersion != activeRuntimeDocumentVersion ||
                document.restTransitionPlan != plan ||
                permission.source != AcceptedRetryPermissionSource.Restored(expectedOwner) ||
                !hasAcceptedRetryPermissionAuthorityLocked(permission, document, plan)
            ) {
                return@withLock null
            }
            RetryPersistenceGate.Restored(
                sourceStableSessionId = document.sourceStableSessionId,
                actionIdentity = plan.actionIdentity(),
                sourceContext = owner.sourceContext,
            )
        } ?: return false
        return tryStartAcceptedRetry(gate)
    }

    /**
     * Runs the one idempotent durability/readiness gate used by live and restored
     * accepted retries. Repository work deliberately happens outside the rest
     * document mutex and outside [WorkoutExecutionGuard]'s platform locks.
     */
    internal suspend fun tryStartAcceptedRetry(gate: RetryPersistenceGate): Boolean {
        val recoveryPublicationEpoch = executionGuard.captureRecoveryPublicationEpoch()
        val configurationInputEpoch = executionGuard.captureConfigurationInputEpoch()
        val externalCommandInputStamp = captureExternalCommandInputStamp() ?: return false
        val capture = restTransitionMutex.withLock {
            captureAcceptedRetryGateLocked(
                gate,
                recoveryPublicationEpoch,
                configurationInputEpoch,
                externalCommandInputStamp,
            )
        }
        val snapshot = when (capture) {
            is RetryGateCapture.Ready -> capture.snapshot

            RetryGateCapture.Wait -> return false

            is RetryGateCapture.FailClosed -> {
                failAcceptedRetryClosed(capture.plan, capture.documentVersion, capture.authority)
                return false
            }
        }

        if (gate is RetryPersistenceGate.Live &&
            executionGuard.persistenceClaimStatus(gate.sourceStableSessionId) != PersistenceClaimStatus.PERSISTED
        ) {
            return false
        }
        currentCoroutineContext().ensureActive()
        val durable = try {
            completedSetRepository.isAttemptDurable(
                stableSessionId = gate.sourceStableSessionId,
                key = snapshot.plan.logicalSetKey,
                attemptNumber = snapshot.document.sourceAttemptNumber,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.w(error) { "Accepted retry durable-source read failed before plan consumption" }
            return false
        }
        currentCoroutineContext().ensureActive()
        if (!durable) {
            if (gate is RetryPersistenceGate.Restored ||
                (
                    gate is RetryPersistenceGate.Live &&
                        executionGuard.persistenceClaimStatus(gate.sourceStableSessionId) == PersistenceClaimStatus.PERSISTED
                    )
            ) {
                failAcceptedRetryClosed(snapshot.plan, snapshot.documentVersion, snapshot.failClosedAuthority())
            }
            return false
        }

        val plannedSets = try {
            completedSetRepository.getPlannedSets(snapshot.document.routineExerciseId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.w(error) { "Accepted retry planned-set read failed before plan consumption" }
            return false
        }
        currentCoroutineContext().ensureActive()
        beforeAcceptedRetryPlanConsumeForTest?.invoke()
        currentCoroutineContext().ensureActive()
        if (gate is RetryPersistenceGate.Live &&
            executionGuard.persistenceClaimStatus(gate.sourceStableSessionId) != PersistenceClaimStatus.PERSISTED
        ) {
            return false
        }
        val durableBeforeConsume = try {
            completedSetRepository.isAttemptDurable(
                stableSessionId = gate.sourceStableSessionId,
                key = snapshot.plan.logicalSetKey,
                attemptNumber = snapshot.document.sourceAttemptNumber,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Logger.w(error) { "Accepted retry durable-source recheck failed before plan consumption" }
            return false
        }
        currentCoroutineContext().ensureActive()
        if (!durableBeforeConsume) {
            failAcceptedRetryClosed(snapshot.plan, snapshot.documentVersion, snapshot.failClosedAuthority())
            return false
        }
        if (gate is RetryPersistenceGate.Live &&
            executionGuard.persistenceClaimStatus(gate.sourceStableSessionId) != PersistenceClaimStatus.PERSISTED
        ) {
            return false
        }
        val buildResult = try {
            restTransitionMutex.withLock {
                buildAndConsumeRetryStartRequestLocked(snapshot, plannedSets)
            }
        } catch (error: CancellationException) {
            val clearedDocument = snapshot.document.copy(restTransitionPlan = null)
            when (runtimeDocumentCommitStatus(clearedDocument, snapshot.document)) {
                RuntimeDocumentCommitStatus.COMMITTED -> try {
                    withContext(NonCancellable) {
                        val recovery = restTransitionMutex.withLock {
                            snapshot.restoredOwner?.let { owner ->
                                reconcileRestoredDocumentCommitLocked(
                                    owner = owner,
                                    priorDocument = snapshot.document,
                                    priorDocumentVersion = snapshot.documentVersion,
                                    priorPlan = snapshot.plan,
                                    committedDocument = clearedDocument,
                                )?.let { reconciledOwner ->
                                    buildFailClosedRetryStartRequest(
                                        document = clearedDocument,
                                        plan = snapshot.plan,
                                        authority = snapshot.failClosedAuthority(),
                                        expectedSource = null,
                                        restoredOwner = reconciledOwner,
                                    )
                                }
                            } ?: if (snapshot.gate is RetryPersistenceGate.Live) {
                                failAcceptedRetryClosedLocked(
                                    snapshot.plan,
                                    snapshot.documentVersion,
                                    snapshot.failClosedAuthority(),
                                )
                            } else {
                                null
                            }
                        }
                        recovery?.let { enterManualRetryRecovery(it, expectedLease = it.expectedSource) }
                    }
                } catch (_: Throwable) {
                    snapshot.restoredOwner?.let { owner ->
                        retireRestoredActionAuthorityPreservingCancellation(owner.guardOwner)
                    }
                }

                RuntimeDocumentCommitStatus.UNCHANGED_PRIOR -> Unit

                RuntimeDocumentCommitStatus.DIVERGED,
                RuntimeDocumentCommitStatus.UNKNOWN,
                -> snapshot.restoredOwner?.let { owner ->
                    retireRestoredActionAuthorityPreservingCancellation(owner.guardOwner)
                }
            }
            throw error
        }
        val request = when (buildResult) {
            is RetryRequestBuildResult.Ready -> buildResult.request

            is RetryRequestBuildResult.FailClosed -> {
                buildResult.recovery?.let { enterManualRetryRecovery(it, expectedLease = it.expectedSource) }
                return false
            }

            RetryRequestBuildResult.Wait -> return false
        }
        try {
            afterAcceptedRetryPlanConsumedForTest?.invoke()
            currentCoroutineContext().ensureActive()
            if (!hasRetryPersistenceAuthority(request)) {
                enterManualRetryRecovery(request, expectedLease = request.expectedSource)
                return false
            }
        } catch (error: CancellationException) {
            try {
                enterManualRetryRecovery(request, expectedLease = request.expectedSource)
            } catch (_: Throwable) {
                releaseManualRetryRecoveryAuthority(request)
            }
            throw error
        } catch (error: Exception) {
            Logger.e(error) { "Accepted retry dispatch failed after its plan was consumed" }
            enterManualRetryRecovery(request, expectedLease = request.expectedSource)
            return false
        }

        val lease = startWorkoutInternal(
            skipCountdown = true,
            isJustLiftMode = false,
            retryRequest = request,
        )
        if (lease == null) {
            enterManualRetryRecovery(request, expectedLease = request.expectedSource)
            return false
        }
        return true
    }

    private fun captureAcceptedRetryGateLocked(
        gate: RetryPersistenceGate,
        recoveryPublicationEpoch: Long,
        configurationInputEpoch: Long,
        externalCommandInputStamp: ExternalCommandInputStamp,
    ): RetryGateCapture {
        if (acceptedRetryStartClaim.value != null) return RetryGateCapture.Wait
        val plan = coordinator._restTransitionPlan.value as? RestTransitionPlan.AcceptedRetry
            ?: return RetryGateCapture.Wait
        val document = activeRuntimeDocument
            ?: return RetryGateCapture.Wait
        val restoredOwner = when (gate) {
            is RetryPersistenceGate.Live -> null

            is RetryPersistenceGate.Restored ->
                restoredRuntimeOwner
                    ?.takeIf { owner ->
                        owner.document == document &&
                            owner.documentVersion == activeRuntimeDocumentVersion &&
                            owner.sourceContext == gate.sourceContext &&
                            owner.guardOwner.configurationInputEpoch == configurationInputEpoch &&
                            owner.guardOwner.recoverySupersessionEpoch == recoveryPublicationEpoch
                    }
                    ?: return RetryGateCapture.Wait
        }
        val permission = when (gate) {
            is RetryPersistenceGate.Live -> {
                val livePermission = acceptedRetryPermission ?: return RetryGateCapture.Wait
                if (!hasAcceptedRetryPermissionAuthorityLocked(livePermission, document, plan)) {
                    acceptedRetryPermission = null
                    return RetryGateCapture.Wait
                }
                livePermission
            }

            is RetryPersistenceGate.Restored -> {
                if (gate.actionIdentity != plan.actionIdentity()) return RetryGateCapture.Wait
                val owner = requireNotNull(restoredOwner)
                val restoredPermission = acceptedRetryPermission ?: return RetryGateCapture.Wait
                if (restoredPermission.source != AcceptedRetryPermissionSource.Restored(owner.guardOwner) ||
                    !hasAcceptedRetryPermissionAuthorityLocked(restoredPermission, document, plan)
                ) {
                    acceptedRetryPermission = null
                    return RetryGateCapture.Wait
                }
                restoredPermission
            }
        }
        val expectedSource = when (gate) {
            is RetryPersistenceGate.Live ->
                executionGuard.currentLease
                    ?.takeIf { leaseMatchesRetrySource(it, document) }
                    ?: return RetryGateCapture.Wait

            is RetryPersistenceGate.Restored -> {
                if (executionGuard.currentLease != null) return RetryGateCapture.Wait
                null
            }
        }
        val completion = expectedSource?.let(executionGuard::claimedCompletion)
        val commandTemplate = when (gate) {
            is RetryPersistenceGate.Live ->
                completion?.logicalPreRackCommandTemplate
                    ?: return RetryGateCapture.Wait

            is RetryPersistenceGate.Restored -> gate.sourceContext.commandTemplate
        }
        val snapshot = AcceptedRetryGateSnapshot(
            gate = gate,
            document = document,
            documentVersion = activeRuntimeDocumentVersion,
            plan = plan,
            permission = permission,
            expectedSource = expectedSource,
            restoredOwner = restoredOwner,
            completion = completion,
            commandTemplate = commandTemplate,
            recoveryPublicationEpoch = recoveryPublicationEpoch,
            configurationInputEpoch = configurationInputEpoch,
            externalCommandInputStamp = externalCommandInputStamp,
        )
        if (gate is RetryPersistenceGate.Restored &&
            !hasRestoredRetryExternalAuthority(snapshot)
        ) {
            val owner = requireNotNull(restoredOwner)
            revokeRestoredActionAuthorityLocked(owner.guardOwner)
            return RetryGateCapture.Wait
        }
        if (!hasAcceptedRetrySnapshotAuthorityLocked(snapshot, requireTeardownReady = false)) {
            return RetryGateCapture.FailClosed(
                plan,
                activeRuntimeDocumentVersion,
                snapshot.failClosedAuthority(),
            )
        }
        val teardownReady = when (gate) {
            is RetryPersistenceGate.Live -> executionGuard.machineTeardownState.value is MachineTeardownState.Ready
            is RetryPersistenceGate.Restored -> executionGuard.isRestoredTeardownReady(requireNotNull(restoredOwner).guardOwner)
        }
        if (!teardownReady) {
            return RetryGateCapture.Wait
        }
        return RetryGateCapture.Ready(snapshot)
    }

    private fun hasRestoredRetryExternalAuthority(snapshot: AcceptedRetryGateSnapshot): Boolean {
        val owner = snapshot.restoredOwner ?: return false
        val readyProfile = userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready
            ?: return false
        return readyProfile.profile.id == snapshot.document.profileId &&
            snapshot.externalCommandInputStamp.profileId == snapshot.document.profileId &&
            captureExternalCommandInputStamp() == snapshot.externalCommandInputStamp &&
            executionGuard.isRestoredRuntimeCurrent(owner.guardOwner)
    }

    private fun AcceptedRetryGateSnapshot.failClosedAuthority(): RetryFailClosedAuthority = when (gate) {
        is RetryPersistenceGate.Live -> RetryFailClosedAuthority.Live(
            requireNotNull(expectedSource),
            recoveryPublicationEpoch,
        )

        is RetryPersistenceGate.Restored -> RetryFailClosedAuthority.Restored(
            owner = requireNotNull(restoredOwner).guardOwner,
            recoveryPublicationEpoch = recoveryPublicationEpoch,
        )
    }

    private fun leaseMatchesRetrySource(
        lease: ExecutionLease,
        document: ActiveWorkoutRuntimeDocument,
    ): Boolean = lease.sessionId == document.sourceStableSessionId &&
        lease.executionId.toString() == document.sourceExecutionId &&
        lease.profileId == document.profileId

    private fun hasAcceptedRetryPermissionAuthorityLocked(
        permission: AcceptedRetryPermission,
        document: ActiveWorkoutRuntimeDocument,
        plan: RestTransitionPlan.AcceptedRetry,
    ): Boolean {
        if (permission.documentVersion != activeRuntimeDocumentVersion ||
            permission.transitionId != plan.transitionId ||
            permission.sourceExecutionId != plan.sourceExecutionId
        ) {
            return false
        }
        return when (val permissionSource = permission.source) {
            AcceptedRetryPermissionSource.Manual -> true

            is AcceptedRetryPermissionSource.Timer -> {
                val owner = persistedRestTimerOwner ?: return false
                owner.job === permissionSource.ownerJob &&
                    owner.job.isActive &&
                    coordinator.restTimerJob === owner.job &&
                    owner.transitionId == plan.transitionId &&
                    owner.sourceExecutionId == plan.sourceExecutionId &&
                    document.restDeadlineEpochMs == permissionSource.deadlineEpochMs &&
                    !document.isRestPaused &&
                    RestDeadlineCalculator.remainingSeconds(document, wallClockMillisProvider()) <= 0
            }

            is AcceptedRetryPermissionSource.Restored -> {
                val owner = restoredRuntimeOwner ?: return false
                owner.guardOwner == permissionSource.owner &&
                    owner.document == document &&
                    owner.documentVersion == activeRuntimeDocumentVersion &&
                    executionGuard.isRestoredRuntimeCurrent(permissionSource.owner)
            }
        }
    }

    private fun hasAcceptedRetrySnapshotAuthorityLocked(
        snapshot: AcceptedRetryGateSnapshot,
        requireTeardownReady: Boolean,
    ): Boolean {
        val document = activeRuntimeDocument ?: return false
        val plan = coordinator._restTransitionPlan.value as? RestTransitionPlan.AcceptedRetry ?: return false
        if (activeRuntimeDocumentVersion != snapshot.documentVersion ||
            document != snapshot.document ||
            plan != snapshot.plan ||
            document.restTransitionPlan != plan ||
            document.sourceStableSessionId != snapshot.gate.sourceStableSessionId ||
            document.sourceExecutionId != plan.sourceExecutionId ||
            document.sourceAttemptNumber + 1 != plan.nextAttemptNumber ||
            document.logicalSetKey != plan.logicalSetKey ||
            document.plannedSetId != plan.plannedSetId ||
            document.sourceExerciseIndex != plan.sourceCoordinates.exerciseIndex ||
            document.sourceSetIndex != plan.sourceCoordinates.setIndex ||
            coordinator.currentRoutineId != document.routineId ||
            coordinator.currentRoutineSessionId != document.routineSessionId ||
            coordinator._currentExerciseIndex.value != document.sourceExerciseIndex ||
            coordinator._currentSetIndex.value != document.sourceSetIndex ||
            coordinator._workoutState.value !is WorkoutState.Resting
        ) {
            return false
        }
        val readyProfile = userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready ?: return false
        if (readyProfile.profile.id != document.profileId ||
            captureExternalCommandInputStamp() != snapshot.externalCommandInputStamp
        ) {
            return false
        }
        val routine = coordinator._loadedRoutine.value ?: return false
        val routineExercise = routine.exercises.getOrNull(document.sourceExerciseIndex) ?: return false
        if (routine.id != document.routineId || routineExercise.id != document.routineExerciseId) return false

        val matchingStates = document.attemptStates.filter { it.logicalSetKey == plan.logicalSetKey }
        if (matchingStates.size != 1 ||
            document.attemptStates.groupingBy { it.logicalSetKey }.eachCount().values.any { it != 1 }
        ) {
            return false
        }
        val attemptState = matchingStates.single()
        if (attemptState.nextAttemptNumber != plan.nextAttemptNumber + 1 ||
            attemptState.acceptedDropCount !in 1..2
        ) {
            return false
        }
        val occurrenceOverlays = document.exerciseLoadOverlays.filter {
            it.routineExerciseId == document.routineExerciseId
        }
        if (occurrenceOverlays.size != 1 ||
            document.exerciseLoadOverlays.groupingBy { it.routineExerciseId }.eachCount().values.any { it != 1 } ||
            !sameMachineWeight(occurrenceOverlays.single().multiplier, plan.resultingExerciseMultiplier)
        ) {
            return false
        }

        when (snapshot.gate) {
            is RetryPersistenceGate.Live -> {
                val permission = acceptedRetryPermission ?: return false
                val source = snapshot.expectedSource ?: return false
                val completion = snapshot.completion ?: return false
                if (snapshot.restoredOwner != null ||
                    snapshot.permission != permission ||
                    permission.documentVersion != snapshot.documentVersion ||
                    permission.transitionId != plan.transitionId ||
                    permission.sourceExecutionId != plan.sourceExecutionId ||
                    !hasAcceptedRetryPermissionAuthorityLocked(permission, document, plan) ||
                    !executionGuard.isCurrent(source) ||
                    source.sessionId != document.sourceStableSessionId ||
                    source.executionId.toString() != document.sourceExecutionId ||
                    source.profileId != document.profileId ||
                    completion.lease != source ||
                    completion.reason != SetEndReason.STALL_FAILURE ||
                    completion.routineIdentity != reconstructLiveRoutineIdentity(document, plan) ||
                    completion.attemptNumber != document.sourceAttemptNumber ||
                    plan.nextAttemptNumber != completion.attemptNumber + 1 ||
                    attemptState.acceptedDropCount != completion.acceptedDropCount + 1
                ) {
                    return false
                }
            }

            is RetryPersistenceGate.Restored -> {
                val gate = snapshot.gate
                val context = gate.sourceContext
                val owner = snapshot.restoredOwner ?: return false
                val currentOwner = restoredRuntimeOwner ?: return false
                val permission = acceptedRetryPermission ?: return false
                if (currentOwner != owner ||
                    owner.document != document ||
                    owner.documentVersion != snapshot.documentVersion ||
                    owner.sourceContext != context ||
                    snapshot.permission != permission ||
                    permission.source != AcceptedRetryPermissionSource.Restored(owner.guardOwner) ||
                    !hasAcceptedRetryPermissionAuthorityLocked(permission, document, plan) ||
                    snapshot.configurationInputEpoch != owner.guardOwner.configurationInputEpoch ||
                    snapshot.recoveryPublicationEpoch != owner.guardOwner.recoverySupersessionEpoch ||
                    !executionGuard.isRestoredRuntimeCurrent(owner.guardOwner) ||
                    gate.actionIdentity != plan.actionIdentity() ||
                    executionGuard.currentLease != null ||
                    snapshot.expectedSource != null ||
                    context.sourceStableSessionId != document.sourceStableSessionId ||
                    context.sourceExecutionId != document.sourceExecutionId ||
                    context.profileId != document.profileId ||
                    context.routineIdentity != reconstructLiveRoutineIdentity(document, plan) ||
                    context.reason != SetEndReason.STALL_FAILURE ||
                    context.attemptNumber != document.sourceAttemptNumber ||
                    plan.nextAttemptNumber != context.attemptNumber + 1 ||
                    attemptState.acceptedDropCount != context.acceptedDropCount + 1 ||
                    context.plannedSetType != plan.logicalSetKey.setKind
                ) {
                    return false
                }
            }
        }
        if (!requireTeardownReady) return true
        return when (snapshot.gate) {
            is RetryPersistenceGate.Live -> executionGuard.machineTeardownState.value is MachineTeardownState.Ready

            is RetryPersistenceGate.Restored -> executionGuard.isRestoredTeardownReady(
                requireNotNull(snapshot.restoredOwner).guardOwner,
            )
        }
    }

    private fun reconstructLiveRoutineIdentity(
        document: ActiveWorkoutRuntimeDocument,
        plan: RestTransitionPlan.AcceptedRetry,
    ): RoutineExecutionIdentity? {
        val routine = coordinator._loadedRoutine.value ?: return null
        val exercise = routine.exercises.getOrNull(document.sourceExerciseIndex) ?: return null
        return RoutineExecutionIdentity(
            profileId = document.profileId,
            routineId = routine.id,
            routineSessionId = requireNotNull(coordinator.currentRoutineSessionId),
            routineExerciseId = exercise.id,
            logicalSetKey = plan.logicalSetKey,
            plannedSetId = plan.plannedSetId,
            exerciseIndex = document.sourceExerciseIndex,
            setIndex = document.sourceSetIndex,
        )
    }

    private suspend fun buildAndConsumeRetryStartRequestLocked(
        snapshot: AcceptedRetryGateSnapshot,
        plannedSets: List<com.devil.phoenixproject.domain.model.PlannedSet>,
    ): RetryRequestBuildResult {
        if (!hasAcceptedRetrySnapshotAuthorityLocked(snapshot, requireTeardownReady = true)) {
            return RetryRequestBuildResult.Wait
        }
        if (snapshot.gate is RetryPersistenceGate.Live &&
            executionGuard.persistenceClaimStatus(snapshot.gate.sourceStableSessionId) != PersistenceClaimStatus.PERSISTED
        ) {
            return RetryRequestBuildResult.Wait
        }
        val document = snapshot.document
        val plan = snapshot.plan
        val routine = coordinator._loadedRoutine.value ?: return RetryRequestBuildResult.Wait
        val exercise = routine.exercises.getOrNull(document.sourceExerciseIndex) ?: return RetryRequestBuildResult.Wait
        val coordinatePlans = plannedSets.filter { it.setNumber == document.sourceSetIndex }
        if (coordinatePlans.size > 1) {
            return RetryRequestBuildResult.FailClosed(
                failAcceptedRetryClosedLocked(plan, snapshot.documentVersion, snapshot.failClosedAuthority()),
            )
        }
        val plannedSet = coordinatePlans.singleOrNull()
        if (plannedSet?.id != plan.plannedSetId ||
            plannedSet?.routineExerciseId?.let { it != document.routineExerciseId } == true ||
            plannedSet?.setType?.let { it != plan.logicalSetKey.setKind } == true ||
            (plannedSet == null && semanticSetType(exercise, document.sourceSetIndex) != plan.logicalSetKey.setKind)
        ) {
            return RetryRequestBuildResult.FailClosed(
                failAcceptedRetryClosedLocked(plan, snapshot.documentVersion, snapshot.failClosedAuthority()),
            )
        }
        val programmedBase = RoutineSetWeightResolver(
            RoutineSetWeightRequest(
                exercise = exercise,
                setIndex = document.sourceSetIndex,
                currentPrKg = null,
                occurrenceMultiplier = 1f,
                manualAdjustmentPerCableKg = null,
            ),
        )
        val resolvedRetryWeight = RoutineSetWeightResolver(
            RoutineSetWeightRequest(
                exercise = exercise,
                setIndex = document.sourceSetIndex,
                currentPrKg = null,
                occurrenceMultiplier = plan.resultingExerciseMultiplier,
                manualAdjustmentPerCableKg = null,
            ),
        )
        val capturedProgrammedBase = snapshot.completion?.programmedBaseWeightPerCableKg
            ?: (snapshot.gate as? RetryPersistenceGate.Restored)
                ?.sourceContext
                ?.programmedBaseWeightPerCableKg
            ?: return RetryRequestBuildResult.FailClosed(
                failAcceptedRetryClosedLocked(plan, snapshot.documentVersion, snapshot.failClosedAuthority()),
            )
        val sourceTargetReps = snapshot.completion?.targetReps
            ?: (snapshot.gate as? RetryPersistenceGate.Restored)?.sourceContext?.targetReps
        if (plannedSet != null &&
            (
                plannedSet.targetReps != sourceTargetReps ||
                    plannedSet.targetWeightKg == null ||
                    !sameMachineWeight(plannedSet.targetWeightKg, capturedProgrammedBase)
                )
        ) {
            return RetryRequestBuildResult.FailClosed(
                failAcceptedRetryClosedLocked(plan, snapshot.documentVersion, snapshot.failClosedAuthority()),
            )
        }
        val sourceConfiguredStart = snapshot.completion?.configuredStartWeightPerCableKg
            ?: (snapshot.gate as? RetryPersistenceGate.Restored)
                ?.sourceContext
                ?.configuredStartWeightPerCableKg
            ?: return RetryRequestBuildResult.FailClosed(
                failAcceptedRetryClosedLocked(plan, snapshot.documentVersion, snapshot.failClosedAuthority()),
            )
        if (!sameMachineWeight(programmedBase, capturedProgrammedBase) ||
            !sameMachineWeight(resolvedRetryWeight, plan.resolvedWeightPerCableKg) ||
            !retryCandidateMatchesCurrentPolicy(
                exercise = exercise,
                percentage = plan.percentage,
                sourceConfiguredStartWeightPerCableKg = sourceConfiguredStart,
                programmedBaseWeightPerCableKg = capturedProgrammedBase,
                sourceCommandTemplate = snapshot.commandTemplate,
                expectedWeightPerCableKg = plan.resolvedWeightPerCableKg,
                expectedMultiplier = plan.resultingExerciseMultiplier,
            )
        ) {
            return RetryRequestBuildResult.FailClosed(
                failAcceptedRetryClosedLocked(plan, snapshot.documentVersion, snapshot.failClosedAuthority()),
            )
        }
        if (!capturedRetryCommandMatchesExercise(snapshot, exercise)) {
            return RetryRequestBuildResult.FailClosed(
                failAcceptedRetryClosedLocked(plan, snapshot.documentVersion, snapshot.failClosedAuthority()),
            )
        }
        val params = snapshot.commandTemplate.copy(weightPerCableKg = resolvedRetryWeight)
        if (validateWorkoutCommand(params).isFailure) {
            return RetryRequestBuildResult.FailClosed(
                failAcceptedRetryClosedLocked(plan, snapshot.documentVersion, snapshot.failClosedAuthority()),
            )
        }

        val cleared = document.copy(restTransitionPlan = null)
        if (!replaceRuntimeDocument(cleared)) return RetryRequestBuildResult.Wait
        if (!hasAcceptedRetrySnapshotAuthorityLocked(snapshot, requireTeardownReady = true)) {
            snapshot.restoredOwner?.let { owner ->
                reconcileRestoredDocumentCommitLocked(
                    owner = owner,
                    priorDocument = document,
                    priorDocumentVersion = snapshot.documentVersion,
                    priorPlan = plan,
                    committedDocument = cleared,
                )
            }
            return RetryRequestBuildResult.Wait
        }
        val startClaim = AcceptedRetryStartClaim(
            token = KmpUtils.randomUUID(),
            transitionId = plan.transitionId,
            sourceStableSessionId = document.sourceStableSessionId,
            sourceExecutionId = document.sourceExecutionId,
            attemptNumber = plan.nextAttemptNumber,
        )
        if (!acceptedRetryStartClaim.compareAndSet(null, startClaim)) {
            return RetryRequestBuildResult.Wait
        }
        setActiveRuntimeDocument(cleared)
        val clearedRestoredOwner = snapshot.restoredOwner?.copy(
            document = cleared,
            documentVersion = activeRuntimeDocumentVersion,
        )
        if (clearedRestoredOwner != null) {
            restoredRuntimeOwner = clearedRestoredOwner
        }
        coordinator._restTransitionPlan.value = null
        acceptedRetryPermission = null
        persistedRestTimerOwner = null
        coordinator.restTimerJob = null
        clearedRestoredOwner
            ?.guardOwner
            ?.let(::detachRestoredRestTimerIfOwned)
            ?.cancel()
        return RetryRequestBuildResult.Ready(
            RetryStartRequest(
                startClaim = startClaim,
                expectedSource = snapshot.expectedSource,
                restoredOwnerToken = clearedRestoredOwner?.guardOwner,
                sourceStableSessionId = document.sourceStableSessionId,
                sourceAttemptNumber = document.sourceAttemptNumber,
                requiresLivePersistedClaim = snapshot.gate is RetryPersistenceGate.Live,
                runtimeDocumentVersion = activeRuntimeDocumentVersion,
                recoveryPublicationEpoch = snapshot.recoveryPublicationEpoch,
                configurationInputEpoch = snapshot.configurationInputEpoch,
                externalCommandInputStamp = snapshot.externalCommandInputStamp,
                profileId = document.profileId,
                routineId = document.routineId,
                routineSessionId = document.routineSessionId,
                routineExerciseId = document.routineExerciseId,
                logicalSetKey = document.logicalSetKey,
                plannedSetId = document.plannedSetId,
                plannedTargetReps = plannedSet?.targetReps,
                plannedTargetWeightKg = plannedSet?.targetWeightKg,
                exerciseIndex = document.sourceExerciseIndex,
                setIndex = document.sourceSetIndex,
                attemptNumber = plan.nextAttemptNumber,
                acceptedDropCount = document.attemptStates.single { it.logicalSetKey == plan.logicalSetKey }.acceptedDropCount,
                percentage = plan.percentage,
                sourceConfiguredStartWeightPerCableKg = sourceConfiguredStart,
                sourceCommandTemplate = snapshot.commandTemplate,
                sourceIsTimed = snapshot.completion?.isTimed
                    ?: (snapshot.gate as RetryPersistenceGate.Restored).sourceContext.isTimed,
                sourceIsBodyweight = snapshot.completion?.isBodyweight
                    ?: (snapshot.gate as RetryPersistenceGate.Restored).sourceContext.isBodyweight,
                sourceIsCableExercise = snapshot.completion?.isCableExercise
                    ?: (snapshot.gate as RetryPersistenceGate.Restored).sourceContext.isCableExercise,
                sourcePhysicalCableCount = snapshot.completion?.physicalCableCount
                    ?: (snapshot.gate as? RetryPersistenceGate.Restored)?.sourceContext?.physicalCableCount,
                occurrenceMultiplier = plan.resultingExerciseMultiplier,
                expectedWeightPerCableKg = plan.resolvedWeightPerCableKg,
                programmedBaseWeightPerCableKg = programmedBase,
                params = params,
                rackBehaviorOverrides = snapshot.restoredOwner?.rackBehaviorOverrides
                    ?: coordinator._activeRackBehaviorOverrides.value.toMap(),
            ),
        )
    }

    private fun capturedRetryCommandMatchesExercise(
        snapshot: AcceptedRetryGateSnapshot,
        exercise: RoutineExercise,
    ): Boolean {
        val template = snapshot.commandTemplate
        val setIndex = snapshot.document.sourceSetIndex
        val setKind = snapshot.plan.logicalSetKey.setKind
        val expectedReps = exercise.setReps.getOrNull(setIndex) ?: exercise.reps
        val expectedAmrap = semanticSetType(exercise, setIndex) == SetType.AMRAP
        val isTimed = exercise.duration?.takeIf { it > 0 } != null
        val isBodyweight = exercise.exercise.isBodyweight
        val isCableExercise = !isBodyweight
        if (template.programMode != exercise.programMode ||
            template.reps != expectedReps ||
            template.echoLevel != exercise.getEchoLevelForSet(setIndex) ||
            template.eccentricLoad != exercise.eccentricLoad ||
            !sameMachineWeight(template.progressionRegressionKg, exercise.progressionKg) ||
            template.selectedExerciseId != exercise.exercise.id ||
            template.stopAtTop != exercise.stopAtTop ||
            template.stallDetectionEnabled != exercise.stallDetectionEnabled ||
            template.repCountTiming != exercise.repCountTiming ||
            template.isAMRAP != expectedAmrap ||
            expectedAmrap != (setKind == SetType.AMRAP) ||
            template.isJustLift ||
            template.useAutoStart ||
            template.warmupReps != Constants.DEFAULT_WARMUP_REPS
        ) {
            return false
        }
        val sourceMatches = snapshot.completion?.let { completion ->
            completion.programMode == template.programMode &&
                completion.plannedSetType == setKind &&
                completion.targetReps == (if (expectedAmrap) null else template.reps) &&
                !completion.isWarmup &&
                !completion.isEcho &&
                !completion.isJustLift &&
                !completion.isBodyweight &&
                completion.isBodyweight == isBodyweight &&
                !completion.isTimed &&
                completion.isTimed == isTimed &&
                completion.isAmrap == expectedAmrap &&
                completion.isCableExercise &&
                completion.isCableExercise == isCableExercise &&
                completion.physicalCableCount == exercise.exercise.preferredCableCount &&
                sameMachineWeight(completion.configuredStartWeightPerCableKg, template.weightPerCableKg) &&
                sameMachineWeight(completion.progressionKg, template.progressionRegressionKg)
        } ?: (snapshot.gate as? RetryPersistenceGate.Restored)?.sourceContext?.let { context ->
            context.commandTemplate == template &&
                context.programMode == template.programMode &&
                context.plannedSetType == setKind &&
                context.targetReps == (if (expectedAmrap) null else template.reps) &&
                !context.isWarmup &&
                !context.isEcho &&
                !context.isJustLift &&
                !context.isBodyweight &&
                context.isBodyweight == isBodyweight &&
                !context.isTimed &&
                context.isTimed == isTimed &&
                context.isAmrap == expectedAmrap &&
                context.isCableExercise &&
                context.isCableExercise == isCableExercise &&
                context.physicalCableCount == exercise.exercise.preferredCableCount &&
                sameMachineWeight(context.configuredStartWeightPerCableKg, template.weightPerCableKg) &&
                sameMachineWeight(context.progressionKg, template.progressionRegressionKg)
        }
        return sourceMatches == true
    }

    private fun retryCandidateMatchesCurrentPolicy(
        exercise: RoutineExercise,
        percentage: DropPercentage,
        sourceConfiguredStartWeightPerCableKg: Float,
        programmedBaseWeightPerCableKg: Float,
        sourceCommandTemplate: WorkoutParameters,
        expectedWeightPerCableKg: Float,
        expectedMultiplier: Float,
    ): Boolean {
        if (sourceCommandTemplate.programMode != ProgramMode.OldSchool ||
            sourceCommandTemplate.isJustLift ||
            sourceCommandTemplate.useAutoStart ||
            sourceCommandTemplate.isEchoMode ||
            sourceCommandTemplate.warmupReps != Constants.DEFAULT_WARMUP_REPS
        ) {
            return false
        }
        val configuration = try {
            dropSetConfigurationProvider(exercise)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
        val minimum = configuration.minimumWeightPerCableKg
        if (!configuration.enabled || minimum == null) return false
        val resolution = dropSetCandidateResolver.resolve(
            DropSetCandidateRequest(
                percentage = percentage,
                failedConfiguredStartWeightPerCableKg = sourceConfiguredStartWeightPerCableKg,
                programmedBaseWeightPerCableKg = programmedBaseWeightPerCableKg,
                minimumWeightPerCableKg = minimum,
                commandTemplate = sourceCommandTemplate,
            ),
        ) as? DropSetCandidateResolution.Valid ?: return false
        return sameMachineWeight(resolution.candidate.resolvedWeightPerCableKg, expectedWeightPerCableKg) &&
            sameMachineWeight(resolution.candidate.resultingExerciseMultiplier, expectedMultiplier) &&
            expectedWeightPerCableKg < sourceConfiguredStartWeightPerCableKg
    }

    private fun validateWorkoutCommand(params: WorkoutParameters): Result<Unit> = if (params.isEchoMode) {
        WorkoutCommandValidator.validateEchoControl(
            level = params.echoLevel,
            warmupReps = params.warmupReps,
            targetReps = params.reps,
            isJustLift = params.isJustLift,
            isAMRAP = params.isAMRAP,
            eccentricPct = params.eccentricLoad.percentage,
        )
    } else {
        WorkoutCommandValidator.validateProgramParams(params)
    }

    private suspend fun failAcceptedRetryClosed(
        plan: RestTransitionPlan.AcceptedRetry,
        documentVersion: Long,
        authority: RetryFailClosedAuthority,
    ) {
        val request = restTransitionMutex.withLock {
            failAcceptedRetryClosedLocked(plan, documentVersion, authority)
        }
        request?.let { enterManualRetryRecovery(it, expectedLease = it.expectedSource) }
    }

    private suspend fun failAcceptedRetryClosedLocked(
        plan: RestTransitionPlan.AcceptedRetry,
        documentVersion: Long,
        authority: RetryFailClosedAuthority,
    ): RetryStartRequest? {
        val document = activeRuntimeDocument ?: return null
        if (activeRuntimeDocumentVersion != documentVersion ||
            coordinator._restTransitionPlan.value != plan ||
            document.restTransitionPlan != plan
        ) {
            return null
        }
        var restoredOwner: RestoredRuntimeOwner? = null
        val expectedSource = when (authority) {
            is RetryFailClosedAuthority.Live -> authority.source.takeIf {
                executionGuard.isCurrent(it) && leaseMatchesRetrySource(it, document)
            } ?: return null

            is RetryFailClosedAuthority.Restored -> {
                val owner = restoredRuntimeOwner
                if (owner == null ||
                    owner.guardOwner != authority.owner ||
                    owner.document != document ||
                    owner.documentVersion != documentVersion ||
                    executionGuard.currentLease != null ||
                    !executionGuard.isRestoredRuntimeCurrent(authority.owner)
                ) {
                    return null
                }
                restoredOwner = owner
                null
            }
        }
        val cleared = document.copy(restTransitionPlan = null)
        if (!replaceRuntimeDocument(cleared)) return null
        setActiveRuntimeDocument(cleared)
        restoredOwner = restoredOwner?.copy(
            document = cleared,
            documentVersion = activeRuntimeDocumentVersion,
        )
        restoredOwner?.let { restoredRuntimeOwner = it }
        coordinator._restTransitionPlan.value = null
        acceptedRetryPermission = null
        persistedRestTimerOwner = null
        coordinator.restTimerJob = null
        return buildFailClosedRetryStartRequest(
            document = cleared,
            plan = plan,
            authority = authority,
            expectedSource = expectedSource,
            restoredOwner = restoredOwner,
        )
    }

    private fun buildFailClosedRetryStartRequest(
        document: ActiveWorkoutRuntimeDocument,
        plan: RestTransitionPlan.AcceptedRetry,
        authority: RetryFailClosedAuthority,
        expectedSource: ExecutionLease?,
        restoredOwner: RestoredRuntimeOwner?,
    ): RetryStartRequest = RetryStartRequest(
        expectedSource = expectedSource,
        restoredOwnerToken = restoredOwner?.guardOwner,
        sourceStableSessionId = document.sourceStableSessionId,
        sourceAttemptNumber = document.sourceAttemptNumber,
        requiresLivePersistedClaim = authority is RetryFailClosedAuthority.Live,
        runtimeDocumentVersion = activeRuntimeDocumentVersion,
        recoveryPublicationEpoch = authority.recoveryPublicationEpoch,
        configurationInputEpoch = restoredOwner?.guardOwner?.configurationInputEpoch ?: -1L,
        externalCommandInputStamp = restoredOwner?.externalCommandInputStamp
            ?: captureExternalCommandInputStamp(),
        profileId = document.profileId,
        routineId = document.routineId,
        routineSessionId = document.routineSessionId,
        routineExerciseId = document.routineExerciseId,
        logicalSetKey = document.logicalSetKey,
        plannedSetId = document.plannedSetId,
        plannedTargetReps = null,
        plannedTargetWeightKg = null,
        exerciseIndex = document.sourceExerciseIndex,
        setIndex = document.sourceSetIndex,
        attemptNumber = plan.nextAttemptNumber,
        acceptedDropCount = document.attemptStates.firstOrNull { it.logicalSetKey == plan.logicalSetKey }?.acceptedDropCount ?: 0,
        percentage = plan.percentage,
        sourceConfiguredStartWeightPerCableKg = restoredOwner?.sourceContext?.configuredStartWeightPerCableKg
            ?: coordinator._workoutParameters.value.weightPerCableKg,
        sourceCommandTemplate = restoredOwner?.sourceContext?.commandTemplate
            ?: coordinator._workoutParameters.value,
        sourceIsTimed = restoredOwner?.sourceContext?.isTimed ?: false,
        sourceIsBodyweight = restoredOwner?.sourceContext?.isBodyweight ?: false,
        sourceIsCableExercise = restoredOwner?.sourceContext?.isCableExercise ?: true,
        sourcePhysicalCableCount = restoredOwner?.sourceContext?.physicalCableCount
            ?: coordinator._loadedRoutine.value
                ?.exercises
                ?.getOrNull(document.sourceExerciseIndex)
                ?.exercise
                ?.preferredCableCount,
        occurrenceMultiplier = plan.resultingExerciseMultiplier,
        expectedWeightPerCableKg = plan.resolvedWeightPerCableKg,
        programmedBaseWeightPerCableKg = plan.resolvedWeightPerCableKg / plan.resultingExerciseMultiplier,
        params = (restoredOwner?.sourceContext?.commandTemplate ?: coordinator._workoutParameters.value).copy(
            weightPerCableKg = plan.resolvedWeightPerCableKg,
        ),
        rackBehaviorOverrides = restoredOwner?.rackBehaviorOverrides
            ?: coordinator._activeRackBehaviorOverrides.value.toMap(),
    )

    private fun enterManualRetryRecovery(
        request: RetryStartRequest,
        expectedLease: ExecutionLease?,
        allowNoCurrentAfterOwnedInvalidation: Boolean = false,
    ) {
        val restoredOwnerToken = request.restoredOwnerToken.takeIf { expectedLease == null }
        if (restoredOwnerToken != null &&
            (
                !executionGuard.isRestoredRuntimeCurrent(restoredOwnerToken) ||
                    !restoredManualRecoveryPresentationIsCurrent(request)
                )
        ) {
            releaseManualRetryRecoveryAuthority(request)
            return
        }
        val current = executionGuard.currentLease
        if (expectedLease == null && current != null) {
            releaseManualRetryRecoveryAuthority(request)
            return
        }
        if (expectedLease != null) {
            if (current == null && !allowNoCurrentAfterOwnedInvalidation) {
                releaseManualRetryRecoveryAuthority(request)
                return
            }
            if (current != null && !current.sameExecutionAs(expectedLease)) {
                releaseManualRetryRecoveryAuthority(request)
                return
            }
        }
        beforeManualRetryRecoveryPublishForTest?.invoke()
        val publicationClaim = executionGuard.beginRecoveryPublication(
            expectedLease = expectedLease,
            expectedSupersessionEpoch = request.recoveryPublicationEpoch,
            allowNoCurrentAfterOwnedInvalidation = allowNoCurrentAfterOwnedInvalidation,
            expectedRestoredOwner = restoredOwnerToken,
        )
        if (publicationClaim == null) {
            releaseManualRetryRecoveryAuthority(request)
            return
        }
        try {
            afterManualRetryRecoveryClaimForTest?.invoke()
            executionGuard.commitRecoveryPublication(
                claim = publicationClaim,
                candidateStillCurrent = {
                    restoredOwnerToken == null || restoredManualRecoveryPresentationIsCurrent(request)
                },
            ) {
                beforeManualRetryRecoveryCommitForTest?.invoke(request.params, request.rackBehaviorOverrides)
                coordinator._workoutParameters.value = request.params
                coordinator._activeRackBehaviorOverrides.value = request.rackBehaviorOverrides
                coordinator._workoutState.value = WorkoutState.Idle
                flowDelegate?.enterSetReady(request.exerciseIndex, request.setIndex)
            }
        } finally {
            releaseManualRetryRecoveryAuthority(request)
        }
    }

    private fun releaseManualRetryRecoveryAuthority(request: RetryStartRequest) {
        request.restoredOwnerToken?.let { owner ->
            revokeRestoredActionAuthorityLocked(owner)
        }
        clearAcceptedRetryStartClaim(request)
    }

    private fun restoredManualRecoveryPresentationIsCurrent(request: RetryStartRequest): Boolean {
        val ownerToken = request.restoredOwnerToken ?: return false
        val owner = restoredRuntimeOwner ?: return false
        val document = activeRuntimeDocument ?: return false
        val stamp = request.externalCommandInputStamp ?: return false
        val readyProfile = userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready
            ?: return false
        val routine = coordinator._loadedRoutine.value ?: return false
        val sourceExercise = routine.exercises.getOrNull(request.exerciseIndex) ?: return false
        return owner.guardOwner == ownerToken &&
            owner.document == document &&
            owner.documentVersion == request.runtimeDocumentVersion &&
            activeRuntimeDocumentVersion == request.runtimeDocumentVersion &&
            document.restTransitionPlan == null &&
            coordinator._restTransitionPlan.value == null &&
            coordinator._workoutState.value is WorkoutState.Resting &&
            (request.startClaim == null || ownsAcceptedRetryStartClaim(request)) &&
            readyProfile.profile.id == request.profileId &&
            stamp == owner.externalCommandInputStamp &&
            captureExternalCommandInputStamp() == stamp &&
            document.profileId == request.profileId &&
            document.routineId == request.routineId &&
            document.routineSessionId == request.routineSessionId &&
            document.routineExerciseId == request.routineExerciseId &&
            document.sourceStableSessionId == request.sourceStableSessionId &&
            document.logicalSetKey == request.logicalSetKey &&
            document.plannedSetId == request.plannedSetId &&
            document.sourceExerciseIndex == request.exerciseIndex &&
            document.sourceSetIndex == request.setIndex &&
            routine.profileId == request.profileId &&
            routine.id == request.routineId &&
            coordinator.currentRoutineId == request.routineId &&
            coordinator.currentRoutineSessionId == request.routineSessionId &&
            coordinator._currentExerciseIndex.value == request.exerciseIndex &&
            coordinator._currentSetIndex.value == request.setIndex &&
            sourceExercise.id == request.routineExerciseId
    }

    private fun failRetryStartAndRecover(
        request: RetryStartRequest,
        lease: ExecutionLease,
        priorWorkoutState: WorkoutState,
    ) {
        val invalidatedOwnedLease = failStart(lease, priorWorkoutState)
        enterManualRetryRecovery(
            request = request,
            expectedLease = lease,
            allowNoCurrentAfterOwnedInvalidation = invalidatedOwnedLease,
        )
    }

    private fun abortRetryStartBeforeConfig(
        request: RetryStartRequest,
        lease: ExecutionLease,
        priorWorkoutState: WorkoutState,
    ) {
        val teardown = executionGuard.machineTeardownState.value
        val teardownOwnsLease = when (teardown) {
            is MachineTeardownState.TearingDown -> teardown.executionId == lease.executionId
            is MachineTeardownState.RecoveryRequired -> teardown.executionId == lease.executionId
            MachineTeardownState.Ready -> false
        }
        if (teardownOwnsLease) {
            clearAcceptedRetryStartClaim(request)
        } else {
            failRetryStartAndRecover(request, lease, priorWorkoutState)
        }
    }

    private fun recoverRetryStartAfterConfigAttempt(
        request: RetryStartRequest,
        lease: ExecutionLease,
        priorWorkoutState: WorkoutState,
    ): Boolean {
        val teardownStarted = requestTeardownForTransition(lease, TeardownReason.RECOVERY) {
            failRetryStartAndRecover(request, lease, priorWorkoutState)
        }
        if (!teardownStarted) clearAcceptedRetryStartClaim(request)
        return teardownStarted
    }

    private fun ownsAcceptedRetryStartClaim(request: RetryStartRequest): Boolean {
        val claim = request.startClaim ?: return false
        return acceptedRetryStartClaim.value === claim
    }

    private fun clearAcceptedRetryStartClaim(request: RetryStartRequest) {
        request.startClaim?.let { acceptedRetryStartClaim.compareAndSet(it, null) }
    }

    private fun sameMachineWeight(first: Float, second: Float): Boolean = abs(first - second) < 0.001f

    private fun semanticSetType(exercise: RoutineExercise, setIndex: Int): SetType = when {
        exercise.setReps.getOrNull(setIndex) == null -> SetType.AMRAP
        exercise.isAMRAP && setIndex == exercise.setReps.lastIndex -> SetType.AMRAP
        else -> SetType.STANDARD
    }

    private fun retryRackCommandMatches(
        request: RetryStartRequest,
        capturedRackParams: WorkoutParameters,
        bleParams: WorkoutParameters,
        physicalCableCount: Int,
    ): Boolean {
        val activeIds = coordinator._activeRackItemIds.value
        val behaviorOverrides = coordinator._activeRackBehaviorOverrides.value
        if (activeIds != request.params.activeRackItemIds ||
            behaviorOverrides != request.rackBehaviorOverrides
        ) {
            return false
        }
        val selectedItems = equipmentRackRepository.rackItems.value
            .filter { it.enabled && it.id in activeIds }
        if (capturedRackParams.activeRackItemIds != request.params.activeRackItemIds ||
            !sameMachineWeight(capturedRackParams.externalAddedLoadKg, request.params.externalAddedLoadKg) ||
            !sameMachineWeight(capturedRackParams.counterweightKg, request.params.counterweightKg)
        ) {
            return false
        }
        val expectedAdjustment = applyEquipmentRackLoadUseCase.calculate(
            programmedWeightPerCableKg = request.expectedWeightPerCableKg,
            physicalCableCount = physicalCableCount,
            selectedItems = selectedItems,
            isEchoMode = request.params.isEchoMode,
            validatorMinimumPerCableKg = validatorSafeMinimum(request.params),
            behaviorOverrides = behaviorOverrides,
        )
        val cableCount = physicalCableCount.coerceIn(1, 2)
        val unclampedMachineWeight = request.expectedWeightPerCableKg -
            (expectedAdjustment.counterweightKg / cableCount)
        if (!request.params.isEchoMode &&
            !sameMachineWeight(unclampedMachineWeight, expectedAdjustment.adjustedMachineWeightPerCableKg)
        ) {
            return false
        }
        val expectedMachineWeight = if (request.params.isEchoMode) {
            request.expectedWeightPerCableKg
        } else {
            expectedAdjustment.adjustedMachineWeightPerCableKg
        }
        return bleParams.activeRackItemIds == request.params.activeRackItemIds &&
            sameMachineWeight(bleParams.externalAddedLoadKg, expectedAdjustment.externalAddedLoadKg) &&
            sameMachineWeight(bleParams.counterweightKg, expectedAdjustment.counterweightKg) &&
            sameMachineWeight(bleParams.weightPerCableKg, expectedMachineWeight)
    }

    private fun retryStartStillAuthorizedLocked(
        request: RetryStartRequest,
        lease: ExecutionLease,
        exercise: RoutineExercise?,
        plannedSets: List<com.devil.phoenixproject.domain.model.PlannedSet>,
    ): Boolean {
        val document = activeRuntimeDocument ?: return false
        val state = coordinator._workoutState.value
        if (!ownsAcceptedRetryStartClaim(request) ||
            !executionGuard.isCurrent(lease) ||
            captureExternalCommandInputStamp() != request.externalCommandInputStamp ||
            executionGuard.machineTeardownState.value !is MachineTeardownState.Ready ||
            activeRuntimeDocumentVersion != request.runtimeDocumentVersion ||
            document.restTransitionPlan != null ||
            coordinator._restTransitionPlan.value != null ||
            acceptedRetryPermission != null ||
            document.profileId != request.profileId ||
            document.routineId != request.routineId ||
            document.routineSessionId != request.routineSessionId ||
            document.routineExerciseId != request.routineExerciseId ||
            document.sourceStableSessionId != request.sourceStableSessionId ||
            document.logicalSetKey != request.logicalSetKey ||
            document.plannedSetId != request.plannedSetId ||
            document.sourceExerciseIndex != request.exerciseIndex ||
            document.sourceSetIndex != request.setIndex ||
            coordinator.currentRoutineId != request.routineId ||
            coordinator.currentRoutineSessionId != request.routineSessionId ||
            coordinator._currentExerciseIndex.value != request.exerciseIndex ||
            coordinator._currentSetIndex.value != request.setIndex ||
            coordinator._currentWarmupSetIndex.value >= 0 ||
            (state !is WorkoutState.Initializing && state !is WorkoutState.Countdown)
        ) {
            return false
        }
        val readyProfile = userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready ?: return false
        val routine = coordinator._loadedRoutine.value ?: return false
        if (readyProfile.profile.id != request.profileId ||
            routine.id != request.routineId ||
            exercise == null ||
            exercise.id != request.routineExerciseId ||
            routine.exercises.getOrNull(request.exerciseIndex) !== exercise
        ) {
            return false
        }
        val coordinatePlans = plannedSets.filter { it.setNumber == request.setIndex }
        if (coordinatePlans.size > 1) return false
        val plannedSet = coordinatePlans.singleOrNull()
        if (plannedSet?.id != request.plannedSetId ||
            plannedSet?.routineExerciseId?.let { it != request.routineExerciseId } == true ||
            plannedSet?.setType?.let { it != request.logicalSetKey.setKind } == true ||
            plannedSet?.targetReps != request.plannedTargetReps ||
            !sameNullableMachineWeight(plannedSet?.targetWeightKg, request.plannedTargetWeightKg) ||
            (plannedSet == null && semanticSetType(exercise, request.setIndex) != request.logicalSetKey.setKind)
        ) {
            return false
        }
        val attemptStates = document.attemptStates.filter { it.logicalSetKey == request.logicalSetKey }
        if (attemptStates.size != 1 ||
            attemptStates.single().nextAttemptNumber != request.attemptNumber + 1 ||
            attemptStates.single().acceptedDropCount != request.acceptedDropCount ||
            document.attemptStates.groupingBy { it.logicalSetKey }.eachCount().values.any { it != 1 }
        ) {
            return false
        }
        val overlays = document.exerciseLoadOverlays.filter { it.routineExerciseId == request.routineExerciseId }
        if (overlays.size != 1 ||
            !sameMachineWeight(overlays.single().multiplier, request.occurrenceMultiplier) ||
            document.exerciseLoadOverlays.groupingBy { it.routineExerciseId }.eachCount().values.any { it != 1 }
        ) {
            return false
        }
        val freshBase = RoutineSetWeightResolver(
            RoutineSetWeightRequest(exercise, request.setIndex, currentPrKg = null),
        )
        val freshRetryWeight = RoutineSetWeightResolver(
            RoutineSetWeightRequest(
                exercise = exercise,
                setIndex = request.setIndex,
                currentPrKg = null,
                occurrenceMultiplier = request.occurrenceMultiplier,
            ),
        )
        return sameMachineWeight(freshBase, request.programmedBaseWeightPerCableKg) &&
            sameMachineWeight(freshRetryWeight, request.expectedWeightPerCableKg) &&
            sameMachineWeight(request.params.weightPerCableKg, request.expectedWeightPerCableKg) &&
            retryCandidateMatchesCurrentPolicy(
                exercise = exercise,
                percentage = request.percentage,
                sourceConfiguredStartWeightPerCableKg = request.sourceConfiguredStartWeightPerCableKg,
                programmedBaseWeightPerCableKg = request.programmedBaseWeightPerCableKg,
                sourceCommandTemplate = request.sourceCommandTemplate,
                expectedWeightPerCableKg = request.expectedWeightPerCableKg,
                expectedMultiplier = request.occurrenceMultiplier,
            ) &&
            retryCommandMatchesCurrentExercise(request, exercise)
    }

    /** Called only while the guard holds its teardown lock; do not re-enter the guard here. */
    private fun restoredRetryCandidateStillCurrent(request: RetryStartRequest): Boolean {
        val token = request.restoredOwnerToken ?: return false
        val owner = restoredRuntimeOwner ?: return false
        val document = activeRuntimeDocument ?: return false
        return owner.guardOwner == token &&
            owner.document == document &&
            owner.documentVersion == activeRuntimeDocumentVersion &&
            document.restTransitionPlan == null &&
            coordinator._restTransitionPlan.value == null &&
            acceptedRetryPermission == null &&
            ownsAcceptedRetryStartClaim(request) &&
            document.profileId == request.profileId &&
            document.routineId == request.routineId &&
            document.routineSessionId == request.routineSessionId &&
            document.routineExerciseId == request.routineExerciseId &&
            document.sourceStableSessionId == request.sourceStableSessionId &&
            document.logicalSetKey == request.logicalSetKey &&
            document.plannedSetId == request.plannedSetId &&
            document.sourceExerciseIndex == request.exerciseIndex &&
            document.sourceSetIndex == request.setIndex &&
            coordinator.currentRoutineId == request.routineId &&
            coordinator.currentRoutineSessionId == request.routineSessionId &&
            coordinator._currentExerciseIndex.value == request.exerciseIndex &&
            coordinator._currentSetIndex.value == request.setIndex &&
            coordinator._workoutState.value is WorkoutState.Resting
    }

    private fun retryCommandMatchesCurrentExercise(
        request: RetryStartRequest,
        exercise: RoutineExercise,
    ): Boolean {
        val expectedAmrap = semanticSetType(exercise, request.setIndex) == SetType.AMRAP
        val expectedReps = exercise.setReps.getOrNull(request.setIndex) ?: exercise.reps
        val isTimed = exercise.duration?.takeIf { it > 0 } != null
        val isBodyweight = exercise.exercise.isBodyweight
        return request.sourceIsTimed == isTimed &&
            request.sourceIsBodyweight == isBodyweight &&
            request.sourceIsCableExercise == !isBodyweight &&
            request.sourcePhysicalCableCount == exercise.exercise.preferredCableCount &&
            request.params.programMode == exercise.programMode &&
            request.params.reps == expectedReps &&
            request.params.echoLevel == exercise.getEchoLevelForSet(request.setIndex) &&
            request.params.eccentricLoad == exercise.eccentricLoad &&
            sameMachineWeight(request.params.progressionRegressionKg, exercise.progressionKg) &&
            request.params.selectedExerciseId == exercise.exercise.id &&
            request.params.stopAtTop == exercise.stopAtTop &&
            request.params.stallDetectionEnabled == exercise.stallDetectionEnabled &&
            request.params.repCountTiming == exercise.repCountTiming &&
            request.params.isAMRAP == expectedAmrap &&
            request.params.isJustLift.not() &&
            request.params.useAutoStart.not() &&
            request.params.warmupReps == Constants.DEFAULT_WARMUP_REPS
    }

    private fun sameNullableMachineWeight(first: Float?, second: Float?): Boolean = when {
        first == null -> second == null
        second == null -> false
        else -> sameMachineWeight(first, second)
    }

    private suspend fun hasRetryPersistenceAuthority(request: RetryStartRequest): Boolean {
        if (!ownsAcceptedRetryStartClaim(request) ||
            (
                request.requiresLivePersistedClaim &&
                    executionGuard.persistenceClaimStatus(request.sourceStableSessionId) != PersistenceClaimStatus.PERSISTED
                )
        ) {
            return false
        }
        currentCoroutineContext().ensureActive()
        val durable = completedSetRepository.isAttemptDurable(
            stableSessionId = request.sourceStableSessionId,
            key = request.logicalSetKey,
            attemptNumber = request.sourceAttemptNumber,
        )
        currentCoroutineContext().ensureActive()
        return ownsAcceptedRetryStartClaim(request) &&
            durable &&
            (
                !request.requiresLivePersistedClaim ||
                    executionGuard.persistenceClaimStatus(request.sourceStableSessionId) == PersistenceClaimStatus.PERSISTED
                )
    }

    private suspend fun replaceRuntimeDocument(document: ActiveWorkoutRuntimeDocument): Boolean {
        val pendingReplace = runtimeCleanupCandidateRef.value
            ?.takeIf { origin -> origin.document != document }
            ?.let { origin ->
                val originEngineVersion = when (val source = origin.source) {
                    is RuntimeCleanupSource.ActiveDocument -> source.engineVersion

                    is RuntimeCleanupSource.RestoredOwner -> source.engineVersion

                    is RuntimeCleanupSource.PendingEngineReplace -> source.expectedPublishedEngineVersion

                    is RuntimeCleanupSource.PendingInitialReplace,
                    is RuntimeCleanupSource.ColdHandle,
                    -> null
                }
                PendingRuntimeReplaceCandidate(
                    candidateToken = pendingRuntimeReplaceCandidateSequence.incrementAndGet(),
                    document = document,
                    origin = origin,
                    expectedPublishedEngineVersion = originEngineVersion?.plus(1L),
                )
            }
            ?.takeIf { candidate -> pendingRuntimeReplaceCandidateRef.compareAndSet(null, candidate) }
        if (pendingReplace != null) {
            handoffPendingRuntimeReplaceToCleanup(pendingReplace)
        }
        return try {
            activeWorkoutRuntimeRepository.replace(document.profileId, document.routineSessionId, document)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        } finally {
            if (pendingReplace != null) {
                pendingRuntimeReplaceCandidateRef.compareAndSet(pendingReplace, null)
            }
        }
    }

    private enum class RuntimeDocumentCommitStatus { COMMITTED, UNCHANGED_PRIOR, DIVERGED, UNKNOWN }

    private suspend fun runtimeDocumentCommitStatus(
        candidate: ActiveWorkoutRuntimeDocument,
        prior: ActiveWorkoutRuntimeDocument,
    ): RuntimeDocumentCommitStatus = withContext(NonCancellable) {
        try {
            val loaded = activeWorkoutRuntimeRepository.load(
                profileId = candidate.profileId,
                routineSessionId = candidate.routineSessionId,
            ) as? ActiveWorkoutRuntimeLoadResult.Loaded
            when (loaded?.document) {
                candidate -> RuntimeDocumentCommitStatus.COMMITTED
                prior -> RuntimeDocumentCommitStatus.UNCHANGED_PRIOR
                else -> RuntimeDocumentCommitStatus.DIVERGED
            }
        } catch (_: Throwable) {
            RuntimeDocumentCommitStatus.UNKNOWN
        }
    }

    private fun retireRestoredActionAuthorityPreservingCancellation(owner: RestoredRuntimeOwnerToken) {
        try {
            revokeRestoredActionAuthorityLocked(owner)
        } catch (_: Throwable) {
            // Best-effort retirement must never replace the causal cancellation.
        }
    }

    private fun setActiveRuntimeDocument(document: ActiveWorkoutRuntimeDocument) {
        activeRuntimeDocument = document
        activeRuntimeDocumentVersion++
        runtimeCleanupCandidateRef.value = RuntimeCleanupCandidate(
            document = document,
            source = RuntimeCleanupSource.ActiveDocument(
                engineVersion = activeRuntimeDocumentVersion,
                sourceExecutionId = document.sourceExecutionId,
                sourceStableSessionId = document.sourceStableSessionId,
            ),
        )
    }

    private fun canonicalAttemptStates(states: List<PlannedSetAttemptState>): List<PlannedSetAttemptState> = states.associateBy { it.logicalSetKey }.values.toList()

    private fun canonicalOverlays(overlays: List<ExerciseLoadOverlay>): List<ExerciseLoadOverlay> = overlays.associateBy { it.routineExerciseId }.values.toList()

    private fun ActiveWorkoutRuntimeDocument.matchesRoutineIdentity(
        profileId: String,
        routineId: String?,
        routineSessionId: String?,
    ): Boolean = this.profileId == profileId &&
        this.routineId == routineId &&
        this.routineSessionId == routineSessionId

    internal fun occurrenceLoadMultiplier(routineExerciseId: String): Float {
        val document = activeRuntimeDocument ?: return 1f
        val profile = (userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready)?.profile ?: return 1f
        val routine = coordinator._loadedRoutine.value ?: return 1f
        val routineId = coordinator.currentRoutineId ?: return 1f
        val routineSessionId = coordinator.currentRoutineSessionId ?: return 1f
        if (
            !document.matchesRoutineIdentity(profile.id, routineId, routineSessionId) ||
            routine.id != routineId ||
            routine.exercises.count { it.id == routineExerciseId } != 1 ||
            document.exerciseLoadOverlays.map { it.routineExerciseId }.distinct().size !=
            document.exerciseLoadOverlays.size
        ) {
            return 1f
        }
        return document.exerciseLoadOverlays
            .singleOrNull { it.routineExerciseId == routineExerciseId }
            ?.multiplier
            ?: 1f
    }

    internal fun resolveOccurrenceSetWeight(exercise: RoutineExercise, setIndex: Int): Float = RoutineSetWeightResolver(
        RoutineSetWeightRequest(
            exercise = exercise,
            setIndex = setIndex,
            currentPrKg = null,
            occurrenceMultiplier = occurrenceLoadMultiplier(exercise.id),
        ),
    )
    private val repFreshnessGate: RepNotificationFreshnessGate
        get() = executionGuard.repFreshnessGate

    val machineTeardownState: StateFlow<MachineTeardownState>
        get() = executionGuard.machineTeardownState

    internal fun currentExecutionLeaseForTest(): ExecutionLease = requireNotNull(executionGuard.currentLease)

    internal fun currentExecutionLeaseOrNull(): ExecutionLease? = executionGuard.currentLease

    internal fun claimedCompletion(lease: ExecutionLease): SetExecutionCompletion? = executionGuard.claimedCompletion(lease)

    internal var completionJobAttachCountForTest: Int = 0
        private set
    internal var lastInitialRestPlanInstallResultForTest: InitialRestPlanInstallResult? = null
        private set
    internal var afterDurableRestPlanClearForTest: (suspend () -> Unit)? = null
    internal var afterAcceptedRetryPlanConsumedForTest: (suspend () -> Unit)? = null
    internal var beforeAcceptedRetryGateCaptureForTest: (suspend () -> Unit)? = null
    internal var beforeAcceptedRetryPlanConsumeForTest: (suspend () -> Unit)? = null
    internal var beforeAcceptedRetryConfigAuthorityForTest: (suspend () -> Unit)? = null
    internal var afterAcceptedRetryFinalAuthorityForTest: (suspend () -> Unit)? = null
    internal var afterAcceptedRetryConfigSentForTest: (suspend () -> Unit)? = null
    internal var afterAcceptedRetryActivatedForTest: (suspend () -> Unit)? = null
    internal var beforeManualRetryRecoveryPublishForTest: (() -> Unit)? = null
    internal var afterManualRetryRecoveryClaimForTest: (() -> Unit)? = null
    internal var beforeManualRetryRecoveryCommitForTest: ((WorkoutParameters, Map<String, RackItemBehavior>) -> Unit)? = null
    internal var beforeRestoredNormalDispatchForTest: (() -> Unit)? = null
    internal var beforeRestoredOwnerCompareAndClearForTest: ((RestoredRuntimeOwnerToken) -> Unit)? = null
    internal var afterRestoredActionProfileAuthorityFailureForTest: (suspend () -> Unit)? = null
    internal var beforeRestoredRestTimerTickPublishForTest: (suspend (RestoredRuntimeOwnerToken, Int) -> Unit)? = null
    internal var afterRestoredRestTimerProfileAuthorityFailureForTest: (suspend () -> Unit)? = null
    internal var afterRestoredRestTimerZeroPublishForTest: (suspend (RestoredRuntimeOwnerToken) -> Unit)? = null
    internal var beforeRestoredRestTimerOwnerCompareAndClearForTest: ((RestoredRuntimeOwnerToken) -> Unit)? = null
    internal var afterRestoredRestTimerControlCaptureForTest: (suspend () -> Unit)? = null
    internal var afterCleanupDisposalBoundaryForTest: (() -> Unit)? = null
    internal var beforeCleanupGuardCloseForTest: (() -> Unit)? = null

    internal fun clearAcceptedRetryStartClaimForTest(): Boolean {
        val claim = acceptedRetryStartClaim.value ?: return false
        return acceptedRetryStartClaim.compareAndSet(claim, null)
    }
    internal var pendingResetStartInterceptorForTest: ((resume: () -> Unit) -> Unit)? = null
    internal var beforeExecutionBeginForTest: (() -> Unit)? = null
    internal var beforeQueuedSuccessorMachineConfigurationForTest: (() -> Unit)? = null
    internal var beforeMachineConfigurationClaimForTest: (() -> Unit)? = null
    internal var beforeRestTransitionNavigationClaimForTest: (suspend () -> Unit)? = null
    internal var beforeRestTransitionNavigationResolutionForTest: (suspend () -> Unit)? = null
    internal var afterRestTransitionNavigationResolutionForTest: (suspend () -> Unit)? = null
    internal var onRestTransitionNavigationCacheReadForTest: (() -> Unit)? = null
    internal var onRestTransitionNavigationContextReadForTest: (() -> Unit)? = null
    internal var afterPersistedRestTimerClaimForTest: (suspend () -> Unit)? = null
    internal var beforePersistedRestTimerTickPublishForTest: (suspend () -> Unit)? = null
    internal var afterPersistedRestTimerProfileAuthorityFailureForTest: (suspend () -> Unit)? = null
    internal var beforePersistedRestTimerActionForTest: (suspend () -> Unit)? = null
    internal var afterPersistedRestTimerActionAuthorizationForTest: (suspend () -> Unit)? = null
    internal var afterResetCleanupTokenCaptureForTest: (() -> Unit)? = null
    internal var recoveryPreparationCallsForTest: Int = 0
    internal var afterRuntimeResumeSelectionForTest: (suspend (RoutineResumeHandle.Persisted) -> Unit)? = null
    internal var beforeRestoredRuntimeOwnerPublicationForTest: (() -> Unit)? = null
    internal var beforeRestoredRoutineFlowPublicationForTest: (() -> Unit)? = null

    internal suspend fun resolveRestTransitionNavigationForTest(plan: RestTransitionPlan.NormalAdvance): Boolean = resolveNavigationOnce(plan) != null

    internal fun activeRuntimeDocumentForTest(): ActiveWorkoutRuntimeDocument? = activeRuntimeDocument

    internal fun currentRestoredRuntimeOwnerForTest(): RestoredRuntimeOwnerToken? = restoredRuntimeOwner?.guardOwner

    internal fun currentRestoredAcceptedRetryPermissionOwnerForTest(): RestoredRuntimeOwnerToken? = (acceptedRetryPermission?.source as? AcceptedRetryPermissionSource.Restored)?.owner

    internal fun currentRestoredRestTimerOwnerForTest(): RestoredRuntimeOwnerToken? = restoredRestTimerOwnerRef.value?.guardOwner

    internal fun currentRestoredRestTimerJobForTest(): Job? = restoredRestTimerOwnerRef.value?.job

    internal fun currentRestoredRestTimerDeadlineElapsedRealtimeMsForTest(): Long? = restoredRestTimerOwnerRef.value?.monotonicDeadlineElapsedRealtimeMs

    internal fun mutateActiveRuntimeDocumentForTest(
        transform: (ActiveWorkoutRuntimeDocument) -> ActiveWorkoutRuntimeDocument,
    ) {
        val updated = transform(requireNotNull(activeRuntimeDocument))
        setActiveRuntimeDocument(updated)
        coordinator._restTransitionPlan.value = updated.restTransitionPlan
    }

    internal fun hasRetainedWorkoutExitSnapshotForTest(sessionId: String): Boolean = exitSnapshotStore.findBySessionId(sessionId) != null

    internal fun hasPendingTeardownReadyContinuationForTest(lease: ExecutionLease): Boolean = pendingTeardownReadyContinuation.value?.lease?.sameExecutionAs(lease) == true ||
        pendingResetStart.value?.owner?.lease?.sameExecutionAs(lease) == true

    internal fun discardTeardownReadyContinuationForTest(lease: ExecutionLease) {
        discardTeardownReadyContinuation(lease)
    }

    private val dangerZoneCountdownGate = DangerZoneCountdownGate()

    internal fun primeDangerZoneCountdownForTest(lease: ExecutionLease, startTimeMs: Long) {
        if (!executionGuard.isCurrent(lease)) return
        dangerZoneCountdownGate.tryPrime(lease, startTimeMs)
        if (!executionGuard.isCurrent(lease)) {
            dangerZoneCountdownGate.clear(lease)
        }
    }

    private fun consumeDangerZoneCountdownOverride(lease: ExecutionLease): Long? = dangerZoneCountdownGate.consume(lease)

    private fun clearDangerZoneCountdownOverride(lease: ExecutionLease) {
        dangerZoneCountdownGate.clear(lease)
    }

    private fun ExecutionLease.sameExecutionAs(other: ExecutionLease): Boolean = executionId == other.executionId && sessionId == other.sessionId

    private fun installBiomechanicsContext(lease: ExecutionLease): Boolean {
        val context = ExecutionBiomechanicsContext(
            lease = lease,
            engine = BiomechanicsEngine(
                coordinator.vbtRuntimeSettings.value.velocityLossThresholdPercent,
            ),
        )
        return executionGuard.commitIfCurrent(lease) {
            biomechanicsContext.value = context
            coordinator.installBiomechanicsEngine(context.engine)
        }
    }

    private fun biomechanicsContextFor(lease: ExecutionLease): ExecutionBiomechanicsContext? = biomechanicsContext.value?.takeIf { it.lease.sameExecutionAs(lease) }

    private fun resetBiomechanicsContext(lease: ExecutionLease) {
        val context = biomechanicsContextFor(lease) ?: return
        val resetWhileCurrent = executionGuard.commitIfCurrent(lease) {
            if (biomechanicsContext.value === context) {
                context.velocityThresholdAlertEmitted = false
                context.consecutiveThresholdReps = 0
                coordinator.resetBiomechanicsEngine(context.engine)
            }
        }
        if (!resetWhileCurrent) {
            // The engine is execution-local. An identity-checked reset cannot touch a newer engine.
            context.velocityThresholdAlertEmitted = false
            context.consecutiveThresholdReps = 0
            coordinator.resetBiomechanicsEngine(context.engine)
        }
    }

    private fun detachBiomechanicsContext(lease: ExecutionLease) {
        val context = biomechanicsContextFor(lease) ?: return
        if (!biomechanicsContext.compareAndSet(context, null)) return
        coordinator.detachBiomechanicsEngine(
            expected = context.engine,
            replacement = BiomechanicsEngine(
                coordinator.vbtRuntimeSettings.value.velocityLossThresholdPercent,
            ),
        )
    }

    internal fun isCurrentExecution(lease: ExecutionLease): Boolean = executionGuard.isCurrent(lease)

    private inline fun ifCurrent(
        lease: ExecutionLease,
        transition: String,
        block: () -> Unit,
    ) {
        if (executionGuard.isCurrent(lease)) {
            block()
        } else {
            logSuppressedStateWrite(lease, transition)
        }
    }

    private fun hasCurrentAuthority(lease: ExecutionLease, transition: String): Boolean {
        var current = false
        ifCurrent(lease, transition) { current = true }
        return current
    }

    private fun hasExpectedAuthority(lease: ExecutionLease?, transition: String): Boolean = lease == null || hasCurrentAuthority(lease, transition)

    private fun hasAutoStartAuthority(expectedLease: ExecutionLease?, transition: String): Boolean {
        if (expectedLease != null) return hasCurrentAuthority(expectedLease, transition)
        return executionGuard.currentLease == null
    }

    private fun logSuppressedStateWrite(lease: ExecutionLease, transition: String) {
        logExecutionEvent(
            LogEventType.WORKOUT_EXECUTION,
            "executionId=${lease.executionId},sessionId=${lease.sessionId}," +
                "transition=suppressed,attempted=$transition",
        )
    }

    private fun launchCompletionJob(
        lease: ExecutionLease,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        if (!hasCurrentAuthority(lease, "completion_job_attach")) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                executionGuard.clearCompletionJobIfOwned(lease)
            }
        }
        if (executionGuard.attachCompletionJob(lease, job)) {
            completionJobAttachCountForTest++
            job.start()
        } else {
            job.cancel()
        }
    }

    private fun launchPresentationContinuation(
        lease: ExecutionLease?,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        if (lease == null) {
            scope.launch(block = block)
        } else {
            launchCompletionJob(lease, block)
        }
    }

    private fun logExecutionEvent(eventType: String, details: String) {
        connectionLogRepository.info(eventType, "Workout execution transition", details = details)
    }

    private fun logTeardownElapsed(lease: ExecutionLease, reason: TeardownReason, elapsedMs: Long) {
        logExecutionEvent(
            LogEventType.WORKOUT_TEARDOWN,
            "executionId=${lease.executionId},sessionId=${lease.sessionId},reason=$reason,elapsedMs=$elapsedMs",
        )
    }

    private fun beginMachineTeardown(
        lease: ExecutionLease,
        reason: TeardownReason,
        attempt: Int = 1,
        afterReady: (() -> Unit)? = null,
    ): Boolean {
        if (!lease.requiresMachine) {
            if (!executionGuard.isCurrent(lease)) return false
            executionGuard.releaseQueuedSuccessorSetup(lease)
            afterReady?.invoke()
            return true
        }
        val request = DeferredMachineConfigurationTeardown(
            lease = lease,
            reason = reason,
            attempt = attempt,
            afterReady = afterReady,
        )
        if (!deferredMachineConfigurationTeardown.compareAndSet(null, request)) return false
        return when (executionGuard.requestTeardown(lease, attempt)) {
            MachineTeardownClaimResult.Begun -> {
                deferredMachineConfigurationTeardown.compareAndSet(request, null)
                launchClaimedMachineTeardown(request)
                true
            }

            MachineTeardownClaimResult.DeferredUntilConfigurationCompletes -> true

            MachineTeardownClaimResult.Rejected -> {
                deferredMachineConfigurationTeardown.compareAndSet(request, null)
                false
            }
        }
    }

    private fun launchClaimedMachineTeardown(request: DeferredMachineConfigurationTeardown) {
        val afterReady = request.afterReady
        if (afterReady != null) {
            pendingTeardownReadyContinuation.value = PendingTeardownReadyContinuation(request.lease, afterReady)
        } else {
            discardTeardownReadyContinuation(request.lease)
        }
        launchMachineTeardownReset(request.lease, request.reason)
    }

    private fun takeDeferredMachineConfigurationTeardown(
        lease: ExecutionLease,
    ): DeferredMachineConfigurationTeardown? {
        while (true) {
            val pending = deferredMachineConfigurationTeardown.value ?: return null
            if (!pending.lease.sameExecutionAs(lease)) return null
            if (deferredMachineConfigurationTeardown.compareAndSet(pending, null)) return pending
        }
    }

    private fun launchMachineTeardownReset(
        lease: ExecutionLease,
        reason: TeardownReason,
    ) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val startedAt = elapsedRealtimeProvider()
            var failureReason: TeardownFailureReason? = null
            try {
                withTimeout(BleConstants.GATT_OPERATION_TIMEOUT_MS) {
                    bleRepository.stopWorkout().getOrThrow()
                }
                if (bleRepository.connectionState.value !is ConnectionState.Connected) {
                    failureReason = TeardownFailureReason.DISCONNECTED
                }
            } catch (error: TimeoutCancellationException) {
                failureReason = TeardownFailureReason.TIMED_OUT
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failureReason = TeardownFailureReason.RESET_FAILED
            } finally {
                bleRepository.stopPolling()
                executionGuard.clearTeardownJobIfOwned(lease)
                logTeardownElapsed(lease, reason, elapsedRealtimeProvider() - startedAt)
            }
            if (failureReason != null) {
                executionGuard.markRecoveryRequired(lease, failureReason)
            } else {
                val ready = executionGuard.markTeardownReady(lease)
                if (ready) {
                    val resetOwner = resetMachineTeardownOwner.value
                        ?.takeIf { it.lease.sameExecutionAs(lease) }
                    if (resetOwner != null && resetMachineTeardownOwner.compareAndSet(resetOwner, null)) {
                        takePendingResetStart(resetOwner)?.let { pending ->
                            val resume = { startPendingResetSuccessor(pending) }
                            pendingResetStartInterceptorForTest?.invoke(resume) ?: resume()
                        }
                    }
                    takeTeardownReadyContinuation(lease)?.invoke()
                    if (executionGuard.isCurrent(lease)) {
                        scope.launch { tryStartCurrentAcceptedRetryLive() }
                    }
                } else {
                    discardTeardownReadyContinuation(lease)
                }
            }
        }
        if (executionGuard.attachTeardownJob(lease, job)) {
            job.start()
        } else {
            job.cancel()
        }
    }

    private fun launchRestoredMachineTeardownReset(guardOwner: RestoredRuntimeOwnerToken) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            var failureReason: TeardownFailureReason? = null
            try {
                withTimeout(BleConstants.GATT_OPERATION_TIMEOUT_MS) {
                    bleRepository.stopWorkout().getOrThrow()
                }
                if (bleRepository.connectionState.value !is ConnectionState.Connected) {
                    failureReason = TeardownFailureReason.DISCONNECTED
                }
            } catch (error: TimeoutCancellationException) {
                failureReason = TeardownFailureReason.TIMED_OUT
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failureReason = TeardownFailureReason.RESET_FAILED
            } finally {
                bleRepository.stopPolling()
                executionGuard.clearRestoredTeardownJobIfOwned(guardOwner, currentCoroutineContext()[Job] ?: return@launch)
            }
            if (failureReason != null) {
                executionGuard.markRestoredRecoveryRequired(guardOwner, failureReason)
            } else {
                if (executionGuard.markRestoredTeardownReady(guardOwner)) {
                    clearRestoredTeardownRetryOwnerIfOwned(guardOwner)
                    scope.launch { tryStartCurrentAcceptedRetryRestored(guardOwner) }
                }
            }
        }
        if (executionGuard.attachRestoredTeardownJob(guardOwner, job)) {
            job.start()
        } else {
            job.cancel()
        }
    }

    private fun takeTeardownReadyContinuation(lease: ExecutionLease): (() -> Unit)? {
        while (true) {
            val pending = pendingTeardownReadyContinuation.value ?: return null
            if (!pending.lease.sameExecutionAs(lease)) return null
            if (pendingTeardownReadyContinuation.compareAndSet(pending, null)) return pending.callback
        }
    }

    private fun discardTeardownReadyContinuation(lease: ExecutionLease) {
        while (true) {
            val pending = pendingTeardownReadyContinuation.value ?: return
            if (!pending.lease.sameExecutionAs(lease)) return
            if (pendingTeardownReadyContinuation.compareAndSet(pending, null)) return
        }
    }

    private fun takePendingResetStart(owner: ResetMachineTeardownOwner): PendingResetStart? {
        while (true) {
            val pending = pendingResetStart.value ?: return null
            if (pending.owner != owner) return null
            if (pendingResetStart.compareAndSet(pending, null)) return pending
        }
    }

    private fun supersedePendingResetStart() {
        while (true) {
            val pending = pendingResetStart.value ?: return
            if (pendingResetStart.compareAndSet(pending, null)) return
        }
    }

    private fun captureExternalCommandInputStamp(): ExternalCommandInputStamp? {
        val contextBefore = userProfileRepository.activeProfileContext.value
        val ready = contextBefore as? ActiveProfileContext.Ready
            ?: return null
        val profileBackedRack = equipmentRackRepository is ProfileEquipmentRackRepository
        val profileRackItems = ready.preferences.rack.value.items.toList()
        val repositoryRackItems = if (profileBackedRack) {
            profileRackItems
        } else {
            equipmentRackRepository.rackItems.value.toList()
        }
        val contextAfter = userProfileRepository.activeProfileContext.value
        val readyAfter = contextAfter as? ActiveProfileContext.Ready ?: return null
        val repositoryRackItemsAfter = if (profileBackedRack) {
            readyAfter.preferences.rack.value.items.toList()
        } else {
            equipmentRackRepository.rackItems.value.toList()
        }
        if (contextBefore != contextAfter || repositoryRackItems != repositoryRackItemsAfter) return null
        return ExternalCommandInputStamp(
            profileId = ready.profile.id,
            profileRackItems = profileRackItems,
            profileRackMetadata = ready.preferences.rack.metadata,
            repositoryRackItems = repositoryRackItems,
        )
    }

    private fun captureQueuedStartCandidate(): QueuedStartCandidate? {
        val externalCommandInputStamp = captureExternalCommandInputStamp() ?: return null
        val loadedRoutine = coordinator._loadedRoutine.value
        val isTrackedRoutine = loadedRoutine != null &&
            !loadedRoutine.id.startsWith(DefaultWorkoutSessionManager.TEMP_SINGLE_EXERCISE_PREFIX)
        val routineContextIsCoherent = if (isTrackedRoutine) {
            loadedRoutine.profileId == externalCommandInputStamp.profileId &&
                coordinator.currentRoutineId == loadedRoutine.id &&
                coordinator.currentRoutineName == loadedRoutine.name &&
                !coordinator.currentRoutineSessionId.isNullOrBlank()
        } else {
            coordinator.currentRoutineId == null &&
                coordinator.currentRoutineName == null &&
                coordinator.currentRoutineSessionId == null
        }
        if (!routineContextIsCoherent) return null
        val exerciseIndex = coordinator._currentExerciseIndex.value
        val exercise = loadedRoutine?.exercises?.getOrNull(exerciseIndex)
        val startOverride = pendingStartOverride
        val params = (startOverride?.params ?: coordinator._workoutParameters.value).let { current ->
            current.copy(activeRackItemIds = current.activeRackItemIds.toList())
        }
        return QueuedStartCandidate(
            profileId = externalCommandInputStamp.profileId,
            loadedRoutineId = loadedRoutine?.id,
            routineId = coordinator.currentRoutineId,
            routineSessionId = coordinator.currentRoutineSessionId,
            routineName = coordinator.currentRoutineName,
            cycleId = coordinator.activeCycleId,
            cycleDayNumber = coordinator.activeCycleDayNumber,
            routineExercise = exercise,
            selectedExerciseId = params.selectedExerciseId,
            exerciseIndex = exerciseIndex,
            setIndex = coordinator._currentSetIndex.value,
            warmupSetIndex = coordinator._currentWarmupSetIndex.value,
            requiresMachine = exercise?.exercise?.isBodyweight != true,
            workoutParameters = params,
            activeRackItemIds = coordinator._activeRackItemIds.value.toList(),
            activeRackBehaviorOverrides = coordinator._activeRackBehaviorOverrides.value.toMap(),
            externalCommandInputStamp = externalCommandInputStamp,
        )
    }

    private fun queuedStartIdentityStillCurrent(candidate: QueuedStartCandidate): Boolean {
        val current = captureQueuedStartCandidate() ?: return false
        return current.copy(workoutParameters = candidate.workoutParameters) == candidate
    }

    private fun startPendingResetSuccessor(pending: PendingResetStart) {
        startWorkoutInternal(
            skipCountdown = pending.skipCountdown,
            isJustLiftMode = pending.isJustLiftMode,
            retryRequest = null,
            queuedSuccessorToken = pending.successorToken,
            queuedStartCandidate = pending.candidate,
        )
    }

    fun retryMachineTeardown() {
        if (bleRepository.connectionState.value !is ConnectionState.Connected) return
        val restoredOwner = restoredRuntimeOwner?.guardOwner ?: restoredTeardownRetryOwner
        if (restoredOwner != null) {
            val restoredAttempt = executionGuard.beginRestoredRecoveryAttempt(restoredOwner)
            if (restoredAttempt != null) {
                launchRestoredMachineTeardownReset(restoredAttempt.owner)
                return
            }
            if (executionGuard.isRestoredRuntimeCurrent(restoredOwner)) return
        }
        val recoveryAttempt = executionGuard.beginRecoveryAttempt() ?: return
        launchMachineTeardownReset(
            lease = recoveryAttempt.lease,
            reason = TeardownReason.RECOVERY,
        )
    }

    internal fun requestTeardownForTransition(
        reason: TeardownReason,
        afterReady: () -> Unit,
    ): Boolean = requestTeardownForTransition(executionGuard.currentLease, reason, afterReady)

    internal fun requestTeardownForTransition(
        expectedLease: ExecutionLease?,
        reason: TeardownReason,
        afterReady: () -> Unit,
    ): Boolean {
        if (expectedLease == null) {
            if (executionGuard.currentLease != null) return false
            if (executionGuard.machineTeardownState.value is MachineTeardownState.Ready) {
                afterReady()
                return true
            }
            return false
        }
        if (!hasCurrentAuthority(expectedLease, "transition_request_$reason")) return false
        if (!expectedLease.requiresMachine) {
            return beginMachineTeardown(expectedLease, reason, afterReady = afterReady)
        }
        if (executionGuard.machineTeardownState.value !is MachineTeardownState.Ready) {
            logSuppressedStateWrite(expectedLease, "transition_successor_$reason")
            return false
        }
        return beginMachineTeardown(expectedLease, reason, afterReady = afterReady)
    }

    /**
     * Delegate interface for operations that require routine navigation or
     * cross-cutting orchestration from DWSM.
     */
    internal interface WorkoutFlowDelegate {
        /** Load a routine by object (delegates to RoutineFlowManager) */
        fun loadRoutine(routine: Routine)

        /** Suspend until routine load and PR weight resolution complete */
        suspend fun loadRoutineAsync(routine: Routine): Boolean

        /** Suspend and publish only while the originating Resume UI action remains current. */
        suspend fun loadRoutineForResumeAsync(
            routine: Routine,
            launchOrigin: RoutineLaunchOrigin,
            cycleId: String?,
            cycleDayNumber: Int?,
            publicationStillCurrent: () -> Boolean,
        ): Boolean

        /** Resolve cold-recovery inputs without publishing coordinator state. */
        suspend fun prepareRoutineForRecovery(
            routine: Routine,
            exerciseIndex: Int,
            setIndex: Int,
            launchOrigin: RoutineLaunchOrigin,
            cycleId: String?,
            cycleDayNumber: Int?,
        ): RoutineRecoveryPreparation?

        /** Enter SetReady screen for a specific exercise/set */
        fun enterSetReady(exerciseIndex: Int, setIndex: Int)

        /** Enter SetReady screen with recovery-adjusted weight/reps */
        fun enterSetReadyWithAdjustments(exerciseIndex: Int, setIndex: Int, adjustedWeight: Float, adjustedReps: Int)

        /** Skip the current exercise and move to the next available routine step in SetReady */
        fun skipCurrentExerciseAndEnterNextStep(): Boolean

        /** Show routine complete screen */
        fun showRoutineComplete()

        /** Get current exercise from loaded routine */
        fun getCurrentExercise(): RoutineExercise?

        /** Get next step in routine navigation */
        fun getNextStep(routine: Routine, exerciseIndex: Int, setIndex: Int): Pair<Int, Int>?

        /**
         * Issue #572: detect whether two routine entries refer to the same physical
         * exercise (matched on name + non-null id). Used by [ActiveSessionEngine] to
         * detect "same-exercise continuations" where adjacent entries are the same
         * movement with a mode change between them (e.g. "Sumo Belt Squat 2x8 OldSchool"
         * followed by "Sumo Belt Squat 1x8 TUT").
         */
        fun isSameExercise(a: RoutineExercise, b: RoutineExercise): Boolean

        /** Check if currently in a superset */
        fun isInSuperset(): Boolean

        /** Check if at end of superset cycle */
        fun isAtEndOfSupersetCycle(): Boolean

        /** Calculate next exercise name for rest timer display */
        fun calculateNextExerciseName(isSingleExercise: Boolean, currentExercise: RoutineExercise?, routine: Routine?): String?

        /** Calculate if this is the last exercise */
        fun calculateIsLastExercise(isSingleExercise: Boolean, currentExercise: RoutineExercise?, routine: Routine?): Boolean

        /** Clear cycle context */
        fun clearCycleContext()

        /**
         * Seed rack selection for the target exercise (defaults or cleared).
         * Called when autoplay advances across exercises without entering SetReady.
         */
        fun seedRackSelectionForExercise(exerciseIndex: Int)

        /** Proceed from summary with the immutable execution completion that owns this transition. */
        fun proceedFromSummary(completion: SetExecutionCompletion)
    }

    /**
     * Flow delegate for operations that need routine navigation.
     * Set by DWSM after construction.
     */
    internal var flowDelegate: WorkoutFlowDelegate? = null

    private data class InterruptedSetRecoverySnapshot(
        val routineId: String,
        val exerciseIndex: Int,
        val setIndex: Int,
        val warmupSetIndex: Int,
        val repCount: RepCount,
    )

    private data class StartWorkoutOverride(
        val params: WorkoutParameters,
        val preserveWarmupReps: Boolean,
        val skipVariableWarmupOverride: Boolean,
    )

    private sealed interface InterruptedWorkoutRecoveryPlan {
        data class Resume(
            val params: WorkoutParameters,
            val warmupSetIndex: Int,
            val preserveWarmupReps: Boolean,
            val skipVariableWarmupOverride: Boolean,
        ) : InterruptedWorkoutRecoveryPlan

        data class EnterSetReady(
            val exerciseIndex: Int,
            val setIndex: Int,
            val adjustedWeight: Float,
            val adjustedReps: Int,
            val feedback: String,
        ) : InterruptedWorkoutRecoveryPlan

        data class ShowRoutineComplete(val feedback: String) : InterruptedWorkoutRecoveryPlan
    }

    private var interruptedSetRecovery: InterruptedSetRecoverySnapshot? = null
    private var pendingStartOverride: StartWorkoutOverride? = null

    /** Detector for identifying rep phase boundaries from position data */
    private val repBoundaryDetector = RepBoundaryDetector()

    /** Issue #237: Motion-triggered set start detector (reused across sets) */
    private val motionStartDetector = MotionStartDetector()
    private var motionStartListenerJob: Job? = null

    // Issue #649 defer deadline: moved to WorkoutCoordinator (F7, stall-detection
    // audit) so RoutineFlowManager reset paths can clear it too.

    // ===== Init Block: Workout-Related Collectors (moved from DWSM) =====

    init {
        // Collectors #3-8 (workout lifecycle collectors).
        // Collectors #1-2 are in RoutineFlowManager (constructed before ActiveSessionEngine).

        scope.launch {
            userProfileRepository.activeProfileContext.collect { context ->
                val ready = context as? ActiveProfileContext.Ready ?: return@collect
                val candidate = runtimeCleanupCandidateRef.value
                if (candidate != null && candidate.document.profileId != ready.profile.id) {
                    beginTrackedRuntimeCleanup(
                        reason = RuntimeCleanupReason.PROFILE_CHANGED,
                        candidate = candidate,
                    )
                }
                exitProfileMismatchedPresentationIfCurrent(ready.profile.id)
            }
        }

        // #3: Hook up RepCounter
        repCounter.onRepEvent = repEvent@{ event ->
            val eventLease = executionGuard.currentLease ?: return@repEvent
            scope.launch {
                if (!executionGuard.isCurrent(eventLease)) return@launch
                val timing = coordinator._workoutParameters.value.repCountTiming
                val params = coordinator._workoutParameters.value
                when (event.type) {
                    RepType.WORKING_PENDING -> {
                        // TOP timing: announce rep number at concentric peak
                        if (timing == RepCountTiming.TOP) {
                            val repNumber = event.workingCount + 1 // PENDING has pre-increment count
                            val prefs = settingsManager.userPreferences.value
                            // Issue #100: Check if this is the final working rep
                            val isFinalRep = !params.isJustLift && !params.isAMRAP &&
                                params.reps > 0 && repNumber >= params.reps
                            if (shouldEmitRepCountAnnouncement(prefs, repNumber)) {
                                coordinator._hapticEvents.emit(HapticEvent.REP_COUNT_ANNOUNCED(repNumber))
                            }
                            if (isFinalRep && prefs.repSoundEnabled) {
                                coordinator._hapticEvents.emit(HapticEvent.FINAL_REP)
                            } else if (!prefs.audioRepCountEnabled && prefs.repSoundEnabled) {
                                coordinator._hapticEvents.emit(HapticEvent.REP_COMPLETED)
                            }
                        }
                        // BOTTOM timing: silent grey preview only, no announcement
                    }

                    RepType.WORKING_COMPLETED -> {
                        // BOTTOM timing: announce at eccentric valley (traditional)
                        if (timing == RepCountTiming.BOTTOM) {
                            val prefs = settingsManager.userPreferences.value
                            // Issue #100: Check if this is the final working rep
                            val isFinalRep = !params.isJustLift && !params.isAMRAP &&
                                params.reps > 0 && event.workingCount >= params.reps
                            if (shouldEmitRepCountAnnouncement(prefs, event.workingCount)) {
                                coordinator._hapticEvents.emit(HapticEvent.REP_COUNT_ANNOUNCED(event.workingCount))
                            }
                            if (isFinalRep && prefs.repSoundEnabled) {
                                coordinator._hapticEvents.emit(HapticEvent.FINAL_REP)
                            } else if (!prefs.audioRepCountEnabled && prefs.repSoundEnabled) {
                                coordinator._hapticEvents.emit(HapticEvent.REP_COMPLETED)
                            }
                        }
                        // TOP timing: already announced on PENDING, no double-announce
                    }

                    RepType.WARMUP_COMPLETED -> {
                        // Issue #100: Gate warmup rep sound by repSoundEnabled preference
                        val prefs = settingsManager.userPreferences.value
                        if (prefs.repSoundEnabled) {
                            coordinator._hapticEvents.emit(HapticEvent.REP_COMPLETED)
                        }
                    }

                    RepType.WARMUP_COMPLETE -> {
                        // Issue #531: single clean transition tone. Previously also emitted
                        // WARMUP_TO_WORKING, which maps to the same `beepboop` file on both
                        // platforms -> a stacked double-tone heard mid-set. The WARMUP_TO_WORKING
                        // enum + sound mappings remain (used by HapticEventAudioTest and the
                        // Android routing guard) but are no longer emitted from the rep-event path.
                        coordinator._hapticEvents.emit(HapticEvent.WARMUP_COMPLETE)
                    }

                    RepType.WORKOUT_COMPLETE -> {
                        // Note: WORKOUT_COMPLETE sound removed - WORKOUT_END in handleSetCompletion
                        // provides sufficient feedback, and celebration sounds (PR/badge) may also play.
                        // Playing both was causing multiple sounds to fire at once (sound stacking bug).
                        // Issue #182: Trigger set completion immediately on WORKOUT_COMPLETE event.
                        if (executionGuard.isCurrent(eventLease) && coordinator._workoutState.value is WorkoutState.Active) {
                            // Issue #703: Merge the counter flush with any already-published or
                            // event-carried count so a lagging StateFlow cannot persist N-1, and a
                            // lagging counter cannot clobber a completed count of N.
                            coordinator._repCount.value = mergedWorkoutCompleteRepCount(
                                published = coordinator._repCount.value,
                                counter = repCounter.getRepCount(),
                                event = event,
                            )
                            Logger.d("WORKOUT_COMPLETE event received - triggering immediate set completion")
                            handleSetCompletion(eventLease, SetEndReason.TARGET_REPS_REACHED)
                        }
                    }
                }
            }
        }

        // #4: Handle activity state collector for auto-start functionality
        scope.launch {
            // handleState is a StateFlow — use try-catch in collect body (SharedFlow.catch is no-op)
            bleRepository.handleState.collect { activityState ->
                try {
                    val params = coordinator._workoutParameters.value
                    val currentState = coordinator._workoutState.value
                    val currentLease = executionGuard.currentLease
                    if (activityState == HandleState.Moving &&
                        currentLease?.activationCutoverTimestampMs != null &&
                        executionGuard.isCurrent(currentLease)
                    ) {
                        repFreshnessGate.observeMovement(currentLease)
                    }
                    val isIdle = currentState is WorkoutState.Idle
                    val isSummaryAndJustLift = currentState is WorkoutState.SetSummary && params.isJustLift

                    // Handle auto-START when Idle and waiting for handles
                    // Also allow auto-start from SetSummary if in Just Lift mode (interrupting to start next set)
                    if (params.useAutoStart && (isIdle || isSummaryAndJustLift)) {
                        when (activityState) {
                            HandleState.Grabbed -> {
                                Logger.d("Handles grabbed! Starting auto-start timer (State: ${coordinator._workoutState.value})")
                                startAutoStartTimer(currentLease)
                            }

                            HandleState.Moving -> {
                                // Moving = position extended but no velocity yet
                                // Don't start countdown yet, but also don't cancel if already running
                            }

                            HandleState.Released -> {
                                Logger.d("Handles released! Canceling auto-start timer")
                                cancelAutoStartTimer()
                            }

                            HandleState.WaitingForRest -> {
                                cancelAutoStartTimer()
                            }
                        }
                    }

                    // F8 (Issue #649 follow-up, stall-detection audit): releasing the
                    // handles during the verbal-cue defer window proves the set is over —
                    // let the auto-stop paths resume immediately instead of waiting out
                    // the 30s window. Transient blips can't instantly end a set: the
                    // stall/position countdowns still need their own 2.5-5s to fire.
                    if (currentState is WorkoutState.Active &&
                        activityState == HandleState.Released &&
                        coordinator.deferAutoStopDeadlineMs != 0L
                    ) {
                        Logger.d("Handles released during verbal-cue defer window - clearing defer deadline")
                        coordinator.deferAutoStopDeadlineMs = 0L
                    }

                    // Handle auto-STOP when Active in Just Lift mode and handles released.
                    // Warmup/ROM gate: auto-stop must remain disabled until warmup is complete.
                    if (params.isJustLift && currentState is WorkoutState.Active) {
                        if (!isWarmupGateOpenForAutoStop()) {
                            resetAutoStopTimer()
                        } else if (activityState == HandleState.Released) {
                            Logger.d("Just Lift: Handles RELEASED - starting auto-stop timer")
                            if (coordinator.autoStopStartTime == null) {
                                coordinator.autoStopStartTime = currentTimeMillis()
                                Logger.d("Auto-stop timer STARTED (Just Lift) - handles released")
                            }
                        } else if (activityState == HandleState.Grabbed || activityState == HandleState.Moving) {
                            resetAutoStopTimer()
                        }
                    }

                    // Track handle activity state for UI
                    coordinator.currentHandleState = activityState
                } catch (e: Exception) {
                    Logger.e(e) { "handleState collector error" }
                }
            }
        }

        // #5: Issue #98: Deload event collector for firmware-based auto-stop detection
        scope.launch {
            bleRepository.deloadOccurredEvents
                .catch { e -> Logger.e(e) { "deloadOccurredEvents collector error" } }
                .collect {
                    val params = coordinator._workoutParameters.value
                    val currentState = coordinator._workoutState.value

                    if (params.stallDetectionEnabled && currentState is WorkoutState.Active) {
                        // Echo levels are defined by the firmware's deload window (e.g. HARDER =
                        // deload after 1.25s below 40 mm/s — Issue #553), so DELOAD_OCCURRED fires
                        // routinely mid-set as the athlete fatigues. It is NOT a cable-release
                        // signal in Echo mode and must never arm the auto-stop stall timer.
                        if (params.isEchoMode) {
                            Logger.d("DELOAD_OCCURRED ignored - Echo mode (deload windows define Echo levels)")
                            return@collect
                        }
                        if (!isWarmupGateOpenForAutoStop()) {
                            Logger.d("DELOAD_OCCURRED ignored - warmup/ROM not established yet")
                            return@collect
                        }
                        val repCount = coordinator._repCount.value
                        if (shouldDeferStandardSetStall(params, repCount)) {
                            Logger.d(
                                "DELOAD_OCCURRED ignored - standard set stall guard " +
                                    "(workingReps=${repCount.workingReps}, pending=${repCount.hasPendingRep})",
                            )
                            resetStallTimer()
                            return@collect
                        }
                        if (!shouldEnableAutoStop(params)) return@collect
                        Logger.d("DELOAD_OCCURRED: Machine detected cable release - starting auto-stop timer")

                        val hasMeaningfulRange = repCounter.hasMeaningfulRange(WorkoutCoordinator.MIN_RANGE_THRESHOLD)
                        val inGrace = isInAmrapStartupGrace(hasMeaningfulRange)

                        if (coordinator.stallStartTime == null && !inGrace) {
                            coordinator.stallStartTime = currentTimeMillis()
                            coordinator.isCurrentlyStalled = true
                            coordinator.stallArmedByDeload = true
                            Logger.d("Auto-stop stall timer STARTED via DELOAD_OCCURRED flag")
                        } else if (coordinator.stallStartTime != null && !inGrace) {
                            // F4: a real deload is the stronger signal — upgrade a
                            // velocity-armed countdown so the retracting cables
                            // (position -> 0) don't cancel it via the racked-handles check.
                            coordinator.stallArmedByDeload = true
                        } else if (inGrace) {
                            Logger.d("DELOAD_OCCURRED ignored - in AMRAP startup grace period")
                        }
                    }
                }
        }

        // #6: Rep events collector for handling machine rep notifications
        coordinator.repEventsCollectionJob = scope.launch {
            bleRepository.repEvents
                .catch { e -> Logger.e(e) { "repEvents collector error" } }
                .collect(::acceptRepNotification)
        }

        // #7: CRITICAL: Global metricsFlow collection (matches parent repo)
        coordinator.monitorDataCollectionJob = scope.launch {
            Logger.d("ActiveSessionEngine") { "Starting global metricsFlow collection..." }
            bleRepository.metricsFlow
                .catch { e -> Logger.e(e) { "metricsFlow collector error" } }
                .collect { metric ->
                    coordinator._currentMetric.value = metric
                    handleMonitorMetric(metric)
                }
        }

        // #8: Heuristic data collection for Echo mode force feedback
        scope.launch {
            // heuristicData is a StateFlow — use try-catch in collect body (SharedFlow.catch is no-op)
            bleRepository.heuristicData.collect { stats ->
                try {
                    if (stats != null && coordinator._workoutState.value is WorkoutState.Active) {
                        val concentricMax = stats.concentric.kgMax
                        val eccentricMax = stats.eccentric.kgMax
                        val currentMax = maxOf(concentricMax, eccentricMax)

                        coordinator._currentHeuristicKgMax.value = currentMax

                        if (currentMax > coordinator.maxHeuristicKgMax) {
                            coordinator.maxHeuristicKgMax = currentMax
                            Logger.v("ActiveSessionEngine") { "Echo force telemetry: kgMax=$currentMax (concentric=$concentricMax, eccentric=$eccentricMax)" }
                        }
                    }
                } catch (e: Exception) {
                    Logger.e(e) { "heuristicData collector error" }
                }
            }
        }
    }

    // ===== Calculation Helpers =====

    private fun resolvedSessionBodyWeightKg(): Float = coordinator._sessionBodyweightState.value.sessionBodyWeightKg?.takeIf { it > 0f }
        ?: settingsManager.userPreferences.value.bodyWeightKg

    private suspend fun resolveSelectedExercise(params: WorkoutParameters) = params.selectedExerciseId?.let { exerciseId -> exerciseRepository.getExerciseById(exerciseId) }

    private suspend fun captureRackLoadSnapshot(
        params: WorkoutParameters,
        currentExercise: RoutineExercise?,
        activeRackItemIds: List<String> = coordinator._activeRackItemIds.value,
        behaviorOverrides: Map<String, RackItemBehavior> = coordinator._activeRackBehaviorOverrides.value,
    ): WorkoutParameters {
        val selectedItems = equipmentRackRepository.resolveActiveItems(
            ActiveRackSelection(activeRackItemIds),
        )
        val physicalCableCount = currentExercise?.exercise?.preferredCableCount
            ?: resolveSelectedExercise(params)?.preferredCableCount
            ?: 1
        val adjustment = applyEquipmentRackLoadUseCase.calculate(
            programmedWeightPerCableKg = params.weightPerCableKg,
            physicalCableCount = physicalCableCount,
            selectedItems = selectedItems,
            isEchoMode = params.isEchoMode,
            validatorMinimumPerCableKg = validatorSafeMinimum(params),
            behaviorOverrides = behaviorOverrides,
        )
        coordinator._currentRackLoadAdjustment.value = adjustment
        coordinator.currentRackItemsJson = rackJson.encodeToString(selectedItems)
        val paramsWithRack = params.copy(
            activeRackItemIds = activeRackItemIds,
            externalAddedLoadKg = adjustment.externalAddedLoadKg,
            counterweightKg = adjustment.counterweightKg,
        )
        coordinator._workoutParameters.value = paramsWithRack
        return paramsWithRack
    }

    private fun applyRackToBleParams(
        params: WorkoutParameters,
        physicalCableCount: Int,
        selectedItems: List<RackItem>,
        behaviorOverrides: Map<String, RackItemBehavior> = emptyMap(),
    ): WorkoutParameters {
        val adjustment = applyEquipmentRackLoadUseCase.calculate(
            programmedWeightPerCableKg = params.weightPerCableKg,
            physicalCableCount = physicalCableCount,
            selectedItems = selectedItems,
            isEchoMode = params.isEchoMode,
            validatorMinimumPerCableKg = validatorSafeMinimum(params),
            behaviorOverrides = behaviorOverrides,
        )
        return params.copy(
            weightPerCableKg = if (params.isEchoMode) {
                params.weightPerCableKg
            } else {
                adjustment.adjustedMachineWeightPerCableKg
            },
            activeRackItemIds = coordinator._activeRackItemIds.value,
            externalAddedLoadKg = adjustment.externalAddedLoadKg,
            counterweightKg = adjustment.counterweightKg,
        )
    }

    private fun validatorSafeMinimum(params: WorkoutParameters): Float = when {
        params.isJustLift -> Constants.JUST_LIFT_MIN_VALID_WEIGHT_KG
        params.isEchoMode -> Constants.MIN_WEIGHT_KG
        else -> Constants.DEFAULT_WEIGHT_INCREMENT_KG
    }

    private fun roundUpRemainingSeconds(seconds: Float): Int {
        val wholeSeconds = seconds.toInt()
        return if (seconds > wholeSeconds.toFloat()) wholeSeconds + 1 else wholeSeconds
    }

    /**
     * Calculate enhanced metrics for the set summary display.
     */
    internal fun calculateSetSummaryMetrics(
        metrics: List<WorkoutMetric>,
        repCount: Int,
        fallbackWeightKg: Float,
        configuredWeightKgPerCable: Float,
        isEchoMode: Boolean = false,
        warmupRepsCount: Int = 0,
        workingRepsCount: Int = 0,
        warmupCompleteTimeMs: Long = 0L,
        cableCountHint: Int? = null,
        displayMultiplierHint: Int? = null,
    ): WorkoutState.SetSummary {
        if (metrics.isEmpty()) {
            return WorkoutState.SetSummary(
                metrics = metrics,
                peakLoadKgPerCable = 0f,
                avgLoadKgPerCable = 0f,
                repCount = repCount,
                cableCount = cableCountHint ?: 1,
                displayMultiplier = displayMultiplierHint ?: 1,
                heaviestLiftKgPerCable = fallbackWeightKg,
                configuredWeightKgPerCable = configuredWeightKgPerCable,
                isEchoMode = isEchoMode,
                warmupReps = warmupRepsCount,
                workingReps = workingRepsCount,
            )
        }

        // Issue #252: Exclude warmup time from set duration
        val effectiveStart = if (warmupCompleteTimeMs > 0L) {
            warmupCompleteTimeMs.coerceAtLeast(metrics.first().timestamp)
        } else {
            metrics.first().timestamp
        }
        val durationMs = metrics.last().timestamp - effectiveStart

        val peakCableA = metrics.maxOf { it.loadA }
        val peakCableB = metrics.maxOf { it.loadB }

        // Issue #6 Fix: Detect single-cable (unilateral) exercises and don't halve the weight.
        // Heuristic: if one cable's peak load is > 5x the other's, treat as single-cable.
        // For single-cable, use the max of the active cable. For double-cable, use totalLoad/2.
        val heuristicIsSingleCable = (
            peakCableA > 0f && peakCableB > 0f &&
                (peakCableA / peakCableB > 5f || peakCableB / peakCableA > 5f)
            ) ||
            (peakCableA > 0f && peakCableB == 0f) ||
            (peakCableB > 0f && peakCableA == 0f)

        val cableCount = when (cableCountHint) {
            1 -> 1
            2 -> 2
            else -> if (heuristicIsSingleCable) 1 else 2
        }
        val isSingleCable = cableCount == 1

        val heaviestLiftKgPerCable = if (isSingleCable) {
            // Single-cable: use the active cable's load (don't halve)
            metrics.maxOf { maxOf(it.loadA, it.loadB) }
        } else {
            // Double-cable: raw totalLoad / 2, no baseline subtraction (parent-aligned)
            metrics.maxOf { it.totalLoad / 2f }
        }

        val volumeWeightKgPerCable = if (isEchoMode) {
            heaviestLiftKgPerCable
        } else {
            configuredWeightKgPerCable
        }
        // Fixed-load modes should log the prescribed working load, while Echo uses measured force.
        val totalVolumeKg = volumeWeightKgPerCable * cableCount.toFloat() * repCount

        val concentricMetrics = metrics.filter { it.velocityA > 10 || it.velocityB > 10 }
        val eccentricMetrics = metrics.filter { it.velocityA < -10 || it.velocityB < -10 }

        val peakConcentricA = concentricMetrics.maxOfOrNull { it.loadA } ?: 0f
        val peakConcentricB = concentricMetrics.maxOfOrNull { it.loadB } ?: 0f
        val peakEccentricA = eccentricMetrics.maxOfOrNull { it.loadA } ?: 0f
        val peakEccentricB = eccentricMetrics.maxOfOrNull { it.loadB } ?: 0f

        val peakLoadA = metrics.maxOf { it.loadA }
        val peakLoadB = metrics.maxOf { it.loadB }
        val thresholdA = (peakLoadA * 0.1f).coerceAtLeast(1f)
        val thresholdB = (peakLoadB * 0.1f).coerceAtLeast(1f)

        val activeConcentricMetrics = concentricMetrics.filter {
            it.loadA > thresholdA || it.loadB > thresholdB
        }
        val activeEccentricMetrics = eccentricMetrics.filter {
            it.loadA > thresholdA || it.loadB > thresholdB
        }

        val avgConcentricA = if (activeConcentricMetrics.isNotEmpty()) {
            activeConcentricMetrics.map { it.loadA }.average().toFloat()
        } else {
            0f
        }
        val avgConcentricB = if (activeConcentricMetrics.isNotEmpty()) {
            activeConcentricMetrics.map { it.loadB }.average().toFloat()
        } else {
            0f
        }
        val avgEccentricA = if (activeEccentricMetrics.isNotEmpty()) {
            activeEccentricMetrics.map { it.loadA }.average().toFloat()
        } else {
            0f
        }
        val avgEccentricB = if (activeEccentricMetrics.isNotEmpty()) {
            activeEccentricMetrics.map { it.loadB }.average().toFloat()
        } else {
            0f
        }

        // Physics-based calorie estimation using work-energy theorem:
        // W = sum(force_i * delta_distance_i) for each consecutive sample pair
        // kcal = (W_joules / 4184) * 5 (metabolic efficiency multiplier ~20%)
        //
        // Issue #358: Cap position deltas to POSITION_JUMP_THRESHOLD (20mm) to prevent
        // BLE glitches from inflating calorie estimates. This matches the validation
        // applied in MonitorDataProcessor.validateSample().
        var calorieMaxRawDeltaMm = 0f
        var calorieMaxCappedDeltaMm = 0f
        var calorieTotalWorkJoules = 0.0
        val estimatedCalories = run {
            if (metrics.size < 2) {
                // Fallback for insufficient samples
                (totalVolumeKg * 0.5f * 9.81f / 4184f).coerceAtLeast(1f)
            } else {
                val maxDeltaMm = BleConstants.Thresholds.POSITION_JUMP_THRESHOLD
                var totalWorkJoules = 0.0
                for (i in 1 until metrics.size) {
                    val prev = metrics[i - 1]
                    val curr = metrics[i]
                    // Average force in N across both cables
                    val avgForceN = ((prev.totalLoad + curr.totalLoad) / 2f) * 9.81f
                    // Distance in meters (position is in mm, capped to filter BLE glitches)
                    val rawDeltaA = kotlin.math.abs(curr.positionA - prev.positionA)
                    val rawDeltaB = kotlin.math.abs(curr.positionB - prev.positionB)
                    calorieMaxRawDeltaMm = maxOf(calorieMaxRawDeltaMm, rawDeltaA, rawDeltaB)
                    val cappedDeltaA = rawDeltaA.coerceAtMost(maxDeltaMm)
                    val cappedDeltaB = rawDeltaB.coerceAtMost(maxDeltaMm)
                    calorieMaxCappedDeltaMm = maxOf(calorieMaxCappedDeltaMm, cappedDeltaA, cappedDeltaB)
                    val deltaA = cappedDeltaA / 1000f
                    val deltaB = cappedDeltaB / 1000f
                    val avgDelta = if (isSingleCable) maxOf(deltaA, deltaB) else (deltaA + deltaB) / 2f
                    totalWorkJoules += avgForceN * avgDelta
                }
                calorieTotalWorkJoules = totalWorkJoules
                ((totalWorkJoules / 4184.0) * 5.0).toFloat().coerceAtLeast(1f)
            }
        }

        Logger.d("ActiveSessionEngine") {
            "HEALTH_DEBUG_SUMMARY: cableCountHint=${cableCountHint ?: -1}, " +
                "heuristicIsSingleCable=$heuristicIsSingleCable, resolvedCableCount=$cableCount, " +
                "peakCableA=$peakCableA, peakCableB=$peakCableB, " +
                "configuredWeightKgPerCable=$configuredWeightKgPerCable, " +
                "heaviestLiftKgPerCable=$heaviestLiftKgPerCable, totalVolumeKg=$totalVolumeKg, " +
                "estimatedCalories=$estimatedCalories, metrics=${metrics.size}, " +
                "maxRawDeltaMm=$calorieMaxRawDeltaMm, maxCappedDeltaMm=$calorieMaxCappedDeltaMm, " +
                "totalWorkJoules=$calorieTotalWorkJoules"
        }

        val peakLoadKgPerCable = heaviestLiftKgPerCable
        val avgLoadKgPerCable = if (isSingleCable) {
            metrics.map { maxOf(it.loadA, it.loadB) }.average().toFloat()
        } else {
            metrics.map { it.totalLoad / 2f }.average().toFloat()
        }

        // Echo Mode Phase-Aware Metrics
        var warmupAvgWeightKg = 0f
        var workingAvgWeightKg = 0f
        var burnoutAvgWeightKg = 0f
        var peakWeightKg = 0f
        var burnoutReps = 0

        if (isEchoMode && metrics.size > 10) {
            val weightSamples = metrics.map { maxOf(it.loadA, it.loadB) }
            peakWeightKg = weightSamples.maxOrNull() ?: 0f
            val peakThreshold = peakWeightKg * 0.9f

            val peakIndices = weightSamples.indices.filter { weightSamples[it] >= peakThreshold }

            if (peakIndices.isNotEmpty()) {
                val firstPeakIndex = peakIndices.first()
                val lastPeakIndex = peakIndices.last()

                val warmupSamples = weightSamples.take(firstPeakIndex)
                warmupAvgWeightKg = if (warmupSamples.isNotEmpty()) {
                    warmupSamples.average().toFloat()
                } else {
                    0f
                }

                val workingSamples = weightSamples.subList(firstPeakIndex, (lastPeakIndex + 1).coerceAtMost(weightSamples.size))
                workingAvgWeightKg = if (workingSamples.isNotEmpty()) {
                    workingSamples.average().toFloat()
                } else {
                    peakWeightKg
                }

                val burnoutSamples = if (lastPeakIndex < weightSamples.lastIndex) {
                    weightSamples.drop(lastPeakIndex + 1)
                } else {
                    emptyList()
                }
                burnoutAvgWeightKg = if (burnoutSamples.isNotEmpty()) {
                    burnoutSamples.average().toFloat()
                } else {
                    0f
                }

                val totalReps = warmupRepsCount + workingRepsCount
                if (burnoutSamples.isNotEmpty() && totalReps > 0) {
                    val burnoutRatio = burnoutSamples.size.toFloat() / weightSamples.size.toFloat()
                    burnoutReps = (totalReps * burnoutRatio).toInt().coerceAtLeast(0)
                }
            } else {
                workingAvgWeightKg = weightSamples.average().toFloat()
                peakWeightKg = workingAvgWeightKg
            }
        }

        return WorkoutState.SetSummary(
            metrics = metrics,
            peakLoadKgPerCable = peakLoadKgPerCable,
            avgLoadKgPerCable = avgLoadKgPerCable,
            repCount = repCount,
            durationMs = durationMs,
            totalVolumeKg = totalVolumeKg,
            cableCount = cableCount,
            displayMultiplier = displayMultiplierHint ?: cableCount,
            heaviestLiftKgPerCable = heaviestLiftKgPerCable,
            configuredWeightKgPerCable = configuredWeightKgPerCable,
            peakForceConcentricA = peakConcentricA,
            peakForceConcentricB = peakConcentricB,
            peakForceEccentricA = peakEccentricA,
            peakForceEccentricB = peakEccentricB,
            avgForceConcentricA = avgConcentricA,
            avgForceConcentricB = avgConcentricB,
            avgForceEccentricA = avgEccentricA,
            avgForceEccentricB = avgEccentricB,
            estimatedCalories = estimatedCalories,
            isEchoMode = isEchoMode,
            warmupReps = warmupRepsCount,
            workingReps = workingRepsCount,
            burnoutReps = burnoutReps,
            warmupAvgWeightKg = warmupAvgWeightKg,
            workingAvgWeightKg = workingAvgWeightKg,
            burnoutAvgWeightKg = burnoutAvgWeightKg,
            peakWeightKg = peakWeightKg,
        )
    }

    /**
     * Apply bodyweight volume overrides to a set summary.
     *
     * For bodyweight exercises (no cable accessories), the cable-based volume calculation
     * produces meaningless values. This replaces totalVolumeKg and heaviestLiftKgPerCable
     * with estimates based on the user's body weight and the exercise-specific percentage
     * from [BodyweightVolumeCalculator].
     *
     * @param summary The cable-based set summary to override
     * @param currentExercise The routine exercise context (null-safe; returns summary unchanged)
     * @param bodyWeightKg User's body weight in kg (0 = not set, returns summary unchanged)
     * @return Summary with bodyweight volume applied, or the original summary if not applicable
     */
    internal fun applyBodyweightVolume(
        summary: WorkoutState.SetSummary,
        currentExercise: RoutineExercise?,
        bodyWeightKg: Float,
        selectedVariant: BodyweightVariantOption? = null,
    ): WorkoutState.SetSummary {
        if (currentExercise == null) return summary
        if (!currentExercise.exercise.isBodyweight) return summary
        if (bodyWeightKg <= 0f) return summary

        val exerciseName = currentExercise.exercise.name
        val repCount = summary.repCount
        val resolvedVariant = selectedVariant
            ?: coordinator._selectedBodyweightVariants.value[bodyweightVariantKey(currentExercise)]
        val percentage = resolvedVariant?.percentage
        val resolvedPercentage = percentage ?: BodyweightVolumeCalculator.getPercentageForExercise(exerciseName)
        val rackAdjustment = coordinator._currentRackLoadAdjustment.value
        val volume = BodyweightVolumeCalculator.calculateVolume(
            bodyWeightKg = bodyWeightKg,
            reps = repCount,
            percentage = resolvedPercentage,
            externalAddedLoadKg = rackAdjustment.externalAddedLoadKg,
            counterweightKg = rackAdjustment.counterweightKg,
        )
        val effectiveWeight = BodyweightVolumeCalculator.effectiveWeight(
            bodyWeightKg = bodyWeightKg,
            percentage = resolvedPercentage,
            externalAddedLoadKg = rackAdjustment.externalAddedLoadKg,
            counterweightKg = rackAdjustment.counterweightKg,
        )

        Logger.d("ActiveSessionEngine") {
            "applyBodyweightVolume: exercise=$exerciseName, bodyWeightSet=${bodyWeightKg > 0f}, " +
                "variant=${resolvedVariant?.label ?: "name-match"}, reps=$repCount, " +
                "externalAddedLoad=${rackAdjustment.externalAddedLoadKg}kg, counterweight=${rackAdjustment.counterweightKg}kg, " +
                "volume=${volume}kg, effectiveWeight=${effectiveWeight}kg"
        }

        return summary.copy(
            peakLoadKgPerCable = effectiveWeight,
            avgLoadKgPerCable = effectiveWeight,
            totalVolumeKg = volume,
            cableCount = 1,
            displayMultiplier = 1,
            heaviestLiftKgPerCable = effectiveWeight,
            configuredWeightKgPerCable = effectiveWeight,
        )
    }

    internal fun bodyweightVariantKey(exercise: RoutineExercise): String = exercise.exercise.id?.takeIf { it.isNotBlank() } ?: exercise.exercise.name.lowercase()

    internal fun selectBodyweightVariant(exerciseKey: String, variant: BodyweightVariantOption) {
        coordinator._selectedBodyweightVariants.update { selections ->
            selections + (exerciseKey to variant)
        }
        val entry = coordinator._workoutState.value as? WorkoutState.BodyweightRepEntry
        if (entry != null && entry.exerciseKey == exerciseKey) {
            coordinator._workoutState.value = entry.copy(selectedVariant = variant)
        }
    }

    fun confirmBodyweightSetResult(reps: Int, variant: BodyweightVariantOption) {
        val entry = coordinator._workoutState.value as? WorkoutState.BodyweightRepEntry ?: return
        val lease = executionGuard.currentLease ?: return
        val completion = bodyweightCompletionGate.pendingFor(lease) ?: return
        val coercedReps = reps.coerceAtLeast(0)
        // The delayed bodyweight gate owns the immutable activation/completion facts.
        // Confirmation may change only the final rep count; never re-read coordinator state here.
        val finalCompletion = completion.copy(actualReps = coercedReps)
        beforeBodyweightCompletionClaim(lease.executionId, lease.sessionId)
        if (!executionGuard.tryClaimCompletion(finalCompletion)) return
        if (!bodyweightCompletionGate.tryConsume(completion, finalCompletion)) {
            executionGuard.releaseCompletionClaim(lease)
            return
        }
        afterBodyweightCompletionConsume(lease.executionId, lease.sessionId)
        val committed = executionGuard.commitIfCurrent(lease) {
            selectBodyweightVariant(entry.exerciseKey, variant)
            coordinator.bodyweightCompletionVariantOverride = variant
            coordinator._repCount.value = RepCount(
                workingReps = coercedReps,
                totalReps = coercedReps,
                isWarmupComplete = true,
            )
        }
        if (!committed) {
            executionGuard.releaseCompletionClaim(lease)
            return
        }
        handleSetCompletion(
            completion = finalCompletion,
            reason = TeardownReason.AUTO_COMPLETE,
            completionClaimed = true,
        )
    }

    private suspend fun showBodyweightRepEntry(currentExercise: RoutineExercise) {
        val exerciseName = currentExercise.exercise.displayName.takeIf { it.isNotBlank() }
            ?: currentExercise.exercise.name
        val variants = BodyweightVolumeCalculator.getVariantsForExercise(currentExercise.exercise.name)
            ?: listOf(BodyweightVolumeCalculator.getDefaultVariantForExercise(currentExercise.exercise.name))
        val exerciseKey = bodyweightVariantKey(currentExercise)
        val savedVariant = coordinator._selectedBodyweightVariants.value[exerciseKey]
        val selectedVariant = savedVariant?.takeIf { saved ->
            variants.any { it.label == saved.label && it.percentage == saved.percentage }
        } ?: variants.first()
        selectBodyweightVariant(exerciseKey, selectedVariant)

        val currentSetIndex = coordinator._currentSetIndex.value
        val plannedReps = currentExercise.setReps.getOrNull(currentSetIndex)?.coerceAtLeast(0) ?: 0

        coordinator._workoutState.value = WorkoutState.BodyweightRepEntry(
            exerciseKey = exerciseKey,
            exerciseName = exerciseName,
            plannedReps = plannedReps,
            currentSet = currentSetIndex + 1,
            totalSets = currentExercise.setReps.size.coerceAtLeast(1),
            bodyWeightKg = resolvedSessionBodyWeightKg(),
            variants = variants,
            selectedVariant = selectedVariant,
        )
    }

    /**
     * Collect metric for history recording.
     */
    private fun collectMetricForHistory(metric: WorkoutMetric) {
        coordinator.collectedMetrics.update { it + metric }
    }

    // ===== Auto-Stop Helpers =====

    /**
     * Reset auto-stop timer without resetting the triggered flag.
     */
    private fun resetAutoStopTimer() {
        coordinator.autoStopStartTime = null
        if (!coordinator.autoStopTriggered && !coordinator.isCurrentlyStalled) {
            coordinator._autoStopState.value = AutoStopUiState()
        }
    }

    /**
     * Reset stall detection timer.
     */
    private fun resetStallTimer() {
        coordinator.stallStartTime = null
        coordinator.isCurrentlyStalled = false
        coordinator.stallArmedByDeload = false
        if (coordinator.autoStopStartTime == null && !coordinator.autoStopTriggered) {
            coordinator._autoStopState.value = AutoStopUiState()
        }
    }

    /**
     * Fully reset auto-stop state for a new workout/set.
     */
    internal fun resetAutoStopState() {
        executionGuard.currentLease?.let(dangerZoneCountdownGate::clear)
        coordinator.resetAutoStopState()
    }

    /**
     * Issue #204: Returns true if we're in the startup grace period for auto-stop modes.
     */
    private fun isInAmrapStartupGrace(hasMeaningfulRange: Boolean): Boolean {
        val params = coordinator._workoutParameters.value
        if (!params.isAMRAP && !params.isJustLift) return false
        if (hasMeaningfulRange) return false
        if (coordinator.workoutStartTime == 0L) return true
        val elapsed = currentTimeMillis() - coordinator.workoutStartTime
        return elapsed < WorkoutCoordinator.AMRAP_STARTUP_GRACE_MS
    }

    /**
     * Auto-stop and stall detection are only active once warmup reps are complete.
     */
    private fun isWarmupGateOpenForAutoStop(): Boolean = coordinator._repCount.value.isWarmupComplete

    /**
     * Whether 2.5s position-based auto-stop should run.
     */
    private fun shouldRunPositionBasedAutoStop(params: WorkoutParameters): Boolean {
        if (!isWarmupGateOpenForAutoStop()) return false
        val timedCableReadyForAutoStop = coordinator.isCurrentTimedCableExercise
        return params.isJustLift || params.isAMRAP || timedCableReadyForAutoStop
    }

    /**
     * Whether any auto-stop evaluation should run.
     * - 5s velocity/deload stall path: controlled by stallDetectionEnabled.
     * - 2.5s position path: Just Lift / AMRAP / timed cable (post-warmup).
     */
    private fun shouldEnableAutoStop(params: WorkoutParameters): Boolean {
        if (!isWarmupGateOpenForAutoStop()) return false
        return params.stallDetectionEnabled || shouldRunPositionBasedAutoStop(params)
    }

    /**
     * Standard set stall guard:
     * - Defer stall detection only when no working reps are confirmed AND no rep is pending.
     *
     * Issue #256: Removed hasPendingRep guard from deferral. A stalled pending rep IS the
     * failure scenario (e.g., failed bench press at TOP of first rep). The velocity hysteresis
     * band (STALL_VELOCITY_LOW=2.5, STALL_VELOCITY_HIGH=10.0 mm/s) already provides adequate
     * protection against false triggers during brief pauses. When workingReps == 0 but
     * hasPendingRep is true, the user is mid-first-rep and must be protected.
     */
    private fun shouldDeferStandardSetStall(params: WorkoutParameters, repCount: RepCount): Boolean {
        val isStandardSet = !params.isJustLift && !params.isAMRAP && !coordinator.isCurrentTimedCableExercise
        if (!isStandardSet) return false
        return repCount.workingReps == 0 && !repCount.hasPendingRep
    }

    /**
     * Request auto-stop (thread-safe, only triggers once).
     */
    private fun requestAutoStop(lease: ExecutionLease, reason: SetEndReason) {
        if (!executionGuard.isCurrent(lease)) return
        if (coordinator.autoStopStopRequested) return
        coordinator.autoStopStopRequested = true
        triggerAutoStop(lease, reason)
    }

    /**
     * Trigger auto-stop and handle set completion.
     */
    private fun triggerAutoStop(lease: ExecutionLease, reason: SetEndReason) {
        if (!executionGuard.isCurrent(lease)) return
        Logger.d("triggerAutoStop() called")
        coordinator.autoStopTriggered = true

        if (coordinator._workoutParameters.value.isJustLift || coordinator._workoutParameters.value.isAMRAP || coordinator.isCurrentTimedCableExercise) {
            coordinator._autoStopState.value = coordinator._autoStopState.value.copy(
                progress = 1f,
                secondsRemaining = 0,
                isActive = true,
            )
        } else {
            coordinator._autoStopState.value = AutoStopUiState()
        }

        handleSetCompletion(lease, reason)
    }

    // ===== Rep Processing =====

    /**
     * Handle rep notification from the machine.
     */
    private fun acceptRepNotification(notification: RepNotification) {
        val lease = executionGuard.currentLease ?: return
        if (coordinator._workoutState.value !is WorkoutState.Active) return

        when (val decision = repFreshnessGate.evaluate(lease, notification)) {
            RepFreshnessDecision.Process -> handleRepNotification(lease, notification)

            RepFreshnessDecision.BaselineOnly -> {
                repCounter.establishLegacyCounterBaseline(
                    topCounter = notification.topCounter,
                    completeCounter = notification.completeCounter,
                )
                logRepDrop(lease, "legacy-baseline", notification)
            }

            is RepFreshnessDecision.Drop -> logRepDrop(lease, decision.reason.name, notification)
        }
    }

    private fun handleRepNotification(lease: ExecutionLease, notification: RepNotification) {
        if (!executionGuard.isCurrent(lease)) return
        if (coordinator._isCurrentExerciseBodyweight.value) {
            return
        }

        val currentPositions = coordinator._currentMetric.value
        val rawPosA = currentPositions?.positionA ?: 0f
        val rawPosB = currentPositions?.positionB ?: 0f

        val repCountBefore = repCounter.getRepCount().totalReps
        logRepNotificationReceipt(notification, repCountBefore)

        // Seed ROM from machine (only has effect on first notification with valid data)
        if (!notification.isLegacyFormat) {
            repCounter.seedRomBoundaries(notification.rangeTop, notification.rangeBottom)
        }

        repCounter.process(
            repsRomCount = notification.repsRomCount,
            repsRomTotal = notification.repsRomTotal,
            repsSetCount = notification.repsSetCount,
            repsSetTotal = notification.repsSetTotal,
            up = notification.topCounter,
            down = notification.completeCounter,
            posA = rawPosA,
            posB = rawPosB,
            isLegacyFormat = notification.isLegacyFormat,
        )

        repCounter.updatePhaseFromPosition(rawPosA, rawPosB)

        if (!executionGuard.isCurrent(lease)) return
        coordinator._repCount.value = repCounter.getRepCount()
        coordinator._repRanges.value = repCounter.getRepRanges()

        // Score the rep if rep count actually incremented
        val repCountAfter = repCounter.getRepCount().totalReps
        if (repCountAfter > repCountBefore) {
            // Issue #649: a completed working rep proves the user is back in
            // motion; let normal AMRAP / stall auto-stop resume by zeroing the
            // deadline (the source-of-truth field).
            coordinator.deferAutoStopDeadlineMs = 0L
            // Issue #652: the same completed-rep boundary is authoritative for
            // the shared stall countdown (both DELOAD_OCCURRED and low-velocity
            // arms write to coordinator.stallStartTime). Without this, a stale
            // countdown started at a turnaround, brief pause, or firmware de-load
            // can survive a subsequent valid rep and later auto-complete the set.
            resetStallTimer()

            // Capture rep boundary timestamp BEFORE scoring so scoreCurrentRep()
            // and processBiomechanicsForRep() both see the correct metric window.
            val now = KmpUtils.currentTimeMillis()
            coordinator.repBoundaryTimestamps.update { it + now }

            scoreCurrentRep(repCountAfter)

            // Segment metrics for this rep and process biomechanics (GATE-04: unconditional capture)
            processBiomechanicsForRep(lease, repCountAfter, now)
        }
    }

    private fun logRepDrop(lease: ExecutionLease, reason: String, notification: RepNotification) {
        connectionLogRepository.debug(
            LogEventType.WORKOUT_REP_REJECTED,
            "Rep event rejected by execution freshness gate",
            details = "executionId=${lease.executionId},sessionId=${lease.sessionId},reason=$reason," +
                "timestamp=${notification.timestamp},repsSetCount=${notification.repsSetCount}," +
                "repsSetTotal=${notification.repsSetTotal},legacy=${notification.isLegacyFormat}",
        )
    }

    private fun logRepNotificationReceipt(notification: RepNotification, repCountBefore: Int) {
        connectionLogRepository.debug(
            LogEventType.REP_RECEIVED,
            "Rep event received by session engine",
            details = "boundary=active-session-engine, packetSize=${notification.rawData.size}, " +
                "legacy=${notification.isLegacyFormat}, up=${notification.topCounter}, " +
                "down=${notification.completeCounter}, repsRomCount=${notification.repsRomCount}, " +
                "repsRomTotal=${notification.repsRomTotal}, repsSetCount=${notification.repsSetCount}, " +
                "repsSetTotal=${notification.repsSetTotal}, repCountBefore=$repCountBefore",
        )
    }

    /**
     * Score the current rep using real metrics from collected WorkoutMetric data.
     * Uses RepBoundaryDetector to segment position data into concentric/eccentric phases,
     * then extracts force, velocity, and position arrays for each phase.
     */
    private fun scoreCurrentRep(repNumber: Int) {
        val metrics = coordinator.collectedMetrics.value
        if (metrics.isEmpty()) return

        // Get all metrics for this rep (use rep boundary timestamps if available)
        val boundaries = coordinator.repBoundaryTimestamps.value
        val prevBoundary = if (boundaries.size >= 2) boundaries[boundaries.size - 2] else 0L
        val currentBoundary = if (boundaries.isNotEmpty()) boundaries.last() else KmpUtils.currentTimeMillis()

        val repMetrics = if (boundaries.size >= 2) {
            metrics.filter { it.timestamp in (prevBoundary + 1)..currentBoundary }
        } else {
            metrics.takeLast(50) // Fallback for first rep
        }

        if (repMetrics.isEmpty()) return

        // Extract position array for phase detection (use max position of A/B)
        val positions = repMetrics.map { maxOf(it.positionA, it.positionB) }.toFloatArray()

        // Detect rep phases using valley detection
        val phaseBoundaries = repBoundaryDetector.detectBoundaries(positions)

        // If boundary detection found a rep, use its phase indices; otherwise use velocity-based split
        val (concentricIndices, eccentricIndices) = if (phaseBoundaries.isNotEmpty()) {
            // Use detected boundary (usually only 1 rep worth of data at capture time)
            val boundary = phaseBoundaries.first()
            Pair(boundary.concentricIndices, boundary.eccentricIndices)
        } else {
            // Fallback: split by velocity direction
            val velocitySplitIndex = repMetrics.indexOfFirst { it.velocityA < 0 || it.velocityB < 0 }
                .takeIf { it > 0 } ?: (repMetrics.size / 2)
            Pair(0 until velocitySplitIndex, velocitySplitIndex until repMetrics.size)
        }

        // Extract concentric phase data
        val concentricMetrics = concentricIndices.mapNotNull { repMetrics.getOrNull(it) }
        val concentricLoadsA = concentricMetrics.map { it.loadA }.toFloatArray()
        val concentricLoadsB = concentricMetrics.map { it.loadB }.toFloatArray()
        val concentricPositions = concentricMetrics.map { maxOf(it.positionA, it.positionB) }.toFloatArray()
        val concentricVelocities = concentricMetrics.map { maxOf(kotlin.math.abs(it.velocityA.toFloat()), kotlin.math.abs(it.velocityB.toFloat())) }.toFloatArray()
        val concentricTimestamps = concentricMetrics.map { it.timestamp - repMetrics.first().timestamp }.toLongArray()
        val concentricDurationMs = if (concentricMetrics.size >= 2) {
            concentricMetrics.last().timestamp - concentricMetrics.first().timestamp
        } else {
            0L
        }

        // Extract eccentric phase data
        val eccentricMetrics = eccentricIndices.mapNotNull { repMetrics.getOrNull(it) }
        val eccentricLoadsA = eccentricMetrics.map { it.loadA }.toFloatArray()
        val eccentricLoadsB = eccentricMetrics.map { it.loadB }.toFloatArray()
        val eccentricPositions = eccentricMetrics.map { maxOf(it.positionA, it.positionB) }.toFloatArray()
        val eccentricVelocities = eccentricMetrics.map { maxOf(kotlin.math.abs(it.velocityA.toFloat()), kotlin.math.abs(it.velocityB.toFloat())) }.toFloatArray()
        val eccentricTimestamps = eccentricMetrics.map { it.timestamp - repMetrics.first().timestamp }.toLongArray()
        val eccentricDurationMs = if (eccentricMetrics.size >= 2) {
            eccentricMetrics.last().timestamp - eccentricMetrics.first().timestamp
        } else {
            0L
        }

        // Calculate summary metrics
        val peakForceA = maxOf(concentricLoadsA.maxOrNull() ?: 0f, eccentricLoadsA.maxOrNull() ?: 0f)
        val peakForceB = maxOf(concentricLoadsB.maxOrNull() ?: 0f, eccentricLoadsB.maxOrNull() ?: 0f)
        val avgForceConcentricA = concentricLoadsA.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
        val avgForceConcentricB = concentricLoadsB.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
        val avgForceEccentricA = eccentricLoadsA.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
        val avgForceEccentricB = eccentricLoadsB.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f

        // ROM and velocity
        val rom = positions.max() - positions.min()
        val peakVelocity = concentricVelocities.maxOrNull() ?: 0f
        val avgVelocityConcentric = concentricVelocities.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f
        val avgVelocityEccentric = eccentricVelocities.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f

        // Power calculations (power = force * velocity, converting units)
        // Force in kg, velocity in mm/s -> W = kg * m/s^2 * m/s = kg * mm/s / 1000 * 9.81
        val concentricPowers = concentricMetrics.map { m ->
            val force = m.loadA + m.loadB // total load in kg
            val velocity = maxOf(kotlin.math.abs(m.velocityA), kotlin.math.abs(m.velocityB)) / 1000.0 // m/s
            (force * velocity * 9.81).toFloat() // watts
        }
        val peakPowerWatts = concentricPowers.maxOrNull() ?: 0f
        val avgPowerWatts = concentricPowers.takeIf { it.isNotEmpty() }?.average()?.toFloat() ?: 0f

        val totalDurationMs = if (repMetrics.size >= 2) {
            repMetrics.last().timestamp - repMetrics.first().timestamp
        } else {
            1000L
        }

        val repData = RepMetricData(
            repNumber = repNumber,
            isWarmup = !repCounter.getRepCount().isWarmupComplete,
            startTimestamp = repMetrics.firstOrNull()?.timestamp ?: 0L,
            endTimestamp = repMetrics.lastOrNull()?.timestamp ?: 0L,
            durationMs = totalDurationMs,
            concentricDurationMs = concentricDurationMs,
            concentricPositions = concentricPositions,
            concentricLoadsA = concentricLoadsA,
            concentricLoadsB = concentricLoadsB,
            concentricVelocities = concentricVelocities,
            concentricTimestamps = concentricTimestamps,
            eccentricDurationMs = eccentricDurationMs,
            eccentricPositions = eccentricPositions,
            eccentricLoadsA = eccentricLoadsA,
            eccentricLoadsB = eccentricLoadsB,
            eccentricVelocities = eccentricVelocities,
            eccentricTimestamps = eccentricTimestamps,
            peakForceA = peakForceA,
            peakForceB = peakForceB,
            avgForceConcentricA = avgForceConcentricA,
            avgForceConcentricB = avgForceConcentricB,
            avgForceEccentricA = avgForceEccentricA,
            avgForceEccentricB = avgForceEccentricB,
            peakVelocity = peakVelocity,
            avgVelocityConcentric = avgVelocityConcentric,
            avgVelocityEccentric = avgVelocityEccentric,
            rangeOfMotionMm = rom,
            peakPowerWatts = peakPowerWatts,
            avgPowerWatts = avgPowerWatts,
        )

        // Accumulate rep metric data for persistence at set completion
        coordinator.setRepMetrics.update { it + repData }

        val score = coordinator.repQualityScorer.scoreRep(repData)
        coordinator._latestRepQuality.value = score
        Logger.d { "Rep quality scored: rep=$repNumber, score=${score.composite}, concentricSamples=${concentricLoadsA.size}, eccentricSamples=${eccentricLoadsA.size}" }
    }

    /**
     * Process biomechanics analysis for a completed rep.
     *
     * Segments collectedMetrics using rep boundary timestamps, then processes
     * through BiomechanicsEngine on Dispatchers.Default (DATA-03 compliance).
     *
     * @param repNumber 1-indexed rep number
     * @param timestamp Rep completion timestamp
     */
    private fun processBiomechanicsForRep(
        lease: ExecutionLease,
        repNumber: Int,
        timestamp: Long,
    ) {
        if (!executionGuard.isCurrent(lease)) return
        val context = biomechanicsContextFor(lease) ?: return
        val allMetrics = coordinator.collectedMetrics.value
        val boundaries = coordinator.repBoundaryTimestamps.value
        if (boundaries.isEmpty()) {
            Logger.d { "Biomechanics: no rep boundary for rep $repNumber" }
            return
        }

        scope.launch(biomechanicsDispatcher) {
            if (!executionGuard.isCurrent(lease)) return@launch
            try {
                // Segment: metrics between previous boundary and current boundary
                val prevBoundary = if (boundaries.size >= 2) boundaries[boundaries.size - 2] else 0L
                val currentBoundary = boundaries.last()

                val repMetrics = allMetrics.filter { it.timestamp in (prevBoundary + 1)..currentBoundary }
                if (repMetrics.isEmpty()) {
                    Logger.d { "Biomechanics: no metrics for rep $repNumber (boundary $prevBoundary..$currentBoundary)" }
                    return@launch
                }

                // Split into concentric/eccentric using velocity direction
                // Concentric = lifting (positive velocity), Eccentric = lowering (negative velocity)
                // Approximate: use first half as concentric if we can't determine from velocity
                val concentricMetrics = repMetrics.filter {
                    it.velocityA > 0 || it.velocityB > 0
                }.takeIf { it.isNotEmpty() } ?: run {
                    // Fallback: first half is concentric
                    val midpoint = repMetrics.size / 2
                    if (midpoint > 0) repMetrics.take(midpoint) else repMetrics
                }

                if (!executionGuard.isCurrent(lease)) return@launch
                val result = biomechanicsRepProcessor.process(
                    engine = context.engine,
                    input = BiomechanicsRepInput(
                        repNumber = repNumber,
                        concentricMetrics = concentricMetrics,
                        allRepMetrics = repMetrics,
                        timestamp = timestamp,
                    ),
                )

                val published = executionGuard.commitIfCurrent(lease) {
                    if (biomechanicsContext.value === context) {
                        coordinator.publishBiomechanicsResult(context.engine, result)
                    }
                }
                if (!published || biomechanicsContext.value !== context) return@launch

                Logger.d { "Biomechanics processed: rep=$repNumber, metrics=${repMetrics.size}, concentric=${concentricMetrics.size}" }

                // Issue #313: Check velocity threshold for alert and auto-end.
                evaluateLatestVbtResult(lease, context, result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e) { "Biomechanics processing failed for rep $repNumber" }
            }
        }
    }

    internal suspend fun evaluateLatestVbtResult(lease: ExecutionLease) {
        val context = biomechanicsContextFor(lease) ?: return
        val latestResult = context.engine.latestRepResult.value ?: return
        evaluateLatestVbtResult(lease, context, latestResult)
    }

    private suspend fun evaluateLatestVbtResult(
        lease: ExecutionLease,
        context: ExecutionBiomechanicsContext,
        latestResult: BiomechanicsRepResult,
    ) {
        val verbalEvent = settingsManager.userPreferences.value.verbalEncouragementEventOrNull()
        var alertEmitted = false
        var verbalEmitted = false
        var shouldComplete = false
        var consecutiveReps = 0
        val alertEvents = mutableListOf<HapticEvent>()
        val committed = executionGuard.commitIfCurrent(
            lease = lease,
            beforeCommit = {
                beforeVbtCommit(lease.executionId, lease.sessionId, latestResult.repNumber)
            },
        ) {
            if (biomechanicsContext.value !== context || !coordinator._repCount.value.isWarmupComplete) {
                return@commitIfCurrent
            }
            val runtime = coordinator.vbtRuntimeSettings.value
            if (!runtime.enabled) return@commitIfCurrent
            val velocity = latestResult.velocity
            if (velocity.shouldStopSet) {
                context.consecutiveThresholdReps++
                if (!context.velocityThresholdAlertEmitted) {
                    context.velocityThresholdAlertEmitted = true
                    alertEvents += HapticEvent.VELOCITY_THRESHOLD_REACHED
                    alertEmitted = true
                    if (verbalEvent != null) {
                        alertEvents += verbalEvent
                        verbalEmitted = true
                        if (!runtime.autoEndOnVelocityLoss && coordinator._workoutState.value is WorkoutState.Active) {
                            coordinator.deferAutoStopDeadlineMs = currentTimeMillis() + VERBAL_ENCOURAGEMENT_DEFER_WINDOW_MS
                            resetStallTimer()
                            resetAutoStopTimer()
                        }
                    }
                }
                consecutiveReps = context.consecutiveThresholdReps
                shouldComplete = consecutiveReps >= 2 && runtime.autoEndOnVelocityLoss
            } else {
                context.consecutiveThresholdReps = 0
            }
        }
        if (!committed || biomechanicsContext.value !== context) return
        afterVbtDecisionCommit(lease.executionId, lease.sessionId, latestResult.repNumber)
        if (!emitVbtAlerts(lease, context, alertEvents)) return
        if (alertEmitted) {
            Logger.i { "VBT: Velocity loss threshold reached (${latestResult.velocity.velocityLossPercent?.roundToInt()}%). Alert emitted." }
        }
        if (verbalEmitted && verbalEvent != null) {
            Logger.i { "VBT: VERBAL_ENCOURAGEMENT emitted (tier=${verbalEvent.vulgarTier}, dominatrix=${verbalEvent.dominatrixMode}, vulgar=${verbalEvent.vulgarMode})" }
        }
        if (shouldComplete) {
            Logger.i { "VBT: Auto-ending set — $consecutiveReps consecutive reps above threshold" }
            handleSetCompletion(lease, SetEndReason.VBT_AUTO_END)
        }
    }

    private suspend fun emitVbtAlerts(
        lease: ExecutionLease,
        context: ExecutionBiomechanicsContext,
        events: List<HapticEvent>,
    ): Boolean {
        if (events.isEmpty()) {
            return executionGuard.isCurrent(lease) && biomechanicsContext.value === context
        }

        lateinit var deliveryJob: Job
        deliveryJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                events.forEach { event ->
                    if (!executionGuard.isCurrent(lease) || biomechanicsContext.value !== context) {
                        return@launch
                    }
                    coordinator._hapticEvents.emit(event)
                }
            } finally {
                executionGuard.clearAlertDeliveryJobIfOwned(lease, deliveryJob)
            }
        }
        if (!executionGuard.attachAlertDeliveryJob(lease, deliveryJob)) {
            deliveryJob.cancel()
            return false
        }
        deliveryJob.start()
        deliveryJob.join()
        return !deliveryJob.isCancelled &&
            executionGuard.isCurrent(lease) &&
            biomechanicsContext.value === context
    }

    // ===== Auto-Stop Detection =====

    /**
     * Handle monitor metric data (matches parent repo logic).
     * Called on every metric from the machine, regardless of workout state.
     */
    internal fun handleMonitorMetric(metric: WorkoutMetric) {
        val params = coordinator._workoutParameters.value
        val state = coordinator._workoutState.value

        if (params.useAutoStart && state is WorkoutState.Idle) {
            repCounter.updatePositionRangesContinuously(metric.positionA, metric.positionB)
            coordinator._repRanges.value = repCounter.getRepRanges()
        }

        if (state is WorkoutState.Active) {
            collectMetricForHistory(metric)

            Logger.d { "Issue221: handleMonitorMetric Active - isJustLift=${params.isJustLift}, isAMRAP=${params.isAMRAP}, isTimedCable=$coordinator.isCurrentTimedCableExercise, posA=${metric.positionA}, posB=${metric.positionB}" }
            if (params.isJustLift || params.isAMRAP || coordinator.isCurrentTimedCableExercise) {
                Logger.d { "Issue221: Calling updatePositionRangesContinuously" }
                repCounter.updatePositionRangesContinuously(metric.positionA, metric.positionB)
            }

            repCounter.updatePhaseFromPosition(metric.positionA, metric.positionB)
            coordinator._repCount.value = repCounter.getRepCount()
            coordinator._repRanges.value = repCounter.getRepRanges()

            // Issue #252: Record the moment warmup completes (once per set)
            if (coordinator.warmupCompleteTimeMs == 0L && coordinator._repCount.value.isWarmupComplete) {
                coordinator.warmupCompleteTimeMs = currentTimeMillis()
            }

            if (shouldEnableAutoStop(params)) {
                Logger.d { "Issue203 DEBUG: checkAutoStop called - isJustLift=${params.isJustLift}, isAMRAP=${params.isAMRAP}, isTimedCable=$coordinator.isCurrentTimedCableExercise, setIndex=${coordinator._currentSetIndex.value}" }
                executionGuard.currentLease?.let { lease -> checkAutoStop(lease, metric) }
            } else {
                resetAutoStopTimer()
                resetStallTimer()
            }

            if (repCounter.shouldStopWorkout()) {
                executionGuard.currentLease?.let { lease ->
                    handleSetCompletion(lease, SetEndReason.TARGET_REPS_REACHED)
                }
            }
        } else {
            resetAutoStopTimer()
        }
    }

    /**
     * Check if auto-stop should be triggered based on velocity stall detection OR position-based detection.
     */
    private fun checkAutoStop(lease: ExecutionLease, metric: WorkoutMetric) {
        if (!executionGuard.isCurrent(lease)) return
        if (coordinator._workoutState.value !is WorkoutState.Active) {
            resetAutoStopTimer()
            resetStallTimer()
            return
        }

        if (!isWarmupGateOpenForAutoStop()) {
            resetAutoStopTimer()
            resetStallTimer()
            return
        }

        // Issue #649: while a verbal VBT cue is still in flight and the user has VBT
        // auto-end OFF, neither AMRAP position nor velocity-stall may end the set.
        // The deadline field is the only source of truth — 0L means no defer.
        // Any reset path (next completed working rep, resetAutoStopState, per-set
        // boundary, deadline expiry) zeros it; this predicate then falls through.
        // Reset the live countdowns defensively each metric.
        val deferDeadline = coordinator.deferAutoStopDeadlineMs
        if (deferDeadline != 0L) {
            if (currentTimeMillis() >= deferDeadline) {
                coordinator.deferAutoStopDeadlineMs = 0L
            } else {
                resetStallTimer()
                resetAutoStopTimer()
                return
            }
        }

        val hasMeaningfulRange = repCounter.hasMeaningfulRange(WorkoutCoordinator.MIN_RANGE_THRESHOLD)
        val params = coordinator._workoutParameters.value
        val repCount = coordinator._repCount.value

        // ===== 1. VELOCITY-BASED STALL DETECTION =====
        if (params.stallDetectionEnabled && !shouldDeferStandardSetStall(params, repCount)) {
            val maxVelocity = maxOf(kotlin.math.abs(metric.velocityA), kotlin.math.abs(metric.velocityB))
            val isDefinitelyStalled = maxVelocity < WorkoutCoordinator.STALL_VELOCITY_LOW
            val isDefinitelyMoving = maxVelocity > WorkoutCoordinator.STALL_VELOCITY_HIGH

            val maxPosition = maxOf(metric.positionA, metric.positionB)
            // F4 (stall-detection audit): the handles must be in use RIGHT NOW for a
            // velocity stall — the old `|| hasMeaningfulRange` latch stayed true for
            // the rest of the set, so a racked pause between reps armed the countdown
            // and force-ended standard sets. Racked handles are the position path's
            // job (AMRAP/Just Lift); a genuine mid-set release is the deload path's job.
            val isActivelyUsing = maxPosition > WorkoutCoordinator.STALL_MIN_POSITION

            val inGrace = isInAmrapStartupGrace(hasMeaningfulRange)
            if (isDefinitelyStalled && isActivelyUsing && coordinator.stallStartTime == null && !inGrace) {
                coordinator.stallStartTime = currentTimeMillis()
                coordinator.isCurrentlyStalled = true
                coordinator.stallArmedByDeload = false
            } else if (isDefinitelyMoving && coordinator.stallStartTime != null) {
                resetStallTimer()
            }

            val startTime = coordinator.stallStartTime
            if (startTime != null) {
                // F4: re-check per sample — a velocity-armed countdown must not keep
                // running once the handles return to rest (racked pause). A deload-armed
                // countdown must survive this (real cable release retracts to ~0mm).
                if (!coordinator.stallArmedByDeload && maxPosition <= WorkoutCoordinator.STALL_MIN_POSITION) {
                    resetStallTimer()
                    return
                }

                val stallElapsed = (currentTimeMillis() - startTime) / 1000f

                if (stallElapsed >= WorkoutCoordinator.STALL_DURATION_SECONDS && !coordinator.autoStopTriggered) {
                    val terminalReason = if (coordinator.stallArmedByDeload) {
                        SetEndReason.CABLE_RELEASED
                    } else {
                        SetEndReason.STALL_FAILURE
                    }
                    requestAutoStop(lease, terminalReason)
                    return
                }

                if (stallElapsed >= 1.0f) {
                    val progress = (stallElapsed / WorkoutCoordinator.STALL_DURATION_SECONDS).coerceIn(0f, 1f)
                    val remaining = (WorkoutCoordinator.STALL_DURATION_SECONDS - stallElapsed).coerceAtLeast(0f)

                    coordinator._autoStopState.value = AutoStopUiState(
                        isActive = true,
                        progress = progress,
                        secondsRemaining = roundUpRemainingSeconds(remaining),
                    )
                }
            }
        } else {
            resetStallTimer()
        }

        if (!shouldRunPositionBasedAutoStop(params)) {
            resetAutoStopTimer()
            return
        }

        // ===== 2. POSITION-BASED DETECTION =====
        val maxPosition = maxOf(metric.positionA, metric.positionB)
        val handlesCompletelyAtRest = maxPosition < WorkoutCoordinator.HANDLE_REST_THRESHOLD

        val inGraceForPositionBased = isInAmrapStartupGrace(repCounter.hasMeaningfulRange(WorkoutCoordinator.MIN_RANGE_THRESHOLD))
        if (handlesCompletelyAtRest && !inGraceForPositionBased) {
            val startTime = coordinator.autoStopStartTime ?: run {
                coordinator.autoStopStartTime = currentTimeMillis()
                currentTimeMillis()
            }

            val elapsed = (currentTimeMillis() - startTime) / 1000f

            if (!coordinator.isCurrentlyStalled) {
                val progress = (elapsed / WorkoutCoordinator.AUTO_STOP_DURATION_SECONDS).coerceIn(0f, 1f)
                val remaining = (WorkoutCoordinator.AUTO_STOP_DURATION_SECONDS - elapsed).coerceAtLeast(0f)

                coordinator._autoStopState.value = AutoStopUiState(
                    isActive = true,
                    progress = progress,
                    secondsRemaining = roundUpRemainingSeconds(remaining),
                )
            }

            if (elapsed >= WorkoutCoordinator.AUTO_STOP_DURATION_SECONDS && !coordinator.autoStopTriggered) {
                requestAutoStop(lease, SetEndReason.CABLE_RELEASED)
            }
            return
        } else if (handlesCompletelyAtRest && inGraceForPositionBased) {
            Logger.v("AutoStop: Handles at rest but in startup grace period - waiting")
            resetAutoStopTimer()
        } else {
            resetAutoStopTimer()
        }

        consumeDangerZoneCountdownOverride(lease)?.let { startTime ->
            coordinator.autoStopStartTime = startTime
        }
        val inDangerZone = repCounter.isInDangerZone(metric.positionA, metric.positionB, WorkoutCoordinator.MIN_RANGE_THRESHOLD)
        val repRanges = repCounter.getRepRanges()

        var cableAppearsReleased = false

        repRanges.minPosA?.let { minA ->
            repRanges.maxPosA?.let { maxA ->
                val rangeA = maxA - minA
                if (rangeA > WorkoutCoordinator.MIN_RANGE_THRESHOLD) {
                    val thresholdA = minA + (rangeA * 0.05f)
                    val cableAInDanger = metric.positionA <= thresholdA
                    val cableAReleased = metric.positionA < WorkoutCoordinator.HANDLE_REST_THRESHOLD ||
                        (metric.positionA - minA) < 10
                    if (cableAInDanger && cableAReleased) {
                        cableAppearsReleased = true
                    }
                }
            }
        }

        if (!cableAppearsReleased) {
            repRanges.minPosB?.let { minB ->
                repRanges.maxPosB?.let { maxB ->
                    val rangeB = maxB - minB
                    if (rangeB > WorkoutCoordinator.MIN_RANGE_THRESHOLD) {
                        val thresholdB = minB + (rangeB * 0.05f)
                        val cableBInDanger = metric.positionB <= thresholdB
                        val cableBReleased = metric.positionB < WorkoutCoordinator.HANDLE_REST_THRESHOLD ||
                            (metric.positionB - minB) < 10
                        if (cableBInDanger && cableBReleased) {
                            cableAppearsReleased = true
                        }
                    }
                }
            }
        }

        if (inDangerZone && cableAppearsReleased) {
            val startTime = coordinator.autoStopStartTime ?: run {
                coordinator.autoStopStartTime = currentTimeMillis()
                currentTimeMillis()
            }

            val elapsed = (currentTimeMillis() - startTime) / 1000f

            if (!coordinator.isCurrentlyStalled) {
                val progress = (elapsed / WorkoutCoordinator.AUTO_STOP_DURATION_SECONDS).coerceIn(0f, 1f)
                val remaining = (WorkoutCoordinator.AUTO_STOP_DURATION_SECONDS - elapsed).coerceAtLeast(0f)

                coordinator._autoStopState.value = AutoStopUiState(
                    isActive = true,
                    progress = progress,
                    secondsRemaining = roundUpRemainingSeconds(remaining),
                )
            }

            if (elapsed >= WorkoutCoordinator.AUTO_STOP_DURATION_SECONDS && !coordinator.autoStopTriggered) {
                requestAutoStop(lease, SetEndReason.CABLE_RELEASED)
            }
        } else {
            resetAutoStopTimer()
        }
    }

    // ===== Weight Adjustment =====

    /**
     * Send weight update command to the machine.
     */
    private suspend fun sendWeightUpdateToMachine(weightKg: Float) {
        try {
            val params = coordinator._workoutParameters.value

            val command = if (!params.isEchoMode) {
                WorkoutCommandValidator.validateLegacyWorkoutCommand(
                    params.programMode,
                    weightKg,
                    params.reps,
                ).getOrThrow()
                BlePacketFactory.createWorkoutCommand(
                    params.programMode,
                    weightKg,
                    params.reps,
                )
            } else {
                return
            }

            bleRepository.sendWorkoutCommand(command).getOrThrow()
            Logger.d("Weight update sent to machine: $weightKg kg")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val errorPrefix = if (e is IllegalArgumentException) "Invalid BLE weight update" else "BLE weight update failed"
            coordinator._bleErrorEvents.tryEmit("$errorPrefix: ${e.message}")
            Logger.e(e) { "Failed to send weight update: ${e.message}" }
        }
    }

    /**
     * Adjust the weight during an active workout or rest period.
     *
     * If called during an active set, the BLE command is deferred until the next
     * set boundary. sendWeightUpdateToMachine() sends a full REGULAR_COMMAND packet
     * which resets the exercise on the machine (BLE exercise packet lifecycle constraint:
     * machine can't receive new exercise packet until active one fully ends).
     */
    fun adjustWeight(newWeightKg: Float, sendToMachine: Boolean = true) {
        // Upper bound is 110kg per cable to support both hardware variants:
        //   V-Form (VIT-200): 100kg max per cable
        //   Trainer+:         110kg max per cable
        // Do NOT replace with Constants.MAX_WEIGHT_KG (100f) — that would regress Trainer+ users.
        val clampedWeight = newWeightKg.coerceIn(0f, 110f)

        Logger.d("ActiveSessionEngine: Adjusting weight to $clampedWeight kg (sendToMachine=$sendToMachine)")

        var transitionState: WorkoutState? = null
        var deferredToSetBoundary = false
        executionGuard.mutateConfigurationInputs {
            val currentState = coordinator._workoutState.value
            if (currentState is WorkoutState.Idle ||
                currentState is WorkoutState.Resting ||
                currentState is WorkoutState.SetSummary
            ) {
                coordinator._userAdjustedWeightDuringRest = true
                transitionState = currentState
            }

            coordinator._workoutParameters.update { params ->
                params.copy(weightPerCableKg = clampedWeight)
            }

            if (sendToMachine && currentState is WorkoutState.Active) {
                // Defer BLE weight change to next set boundary. Sending a full workout
                // command (REGULAR_COMMAND) mid-set would fault the machine.
                coordinator.pendingWeightChangeKg = clampedWeight
                deferredToSetBoundary = true
            }
        }
        transitionState?.let { state ->
            Logger.d("ActiveSessionEngine: User adjusted weight in ${state::class.simpleName} - will preserve on next set")
        }
        if (deferredToSetBoundary) {
            Logger.d("ActiveSessionEngine: Deferred weight change to $clampedWeight kg (mid-set, will apply at next set start)")
        }
    }

    fun incrementWeight(amount: Float = 0.5f) {
        val currentWeight = coordinator._workoutParameters.value.weightPerCableKg
        adjustWeight(currentWeight + amount)
    }

    fun decrementWeight(amount: Float = 0.5f) {
        val currentWeight = coordinator._workoutParameters.value.weightPerCableKg
        adjustWeight(currentWeight - amount)
    }

    fun setWeightPreset(presetWeightKg: Float) {
        adjustWeight(presetWeightKg)
    }

    suspend fun getLastWeightForExercise(exerciseId: String): Float? {
        val profileId = userProfileRepository.activeProfile.value?.id ?: "default"
        return workoutRepository.getAllSessions(profileId = profileId)
            .first()
            .filter { it.exerciseId == exerciseId }
            .sortedByDescending { it.timestamp }
            .firstOrNull()
            ?.weightPerCableKg
    }

    suspend fun getPrWeightForExercise(exerciseId: String): Float? {
        val profileId = userProfileRepository.activeProfile.value?.id ?: "default"
        return workoutRepository.getAllPersonalRecords(profileId)
            .first()
            .filter { it.exerciseId == exerciseId }
            .maxOfOrNull { it.weightPerCableKg }
    }

    // ===== Just Lift =====

    fun enableHandleDetection() {
        val now = currentTimeMillis()
        if (now - coordinator.handleDetectionEnabledTimestamp < coordinator.HANDLE_DETECTION_DEBOUNCE_MS) {
            Logger.d("ActiveSessionEngine: Handle detection already enabled recently, skipping (idempotent)")
            return
        }
        coordinator.handleDetectionEnabledTimestamp = now
        Logger.d("ActiveSessionEngine: Enabling handle detection for auto-start")
        bleRepository.enableHandleDetection(true)
    }

    fun disableHandleDetection() {
        Logger.d("ActiveSessionEngine: Disabling handle detection")
        bleRepository.enableHandleDetection(false)
    }

    fun prepareForJustLift() {
        supersedeConfigurationInputIntent()
        scope.launch {
            val currentState = coordinator._workoutState.value
            val currentWeight = coordinator._workoutParameters.value.weightPerCableKg
            Logger.d("prepareForJustLift: BEFORE - weight=$currentWeight kg")

            if (currentState !is WorkoutState.Idle) {
                Logger.d("Preparing for Just Lift: Resetting from ${currentState::class.simpleName} to Idle")
                resetForNewWorkout()
            } else {
                Logger.d("Just Lift already in Idle state, ensuring auto-start is enabled")
            }
            val justLiftParams = coordinator._workoutParameters.value.copy(
                isJustLift = true,
                useAutoStart = true,
                selectedExerciseId = null,
            )
            executionGuard.mutateConfigurationInputs {
                coordinator._workoutState.value = WorkoutState.Idle
                coordinator.clearActiveRackSelection()
                coordinator._workoutParameters.value = justLiftParams
            }

            enableHandleDetection()
            val newWeight = coordinator._workoutParameters.value.weightPerCableKg
            Logger.d("prepareForJustLift: AFTER - weight=$newWeight kg")
            Logger.d("Just Lift ready: State=Idle, AutoStart=enabled, waiting for handle grab")
        }
    }

    suspend fun getJustLiftDefaults(): JustLiftDefaults = settingsManager.getJustLiftDefaultsDocument().toRuntimeJustLiftDefaults()

    fun saveJustLiftDefaults(defaults: JustLiftDefaults) {
        settingsManager.saveJustLiftDefaultsDocument(defaults.toDocument())
        Logger.d("saveJustLiftDefaults: weight=${defaults.weightPerCableKg}kg, mode=${defaults.workoutModeId}, restSeconds=${defaults.restSeconds}")
    }

    private fun JustLiftDefaults.toDocument() = JustLiftDefaultsDocument(
        workoutModeId = workoutModeId,
        weightPerCableKg = weightPerCableKg,
        weightChangePerRep = weightChangePerRep.toFloat(),
        eccentricLoadPercentage = eccentricLoadPercentage,
        echoLevelValue = echoLevelValue,
        stallDetectionEnabled = stallDetectionEnabled,
        repCountTimingName = repCountTimingName,
        restSeconds = restSeconds,
    )

    private fun JustLiftDefaultsDocument.toRuntimeJustLiftDefaults() = JustLiftDefaults(
        workoutModeId = workoutModeId,
        weightPerCableKg = weightPerCableKg,
        weightChangePerRep = weightChangePerRep.roundToInt(),
        eccentricLoadPercentage = eccentricLoadPercentage,
        echoLevelValue = echoLevelValue,
        stallDetectionEnabled = stallDetectionEnabled,
        repCountTimingName = repCountTimingName,
        restSeconds = restSeconds,
    )

    private suspend fun saveJustLiftDefaultsFromWorkout() {
        val params = coordinator._workoutParameters.value
        if (!params.isJustLift) return

        val eccentricLoadPct = if (params.isEchoMode) params.eccentricLoad.percentage else 100
        val echoLevelVal = if (params.isEchoMode) params.echoLevel.levelValue else 0

        try {
            val defaults = JustLiftDefaultsDocument(
                workoutModeId = params.programMode.modeValue,
                weightPerCableKg = params.weightPerCableKg.coerceAtLeast(0.1f),
                weightChangePerRep = params.progressionRegressionKg,
                eccentricLoadPercentage = eccentricLoadPct,
                echoLevelValue = echoLevelVal,
                stallDetectionEnabled = params.stallDetectionEnabled,
                repCountTimingName = params.repCountTiming.name,
                restSeconds = params.justLiftRestSeconds,
            )
            settingsManager.saveJustLiftDefaultsDocument(defaults)
            Logger.d { "Saved Just Lift defaults: mode=${params.programMode.modeValue}, weight=${params.weightPerCableKg}kg, restSeconds=${params.justLiftRestSeconds}" }
        } catch (e: Exception) {
            Logger.e(e) { "Failed to save Just Lift defaults: ${e.message}" }
        }
    }

    suspend fun getSingleExerciseDefaults(
        exerciseId: String,
    ): com.devil.phoenixproject.data.preferences.SingleExerciseDefaults? = settingsManager.getSingleExerciseDefaultsDocument(exerciseId)?.toLegacySingleExerciseDefaults()

    fun saveSingleExerciseDefaults(defaults: com.devil.phoenixproject.data.preferences.SingleExerciseDefaults) {
        settingsManager.saveSingleExerciseDefaultsDocument(defaults.toDocument())
        Logger.d("saveSingleExerciseDefaults: exerciseId=${defaults.exerciseId}")
    }

    private fun captureSingleExerciseDefaultsFromWorkout(): SingleExerciseDefaultsDocument? {
        val routine = coordinator._loadedRoutine.value ?: return null

        if (!routine.id.startsWith(DefaultWorkoutSessionManager.TEMP_SINGLE_EXERCISE_PREFIX)) return null

        val currentExercise = routine.exercises.getOrNull(coordinator._currentExerciseIndex.value) ?: return null
        val exerciseId = currentExercise.exercise.id ?: return null

        val isEchoExercise = currentExercise.programMode == ProgramMode.Echo
        val eccentricLoadPct = if (isEchoExercise) currentExercise.eccentricLoad.percentage else 100
        val echoLevelVal = if (isEchoExercise) currentExercise.echoLevel.levelValue else 0

        val setReps = currentExercise.setReps.ifEmpty { listOf(10) }
        val numSets = setReps.size

        val normalizedSetWeights = when {
            currentExercise.setWeightsPerCableKg.isEmpty() -> emptyList()
            currentExercise.setWeightsPerCableKg.size == numSets -> currentExercise.setWeightsPerCableKg
            else -> emptyList()
        }

        val normalizedSetRest = when {
            currentExercise.setRestSeconds.isEmpty() -> emptyList()
            currentExercise.setRestSeconds.size == numSets -> currentExercise.setRestSeconds
            else -> emptyList()
        }

        return com.devil.phoenixproject.data.preferences.SingleExerciseDefaults(
            exerciseId = exerciseId,
            setReps = setReps.toList(),
            weightPerCableKg = currentExercise.weightPerCableKg.coerceAtLeast(0f),
            setWeightsPerCableKg = normalizedSetWeights.toList(),
            progressionKg = currentExercise.progressionKg.coerceIn(-50f, 50f),
            setRestSeconds = normalizedSetRest.toList(),
            workoutModeId = currentExercise.programMode.modeValue,
            eccentricLoadPercentage = eccentricLoadPct,
            echoLevelValue = echoLevelVal,
            duration = currentExercise.duration?.takeIf { it > 0 } ?: 0,
            isAMRAP = currentExercise.isAMRAP,
            perSetRestTime = currentExercise.perSetRestTime,
            defaultRackItemIds = currentExercise.defaultRackItemIds.filter { it.isNotBlank() }.distinct(),
        ).toDocument()
    }

    private suspend fun saveSingleExerciseDefaultsFromWorkout() {
        val defaults = captureSingleExerciseDefaultsFromWorkout() ?: return
        try {
            settingsManager.saveSingleExerciseDefaultsDocument(defaults)
            Logger.d { "Saved Single Exercise defaults for ${defaults.exerciseId}" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            Logger.e(e) { "Failed to save Single Exercise defaults - validation error" }
        } catch (e: Exception) {
            Logger.e(e) { "Failed to save Single Exercise defaults: ${e.message}" }
        }
    }

    // ===== Training Cycles =====

    fun loadRoutineFromCycle(routineId: String, cycleId: String, dayNumber: Int) {
        supersedeConfigurationInputIntent()
        // Template-created cycle routines (id prefix "cycle_routine_") are intentionally
        // filtered out of coordinator._routines (Daily Routines UI hygiene), so a StateFlow
        // miss must fall back to a direct DB lookup. See issue #620.
        val routine = coordinator._routines.value.find { it.id == routineId }
        if (routine != null) {
            executionGuard.mutateConfigurationInputs {
                coordinator.activeCycleId = cycleId
                coordinator.activeCycleDayNumber = dayNumber
            }
            Logger.d { "Loading routine from cycle: cycleId=$cycleId, dayNumber=$dayNumber" }
            // flowDelegate.loadRoutine sets DAILY_ROUTINES synchronously; overwrite to TRAINING_CYCLES after.
            flowDelegate?.loadRoutine(routine)
            coordinator.routineLaunchOrigin = RoutineLaunchOrigin.TRAINING_CYCLES
        } else {
            scope.launch {
                val dbRoutine = workoutRepository.getRoutineById(routineId)
                if (dbRoutine == null) {
                    Logger.w { "Routine not found for cycle load (StateFlow or DB): $routineId" }
                    return@launch
                }
                executionGuard.mutateConfigurationInputs {
                    coordinator.activeCycleId = cycleId
                    coordinator.activeCycleDayNumber = dayNumber
                }
                Logger.d { "Loading routine from cycle via DB fallback: cycleId=$cycleId, dayNumber=$dayNumber" }
                flowDelegate?.loadRoutine(dbRoutine)
                coordinator.routineLaunchOrigin = RoutineLaunchOrigin.TRAINING_CYCLES
            }
        }
    }

    /**
     * Suspend version of [loadRoutineFromCycle] that completes only after PR-based
     * weight resolution finishes. Callers must await this before enterSetReady/startWorkout.
     */
    suspend fun loadRoutineFromCycleAsync(routineId: String, cycleId: String, dayNumber: Int): Boolean {
        supersedeConfigurationInputIntent()
        // DB fallback: template-created cycle routines ("cycle_routine_" prefix) are filtered
        // out of coordinator._routines, so they can only be found via direct lookup. Issue #620.
        val routine = coordinator._routines.value.find { it.id == routineId }
            ?: workoutRepository.getRoutineById(routineId)
            ?: run {
                Logger.w { "Routine not found for cycle load (StateFlow or DB): $routineId" }
                return false
            }
        executionGuard.mutateConfigurationInputs {
            coordinator.activeCycleId = cycleId
            coordinator.activeCycleDayNumber = dayNumber
        }
        Logger.d { "Loading routine from cycle (async): cycleId=$cycleId, dayNumber=$dayNumber" }
        // flowDelegate.loadRoutineAsync sets DAILY_ROUTINES; overwrite to TRAINING_CYCLES after it returns.
        val result = flowDelegate?.loadRoutineAsync(routine) ?: false
        coordinator.routineLaunchOrigin = RoutineLaunchOrigin.TRAINING_CYCLES
        return result
    }

    internal suspend fun loadRoutineFromCycleForResumeAsync(
        routine: Routine,
        cycleId: String,
        dayNumber: Int,
        publicationStillCurrent: () -> Boolean,
    ): Boolean {
        if (routine.exercises.isEmpty() || cycleId.isBlank() || dayNumber <= 0 ||
            !publicationStillCurrent()
        ) {
            return false
        }
        val result = flowDelegate?.loadRoutineForResumeAsync(
            routine = routine,
            launchOrigin = RoutineLaunchOrigin.TRAINING_CYCLES,
            cycleId = cycleId,
            cycleDayNumber = dayNumber,
            publicationStillCurrent = publicationStillCurrent,
        ) ?: false
        currentCoroutineContext().ensureActive()
        return result && publicationStillCurrent()
    }

    fun clearCycleContext() {
        flowDelegate?.clearCycleContext()
    }

    private fun shouldUpdateCycleProgressAfterSavedSet(
        cycleId: String? = coordinator.activeCycleId,
        dayNumber: Int? = coordinator.activeCycleDayNumber,
    ): Boolean {
        if (cycleId == null || dayNumber == null) {
            return false
        }

        // Routine completion is now decided from the one cached, durably-plan-owned
        // successor.  An exit snapshot must not navigate or advance a cycle early.
        return coordinator._loadedRoutine.value == null
    }

    private suspend fun updateCycleProgressIfNeeded(
        cycleId: String? = coordinator.activeCycleId,
        dayNumber: Int? = coordinator.activeCycleDayNumber,
    ) {
        val resolvedCycleId = cycleId ?: return
        val resolvedDayNumber = dayNumber ?: return

        // A final transition may complete after a new routine has replaced the
        // coordinator context. Only clear the fields when they still name this
        // captured source; never consume a newer cycle's identity.
        executionGuard.mutateConfigurationInputs {
            if (coordinator.activeCycleId == resolvedCycleId &&
                coordinator.activeCycleDayNumber == resolvedDayNumber
            ) {
                coordinator.activeCycleId = null
                coordinator.activeCycleDayNumber = null
            }
        }

        updateCycleProgress(resolvedCycleId, resolvedDayNumber)
    }

    private suspend fun updateCycleProgress(cycleId: String, dayNumber: Int) {
        try {
            val cycle = trainingCycleRepository.getCycleById(cycleId)
            val progress = trainingCycleRepository.getCycleProgress(cycleId)

            if (cycle != null && progress != null) {
                val updated = progress.markDayCompleted(dayNumber)
                trainingCycleRepository.updateCycleProgress(updated)

                val completedDay = cycle.days.find { it.dayNumber == dayNumber }
                val lastWorkoutBearingDayNumber = cycle.days
                    .asSequence()
                    .filter { !it.isRestDay && it.routineId != null }
                    .maxOfOrNull { it.dayNumber }
                val isGenericRotationComplete = dayNumber >= cycle.days.size
                val isFiveThreeOneWorkoutRotationComplete = lastWorkoutBearingDayNumber != null &&
                    dayNumber == lastWorkoutBearingDayNumber &&
                    cycle.isFiveThreeOneCycleForProgress()
                val isRotationComplete = isGenericRotationComplete || isFiveThreeOneWorkoutRotationComplete
                val newRotationCount = if (isRotationComplete) progress.rotationCount + 1 else progress.rotationCount
                val targetWeek = if (isFiveThreeOneWorkoutRotationComplete) {
                    if (cycle.weekNumber >= 4) 1 else cycle.weekNumber + 1
                } else {
                    (newRotationCount % 4) + 1
                }
                val bumpTrainingMax = isFiveThreeOneWorkoutRotationComplete && cycle.weekNumber == 4
                var regenerationSucceeded = false

                if (
                    isFiveThreeOneWorkoutRotationComplete &&
                    cycle.weekNumber != targetWeek
                ) {
                    try {
                        regenerateFiveThreeOneUseCase?.let { useCase ->
                            regenerationSucceeded = useCase.execute(
                                cycleId = cycleId,
                                targetWeek = targetWeek,
                                bumpTrainingMax = bumpTrainingMax,
                            )
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (e: Exception) {
                        Logger.w(e) {
                            "5/3/1 regeneration failed after cycle completion: cycleId=$cycleId targetWeek=$targetWeek"
                        }
                    }
                }

                coordinator._cycleDayCompletionEvent.value = CycleDayCompletionEvent(
                    dayNumber = dayNumber,
                    dayName = completedDay?.name,
                    isRotationComplete = isRotationComplete,
                    rotationCount = newRotationCount,
                    newWeekNumber = if (regenerationSucceeded) targetWeek else null,
                    tmBumped = regenerationSucceeded && bumpTrainingMax,
                )

                Logger.d {
                    "Cycle progress updated: day $dayNumber completed, now on day ${updated.currentDayNumber}" +
                        if (isRotationComplete) " (rotation $newRotationCount complete!)" else ""
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            Logger.e(e) { "Error updating cycle progress: ${e.message}" }
        }
    }

    private suspend fun TrainingCycle.isFiveThreeOneCycleForProgress(): Boolean {
        if (templateId == TEMPLATE_531_ID) {
            return true
        }

        val matchedLiftIds = mutableSetOf<String>()
        for (day in days) {
            if (day.isRestDay) {
                continue
            }

            val routineId = day.routineId ?: continue
            val routine = workoutRepository.getRoutineById(routineId) ?: continue
            for (exercise in routine.exercises) {
                FiveThreeOneRoutineDetector.knownShapeMainLiftId(exercise)?.let { matchedLiftIds += it }
            }
        }

        return matchedLiftIds.containsAll(FiveThreeOneRoutineDetector.MAIN_LIFT_IDS)
    }

    // ===== Form Check =====

    // ===== Core Workout Lifecycle =====

    fun resetForNewWorkout() {
        val cleanupCandidate = runtimeCleanupCandidateRef.value
        val restoredTimerOwner = restoredRestTimerOwnerRef.value
        val resetToken = executionGuard.supersedeRecoveryPublicationAndCaptureResetCleanupToken()
        val cleanupTarget = cleanupCandidate?.let { candidate ->
            installRuntimeCleanupIntent(
                captureRuntimeCleanupTarget(candidate, RuntimeCleanupReason.EXPLICIT_RESTART),
            )
        }
        executionGuard.supersedeQueuedSuccessors()
        supersedePendingResetStart()
        cleanupTarget?.let(::launchRuntimeCleanup)
        if (acceptedRetryStartClaim.value != null && coordinator.workoutJob?.isActive == true) {
            afterResetCleanupTokenCaptureForTest?.invoke()
            restoredTimerOwner?.let(::detachRestoredRestTimerIfExact)?.cancel()
            coordinator.workoutJob?.cancel(CancellationException("Accepted retry reset requested"))
            return
        }
        val lease = resetToken.lease
        afterResetCleanupTokenCaptureForTest?.invoke()
        restoredTimerOwner?.let(::detachRestoredRestTimerIfExact)?.cancel()
        coordinator.workoutJob?.cancel(CancellationException("Workout reset requested"))
        coordinator.workoutJob = null
        lease?.let(bodyweightCompletionGate::invalidate)
        lease?.let(::clearDangerZoneCountdownOverride)
        if (lease?.requiresMachine == true) {
            val resetOwner = ResetMachineTeardownOwner(
                id = resetMachineTeardownOwnerSequence.incrementAndGet(),
                lease = lease,
            )
            if (resetMachineTeardownOwner.compareAndSet(null, resetOwner) &&
                !beginMachineTeardown(lease, TeardownReason.RECOVERY)
            ) {
                resetMachineTeardownOwner.compareAndSet(resetOwner, null)
            }
        }
        val invalidatedLease = lease?.takeIf {
            executionGuard.invalidate(it, ExecutionInvalidationReason.RESET_FOR_NEW_WORKOUT)
        }
        lease?.let(::discardTeardownReadyContinuation)
        if (invalidatedLease != null) {
            afterResetInvalidation(invalidatedLease.executionId, invalidatedLease.sessionId)
            repFreshnessGate.invalidate(invalidatedLease)
            detachBiomechanicsContext(invalidatedLease)
            if (executionContext?.lease?.sameExecutionAs(invalidatedLease) == true) {
                executionContext = null
            }
        }
        if (lease != null && invalidatedLease == null) return
        executionGuard.commitResetCleanupIfNoSuccessor(resetToken, invalidatedLease) {
            clearSharedStateForNewWorkout()
        }
    }

    private fun clearSharedStateForNewWorkout() {
        cancelJustLiftEggTimer()
        interruptedSetRecovery = null
        pendingStartOverride = null
        coordinator.currentSessionId = null
        coordinator.workoutStartTime = 0
        coordinator.collectedMetrics.value = emptyList()
        coordinator.restDeadlineElapsedRealtimeMs = null
        coordinator._restSecondsRemaining.value = 0
        coordinator._restOriginalDuration.value = 0
        coordinator._isRestPaused.value = false
        coordinator._workoutState.value = WorkoutState.Idle
        coordinator._weightAdjustmentRecommendation.value = null
        coordinator._repCount.value = RepCount()
        coordinator._repRanges.value = null
        coordinator.setRepMetrics.value = emptyList()
        coordinator.deferAutoStopDeadlineMs = 0L
        coordinator.repBoundaryTimestamps.value = emptyList()
        coordinator.warmupCompleteTimeMs = 0
        // Reset variable warm-up state
        coordinator._currentWarmupSetIndex.value = -1
        coordinator._totalWarmupSets.value = 0
        coordinator._selectedBodyweightVariants.value = emptyMap()
        coordinator.bodyweightCompletionVariantOverride = null
        coordinator.clearActiveRackSelection()
    }

    fun recaptureLoadBaseline() {
        coordinator._currentMetric.value?.let { metric ->
            coordinator._loadBaselineA.value = metric.loadA
            coordinator._loadBaselineB.value = metric.loadB
            Logger.d("ActiveSessionEngine") { "LOAD BASELINE: Manually recaptured loadA=${metric.loadA}kg, loadB=${metric.loadB}kg" }
        }
    }

    fun resetLoadBaseline() {
        coordinator._loadBaselineA.value = 0f
        coordinator._loadBaselineB.value = 0f
        Logger.d("ActiveSessionEngine") { "LOAD BASELINE: Reset to 0 (disabled)" }
    }

    private fun clampUpcomingProgressionKg(valueKg: Float): Float = valueKg.coerceIn(-3f, 3f)

    fun updateWorkoutParameters(params: WorkoutParameters) {
        supersedeConfigurationInputIntent()
        // Defense: reject near-zero weight writes in Just Lift mode.
        // The JustLiftScreen param-sync LaunchedEffect can fire before defaults
        // are loaded, writing the hardcoded initial value (0.453592f). Guard
        // against this race by preserving the current weight when the incoming
        // value is suspiciously low and Just Lift mode is active.
        val safeParams = if (params.isJustLift && params.weightPerCableKg < Constants.JUST_LIFT_MIN_VALID_WEIGHT_KG) {
            val currentWeight = coordinator._workoutParameters.value.weightPerCableKg
            if (currentWeight >= Constants.JUST_LIFT_MIN_VALID_WEIGHT_KG) {
                Logger.w { "updateWorkoutParameters: Rejected near-zero weight ${params.weightPerCableKg}kg in Just Lift — preserving ${currentWeight}kg" }
                params.copy(weightPerCableKg = currentWeight)
            } else {
                params
            }
        } else {
            params
        }

        val currentState = coordinator._workoutState.value
        val preserveDuringTransition = currentState is WorkoutState.Idle ||
            currentState is WorkoutState.Resting ||
            currentState is WorkoutState.SetSummary
        executionGuard.mutateConfigurationInputs {
            if (preserveDuringTransition) {
                coordinator._userAdjustedWeightDuringRest = true
            }
            coordinator._workoutParameters.value = safeParams
        }
        if (preserveDuringTransition) {
            Logger.d("updateWorkoutParameters: User edited params in ${currentState::class.simpleName} - will preserve on transition")
        }
    }

    fun updateActiveRackSelection(itemIds: List<String>) {
        supersedeConfigurationInputIntent()
        // Issue #534: For body-weight exercises, recompute _currentRackLoadAdjustment
        // synchronously when the user toggles a vest / counterweight on the live-set
        // screen, so that applyBodyweightVolume (called from confirmBodyweightSetResult)
        // sees the new mass on the body-weight post-timer path.
        //
        // For cable exercises the rack-load snapshot is locked at startWorkout time
        // (see DWSMEquipmentRackTest "set start snapshot"): the BLE machine is already
        // lifting at the set-start weight, so a mid-set chip toggle must NOT mutate
        // the saved load fields on the session. We still update the active IDs
        // (so the next set / chip UI shows the new state) but skip the adjustment
        // recompute and skip the workoutParameters mirror copy.
        val distinctIds = itemIds.filter { it.isNotBlank() }.distinct()
        val currentExercise = coordinator._loadedRoutine.value
            ?.exercises
            ?.getOrNull(coordinator._currentExerciseIndex.value)
        val isBodyweight = currentExercise?.exercise?.isBodyweight == true
        if (isBodyweight) {
            val resolvedItems = equipmentRackRepository.rackItems.value
                .filter { it.enabled && it.id in distinctIds }
            val currentParams = coordinator._workoutParameters.value
            val physicalCableCount = currentExercise?.exercise?.preferredCableCount ?: 1
            val adjustment = applyEquipmentRackLoadUseCase.calculate(
                programmedWeightPerCableKg = currentParams.weightPerCableKg,
                physicalCableCount = physicalCableCount,
                selectedItems = resolvedItems,
                isEchoMode = currentParams.isEchoMode,
                validatorMinimumPerCableKg = validatorSafeMinimum(currentParams),
                behaviorOverrides = coordinator._activeRackBehaviorOverrides.value,
            )
            val itemsJson = rackJson.encodeToString(
                ListSerializer(RackItem.serializer()),
                resolvedItems,
            )
            executionGuard.mutateConfigurationInputs {
                coordinator.setActiveRackSelection(
                    itemIds = distinctIds,
                    precomputedAdjustment = adjustment,
                    precomputedItemsJson = itemsJson,
                )
            }
        } else {
            executionGuard.mutateConfigurationInputs {
                coordinator.setActiveRackSelection(distinctIds)
            }
        }
    }

    fun updateActiveRackBehaviorOverrides(overrides: Map<String, RackItemBehavior>) {
        publishLoadedRoutineRackBehaviorOverrides(
            updatedRoutine = null,
            overrides = overrides,
        )
    }

    fun updateLoadedRoutineRackBehaviorOverrides(
        updatedRoutine: Routine,
        overrides: Map<String, RackItemBehavior>,
    ) {
        publishLoadedRoutineRackBehaviorOverrides(
            updatedRoutine = updatedRoutine,
            overrides = overrides,
        )
    }

    private fun publishLoadedRoutineRackBehaviorOverrides(
        updatedRoutine: Routine?,
        overrides: Map<String, RackItemBehavior>,
    ) {
        supersedeConfigurationInputIntent()
        val routine = updatedRoutine ?: coordinator._loadedRoutine.value
        val currentExercise = routine
            ?.exercises
            ?.getOrNull(coordinator._currentExerciseIndex.value)
        val activeIds = coordinator._activeRackItemIds.value.toList()
        val rackUpdate = currentExercise?.let { exercise ->
            val resolvedItems = equipmentRackRepository.rackItems.value
                .filter { it.enabled && it.id in activeIds }
            val currentParams = coordinator._workoutParameters.value
            val adjustment = applyEquipmentRackLoadUseCase.calculate(
                programmedWeightPerCableKg = currentParams.weightPerCableKg,
                physicalCableCount = exercise.exercise.preferredCableCount ?: 1,
                selectedItems = resolvedItems,
                isEchoMode = currentParams.isEchoMode,
                validatorMinimumPerCableKg = validatorSafeMinimum(currentParams),
                behaviorOverrides = overrides,
            )
            adjustment to rackJson.encodeToString(
                ListSerializer(RackItem.serializer()),
                resolvedItems,
            )
        }
        executionGuard.mutateConfigurationInputs {
            if (updatedRoutine != null) {
                coordinator._loadedRoutine.value = updatedRoutine
            }
            coordinator._activeRackBehaviorOverrides.value = overrides
            rackUpdate?.let { (adjustment, itemsJson) ->
                coordinator.setActiveRackSelection(
                    itemIds = activeIds,
                    precomputedAdjustment = adjustment,
                    precomputedItemsJson = itemsJson,
                )
            }
        }
    }

    fun clearActiveRackSelection() {
        executionGuard.mutateConfigurationInputs {
            coordinator.clearActiveRackSelection()
        }
    }

    /**
     * Internal parameter updates used by manager-driven transitions.
     *
     * Unlike [updateWorkoutParameters], this intentionally does NOT mark the
     * parameters as user-adjusted during rest.
     */
    fun setWorkoutParametersInternal(params: WorkoutParameters) {
        executionGuard.mutateConfigurationInputs {
            coordinator._userAdjustedWeightDuringRest = false
            coordinator._workoutParameters.value = params
        }
    }

    internal fun mutateConfigurationInputs(block: () -> Unit) {
        executionGuard.mutateConfigurationInputs(block)
    }

    internal fun mutateConfigurationInputsIf(
        candidateStillCurrent: () -> Boolean,
        block: () -> Unit,
    ): Boolean = executionGuard.mutateConfigurationInputsIf(candidateStillCurrent, block)

    internal fun <T> captureConfigurationInputs(block: () -> T): ConfigurationInputCapture<T> = executionGuard.captureConfigurationInputs(block)

    internal fun supersedeConfigurationInputIntent() {
        executionGuard.mutateConfigurationInputs { }
    }

    internal fun beginConfigurationInputMutation(): ConfigurationInputMutationToken = executionGuard.beginConfigurationInputMutation()

    internal fun endConfigurationInputMutation(token: ConfigurationInputMutationToken) {
        executionGuard.endConfigurationInputMutation(token)
    }

    fun captureInterruptedWorkoutForRecovery() {
        val routine = coordinator._loadedRoutine.value
        if (coordinator._workoutState.value !is WorkoutState.Active || routine == null) {
            interruptedSetRecovery = null
            return
        }

        interruptedSetRecovery = InterruptedSetRecoverySnapshot(
            routineId = routine.id,
            exerciseIndex = coordinator._currentExerciseIndex.value,
            setIndex = coordinator._currentSetIndex.value,
            warmupSetIndex = coordinator._currentWarmupSetIndex.value,
            repCount = coordinator._repCount.value,
        )
        Logger.w {
            "Captured interrupted set recovery snapshot: routine=${routine.name}, " +
                "exerciseIndex=${coordinator._currentExerciseIndex.value}, setIndex=${coordinator._currentSetIndex.value}, " +
                "warmupSetIndex=${coordinator._currentWarmupSetIndex.value}, reps=${coordinator._repCount.value}"
        }
    }

    fun reconnectInterruptedWorkout() {
        supersedeConfigurationInputIntent()
        val plan = buildInterruptedWorkoutRecoveryPlan()
        applyInterruptedWorkoutRecoveryPlan(plan)
    }

    private fun buildInterruptedWorkoutRecoveryPlan(): InterruptedWorkoutRecoveryPlan {
        val snapshot = interruptedSetRecovery
        val routine = coordinator._loadedRoutine.value
        if (snapshot == null || routine == null || routine.id != snapshot.routineId) {
            return InterruptedWorkoutRecoveryPlan.EnterSetReady(
                exerciseIndex = coordinator._currentExerciseIndex.value,
                setIndex = coordinator._currentSetIndex.value,
                adjustedWeight = coordinator._workoutParameters.value.weightPerCableKg,
                adjustedReps = coordinator._workoutParameters.value.reps,
                feedback = "Phoenix reconnected, but couldn't rebuild the interrupted set. Restart it from Set Ready.",
            )
        }

        val exercise = routine.exercises.getOrNull(snapshot.exerciseIndex) ?: return InterruptedWorkoutRecoveryPlan.EnterSetReady(
            exerciseIndex = snapshot.exerciseIndex,
            setIndex = snapshot.setIndex,
            adjustedWeight = coordinator._workoutParameters.value.weightPerCableKg,
            adjustedReps = coordinator._workoutParameters.value.reps,
            feedback = "Phoenix reconnected, but the interrupted exercise could not be resolved safely.",
        )

        val baseParams = buildRoutineRecoveryBaseParameters(exercise, snapshot.setIndex)
        if (isBodyweightExercise(exercise) || exercise.duration != null || baseParams.isAMRAP || baseParams.isJustLift) {
            return InterruptedWorkoutRecoveryPlan.EnterSetReady(
                exerciseIndex = snapshot.exerciseIndex,
                setIndex = snapshot.setIndex,
                adjustedWeight = baseParams.weightPerCableKg,
                adjustedReps = baseParams.reps,
                feedback = "Phoenix reconnected, but this set type needs a manual restart from Set Ready.",
            )
        }

        return if (snapshot.warmupSetIndex >= 0) {
            buildVariableWarmupRecoveryPlan(snapshot, routine, exercise, baseParams)
        } else {
            buildStandardRecoveryPlan(snapshot, routine, exercise, baseParams)
        }
    }

    private fun buildStandardRecoveryPlan(
        snapshot: InterruptedSetRecoverySnapshot,
        routine: Routine,
        exercise: RoutineExercise,
        baseParams: WorkoutParameters,
    ): InterruptedWorkoutRecoveryPlan {
        val warmupRemaining = (baseParams.warmupReps - snapshot.repCount.warmupReps).coerceAtLeast(0)
        val workingRemaining = if (warmupRemaining > 0) {
            baseParams.reps
        } else {
            (baseParams.reps - snapshot.repCount.workingReps).coerceAtLeast(0)
        }

        if (warmupRemaining == 0 && workingRemaining == 0) {
            return nextRoutineStepPlan(
                routine = routine,
                exercise = exercise,
                exerciseIndex = snapshot.exerciseIndex,
                setIndex = snapshot.setIndex,
                feedback = "Phoenix reconnected after the set had already completed. Continue from the next step.",
            )
        }

        return InterruptedWorkoutRecoveryPlan.Resume(
            params = baseParams.copy(
                warmupReps = warmupRemaining,
                reps = workingRemaining,
            ),
            warmupSetIndex = -1,
            preserveWarmupReps = true,
            skipVariableWarmupOverride = false,
        )
    }

    private fun buildVariableWarmupRecoveryPlan(
        snapshot: InterruptedSetRecoverySnapshot,
        routine: Routine,
        exercise: RoutineExercise,
        baseParams: WorkoutParameters,
    ): InterruptedWorkoutRecoveryPlan {
        val warmupSet = exercise.warmupSets.getOrNull(snapshot.warmupSetIndex)
            ?: return InterruptedWorkoutRecoveryPlan.EnterSetReady(
                exerciseIndex = snapshot.exerciseIndex,
                setIndex = snapshot.setIndex,
                adjustedWeight = baseParams.weightPerCableKg,
                adjustedReps = baseParams.reps,
                feedback = "Phoenix reconnected, but the interrupted warm-up set could not be rebuilt safely.",
            )

        val completedReps = snapshot.repCount.workingReps
        val remainingReps = (warmupSet.reps - completedReps).coerceAtLeast(0)
        if (remainingReps > 0) {
            return InterruptedWorkoutRecoveryPlan.Resume(
                params = buildWarmupOverrideParams(
                    baseParams = baseParams,
                    workingWeightKg = baseParams.weightPerCableKg,
                    warmupSet = warmupSet,
                    reps = remainingReps,
                ),
                warmupSetIndex = snapshot.warmupSetIndex,
                preserveWarmupReps = true,
                skipVariableWarmupOverride = true,
            )
        }

        val nextWarmupIndex = snapshot.warmupSetIndex + 1
        if (nextWarmupIndex < exercise.warmupSets.size) {
            return InterruptedWorkoutRecoveryPlan.Resume(
                params = buildWarmupOverrideParams(
                    baseParams = baseParams,
                    workingWeightKg = baseParams.weightPerCableKg,
                    warmupSet = exercise.warmupSets[nextWarmupIndex],
                    reps = exercise.warmupSets[nextWarmupIndex].reps,
                ),
                warmupSetIndex = nextWarmupIndex,
                preserveWarmupReps = true,
                skipVariableWarmupOverride = true,
            )
        }

        return InterruptedWorkoutRecoveryPlan.Resume(
            params = baseParams,
            warmupSetIndex = -1,
            preserveWarmupReps = true,
            skipVariableWarmupOverride = false,
        )
    }

    private fun nextRoutineStepPlan(
        routine: Routine,
        exercise: RoutineExercise,
        exerciseIndex: Int,
        setIndex: Int,
        feedback: String,
    ): InterruptedWorkoutRecoveryPlan {
        val nextStep = flowDelegate?.getNextStep(routine, exerciseIndex, setIndex)
        if (nextStep == null) {
            return InterruptedWorkoutRecoveryPlan.ShowRoutineComplete(feedback)
        }

        val (nextExerciseIndex, nextSetIndex) = nextStep
        val nextExercise = routine.exercises.getOrNull(nextExerciseIndex) ?: return InterruptedWorkoutRecoveryPlan.ShowRoutineComplete(feedback)
        val nextParams = buildRoutineRecoveryBaseParameters(nextExercise, nextSetIndex)
        return InterruptedWorkoutRecoveryPlan.EnterSetReady(
            exerciseIndex = nextExerciseIndex,
            setIndex = nextSetIndex,
            adjustedWeight = nextParams.weightPerCableKg,
            adjustedReps = nextParams.reps,
            feedback = feedback,
        )
    }

    private fun buildRoutineRecoveryBaseParameters(exercise: RoutineExercise, setIndex: Int): WorkoutParameters {
        val rawSetReps = exercise.setReps.getOrNull(setIndex)
        val resolvedSetReps = rawSetReps ?: exercise.reps
        return coordinator._workoutParameters.value.copy(
            programMode = exercise.programMode,
            weightPerCableKg = resolveOccurrenceSetWeight(exercise, setIndex),
            reps = resolvedSetReps,
            warmupReps = Constants.DEFAULT_WARMUP_REPS,
            echoLevel = exercise.getEchoLevelForSet(setIndex),
            eccentricLoad = exercise.eccentricLoad,
            selectedExerciseId = exercise.exercise.id,
            stallDetectionEnabled = exercise.stallDetectionEnabled,
            repCountTiming = exercise.repCountTiming,
            stopAtTop = exercise.stopAtTop,
            isAMRAP = rawSetReps == null,
            progressionRegressionKg = exercise.progressionKg,
            isJustLift = false,
            useAutoStart = false,
        )
    }

    private fun buildWarmupOverrideParams(
        baseParams: WorkoutParameters,
        workingWeightKg: Float,
        warmupSet: com.devil.phoenixproject.domain.model.WarmupSet,
        reps: Int,
    ): WorkoutParameters {
        val warmupWeight = (workingWeightKg * warmupSet.percentOfWorking / 100f).coerceIn(0f, 110f)
        return baseParams.copy(
            weightPerCableKg = warmupWeight,
            reps = reps,
            warmupReps = Constants.DEFAULT_WARMUP_REPS,
            isAMRAP = false,
        )
    }

    private fun applyInterruptedWorkoutRecoveryPlan(plan: InterruptedWorkoutRecoveryPlan) {
        when (plan) {
            is InterruptedWorkoutRecoveryPlan.Resume -> {
                Logger.i {
                    "Rebuilding interrupted set: exerciseIndex=${coordinator._currentExerciseIndex.value}, " +
                        "setIndex=${coordinator._currentSetIndex.value}, warmupSetIndex=${plan.warmupSetIndex}, params=${plan.params}"
                }
                interruptedSetRecovery = null
                executionGuard.mutateConfigurationInputs {
                    pendingStartOverride = StartWorkoutOverride(
                        params = plan.params,
                        preserveWarmupReps = plan.preserveWarmupReps,
                        skipVariableWarmupOverride = plan.skipVariableWarmupOverride,
                    )
                    coordinator._currentWarmupSetIndex.value = plan.warmupSetIndex
                    coordinator._workoutParameters.value = plan.params
                }
                resetInterruptedWorkoutTrackingState()
                startWorkout(skipCountdown = true)
            }

            is InterruptedWorkoutRecoveryPlan.EnterSetReady -> {
                Logger.w { "Interrupted workout requires manual restart from Set Ready" }
                interruptedSetRecovery = null
                pendingStartOverride = null
                resetInterruptedWorkoutTrackingState()
                coordinator._workoutState.value = WorkoutState.Idle
                flowDelegate?.enterSetReadyWithAdjustments(
                    exerciseIndex = plan.exerciseIndex,
                    setIndex = plan.setIndex,
                    adjustedWeight = plan.adjustedWeight,
                    adjustedReps = plan.adjustedReps,
                )
                coordinator._userFeedbackEvents.tryEmit(plan.feedback)
            }

            is InterruptedWorkoutRecoveryPlan.ShowRoutineComplete -> {
                Logger.w { "Interrupted workout already completed before reconnect; showing routine complete" }
                // Issue #395: Write aggregate health workout before clearing routine state
                writeRoutineHealthData()
                autoBackupRoutineIfEnabled("interrupted-routine-complete")
                interruptedSetRecovery = null
                pendingStartOverride = null
                resetInterruptedWorkoutTrackingState()
                coordinator._workoutState.value = WorkoutState.Idle
                flowDelegate?.showRoutineComplete()
                coordinator._userFeedbackEvents.tryEmit(plan.feedback)
            }
        }
    }

    private fun resetInterruptedWorkoutTrackingState() {
        coordinator.workoutJob?.cancel()
        coordinator.workoutJob = null
        repCounter.reset()
        resetAutoStopState()
        coordinator._repCount.value = RepCount()
        coordinator._repRanges.value = null
        coordinator._timedExerciseRemainingSeconds.value = null
        coordinator._currentHeuristicKgMax.value = 0f
        coordinator.collectedMetrics.value = emptyList()
        coordinator.setRepMetrics.value = emptyList()
        coordinator.repBoundaryTimestamps.value = emptyList()
        executionGuard.currentLease?.let(::resetBiomechanicsContext)
        coordinator.repQualityScorer.reset()
        coordinator._latestRepQuality.value = null
        coordinator._loadBaselineA.value = 0f
        coordinator._loadBaselineB.value = 0f
        coordinator.warmupCompleteTimeMs = 0
        coordinator.currentSessionId = null
        coordinator.bodyweightTimerJob?.cancel()
        coordinator.bodyweightTimerJob = null
    }

    private fun rejectStart(reason: StartRejectionReason) {
        val message = when (reason) {
            StartRejectionReason.TEARING_DOWN -> "Finishing previous workout…"
            StartRejectionReason.RECOVERY_REQUIRED -> "Trainer reset didn't complete"
            StartRejectionReason.PROFILE_SWITCHING -> "Profile switch in progress"
            StartRejectionReason.NOT_CONNECTED -> "Connect to your trainer first"
        }
        coordinator._userFeedbackEvents.tryEmit(message)
        connectionLogRepository.info(
            LogEventType.WORKOUT_EXECUTION,
            "Workout start rejected",
            details = "reason=${reason.name}",
        )
    }

    private fun failStart(lease: ExecutionLease, priorWorkoutState: WorkoutState): Boolean {
        bodyweightCompletionGate.invalidate(lease)
        clearDangerZoneCountdownOverride(lease)
        if (!executionGuard.invalidate(lease, ExecutionInvalidationReason.START_FAILED)) return false
        discardTeardownReadyContinuation(lease)
        executionGuard.cancelPresentationJobsFor(lease)
        repFreshnessGate.invalidate(lease)
        if (coordinator.currentSessionId == lease.sessionId) {
            coordinator.currentSessionId = null
        }
        coordinator._workoutState.value = if (priorWorkoutState is WorkoutState.Idle) {
            priorWorkoutState
        } else {
            WorkoutState.Idle
        }
        return true
    }

    private enum class StartRejectionReason {
        TEARING_DOWN,
        RECOVERY_REQUIRED,
        PROFILE_SWITCHING,
        NOT_CONNECTED,
    }

    fun startWorkout(skipCountdown: Boolean = false, isJustLiftMode: Boolean = false) {
        when (coordinator._restTransitionPlan.value) {
            is RestTransitionPlan.UnresolvedDropOffer,
            is RestTransitionPlan.AcceptedRetry,
            -> return

            else -> Unit
        }
        if (acceptedRetryStartClaim.value != null) return
        val exercise = coordinator._loadedRoutine.value?.exercises?.getOrNull(coordinator._currentExerciseIndex.value)
        if (!isBodyweightExercise(exercise) && deferStartUntilOwnedResetCompletes(skipCountdown, isJustLiftMode)) {
            return
        }
        executionGuard.supersedeQueuedSuccessors()
        supersedePendingResetStart()
        startWorkoutInternal(
            skipCountdown = skipCountdown,
            isJustLiftMode = isJustLiftMode,
            retryRequest = null,
            queuedSuccessorToken = null,
            queuedStartCandidate = null,
        )
    }

    private fun deferStartUntilOwnedResetCompletes(
        skipCountdown: Boolean,
        isJustLiftMode: Boolean,
    ): Boolean {
        val resetOwner = resetMachineTeardownOwner.value ?: return false
        val teardownLease = executionGuard.captureMachineTeardownLease()
            ?.takeIf { it.sameExecutionAs(resetOwner.lease) }
            ?: return false
        val preparedSuccessor = executionGuard.prepareNoCurrentSuccessor {
            syncRoutineSessionContext()
            captureQueuedStartCandidate()
        }
        if (preparedSuccessor == null) {
            rejectStart(StartRejectionReason.PROFILE_SWITCHING)
            return true
        }
        val (successorToken, candidate) = preparedSuccessor
        val pending = PendingResetStart(
            owner = resetOwner,
            successorToken = successorToken,
            candidate = candidate,
            skipCountdown = skipCountdown,
            isJustLiftMode = isJustLiftMode,
        )
        while (true) {
            val existing = pendingResetStart.value
            if (existing != null && existing.owner != resetOwner) {
                rejectStart(StartRejectionReason.TEARING_DOWN)
                return true
            }
            if (pendingResetStart.compareAndSet(existing, pending)) break
        }
        if ((
                resetMachineTeardownOwner.value != resetOwner ||
                    executionGuard.captureMachineTeardownLease()?.sameExecutionAs(teardownLease) != true
                ) &&
            pendingResetStart.compareAndSet(pending, null)
        ) {
            startPendingResetSuccessor(pending)
        }
        return true
    }

    private fun startWorkoutInternal(
        skipCountdown: Boolean,
        isJustLiftMode: Boolean,
        retryRequest: RetryStartRequest?,
        queuedSuccessorToken: NoCurrentSuccessorToken? = null,
        queuedStartCandidate: QueuedStartCandidate? = null,
    ): ExecutionLease? {
        if (retryRequest != null && !ownsAcceptedRetryStartClaim(retryRequest)) return null
        if ((queuedSuccessorToken == null) != (queuedStartCandidate == null)) return null
        val ordinaryConfigurationInputEpoch = if (retryRequest == null && queuedSuccessorToken == null) {
            executionGuard.captureConfigurationInputEpoch()
        } else {
            null
        }
        val ordinaryExternalCommandInputStamp = if (retryRequest == null && queuedSuccessorToken == null) {
            captureExternalCommandInputStamp()
        } else {
            null
        }
        val expectedExternalCommandInputStamp = retryRequest?.externalCommandInputStamp
            ?: queuedStartCandidate?.externalCommandInputStamp
            ?: ordinaryExternalCommandInputStamp
        val readyProfile = userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready
        if (readyProfile == null) {
            Logger.w { "Workout start ignored while profile context is switching" }
            rejectStart(StartRejectionReason.PROFILE_SWITCHING)
            return null
        }
        if (expectedExternalCommandInputStamp == null ||
            readyProfile.profile.id != expectedExternalCommandInputStamp.profileId ||
            captureExternalCommandInputStamp() != expectedExternalCommandInputStamp
        ) {
            return null
        }
        if (retryRequest != null && readyProfile.profile.id != retryRequest.profileId) {
            return null
        }
        val coordinatorExercise = coordinator._loadedRoutine.value?.exercises?.getOrNull(coordinator._currentExerciseIndex.value)
        if (queuedStartCandidate != null && captureQueuedStartCandidate() != queuedStartCandidate) {
            return null
        }
        val currentExercise = queuedStartCandidate?.routineExercise ?: coordinatorExercise
        if (retryRequest != null &&
            (
                coordinator._currentExerciseIndex.value != retryRequest.exerciseIndex ||
                    coordinator._currentSetIndex.value != retryRequest.setIndex ||
                    currentExercise == null ||
                    currentExercise.id != retryRequest.routineExerciseId ||
                    !retryCommandMatchesCurrentExercise(retryRequest, currentExercise)
                )
        ) {
            return null
        }
        val isBodyweightAtStart = isBodyweightExercise(currentExercise)
        val requiresMachine = !isBodyweightAtStart
        if (requiresMachine) {
            when (executionGuard.machineTeardownState.value) {
                is MachineTeardownState.TearingDown -> {
                    rejectStart(StartRejectionReason.TEARING_DOWN)
                    return null
                }

                is MachineTeardownState.RecoveryRequired -> {
                    rejectStart(StartRejectionReason.RECOVERY_REQUIRED)
                    return null
                }

                MachineTeardownState.Ready -> Unit
            }
        }
        if (requiresMachine && bleRepository.connectionState.value !is ConnectionState.Connected) {
            rejectStart(StartRejectionReason.NOT_CONNECTED)
            return null
        }

        val capturedStartOverride = if (retryRequest == null) {
            pendingStartOverride
        } else {
            null
        }
        val seedParams = retryRequest?.params
            ?: capturedStartOverride?.params
            ?: queuedStartCandidate?.workoutParameters
            ?: coordinator._workoutParameters.value
        val durationSeconds = currentExercise?.duration?.takeIf { it > 0 }
        val isTimedCableAtStart = requiresMachine && durationSeconds != null
        val variableWarmupTarget = (queuedStartCandidate?.warmupSetIndex ?: coordinator._currentWarmupSetIndex.value)
            .takeIf { it >= 0 }
            ?.let { currentExercise?.warmupSets?.getOrNull(it)?.reps }
        val leaseTarget = if (isBodyweightAtStart || isTimedCableAtStart) {
            0
        } else {
            variableWarmupTarget ?: seedParams.reps
        }
        val usesUnlimitedRepTarget = requiresMachine &&
            !isBodyweightAtStart &&
            !isTimedCableAtStart &&
            variableWarmupTarget == null &&
            (isJustLiftMode || seedParams.isJustLift || seedParams.isAMRAP)
        val outgoingLease = executionGuard.currentLease
        val executionSeed = ExecutionSeed(
            sessionId = KmpUtils.randomUUID(),
            profileId = readyProfile.profile.id,
            requiresMachine = requiresMachine,
            workingRepTarget = leaseTarget,
            isBodyweight = isBodyweightAtStart,
            isJustLift = isJustLiftMode || seedParams.isJustLift,
            isAmrap = seedParams.isAMRAP,
            isTimedCable = isTimedCableAtStart,
            usesUnlimitedRepTarget = usesUnlimitedRepTarget,
        )
        beforeExecutionBeginForTest?.invoke()
        val leaseResult = when {
            retryRequest?.restoredOwnerToken != null -> executionGuard.beginRestoredSuccessorExecution(
                owner = retryRequest.restoredOwnerToken,
                seed = executionSeed,
                candidateStillCurrent = { restoredRetryCandidateStillCurrent(retryRequest) },
            )

            retryRequest != null -> executionGuard.beginSuccessorExecution(retryRequest.expectedSource, executionSeed)

            queuedSuccessorToken != null -> executionGuard.beginNoCurrentSuccessorExecution(
                token = queuedSuccessorToken,
                seed = executionSeed,
                candidateStillCurrent = { captureQueuedStartCandidate() == queuedStartCandidate },
            )

            else -> executionGuard.beginExecution(executionSeed)
        }
        val lease = leaseResult.getOrElse {
            when (executionGuard.machineTeardownState.value) {
                is MachineTeardownState.RecoveryRequired -> rejectStart(StartRejectionReason.RECOVERY_REQUIRED)
                else -> rejectStart(StartRejectionReason.TEARING_DOWN)
            }
            return null
        }
        retryRequest?.restoredOwnerToken?.let { owner ->
            clearRestoredRuntimeOwnerIfOwned(owner)
            clearRestoredTeardownRetryOwnerIfOwned(owner)
        }
        if (queuedStartCandidate != null && captureQueuedStartCandidate() != queuedStartCandidate) {
            executionGuard.invalidate(lease, ExecutionInvalidationReason.START_FAILED)
            return null
        }
        if (retryRequest == null && pendingStartOverride === capturedStartOverride) {
            pendingStartOverride = null
        }
        outgoingLease?.let(::discardTeardownReadyContinuation)
        afterExecutionBegin(outgoingLease?.executionId, lease.executionId)
        if (queuedStartCandidate != null && !queuedStartIdentityStillCurrent(queuedStartCandidate)) {
            executionGuard.invalidate(lease, ExecutionInvalidationReason.START_FAILED)
            return null
        }
        bodyweightCompletionGate.beginExecution(lease)
        if (!installBiomechanicsContext(lease)) return null
        retryRetainedWorkoutExitPersistence()
        executionContext = null
        val priorWorkoutState = coordinator._workoutState.value

        Logger.d { "startWorkout called: skipCountdown=$skipCountdown, isJustLiftMode=$isJustLiftMode" }
        Logger.d { "startWorkout: loadedRoutine=${coordinator._loadedRoutine.value?.name}, params=${coordinator._workoutParameters.value}" }

        cancelJustLiftEggTimer()
        coordinator.stopWorkoutInProgress.value = false
        coordinator._weightAdjustmentRecommendation.value = null
        resetAutoStopState()
        coordinator.skipCountdownRequested = skipCountdown
        coordinator.currentSessionId = lease.sessionId

        // Reset rep quality scorer for fresh set
        coordinator.repQualityScorer.reset()
        coordinator._latestRepQuality.value = null
        // F3 (stall-detection audit): a set start must never inherit the previous
        // set's biomech/VBT state. The Phase 35C variable warm-up fast path in
        // handleSetCompletion returns early — before its biomech/VBT reset block —
        // so a warm-up set's firstRepMcv (at a fraction of working weight) and the
        // one-shot VBT alert flags leaked into the first working set. Resetting here
        // covers every set-start path; the handleSetCompletion resets remain (they
        // must run before the summary is displayed) and are idempotent with these.
        coordinator.repBoundaryTimestamps.value = emptyList()
        // Reset quality streak only at actual workout start, not between sets.
        // skipCountdown=true indicates a set-to-set transition within the same workout.
        if (!skipCountdown) {
            gamificationManager.resetQualityStreak()
        }

        coordinator.workoutJob?.cancel()

        coordinator._workoutState.value = WorkoutState.Initializing
        if (retryRequest == null && queuedStartCandidate == null) syncRoutineSessionContext()
        val routineSessionIdAtStart = retryRequest?.routineSessionId
            ?: queuedStartCandidate?.routineSessionId
            ?: coordinator.currentRoutineSessionId
        val routineIdAtStart = retryRequest?.routineId
            ?: queuedStartCandidate?.routineId
            ?: coordinator.currentRoutineId
        val routineNameAtStart = queuedStartCandidate?.routineName ?: coordinator.currentRoutineName
        val exerciseIndexAtStart = queuedStartCandidate?.exerciseIndex ?: coordinator._currentExerciseIndex.value
        val setIndexAtStart = queuedStartCandidate?.setIndex ?: coordinator._currentSetIndex.value
        val warmupSetIndexAtStart = queuedStartCandidate?.warmupSetIndex ?: coordinator._currentWarmupSetIndex.value
        val cycleIdAtStart = queuedStartCandidate?.cycleId ?: coordinator.activeCycleId
        val cycleDayNumberAtStart = queuedStartCandidate?.cycleDayNumber ?: coordinator.activeCycleDayNumber

        coordinator.workoutJob = scope.launch {
            var configMayHaveReachedMachine = false
            var recoveryTeardownScheduled = false
            var configurationTeardownHandedOff = false
            var retryStartCommitted = false
            try {
                val startOverride = capturedStartOverride
                if (startOverride != null) {
                    coordinator._workoutParameters.value = startOverride.params
                }
                if (queuedStartCandidate != null && captureQueuedStartCandidate() != queuedStartCandidate) {
                    failStart(lease, priorWorkoutState)
                    return@launch
                }
                val baseParams = retryRequest?.params
                    ?: startOverride?.params
                    ?: queuedStartCandidate?.workoutParameters
                    ?: coordinator._workoutParameters.value

                if (retryRequest != null && !hasRetryPersistenceAuthority(retryRequest)) {
                    failRetryStartAndRecover(retryRequest, lease, priorWorkoutState)
                    return@launch
                }

                val isBodyweight = isBodyweightExercise(currentExercise)
                val exerciseDuration = currentExercise?.duration?.takeIf { it > 0 }
                val bodyweightDuration = if (isBodyweight) exerciseDuration else null

                val plannedSetsAtStart = currentExercise?.let { exercise ->
                    completedSetRepository.getPlannedSets(exercise.id)
                }.orEmpty()
                val plannedSetAtStart = plannedSetsAtStart.singleOrNull { it.setNumber == setIndexAtStart }
                if (retryRequest != null &&
                    !restTransitionMutex.withLock {
                        retryStartStillAuthorizedLocked(
                            request = retryRequest,
                            lease = lease,
                            exercise = currentExercise,
                            plannedSets = plannedSetsAtStart,
                        )
                    }
                ) {
                    failRetryStartAndRecover(retryRequest, lease, priorWorkoutState)
                    return@launch
                }
                val params = captureRackLoadSnapshot(
                    params = baseParams,
                    currentExercise = currentExercise,
                    activeRackItemIds = queuedStartCandidate?.activeRackItemIds
                        ?: coordinator._activeRackItemIds.value,
                    behaviorOverrides = queuedStartCandidate?.activeRackBehaviorOverrides
                        ?: coordinator._activeRackBehaviorOverrides.value,
                )
                val selectedExerciseAtStart = currentExercise?.exercise ?: resolveSelectedExercise(params)
                val semanticSetType = when {
                    retryRequest != null -> retryRequest.logicalSetKey.setKind
                    currentExercise == null -> if (seedParams.isAMRAP) SetType.AMRAP else SetType.STANDARD
                    currentExercise.setReps.getOrNull(setIndexAtStart) == null -> SetType.AMRAP
                    currentExercise.isAMRAP && setIndexAtStart == currentExercise.setReps.lastIndex -> SetType.AMRAP
                    else -> SetType.STANDARD
                }
                val plannedSetTypeAtStart = plannedSetAtStart?.setType ?: semanticSetType
                val logicalSetKeyAtStart = retryRequest?.logicalSetKey ?: if (routineSessionIdAtStart != null && currentExercise != null) {
                    LogicalSetKey(
                        routineSessionId = routineSessionIdAtStart,
                        routineExerciseId = currentExercise.id,
                        setIndex = setIndexAtStart,
                        setKind = plannedSetTypeAtStart,
                    )
                } else {
                    null
                }
                // startWorkout is intentionally non-suspending. The durable seed is resolved
                // inside its existing start job, before activation or any machine command,
                // from the immutable key captured above rather than mutable coordinator state.
                val attemptNumberAtStart = retryRequest?.attemptNumber ?: logicalSetKeyAtStart
                    ?.let { completedSetRepository.nextAttemptNumber(it) }
                    ?: 1
                val retainedManualAttemptState = if (retryRequest == null && logicalSetKeyAtStart != null) {
                    activeRuntimeDocument
                        ?.takeIf { document ->
                            document.restTransitionPlan == null &&
                                document.matchesRoutineIdentity(
                                    lease.profileId,
                                    routineIdAtStart,
                                    routineSessionIdAtStart,
                                )
                        }
                        ?.attemptStates
                        ?.filter { it.logicalSetKey == logicalSetKeyAtStart }
                        ?.let { matchingStates ->
                            val state = matchingStates.singleOrNull()
                            val expectedReservation = state?.nextAttemptNumber == attemptNumberAtStart
                            val consumedRetryReservation = state?.nextAttemptNumber == attemptNumberAtStart + 1 &&
                                activeRuntimeDocument?.sourceAttemptNumber == attemptNumberAtStart - 1 &&
                                activeRuntimeDocument?.logicalSetKey == logicalSetKeyAtStart &&
                                state.acceptedDropCount > 0
                            if (matchingStates.size > 1 ||
                                (
                                    state != null &&
                                        (
                                            (!expectedReservation && !consumedRetryReservation) ||
                                                state.acceptedDropCount !in 0..2
                                            )
                                    )
                            ) {
                                failStart(lease, priorWorkoutState)
                                return@launch
                            }
                            state
                        }
                } else {
                    null
                }
                val routineIdentityAtStart = if (
                    routineIdAtStart != null && routineSessionIdAtStart != null &&
                    currentExercise != null && logicalSetKeyAtStart != null
                ) {
                    RoutineExecutionIdentity(
                        profileId = lease.profileId,
                        routineId = routineIdAtStart,
                        routineSessionId = routineSessionIdAtStart,
                        routineExerciseId = currentExercise.id,
                        logicalSetKey = logicalSetKeyAtStart,
                        plannedSetId = retryRequest?.plannedSetId ?: plannedSetAtStart?.id,
                        exerciseIndex = exerciseIndexAtStart,
                        setIndex = setIndexAtStart,
                    )
                } else {
                    null
                }
                val programmedBaseWeight = retryRequest?.programmedBaseWeightPerCableKg ?: currentExercise?.let { exercise ->
                    RoutineSetWeightResolver(
                        RoutineSetWeightRequest(
                            exercise = exercise,
                            setIndex = setIndexAtStart,
                            currentPrKg = null,
                            occurrenceMultiplier = 1f,
                            manualAdjustmentPerCableKg = null,
                        ),
                    )
                } ?: baseParams.weightPerCableKg
                val semanticAmrap = plannedSetTypeAtStart == SetType.AMRAP
                val startedContext = WorkoutExecutionContext(
                    lease = lease,
                    exerciseName = selectedExerciseAtStart?.name,
                    preferredCableCount = selectedExerciseAtStart?.preferredCableCount,
                    displayMultiplier = selectedExerciseAtStart?.displayMultiplier,
                    sessionBodyWeightKg = resolvedSessionBodyWeightKg(),
                    routineSessionId = routineSessionIdAtStart,
                    routineId = routineIdAtStart,
                    routineName = routineNameAtStart,
                    cycleId = cycleIdAtStart,
                    cycleDayNumber = cycleDayNumberAtStart,
                    completionFacts = SetExecutionActivationFacts(
                        routineIdentity = routineIdentityAtStart,
                        attemptNumber = attemptNumberAtStart,
                        acceptedDropCount = retryRequest?.acceptedDropCount
                            ?: retainedManualAttemptState?.acceptedDropCount
                            ?: 0,
                        plannedSetType = plannedSetTypeAtStart,
                        programMode = baseParams.programMode,
                        programmedBaseWeightPerCableKg = programmedBaseWeight,
                        configuredStartWeightPerCableKg = retryRequest?.expectedWeightPerCableKg ?: baseParams.weightPerCableKg,
                        progressionKg = baseParams.progressionRegressionKg,
                        targetReps = if (semanticAmrap || isTimedCableAtStart || isBodyweightAtStart) null else leaseTarget,
                        isWarmup = warmupSetIndexAtStart >= 0,
                        isEcho = baseParams.isEchoMode,
                        isJustLift = isJustLiftMode || baseParams.isJustLift,
                        isBodyweight = isBodyweightAtStart,
                        isTimed = durationSeconds != null,
                        isAmrap = semanticAmrap,
                        isCableExercise = requiresMachine,
                        physicalCableCount = selectedExerciseAtStart?.preferredCableCount,
                        logicalPreRackCommandTemplate = params.copy(
                            // The command must retain the pre-rack programmed weight,
                            // while the remaining metadata is the resolved set-start
                            // rack snapshot (IDs, external load, counterweight).
                            weightPerCableKg = baseParams.weightPerCableKg,
                            activeRackItemIds = params.activeRackItemIds.toList(),
                        ),
                    ),
                )
                if (!executionGuard.isCurrent(lease)) return@launch
                executionContext = startedContext

                val isTimedCableExercise = !isBodyweight && exerciseDuration != null
                coordinator.isCurrentWorkoutTimed = exerciseDuration != null
                coordinator.isCurrentTimedCableExercise = isTimedCableExercise
                coordinator._isCurrentExerciseBodyweight.value = isBodyweight

                Logger.d { "Issue227: startWorkout exercise type detection:" }
                Logger.d { "  - Exercise: ${currentExercise?.exercise?.name}" }
                Logger.d { "  - Equipment: '${currentExercise?.exercise?.equipment}'" }
                Logger.d { "  - Weight: ${currentExercise?.weightPerCableKg}kg" }
                Logger.d { "  - Duration: ${exerciseDuration}s" }
                Logger.d { "  - isBodyweight: $isBodyweight" }
                Logger.d { "  - isTimedCableExercise: $isTimedCableExercise" }

                // Issue #222: For ALL bodyweight exercises, skip machine commands
                if (isBodyweight) {
                    val effectiveDuration = bodyweightDuration ?: 30
                    Logger.d("Starting bodyweight exercise: ${currentExercise?.exercise?.name} for ${effectiveDuration}s (bodyweightDuration=$bodyweightDuration)")

                    Logger.d("ActiveSessionEngine") { "Issue #222 v6: Bodyweight start - keeping existing polling state (matching parent repo)" }

                    repCounter.reset()
                    repCounter.configure(
                        warmupTarget = 0,
                        workingTarget = 0,
                        isJustLift = false,
                        stopAtTop = params.stopAtTop,
                        isAMRAP = false,
                    )
                    coordinator._repCount.value = RepCount()
                    coordinator.warmupCompleteTimeMs = 0

                    if (!coordinator.skipCountdownRequested) {
                        startMotionStartDetection(lease)
                        for (i in 5 downTo 1) {
                            if (coordinator.skipCountdownRequested) break
                            if (!executionGuard.isCurrent(lease)) return@launch
                            coordinator._workoutState.value = WorkoutState.Countdown(i)
                            delay(1000)
                            if (!hasCurrentAuthority(lease, "bodyweight_countdown_after_delay")) return@launch
                        }
                        if (!hasCurrentAuthority(lease, "bodyweight_countdown_motion_cleanup")) return@launch
                        stopMotionStartDetection()
                    }

                    val activeLease = executionGuard.activate(
                        lease = lease,
                        cutoverTimestampMs = wallClockMillisProvider(),
                        expectedConfigurationInputEpoch = retryRequest?.configurationInputEpoch
                            ?: queuedSuccessorToken?.configurationInputEpoch
                            ?: ordinaryConfigurationInputEpoch,
                        inputAuthorityStillCurrent = {
                            captureExternalCommandInputStamp() == expectedExternalCommandInputStamp &&
                                (
                                    queuedStartCandidate == null ||
                                        captureQueuedStartCandidate() == queuedStartCandidate
                                    )
                        },
                    )
                    if (activeLease == null) {
                        if (retryRequest != null) {
                            failRetryStartAndRecover(retryRequest, lease, priorWorkoutState)
                        } else {
                            failStart(lease, priorWorkoutState)
                        }
                        return@launch
                    }
                    repFreshnessGate.resetFor(activeLease)
                    if (!executionGuard.isCurrent(activeLease)) return@launch
                    coordinator._workoutState.value = WorkoutState.Active
                    coordinator.workoutStartTime = currentTimeMillis()
                    if (coordinator._loadedRoutine.value != null && coordinator.routineStartTime == 0L) {
                        coordinator.routineStartTime = coordinator.workoutStartTime
                    }
                    coordinator.collectedMetrics.value = emptyList()
                    coordinator._hapticEvents.emit(HapticEvent.WORKOUT_START)
                    if (!hasCurrentAuthority(activeLease, "bodyweight_start_after_haptic")) return@launch

                    startBodyweightTimer(activeLease, effectiveDuration)

                    return@launch
                }

                // Normal cable-based exercise
                if (coordinator.previousExerciseWasBodyweight) {
                    coordinator.previousExerciseWasBodyweight = false
                }

                val effectiveWarmupReps = Constants.DEFAULT_WARMUP_REPS
                val preserveWarmupReps = startOverride?.preserveWarmupReps == true
                val effectiveParams = if (!preserveWarmupReps && params.warmupReps != effectiveWarmupReps) {
                    Logger.d("ActiveSessionEngine") { "Issue #222: Forcing warmupReps=$effectiveWarmupReps for cable exercise (was ${params.warmupReps})" }
                    val updated = params.copy(warmupReps = effectiveWarmupReps)
                    coordinator._workoutParameters.value = updated
                    updated
                } else {
                    params
                }

                // Clear any deferred mid-set weight change flag (weight already applied to _workoutParameters)
                if (coordinator.pendingWeightChangeKg != null) {
                    Logger.d("ActiveSessionEngine: Deferred weight change of ${coordinator.pendingWeightChangeKg} kg applied at set start")
                    coordinator.pendingWeightChangeKg = null
                }

                Logger.d("ActiveSessionEngine") { "Cable workout starting - bodyweightSetsInRoutine=${coordinator.bodyweightSetsCompletedInRoutine}, exerciseIdx=${coordinator._currentExerciseIndex.value}, setIdx=${coordinator._currentSetIndex.value}, echo=${effectiveParams.isEchoMode}, mode=${effectiveParams.programMode}" }
                Logger.d("ActiveSessionEngine") { "BLE params: mode=${effectiveParams.programMode.displayName}, weight=${effectiveParams.weightPerCableKg}kg, reps=${effectiveParams.reps} (AMRAP=${effectiveParams.isAMRAP}), warmup=${effectiveParams.warmupReps}, progression=${effectiveParams.progressionRegressionKg}kg/rep" }
                Logger.d("ActiveSessionEngine") { "BLE params (cont): justLift=${effectiveParams.isJustLift}, echo=${effectiveParams.isEchoMode}, echoLevel=${effectiveParams.echoLevel.displayName}, eccentricLoad=${effectiveParams.eccentricLoad.percentage}%, stopAtTop=${effectiveParams.stopAtTop}, stallDetection=${effectiveParams.stallDetectionEnabled}" }
                Logger.d("ActiveSessionEngine") { "Issue203: setReps=${currentExercise?.setReps}, currentSetIndex=${coordinator._currentSetIndex.value}, isAMRAP=${effectiveParams.isAMRAP}" }

                // ===== Variable Warm-up Sets (Phase 35C: Issue #30) =====
                // When the exercise has warmupSets defined, override weight/reps for warm-up phase.
                // Each warm-up set is a separate BLE stop/start cycle at a percentage of working weight.
                val warmupSetIndex = coordinator._currentWarmupSetIndex.value
                val isInWarmupPhase = warmupSetIndex >= 0
                val skipVariableWarmupOverride = startOverride?.skipVariableWarmupOverride == true
                val hasVariableWarmupOverrideApplied: Boolean
                val warmupOverrideParams = if (!skipVariableWarmupOverride && isInWarmupPhase && currentExercise != null) {
                    val warmupSet = currentExercise.warmupSets.getOrNull(warmupSetIndex)
                    if (warmupSet != null) {
                        val warmupWeight = (effectiveParams.weightPerCableKg * warmupSet.percentOfWorking / 100f)
                            .coerceIn(0f, 110f)
                        Logger.d {
                            "WarmupSet ${warmupSetIndex + 1}/${currentExercise.warmupSets.size}: " +
                                "${warmupSet.reps} reps @ ${warmupSet.percentOfWorking}% = ${warmupWeight}kg (working=${effectiveParams.weightPerCableKg}kg)"
                        }
                        hasVariableWarmupOverrideApplied = true
                        effectiveParams.copy(
                            weightPerCableKg = warmupWeight,
                            reps = warmupSet.reps,
                            warmupReps = Constants.DEFAULT_WARMUP_REPS, // Preserving 3-rep firmware warmup buffer for calibration
                            isAMRAP = false, // Warm-up sets are always fixed reps
                        )
                    } else {
                        Logger.w { "WarmupSet index $warmupSetIndex out of bounds for ${currentExercise.warmupSets.size} warmup sets - falling through to working set" }
                        coordinator._currentWarmupSetIndex.value = -1
                        hasVariableWarmupOverrideApplied = false
                        effectiveParams
                    }
                } else {
                    hasVariableWarmupOverrideApplied = false
                    effectiveParams
                }
                // Update coordinator params if warm-up override applied
                if (hasVariableWarmupOverrideApplied) {
                    coordinator._workoutParameters.value = warmupOverrideParams
                }

                val rackPhysicalCableCount = currentExercise?.exercise?.preferredCableCount
                    ?: resolveSelectedExercise(effectiveParams)?.preferredCableCount
                    ?: 1
                val rackSnapshotItems = coordinator._currentRackLoadAdjustment.value.selectedItems
                val bleParams = run {
                    val base = if (isTimedCableExercise) {
                        Logger.d { "Duration cable: overriding isAMRAP=true for BLE command (prevents machine rep limit)" }
                        warmupOverrideParams.copy(isAMRAP = true)
                    } else {
                        warmupOverrideParams
                    }
                    // Issue #481: Variable warm-up sets must never carry the working-set's
                    // per-rep weight progression to the machine — the firmware applies the
                    // increment to every rep, including warm-up reps. The warm-up override
                    // (inline above and buildWarmupOverrideParams) intentionally inherits
                    // progressionRegressionKg so that _workoutParameters retains the working
                    // value for the warm-up→working transition (handleSetCompletion restores
                    // weight/reps but NOT progression). So zero it here for the BLE packet
                    // ONLY, leaving persisted state intact. Covers both the inline override
                    // and the interrupted-workout recovery replay (skipVariableWarmupOverride).
                    val sendingWarmupSet = hasVariableWarmupOverrideApplied ||
                        (skipVariableWarmupOverride && isInWarmupPhase)
                    if (sendingWarmupSet && base.progressionRegressionKg != 0f) {
                        Logger.d { "Issue #481: zeroing per-rep progression for warm-up BLE packet (was ${base.progressionRegressionKg}kg)" }
                        base.copy(progressionRegressionKg = 0f)
                    } else {
                        base
                    }
                }.let { paramsForBle ->
                    applyRackToBleParams(
                        params = paramsForBle,
                        physicalCableCount = rackPhysicalCableCount,
                        selectedItems = rackSnapshotItems,
                        behaviorOverrides = coordinator._activeRackBehaviorOverrides.value,
                    )
                }

                if (retryRequest != null) {
                    currentCoroutineContext().ensureActive()
                    val freshPlannedSets = currentExercise?.let { exercise ->
                        completedSetRepository.getPlannedSets(exercise.id)
                    }.orEmpty()
                    currentCoroutineContext().ensureActive()
                    val retryStillAuthorized = restTransitionMutex.withLock {
                        retryStartStillAuthorizedLocked(
                            request = retryRequest,
                            lease = lease,
                            exercise = currentExercise,
                            plannedSets = freshPlannedSets,
                        )
                    }
                    if (!retryStillAuthorized ||
                        !retryRackCommandMatches(
                            request = retryRequest,
                            capturedRackParams = params,
                            bleParams = bleParams,
                            physicalCableCount = rackPhysicalCableCount,
                        )
                    ) {
                        failRetryStartAndRecover(retryRequest, lease, priorWorkoutState)
                        return@launch
                    }
                }

                // Issue #390: Diagnostic logging for weight tracing from routine → BLE
                val routineExercise = currentExercise
                if (routineExercise != null) {
                    Logger.w("Issue390") {
                        "WEIGHT TRACE: exercise='${routineExercise.exercise.name}', " +
                            "equipment='${routineExercise.exercise.equipment}', " +
                            "cableIntent=${routineExercise.exercise.cableIntent}, " +
                            "displayMultiplier=${routineExercise.exercise.displayMultiplier}, " +
                            "routineExercise.weightPerCableKg=${routineExercise.weightPerCableKg}kg, " +
                            "routineExercise.progressionKg=${routineExercise.progressionKg}kg, " +
                            "routineExercise.setWeightsPerCableKg=${routineExercise.setWeightsPerCableKg}, " +
                            "routineExercise.usePercentOfPR=${routineExercise.usePercentOfPR}, " +
                            "routineExercise.weightPercentOfPR=${routineExercise.weightPercentOfPR}%, " +
                            "setIndex=${coordinator._currentSetIndex.value}, " +
                            "hadStartOverride=${startOverride != null}"
                    }
                }
                Logger.w("Issue390") {
                    "BLE PARAMS FINAL: weightPerCableKg=${bleParams.weightPerCableKg}kg, " +
                        "progressionRegressionKg=${bleParams.progressionRegressionKg}kg, " +
                        "reps=${bleParams.reps}, isAMRAP=${bleParams.isAMRAP}, " +
                        "isJustLift=${bleParams.isJustLift}, mode=${bleParams.programMode}"
                }

                // Issue #390: Defensive guard — if weight is suspiciously low for a non-JustLift
                // routine exercise, log a critical warning. This catches cases where unresolved
                // PR% weights or stale defaults produce near-zero BLE packet values.
                if (!bleParams.isJustLift && routineExercise != null &&
                    bleParams.weightPerCableKg < 2f && routineExercise.weightPerCableKg > 5f
                ) {
                    Logger.e("Issue390") {
                        "CRITICAL: BLE weight (${bleParams.weightPerCableKg}kg) is far below " +
                            "routine exercise weight (${routineExercise.weightPerCableKg}kg). " +
                            "The machine will receive near-zero weight! " +
                            "This is likely a weight resolution or parameter propagation bug."
                    }
                }

                val commandValidation = if (bleParams.isEchoMode) {
                    WorkoutCommandValidator.validateEchoControl(
                        level = bleParams.echoLevel,
                        warmupReps = bleParams.warmupReps,
                        targetReps = bleParams.reps,
                        isJustLift = isJustLiftMode || bleParams.isJustLift,
                        isAMRAP = bleParams.isAMRAP,
                        eccentricPct = bleParams.eccentricLoad.percentage,
                    )
                } else {
                    WorkoutCommandValidator.validateProgramParams(bleParams)
                }
                commandValidation.onFailure { error ->
                    Logger.e(error) { "Invalid BLE workout command parameters: ${error.message}" }
                    coordinator._bleErrorEvents.tryEmit("Invalid BLE workout command: ${error.message}")
                    if (retryRequest != null) {
                        failRetryStartAndRecover(retryRequest, lease, priorWorkoutState)
                    } else {
                        failStart(lease, priorWorkoutState)
                    }
                    return@launch
                }

                val command = if (bleParams.isEchoMode) {
                    BlePacketFactory.createEchoControl(
                        level = bleParams.echoLevel,
                        warmupReps = bleParams.warmupReps,
                        targetReps = bleParams.reps,
                        isJustLift = isJustLiftMode || bleParams.isJustLift,
                        isAMRAP = bleParams.isAMRAP,
                        eccentricPct = bleParams.eccentricLoad.percentage,
                    )
                } else {
                    BlePacketFactory.createProgramParams(bleParams)
                }
                Logger.d { "Built ${command.size}-byte workout command for ${bleParams.programMode}" }

                coordinator._repCount.value = RepCount()
                coordinator.warmupCompleteTimeMs = 0
                coordinator._currentHeuristicKgMax.value = 0f
                if (isJustLiftMode) {
                    repCounter.resetCountsOnly()
                } else {
                    repCounter.reset()
                }
                // Issue #411: Keep the 3-rep firmware warmup buffer (warmupReps = 3) for variable
                // warm-up sets. Because the machine firmware always consumes the first 3 reps
                // for ROM/cable calibration before incrementing repsSetCount, setting warmupReps = 0
                // in the BLE command reduced the machine's total capacity, causing the machine to
                // dump the load 3 reps early. By setting warmupReps = 3, the machine allows the full
                // count of working reps, and the rep counter correctly counts the first 3 calibration
                // reps as warmup and the remaining as working.
                val repCounterWarmupTarget = if (hasVariableWarmupOverrideApplied) {
                    warmupOverrideParams.warmupReps
                } else {
                    effectiveParams.warmupReps
                }
                repCounter.configure(
                    warmupTarget = repCounterWarmupTarget,
                    workingTarget = if (isTimedCableExercise) 0 else warmupOverrideParams.reps,
                    isJustLift = isJustLiftMode,
                    stopAtTop = warmupOverrideParams.stopAtTop,
                    isAMRAP = if (isTimedCableExercise) true else warmupOverrideParams.isAMRAP,
                )

                if (isTimedCableExercise) {
                    Logger.d { "Starting TIMED cable exercise: ${currentExercise.exercise.name} for ${exerciseDuration}s" }
                }

                if (!coordinator.skipCountdownRequested && !isJustLiftMode) {
                    startMotionStartDetection(lease)
                    for (i in 5 downTo 1) {
                        if (coordinator.skipCountdownRequested) break
                        if (!executionGuard.isCurrent(lease)) return@launch
                        coordinator._workoutState.value = WorkoutState.Countdown(i)
                        delay(1000)
                        if (!hasCurrentAuthority(lease, "cable_countdown_after_delay")) return@launch
                    }
                    if (!hasCurrentAuthority(lease, "cable_countdown_motion_cleanup")) return@launch
                    stopMotionStartDetection()
                }

                if (retryRequest != null) {
                    beforeAcceptedRetryConfigAuthorityForTest?.invoke()
                    currentCoroutineContext().ensureActive()
                    val finalPlannedSets = currentExercise?.let { exercise ->
                        completedSetRepository.getPlannedSets(exercise.id)
                    }.orEmpty()
                    currentCoroutineContext().ensureActive()
                    if (!hasRetryPersistenceAuthority(retryRequest)) {
                        failRetryStartAndRecover(retryRequest, lease, priorWorkoutState)
                        return@launch
                    }
                    val finalAuthority = restTransitionMutex.withLock {
                        retryStartStillAuthorizedLocked(
                            request = retryRequest,
                            lease = lease,
                            exercise = currentExercise,
                            plannedSets = finalPlannedSets,
                        )
                    }
                    if (!finalAuthority ||
                        !retryRackCommandMatches(
                            request = retryRequest,
                            capturedRackParams = params,
                            bleParams = bleParams,
                            physicalCableCount = rackPhysicalCableCount,
                        )
                    ) {
                        failRetryStartAndRecover(retryRequest, lease, priorWorkoutState)
                        return@launch
                    }
                    afterAcceptedRetryFinalAuthorityForTest?.invoke()
                    currentCoroutineContext().ensureActive()
                }
                if (queuedStartCandidate != null && !queuedStartIdentityStillCurrent(queuedStartCandidate)) {
                    failStart(lease, priorWorkoutState)
                    return@launch
                }
                if (queuedStartCandidate != null) {
                    beforeQueuedSuccessorMachineConfigurationForTest?.invoke()
                    currentCoroutineContext().ensureActive()
                }
                beforeMachineConfigurationClaimForTest?.invoke()
                currentCoroutineContext().ensureActive()
                val configurationClaim = executionGuard.claimMachineConfiguration(
                    lease = lease,
                    expectedConfigurationInputEpoch = retryRequest?.configurationInputEpoch
                        ?: ordinaryConfigurationInputEpoch,
                    inputAuthorityStillCurrent = {
                        captureExternalCommandInputStamp() == expectedExternalCommandInputStamp
                    },
                )
                when (configurationClaim) {
                    MachineConfigurationClaimResult.CLAIMED -> Unit

                    MachineConfigurationClaimResult.CONFIGURATION_INPUT_SUPERSEDED -> {
                        if (retryRequest != null) {
                            failRetryStartAndRecover(retryRequest, lease, priorWorkoutState)
                        } else {
                            failStart(lease, priorWorkoutState)
                        }
                        return@launch
                    }

                    MachineConfigurationClaimResult.REJECTED -> return@launch
                }
                var activationAllowed = false
                var configurationCompletion: MachineConfigurationCompletion = MachineConfigurationCompletion.Rejected
                val configFailure: Exception? = try {
                    configMayHaveReachedMachine = true
                    bleRepository.sendWorkoutCommand(command).getOrThrow()
                    if (retryRequest != null) {
                        afterAcceptedRetryConfigSentForTest?.invoke()
                        currentCoroutineContext().ensureActive()
                    }
                    Logger.i { "CONFIG command sent: ${command.size} bytes for ${effectiveParams.programMode}" }
                    val preview = command.take(16).joinToString(" ") { it.toUByte().toString(16).padStart(2, '0').uppercase() }
                    Logger.d { "Config preview: $preview ..." }
                    if (!effectiveParams.isEchoMode && command.size >= 0x60) {
                        val activationTailDump = command
                            .copyOfRange(0x48, 0x60)
                            .joinToString(" ") { it.toUByte().toString(16).padStart(2, '0').uppercase() }
                        Logger.w {
                            "BLE-ACTIVATION-VERIFY (temporary): offsets 0x48..0x5F => $activationTailDump"
                        }
                        val eccentricUpDump = command
                            .copyOfRange(0x48, 0x50)
                            .joinToString(" ") { it.toUByte().toString(16).padStart(2, '0').uppercase() }

                        // Issue #390: Decode the key float values from packet bytes for readability
                        fun readFloatLE(buf: ByteArray, off: Int): Float {
                            val bits = (buf[off].toInt() and 0xFF) or
                                ((buf[off + 1].toInt() and 0xFF) shl 8) or
                                ((buf[off + 2].toInt() and 0xFF) shl 16) or
                                ((buf[off + 3].toInt() and 0xFF) shl 24)
                            return Float.fromBits(bits)
                        }
                        Logger.w("Issue390") {
                            "PACKET DECODED: eccUpRamp@0x48..0x4F=$eccentricUpDump, " +
                                "forceMin@0x50=${readFloatLE(command, 0x50)}kg, " +
                                "forceMax@0x54=${readFloatLE(command, 0x54)}kg, " +
                                "targetWeight@0x58=${readFloatLE(command, 0x58)}kg, " +
                                "progression@0x5C=${readFloatLE(command, 0x5C)}kg"
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    bleRepository.startActiveWorkoutPolling()
                    activationAllowed = true
                    null
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    error
                } finally {
                    configurationCompletion = executionGuard.completeMachineConfiguration(
                        lease = lease,
                        activationCutoverTimestampMs = if (activationAllowed) wallClockMillisProvider() else null,
                        inputAuthorityStillCurrent = {
                            captureExternalCommandInputStamp() == expectedExternalCommandInputStamp
                        },
                    ) { activatedLease ->
                        repFreshnessGate.resetFor(activatedLease)
                        coordinator._workoutState.value = WorkoutState.Active
                        retryStartCommitted = retryRequest != null
                    }
                    if (configurationCompletion is MachineConfigurationCompletion.TeardownBegun) {
                        val deferredTeardown = takeDeferredMachineConfigurationTeardown(lease)
                            ?: DeferredMachineConfigurationTeardown(
                                lease = lease,
                                reason = TeardownReason.RECOVERY,
                                attempt = 1,
                                afterReady = if (configurationCompletion.configurationInputsSuperseded) {
                                    if (retryRequest != null) {
                                        {
                                            failRetryStartAndRecover(
                                                request = retryRequest,
                                                lease = lease,
                                                priorWorkoutState = priorWorkoutState,
                                            )
                                        }
                                    } else {
                                        { failStart(lease, priorWorkoutState) }
                                    }
                                } else {
                                    null
                                },
                            )
                        launchClaimedMachineTeardown(deferredTeardown)
                        configurationTeardownHandedOff = true
                        recoveryTeardownScheduled = true
                        retryRequest?.let(::clearAcceptedRetryStartClaim)
                    } else if (configurationCompletion is MachineConfigurationCompletion.ReleasedWithoutActivation ||
                        configurationCompletion is MachineConfigurationCompletion.Rejected
                    ) {
                        takeDeferredMachineConfigurationTeardown(lease)
                    }
                }
                if (configurationTeardownHandedOff) return@launch
                if (configFailure != null) {
                    Logger.e(configFailure) { "Failed to send config command" }
                    coordinator._bleErrorEvents.tryEmit("Failed to send command: ${configFailure.message}")
                    if (retryRequest != null) {
                        recoveryTeardownScheduled = recoverRetryStartAfterConfigAttempt(
                            retryRequest,
                            lease,
                            priorWorkoutState,
                        )
                    } else {
                        failStart(lease, priorWorkoutState)
                    }
                    return@launch
                }

                val activeLease = (configurationCompletion as? MachineConfigurationCompletion.Activated)?.lease
                    ?: return@launch
                retryRequest?.let(::clearAcceptedRetryStartClaim)
                if (retryRequest != null) {
                    afterAcceptedRetryActivatedForTest?.invoke()
                    currentCoroutineContext().ensureActive()
                }
                coordinator.workoutStartTime = currentTimeMillis()
                if (coordinator._loadedRoutine.value != null && coordinator.routineStartTime == 0L) {
                    coordinator.routineStartTime = coordinator.workoutStartTime
                }
                coordinator.collectedMetrics.value = emptyList()
                coordinator._hapticEvents.emit(HapticEvent.WORKOUT_START)
                if (!hasCurrentAuthority(activeLease, "cable_start_after_haptic")) return@launch

                // exerciseDuration != null is logically redundant (implied by isTimedCableExercise)
                // but required for Kotlin smart-cast so exerciseDuration can be used as non-null below
                @Suppress("SENSELESS_COMPARISON")
                if (isTimedCableExercise && exerciseDuration != null) {
                    startTimedCableTimer(activeLease, requireNotNull(exerciseDuration))
                }

                coordinator._currentMetric.value?.let { metric ->
                    repCounter.setInitialBaseline(metric.positionA, metric.positionB)
                    coordinator._repRanges.value = repCounter.getRepRanges()
                    Logger.d("ActiveSessionEngine") { "POSITION BASELINE: Set initial baseline posA=${metric.positionA}, posB=${metric.positionB}" }

                    coordinator._loadBaselineA.value = metric.loadA
                    coordinator._loadBaselineB.value = metric.loadB
                    Logger.d("ActiveSessionEngine") { "LOAD BASELINE: Set initial baseline loadA=${metric.loadA}kg, loadB=${metric.loadB}kg" }
                }
            } catch (e: CancellationException) {
                if (retryRequest != null && !configurationTeardownHandedOff && !retryStartCommitted) {
                    if (configMayHaveReachedMachine) {
                        recoveryTeardownScheduled = recoverRetryStartAfterConfigAttempt(
                            retryRequest,
                            lease,
                            priorWorkoutState,
                        )
                    } else {
                        abortRetryStartBeforeConfig(retryRequest, lease, priorWorkoutState)
                    }
                }
                throw e // let cancellation propagate
            } catch (e: Exception) {
                Logger.e(e) { "workoutJob: uncaught exception" }
                coordinator._bleErrorEvents.tryEmit("Workout error: ${e.message}")
                if (retryRequest != null) {
                    if (!configurationTeardownHandedOff && !retryStartCommitted) {
                        if (configMayHaveReachedMachine) {
                            recoveryTeardownScheduled = recoverRetryStartAfterConfigAttempt(
                                retryRequest,
                                lease,
                                priorWorkoutState,
                            )
                        } else {
                            abortRetryStartBeforeConfig(retryRequest, lease, priorWorkoutState)
                        }
                    }
                } else {
                    failStart(lease, priorWorkoutState)
                }
            } finally {
                if (retryRequest != null &&
                    !retryStartCommitted &&
                    ownsAcceptedRetryStartClaim(retryRequest) &&
                    !recoveryTeardownScheduled
                ) {
                    if (configMayHaveReachedMachine) {
                        recoverRetryStartAfterConfigAttempt(retryRequest, lease, priorWorkoutState)
                    } else {
                        abortRetryStartBeforeConfig(retryRequest, lease, priorWorkoutState)
                    }
                }
            }
        }
        return lease
    }

    /**
     * Ensure routine session metadata is present for routine workouts so persisted
     * sessions (and backups) can be grouped back to the originating routine run.
     * Single-exercise temp routines and Just Lift sessions intentionally stay null.
     */
    private fun syncRoutineSessionContext() {
        val loadedRoutine = coordinator._loadedRoutine.value
        val isTrackedRoutine = loadedRoutine != null &&
            !loadedRoutine.id.startsWith(DefaultWorkoutSessionManager.TEMP_SINGLE_EXERCISE_PREFIX)

        if (!isTrackedRoutine) {
            coordinator.currentRoutineSessionId = null
            coordinator.currentRoutineName = null
            coordinator.currentRoutineId = null
            coordinator.routineAccumulatedCalories = 0f
            coordinator._completedRoutineSetKeys.value = emptySet()
            return
        }

        // Issue #392: Generate new session ID when routine changes OR when no ID exists.
        // Previously only checked isNullOrBlank, so switching from Routine A → B without
        // going through the natural "routine complete" path reused Routine A's session ID,
        // merging both routines into one history entry.
        if (coordinator.currentRoutineSessionId.isNullOrBlank() ||
            coordinator.currentRoutineId != loadedRoutine.id
        ) {
            coordinator.currentRoutineSessionId = KmpUtils.randomUUID()
        }
        coordinator.currentRoutineName = loadedRoutine.name
        coordinator.currentRoutineId = loadedRoutine.id
    }

    fun skipCountdown() {
        coordinator.skipCountdownRequested = true
        Logger.d { "skipCountdown: Countdown skip requested" }
    }

    /**
     * Issue #237: Start motion-start detection during countdown.
     * Collects metricsFlow and feeds load values to the MotionStartDetector.
     * On [MotionStartEvent.Started], calls [skipCountdown] to begin the set immediately.
     * On [MotionStartEvent.CountdownTick], updates hold progress for the ring-fill UI.
     * On [MotionStartEvent.Cancelled], resets progress.
     */
    private fun startMotionStartDetection(lease: ExecutionLease) {
        if (!hasCurrentAuthority(lease, "motion_start_detection")) return
        val prefs = settingsManager.userPreferences.value
        if (!prefs.motionStartEnabled) return

        motionStartDetector.reset()
        coordinator._motionStartHoldProgress.value = 0f

        // Listen for detector events
        motionStartListenerJob?.cancel()
        motionStartListenerJob = scope.launch {
            // Event listener coroutine
            launch {
                motionStartDetector.events.collect { event ->
                    if (!hasCurrentAuthority(lease, "motion_start_event")) return@collect
                    when (event) {
                        is MotionStartEvent.Started -> {
                            coordinator._motionStartHoldProgress.value = 1f
                            Logger.d { "MotionStart: Cable hold complete, skipping countdown" }
                            skipCountdown()
                        }

                        is MotionStartEvent.CountdownTick -> {
                            val progress = 1f - (event.remainingMs.toFloat() / 1500f)
                            coordinator._motionStartHoldProgress.value = progress.coerceIn(0f, 1f)
                        }

                        is MotionStartEvent.Cancelled -> {
                            coordinator._motionStartHoldProgress.value = 0f
                        }
                    }
                }
            }
            // Metric feeder coroutine
            launch {
                bleRepository.metricsFlow
                    .catch { e ->
                        if (e is CancellationException) throw e
                        Logger.e(e) { "metricsFlow collector error (motionStart)" }
                    }
                    .collect { metric ->
                        if (!hasCurrentAuthority(lease, "motion_start_metric")) return@collect
                        // Use average of both cables for load detection
                        val load = (metric.loadA + metric.loadB) / 2f
                        motionStartDetector.onMetricReceived(load, metric.timestamp)
                    }
            }
        }
    }

    /** Issue #237: Stop motion-start detection and clear UI state. */
    private fun stopMotionStartDetection() {
        motionStartListenerJob?.cancel()
        motionStartListenerJob = null
        motionStartDetector.reset()
        coordinator._motionStartHoldProgress.value = null
    }

    private fun captureExitSnapshot(
        completion: SetExecutionCompletion,
        terminalPath: TerminalPath,
    ): WorkoutExitSnapshot = exitSnapshotStore.getOrCapture(
        completion = completion,
        terminalPath = terminalPath,
        onInstalled = ::applyRoutineBookkeepingAtCapture,
    ) {
        buildExitSnapshot(completion, terminalPath)
    }

    private fun mergedWorkoutCompleteRepCount(
        published: RepCount,
        counter: RepCount,
        event: RepEvent,
    ): RepCount {
        val warmup = maxOf(published.warmupReps, counter.warmupReps, event.warmupCount)
        val working = maxOf(published.workingReps, counter.workingReps, event.workingCount)
        return counter.copy(
            warmupReps = warmup,
            workingReps = working,
            totalReps = maxOf(published.totalReps, counter.totalReps, working),
            isWarmupComplete = published.isWarmupComplete || counter.isWarmupComplete || warmup > 0 || working > 0,
        )
    }

    private fun completionFor(
        lease: ExecutionLease,
        reason: SetEndReason,
        actualReps: Int = coordinator._repCount.value.workingReps,
    ): SetExecutionCompletion {
        val facts = executionContext
            ?.takeIf { it.lease.executionId == lease.executionId && it.lease.sessionId == lease.sessionId }
            ?.completionFacts
            ?: coordinator._workoutParameters.value.let { params ->
                SetExecutionActivationFacts(
                    routineIdentity = null,
                    attemptNumber = 1,
                    acceptedDropCount = 0,
                    plannedSetType = if (lease.isAmrap) SetType.AMRAP else SetType.STANDARD,
                    programMode = params.programMode,
                    programmedBaseWeightPerCableKg = params.weightPerCableKg,
                    configuredStartWeightPerCableKg = params.weightPerCableKg,
                    progressionKg = params.progressionRegressionKg,
                    targetReps = lease.workingRepTarget.takeIf { !lease.isAmrap && it > 0 },
                    isWarmup = false,
                    isEcho = params.isEchoMode,
                    isJustLift = lease.isJustLift,
                    isBodyweight = lease.isBodyweight,
                    isTimed = lease.isTimedCable,
                    isAmrap = lease.isAmrap,
                    isCableExercise = lease.requiresMachine && !lease.isBodyweight,
                    logicalPreRackCommandTemplate = params.copy(
                        activeRackItemIds = params.activeRackItemIds.toList(),
                    ),
                )
            }
        return facts.complete(lease, reason, actualReps)
    }

    private fun buildExitSnapshot(
        completion: SetExecutionCompletion,
        terminalPath: TerminalPath,
    ): WorkoutExitSnapshot {
        val lease = completion.lease
        val params = coordinator._workoutParameters.value
        val repCount = coordinator._repCount.value
        val metrics = coordinator.collectedMetrics.value.toList()
        val exerciseIndex = coordinator._currentExerciseIndex.value
        val setIndex = coordinator._currentSetIndex.value
        val currentExercise = coordinator._loadedRoutine.value?.exercises?.getOrNull(exerciseIndex)
        val context = executionContext
            ?.takeIf { it.lease.executionId == lease.executionId && it.lease.sessionId == lease.sessionId }
            ?: WorkoutExecutionContext(
                lease = lease,
                exerciseName = currentExercise?.exercise?.name,
                preferredCableCount = currentExercise?.exercise?.preferredCableCount,
                displayMultiplier = currentExercise?.exercise?.displayMultiplier,
                sessionBodyWeightKg = resolvedSessionBodyWeightKg(),
                routineSessionId = coordinator.currentRoutineSessionId,
                routineId = coordinator.currentRoutineId,
                routineName = coordinator.currentRoutineName,
                cycleId = coordinator.activeCycleId,
                cycleDayNumber = coordinator.activeCycleDayNumber,
                completionFacts = SetExecutionActivationFacts(
                    routineIdentity = completion.routineIdentity,
                    attemptNumber = completion.attemptNumber,
                    acceptedDropCount = completion.acceptedDropCount,
                    plannedSetType = completion.plannedSetType,
                    programMode = completion.programMode,
                    programmedBaseWeightPerCableKg = completion.programmedBaseWeightPerCableKg,
                    configuredStartWeightPerCableKg = completion.configuredStartWeightPerCableKg,
                    progressionKg = completion.progressionKg,
                    targetReps = completion.targetReps,
                    isWarmup = completion.isWarmup,
                    isEcho = completion.isEcho,
                    isJustLift = completion.isJustLift,
                    isBodyweight = completion.isBodyweight,
                    isTimed = completion.isTimed,
                    isAmrap = completion.isAmrap,
                    isCableExercise = completion.isCableExercise,
                    logicalPreRackCommandTemplate = params.copy(
                        activeRackItemIds = params.activeRackItemIds.toList(),
                    ),
                ),
            )
        val summary = calculateSetSummaryMetrics(
            metrics = metrics,
            repCount = repCount.workingReps,
            fallbackWeightKg = params.weightPerCableKg,
            configuredWeightKgPerCable = params.weightPerCableKg,
            isEchoMode = params.isEchoMode,
            warmupRepsCount = repCount.warmupReps,
            workingRepsCount = repCount.workingReps,
            warmupCompleteTimeMs = coordinator.warmupCompleteTimeMs,
            cableCountHint = context.preferredCableCount,
            displayMultiplierHint = context.displayMultiplier,
        ).let { baseSummary ->
            applyBodyweightVolume(
                baseSummary,
                currentExercise,
                context.sessionBodyWeightKg,
                coordinator.bodyweightCompletionVariantOverride,
            )
        }
        val rackAdjustment = coordinator._currentRackLoadAdjustment.value
        val biomechanicsSummary = coordinator.biomechanicsEngine.getSetSummary()
            ?.deepCopyForExitSnapshot()
        val qualitySummary = try {
            coordinator.repQualityScorer.getSetSummary()
        } catch (_: IllegalStateException) {
            null
        }
        val effectiveStart = if (coordinator.warmupCompleteTimeMs > 0L) {
            coordinator.warmupCompleteTimeMs
        } else {
            coordinator.workoutStartTime
        }
        val savedWeightKg = if (lease.isBodyweight) {
            summary.heaviestLiftKgPerCable.takeIf { it > 0f } ?: params.weightPerCableKg
        } else {
            params.weightPerCableKg
        }
        val session = WorkoutSession(
            id = lease.sessionId,
            timestamp = coordinator.workoutStartTime,
            mode = params.programMode.displayName,
            reps = params.reps,
            weightPerCableKg = params.weightPerCableKg,
            progressionKg = params.progressionRegressionKg,
            duration = (wallClockMillisProvider() - effectiveStart).coerceAtLeast(0L),
            totalReps = repCount.totalReps,
            warmupReps = repCount.warmupReps,
            workingReps = repCount.workingReps,
            isJustLift = params.isJustLift,
            stopAtTop = params.stopAtTop,
            exerciseId = params.selectedExerciseId,
            exerciseName = context.exerciseName,
            routineSessionId = context.routineSessionId,
            routineName = context.routineName,
            routineId = context.routineId,
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
            cableCount = summary.cableCount,
            displayMultiplier = summary.displayMultiplier,
            externalAddedLoadKg = rackAdjustment.externalAddedLoadKg,
            counterweightKg = rackAdjustment.counterweightKg,
            rackItemsJson = coordinator.currentRackItemsJson,
            estimatedCalories = summary.estimatedCalories,
            warmupAvgWeightKg = if (params.isEchoMode) summary.warmupAvgWeightKg else null,
            workingAvgWeightKg = if (params.isEchoMode) summary.workingAvgWeightKg else null,
            burnoutAvgWeightKg = if (params.isEchoMode) summary.burnoutAvgWeightKg else null,
            peakWeightKg = if (params.isEchoMode) summary.peakWeightKg else null,
            rpe = coordinator._currentSetRpe.value,
            avgMcvMmS = biomechanicsSummary?.avgMcvMmS,
            avgAsymmetryPercent = biomechanicsSummary?.avgAsymmetryPercent,
            totalVelocityLossPercent = biomechanicsSummary?.totalVelocityLossPercent,
            dominantSide = biomechanicsSummary?.dominantSide,
            strengthProfile = biomechanicsSummary?.strengthProfile?.name,
            profileId = lease.profileId,
        )
        val hasExerciseIdentity = params.selectedExerciseId != null || params.isJustLift
        val persistZeroRepStallAttempt = completion.reason == SetEndReason.STALL_FAILURE &&
            completion.routineIdentity != null &&
            hasExerciseIdentity
        val completedSet = if (hasExerciseIdentity && (repCount.workingReps > 0 || persistZeroRepStallAttempt)) {
            CompletedSet(
                id = generateUUID(),
                sessionId = lease.sessionId,
                plannedSetId = context.completionFacts.routineIdentity?.plannedSetId,
                setNumber = context.completionFacts.routineIdentity?.logicalSetKey?.setIndex ?: setIndex,
                setType = context.completionFacts.plannedSetType,
                actualReps = repCount.workingReps,
                actualWeightKg = savedWeightKg,
                loggedRpe = coordinator._currentSetRpe.value,
                isPr = false,
                completedAt = wallClockMillisProvider(),
                setEndReason = completion.reason,
                routineExerciseId = context.completionFacts.routineIdentity?.routineExerciseId,
                attemptNumber = context.completionFacts.attemptNumber,
            )
        } else {
            null
        }
        val presentationSummary = summary.copy(
            qualitySummary = qualitySummary,
            biomechanicsSummary = biomechanicsSummary,
            sessionId = lease.sessionId,
            taggedExerciseId = params.selectedExerciseId,
            taggedExerciseName = context.exerciseName,
            isAmrap = params.isAMRAP,
        ).deepCopyForExitSnapshot()
        val isRoutineSet = context.routineSessionId != null
        val shouldUpdateCycleProgress = shouldUpdateCycleProgressAfterSavedSet(
            cycleId = context.cycleId,
            dayNumber = context.cycleDayNumber,
        )
        return WorkoutExitSnapshot(
            lease = lease,
            completion = completion,
            terminalPath = terminalPath,
            session = session,
            completedSet = completedSet,
            metrics = metrics,
            repMetrics = coordinator.setRepMetrics.value.map(RepMetricData::deepCopyForExitSnapshot),
            biomechanicsRepResults = biomechanicsSummary?.repResults.orEmpty()
                .map { it.deepCopyForExitSnapshot() },
            singleExerciseDefaults = captureSingleExerciseDefaultsFromWorkout(),
            presentationSummary = presentationSummary,
            exerciseIndex = exerciseIndex,
            setIndex = setIndex,
            isRoutineSet = isRoutineSet,
            shouldAccumulateRoutineCalories = isRoutineSet && isValidCompletedSession(session),
            shouldExportIndividualHealthSession = !isRoutineSet,
            shouldExportIndividualBackup = !isRoutineSet &&
                preferencesManager.preferencesFlow.value.autoBackupEnabled &&
                dataBackupManager != null,
            shouldUpdateCycleProgress = shouldUpdateCycleProgress,
            cycleId = context.cycleId.takeIf { shouldUpdateCycleProgress },
            cycleDayNumber = context.cycleDayNumber.takeIf { shouldUpdateCycleProgress },
            postSaveInput = PostSaveWorkoutInput(
                profileId = lease.profileId,
                exerciseId = params.selectedExerciseId,
                workingReps = repCount.workingReps,
                achievedWeightKg = summary.heaviestLiftKgPerCable,
                volumeWeightKg = savedWeightKg,
                programMode = params.programMode,
                isJustLift = params.isJustLift,
                isEchoMode = params.isEchoMode,
                peakConcentricForceKg = maxOf(summary.peakForceConcentricA, summary.peakForceConcentricB),
                peakEccentricForceKg = maxOf(summary.peakForceEccentricA, summary.peakForceEccentricB),
                sessionMcvMmS = session.avgMcvMmS,
            ),
        )
    }

    private fun applyRoutineBookkeepingAtCapture(snapshot: WorkoutExitSnapshot) {
        if (!snapshot.shouldAccumulateRoutineCalories || !executionGuard.isCurrent(snapshot.lease)) return
        snapshot.session.estimatedCalories?.takeIf { it > 0f }?.let { calories ->
            coordinator.routineAccumulatedCalories += calories
        }
        coordinator._completedRoutineSetKeys.update {
            it + (snapshot.exerciseIndex to snapshot.setIndex)
        }
    }

    private fun launchSnapshotPersistence(snapshot: WorkoutExitSnapshot) {
        val stableSessionId = snapshot.completion.lease.sessionId
        when (executionGuard.claimPersistence(stableSessionId, snapshot.terminalPath)) {
            PersistenceClaimResult.Claimed -> scope.launch { persistSnapshot(snapshot) }

            PersistenceClaimResult.DuplicateInProgress,
            PersistenceClaimResult.AlreadyPersisted,
            -> logPersistenceDeduplicated(snapshot)
        }
    }

    private fun retryRetainedWorkoutExitPersistence() {
        scope.launch {
            exitSnapshotStore.retainedSnapshots().forEach(::launchSnapshotPersistence)
        }
    }

    internal fun retryWorkoutExitPersistence(sessionId: String): Boolean {
        val snapshot = exitSnapshotStore.findBySessionId(sessionId) ?: return false
        val stableSessionId = snapshot.completion.lease.sessionId
        return when (executionGuard.claimPersistence(stableSessionId, snapshot.terminalPath)) {
            PersistenceClaimResult.Claimed -> {
                scope.launch { persistSnapshot(snapshot) }
                true
            }

            PersistenceClaimResult.DuplicateInProgress,
            PersistenceClaimResult.AlreadyPersisted,
            -> false
        }
    }

    private suspend fun persistSnapshot(snapshot: WorkoutExitSnapshot) {
        val sessionId = snapshot.completion.lease.sessionId
        require(snapshot.session.id == sessionId) {
            "Workout exit snapshot session must match its completion lease"
        }
        var persistenceSucceeded = false
        try {
            if (workoutRepository.getSession(sessionId) == null) {
                workoutRepository.saveSession(snapshot.session)
            }
            if (snapshot.metrics.isNotEmpty()) {
                workoutRepository.saveMetrics(sessionId, snapshot.metrics)
            }
            snapshot.completedSet?.let { completedSet ->
                val alreadySaved = completedSetRepository.getCompletedSets(sessionId)
                    .any { it.id == completedSet.id }
                if (!alreadySaved) {
                    completedSetRepository.saveCompletedSet(completedSet)
                }
            }
            repMetricRepository.deleteRepMetrics(sessionId)
            if (snapshot.repMetrics.isNotEmpty()) {
                repMetricRepository.saveRepMetrics(sessionId, snapshot.repMetrics)
            }
            biomechanicsRepository.deleteRepBiomechanics(sessionId)
            if (snapshot.biomechanicsRepResults.isNotEmpty()) {
                biomechanicsRepository.saveRepBiomechanics(sessionId, snapshot.biomechanicsRepResults)
            }
            snapshot.singleExerciseDefaults?.let { defaults ->
                settingsManager.mutateWorkout(snapshot.lease.profileId) { workoutPreferences ->
                    workoutPreferences.copy(
                        singleExerciseDefaults = workoutPreferences.singleExerciseDefaults +
                            (defaults.exerciseId to defaults),
                    )
                }
            }

            val postSave = snapshot.postSaveInput
            val hasPR = gamificationManager.processPostSaveEvents(
                exerciseId = postSave.exerciseId,
                workingReps = postSave.workingReps,
                achievedWeightKg = postSave.achievedWeightKg,
                volumeWeightKg = postSave.volumeWeightKg,
                programMode = postSave.programMode,
                isJustLift = postSave.isJustLift,
                isEchoMode = postSave.isEchoMode,
                peakConcentricForceKg = postSave.peakConcentricForceKg,
                peakEccentricForceKg = postSave.peakEccentricForceKg,
                profileId = postSave.profileId,
                sessionMcvMmS = postSave.sessionMcvMmS,
            )
            if (hasPR) {
                snapshot.completedSet?.let { completedSetRepository.markAsPr(it.id) }
            }
            if (snapshot.shouldExportIndividualHealthSession) {
                enqueueWorkoutHealthPush(snapshot.session)
            }
            if (snapshot.shouldExportIndividualBackup) {
                scope.launch {
                    dataBackupManager?.exportSession(sessionId)
                        ?.onFailure { error -> Logger.w(error) { "Auto-backup failed for session $sessionId" } }
                }
            }
            updateCycleProgressFromSnapshot(snapshot)
            scope.launch { syncTriggerManager?.onWorkoutCompleted() }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                executionGuard.markPersistenceSucceeded(sessionId)
                exitSnapshotStore.remove(snapshot)
                executionGuard.prunePersistedClaims(retainNewest = 32)
            }
            persistenceSucceeded = true
        } catch (error: CancellationException) {
            executionGuard.markPersistenceFailed(sessionId)
            throw error
        } catch (error: Exception) {
            executionGuard.markPersistenceFailed(sessionId)
            Logger.e(error) { "Failed to persist workout snapshot for session $sessionId" }
            coordinator._userFeedbackEvents.tryEmit("Workout data couldn't be saved. Please try again.")
        }
        if (!persistenceSucceeded) return
        try {
            tryStartCurrentAcceptedRetryLive()
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                failCurrentAcceptedRetryDispatchClosed(sessionId)
            }
            throw error
        } catch (error: Exception) {
            Logger.e(error) { "Failed to dispatch accepted retry for persisted session $sessionId" }
            failCurrentAcceptedRetryDispatchClosed(sessionId)
        }
    }

    private suspend fun failCurrentAcceptedRetryDispatchClosed(sourceStableSessionId: String) {
        val recoveryPublicationEpoch = executionGuard.captureRecoveryPublicationEpoch()
        val authority = restTransitionMutex.withLock {
            val document = activeRuntimeDocument ?: return@withLock null
            val plan = coordinator._restTransitionPlan.value as? RestTransitionPlan.AcceptedRetry
                ?: return@withLock null
            if (document.sourceStableSessionId != sourceStableSessionId || document.restTransitionPlan != plan) {
                return@withLock null
            }
            val source = executionGuard.currentLease
                ?.takeIf { leaseMatchesRetrySource(it, document) }
                ?: return@withLock null
            Triple(
                plan,
                activeRuntimeDocumentVersion,
                RetryFailClosedAuthority.Live(source, recoveryPublicationEpoch),
            )
        } ?: return
        failAcceptedRetryClosed(authority.first, authority.second, authority.third)
    }

    private suspend fun updateCycleProgressFromSnapshot(snapshot: WorkoutExitSnapshot) {
        if (!snapshot.shouldUpdateCycleProgress) return
        val cycleId = snapshot.cycleId ?: return
        val dayNumber = snapshot.cycleDayNumber ?: return
        updateCycleProgress(cycleId, dayNumber)
    }

    private fun logPersistenceDeduplicated(snapshot: WorkoutExitSnapshot) {
        logExecutionEvent(
            LogEventType.WORKOUT_PERSISTENCE,
            "executionId=${snapshot.lease.executionId},sessionId=${snapshot.session.id}," +
                "transition=deduplicated,path=${snapshot.terminalPath}",
        )
    }

    private fun cancelSetOwnedPresentationJobs(lease: ExecutionLease) {
        executionGuard.cancelPresentationJobsFor(lease)
        coordinator._weightAdjustmentRecommendation.value = null
        coordinator.isCurrentWorkoutTimed = false
        coordinator.isCurrentTimedCableExercise = false
        coordinator._isCurrentExerciseBodyweight.value = false
        coordinator._hapticEvents.tryEmit(HapticEvent.WORKOUT_END)
        coordinator.workoutJob?.cancel()
        coordinator.workoutJob = null
        coordinator.bodyweightTimerJob?.cancel()
        coordinator.bodyweightTimerJob = null
        coordinator._timedExerciseRemainingSeconds.value = null
        coordinator.restTimerJob?.cancel()
        coordinator.restTimerJob = null
        coordinator.restDeadlineElapsedRealtimeMs = null
        cancelAutoStartTimer()
        cancelJustLiftEggTimer()
        stopMotionStartDetection()
        repFreshnessGate.invalidate(lease)
        coordinator.setRepMetrics.value = emptyList()
        resetBiomechanicsContext(lease)
        coordinator.repQualityScorer.reset()
        coordinator._latestRepQuality.value = null
        coordinator.repBoundaryTimestamps.value = emptyList()
    }

    private fun detachEndedRoutineFromCoordinator() {
        coordinator._loadedRoutine.value = null
        coordinator.routineStartTime = 0
        coordinator.currentRoutineSessionId = null
        coordinator.currentRoutineName = null
        coordinator.currentRoutineId = null
        coordinator.routineAccumulatedCalories = 0f
        coordinator._completedRoutineSetKeys.value = emptySet()
        coordinator.routineLaunchOrigin = null
    }

    private fun captureRuntimeCleanupTarget(
        owner: RestoredRuntimeOwner,
        reason: RuntimeCleanupReason,
        exitPresentation: Boolean = false,
    ) = RuntimeCleanupTarget(
        reason = reason,
        lookupKey = owner.handle.lookupKey,
        initialRowRevision = null,
        expectedDocument = owner.document,
        source = RuntimeCleanupSource.RestoredOwner(
            owner = owner.guardOwner,
            engineVersion = owner.documentVersion,
        ),
        planVariant = runtimeCleanupPlanVariant(owner.document),
        exitPresentation = exitPresentation,
    )

    private fun captureRuntimeCleanupTarget(
        candidate: RuntimeCleanupCandidate,
        reason: RuntimeCleanupReason,
        exitPresentation: Boolean = false,
    ) = RuntimeCleanupTarget(
        reason = reason,
        lookupKey = ActiveWorkoutRuntimeLookupKey(
            profileId = candidate.document.profileId,
            routineSessionId = candidate.document.routineSessionId,
        ),
        initialRowRevision = (candidate.source as? RuntimeCleanupSource.ColdHandle)?.rowRevision,
        expectedDocument = candidate.document,
        source = candidate.source,
        planVariant = runtimeCleanupPlanVariant(candidate.document),
        exitPresentation = exitPresentation,
    )

    private fun captureColdRuntimeCleanupTarget(
        handle: RoutineResumeHandle.Persisted,
        loadResult: ActiveWorkoutRuntimeLoadResult,
        reason: RuntimeCleanupReason,
    ): RuntimeCleanupTarget {
        val document = (loadResult as? ActiveWorkoutRuntimeLoadResult.Loaded)?.document
        return RuntimeCleanupTarget(
            reason = reason,
            lookupKey = handle.lookupKey,
            initialRowRevision = handle.rowRevision,
            expectedDocument = document,
            source = RuntimeCleanupSource.ColdHandle(handle.rowRevision),
            planVariant = document?.let(::runtimeCleanupPlanVariant) ?: RuntimeCleanupPlanVariant.NONE,
            exitPresentation = false,
        )
    }

    private fun runtimeCleanupPlanVariant(document: ActiveWorkoutRuntimeDocument) = when (document.restTransitionPlan) {
        null -> RuntimeCleanupPlanVariant.NONE
        is RestTransitionPlan.NormalAdvance -> RuntimeCleanupPlanVariant.NORMAL
        is RestTransitionPlan.UnresolvedDropOffer -> RuntimeCleanupPlanVariant.UNRESOLVED
        is RestTransitionPlan.AcceptedRetry -> RuntimeCleanupPlanVariant.ACCEPTED
        is RestTransitionPlan.Declined -> RuntimeCleanupPlanVariant.DECLINED
    }

    private fun beginRestoredRuntimeTerminalCleanup(
        owner: RestoredRuntimeOwner,
        reason: RuntimeCleanupReason,
    ) {
        val candidate = captureRuntimeCleanupTarget(owner, reason, exitPresentation = true)
        val target = installRuntimeCleanupIntent(candidate)
            ?: pendingRuntimeCleanupRef.value.firstOrNull { existing ->
                sameRuntimeCleanupTargetIdentity(existing, candidate)
            }
            ?: run {
                coordinator.stopWorkoutInProgress.value = false
                return
            }
        ownStopWorkoutCleanupTarget(target)
        executionGuard.supersedeRecoveryPublication()
        executionGuard.supersedeQueuedSuccessors()
        supersedePendingResetStart()
        launchRuntimeCleanup(target)
    }

    internal fun beginRoutineCompletedRuntimeCleanup() {
        beginTrackedRuntimeCleanup(RuntimeCleanupReason.ROUTINE_COMPLETED)
    }

    internal fun beginRoutineAbandonmentRuntimeCleanup() {
        beginTrackedRuntimeCleanup(RuntimeCleanupReason.EXPLICIT_RESTART)
    }

    private fun beginTrackedRuntimeCleanup(reason: RuntimeCleanupReason) {
        beginTrackedRuntimeCleanup(
            reason = reason,
            candidate = runtimeCleanupCandidateRef.value ?: return,
        )
    }

    private fun beginTrackedRuntimeCleanup(
        reason: RuntimeCleanupReason,
        candidate: RuntimeCleanupCandidate,
    ) {
        val capturedTarget = captureRuntimeCleanupTarget(
            candidate = candidate,
            reason = reason,
            exitPresentation = reason == RuntimeCleanupReason.PROFILE_CHANGED,
        )
        val target = installRuntimeCleanupIntent(capturedTarget)
        if (target == null) {
            if (capturedTarget.exitPresentation) {
                pendingRuntimeCleanupRef.value
                    .firstOrNull { existing -> sameRuntimeCleanupTargetIdentity(existing, capturedTarget) }
                    ?.let(::exitRuntimeCleanupPresentationIfCurrent)
            }
            return
        }
        launchRuntimeCleanup(target)
    }

    private fun exitRuntimeCleanupPresentationIfCurrent(
        target: RuntimeCleanupTarget,
        requireDifferentProfile: Boolean = true,
    ) {
        val document = target.expectedDocument ?: return
        executionGuard.mutateConfigurationInputsIf(
            candidateStillCurrent = {
                if (!runtimeCleanupTargetIsPending(target)) return@mutateConfigurationInputsIf false
                if (requireDifferentProfile) {
                    val readyProfile = userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready
                        ?: return@mutateConfigurationInputsIf false
                    if (readyProfile.profile.id == document.profileId) return@mutateConfigurationInputsIf false
                }
                val loadedRoutine = coordinator._loadedRoutine.value
                coordinator.currentRoutineId == document.routineId &&
                    coordinator.currentRoutineSessionId == document.routineSessionId &&
                    (
                        loadedRoutine == null ||
                            (
                                loadedRoutine.id == document.routineId &&
                                    (loadedRoutine.profileId ?: "default") == document.profileId
                                )
                        )
            },
        ) {
            detachEndedRoutineFromCoordinator()
            coordinator._workoutState.value = WorkoutState.Idle
            coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
        }
    }

    private fun exitProfileMismatchedPresentationIfCurrent(readyProfileId: String) {
        val loadedRoutine = coordinator._loadedRoutine.value ?: return
        if ((loadedRoutine.profileId ?: "default") == readyProfileId) return
        val routineId = coordinator.currentRoutineId
        val routineSessionId = coordinator.currentRoutineSessionId
        executionGuard.mutateConfigurationInputsIf(
            candidateStillCurrent = {
                val currentReady = userProfileRepository.activeProfileContext.value as? ActiveProfileContext.Ready
                    ?: return@mutateConfigurationInputsIf false
                currentReady.profile.id == readyProfileId &&
                    coordinator._loadedRoutine.value === loadedRoutine &&
                    coordinator.currentRoutineId == routineId &&
                    coordinator.currentRoutineSessionId == routineSessionId
            },
        ) {
            detachEndedRoutineFromCoordinator()
            coordinator._workoutState.value = WorkoutState.Idle
            coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
        }
    }

    private fun runtimeCleanupTargetMatchesCandidate(
        target: RuntimeCleanupTarget,
        candidate: RuntimeCleanupCandidate,
    ): Boolean {
        if (target.expectedDocument != candidate.document) return false
        val targetEngineVersion = when (val source = target.source) {
            is RuntimeCleanupSource.ActiveDocument -> source.engineVersion

            is RuntimeCleanupSource.RestoredOwner -> source.engineVersion

            is RuntimeCleanupSource.PendingEngineReplace -> source.expectedPublishedEngineVersion

            is RuntimeCleanupSource.PendingInitialReplace,
            is RuntimeCleanupSource.ColdHandle,
            -> null
        }
        val candidateEngineVersion = when (val source = candidate.source) {
            is RuntimeCleanupSource.ActiveDocument -> source.engineVersion

            is RuntimeCleanupSource.RestoredOwner -> source.engineVersion

            is RuntimeCleanupSource.PendingEngineReplace -> source.expectedPublishedEngineVersion

            is RuntimeCleanupSource.PendingInitialReplace,
            is RuntimeCleanupSource.ColdHandle,
            -> null
        }
        if (targetEngineVersion != null || candidateEngineVersion != null) {
            return targetEngineVersion != null && targetEngineVersion == candidateEngineVersion
        }
        return target.source == candidate.source
    }

    private fun handoffPendingRuntimeReplaceToCleanup(pending: PendingRuntimeReplaceCandidate) {
        val ancestor = pendingRuntimeCleanupRef.value.firstOrNull { target ->
            runtimeCleanupTargetMatchesCandidate(target, pending.origin)
        } ?: return
        enqueueRuntimeCleanupTarget(
            RuntimeCleanupTarget(
                reason = ancestor.reason,
                lookupKey = ActiveWorkoutRuntimeLookupKey(
                    profileId = pending.document.profileId,
                    routineSessionId = pending.document.routineSessionId,
                ),
                initialRowRevision = null,
                expectedDocument = pending.document,
                source = RuntimeCleanupSource.PendingEngineReplace(
                    candidateToken = pending.candidateToken,
                    expectedPublishedEngineVersion = pending.expectedPublishedEngineVersion,
                ),
                planVariant = runtimeCleanupPlanVariant(pending.document),
                exitPresentation = ancestor.exitPresentation,
            ),
        )
    }

    private fun installRuntimeCleanupIntent(target: RuntimeCleanupTarget): RuntimeCleanupTarget? {
        val installed = enqueueRuntimeCleanupTarget(target) ?: return null
        val directOwner = (target.source as? RuntimeCleanupSource.RestoredOwner)?.owner
        val matchingOwner = restoredRuntimeOwnerRef.value
            ?.takeIf { owner ->
                val targetVersion = when (val source = target.source) {
                    is RuntimeCleanupSource.ActiveDocument -> source.engineVersion

                    is RuntimeCleanupSource.RestoredOwner -> source.engineVersion

                    is RuntimeCleanupSource.PendingEngineReplace -> source.expectedPublishedEngineVersion

                    is RuntimeCleanupSource.PendingInitialReplace,
                    is RuntimeCleanupSource.ColdHandle,
                    -> null
                }
                owner.document == target.expectedDocument &&
                    (targetVersion == null || owner.documentVersion == targetVersion)
            }
            ?.guardOwner
        (directOwner ?: matchingOwner)?.let { owner ->
            revokeRestoredActionAuthorityLocked(owner)
            clearRestoredTeardownRetryOwnerIfOwned(owner)
        }
        clearAcceptedRetryStartClaimIfOwned(target)
        pendingRuntimeReplaceCandidateRef.value?.let(::handoffPendingRuntimeReplaceToCleanup)
        return installed
    }

    private fun launchRuntimeCleanup(target: RuntimeCleanupTarget): Job = scope.launch {
        clearActiveWorkoutRuntime(target)
    }

    private fun handoffStopWorkoutCleanupTarget(
        previous: RuntimeCleanupTarget,
        successor: RuntimeCleanupTarget,
    ) {
        stopWorkoutCleanupTargetRef.compareAndSet(previous, successor)
    }

    private fun ownStopWorkoutCleanupTarget(target: RuntimeCleanupTarget) {
        stopWorkoutCleanupTargetRef.value = target
        if (!runtimeCleanupTargetIsPending(target)) {
            releaseStopWorkoutGuardIfOwned(target)
        }
    }

    private fun releaseStopWorkoutGuardIfOwned(target: RuntimeCleanupTarget) {
        if (stopWorkoutCleanupTargetRef.compareAndSet(target, null)) {
            coordinator.stopWorkoutInProgress.value = false
        }
    }

    private fun sameRuntimeCleanupTargetIdentity(
        left: RuntimeCleanupTarget,
        right: RuntimeCleanupTarget,
    ): Boolean {
        if (left.lookupKey != right.lookupKey ||
            left.initialRowRevision != right.initialRowRevision ||
            left.expectedDocument != right.expectedDocument
        ) {
            return false
        }
        if (left.source == right.source) return true
        val leftEngineVersion = when (val source = left.source) {
            is RuntimeCleanupSource.ActiveDocument -> source.engineVersion

            is RuntimeCleanupSource.RestoredOwner -> source.engineVersion

            is RuntimeCleanupSource.PendingEngineReplace -> source.expectedPublishedEngineVersion

            is RuntimeCleanupSource.PendingInitialReplace,
            is RuntimeCleanupSource.ColdHandle,
            -> null
        }
        val rightEngineVersion = when (val source = right.source) {
            is RuntimeCleanupSource.ActiveDocument -> source.engineVersion

            is RuntimeCleanupSource.RestoredOwner -> source.engineVersion

            is RuntimeCleanupSource.PendingEngineReplace -> source.expectedPublishedEngineVersion

            is RuntimeCleanupSource.PendingInitialReplace,
            is RuntimeCleanupSource.ColdHandle,
            -> null
        }
        return leftEngineVersion != null && leftEngineVersion == rightEngineVersion
    }

    private fun enqueueRuntimeCleanupTarget(candidate: RuntimeCleanupTarget): RuntimeCleanupTarget? {
        while (true) {
            val current = pendingRuntimeCleanupRef.value
            if (current.any { existing -> sameRuntimeCleanupTargetIdentity(existing, candidate) }) return null
            if (pendingRuntimeCleanupRef.compareAndSet(current, current + candidate)) {
                connectionLogRepository.info(
                    LogEventType.WORKOUT_PERSISTENCE,
                    "Active workout runtime cleanup",
                    details = "reason=${candidate.reason},planVariant=${candidate.planVariant}",
                )
                return candidate
            }
        }
    }

    private fun runtimeCleanupTargetIsPending(target: RuntimeCleanupTarget): Boolean = pendingRuntimeCleanupRef.value.any { it === target }

    private fun removeRuntimeCleanupTarget(target: RuntimeCleanupTarget) {
        while (true) {
            val current = pendingRuntimeCleanupRef.value
            if (current.none { it === target }) return
            val updated = current.filterNot { it === target }
            if (pendingRuntimeCleanupRef.compareAndSet(current, updated)) return
        }
    }

    private fun clearAcceptedRetryStartClaimIfOwned(target: RuntimeCleanupTarget) {
        val document = target.expectedDocument ?: return
        val transitionId = document.restTransitionPlan?.transitionId ?: return
        while (true) {
            val claim = acceptedRetryStartClaim.value ?: return
            if (claim.sourceStableSessionId != document.sourceStableSessionId ||
                claim.sourceExecutionId != document.sourceExecutionId ||
                claim.transitionId != transitionId
            ) {
                return
            }
            if (acceptedRetryStartClaim.compareAndSet(claim, null)) return
        }
    }

    private fun detachTransitionNavigationForCleanup(
        target: RuntimeCleanupTarget,
    ): CompletableDeferred<CachedTransitionNavigation?>? {
        val document = target.expectedDocument ?: return null
        val transitionId = document.restTransitionPlan?.transitionId
        val sourceExecutionId = document.sourceExecutionId
        if (cachedTransitionNavigation?.let {
                it.transitionId == transitionId && it.sourceExecutionId == sourceExecutionId
            } == true
        ) {
            cachedTransitionNavigation = null
        }
        val pending = pendingTransitionNavigation?.takeIf {
            it.transitionId == transitionId && it.sourceExecutionId == sourceExecutionId
        }
        if (pending != null) {
            pendingTransitionNavigation = null
        }
        return pending?.result
    }

    private fun makeRuntimeCleanupInertLocked(
        target: RuntimeCleanupTarget,
    ): RuntimeCleanupDetachedEffects {
        val document = target.expectedDocument ?: return RuntimeCleanupDetachedEffects()
        val expectedEngineVersion = when (val source = target.source) {
            is RuntimeCleanupSource.ActiveDocument -> source.engineVersion

            is RuntimeCleanupSource.RestoredOwner -> source.engineVersion

            is RuntimeCleanupSource.PendingEngineReplace -> source.expectedPublishedEngineVersion

            is RuntimeCleanupSource.PendingInitialReplace,
            is RuntimeCleanupSource.ColdHandle,
            -> null
        }
        val activeTargetMatches = expectedEngineVersion != null &&
            activeRuntimeDocument == document &&
            activeRuntimeDocumentVersion == expectedEngineVersion
        val pendingTargetMatches = target.source is RuntimeCleanupSource.PendingInitialReplace &&
            pendingInitialRestRuntimeDocument === document
        if (!activeTargetMatches && !pendingTargetMatches) return RuntimeCleanupDetachedEffects()

        if (activeTargetMatches) {
            activeRuntimeDocument = null
            activeRuntimeDocumentVersion += 1L
        }
        if (pendingTargetMatches) pendingInitialRestRuntimeDocument = null
        if (coordinator._restTransitionPlan.value == document.restTransitionPlan) {
            coordinator._restTransitionPlan.value = null
        }
        val persistedTimerJob = persistedRestTimerOwner?.job
        persistedRestTimerOwner = null
        val legacyTimerJob = coordinator.restTimerJob
        coordinator.restTimerJob = null
        coordinator.restDeadlineElapsedRealtimeMs = null
        coordinator._restSecondsRemaining.value = 0
        coordinator._restOriginalDuration.value = 0
        coordinator._isRestPaused.value = false
        val pendingNavigation = detachTransitionNavigationForCleanup(target)
        if (target.exitPresentation &&
            coordinator.currentRoutineId == document.routineId &&
            coordinator.currentRoutineSessionId == document.routineSessionId
        ) {
            detachEndedRoutineFromCoordinator()
            coordinator._workoutState.value = WorkoutState.Idle
            coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
        }
        return RuntimeCleanupDetachedEffects(
            completeNavigationFollower = pendingNavigation?.let { follower ->
                { follower.complete(null) }
            },
            jobs = listOfNotNull(persistedTimerJob, legacyTimerJob).distinct(),
        )
    }

    private fun pendingEngineReplacementTarget(
        predecessor: RuntimeCleanupTarget,
        document: ActiveWorkoutRuntimeDocument,
    ): RuntimeCleanupTarget? = pendingRuntimeCleanupRef.value.firstOrNull { candidate ->
        candidate !== predecessor &&
            candidate.reason == predecessor.reason &&
            candidate.lookupKey == predecessor.lookupKey &&
            candidate.expectedDocument == document &&
            candidate.source is RuntimeCleanupSource.PendingEngineReplace
    }

    private suspend fun clearActiveWorkoutRuntime(target: RuntimeCleanupTarget): RuntimeCleanupResult {
        var detachedEffects = RuntimeCleanupDetachedEffects()
        return try {
            restTransitionMutex.withLock {
                var currentTarget = target
                while (true) {
                    if (!runtimeCleanupTargetIsPending(currentTarget)) {
                        releaseStopWorkoutGuardIfOwned(currentTarget)
                        return@withLock RuntimeCleanupResult.SUPERSEDED
                    }
                    val nextEffects = makeRuntimeCleanupInertLocked(currentTarget)
                    detachedEffects = RuntimeCleanupDetachedEffects(
                        completeNavigationFollower = detachedEffects.completeNavigationFollower
                            ?: nextEffects.completeNavigationFollower,
                        jobs = (detachedEffects.jobs + nextEffects.jobs).distinct(),
                    )
                    val loaded = activeWorkoutRuntimeRepository.load(
                        profileId = currentTarget.lookupKey.profileId,
                        routineSessionId = currentTarget.lookupKey.routineSessionId,
                    )
                    currentCoroutineContext().ensureActive()
                    val revision = when (loaded) {
                        ActiveWorkoutRuntimeLoadResult.Missing -> {
                            finishRuntimeCleanupLocked(currentTarget)
                            return@withLock RuntimeCleanupResult.CLEARED
                        }

                        is ActiveWorkoutRuntimeLoadResult.Loaded -> {
                            val exactLoadedTarget = if (currentTarget.source is RuntimeCleanupSource.ColdHandle) {
                                loaded.rowRevision == currentTarget.initialRowRevision
                            } else {
                                currentTarget.expectedDocument != null &&
                                    loaded.document == currentTarget.expectedDocument
                            }
                            if (!exactLoadedTarget) {
                                val successor = pendingEngineReplacementTarget(currentTarget, loaded.document)
                                if (successor != null) {
                                    handoffStopWorkoutCleanupTarget(currentTarget, successor)
                                }
                                retireRuntimeCleanupCandidate(currentTarget)
                                removeRuntimeCleanupTarget(currentTarget)
                                if (successor == null) {
                                    releaseStopWorkoutGuardIfOwned(currentTarget)
                                    return@withLock RuntimeCleanupResult.SUPERSEDED
                                }
                                currentTarget = successor
                                continue
                            }
                            loaded.rowRevision
                        }

                        is ActiveWorkoutRuntimeLoadResult.Rejected -> {
                            if (currentTarget.source !is RuntimeCleanupSource.ColdHandle ||
                                loaded.rowRevision != currentTarget.initialRowRevision
                            ) {
                                retireRuntimeCleanupCandidate(currentTarget)
                                removeRuntimeCleanupTarget(currentTarget)
                                releaseStopWorkoutGuardIfOwned(currentTarget)
                                return@withLock RuntimeCleanupResult.SUPERSEDED
                            }
                            loaded.rowRevision
                        }
                    }
                    val deleted = activeWorkoutRuntimeRepository.deleteIfRevisionMatches(
                        profileId = currentTarget.lookupKey.profileId,
                        routineSessionId = currentTarget.lookupKey.routineSessionId,
                        expectedRevision = revision,
                    )
                    currentCoroutineContext().ensureActive()
                    if (!deleted) {
                        val current = activeWorkoutRuntimeRepository.load(
                            profileId = currentTarget.lookupKey.profileId,
                            routineSessionId = currentTarget.lookupKey.routineSessionId,
                        )
                        currentCoroutineContext().ensureActive()
                        val exactTargetStillPresent = when (current) {
                            ActiveWorkoutRuntimeLoadResult.Missing -> {
                                finishRuntimeCleanupLocked(currentTarget)
                                return@withLock RuntimeCleanupResult.CLEARED
                            }

                            is ActiveWorkoutRuntimeLoadResult.Loaded ->
                                current.rowRevision == revision &&
                                    if (currentTarget.source is RuntimeCleanupSource.ColdHandle) {
                                        current.rowRevision == currentTarget.initialRowRevision
                                    } else {
                                        currentTarget.expectedDocument != null &&
                                            current.document == currentTarget.expectedDocument
                                    }

                            is ActiveWorkoutRuntimeLoadResult.Rejected ->
                                currentTarget.source is RuntimeCleanupSource.ColdHandle &&
                                    current.rowRevision == revision &&
                                    current.rowRevision == currentTarget.initialRowRevision
                        }
                        if (!exactTargetStillPresent) {
                            retireRuntimeCleanupCandidate(currentTarget)
                            removeRuntimeCleanupTarget(currentTarget)
                            releaseStopWorkoutGuardIfOwned(currentTarget)
                            return@withLock RuntimeCleanupResult.SUPERSEDED
                        }
                        return@withLock RuntimeCleanupResult.RETRYABLE_FAILURE
                    }
                    finishRuntimeCleanupLocked(currentTarget)
                    return@withLock RuntimeCleanupResult.CLEARED
                }
                @Suppress("UNREACHABLE_CODE")
                RuntimeCleanupResult.SUPERSEDED
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            RuntimeCleanupResult.RETRYABLE_FAILURE
        } finally {
            detachedEffects.jobs.forEach { it.cancel() }
            detachedEffects.completeNavigationFollower?.invoke()
        }
    }

    private fun finishRuntimeCleanupLocked(target: RuntimeCleanupTarget) {
        val document = target.expectedDocument
        if (document == null) {
            removeRuntimeCleanupTarget(target)
            releaseStopWorkoutGuardIfOwned(target)
            return
        }
        val expectedEngineVersion = when (val source = target.source) {
            is RuntimeCleanupSource.ActiveDocument -> source.engineVersion

            is RuntimeCleanupSource.RestoredOwner -> source.engineVersion

            is RuntimeCleanupSource.PendingEngineReplace -> source.expectedPublishedEngineVersion

            is RuntimeCleanupSource.PendingInitialReplace,
            is RuntimeCleanupSource.ColdHandle,
            -> null
        }
        val activeTargetMatches = expectedEngineVersion != null &&
            activeRuntimeDocument == document &&
            activeRuntimeDocumentVersion == expectedEngineVersion
        val pendingTargetMatches = target.source is RuntimeCleanupSource.PendingInitialReplace &&
            pendingInitialRestRuntimeDocument === document
        if (activeTargetMatches) {
            activeRuntimeDocument = null
            activeRuntimeDocumentVersion += 1L
        }
        if (pendingTargetMatches) {
            pendingInitialRestRuntimeDocument = null
        }
        if ((activeTargetMatches || pendingTargetMatches) &&
            coordinator._restTransitionPlan.value == document.restTransitionPlan
        ) {
            coordinator._restTransitionPlan.value = null
        }
        retireRuntimeCleanupCandidate(target)
        removeRuntimeCleanupTarget(target)
        releaseStopWorkoutGuardIfOwned(target)
    }

    private fun retireRuntimeCleanupCandidate(target: RuntimeCleanupTarget) {
        val document = target.expectedDocument ?: return
        val expectedEngineVersion = when (val source = target.source) {
            is RuntimeCleanupSource.ActiveDocument -> source.engineVersion

            is RuntimeCleanupSource.RestoredOwner -> source.engineVersion

            is RuntimeCleanupSource.PendingEngineReplace -> source.expectedPublishedEngineVersion

            is RuntimeCleanupSource.PendingInitialReplace,
            is RuntimeCleanupSource.ColdHandle,
            -> null
        }
        while (true) {
            val candidate = runtimeCleanupCandidateRef.value ?: return
            val candidateEngineVersion = when (val source = candidate.source) {
                is RuntimeCleanupSource.ActiveDocument -> source.engineVersion

                is RuntimeCleanupSource.RestoredOwner -> source.engineVersion

                is RuntimeCleanupSource.PendingEngineReplace -> source.expectedPublishedEngineVersion

                is RuntimeCleanupSource.PendingInitialReplace,
                is RuntimeCleanupSource.ColdHandle,
                -> null
            }
            val targetSourceMatches = when (val source = target.source) {
                is RuntimeCleanupSource.PendingInitialReplace ->
                    (candidate.source as? RuntimeCleanupSource.PendingInitialReplace)?.candidateToken == source.candidateToken

                is RuntimeCleanupSource.ActiveDocument,
                is RuntimeCleanupSource.RestoredOwner,
                is RuntimeCleanupSource.PendingEngineReplace,
                -> candidateEngineVersion == expectedEngineVersion

                is RuntimeCleanupSource.ColdHandle -> candidate.source == source
            }
            if (candidate.document != document || !targetSourceMatches) return
            if (runtimeCleanupCandidateRef.compareAndSet(candidate, null)) return
        }
    }

    internal fun retryPendingRuntimeCleanup(): Job? {
        val target = pendingRuntimeCleanupRef.value.firstOrNull() ?: return null
        return scope.launch {
            clearActiveWorkoutRuntime(target)
        }
    }

    internal fun pendingRuntimeCleanupReasonForTest(): RuntimeCleanupReason? = pendingRuntimeCleanupRef.value.firstOrNull()?.reason

    private fun captureCompletedNoLeasePresentation(): CompletedNoLeasePresentation? {
        if (executionGuard.currentLease != null ||
            restoredRuntimeOwnerRef.value != null ||
            activeRuntimeDocument != null ||
            runtimeCleanupCandidateRef.value != null ||
            pendingRuntimeCleanupRef.value.isNotEmpty()
        ) {
            return null
        }
        val loadedRoutine = coordinator._loadedRoutine.value ?: return null
        val flowState = coordinator._routineFlowState.value as? RoutineFlowState.Complete ?: return null
        return CompletedNoLeasePresentation(
            loadedRoutine = loadedRoutine,
            routineId = coordinator.currentRoutineId,
            routineSessionId = coordinator.currentRoutineSessionId,
            workoutState = coordinator._workoutState.value,
            routineFlowState = flowState,
        )
    }

    private fun exitCompletedNoLeasePresentationIfCurrent(
        presentation: CompletedNoLeasePresentation,
    ): Boolean = executionGuard.mutateConfigurationInputsIf(
        candidateStillCurrent = {
            executionGuard.currentLease == null &&
                restoredRuntimeOwnerRef.value == null &&
                activeRuntimeDocument == null &&
                runtimeCleanupCandidateRef.value == null &&
                pendingRuntimeCleanupRef.value.isEmpty() &&
                coordinator._loadedRoutine.value === presentation.loadedRoutine &&
                coordinator.currentRoutineId == presentation.routineId &&
                coordinator.currentRoutineSessionId == presentation.routineSessionId &&
                coordinator._workoutState.value == presentation.workoutState &&
                coordinator._routineFlowState.value == presentation.routineFlowState
        },
    ) {
        executionGuard.supersedeRecoveryPublication()
        executionGuard.supersedeQueuedSuccessors()
        supersedePendingResetStart()
        detachEndedRoutineFromCoordinator()
        coordinator._workoutState.value = WorkoutState.Idle
        coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
    }

    fun stopWorkout(exitingWorkout: Boolean = false) {
        val cleanupCandidateAtInvocation = if (exitingWorkout) runtimeCleanupCandidateRef.value else null
        val restoredOwnerAtInvocation = restoredRuntimeOwnerRef.value
        val pendingNoLeaseCleanupAtInvocation = if (
            exitingWorkout &&
            executionGuard.currentLease == null &&
            restoredOwnerAtInvocation == null
        ) {
            cleanupCandidateAtInvocation?.let { candidate ->
                pendingRuntimeCleanupRef.value.firstOrNull { target ->
                    runtimeCleanupTargetMatchesCandidate(target, candidate)
                }
            }
        } else {
            null
        }
        val completedNoLeasePresentationAtInvocation = if (
            exitingWorkout &&
            executionGuard.currentLease == null &&
            restoredOwnerAtInvocation == null &&
            pendingNoLeaseCleanupAtInvocation == null
        ) {
            captureCompletedNoLeasePresentation()
        } else {
            null
        }
        if (executionGuard.currentLease == null && restoredOwnerAtInvocation != null) {
            if (!exitingWorkout) return
            if (!coordinator.stopWorkoutInProgress.compareAndSet(expect = false, update = true)) return
            beginRestoredRuntimeTerminalCleanup(
                owner = restoredOwnerAtInvocation,
                reason = RuntimeCleanupReason.END_WORKOUT,
            )
            return
        }
        if (pendingNoLeaseCleanupAtInvocation != null) {
            if (!coordinator.stopWorkoutInProgress.compareAndSet(expect = false, update = true)) return
            ownStopWorkoutCleanupTarget(pendingNoLeaseCleanupAtInvocation)
            executionGuard.supersedeRecoveryPublication()
            executionGuard.supersedeQueuedSuccessors()
            supersedePendingResetStart()
            exitRuntimeCleanupPresentationIfCurrent(
                target = pendingNoLeaseCleanupAtInvocation,
                requireDifferentProfile = false,
            )
            launchRuntimeCleanup(pendingNoLeaseCleanupAtInvocation)
            return
        }
        if (completedNoLeasePresentationAtInvocation != null) {
            if (!coordinator.stopWorkoutInProgress.compareAndSet(expect = false, update = true)) return
            try {
                exitCompletedNoLeasePresentationIfCurrent(completedNoLeasePresentationAtInvocation)
            } finally {
                coordinator.stopWorkoutInProgress.value = false
            }
            return
        }
        executionGuard.supersedeRecoveryPublication()
        executionGuard.supersedeQueuedSuccessors()
        supersedePendingResetStart()
        // C1: Atomic compareAndSet prevents TOCTOU race — only the first caller proceeds
        if (!coordinator.stopWorkoutInProgress.compareAndSet(expect = false, update = true)) return
        val cleanupTarget = cleanupCandidateAtInvocation?.let { cleanupCandidate ->
            val candidate = captureRuntimeCleanupTarget(
                cleanupCandidate,
                RuntimeCleanupReason.END_WORKOUT,
            )
            installRuntimeCleanupIntent(candidate)
                ?: pendingRuntimeCleanupRef.value.firstOrNull { existing ->
                    sameRuntimeCleanupTargetIdentity(existing, candidate)
                }
        }?.also(::ownStopWorkoutCleanupTarget)

        val lease = executionGuard.currentLease
        val requestedCompletion = lease?.let { currentLease ->
            bodyweightCompletionGate.pendingFor(currentLease)
                ?: completionFor(currentLease, SetEndReason.USER_STOPPED)
        }
        val completion = when (val claim = requestedCompletion?.let(executionGuard::claimCompletion)) {
            is CompletionClaimResult.Claimed -> claim.completion.also { claimed ->
                afterCompletionClaim(claimed.lease.executionId, claimed.lease.sessionId, claimed.reason)
            }

            is CompletionClaimResult.AlreadyClaimed -> claim.completion

            CompletionClaimResult.Rejected -> {
                if (cleanupTarget != null) {
                    launchRuntimeCleanup(cleanupTarget)
                } else {
                    coordinator.stopWorkoutInProgress.value = false
                }
                return
            }

            null -> null
        }
        lease?.let(bodyweightCompletionGate::invalidate)
        lease?.let(::clearDangerZoneCountdownOverride)
        if (exitingWorkout && completion != null) {
            val snapshot = captureExitSnapshot(completion, TerminalPath.END_WORKOUT)
            executionGuard.invalidateCurrent(ExecutionInvalidationReason.END_WORKOUT)
            cancelSetOwnedPresentationJobs(completion.lease)
            detachEndedRoutineFromCoordinator()
            coordinator._workoutState.value = WorkoutState.Idle
            coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
            launchSnapshotPersistence(snapshot)
            cleanupTarget?.let(::launchRuntimeCleanup)
            beginMachineTeardown(completion.lease, TeardownReason.END_WORKOUT)
            return
        }

        val manualSnapshot = completion?.let { captureExitSnapshot(it, TerminalPath.MANUAL_STOP) }
        manualSnapshot?.let(::launchSnapshotPersistence)
        val shouldExitToIdle = exitingWorkout
        coordinator._weightAdjustmentRecommendation.value = null

        coordinator.workoutJob?.cancel()
        coordinator.workoutJob = null

        coordinator.bodyweightTimerJob?.cancel()
        coordinator.bodyweightTimerJob = null
        coordinator._timedExerciseRemainingSeconds.value = null

        coordinator.restTimerJob?.cancel()
        coordinator.restTimerJob = null
        coordinator.restDeadlineElapsedRealtimeMs = null
        cancelJustLiftEggTimer()

        val completeStop: () -> Unit = {
            launchPresentationContinuation(lease) {
                if (manualSnapshot != null) {
                    if (!hasCurrentAuthority(manualSnapshot.lease, "manual_stop_continuation")) {
                        return@launchPresentationContinuation
                    }
                    coordinator.isCurrentWorkoutTimed = false
                    coordinator.isCurrentTimedCableExercise = false
                    coordinator._isCurrentExerciseBodyweight.value = false
                    if (!hasCurrentAuthority(manualSnapshot.lease, "manual_stop_haptic")) {
                        return@launchPresentationContinuation
                    }
                    coordinator._hapticEvents.emit(HapticEvent.WORKOUT_END)
                    if (!hasCurrentAuthority(manualSnapshot.lease, "manual_stop_after_haptic")) {
                        return@launchPresentationContinuation
                    }
                    coordinator.setRepMetrics.value = emptyList()
                    resetBiomechanicsContext(manualSnapshot.lease)
                    coordinator.repQualityScorer.reset()
                    coordinator._latestRepQuality.value = null
                    coordinator.repBoundaryTimestamps.value = emptyList()

                    if (manualSnapshot.postSaveInput.isJustLift) {
                        Logger.d("Just Lift: Restarting monitor polling to clear machine fault state")
                        bleRepository.restartMonitorPolling()
                        saveJustLiftDefaultsFromWorkout()
                        coordinator._workoutParameters.update { params ->
                            params.copy(selectedExerciseId = null)
                        }
                    }

                    if (!hasCurrentAuthority(manualSnapshot.lease, "manual_stop_summary")) {
                        return@launchPresentationContinuation
                    }
                    coordinator._workoutState.value = manualSnapshot.presentationSummary
                    return@launchPresentationContinuation
                }

                coordinator.isCurrentWorkoutTimed = false
                coordinator.isCurrentTimedCableExercise = false
                coordinator._isCurrentExerciseBodyweight.value = false

                val currentExercise = coordinator._loadedRoutine.value?.exercises?.getOrNull(coordinator._currentExerciseIndex.value)
                val legacyContext = lease?.let { stoppedLease ->
                    executionContext?.takeIf {
                        it.lease.executionId == stoppedLease.executionId &&
                            it.lease.sessionId == stoppedLease.sessionId
                    }
                }
                val legacyAttemptNumber = legacyContext?.completionFacts?.attemptNumber ?: 1
                Logger.d("ActiveSessionEngine") { "Manual stop continuation: exitingWorkout=$shouldExitToIdle" }
                coordinator._hapticEvents.emit(HapticEvent.WORKOUT_END)

                val repCount = coordinator._repCount.value
                val isJustLift = coordinator._workoutParameters.value.isJustLift

                if (isJustLift) {
                    Logger.d("Just Lift: Restarting monitor polling to clear machine fault state")
                    bleRepository.restartMonitorPolling()
                }

                // Re-read params after stop-time state updates.
                val params = coordinator._workoutParameters.value
                val legacyLogicalSetKey = legacyContext?.completionFacts?.routineIdentity?.logicalSetKey
                    ?: coordinator.currentRoutineSessionId?.let { routineSessionId ->
                        currentExercise?.let { exercise ->
                            LogicalSetKey(
                                routineSessionId = routineSessionId,
                                routineExerciseId = exercise.id,
                                setIndex = coordinator._currentSetIndex.value,
                                setKind = if (params.isAMRAP) SetType.AMRAP else SetType.STANDARD,
                            )
                        }
                    }

                val selectedExercise = resolveSelectedExercise(params)
                val exerciseName = selectedExercise?.name

                val metrics = coordinator.collectedMetrics.value
                Logger.i { "WEIGHT_DEBUG[Session]: At set completion - params.weightPerCableKg=${params.weightPerCableKg} kg" }
                val summary = calculateSetSummaryMetrics(
                    metrics = metrics,
                    repCount = repCount.totalReps,
                    fallbackWeightKg = params.weightPerCableKg,
                    configuredWeightKgPerCable = params.weightPerCableKg,
                    isEchoMode = params.isEchoMode,
                    warmupRepsCount = repCount.warmupReps,
                    workingRepsCount = repCount.workingReps,
                    warmupCompleteTimeMs = coordinator.warmupCompleteTimeMs,
                    cableCountHint = selectedExercise?.preferredCableCount,
                    displayMultiplierHint = selectedExercise?.displayMultiplier,
                ).let { baseSummary ->
                    // Issue #229: Override volume for bodyweight exercises
                    val bodyWeightKg = resolvedSessionBodyWeightKg()
                    applyBodyweightVolume(baseSummary, currentExercise, bodyWeightKg)
                }
                val rackAdjustment = coordinator._currentRackLoadAdjustment.value

                // Issue #252: Exclude warmup time from session duration
                val effectiveStart = if (coordinator.warmupCompleteTimeMs > 0L) coordinator.warmupCompleteTimeMs else coordinator.workoutStartTime
                // Capture biomechanics summary before building session (mirrors saveWorkoutSession() pattern;
                // biomechanicsEngine.reset() is not called on this path before this point).
                val bioSummary = coordinator.biomechanicsEngine.getSetSummary()
                val session = WorkoutSession(
                    timestamp = coordinator.workoutStartTime,
                    mode = params.programMode.displayName,
                    reps = params.reps,
                    weightPerCableKg = params.weightPerCableKg,
                    totalReps = repCount.totalReps,
                    workingReps = repCount.workingReps,
                    warmupReps = repCount.warmupReps,
                    duration = currentTimeMillis() - effectiveStart,
                    isJustLift = isJustLift,
                    exerciseId = params.selectedExerciseId,
                    exerciseName = exerciseName,
                    routineSessionId = legacyLogicalSetKey?.routineSessionId ?: coordinator.currentRoutineSessionId,
                    routineName = coordinator.currentRoutineName,
                    routineId = coordinator.currentRoutineId,
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
                    cableCount = summary.cableCount,
                    displayMultiplier = summary.displayMultiplier,
                    externalAddedLoadKg = rackAdjustment.externalAddedLoadKg,
                    counterweightKg = rackAdjustment.counterweightKg,
                    rackItemsJson = coordinator.currentRackItemsJson,
                    estimatedCalories = summary.estimatedCalories,
                    warmupAvgWeightKg = if (params.isEchoMode) summary.warmupAvgWeightKg else null,
                    workingAvgWeightKg = if (params.isEchoMode) summary.workingAvgWeightKg else null,
                    burnoutAvgWeightKg = if (params.isEchoMode) summary.burnoutAvgWeightKg else null,
                    peakWeightKg = if (params.isEchoMode) summary.peakWeightKg else null,
                    rpe = coordinator._currentSetRpe.value,
                    avgMcvMmS = bioSummary?.avgMcvMmS,
                    avgAsymmetryPercent = bioSummary?.avgAsymmetryPercent,
                    totalVelocityLossPercent = bioSummary?.totalVelocityLossPercent,
                    dominantSide = bioSummary?.dominantSide,
                    strengthProfile = bioSummary?.strengthProfile?.name,
                    // C4: profileId was missing from manual-stop path — matches saveWorkoutSession() pattern
                    profileId = userProfileRepository.activeProfile.value?.id ?: "default",
                )
                workoutRepository.saveSession(session)
                val isRoutineSet = session.routineSessionId != null
                if (isRoutineSet && isValidCompletedSession(session)) {
                    session.estimatedCalories?.let { cal ->
                        if (cal > 0f) coordinator.routineAccumulatedCalories += cal
                    }
                    coordinator._completedRoutineSetKeys.update {
                        it + (coordinator._currentExerciseIndex.value to coordinator._currentSetIndex.value)
                    }
                }
                val persistedSummary = summary.copy(
                    sessionId = session.id,
                    taggedExerciseId = params.selectedExerciseId,
                    taggedExerciseName = exerciseName,
                    isAmrap = params.isAMRAP,
                )

                var completedSetId: String? = null
                if (params.selectedExerciseId != null && repCount.workingReps > 0) {
                    val setIndex = legacyLogicalSetKey?.setIndex ?: coordinator._currentSetIndex.value
                    val setId = generateUUID()
                    completedSetId = setId
                    val matchedPlannedSetId = findPlannedSetId(setIndex)
                    val completedSet = CompletedSet(
                        id = setId,
                        sessionId = session.id,
                        plannedSetId = matchedPlannedSetId,
                        setNumber = setIndex,
                        setType = legacyLogicalSetKey?.setKind ?: if (params.isAMRAP) SetType.AMRAP else SetType.STANDARD,
                        actualReps = repCount.workingReps,
                        actualWeightKg = params.weightPerCableKg,
                        loggedRpe = coordinator._currentSetRpe.value,
                        isPr = false,
                        completedAt = currentTimeMillis(),
                        setEndReason = SetEndReason.USER_STOPPED,
                        routineExerciseId = legacyLogicalSetKey?.routineExerciseId,
                        attemptNumber = legacyAttemptNumber,
                    )
                    completedSetRepository.saveCompletedSet(completedSet)
                    Logger.d("Saved CompletedSet (manual stop): set #$setIndex, ${repCount.workingReps} reps${if (matchedPlannedSetId != null) " (linked to PlannedSet)" else ""}")
                }

                val hasPR = gamificationManager.processPostSaveEvents(
                    exerciseId = params.selectedExerciseId,
                    workingReps = repCount.workingReps,
                    achievedWeightKg = summary.heaviestLiftKgPerCable,
                    volumeWeightKg = params.weightPerCableKg,
                    programMode = params.programMode,
                    isJustLift = isJustLift,
                    isEchoMode = params.isEchoMode,
                    peakConcentricForceKg = maxOf(summary.peakForceConcentricA, summary.peakForceConcentricB),
                    peakEccentricForceKg = maxOf(summary.peakForceEccentricA, summary.peakForceEccentricB),
                    profileId = userProfileRepository.activeProfile.value?.id ?: "default",
                    sessionMcvMmS = session.avgMcvMmS,
                )

                // Reset biomechanics engine after manual-stop — mirrors handleSetCompletion (~line 3821).
                // Safe: processPostSaveEvents() is the last consumer of bioSummary/session.avgMcvMmS;
                // nothing below this point reads bioSummary or calls getSetSummary().
                lease?.let(::resetBiomechanicsContext)

                if (hasPR && completedSetId != null) {
                    completedSetRepository.markAsPr(completedSetId)
                    Logger.d("Marked CompletedSet $completedSetId as PR (manual stop)")
                }

                if (!isRoutineSet) {
                    enqueueWorkoutHealthPush(session)
                }

                scope.launch {
                    syncTriggerManager?.onWorkoutCompleted()
                }

                if (isJustLift) {
                    saveJustLiftDefaultsFromWorkout()
                    coordinator._workoutParameters.update { p ->
                        p.copy(selectedExerciseId = null)
                    }
                } else if (isSingleExerciseMode(coordinator)) {
                    saveSingleExerciseDefaultsFromWorkout()
                }

                if (shouldExitToIdle) {
                    coordinator._workoutState.value = WorkoutState.Idle
                    coordinator._routineFlowState.value = RoutineFlowState.NotInRoutine
                    coordinator._loadedRoutine.value = null
                    coordinator.routineStartTime = 0
                    // Issue #392: Clear routine session context on exit
                    coordinator.currentRoutineSessionId = null
                    coordinator.currentRoutineName = null
                    coordinator.currentRoutineId = null
                    coordinator.routineAccumulatedCalories = 0f
                    coordinator._completedRoutineSetKeys.value = emptySet()
                    // Safe to clear origin here: every call site (e.g. ActiveWorkoutScreen) reads
                    // routineExitDestination() BEFORE invoking stopWorkout(exitingWorkout=true), so
                    // the navigation decision is already captured before this async block runs.
                    // Clearing prevents a stale TRAINING_CYCLES origin from bleeding into the next
                    // session when the user subsequently enters via DailyRoutinesScreen.
                    coordinator.routineLaunchOrigin = null
                } else {
                    coordinator._workoutState.value = persistedSummary
                }
            }
        }

        if (lease == null) {
            completeStop()
        } else if (shouldExitToIdle) {
            beginMachineTeardown(lease, TeardownReason.END_WORKOUT)
            executionGuard.invalidate(lease, ExecutionInvalidationReason.END_WORKOUT)
            repFreshnessGate.invalidate(lease)
            completeStop()
        } else {
            beginMachineTeardown(lease, TeardownReason.MANUAL_STOP, afterReady = completeStop)
        }
    }

    fun stopAndReturnToSetReady() {
        // C1: Atomic compareAndSet prevents TOCTOU race
        if (!coordinator.stopWorkoutInProgress.compareAndSet(expect = false, update = true)) return

        // Issue #320: When user has completed working reps in a real routine AND the set is still
        // Active, save partial reps and advance to next set instead of discarding.
        // The Active check is critical: _repCount isn't cleared until the next set starts,
        // so pressing Back during SetSummary/Resting would also match workingReps > 0 and
        // double-save the set. Only intercept when genuinely mid-set.
        // Excludes temp single-exercise routines (temp_single_*) — those should use the
        // original discard-and-retry behavior since there's no multi-set routine to advance.
        val isActiveSet = coordinator._workoutState.value is WorkoutState.Active
        val hasCompletedReps = coordinator._repCount.value.workingReps > 0
        val isRealRoutine = !isSingleExerciseMode(coordinator)

        if (isActiveSet && hasCompletedReps && isRealRoutine) {
            Logger.d { "stopAndReturnToSetReady: Issue #320 - workingReps=${coordinator._repCount.value.workingReps} > 0, routing through handleSetCompletion to save reps and advance" }
            // Release stop guard before delegating — handleSetCompletion owns its lease-scoped claim.
            coordinator.stopWorkoutInProgress.value = false
            executionGuard.currentLease?.let { lease ->
                handleSetCompletion(lease, SetEndReason.USER_STOPPED)
            }
            return
        }

        coordinator.workoutJob?.cancel()
        coordinator.workoutJob = null
        coordinator.restTimerJob?.cancel()
        coordinator.restTimerJob = null
        coordinator.restDeadlineElapsedRealtimeMs = null
        coordinator.bodyweightTimerJob?.cancel()
        coordinator.bodyweightTimerJob = null
        coordinator._timedExerciseRemainingSeconds.value = null
        cancelJustLiftEggTimer()

        val lease = executionGuard.currentLease
        lease?.let(bodyweightCompletionGate::invalidate)
        lease?.let(::clearDangerZoneCountdownOverride)
        // Leave this armed after a successful Stop Set so Idle routing cannot race the
        // SetReady observer; startWorkout() releases it when the user retries the set.
        var returnedToSetReady = false
        val returnToSetReady: () -> Unit = {
            launchPresentationContinuation(lease) {
                try {
                    if (lease != null && !hasCurrentAuthority(lease, "stop_set_continuation")) {
                        return@launchPresentationContinuation
                    }
                    repCounter.reset()
                    coordinator._repCount.value = RepCount()
                    coordinator._repRanges.value = null
                    coordinator.warmupCompleteTimeMs = 0
                    resetAutoStopState()

                    val routine = coordinator._loadedRoutine.value
                    if (routine != null) {
                        if (lease != null && !hasCurrentAuthority(lease, "stop_set_navigation")) {
                            return@launchPresentationContinuation
                        }
                        // Issue #660: publish SetReady before Idle so its observer owns navigation.
                        flowDelegate?.enterSetReady(coordinator._currentExerciseIndex.value, coordinator._currentSetIndex.value)
                    }
                    if (lease != null && !hasCurrentAuthority(lease, "stop_set_idle")) {
                        return@launchPresentationContinuation
                    }
                    coordinator._workoutState.value = WorkoutState.Idle
                    returnedToSetReady = true

                    if (lease != null && executionGuard.invalidate(lease, ExecutionInvalidationReason.STOP_SET)) {
                        repFreshnessGate.invalidate(lease)
                    }

                    Logger.d { "stopAndReturnToSetReady: Reset to SetReady for exercise=${coordinator._currentExerciseIndex.value}, set=${coordinator._currentSetIndex.value}" }
                } finally {
                    if (!returnedToSetReady) {
                        if (lease == null || executionGuard.isCurrent(lease)) {
                            coordinator.stopWorkoutInProgress.value = false
                        }
                    }
                }
            }
        }
        if (lease == null) {
            returnToSetReady()
        } else {
            beginMachineTeardown(lease, TeardownReason.STOP_SET, afterReady = returnToSetReady)
        }
    }

    fun stopAndSkipCurrentExercise() {
        // C1: Atomic compareAndSet prevents TOCTOU race
        if (!coordinator.stopWorkoutInProgress.compareAndSet(expect = false, update = true)) return
        val lease = executionGuard.currentLease
        lease?.let(bodyweightCompletionGate::invalidate)
        lease?.let(::clearDangerZoneCountdownOverride)

        val skippedExerciseIndex = coordinator._currentExerciseIndex.value
        val skippedSetIndex = coordinator._currentSetIndex.value

        coordinator.workoutJob?.cancel()
        coordinator.workoutJob = null
        coordinator.restTimerJob?.cancel()
        coordinator.restTimerJob = null
        coordinator.restDeadlineElapsedRealtimeMs = null
        coordinator.bodyweightTimerJob?.cancel()
        coordinator.bodyweightTimerJob = null
        coordinator._timedExerciseRemainingSeconds.value = null
        cancelJustLiftEggTimer()

        val skipExercise: () -> Unit = {
            launchPresentationContinuation(lease) {
                var completed = false
                try {
                    if (lease != null && !hasCurrentAuthority(lease, "skip_exercise_continuation")) {
                        return@launchPresentationContinuation
                    }
                    repCounter.reset()
                    coordinator._repCount.value = RepCount()
                    coordinator._repRanges.value = null
                    coordinator.warmupCompleteTimeMs = 0
                    resetAutoStopState()
                    coordinator._workoutState.value = WorkoutState.Idle

                    val routine = coordinator._loadedRoutine.value
                    if (routine != null) {
                        if (lease != null && !hasCurrentAuthority(lease, "skip_exercise_navigation")) {
                            return@launchPresentationContinuation
                        }
                        val movedToNextStep = flowDelegate?.skipCurrentExerciseAndEnterNextStep() == true
                        if (lease != null && !hasCurrentAuthority(lease, "skip_exercise_after_navigation")) {
                            return@launchPresentationContinuation
                        }
                        if (!movedToNextStep) {
                            // Issue #395: Write aggregate health workout before completing routine
                            writeRoutineHealthData()
                            autoBackupRoutineIfEnabled("skip-final-exercise")
                            flowDelegate?.showRoutineComplete()
                        }
                    }

                    coordinator.stopWorkoutInProgress.value = false
                    completed = true
                    if (lease != null && executionGuard.invalidate(lease, ExecutionInvalidationReason.SKIP_EXERCISE)) {
                        repFreshnessGate.invalidate(lease)
                    }

                    Logger.d {
                        "stopAndSkipCurrentExercise: Skipped exercise=$skippedExerciseIndex, set=$skippedSetIndex"
                    }
                } finally {
                    if (!completed && (lease == null || executionGuard.isCurrent(lease))) {
                        coordinator.stopWorkoutInProgress.value = false
                    }
                }
            }
        }
        if (lease == null) {
            skipExercise()
        } else {
            beginMachineTeardown(lease, TeardownReason.SKIP_EXERCISE, afterReady = skipExercise)
        }
    }

    fun pauseWorkout() {
        if (coordinator._workoutState.value is WorkoutState.Active) {
            // Intentionally preserved pause/resume non-exit RESET exception: pause is not
            // a terminal transition, and changing its continuation protocol is out of scope.
            // Send BLE stop BEFORE cancelling collection jobs.
            // The machine must be stopped first; cancelling collection without stopping
            // leaves the machine running unmonitored (project memory: mid-set BLE faults).
            scope.launch {
                try {
                    bleRepository.stopWorkout()
                    Logger.d { "ActiveSessionEngine: BLE stop sent before pause" }
                } catch (e: Exception) {
                    Logger.e(e) { "ActiveSessionEngine: Failed to send BLE stop during pause: ${e.message}" }
                }
            }

            coordinator.monitorDataCollectionJob?.cancel()
            coordinator.repEventsCollectionJob?.cancel()

            coordinator._workoutState.value = WorkoutState.Paused
            Logger.d { "ActiveSessionEngine: Workout paused, collection jobs cancelled" }
        }
    }

    fun resumeWorkout() {
        // #627 + PR-review: refuse resume while a stop teardown is in flight — the workout
        // is ending; resuming a dying session would also reopen the CAS guard and permit a
        // concurrent duplicate teardown. The flag itself is reset only by the next
        // startWorkout() (~line 2352); after teardown the state is Idle/SetSummary anyway,
        // so the `is Paused` check below would refuse the resume even without this guard —
        // this early return exists for the in-flight window while state is still Paused.
        if (coordinator.stopWorkoutInProgress.value) return
        if (coordinator._workoutState.value is WorkoutState.Paused) {
            coordinator._workoutState.value = WorkoutState.Active

            restartCollectionJobs()
            Logger.d { "ActiveSessionEngine: Workout resumed, collection jobs restarted" }
        }
    }

    private fun restartCollectionJobs() {
        coordinator.monitorDataCollectionJob = scope.launch {
            Logger.d("ActiveSessionEngine") { "Restarting global metricsFlow collection after resume..." }
            bleRepository.metricsFlow
                .catch { e -> Logger.e(e) { "metricsFlow collector error (restart)" } }
                .collect { metric ->
                    coordinator._currentMetric.value = metric
                    handleMonitorMetric(metric)
                }
        }

        coordinator.repEventsCollectionJob = scope.launch {
            bleRepository.repEvents
                .catch { e -> Logger.e(e) { "repEvents collector error (restart)" } }
                .collect(::acceptRepNotification)
        }
    }

    // ===== Session Persistence =====

    /**
     * Look up the PlannedSet ID for the current routine exercise and set index.
     */
    private suspend fun findPlannedSetId(setIndex: Int): String? {
        val routineExercise = flowDelegate?.getCurrentExercise() ?: return null
        val plannedSets = completedSetRepository.getPlannedSets(routineExercise.id)
        return plannedSets.find { it.setNumber == setIndex }?.id
    }

    private fun isValidCompletedSession(session: WorkoutSession): Boolean = session.workingReps > 0 && session.duration > 0L

    private fun healthProviderForPlatform(): IntegrationProvider = if (getPlatform().name.startsWith("iOS")) {
        IntegrationProvider.APPLE_HEALTH
    } else {
        IntegrationProvider.GOOGLE_HEALTH
    }

    private fun enqueueWorkoutHealthPush(session: WorkoutSession) {
        val healthIntegration = healthIntegration ?: return
        val externalActivityRepository = externalActivityRepository ?: return

        if (!isValidCompletedSession(session)) {
            Logger.i("ActiveSessionEngine") {
                "HEALTH_DEBUG_PUSH: skipped invalid workout sessionId=${session.id}, " +
                    "exerciseId=${session.exerciseId ?: "NULL"}, workingReps=${session.workingReps}, durationMs=${session.duration}"
            }
            return
        }

        val profileId = session.profileId
        scope.launch {
            try {
                val provider = healthProviderForPlatform()
                val status = externalActivityRepository.getIntegrationStatus(provider, profileId).first()
                Logger.i("ActiveSessionEngine") {
                    "HEALTH_DEBUG_PUSH: provider=${provider.key}, status=${status?.status}, " +
                        "sessionId=${session.id}, exercise=${session.exerciseName ?: "NULL"}, " +
                        "weightPerCableKg=${session.weightPerCableKg}, cableCount=${session.cableCount ?: -1}, " +
                        "estimatedCalories=${session.estimatedCalories ?: -1f}"
                }
                if (status?.status != ConnectionStatus.CONNECTED) {
                    Logger.i("ActiveSessionEngine") {
                        "Health auto-push skipped: ${provider.displayName} is not connected (status=${status?.status})"
                    }
                    return@launch
                }

                val completedSets = completedSetRepository.getCompletedSets(session.id)
                val data = HealthWorkoutExportBuilder.buildStandaloneWorkout(
                    session = session,
                    completedSets = completedSets,
                )
                if (data == null) {
                    Logger.i("ActiveSessionEngine") {
                        "Health auto-push skipped: no exportable segments for sessionId=${session.id}"
                    }
                    return@launch
                }
                val cursorRepository = healthExportCursorRepository
                if (cursorRepository != null && HealthExportMarkers.isExported(cursorRepository, provider, profileId, data.externalId)) {
                    Logger.i("ActiveSessionEngine") {
                        "Health auto-push skipped: ${data.externalId} is already marked exported"
                    }
                    return@launch
                }

                healthIntegration.writeHealthWorkout(data)
                    .onSuccess {
                        if (cursorRepository != null) {
                            HealthExportMarkers.markExported(cursorRepository, provider, profileId, data.externalId)
                        }
                        Logger.i("ActiveSessionEngine") { "Auto-pushed workout to ${provider.displayName}: sessionId=${session.id}" }
                    }
                    .onFailure { e ->
                        Logger.w("ActiveSessionEngine") { "Health auto-push failed (non-fatal): ${e.message}" }
                    }
            } catch (e: Exception) {
                Logger.w("ActiveSessionEngine") { "Health auto-push failed (non-fatal): ${e.message}" }
            }
        }
    }

    /**
     * Issue #395: Write a single aggregate workout to the health platform
     * for a completed routine. Fire-and-forget; failure is non-fatal.
     *
     * Must be called BEFORE coordinator.routineStartTime/currentRoutineSessionId are reset.
     */
    internal fun writeRoutineHealthData() {
        val routineSessionId = coordinator.currentRoutineSessionId ?: return
        val startTime = coordinator.routineStartTime
        if (startTime <= 0L) return
        val healthIntegration = healthIntegration ?: return
        val externalActivityRepository = externalActivityRepository ?: return

        val routineName = coordinator.currentRoutineName ?: "Phoenix Routine"
        val durationMs = currentTimeMillis() - startTime
        val totalCalories = coordinator.routineAccumulatedCalories.takeIf { it > 0f }
        val profileId = userProfileRepository.activeProfile.value?.id ?: "default"
        val skippedIndices = coordinator._skippedExercises.value
        val completedSetKeys = coordinator._completedRoutineSetKeys.value.filterNot { it.first in skippedIndices }
        val completedExerciseCount = (
            (coordinator._completedExercises.value - skippedIndices) +
                completedSetKeys.map { it.first }.toSet()
            ).size

        if (completedExerciseCount <= 0 || completedSetKeys.isEmpty()) {
            Logger.i("ActiveSessionEngine") {
                "Routine health push skipped: no valid completed routine sets " +
                    "(routineSessionId=$routineSessionId, completedExercises=$completedExerciseCount, completedSets=${completedSetKeys.size})"
            }
            coordinator.routineAccumulatedCalories = 0f
            return
        }

        scope.launch {
            try {
                val provider = healthProviderForPlatform()
                val status = externalActivityRepository.getIntegrationStatus(provider, profileId).first()
                Logger.i("ActiveSessionEngine") {
                    "HEALTH_DEBUG_ROUTINE_PUSH: provider=${provider.key}, status=${status?.status}, " +
                        "routineSessionId=$routineSessionId, completedExercises=$completedExerciseCount, " +
                        "completedSets=${completedSetKeys.size}, skippedExercises=${skippedIndices.size}, calories=${totalCalories ?: -1f}"
                }
                if (status?.status != ConnectionStatus.CONNECTED) {
                    Logger.i("ActiveSessionEngine") {
                        "Routine health push skipped: ${provider.displayName} is not connected (status=${status?.status})"
                    }
                    return@launch
                }

                val sessions = workoutRepository.getSessionsForRoutineSession(
                    profileId = profileId,
                    routineSessionId = routineSessionId,
                )
                val completedSetsBySessionId = completedSetRepository
                    .getCompletedSetsForSessions(sessions.map { it.id })
                    .groupBy { it.sessionId }
                val data = HealthWorkoutExportBuilder.buildRoutineWorkout(
                    routineSessionId = routineSessionId,
                    sessions = sessions,
                    completedSetsBySessionId = completedSetsBySessionId,
                )
                if (data == null) {
                    Logger.i("ActiveSessionEngine") {
                        "Routine health push skipped: no persisted exportable segments for routineSessionId=$routineSessionId"
                    }
                    return@launch
                }
                val cursorRepository = healthExportCursorRepository
                if (cursorRepository != null && HealthExportMarkers.isExported(cursorRepository, provider, profileId, data.externalId)) {
                    Logger.i("ActiveSessionEngine") {
                        "Routine health push skipped: ${data.externalId} is already marked exported"
                    }
                    return@launch
                }

                healthIntegration.writeHealthWorkout(data)
                    .onSuccess {
                        if (cursorRepository != null) {
                            HealthExportMarkers.markExported(cursorRepository, provider, profileId, data.externalId)
                        }
                        Logger.i("ActiveSessionEngine") {
                            "Auto-pushed routine workout to ${provider.displayName}: $routineName (${durationMs / 1000}s, segments=${data.segments.size})"
                        }
                    }
                    .onFailure { e ->
                        Logger.w("ActiveSessionEngine") { "Routine health push failed (non-fatal): ${e.message}" }
                    }
            } catch (e: Exception) {
                Logger.w("ActiveSessionEngine") { "Routine health push failed (non-fatal): ${e.message}" }
            } finally {
                coordinator.routineAccumulatedCalories = 0f
            }
        }
    }

    /**
     * Save workout session to database and check for personal records.
     */
    private suspend fun saveWorkoutSession(completion: SetExecutionCompletion) {
        val completionContext = executionContext?.takeIf {
            it.lease.executionId == completion.lease.executionId &&
                it.lease.sessionId == completion.lease.sessionId
        }
        val sessionId = coordinator.currentSessionId
        if (sessionId == null) {
            Logger.e {
                "PR_TRACK: CRITICAL — saveWorkoutSession() aborted: currentSessionId is null! " +
                    "No session or PR will be saved. workingReps=${coordinator._repCount.value.workingReps}, " +
                    "exerciseId=${coordinator._workoutParameters.value.selectedExerciseId}"
            }
            return
        }
        val params = coordinator._workoutParameters.value
        val warmup = coordinator._repCount.value.warmupReps
        val working = coordinator._repCount.value.workingReps

        // Issue #319: Log rep counts and exercise context entering the save pipeline
        Logger.i {
            "PR_TRACK: saveWorkoutSession — sessionId=$sessionId, " +
                "workingReps=$working, warmupReps=$warmup, " +
                "exerciseId=${params.selectedExerciseId ?: "NULL"}, " +
                "weight=${params.weightPerCableKg}kg, mode=${params.programMode.displayName}, " +
                "isJustLift=${params.isJustLift}, isEcho=${params.isEchoMode}, " +
                "metricsCount=${coordinator.collectedMetrics.value.size}"
        }

        // Issue #252: Exclude warmup time from session duration
        val effectiveStart = if (coordinator.warmupCompleteTimeMs > 0L) coordinator.warmupCompleteTimeMs else coordinator.workoutStartTime
        val duration = currentTimeMillis() - effectiveStart

        val metricsSnapshot = coordinator.collectedMetrics.value

        val selectedExercise = resolveSelectedExercise(params)
        val exerciseName = selectedExercise?.name

        val currentExercise = coordinator._loadedRoutine.value?.exercises?.getOrNull(coordinator._currentExerciseIndex.value)
        val completionLogicalSetKey = completionContext?.completionFacts?.routineIdentity?.logicalSetKey
            ?: coordinator.currentRoutineSessionId?.let { routineSessionId ->
                currentExercise?.let { exercise ->
                    LogicalSetKey(
                        routineSessionId = routineSessionId,
                        routineExerciseId = exercise.id,
                        setIndex = coordinator._currentSetIndex.value,
                        setKind = if (params.isAMRAP) SetType.AMRAP else SetType.STANDARD,
                    )
                }
            }
        val bodyweightVariant = coordinator.bodyweightCompletionVariantOverride

        val summary = calculateSetSummaryMetrics(
            metrics = metricsSnapshot,
            repCount = working,
            fallbackWeightKg = params.weightPerCableKg,
            configuredWeightKgPerCable = params.weightPerCableKg,
            isEchoMode = params.isEchoMode,
            warmupRepsCount = warmup,
            workingRepsCount = working,
            warmupCompleteTimeMs = coordinator.warmupCompleteTimeMs,
            cableCountHint = selectedExercise?.preferredCableCount,
            displayMultiplierHint = selectedExercise?.displayMultiplier,
        ).let { baseSummary ->
            // Issue #229: Override volume for bodyweight exercises
            val bodyWeightKg = resolvedSessionBodyWeightKg()
            applyBodyweightVolume(baseSummary, currentExercise, bodyWeightKg, bodyweightVariant)
        }
        val savedWeightKg = if (isBodyweightExercise(currentExercise)) {
            summary.heaviestLiftKgPerCable.takeIf { it > 0f } ?: params.weightPerCableKg
        } else {
            params.weightPerCableKg
        }
        val rackAdjustment = coordinator._currentRackLoadAdjustment.value

        // Capture biomechanics summary for WorkoutSession fields.
        // Safe to call here: runs BEFORE biomechanicsEngine.reset() in handleSetCompletion.
        // getSetSummary() is read-only/idempotent.
        val bioSummary = coordinator.biomechanicsEngine.getSetSummary()

        val session = WorkoutSession(
            id = sessionId,
            timestamp = coordinator.workoutStartTime,
            mode = params.programMode.displayName,
            reps = params.reps,
            weightPerCableKg = params.weightPerCableKg,
            progressionKg = params.progressionRegressionKg,
            duration = duration,
            totalReps = working,
            warmupReps = warmup,
            workingReps = working,
            isJustLift = params.isJustLift,
            stopAtTop = params.stopAtTop,
            exerciseId = params.selectedExerciseId,
            exerciseName = exerciseName,
            routineSessionId = completionLogicalSetKey?.routineSessionId ?: coordinator.currentRoutineSessionId,
            routineName = coordinator.currentRoutineName,
            routineId = coordinator.currentRoutineId,
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
            cableCount = summary.cableCount,
            displayMultiplier = summary.displayMultiplier,
            externalAddedLoadKg = rackAdjustment.externalAddedLoadKg,
            counterweightKg = rackAdjustment.counterweightKg,
            rackItemsJson = coordinator.currentRackItemsJson,
            estimatedCalories = summary.estimatedCalories,
            warmupAvgWeightKg = if (params.isEchoMode) summary.warmupAvgWeightKg else null,
            workingAvgWeightKg = if (params.isEchoMode) summary.workingAvgWeightKg else null,
            burnoutAvgWeightKg = if (params.isEchoMode) summary.burnoutAvgWeightKg else null,
            peakWeightKg = if (params.isEchoMode) summary.peakWeightKg else null,
            rpe = coordinator._currentSetRpe.value,
            // Biomechanics summary (Phase 13 - captured for all tiers)
            avgMcvMmS = bioSummary?.avgMcvMmS,
            avgAsymmetryPercent = bioSummary?.avgAsymmetryPercent,
            totalVelocityLossPercent = bioSummary?.totalVelocityLossPercent,
            dominantSide = bioSummary?.dominantSide,
            strengthProfile = bioSummary?.strengthProfile?.name,
            profileId = userProfileRepository.activeProfile.value?.id ?: "default",
        )

        Logger.d("ActiveSessionEngine") {
            "HEALTH_DEBUG_SESSION: sessionId=${session.id}, " +
                "exercise=${session.exerciseName ?: "NULL"}, " +
                "routineSessionId=${session.routineSessionId ?: "NULL"}, " +
                "weightPerCableKg=${session.weightPerCableKg}, cableCount=${session.cableCount ?: -1}, " +
                "displayMultiplier=${session.displayMultiplier ?: -1}, " +
                "heaviestLiftKg=${session.heaviestLiftKg ?: -1f}, " +
                "totalVolumeKg=${session.totalVolumeKg ?: -1f}, " +
                "estimatedCalories=${session.estimatedCalories ?: -1f}, durationMs=${session.duration}, " +
                "totalReps=${session.totalReps}, metrics=${metricsSnapshot.size}"
        }

        workoutRepository.saveSession(session)

        // Issue #395: Accumulate calories for routine-level aggregate health write.
        // Only write per-set to health platform for non-routine (Just Lift) workouts.
        val isRoutineSet = session.routineSessionId != null
        if (isRoutineSet && isValidCompletedSession(session)) {
            session.estimatedCalories?.let { cal ->
                if (cal > 0f) coordinator.routineAccumulatedCalories += cal
            }
            coordinator._completedRoutineSetKeys.update {
                it + (coordinator._currentExerciseIndex.value to coordinator._currentSetIndex.value)
            }
        }

        if (metricsSnapshot.isNotEmpty()) {
            workoutRepository.saveMetrics(sessionId, metricsSnapshot)
        }

        Logger.d("Saved workout session: $sessionId with ${metricsSnapshot.size} metrics")

        var completedSetId: String? = null
        if (params.selectedExerciseId != null && working > 0) {
            val setIndex = completionLogicalSetKey?.setIndex ?: coordinator._currentSetIndex.value
            val setId = generateUUID()
            completedSetId = setId
            val matchedPlannedSetId = findPlannedSetId(setIndex)
            val completedSet = CompletedSet(
                id = setId,
                sessionId = sessionId,
                plannedSetId = matchedPlannedSetId,
                setNumber = setIndex,
                setType = completionLogicalSetKey?.setKind ?: if (params.isAMRAP) SetType.AMRAP else SetType.STANDARD,
                actualReps = working,
                actualWeightKg = savedWeightKg,
                loggedRpe = coordinator._currentSetRpe.value,
                isPr = false,
                completedAt = currentTimeMillis(),
                setEndReason = completion.reason,
                routineExerciseId = completionLogicalSetKey?.routineExerciseId,
                attemptNumber = completionContext?.completionFacts?.attemptNumber ?: 1,
            )
            completedSetRepository.saveCompletedSet(completedSet)
            Logger.d("Saved CompletedSet: set #$setIndex, $working reps @ ${savedWeightKg}kg${if (matchedPlannedSetId != null) " (linked to PlannedSet)" else ""}")
        }

        val hasPR = gamificationManager.processPostSaveEvents(
            exerciseId = params.selectedExerciseId,
            workingReps = working,
            achievedWeightKg = summary.heaviestLiftKgPerCable,
            volumeWeightKg = savedWeightKg,
            programMode = params.programMode,
            isJustLift = params.isJustLift,
            isEchoMode = params.isEchoMode,
            peakConcentricForceKg = maxOf(summary.peakForceConcentricA, summary.peakForceConcentricB),
            peakEccentricForceKg = maxOf(summary.peakForceEccentricA, summary.peakForceEccentricB),
            profileId = userProfileRepository.activeProfile.value?.id ?: "default",
            sessionMcvMmS = session.avgMcvMmS,
        )

        if (hasPR && completedSetId != null) {
            completedSetRepository.markAsPr(completedSetId)
            Logger.d("Marked CompletedSet $completedSetId as PR")
        }

        // Fire-and-forget health push after session, metrics, CompletedSet, and PR persistence.
        // Issue #395: Skip per-set writes for routine sets; aggregate is written at routine completion.
        if (!isRoutineSet) {
            enqueueWorkoutHealthPush(session)
        }

        // Per-session auto-backup AFTER all persistence (including CompletedSet and PR).
        // Fire-and-forget, never blocks the save flow.
        // Issue #525: skip per-set backup for routine sets — exportRoutine handles the
        // entire routine on routine exit. Preserves single-exercise / Just Lift auto-backup.
        if (!isRoutineSet && preferencesManager.preferencesFlow.value.autoBackupEnabled && dataBackupManager != null) {
            scope.launch {
                dataBackupManager.exportSession(sessionId)
                    .onFailure { e -> Logger.w(e) { "Auto-backup failed for session $sessionId" } }
            }
        }

        if (params.isJustLift) {
            saveJustLiftDefaultsFromWorkout()
        } else if (isSingleExerciseMode(coordinator)) {
            saveSingleExerciseDefaultsFromWorkout()
        }

        if (shouldUpdateCycleProgressAfterSavedSet()) {
            updateCycleProgressIfNeeded()
        }

        // Sync trigger after all local persistence, including 5/3/1 cycle
        // advancement, so the push cannot snapshot the old week sentinel.
        scope.launch {
            syncTriggerManager?.onWorkoutCompleted()
        }
    }

    // ===== Set Completion (cross-cutting) =====

    /**
     * Handle automatic set completion (when rep target is reached via auto-stop).
     * Phase A: Stop BLE, save session, emit haptics, show summary.
     * Phase B: Rest timer, navigation advancement (delegated back to DWSM via startRestTimer).
     */
    internal fun handleSetCompletion(
        lease: ExecutionLease,
        endReason: SetEndReason,
    ) {
        if (!executionGuard.isCurrent(lease)) return
        if (bodyweightCompletionGate.hasClaimedCompletion(lease)) return
        handleSetCompletion(completionFor(lease, endReason), TeardownReason.AUTO_COMPLETE)
    }

    private fun handleSetCompletion(
        completion: SetExecutionCompletion,
        reason: TeardownReason,
        completionClaimed: Boolean = false,
    ) {
        val lease = completion.lease
        if (!executionGuard.isCurrent(lease)) return
        // Lease-scoped claim prevents both duplicate completion and stale execution ownership.
        if (!completionClaimed) {
            when (executionGuard.claimCompletion(completion)) {
                is CompletionClaimResult.Claimed ->
                    afterCompletionClaim(lease.executionId, lease.sessionId, completion.reason)

                is CompletionClaimResult.AlreadyClaimed,
                CompletionClaimResult.Rejected,
                -> {
                    Logger.d("handleSetCompletion: already in progress - ignoring")
                    return
                }
            }
        } else if (executionGuard.claimedCompletion(lease) != completion) {
            Logger.d("handleSetCompletion: claimed completion changed - ignoring")
            return
        }

        // Issue #319: Log full context at entry so we can diagnose what the pipeline receives
        val repCount = coordinator._repCount.value
        val entryParams = coordinator._workoutParameters.value
        Logger.i {
            "PR_TRACK: handleSetCompletion — workingReps=${repCount.workingReps}, " +
                "warmupReps=${repCount.warmupReps}, " +
                "exerciseId=${entryParams.selectedExerciseId ?: "NULL"}, " +
                "mode=${entryParams.programMode.displayName}, " +
                "isJustLift=${entryParams.isJustLift}, isEcho=${entryParams.isEchoMode}, " +
                "sessionId=${coordinator.currentSessionId ?: "NULL"}, " +
                "weight=${entryParams.weightPerCableKg}kg"
        }

        coordinator.bodyweightTimerJob?.cancel()
        coordinator.bodyweightTimerJob = null
        coordinator._timedExerciseRemainingSeconds.value = null
        coordinator._isExerciseTimerPaused.value = false

        val teardownReason = if (coordinator._currentWarmupSetIndex.value >= 0) {
            TeardownReason.WARMUP_TRANSITION
        } else {
            reason
        }
        val currentExerciseAtCapture = coordinator._loadedRoutine.value
            ?.exercises
            ?.getOrNull(coordinator._currentExerciseIndex.value)
        val shouldPromptBodyweightAtCapture = isBodyweightExercise(currentExerciseAtCapture) &&
            currentExerciseAtCapture != null &&
            coordinator.bodyweightCompletionVariantOverride == null &&
            coordinator._loadedRoutine.value != null
        val isWarmupTransition = coordinator._currentWarmupSetIndex.value >= 0 &&
            currentExerciseAtCapture != null
        if (!executionGuard.isCurrent(lease)) return
        val terminalSnapshot = if (shouldPromptBodyweightAtCapture || isWarmupTransition) {
            if (shouldPromptBodyweightAtCapture && !bodyweightCompletionGate.tryPublish(completion)) {
                executionGuard.releaseCompletionClaim(lease)
                return
            }
            null
        } else {
            captureExitSnapshot(completion, TerminalPath.AUTO_COMPLETE).also(::launchSnapshotPersistence)
        }
        val teardownReady = CompletableDeferred<Unit>()
        launchCompletionJob(lease) {
            // This is the one lease-owned completion job. It persists the exact rest
            // decision while RESET is in flight, before any successor resolution or
            // recommendation can inspect its Normal branch. An unresolved offer alone
            // owns the early Resting state.
            if (completion.routineIdentity != null) {
                val identity = completion.routineIdentity
                val restDuration = coordinator._loadedRoutine.value
                    ?.exercises
                    ?.getOrNull(identity.exerciseIndex)
                    ?.getRestForSet(identity.setIndex)
                    ?: return@launchCompletionJob
                val plan = when (val result = installInitialRestPlan(completion, restDuration)) {
                    is InitialRestPlanInstallResult.Installed -> result.plan
                    else -> null
                }
                if (plan is RestTransitionPlan.UnresolvedDropOffer) {
                    startRestTimer(completion)
                }
            }
            teardownReady.await()
            if (!hasCurrentAuthority(lease, "completion_resume_after_teardown")) return@launchCompletionJob
            val params = coordinator._workoutParameters.value
            val isJustLift = params.isJustLift

            Logger.d("handleSetCompletion: isJustLift=$isJustLift")

            coordinator.isCurrentWorkoutTimed = false
            coordinator.isCurrentTimedCableExercise = false
            coordinator._isCurrentExerciseBodyweight.value = false

            val currentExercise = coordinator._loadedRoutine.value?.exercises?.getOrNull(coordinator._currentExerciseIndex.value)
            val wasBodyweight = isBodyweightExercise(currentExercise)
            // Issue #593: gate on "any routine bodyweight set whose reps have not been
            // confirmed via the rep-entry dialog". The pre-#593 check `currentExercise.duration
            // > 0` caused every default-reps-mode routine (duration == null) to fall through
            // `handleSetCompletion` to `saveWorkoutSession()` with `workingReps=0`. The
            // user had no UI path to enter their actual rep count, so PR #592's
            // history-visible filter then dropped the entire routine from Analytics while
            // Home Recent Activity surfaced the misleading "0 reps · <load>" rows.
            val shouldPromptBodyweightRepEntry = wasBodyweight &&
                currentExercise != null &&
                coordinator.bodyweightCompletionVariantOverride == null &&
                // Defensive: a non-routine path could in theory reach here without a
                // routine-loaded exercise; require a real routine exercise so we never
                // re-prompt outside the routine flow.
                coordinator._loadedRoutine.value != null
            if (shouldPromptBodyweightRepEntry) {
                Logger.d("ActiveSessionEngine") {
                    "Bodyweight set finished; prompting for reps before saving " +
                        "(exercise=${currentExercise.exercise.name}, set=${coordinator._currentSetIndex.value + 1}, " +
                        "hasDuration=${currentExercise.duration?.let { it > 0 } == true})"
                }
                showBodyweightRepEntry(currentExercise)
                executionGuard.releaseCompletionClaim(lease)
                return@launchCompletionJob
            }

            if (wasBodyweight) {
                coordinator.bodyweightSetsCompletedInRoutine++
                Logger.d("ActiveSessionEngine") { "Bodyweight set #${coordinator.bodyweightSetsCompletedInRoutine} completed (exercise=${currentExercise?.exercise?.name})" }
            }

            coordinator.previousExerciseWasBodyweight = wasBodyweight
            Logger.d { "Issue #222 v8: Set coordinator.previousExerciseWasBodyweight=$wasBodyweight" }

            // ===== Phase 35C: Variable Warm-up Set Fast Path =====
            // If we just completed a warm-up set, skip session save/summary/rest
            // and immediately advance to the next warm-up set or working phase.
            val warmupSetIdx = coordinator._currentWarmupSetIndex.value
            if (warmupSetIdx >= 0 && currentExercise != null) {
                val nextWarmupIdx = warmupSetIdx + 1
                if (nextWarmupIdx < currentExercise.warmupSets.size) {
                    // More warm-up sets remaining
                    Logger.d { "Phase 35C: Warm-up set ${warmupSetIdx + 1}/${currentExercise.warmupSets.size} complete, advancing to warm-up ${nextWarmupIdx + 1}" }
                    coordinator._currentWarmupSetIndex.value = nextWarmupIdx
                    if (!hasCurrentAuthority(lease, "warmup_successor_haptic")) return@launchCompletionJob
                    coordinator._hapticEvents.emit(HapticEvent.WORKOUT_END)
                    if (!hasCurrentAuthority(lease, "warmup_successor_after_haptic")) return@launchCompletionJob
                    // Reset for next warm-up set
                    repCounter.resetCountsOnly()
                    resetAutoStopState()
                    executionGuard.releaseCompletionClaim(lease)
                    coordinator.stopWorkoutInProgress.value = false
                    // Restore working weight in params before startWorkout overrides it
                    val workingWeight = resolveOccurrenceSetWeight(currentExercise, 0)
                    coordinator._workoutParameters.update { p ->
                        p.copy(weightPerCableKg = workingWeight, reps = currentExercise.setReps.firstOrNull() ?: 10)
                    }
                    ifCurrent(lease, "warmup_successor_start") {
                        startWorkout(skipCountdown = true)
                    }
                    return@launchCompletionJob
                } else {
                    // All warm-up sets done — transition to working phase
                    Logger.d { "Phase 35C: All ${currentExercise.warmupSets.size} warm-up sets complete, transitioning to working sets" }
                    coordinator._currentWarmupSetIndex.value = -1
                    if (!hasCurrentAuthority(lease, "working_successor_haptic")) return@launchCompletionJob
                    coordinator._hapticEvents.emit(HapticEvent.WORKOUT_END)
                    if (!hasCurrentAuthority(lease, "working_successor_after_haptic")) return@launchCompletionJob
                    // Reset for first working set
                    repCounter.reset()
                    resetAutoStopState()
                    executionGuard.releaseCompletionClaim(lease)
                    coordinator.stopWorkoutInProgress.value = false
                    // Restore working weight/reps
                    val workingWeight = resolveOccurrenceSetWeight(currentExercise, 0)
                    coordinator._workoutParameters.update { p ->
                        p.copy(
                            weightPerCableKg = workingWeight,
                            reps = currentExercise.setReps.firstOrNull() ?: 10,
                            warmupReps = Constants.DEFAULT_WARMUP_REPS,
                        )
                    }
                    ifCurrent(lease, "working_successor_start") {
                        startWorkout(skipCountdown = true)
                    }
                    return@launchCompletionJob
                }
            }

            if (!hasCurrentAuthority(lease, "completion_workout_end_haptic")) return@launchCompletionJob
            coordinator._hapticEvents.emit(HapticEvent.WORKOUT_END)
            if (!hasCurrentAuthority(lease, "completion_after_workout_end_haptic")) return@launchCompletionJob
            val snapshot = terminalSnapshot
                ?: captureExitSnapshot(completion, TerminalPath.AUTO_COMPLETE)
            coordinator.setRepMetrics.value = emptyList()
            val biomechanicsSummary = snapshot.presentationSummary.biomechanicsSummary
            val qualitySummary = snapshot.presentationSummary.qualitySummary

            // Reset rep quality scorer for next set
            coordinator.repQualityScorer.reset()
            coordinator._latestRepQuality.value = null

            // Reuse biomechanics summary captured above for SetSummary display (no second engine call needed)
            // biomechanicsSummary is already captured and engine hasn't been reset yet

            // Reset biomechanics engine and rep boundary timestamps for next set
            resetBiomechanicsContext(lease)
            coordinator.deferAutoStopDeadlineMs = 0L
            coordinator.repBoundaryTimestamps.value = emptyList()

            val completedReps = snapshot.postSaveInput.workingReps
            val summary = snapshot.presentationSummary
            coordinator.bodyweightCompletionVariantOverride = null

            if (completion.routineIdentity != null) {
                // The installed durable Normal transition is the sole successor
                // owner. Resolve it once before publishing SetSummary so the
                // recommendation is visible during the summary window; unresolved
                // and accepted offers deliberately never inspect a successor.
                val persistedPlan = coordinator._restTransitionPlan.value
                val normalPlan = when (persistedPlan) {
                    is RestTransitionPlan.NormalAdvance -> persistedPlan
                    is RestTransitionPlan.Declined -> persistedPlan.normalAdvance
                    else -> null
                }
                val cachedNavigation = if (normalPlan != null) resolveNavigationOnce(normalPlan) else null
                if (cachedNavigation != null) {
                    updateWeightRecommendationForCompletedSet(
                        params = params,
                        currentExercise = currentExercise,
                        completedReps = completedReps,
                        qualitySummary = qualitySummary,
                        biomechanicsSummary = biomechanicsSummary,
                        resolvedNextStep = cachedNavigation.nextStep,
                        successorWasResolved = true,
                    )
                } else {
                    coordinator._weightAdjustmentRecommendation.value = null
                }
            } else {
                updateWeightRecommendationForCompletedSet(
                    params = params,
                    currentExercise = currentExercise,
                    completedReps = completedReps,
                    qualitySummary = qualitySummary,
                    biomechanicsSummary = biomechanicsSummary,
                )
            }

            // Process quality event for Form Master badge tracking
            qualitySummary?.let { qs ->
                gamificationManager.processSetQualityEvent(qs.averageScore, snapshot.lease.profileId)
            }
            if (!hasCurrentAuthority(lease, "completion_after_quality_processing")) return@launchCompletionJob

            Logger.d("Set summary: heaviest=${summary.heaviestLiftKgPerCable}kg, reps=$completedReps, duration=${summary.durationMs}ms")

            val summaryCountdownSeconds = settingsManager.userPreferences.value.summaryCountdownSeconds
            val skipSummary = summaryCountdownSeconds < 0
            val summaryDelayMs = if (skipSummary) 0L else summaryCountdownSeconds * 1000L

            val effectiveSkipSummary = skipSummary

            // Rest actions can be selected while RESET is still in flight. Read the
            // final durable plan and its visible state together under the transition
            // mutex immediately before any post-Ready presentation write.
            val preservePlanOwnedResting = restTransitionMutex.withLock {
                val plan = coordinator._restTransitionPlan.value
                val document = activeRuntimeDocument
                coordinator._workoutState.value is WorkoutState.Resting &&
                    plan != null &&
                    plan !is RestTransitionPlan.NormalAdvance &&
                    document != null &&
                    hasRestTransitionAuthority(document, plan, lease)
            }

            Logger.d("handleSetCompletion: summaryCountdownSeconds=$summaryCountdownSeconds, skipSummary=$skipSummary, wasBodyweight=$wasBodyweight, effectiveSkipSummary=$effectiveSkipSummary, isJustLift=$isJustLift, isAMRAP=${params.isAMRAP}")

            if (!effectiveSkipSummary && !preservePlanOwnedResting) {
                Logger.d("handleSetCompletion: Setting state to SetSummary (effectiveSkipSummary=false)")
                val summaryPublished = executionGuard.commitIfCurrent(lease) {
                    coordinator._workoutState.value = summary
                }
                if (!summaryPublished) return@launchCompletionJob
            } else {
                Logger.d("handleSetCompletion: Skipping SetSummary state (effectiveSkipSummary=true, wasBodyweight=$wasBodyweight)")
            }

            if (preservePlanOwnedResting) return@launchCompletionJob

            if (isJustLift) {
                Logger.d("Just Lift: IMMEDIATE reset for next set (while showing summary)")

                repCounter.reset()
                resetAutoStopState()

                coordinator._workoutParameters.update { p ->
                    p.copy(selectedExerciseId = null)
                }

                bleRepository.restartMonitorPolling()

                enableHandleDetection()
                bleRepository.enableJustLiftWaitingMode()

                val justLiftRestSeconds = params.justLiftRestSeconds
                Logger.d("Just Lift: Machine armed & ready. summaryCountdownSeconds=$summaryCountdownSeconds, skipSummary=$skipSummary, restSeconds=$justLiftRestSeconds")

                if (skipSummary) {
                    Logger.d("Just Lift: Summary OFF - skipping summary")
                    resetForNewWorkout()
                    coordinator._workoutState.value = WorkoutState.Idle
                    if (justLiftRestSeconds > 0) {
                        startJustLiftEggTimer(justLiftRestSeconds)
                    }
                } else if (summaryDelayMs > 0) {
                    delay(summaryDelayMs)
                    if (!hasCurrentAuthority(lease, "just_lift_summary_delay")) return@launchCompletionJob

                    if (coordinator._workoutState.value is WorkoutState.SetSummary) {
                        Logger.d("Just Lift: Summary complete, transitioning to Idle")
                        resetForNewWorkout()
                        coordinator._workoutState.value = WorkoutState.Idle
                        if (justLiftRestSeconds > 0) {
                            Logger.d("Just Lift: Starting egg timer ($justLiftRestSeconds s)")
                            startJustLiftEggTimer(justLiftRestSeconds)
                        }
                    } else {
                        Logger.d("Just Lift: Summary interrupted by user action (state is ${coordinator._workoutState.value})")
                    }
                } else {
                    Logger.d("Just Lift: Summary Unlimited - waiting for user action")
                }
            } else if (params.isAMRAP) {
                Logger.d("AMRAP: Auto-advancing to rest timer")

                repCounter.reset()
                resetAutoStopState()

                bleRepository.restartMonitorPolling()

                enableHandleDetection()
                bleRepository.enableJustLiftWaitingMode()

                Logger.d("AMRAP: Machine armed & ready. summaryCountdownSeconds=$summaryCountdownSeconds, skipSummary=$skipSummary")

                if (skipSummary) {
                    Logger.d("AMRAP: Summary OFF - skipping summary, proceeding to rest timer")
                    startRestTimer(completion)
                } else if (summaryDelayMs > 0) {
                    delay(summaryDelayMs)
                    if (!hasCurrentAuthority(lease, "amrap_summary_delay")) return@launchCompletionJob

                    if (coordinator._workoutState.value is WorkoutState.SetSummary) {
                        startRestTimer(completion)
                    }
                } else {
                    Logger.d("AMRAP: Summary Unlimited - waiting for user action")
                }
            } else {
                Logger.d("Routine/SingleExercise mode: skipSummary=$skipSummary, effectiveSkipSummary=$effectiveSkipSummary, wasBodyweight=$wasBodyweight, summaryCountdownSeconds=$summaryCountdownSeconds")
                if (effectiveSkipSummary) {
                    Logger.d("Routine mode: Summary skipped (effectiveSkipSummary=true, wasBodyweight=$wasBodyweight) - calling startRestTimer()")

                    repCounter.reset()
                    resetAutoStopState()

                    Logger.d("Routine mode: Parent-aligned - no polling restart/auto-start during rest")

                    startRestTimer(completion)
                } else if (summaryDelayMs > 0 && !isSingleExerciseMode(coordinator)) {
                    // Issue #320: Auto-advance from summary via proceedFromSummary() which handles
                    // full bookkeeping: clearing RPE, marking exercises completed, checking routine
                    // completion, and starting rest timer. Direct startRestTimer() would bypass this.
                    Logger.d("Routine mode: Auto-advancing from summary after ${summaryDelayMs}ms (Issue #320)")
                    delay(summaryDelayMs)
                    if (!hasCurrentAuthority(lease, "routine_summary_delay")) return@launchCompletionJob
                    if (coordinator._workoutState.value is WorkoutState.SetSummary) {
                        flowDelegate?.proceedFromSummary(completion)
                            ?: run {
                                // Fallback if delegate not wired (shouldn't happen in production)
                                Logger.w("Issue #320: flowDelegate null, falling back to direct startRestTimer")
                                repCounter.reset()
                                resetAutoStopState()
                                startRestTimer(completion)
                            }
                    }
                } else {
                    Logger.d("Routine mode: Summary Unlimited - waiting for user action")
                }
            }
        }
        beginMachineTeardown(lease, teardownReason) {
            teardownReady.complete(Unit)
        }
    }

    // ===== Rest Timer and Flow Control =====

    private fun computeRemainingSeconds(
        deadlineElapsedRealtimeMs: Long,
        nowElapsedRealtimeMs: Long = elapsedRealtimeProvider(),
    ): Int {
        val remainingMs = deadlineElapsedRealtimeMs - nowElapsedRealtimeMs
        if (remainingMs <= 0L) return 0
        return ((remainingMs + 999L) / 1000L).toInt()
    }

    private fun armRestDeadline(
        remainingSeconds: Int,
        nowElapsedRealtimeMs: Long = elapsedRealtimeProvider(),
    ) {
        coordinator.restDeadlineElapsedRealtimeMs = saturatingAddMilliseconds(
            baseMs = nowElapsedRealtimeMs,
            durationMs = remainingSeconds.coerceAtLeast(0).toLong() * 1_000L,
        )
    }

    private fun currentRestRemainingSeconds(): Int {
        val deadline = coordinator.restDeadlineElapsedRealtimeMs ?: return coordinator._restSecondsRemaining.value.coerceAtLeast(0)
        return computeRemainingSeconds(deadline)
    }

    private fun deadlineEpochMs(nowEpochMs: Long, durationSeconds: Int): Long = saturatingAddMilliseconds(
        baseMs = nowEpochMs,
        durationMs = durationSeconds.coerceAtLeast(0).toLong() * 1_000L,
    )

    private fun saturatingAddMilliseconds(baseMs: Long, durationMs: Long): Long = if (durationMs > 0L && baseMs > Long.MAX_VALUE - durationMs) Long.MAX_VALUE else baseMs + durationMs

    private fun saturatingAddSeconds(baseSeconds: Int, deltaSeconds: Int): Int = (baseSeconds.toLong() + deltaSeconds.toLong()).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    private fun armJustLiftRestDeadline(
        remainingSeconds: Int,
        nowElapsedRealtimeMs: Long = elapsedRealtimeProvider(),
    ) {
        coordinator.justLiftRestDeadlineElapsedRealtimeMs =
            nowElapsedRealtimeMs + (remainingSeconds.coerceAtLeast(0).toLong() * 1000L)
    }

    private fun currentJustLiftRestRemainingSeconds(): Int {
        val deadline = coordinator.justLiftRestDeadlineElapsedRealtimeMs
            ?: return coordinator._justLiftRestCountdown.value?.coerceAtLeast(0) ?: 0
        return computeRemainingSeconds(deadline)
    }

    private fun updateWeightRecommendationForCompletedSet(
        params: WorkoutParameters,
        currentExercise: RoutineExercise?,
        completedReps: Int,
        qualitySummary: SetQualitySummary?,
        biomechanicsSummary: BiomechanicsSetSummary?,
        resolvedNextStep: Pair<Int, Int>? = null,
        successorWasResolved: Boolean = false,
    ) {
        val prefs = settingsManager.userPreferences.value
        if (!prefs.weightSuggestionsEnabled) {
            coordinator._weightAdjustmentRecommendation.value = null
            return
        }

        val routine = coordinator._loadedRoutine.value
        if (routine == null || params.isJustLift) {
            coordinator._weightAdjustmentRecommendation.value = null
            return
        }

        val currentExerciseIndex = coordinator._currentExerciseIndex.value
        val currentSetIndex = coordinator._currentSetIndex.value
        val nextStep = if (successorWasResolved) {
            resolvedNextStep
        } else {
            flowDelegate?.getNextStep(routine, currentExerciseIndex, currentSetIndex)
        }
        if (nextStep == null) {
            coordinator._weightAdjustmentRecommendation.value = null
            return
        }

        val targetExercise = routine.exercises.getOrNull(nextStep.first)
        val currentExerciseId = currentExercise?.exercise?.id ?: params.selectedExerciseId
        val targetExerciseId = targetExercise?.exercise?.id
        val isSameExercise = currentExerciseId != null && currentExerciseId == targetExerciseId
        val targetExerciseIsBodyweight = targetExercise?.exercise?.isBodyweight == true
        val completedSetHasTarget = params.reps > 0 && !params.isAMRAP
        val targetWeightKgPerCable = targetExercise
            ?.setWeightsPerCableKg
            ?.getOrNull(nextStep.second)
            ?: targetExercise?.weightPerCableKg
            ?: params.weightPerCableKg

        val input = WeightAdjustmentInput(
            exerciseId = currentExerciseId,
            exerciseName = targetExercise?.exercise?.name ?: currentExercise?.exercise?.name,
            targetExerciseId = targetExerciseId,
            targetSetIndex = nextStep.second,
            targetReps = params.reps,
            actualReps = completedReps,
            currentWeightKgPerCable = targetWeightKgPerCable,
            weightIncrementKg = prefs.effectiveWeightIncrementKg,
            qualitySummary = qualitySummary,
            biomechanicsSummary = biomechanicsSummary,
            isBodyweight = currentExercise?.exercise?.isBodyweight == true,
            hasNextSetTarget = isSameExercise &&
                !targetExerciseIsBodyweight &&
                completedSetHasTarget,
        )

        coordinator._weightAdjustmentRecommendation.value = recommendWeightAdjustmentUseCase(input)
    }

    /**
     * Start the rest timer between sets.
     */
    internal fun startRestTimer() {
        startRestTimerFor(executionGuard.currentLease)
    }

    internal fun startRestTimer(lease: ExecutionLease) {
        startRestTimerFor(lease)
    }

    internal fun startRestTimer(completion: SetExecutionCompletion) {
        startRestTimerFor(completion.lease, completion)
    }

    private fun startRestTimerFor(
        lease: ExecutionLease?,
        completion: SetExecutionCompletion? = null,
    ) {
        if (!hasExpectedAuthority(lease, "rest_timer_start")) return
        val timerJob = scope.launch(start = CoroutineStart.LAZY) {
            if (!hasExpectedAuthority(lease, "rest_timer_launch")) return@launch
            val timerJob = coroutineContext[kotlinx.coroutines.Job] ?: return@launch
            val routine = coordinator._loadedRoutine.value
            val currentExercise = routine?.exercises?.getOrNull(coordinator._currentExerciseIndex.value)
            val exerciseId = currentExercise?.exercise?.id ?: coordinator._workoutParameters.value.selectedExerciseId

            val completedSetIndex = coordinator._currentSetIndex.value

            // Capture a normal transition from immutable completion coordinates before any
            // navigation lookup. The later durable runtime installation owns publication.
            val restDuration = currentExercise?.getRestForSet(completedSetIndex) ?: 90
            val installResult = completion?.routineIdentity?.let {
                installInitialRestPlan(completion, restDuration)
            }
            val installedPlan = (installResult as? InitialRestPlanInstallResult.Installed)?.plan
            if (completion?.routineIdentity != null && installResult !is InitialRestPlanInstallResult.Installed) {
                return@launch
            }
            val persistedTimerClaim = if (installedPlan != null && completion != null && timerJob != null) {
                claimPersistedRestTimer(completion, installedPlan, restDuration, timerJob)
                    ?: return@launch
            } else {
                null
            }
            persistedTimerClaim?.previousJob
                ?.takeIf { previous -> previous !== timerJob }
                ?.cancel()
            if (persistedTimerClaim != null) {
                afterPersistedRestTimerClaimForTest?.invoke()
            }
            val resolvedTransition = when (installedPlan) {
                is RestTransitionPlan.NormalAdvance -> installedPlan
                is RestTransitionPlan.Declined -> installedPlan.normalAdvance
                else -> null
            }
            val deferredTransition = installedPlan != null && resolvedTransition == null
            val cachedNavigation = if (resolvedTransition != null) resolveNavigationOnce(resolvedTransition) else null
            val nextStep = when {
                cachedNavigation != null -> cachedNavigation.nextStep

                installedPlan == null && routine != null ->
                    flowDelegate?.getNextStep(routine, coordinator._currentExerciseIndex.value, coordinator._currentSetIndex.value)

                else -> null
            }
            val nextExerciseFromStep = if (nextStep != null && routine != null) {
                routine.exercises.getOrNull(nextStep.first)
            } else {
                null
            }
            val nextSetIdxFromStep = nextStep?.second

            // Issue #354: Always use the exercise's configured rest time, even within supersets.
            // Previously, supersets used a hardcoded short rest time, but users should configure
            // rest per exercise instead.
            val autoplay = settingsManager.autoplayEnabled.value
            val isSingleExercise = isSingleExerciseMode(coordinator)

            Logger.d("startRestTimer: restDuration=$restDuration, autoplay=$autoplay, isSingleExercise=$isSingleExercise, summaryCountdownSeconds=${settingsManager.userPreferences.value.summaryCountdownSeconds}")

            if (restDuration == 0 && !deferredTransition) {
                val canAutomaticallyDispatchPersistedPlan =
                    installedPlan is RestTransitionPlan.NormalAdvance || autoplay
                if (installedPlan != null && canAutomaticallyDispatchPersistedPlan) {
                    beforePersistedRestTimerActionForTest?.invoke()
                    if (dispatchPersistedRestTimerAction(
                            timerJob = timerJob,
                            installedPlan = installedPlan,
                            requiresExpiredDeadline = true,
                        )
                    ) {
                        return@launch
                    }
                }
                if (installedPlan == null) {
                    Logger.d { "Rest duration is 0 - skipping rest timer, advancing immediately (no BLE stop - already sent at set end)" }
                    if (!hasExpectedAuthority(lease, "zero_rest_successor")) return@launch
                    if (isSingleExerciseMode(coordinator)) {
                        advanceToNextSetInSingleExercise(lease)
                    } else {
                        startNextSetOrExerciseFor(lease)
                    }
                    return@launch
                }
            }

            // A manual routine progression still installs the immutable transition,
            // but a resolved normal plan must be durably consumed before publishing a
            // rest screen. Declined and deferred offers deliberately remain in rest.
            if (restDuration != 0 && !autoplay && installedPlan is RestTransitionPlan.NormalAdvance) {
                beforePersistedRestTimerActionForTest?.invoke()
                if (dispatchPersistedRestTimerAction(
                        timerJob = timerJob,
                        installedPlan = installedPlan,
                        requiresExpiredDeadline = false,
                    )
                ) {
                    return@launch
                }
            }

            // Show superset label during rest for user context (e.g., "Superset A")
            val supersetLabel = if (flowDelegate?.isInSuperset() == true) {
                val supersetIds = routine?.supersets?.map { it.id } ?: emptyList()
                val groupIndex = supersetIds.indexOf(currentExercise?.supersetId)
                if (groupIndex >= 0) "Superset ${('A' + groupIndex)}" else "Superset"
            } else {
                null
            }

            val isLastSetOfCurrentExercise = coordinator._currentSetIndex.value >= (currentExercise?.setReps?.size ?: 1) - 1
            val isLastExerciseOverall = when {
                cachedNavigation != null -> cachedNavigation.nextStep == null
                deferredTransition -> false
                else -> flowDelegate?.calculateIsLastExercise(isSingleExercise, currentExercise, routine) ?: false
            }
            val isTransitioningToNextExercise = isLastSetOfCurrentExercise && !isLastExerciseOverall && !isSingleExercise

            val nextExercise = nextExerciseFromStep

            val exerciseForNextSet = nextExerciseFromStep ?: currentExercise
            val nextExerciseIsBodyweight = isBodyweightExercise(exerciseForNextSet)

            if (persistedTimerClaim == null && !deferredTransition && exerciseForNextSet != null && !nextExerciseIsBodyweight) {
                val nextSetIdx = nextSetIdxFromStep ?: (completedSetIndex + 1)

                val hasNextSet = nextSetIdx < exerciseForNextSet.setReps.size
                if (hasNextSet) {
                    val nextSetReps = exerciseForNextSet.setReps.getOrNull(nextSetIdx)
                    val nextSetWeight = resolveOccurrenceSetWeight(exerciseForNextSet, nextSetIdx)
                    val isNextSetLastSet = nextSetIdx >= exerciseForNextSet.setReps.size - 1
                    val nextIsAMRAP = nextSetReps == null || (exerciseForNextSet.isAMRAP && isNextSetLastSet)

                    coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(
                        weightPerCableKg = nextSetWeight,
                        reps = nextSetReps ?: 0,
                        programMode = exerciseForNextSet.programMode,
                        echoLevel = exerciseForNextSet.getEchoLevelForSet(nextSetIdx),
                        eccentricLoad = exerciseForNextSet.eccentricLoad,
                        progressionRegressionKg = clampUpcomingProgressionKg(exerciseForNextSet.progressionKg),
                        selectedExerciseId = exerciseForNextSet.exercise.id,
                        isAMRAP = nextIsAMRAP,
                        stallDetectionEnabled = exerciseForNextSet.stallDetectionEnabled,
                        warmupReps = if (nextExerciseIsBodyweight) 0 else Constants.DEFAULT_WARMUP_REPS,
                        stopAtTop = exerciseForNextSet.stopAtTop,
                        repCountTiming = exerciseForNextSet.repCountTiming,
                    )
                    Logger.d { "startRestTimer: Issue #203 - Updated params for next set: ${exerciseForNextSet.exercise.name}, setIdx=$nextSetIdx, isAMRAP=$nextIsAMRAP, nextSetReps=$nextSetReps" }
                }
            } else if (nextExerciseIsBodyweight) {
                Logger.d { "startRestTimer: Issue #222 - Skipping params update for bodyweight exercise: ${nextExerciseFromStep?.exercise?.name}" }
            }

            val displaySetIndex = nextSetIdxFromStep ?: (coordinator._currentSetIndex.value + 1)
            val displayTotalSets = nextExerciseFromStep?.setReps?.size ?: currentExercise?.setReps?.size ?: 0
            val initialNextName = when {
                cachedNavigation != null -> nextExerciseFromStep?.exercise?.displayName.orEmpty()

                deferredTransition -> ""

                else ->
                    flowDelegate?.calculateNextExerciseName(isSingleExercise, currentExercise, routine) ?: ""
            }

            // Issue #297, #228: Initialize rest timer control state
            val persistedInitialPresentation = if (persistedTimerClaim != null && timerJob != null) {
                publishCurrentPlanOwnedRestPresentation(
                    timerJob = timerJob,
                    initializeDeadline = true,
                )
                    ?: return@launch
            } else {
                null
            }
            val initialRestDuration = persistedInitialPresentation?.originalDurationSeconds ?: restDuration
            val initialRemainingSeconds = persistedInitialPresentation?.remainingSeconds ?: restDuration
            if (persistedInitialPresentation == null) {
                coordinator._restOriginalDuration.value = initialRestDuration
                coordinator._restSecondsRemaining.value = initialRemainingSeconds
                coordinator._isRestPaused.value = false
                armRestDeadline(restDuration)
            }

            // Determine if this is a superset transition for UI display purposes.
            // This indicates we're moving between exercises in a superset group.
            val isSupersetTransition = (flowDelegate?.isInSuperset() == true) &&
                isTransitioningToNextExercise &&
                nextExercise?.supersetId == currentExercise?.supersetId

            // Emit Resting immediately so the UI timer starts without waiting on repository lookups.
            if (persistedInitialPresentation != null) {
                Unit
            } else if (hasExpectedAuthority(lease, "rest_timer_initial_state")) {
                coordinator._workoutState.value = WorkoutState.Resting(
                    restSecondsRemaining = initialRemainingSeconds,
                    nextExerciseName = initialNextName,
                    isLastExercise = isLastExerciseOverall,
                    currentSet = displaySetIndex,
                    totalSets = displayTotalSets,
                    isSupersetTransition = isSupersetTransition,
                    supersetLabel = supersetLabel,
                )
            } else {
                return@launch
            }

            if (exerciseId != null) {
                launch {
                    val lastWeight = getLastWeightForExercise(exerciseId)
                    val prWeight = getPrWeightForExercise(exerciseId)
                    if (hasExpectedAuthority(lease, "rest_timer_weight_lookup")) {
                        coordinator._workoutParameters.value = coordinator._workoutParameters.value.copy(
                            lastUsedWeightKg = lastWeight,
                            prWeightKg = prWeight,
                        )
                    }
                }
            }

            var lastRenderedSecond = initialRemainingSeconds + 1
            var lastTickedSecond = -1
            var restEndingEmitted = false

            try {
                // Issue #339: Deadline-based loop so timers catch up after background suspension.
                while (isActive) {
                    if (!hasExpectedAuthority(lease, "rest_timer_tick")) return@launch
                    val planOwnedPresentation = if (installedPlan != null && timerJob != null) {
                        val captured = captureCurrentPlanOwnedRestPresentation(timerJob)
                        if (captured == null) {
                            afterPersistedRestTimerProfileAuthorityFailureForTest?.invoke()
                            if (retainPlanOwnedRestTimerForTransientProfile(timerJob)) {
                                delay(100L)
                                continue
                            }
                            return@launch
                        }
                        captured
                    } else {
                        null
                    }
                    val remainingSeconds = planOwnedPresentation?.remainingSeconds ?: currentRestRemainingSeconds()
                    if (planOwnedPresentation != null) {
                        beforePersistedRestTimerTickPublishForTest?.invoke()
                        if (!publishCapturedPlanOwnedRestPresentation(timerJob, planOwnedPresentation)) {
                            continue
                        }
                    } else if (remainingSeconds != coordinator._restSecondsRemaining.value) {
                        coordinator._restSecondsRemaining.value = remainingSeconds
                    }

                    // The rest-ending warning has its own cue; avoid overlapping it with per-second ticks.
                    if (!coordinator._isRestPaused.value &&
                        remainingSeconds in (ExerciseCountdownCuePolicy.REST_ENDING_CUE_REMAINING_SECONDS + 1)..10 &&
                        remainingSeconds != lastTickedSecond
                    ) {
                        lastTickedSecond = remainingSeconds
                        val prefs = settingsManager.userPreferences.value
                        if (prefs.beepsEnabled && prefs.countdownBeepsEnabled) {
                            coordinator._hapticEvents.emit(HapticEvent.COUNTDOWN_TICK(remainingSeconds))
                            if (!hasExpectedAuthority(lease, "rest_timer_after_countdown_haptic")) return@launch
                        }
                    }

                    if (remainingSeconds > ExerciseCountdownCuePolicy.REST_ENDING_CUE_REMAINING_SECONDS) {
                        restEndingEmitted = false
                    }

                    val prefs = settingsManager.userPreferences.value
                    if (ExerciseCountdownCuePolicy.shouldEmitRestEndingCue(
                            remainingSeconds = remainingSeconds,
                            isPaused = coordinator._isRestPaused.value,
                            restEndingEmitted = restEndingEmitted,
                            beepsEnabled = prefs.beepsEnabled,
                            countdownBeepsEnabled = prefs.countdownBeepsEnabled,
                        )
                    ) {
                        restEndingEmitted = true
                        coordinator._hapticEvents.emit(HapticEvent.REST_ENDING)
                        if (!hasExpectedAuthority(lease, "rest_timer_after_ending_haptic")) return@launch
                    }

                    if (planOwnedPresentation != null) {
                        lastRenderedSecond = remainingSeconds
                    } else if (remainingSeconds != lastRenderedSecond) {
                        lastRenderedSecond = remainingSeconds
                        val nextName = when {
                            cachedNavigation != null -> nextExerciseFromStep?.exercise?.displayName.orEmpty()

                            deferredTransition -> ""

                            else ->
                                flowDelegate?.calculateNextExerciseName(isSingleExercise, currentExercise, routine) ?: ""
                        }

                        coordinator._workoutState.value = WorkoutState.Resting(
                            restSecondsRemaining = remainingSeconds,
                            nextExerciseName = nextName,
                            isLastExercise = isLastExerciseOverall,
                            currentSet = displaySetIndex,
                            totalSets = displayTotalSets,
                            isSupersetTransition = isSupersetTransition,
                            supersetLabel = supersetLabel,
                        )
                    }

                    val currentPlan = coordinator._restTransitionPlan.value
                    val blocksAutomaticAdvance = currentPlan is RestTransitionPlan.UnresolvedDropOffer
                    if (remainingSeconds <= 0 && autoplay && !coordinator._isRestPaused.value && !blocksAutomaticAdvance) {
                        if (installedPlan != null) {
                            beforePersistedRestTimerActionForTest?.invoke()
                            if (dispatchPersistedRestTimerAction(
                                    timerJob = timerJob,
                                    installedPlan = installedPlan,
                                    requiresExpiredDeadline = true,
                                )
                            ) {
                                return@launch
                            }
                        } else {
                            break
                        }
                    }

                    delay(100)
                }
            } finally {
                if (installedPlan != null && timerJob != null) {
                    withContext(NonCancellable) {
                        restTransitionMutex.withLock {
                            if (persistedRestTimerOwner?.job === timerJob) {
                                persistedRestTimerOwner = null
                                if (coordinator.restTimerJob === timerJob) {
                                    coordinator._isRestPaused.value = false
                                    coordinator.restDeadlineElapsedRealtimeMs = null
                                }
                            }
                        }
                    }
                } else if (coordinator.restTimerJob === timerJob) {
                    coordinator._isRestPaused.value = false
                    coordinator.restDeadlineElapsedRealtimeMs = null
                }
            }

            if (autoplay) {
                Logger.d("ActiveSessionEngine") { "autoplay rest complete: advancing to next set (no BLE stop - already sent at set end)" }
                if (!hasExpectedAuthority(lease, "rest_timer_successor")) return@launch
                if (isSingleExercise) {
                    advanceToNextSetInSingleExercise(lease)
                } else {
                    startNextSetOrExerciseFor(lease)
                }
            }
        }
        if (completion?.routineIdentity == null) {
            coordinator.restTimerJob?.cancel()
            coordinator.restTimerJob = timerJob
        }
        timerJob.start()
    }

    private suspend fun dispatchPersistedRestTimerAction(
        timerJob: Job,
        installedPlan: RestTransitionPlan,
        requiresExpiredDeadline: Boolean,
    ): Boolean {
        val authorized = authorizePersistedRestTimerAction(
            timerJob = timerJob,
            installedPlan = installedPlan,
            requiresExpiredDeadline = requiresExpiredDeadline,
        ) ?: return false
        afterPersistedRestTimerActionAuthorizationForTest?.invoke()
        return applyRestTransitionAwait(authorized.command, authorized.authority).normalTransitionConsumed
    }

    private suspend fun authorizePersistedRestTimerAction(
        timerJob: Job,
        installedPlan: RestTransitionPlan,
        requiresExpiredDeadline: Boolean,
    ): AuthorizedPersistedRestTimerAction? = restTransitionMutex.withLock {
        val owner = persistedRestTimerOwner?.takeIf { it.job === timerJob } ?: return@withLock null
        if (owner.transitionId != installedPlan.transitionId ||
            owner.sourceExecutionId != installedPlan.sourceExecutionId
        ) {
            return@withLock null
        }
        val lease = executionGuard.currentLease ?: return@withLock null
        val document = activeRuntimeDocument ?: return@withLock null
        val plan = coordinator._restTransitionPlan.value ?: return@withLock null
        if (plan.transitionId != owner.transitionId ||
            plan.sourceExecutionId != owner.sourceExecutionId ||
            (
                plan !is RestTransitionPlan.NormalAdvance &&
                    plan !is RestTransitionPlan.Declined &&
                    plan !is RestTransitionPlan.AcceptedRetry
                ) ||
            !hasRestTransitionAuthority(document, plan, lease)
        ) {
            return@withLock null
        }
        if (requiresExpiredDeadline &&
            (document.isRestPaused || RestDeadlineCalculator.remainingSeconds(document, wallClockMillisProvider()) > 0)
        ) {
            return@withLock null
        }
        val authority = PersistedRestTimerActionAuthority(
            timerJob = timerJob,
            transitionId = owner.transitionId,
            sourceExecutionId = owner.sourceExecutionId,
            plan = plan,
            documentVersion = activeRuntimeDocumentVersion,
            deadlineEpochMs = document.restDeadlineEpochMs,
            isPaused = document.isRestPaused,
            requiresExpiredDeadline = requiresExpiredDeadline,
        )
        AuthorizedPersistedRestTimerAction(
            command = RestTransitionCommand.SkipRest(plan.actionIdentity()),
            authority = authority,
        )
    }

    private suspend fun claimPersistedRestTimer(
        completion: SetExecutionCompletion,
        plan: RestTransitionPlan,
        restDurationSeconds: Int,
        timerJob: Job,
    ): PersistedRestTimerClaim? = restTransitionMutex.withLock {
        if (!hasCompletionRestAuthority(completion)) return@withLock null
        val document = activeRuntimeDocument ?: return@withLock null
        if (!hasRestTransitionAuthority(document, plan, completion.lease)) return@withLock null

        val owner = persistedRestTimerOwner
        if (owner?.transitionId == plan.transitionId &&
            owner.sourceExecutionId == plan.sourceExecutionId &&
            owner.job.isActive
        ) {
            return@withLock null
        }
        if (document.isRestPaused) return@withLock null

        val persistedDocument = if (document.restDeadlineEpochMs == null) {
            val timerDocument = document.copy(
                restDeadlineEpochMs = deadlineEpochMs(wallClockMillisProvider(), restDurationSeconds),
                pausedRestRemainingSeconds = null,
                isRestPaused = false,
            )
            if (!replaceRuntimeDocument(timerDocument)) return@withLock null
            if (!hasCompletionRestAuthority(completion) ||
                coordinator._restTransitionPlan.value != plan
            ) {
                return@withLock null
            }
            setActiveRuntimeDocument(timerDocument)
            timerDocument
        } else {
            document
        }
        if (persistedDocument.restDeadlineEpochMs == null) return@withLock null
        val previousJob = coordinator.restTimerJob
        coordinator.restTimerJob = timerJob
        persistedRestTimerOwner = PersistedRestTimerOwner(
            transitionId = plan.transitionId,
            sourceExecutionId = plan.sourceExecutionId,
            job = timerJob,
        )
        PersistedRestTimerClaim(
            previousJob = previousJob,
        )
    }

    private suspend fun publishCurrentPlanOwnedRestPresentation(
        timerJob: Job,
        initializeDeadline: Boolean,
    ): PlanOwnedRestPresentation? = restTransitionMutex.withLock {
        val presentation = capturePlanOwnedRestPresentationLocked(timerJob) ?: return@withLock null
        if (!publishPlanOwnedRestPresentationLocked(timerJob, presentation, initializeDeadline)) return@withLock null
        presentation
    }

    private suspend fun captureCurrentPlanOwnedRestPresentation(timerJob: Job): PlanOwnedRestPresentation? = restTransitionMutex.withLock {
        capturePlanOwnedRestPresentationLocked(timerJob)
    }

    private suspend fun retainPlanOwnedRestTimerForTransientProfile(timerJob: Job): Boolean = restTransitionMutex.withLock {
        val owner = persistedRestTimerOwner?.takeIf { it.job === timerJob } ?: return@withLock false
        val lease = executionGuard.currentLease ?: return@withLock false
        val document = activeRuntimeDocument ?: return@withLock false
        val plan = coordinator._restTransitionPlan.value ?: return@withLock false
        val profileMayStillMatch = when (val profileContext = userProfileRepository.activeProfileContext.value) {
            is ActiveProfileContext.Switching -> true
            is ActiveProfileContext.Ready -> profileContext.profile.id == document.profileId
        }
        profileMayStillMatch &&
            owner.transitionId == plan.transitionId &&
            owner.sourceExecutionId == plan.sourceExecutionId &&
            hasRestTransitionAuthorityIgnoringReadyProfile(document, plan, lease)
    }

    private suspend fun publishCapturedPlanOwnedRestPresentation(
        timerJob: Job,
        presentation: PlanOwnedRestPresentation,
    ): Boolean = restTransitionMutex.withLock {
        publishPlanOwnedRestPresentationLocked(timerJob, presentation, initializeDeadline = false)
    }

    private fun capturePlanOwnedRestPresentationLocked(timerJob: Job): PlanOwnedRestPresentation? {
        val owner = persistedRestTimerOwner?.takeIf { it.job === timerJob } ?: return null
        val lease = executionGuard.currentLease ?: return null
        val document = activeRuntimeDocument ?: return null
        val plan = coordinator._restTransitionPlan.value ?: return null
        if (owner.transitionId != plan.transitionId ||
            owner.sourceExecutionId != plan.sourceExecutionId ||
            !hasRestTransitionAuthority(document, plan, lease)
        ) {
            return null
        }
        val routine = coordinator._loadedRoutine.value ?: return null
        val currentExercise = routine.exercises.getOrNull(document.sourceExerciseIndex) ?: return null
        val normalPlan = when (plan) {
            is RestTransitionPlan.NormalAdvance -> plan
            is RestTransitionPlan.Declined -> plan.normalAdvance
            else -> null
        }
        val navigation = normalPlan?.let { normal ->
            cachedTransitionNavigation?.takeIf {
                it.transitionId == normal.transitionId && it.sourceExecutionId == normal.sourceExecutionId
            }
        }
        val nextStep = navigation?.nextStep
        val nextExercise = nextStep?.let { routine.exercises.getOrNull(it.first) }
        val nextSetIndex = nextStep?.second
        val displaySetIndex = nextSetIndex ?: (document.sourceSetIndex + 1)
        val displayTotalSets = nextExercise?.setReps?.size ?: currentExercise.setReps.size
        val isLastExercise = normalPlan != null && navigation != null && nextStep == null
        val isLastSetOfCurrentExercise = document.sourceSetIndex >= currentExercise.setReps.size - 1
        val isTransitioningToNextExercise = nextStep != null &&
            nextStep.first != document.sourceExerciseIndex &&
            isLastSetOfCurrentExercise &&
            !isLastExercise
        val supersetId = currentExercise.supersetId
        val supersetLabel = supersetId?.let { id ->
            val groupIndex = routine.supersets.indexOfFirst { it.id == id }
            if (groupIndex >= 0) "Superset ${('A' + groupIndex)}" else "Superset"
        }
        val isSupersetTransition = supersetId != null &&
            isTransitioningToNextExercise &&
            nextExercise?.supersetId == supersetId
        val navigationParameters = if (
            !owner.navigationParametersPublished &&
            !coordinator._userAdjustedWeightDuringRest &&
            normalPlan != null &&
            navigation != null &&
            nextExercise != null &&
            nextSetIndex != null &&
            !isBodyweightExercise(nextExercise) &&
            nextSetIndex < nextExercise.setReps.size
        ) {
            val nextSetReps = nextExercise.setReps.getOrNull(nextSetIndex)
            val nextSetWeight = resolveOccurrenceSetWeight(nextExercise, nextSetIndex)
            val isNextSetLastSet = nextSetIndex >= nextExercise.setReps.size - 1
            coordinator._workoutParameters.value.copy(
                weightPerCableKg = nextSetWeight,
                reps = nextSetReps ?: 0,
                programMode = nextExercise.programMode,
                echoLevel = nextExercise.getEchoLevelForSet(nextSetIndex),
                eccentricLoad = nextExercise.eccentricLoad,
                progressionRegressionKg = clampUpcomingProgressionKg(nextExercise.progressionKg),
                selectedExerciseId = nextExercise.exercise.id,
                isAMRAP = nextSetReps == null || (nextExercise.isAMRAP && isNextSetLastSet),
                stallDetectionEnabled = nextExercise.stallDetectionEnabled,
                warmupReps = Constants.DEFAULT_WARMUP_REPS,
                stopAtTop = nextExercise.stopAtTop,
                repCountTiming = nextExercise.repCountTiming,
            )
        } else {
            null
        }
        val remainingSeconds = RestDeadlineCalculator.remainingSeconds(document, wallClockMillisProvider())
        return PlanOwnedRestPresentation(
            documentVersion = activeRuntimeDocumentVersion,
            remainingSeconds = remainingSeconds,
            originalDurationSeconds = document.originalRestDurationSeconds,
            isPaused = document.isRestPaused,
            restingState = WorkoutState.Resting(
                restSecondsRemaining = remainingSeconds,
                nextExerciseName = nextExercise?.exercise?.displayName.orEmpty(),
                isLastExercise = isLastExercise,
                currentSet = displaySetIndex,
                totalSets = displayTotalSets,
                isSupersetTransition = isSupersetTransition,
                supersetLabel = supersetLabel,
            ),
            navigationParameters = navigationParameters,
        )
    }

    private fun publishPlanOwnedRestPresentationLocked(
        timerJob: Job,
        presentation: PlanOwnedRestPresentation,
        initializeDeadline: Boolean,
    ): Boolean {
        val owner = persistedRestTimerOwner?.takeIf { it.job === timerJob } ?: return false
        val lease = executionGuard.currentLease ?: return false
        val document = activeRuntimeDocument ?: return false
        val plan = coordinator._restTransitionPlan.value ?: return false
        if (presentation.documentVersion != activeRuntimeDocumentVersion ||
            owner.transitionId != plan.transitionId ||
            owner.sourceExecutionId != plan.sourceExecutionId ||
            !hasRestTransitionAuthority(document, plan, lease)
        ) {
            return false
        }
        coordinator._restOriginalDuration.value = presentation.originalDurationSeconds
        coordinator._restSecondsRemaining.value = presentation.remainingSeconds
        coordinator._isRestPaused.value = presentation.isPaused
        if (initializeDeadline) {
            if (presentation.isPaused) {
                coordinator.restDeadlineElapsedRealtimeMs = null
            } else {
                armRestDeadline(presentation.remainingSeconds)
            }
        }
        presentation.navigationParameters?.let { parameters ->
            coordinator._workoutParameters.value = parameters
            owner.navigationParametersPublished = true
        }
        coordinator._workoutState.value = presentation.restingState
        return true
    }

    private fun refreshActivePlanOwnedRestPresentationLocked() {
        if (coordinator._workoutState.value !is WorkoutState.Resting) return
        val owner = persistedRestTimerOwner ?: return
        val presentation = capturePlanOwnedRestPresentationLocked(owner.job) ?: return
        publishPlanOwnedRestPresentationLocked(owner.job, presentation, initializeDeadline = false)
    }

    private suspend fun installInitialRestPlan(
        completion: SetExecutionCompletion,
        restDurationSeconds: Int,
    ): InitialRestPlanInstallResult {
        val identity = completion.routineIdentity
            ?: return recordInitialRestPlanInstallResult(InitialRestPlanInstallResult.SourceRejected)
        val result = restTransitionMutex.withLock {
            if (!hasCompletionRestAuthority(completion)) {
                return@withLock InitialRestPlanInstallResult.AuthorityRejected
            }
            val existing = coordinator._restTransitionPlan.value
            if (existing != null &&
                existing.sourceExecutionId == completion.lease.executionId.toString() &&
                existing.logicalSetKey == identity.logicalSetKey
            ) {
                return@withLock InitialRestPlanInstallResult.Installed(existing)
            }

            val sourceExercise = coordinator._loadedRoutine.value
                ?.exercises
                ?.getOrNull(identity.exerciseIndex)
                ?: return@withLock InitialRestPlanInstallResult.SourceRejected
            val document = pendingInitialRestRuntimeDocument
                ?.takeIf { pending ->
                    pending.profileId == identity.profileId &&
                        pending.routineId == identity.routineId &&
                        pending.routineSessionId == identity.routineSessionId &&
                        pending.routineExerciseId == identity.routineExerciseId &&
                        pending.sourceExecutionId == completion.lease.executionId.toString() &&
                        pending.sourceStableSessionId == completion.lease.sessionId &&
                        pending.logicalSetKey == identity.logicalSetKey &&
                        pending.plannedSetId == identity.plannedSetId
                }
                ?: buildInitialRestRuntimeDocument(
                    completion = completion,
                    sourceExercise = sourceExercise,
                    restDurationSeconds = restDurationSeconds,
                ).also { pendingInitialRestRuntimeDocument = it }
            ensurePendingInitialRuntimeCleanupCandidate(document)
            if (!replaceRuntimeDocument(document)) {
                return@withLock InitialRestPlanInstallResult.PersistenceFailure
            }
            if (!hasCompletionRestAuthority(completion)) {
                pendingInitialRestRuntimeDocument = null
                return@withLock InitialRestPlanInstallResult.AuthorityRejected
            }
            pendingInitialRestRuntimeDocument = null
            setActiveRuntimeDocument(document)
            val plan = document.restTransitionPlan
                ?: return@withLock InitialRestPlanInstallResult.SourceRejected
            coordinator._restTransitionPlan.value = plan
            InitialRestPlanInstallResult.Installed(plan)
        }
        return recordInitialRestPlanInstallResult(result)
    }

    private fun ensurePendingInitialRuntimeCleanupCandidate(document: ActiveWorkoutRuntimeDocument) {
        while (true) {
            val current = runtimeCleanupCandidateRef.value
            if (current?.document === document && current.source is RuntimeCleanupSource.PendingInitialReplace) return
            val replacement = RuntimeCleanupCandidate(
                document = document,
                source = RuntimeCleanupSource.PendingInitialReplace(
                    candidateToken = pendingInitialRuntimeCandidateSequence.incrementAndGet(),
                ),
            )
            if (runtimeCleanupCandidateRef.compareAndSet(current, replacement)) return
        }
    }

    internal suspend fun installInitialRestPlanForTest(
        completion: SetExecutionCompletion,
        restDurationSeconds: Int,
    ): InitialRestPlanInstallResult = installInitialRestPlan(completion, restDurationSeconds)

    private fun recordInitialRestPlanInstallResult(
        result: InitialRestPlanInstallResult,
    ): InitialRestPlanInstallResult {
        lastInitialRestPlanInstallResultForTest = result
        val reason = when (result) {
            is InitialRestPlanInstallResult.Installed -> return result
            InitialRestPlanInstallResult.PersistenceFailure -> "PERSISTENCE_FAILURE"
            InitialRestPlanInstallResult.AuthorityRejected -> "AUTHORITY_REJECTED"
            InitialRestPlanInstallResult.SourceRejected -> "SOURCE_REJECTED"
        }
        connectionLogRepository.warning(
            LogEventType.WORKOUT_PERSISTENCE,
            "Rest transition install failed",
            details = "reason=$reason",
        )
        return result
    }

    private fun buildInitialRestRuntimeDocument(
        completion: SetExecutionCompletion,
        sourceExercise: RoutineExercise,
        restDurationSeconds: Int,
    ): ActiveWorkoutRuntimeDocument {
        val identity = requireNotNull(completion.routineIdentity)
        val normalAdvance = RestTransitionPlan.NormalAdvance(
            transitionId = transitionIdGenerator(),
            sourceExecutionId = completion.lease.executionId.toString(),
            logicalSetKey = identity.logicalSetKey,
            sourceCoordinates = RestTransitionPlan.Coordinates(identity.exerciseIndex, identity.setIndex),
            plannedSetId = identity.plannedSetId,
            restDurationSeconds = restDurationSeconds,
        )
        val eligibility = dropSetEligibilityPolicy.evaluate(
            DropSetEligibilityRequest(
                offerId = offerIdGenerator(),
                completion = completion,
                configuration = dropSetConfigurationProvider(sourceExercise),
                expectedLiveIdentity = identity,
                commandTemplate = completion.logicalPreRackCommandTemplate,
            ),
        )
        val prior = activeRuntimeDocument?.takeIf {
            it.matchesRoutineIdentity(identity.profileId, identity.routineId, identity.routineSessionId)
        }
        val attemptState = PlannedSetAttemptState(
            logicalSetKey = identity.logicalSetKey,
            nextAttemptNumber = completion.attemptNumber + 1,
            acceptedDropCount = completion.acceptedDropCount,
        )
        return ActiveWorkoutRuntimeDocument(
            profileId = identity.profileId,
            routineId = identity.routineId,
            routineSessionId = identity.routineSessionId,
            routineExerciseId = identity.routineExerciseId,
            sourceExecutionId = completion.lease.executionId.toString(),
            sourceStableSessionId = completion.lease.sessionId,
            sourceAttemptNumber = completion.attemptNumber,
            logicalSetKey = identity.logicalSetKey,
            plannedSetId = identity.plannedSetId,
            sourceExerciseIndex = identity.exerciseIndex,
            sourceSetIndex = identity.setIndex,
            sourceAuthority = completion.toRuntimeSourceAuthoritySnapshot(),
            teardownSeed = RestoredTeardownSeedSnapshot(
                sourceExecutionId = completion.lease.executionId,
                sourceStableSessionId = completion.lease.sessionId,
                profileId = completion.lease.profileId,
                requiresMachine = completion.lease.requiresMachine,
            ),
            exerciseLoadOverlays = prior?.exerciseLoadOverlays.orEmpty(),
            attemptStates = prior?.attemptStates.orEmpty()
                .filterNot { it.logicalSetKey == identity.logicalSetKey } + attemptState,
            restTransitionPlan = buildRestTransitionPlan(normalAdvance, eligibility),
            restDeadlineEpochMs = null,
            originalRestDurationSeconds = restDurationSeconds.coerceAtLeast(0),
        )
    }

    private fun hasCompletionRestAuthority(completion: SetExecutionCompletion): Boolean {
        val identity = completion.routineIdentity ?: return false
        if (!executionGuard.isCurrent(completion.lease) ||
            completion.lease.profileId != identity.profileId ||
            coordinator.currentRoutineId != identity.routineId ||
            coordinator.currentRoutineSessionId != identity.routineSessionId ||
            coordinator._currentExerciseIndex.value != identity.exerciseIndex ||
            coordinator._currentSetIndex.value != identity.setIndex
        ) {
            return false
        }
        val routine = coordinator._loadedRoutine.value ?: return false
        return routine.exercises.getOrNull(identity.exerciseIndex)?.id == identity.routineExerciseId
    }

    /**
     * Start a visual-only "egg timer" for Just Lift rest.
     *
     * Unlike the routine rest timer, this does NOT change WorkoutState — the workout
     * stays in Idle and the auto-start handle-grab detection remains active. The timer
     * is purely informational: it counts down on screen and is canceled when the user
     * picks up the handles (triggering auto-start for the next set).
     *
     * Issue #113: Configurable rest timer for Just Lift mode.
     */
    internal fun startJustLiftEggTimer(restSeconds: Int) {
        coordinator.justLiftRestTimerJob?.cancel()
        coordinator._justLiftRestCountdown.value = restSeconds
        armJustLiftRestDeadline(restSeconds)

        coordinator.justLiftRestTimerJob = scope.launch {
            val timerJob = coroutineContext[kotlinx.coroutines.Job]
            Logger.d("startJustLiftEggTimer: starting $restSeconds s visual countdown")

            var lastRenderedSecond = restSeconds + 1
            var lastTickedSecond = -1

            try {
                while (isActive) {
                    val remaining = currentJustLiftRestRemainingSeconds()
                    if (coordinator._justLiftRestCountdown.value != remaining) {
                        coordinator._justLiftRestCountdown.value = remaining
                    }

                    if (remaining in 1..10 && remaining != lastTickedSecond) {
                        lastTickedSecond = remaining
                        val prefs = settingsManager.userPreferences.value
                        if (prefs.beepsEnabled && prefs.countdownBeepsEnabled) {
                            coordinator._hapticEvents.emit(HapticEvent.COUNTDOWN_TICK(remaining))
                        }
                    }

                    if (remaining != lastRenderedSecond) {
                        lastRenderedSecond = remaining
                    }

                    if (remaining <= 0) {
                        val prefs = settingsManager.userPreferences.value
                        if (prefs.beepsEnabled) {
                            coordinator._hapticEvents.emit(HapticEvent.REST_ENDING)
                        }
                        break
                    }

                    delay(100)
                }

                coordinator._justLiftRestCountdown.value = 0
                Logger.d("startJustLiftEggTimer: countdown complete")
            } finally {
                if (coordinator.justLiftRestTimerJob === timerJob) {
                    coordinator.justLiftRestDeadlineElapsedRealtimeMs = null
                }
            }
        }
    }

    /** Cancel the Just Lift egg timer (called when auto-start fires). */
    internal fun cancelJustLiftEggTimer() {
        val timerJob = coordinator.justLiftRestTimerJob
        coordinator.justLiftRestTimerJob = null
        timerJob?.cancel()
        coordinator.justLiftRestDeadlineElapsedRealtimeMs = null
        coordinator._justLiftRestCountdown.value = null
    }

    /**
     * Advance to the next set within a single exercise (non-routine mode).
     */
    private fun advanceToNextSetInSingleExercise(lease: ExecutionLease?) {
        if (!hasExpectedAuthority(lease, "single_exercise_successor")) return
        val routine = coordinator._loadedRoutine.value
        if (routine == null) {
            coordinator._workoutState.value = WorkoutState.Completed
            coordinator._currentSetIndex.value = 0
            coordinator._currentExerciseIndex.value = 0
            repCounter.reset()
            resetAutoStopState()
            return
        }
        val currentExercise = routine.exercises.getOrNull(coordinator._currentExerciseIndex.value) ?: return

        if (coordinator._currentSetIndex.value < currentExercise.setReps.size - 1) {
            coordinator._currentSetIndex.value++
            val targetReps = currentExercise.setReps[coordinator._currentSetIndex.value]
            val currentParams = coordinator._workoutParameters.value

            val setWeight = if (coordinator._userAdjustedWeightDuringRest) {
                currentParams.weightPerCableKg
            } else {
                resolveOccurrenceSetWeight(currentExercise, coordinator._currentSetIndex.value)
            }
            val setReps = if (coordinator._userAdjustedWeightDuringRest) {
                currentParams.reps
            } else {
                targetReps ?: 0
            }
            val setProgressionKg = if (coordinator._userAdjustedWeightDuringRest) {
                currentParams.progressionRegressionKg
            } else {
                clampUpcomingProgressionKg(currentExercise.progressionKg)
            }
            coordinator._userAdjustedWeightDuringRest = false

            val isLastSet = coordinator._currentSetIndex.value >= currentExercise.setReps.size - 1
            val nextIsAMRAP = targetReps == null || (currentExercise.isAMRAP && isLastSet)

            coordinator._workoutParameters.value = currentParams.copy(
                reps = setReps,
                weightPerCableKg = setWeight,
                isAMRAP = nextIsAMRAP,
                stallDetectionEnabled = currentExercise.stallDetectionEnabled,
                progressionRegressionKg = setProgressionKg,
            )
            Logger.d { "advanceToNextSetInSingleExercise: Issue #203 - setIdx=${coordinator._currentSetIndex.value}, isAMRAP=$nextIsAMRAP" }

            repCounter.resetCountsOnly()
            resetAutoStopState()
            if (hasExpectedAuthority(lease, "single_exercise_start")) {
                startWorkout(skipCountdown = true)
            }
        } else {
            coordinator._workoutState.value = WorkoutState.Completed
            coordinator._loadedRoutine.value = null
            coordinator.routineStartTime = 0
            coordinator._currentSetIndex.value = 0
            coordinator._currentExerciseIndex.value = 0
            repCounter.reset()
            resetAutoStopState()
        }
    }

    /**
     * Start workout or enter SetReady based on autoplay preference.
     */
    private fun startWorkoutOrSetReady(lease: ExecutionLease?) {
        if (!hasExpectedAuthority(lease, "workout_or_set_ready")) return
        val autoplay = settingsManager.autoplayEnabled.value
        if (autoplay) {
            startWorkout(skipCountdown = true)
        } else {
            flowDelegate?.enterSetReady(coordinator._currentExerciseIndex.value, coordinator._currentSetIndex.value)
        }
    }

    /**
     * Issue #525: write one backup for the completed routine instead of per-set files.
     */
    private fun autoBackupRoutineIfEnabled(context: String) {
        val routineSessionId = coordinator.currentRoutineSessionId ?: return
        val backupManager = dataBackupManager ?: return
        if (!preferencesManager.preferencesFlow.value.autoBackupEnabled) return

        scope.launch {
            backupManager.exportRoutine(routineSessionId)
                .onFailure { e ->
                    Logger.w(e) { "Routine auto-backup failed for routine $routineSessionId ($context)" }
                }
        }
    }

    /**
     * Progress to the next set or exercise in a routine.
     */
    private fun startNextSetOrExercise(lease: ExecutionLease) {
        startNextSetOrExerciseFor(lease)
    }

    private fun startNextSetOrExerciseFor(
        lease: ExecutionLease?,
        cachedNavigation: CachedTransitionNavigation? = null,
    ) {
        if (!hasExpectedAuthority(lease, "next_set_or_exercise")) return
        val currentState = coordinator._workoutState.value
        if (currentState is WorkoutState.Completed) return
        if (currentState !is WorkoutState.Resting &&
            currentState !is WorkoutState.SetSummary &&
            currentState !is WorkoutState.Active
        ) {
            return
        }

        coordinator.bodyweightTimerJob?.cancel()
        coordinator.bodyweightTimerJob = null
        coordinator._timedExerciseRemainingSeconds.value = null

        val routine = coordinator._loadedRoutine.value ?: return

        val nextStep = if (cachedNavigation != null) {
            cachedNavigation.nextStep
        } else {
            flowDelegate?.getNextStep(routine, coordinator._currentExerciseIndex.value, coordinator._currentSetIndex.value)
        }

        Logger.d { "startNextSetOrExercise: current=(${coordinator._currentExerciseIndex.value}, ${coordinator._currentSetIndex.value}), nextStep=$nextStep" }

        if (nextStep != null) {
            val (nextExIdx, nextSetIdx) = nextStep
            val nextExercise = routine.exercises[nextExIdx]
            val currentExercise = routine.exercises.getOrNull(coordinator._currentExerciseIndex.value)

            val isChangingExercise = nextExIdx != coordinator._currentExerciseIndex.value
            // Issue #572: when getNextStep advances to the next entry but the new entry
            // is the same physical exercise as the current one (e.g. "Sumo Belt Squat 2x8
            // OldSchool" -> "Sumo Belt Squat 1x8 TUT" the user intends as a single
            // logical movement with a mode change between sets), treat this as a
            // same-exercise continuation. We do NOT want to send a fresh 0x04 BLE CONFIG
            // frame mid-movement, because the Phoenix firmware de-energises the cable
            // on a mode change, which the user perceives as "the set deloads". The mode
            // change (OldSchool -> TUT) is still surfaced to the on-screen label, but
            // the CONFIG frame is deferred until the user explicitly starts that set.
            val isAdjacentLinearExercise =
                nextExIdx == coordinator._currentExerciseIndex.value + 1 &&
                    currentExercise?.supersetId == null &&
                    nextExercise.supersetId == null
            val isSameExerciseContinuation = isChangingExercise &&
                currentExercise != null &&
                isAdjacentLinearExercise &&
                flowDelegate?.isSameExercise(currentExercise, nextExercise) == true

            coordinator._currentExerciseIndex.value = nextExIdx
            coordinator._currentSetIndex.value = nextSetIdx

            val nextSetReps = nextExercise.setReps.getOrNull(nextSetIdx)
            val currentParams = coordinator._workoutParameters.value
            val preserveRestEdits = coordinator._userAdjustedWeightDuringRest

            val nextSetWeight = if (preserveRestEdits) {
                currentParams.weightPerCableKg
            } else {
                resolveOccurrenceSetWeight(nextExercise, nextSetIdx)
            }
            val nextReps = if (preserveRestEdits) {
                currentParams.reps
            } else {
                nextSetReps ?: 0
            }
            val nextEchoLevel = if (preserveRestEdits) {
                currentParams.echoLevel
            } else {
                nextExercise.getEchoLevelForSet(nextSetIdx)
            }
            val nextEccentricLoad = if (preserveRestEdits) {
                currentParams.eccentricLoad
            } else {
                nextExercise.eccentricLoad
            }
            val nextProgressionKg = if (preserveRestEdits) {
                currentParams.progressionRegressionKg
            } else {
                clampUpcomingProgressionKg(nextExercise.progressionKg)
            }

            val nextIsBodyweight = isBodyweightExercise(nextExercise)

            val isNextSetLastSet = nextSetIdx >= nextExercise.setReps.size - 1
            val nextIsAMRAP = nextSetReps == null || (nextExercise.isAMRAP && isNextSetLastSet)

            // Issue #572: for a same-exercise continuation, the on-screen programMode
            // label and the per-set weight should reflect the new entry (so the user
            // sees the TUT finisher weight + mode), but the firmware stays in the
            // current programMode until the user explicitly starts that set, at which
            // point a fresh 0x04 BLE CONFIG frame is sent. Keeping the firmware in the
            // current mode across the boundary is what prevents the cable from
            // de-energising mid-movement.
            val carryProgramMode = if (isSameExerciseContinuation) {
                currentParams.programMode
            } else {
                nextExercise.programMode
            }
            val carryEchoLevel = if (isSameExerciseContinuation) {
                currentParams.echoLevel
            } else {
                nextEchoLevel
            }
            val carryEccentricLoad = if (isSameExerciseContinuation) {
                currentParams.eccentricLoad
            } else {
                nextEccentricLoad
            }
            coordinator._workoutParameters.value = currentParams.copy(
                weightPerCableKg = nextSetWeight,
                reps = nextReps,
                programMode = carryProgramMode,
                echoLevel = carryEchoLevel,
                eccentricLoad = carryEccentricLoad,
                progressionRegressionKg = nextProgressionKg,
                selectedExerciseId = nextExercise.exercise.id,
                isAMRAP = nextIsAMRAP,
                stallDetectionEnabled = nextExercise.stallDetectionEnabled,
                warmupReps = if (nextIsBodyweight) 0 else Constants.DEFAULT_WARMUP_REPS,
                stopAtTop = nextExercise.stopAtTop,
                repCountTiming = nextExercise.repCountTiming,
            )
            Logger.d {
                "startNextSetOrExercise: Issue #203 - progressionKg=${nextExercise.progressionKg}kg for " +
                    "${nextExercise.exercise.displayName}, isBodyweight=$nextIsBodyweight, " +
                    "isAMRAP=$nextIsAMRAP, isSameExerciseContinuation=$isSameExerciseContinuation"
            }

            if (isChangingExercise && !isSameExerciseContinuation) {
                // Issue #536: autoplay advances via startNextSetOrExercise, not enterSetReady.
                // Without re-seeding rack defaults here, a vest toggled on the previous
                // exercise leaks into captureRackLoadSnapshot for the next exercise.
                flowDelegate?.seedRackSelectionForExercise(nextExIdx)
                repCounter.reset()
                // Phase 35C: Initialize warm-up phase for new exercise with warmupSets
                if (nextSetIdx == 0 && nextExercise.warmupSets.isNotEmpty() && !nextIsBodyweight) {
                    coordinator._currentWarmupSetIndex.value = 0
                    coordinator._totalWarmupSets.value = nextExercise.warmupSets.size
                    Logger.d { "Phase 35C: Entering warm-up phase for ${nextExercise.exercise.displayName}: ${nextExercise.warmupSets.size} warm-up sets" }
                } else {
                    coordinator._currentWarmupSetIndex.value = -1
                    coordinator._totalWarmupSets.value = 0
                }
                resetAutoStopState()
                startWorkoutOrSetReady(lease)
            } else if (isSameExerciseContinuation) {
                // Issue #572: same-exercise continuation across entries. We do NOT call
                // startWorkout() here even when autoplay is on, because that would send
                // a fresh 0x04 BLE CONFIG frame for the TUT finisher (or any other mode
                // change embedded in a same-exercise continuation) mid-movement, which
                // de-energises the cable. Instead we go to SetReady so the user
                // explicitly starts the next set, at which point the fresh CONFIG is
                // sent at the right time. The on-screen label and per-set weight have
                // already been updated above to reflect the new entry.
                repCounter.resetCountsOnly()
                resetAutoStopState()
                // ActiveWorkoutScreen only navigates to SetReady when workoutState is Idle.
                // Leaving Resting/SetSummary after SetReady setup strands the user on the
                // rest/summary UI; once the SetReady state has preserved any rest-screen
                // edits, flip the workout state to Idle so navigation can occur.
                flowDelegate?.enterSetReady(nextExIdx, nextSetIdx)
                coordinator._workoutState.value = WorkoutState.Idle
            } else {
                // Same-entry set advance (isChangingExercise == false). Preserve the
                // existing behaviour so that manual rest-screen weight/rep edits
                // (captured by _userAdjustedWeightDuringRest above) are kept when
                // startWorkoutOrSetReady() runs.
                repCounter.resetCountsOnly()
                resetAutoStopState()
                startWorkoutOrSetReady(lease)
            }
            coordinator._userAdjustedWeightDuringRest = false
        } else {
            coordinator._userAdjustedWeightDuringRest = false
            Logger.d { "startNextSetOrExercise: No more steps - showing routine complete" }
            supersedeConfigurationInputIntent()
            val completedCycleId = cachedNavigation?.cycleId ?: coordinator.activeCycleId
            val completedCycleDayNumber = cachedNavigation?.cycleDayNumber ?: coordinator.activeCycleDayNumber
            scope.launch { updateCycleProgressIfNeeded(completedCycleId, completedCycleDayNumber) }
            // Issue #395: Write aggregate health workout BEFORE clearing routine state
            writeRoutineHealthData()
            autoBackupRoutineIfEnabled("routine-complete-autoplay")
            // Issue #393: Set Idle BEFORE showRoutineComplete() to prevent race where
            // routineFlowState=Complete + workoutState=SetSummary causes navigation ping-pong
            // between EnhancedMainScreen and ActiveWorkoutScreen.
            coordinator._workoutState.value = WorkoutState.Idle
            flowDelegate?.showRoutineComplete()
            executionGuard.mutateConfigurationInputs {
                coordinator._currentSetIndex.value = 0
                coordinator._currentExerciseIndex.value = 0
                coordinator.currentRoutineSessionId = null
                coordinator.currentRoutineName = null
                coordinator.currentRoutineId = null
                coordinator._completedRoutineSetKeys.value = emptySet()
            }
            repCounter.reset()
            resetAutoStopState()
        }
    }

    fun skipRest() {
        if (coordinator._workoutState.value is WorkoutState.Resting) {
            val plan = coordinator._restTransitionPlan.value
            if (plan != null) {
                // The legacy no-ID adapter exists only for ordinary NormalAdvance while
                // the production offer surface is disabled.  Every other plan fails
                // closed until PR3 can echo the identity rendered to the user.
                if (plan is RestTransitionPlan.NormalAdvance) {
                    applyRestTransition(RestTransitionCommand.SkipRest(plan.actionIdentity()))
                }
                return
            }
            coordinator._isRestPaused.value = false
            coordinator.restTimerJob?.cancel()
            coordinator.restTimerJob = null

            val isJustLift = coordinator._workoutParameters.value.isJustLift
            Logger.d("ActiveSessionEngine") { "skipRest: isJustLift=$isJustLift, advancing (no BLE stop - already sent at set end)" }

            if (isJustLift) {
                // Just Lift rest returns to Idle (ready for next auto-start set), not Completed
                coordinator._workoutState.value = WorkoutState.Idle
            } else if (isSingleExerciseMode(coordinator)) {
                advanceToNextSetInSingleExercise(executionGuard.currentLease)
            } else {
                val lease = executionGuard.currentLease
                if (lease != null) startNextSetOrExercise(lease) else startNextSetOrExerciseFor(null)
            }
        }
    }

    // ===== Rest Timer Controls (Issue #297, #228) =====

    /**
     * Extend the rest timer by the given number of seconds.
     * Updates both the original duration (for reset) and the current remaining time.
     */
    fun extendRestTime(seconds: Int) {
        if (coordinator._workoutState.value !is WorkoutState.Resting) return
        if (coordinator._restTransitionPlan.value != null) {
            val restoredTimerOwner = restoredRestTimerOwnerRef.value
            if (restoredTimerOwner != null) {
                mutateRestoredPlanOwnedRest(
                    capturedTimerOwner = restoredTimerOwner,
                    mutation = RestoredRestTimerMutation.Extend(seconds),
                )
            } else {
                mutatePlanOwnedRest { document ->
                    val nowEpochMs = wallClockMillisProvider()
                    val remaining = saturatingAddSeconds(
                        RestDeadlineCalculator.remainingSeconds(document, nowEpochMs),
                        seconds,
                    )
                    val originalDuration = saturatingAddSeconds(document.originalRestDurationSeconds, seconds)
                    document.copy(
                        restTransitionPlan = document.restTransitionPlan
                            ?.withRestDurationSeconds(originalDuration),
                        restDeadlineEpochMs = if (document.isRestPaused) {
                            null
                        } else {
                            deadlineEpochMs(nowEpochMs, remaining)
                        },
                        pausedRestRemainingSeconds = if (document.isRestPaused) remaining.coerceAtLeast(0) else null,
                        originalRestDurationSeconds = originalDuration,
                    ) to {
                        coordinator._restOriginalDuration.value = originalDuration
                        if (document.isRestPaused) {
                            coordinator._restSecondsRemaining.value = remaining.coerceAtLeast(0)
                        } else {
                            armRestDeadline(remaining.coerceAtLeast(0))
                            coordinator._restSecondsRemaining.value = currentRestRemainingSeconds()
                        }
                    }
                }
            }
            return
        }
        coordinator._restOriginalDuration.value += seconds
        val now = elapsedRealtimeProvider()
        val activeDeadline = coordinator.restDeadlineElapsedRealtimeMs
        if (activeDeadline != null && !coordinator._isRestPaused.value) {
            val extendedDeadline = maxOf(activeDeadline, now) + (seconds * 1000L)
            coordinator.restDeadlineElapsedRealtimeMs = extendedDeadline
            coordinator._restSecondsRemaining.value =
                computeRemainingSeconds(extendedDeadline, now)
        } else {
            coordinator._restSecondsRemaining.value += seconds
        }
        Logger.d("ActiveSessionEngine") { "extendRestTime: +${seconds}s, remaining=${coordinator._restSecondsRemaining.value}, original=${coordinator._restOriginalDuration.value}" }
    }

    /**
     * Toggle rest timer pause/resume.
     * When paused, the countdown loop skips decrementing. Beeps also stop.
     */
    fun toggleRestPause() {
        if (coordinator._workoutState.value !is WorkoutState.Resting) return
        if (coordinator._restTransitionPlan.value != null) {
            val restoredTimerOwner = restoredRestTimerOwnerRef.value
            if (restoredTimerOwner != null) {
                mutateRestoredPlanOwnedRest(
                    capturedTimerOwner = restoredTimerOwner,
                    mutation = RestoredRestTimerMutation.TogglePause,
                )
            } else {
                mutatePlanOwnedRest { document ->
                    val nowEpochMs = wallClockMillisProvider()
                    val pausing = !document.isRestPaused
                    val remaining = RestDeadlineCalculator.remainingSeconds(document, nowEpochMs)
                    document.copy(
                        restDeadlineEpochMs = if (pausing) null else deadlineEpochMs(nowEpochMs, remaining),
                        pausedRestRemainingSeconds = if (pausing) remaining.coerceAtLeast(0) else null,
                        isRestPaused = pausing,
                    ) to {
                        coordinator._restSecondsRemaining.value = remaining.coerceAtLeast(0)
                        coordinator._isRestPaused.value = pausing
                        if (pausing) coordinator.restDeadlineElapsedRealtimeMs = null else armRestDeadline(remaining.coerceAtLeast(0))
                    }
                }
            }
            return
        }
        val newPaused = !coordinator._isRestPaused.value
        if (!newPaused) {
            coordinator._isRestPaused.value = false
            armRestDeadline(coordinator._restSecondsRemaining.value)
        } else {
            coordinator._restSecondsRemaining.value = currentRestRemainingSeconds()
            coordinator.restDeadlineElapsedRealtimeMs = null
            coordinator._isRestPaused.value = true
        }
        Logger.d("ActiveSessionEngine") { "toggleRestPause: paused=$newPaused" }
    }

    /**
     * Reset the rest timer to its original duration (including any extensions).
     * Also unpauses if currently paused.
     */
    fun resetRestTimer() {
        if (coordinator._workoutState.value !is WorkoutState.Resting) return
        if (coordinator._restTransitionPlan.value != null) {
            val restoredTimerOwner = restoredRestTimerOwnerRef.value
            if (restoredTimerOwner != null) {
                mutateRestoredPlanOwnedRest(
                    capturedTimerOwner = restoredTimerOwner,
                    mutation = RestoredRestTimerMutation.Reset,
                )
            } else {
                mutatePlanOwnedRest { document ->
                    val restored = document.originalRestDurationSeconds
                    document.copy(
                        restDeadlineEpochMs = deadlineEpochMs(wallClockMillisProvider(), restored),
                        pausedRestRemainingSeconds = null,
                        isRestPaused = false,
                    ) to {
                        coordinator._isRestPaused.value = false
                        coordinator._restSecondsRemaining.value = restored
                        armRestDeadline(restored)
                    }
                }
            }
            return
        }
        coordinator._isRestPaused.value = false
        coordinator._restSecondsRemaining.value = coordinator._restOriginalDuration.value
        armRestDeadline(coordinator._restOriginalDuration.value)
        Logger.d("ActiveSessionEngine") { "resetRestTimer: reset to ${coordinator._restOriginalDuration.value}s" }
    }

    private fun mutateRestoredPlanOwnedRest(
        capturedTimerOwner: RestoredRestTimerOwner,
        mutation: RestoredRestTimerMutation,
    ) {
        scope.launch {
            afterRestoredRestTimerControlCaptureForTest?.invoke()
            var timerPublication: RestoredRestTimerPublication? = null
            restTransitionMutex.withLock {
                val owner = restoredRuntimeOwner ?: return@withLock
                val document = activeRuntimeDocument ?: return@withLock
                val plan = coordinator._restTransitionPlan.value ?: return@withLock
                if (restoredRestTimerOwnerRef.value !== capturedTimerOwner ||
                    owner.guardOwner != capturedTimerOwner.guardOwner ||
                    owner.document != capturedTimerOwner.document ||
                    owner.documentVersion != capturedTimerOwner.documentVersion ||
                    document != capturedTimerOwner.document ||
                    activeRuntimeDocumentVersion != capturedTimerOwner.documentVersion ||
                    plan.transitionId != capturedTimerOwner.transitionId ||
                    plan.sourceExecutionId != capturedTimerOwner.sourceExecutionId ||
                    coordinator._workoutState.value !is WorkoutState.Resting ||
                    !hasRestoredRestTransitionAuthority(owner, document, plan)
                ) {
                    return@withLock
                }

                val nowElapsedRealtimeMs = elapsedRealtimeProvider()
                val nowEpochMs = wallClockMillisProvider()
                val currentRemaining = if (document.isRestPaused) {
                    document.pausedRestRemainingSeconds ?: return@withLock
                } else {
                    capturedTimerOwner.monotonicDeadlineElapsedRealtimeMs
                        ?.let { computeRemainingSeconds(it, nowElapsedRealtimeMs) }
                        ?: 0
                }
                val updatedOriginalDuration: Int
                val updatedRemaining: Int
                val updatedPaused: Boolean
                when (mutation) {
                    is RestoredRestTimerMutation.Extend -> {
                        updatedOriginalDuration = saturatingAddSeconds(
                            document.originalRestDurationSeconds,
                            mutation.seconds,
                        )
                        updatedRemaining = saturatingAddSeconds(currentRemaining, mutation.seconds)
                            .coerceAtMost(updatedOriginalDuration)
                        updatedPaused = document.isRestPaused
                    }

                    RestoredRestTimerMutation.TogglePause -> {
                        updatedOriginalDuration = document.originalRestDurationSeconds
                        updatedRemaining = currentRemaining.coerceIn(0, updatedOriginalDuration)
                        updatedPaused = !document.isRestPaused
                    }

                    RestoredRestTimerMutation.Reset -> {
                        updatedOriginalDuration = document.originalRestDurationSeconds
                        updatedRemaining = updatedOriginalDuration
                        updatedPaused = false
                    }
                }
                val updatedMonotonicDeadline = if (!updatedPaused && updatedRemaining > 0) {
                    saturatingAddMilliseconds(
                        baseMs = nowElapsedRealtimeMs,
                        durationMs = updatedRemaining.toLong() * 1_000L,
                    )
                } else {
                    null
                }
                val updatedDocument = document.copy(
                    restTransitionPlan = if (mutation is RestoredRestTimerMutation.Extend) {
                        plan.withRestDurationSeconds(updatedOriginalDuration)
                    } else {
                        plan
                    },
                    restDeadlineEpochMs = if (updatedMonotonicDeadline == null) {
                        null
                    } else {
                        deadlineEpochMs(nowEpochMs, updatedRemaining)
                    },
                    pausedRestRemainingSeconds = if (updatedPaused) updatedRemaining else null,
                    isRestPaused = updatedPaused,
                    originalRestDurationSeconds = updatedOriginalDuration,
                )
                val replacementJob = if (updatedMonotonicDeadline != null) {
                    capturedTimerOwner.job ?: createRestoredRestTimerJob(
                        guardOwner = capturedTimerOwner.guardOwner,
                        transitionId = capturedTimerOwner.transitionId,
                        sourceExecutionId = capturedTimerOwner.sourceExecutionId,
                    )
                } else {
                    null
                }
                val createdJob = replacementJob?.takeIf { it !== capturedTimerOwner.job }
                val committed = try {
                    replaceRuntimeDocument(updatedDocument)
                } catch (error: CancellationException) {
                    when (runtimeDocumentCommitStatus(updatedDocument, document)) {
                        RuntimeDocumentCommitStatus.COMMITTED -> {
                            try {
                                withContext(NonCancellable) {
                                    val reconciled = reconcileRestoredRestTimerMutationCommitLocked(
                                        owner = owner,
                                        capturedTimerOwner = capturedTimerOwner,
                                        document = document,
                                        plan = plan,
                                        updatedDocument = updatedDocument,
                                        updatedOriginalDuration = updatedOriginalDuration,
                                        updatedRemaining = updatedRemaining,
                                        updatedPaused = updatedPaused,
                                        updatedMonotonicDeadline = updatedMonotonicDeadline,
                                        replacementJob = replacementJob,
                                        createdJob = createdJob,
                                    )
                                    publishRestoredRestTimerMutationJobs(reconciled)
                                }
                            } catch (_: Throwable) {
                                createdJob?.cancel()
                                retireRestoredActionAuthorityPreservingCancellation(owner.guardOwner)
                            }
                        }

                        RuntimeDocumentCommitStatus.UNCHANGED_PRIOR -> createdJob?.cancel()

                        RuntimeDocumentCommitStatus.DIVERGED,
                        RuntimeDocumentCommitStatus.UNKNOWN,
                        -> try {
                            createdJob?.cancel()
                            revokeRestoredActionAuthorityLocked(owner.guardOwner)
                        } catch (_: Throwable) {
                            // Preserve the causal cancellation after best-effort retirement.
                        }
                    }
                    throw error
                }
                if (!committed) {
                    when (runtimeDocumentCommitStatus(updatedDocument, document)) {
                        RuntimeDocumentCommitStatus.COMMITTED -> {
                            timerPublication = reconcileRestoredRestTimerMutationCommitLocked(
                                owner = owner,
                                capturedTimerOwner = capturedTimerOwner,
                                document = document,
                                plan = plan,
                                updatedDocument = updatedDocument,
                                updatedOriginalDuration = updatedOriginalDuration,
                                updatedRemaining = updatedRemaining,
                                updatedPaused = updatedPaused,
                                updatedMonotonicDeadline = updatedMonotonicDeadline,
                                replacementJob = replacementJob,
                                createdJob = createdJob,
                            )
                        }

                        RuntimeDocumentCommitStatus.UNCHANGED_PRIOR -> createdJob?.cancel()

                        RuntimeDocumentCommitStatus.DIVERGED,
                        RuntimeDocumentCommitStatus.UNKNOWN,
                        -> {
                            createdJob?.cancel()
                            revokeRestoredActionAuthorityLocked(owner.guardOwner)
                        }
                    }
                    return@withLock
                }
                timerPublication = reconcileRestoredRestTimerMutationCommitLocked(
                    owner = owner,
                    capturedTimerOwner = capturedTimerOwner,
                    document = document,
                    plan = plan,
                    updatedDocument = updatedDocument,
                    updatedOriginalDuration = updatedOriginalDuration,
                    updatedRemaining = updatedRemaining,
                    updatedPaused = updatedPaused,
                    updatedMonotonicDeadline = updatedMonotonicDeadline,
                    replacementJob = replacementJob,
                    createdJob = createdJob,
                )
            }
            publishRestoredRestTimerMutationJobs(timerPublication)
        }
    }

    private fun reconcileRestoredRestTimerMutationCommitLocked(
        owner: RestoredRuntimeOwner,
        capturedTimerOwner: RestoredRestTimerOwner,
        document: ActiveWorkoutRuntimeDocument,
        plan: RestTransitionPlan,
        updatedDocument: ActiveWorkoutRuntimeDocument,
        updatedOriginalDuration: Int,
        updatedRemaining: Int,
        updatedPaused: Boolean,
        updatedMonotonicDeadline: Long?,
        replacementJob: Job?,
        createdJob: Job?,
    ): RestoredRestTimerPublication? {
        val updatedDocumentVersion = capturedTimerOwner.documentVersion + 1L
        val replacementTimerOwner = capturedTimerOwner.copy(
            document = updatedDocument,
            documentVersion = updatedDocumentVersion,
            monotonicDeadlineElapsedRealtimeMs = updatedMonotonicDeadline,
            job = replacementJob,
        )
        val replacementOwner = owner.copy(
            document = updatedDocument,
            documentVersion = updatedDocumentVersion,
        )
        val published = executionGuard.commitRestoredTimerPublication(
            owner = capturedTimerOwner.guardOwner,
            candidateStillCurrent = {
                restoredRuntimeOwnerRef.value === owner &&
                    restoredRestTimerOwnerRef.value === capturedTimerOwner &&
                    activeRuntimeDocument == document &&
                    activeRuntimeDocumentVersion == capturedTimerOwner.documentVersion &&
                    coordinator._restTransitionPlan.value == plan &&
                    coordinator._workoutState.value is WorkoutState.Resting &&
                    hasRestoredOwnerContextAuthority(owner, document)
            },
        ) {
            check(restoredRuntimeOwnerRef.compareAndSet(owner, replacementOwner))
            check(restoredRestTimerOwnerRef.compareAndSet(capturedTimerOwner, replacementTimerOwner))
            setActiveRuntimeDocument(updatedDocument)
            check(activeRuntimeDocumentVersion == updatedDocumentVersion)
            coordinator._restTransitionPlan.value = updatedDocument.restTransitionPlan
            clearRestoredAcceptedRetryPermissionIfOwned(owner.guardOwner)
            persistedRestTimerOwner = null
            coordinator.restTimerJob = null
            coordinator.restDeadlineElapsedRealtimeMs = null
            coordinator._restOriginalDuration.value = updatedOriginalDuration
            coordinator._restSecondsRemaining.value = updatedRemaining
            coordinator._isRestPaused.value = updatedPaused
            val resting = coordinator._workoutState.value as WorkoutState.Resting
            coordinator._workoutState.value = resting.copy(
                restSecondsRemaining = updatedRemaining,
            )
        }
        if (published) {
            return RestoredRestTimerPublication(
                jobToStart = replacementJob,
                jobsToCancel = listOfNotNull(
                    capturedTimerOwner.job?.takeIf { it !== replacementJob },
                ),
            )
        }

        if (restoredRuntimeOwnerRef.value === owner &&
            restoredRestTimerOwnerRef.value === capturedTimerOwner &&
            activeRuntimeDocument == document &&
            activeRuntimeDocumentVersion == capturedTimerOwner.documentVersion &&
            coordinator._restTransitionPlan.value == plan
        ) {
            setActiveRuntimeDocument(updatedDocument)
        }
        createdJob?.cancel()
        revokeRestoredActionAuthorityLocked(owner.guardOwner)
        return null
    }

    private fun publishRestoredRestTimerMutationJobs(publication: RestoredRestTimerPublication?) {
        publication?.jobsToCancel?.forEach { it.cancel() }
        publication?.jobToStart?.let(::startRestoredRestTimerIfOwned)
    }

    private fun mutatePlanOwnedRest(
        mutation: (ActiveWorkoutRuntimeDocument) -> Pair<ActiveWorkoutRuntimeDocument, () -> Unit>,
    ) {
        scope.launch {
            restTransitionMutex.withLock {
                val lease = executionGuard.currentLease ?: return@withLock
                val document = activeRuntimeDocument ?: return@withLock
                val plan = coordinator._restTransitionPlan.value ?: return@withLock
                if (!hasRestTransitionAuthority(document, plan, lease)) return@withLock
                val (updatedDocument, publish) = mutation(document)
                if (!replaceRuntimeDocument(updatedDocument)) return@withLock
                if (activeRuntimeDocument != document || !hasRestTransitionAuthority(document, plan, lease)) {
                    return@withLock
                }
                setActiveRuntimeDocument(updatedDocument)
                coordinator._restTransitionPlan.value = updatedDocument.restTransitionPlan
                acceptedRetryPermission = null
                publish()
                val resting = coordinator._workoutState.value as? WorkoutState.Resting
                if (resting != null) {
                    coordinator._workoutState.value = resting.copy(
                        restSecondsRemaining = coordinator._restSecondsRemaining.value,
                    )
                }
            }
        }
    }

    private fun startTimedCableTimer(lease: ExecutionLease, durationSeconds: Int) {
        val warmupReps = coordinator._workoutParameters.value.warmupReps
        startExerciseTimer(lease, durationSeconds, warmupReps)
    }

    private fun startBodyweightTimer(lease: ExecutionLease, durationSeconds: Int) {
        startExerciseTimer(lease, durationSeconds, warmupReps = 0)
    }

    private fun startExerciseTimer(
        lease: ExecutionLease,
        durationSeconds: Int,
        warmupReps: Int,
    ) {
        if (!hasCurrentAuthority(lease, "exercise_timer_start")) return
        coordinator.bodyweightTimerJob?.cancel()
        coordinator.exerciseTimerOriginalDuration = durationSeconds
        coordinator._isExerciseTimerPaused.value = false

        val timerJob = scope.launch(start = CoroutineStart.LAZY) {
            if (warmupReps > 0) {
                Logger.d { "Duration cable: waiting for $warmupReps warmup reps before starting ${durationSeconds}s timer" }
                coordinator._repCount.first { it.isWarmupComplete }
                if (!hasCurrentAuthority(lease, "timed_cable_warmup_complete")) return@launch
                Logger.d { "Duration cable: warmup complete, starting ${durationSeconds}s duration timer" }
            }

            if (!hasCurrentAuthority(lease, "exercise_timer_initial_state")) return@launch
            coordinator._timedExerciseRemainingSeconds.value = durationSeconds
            var lastObservedRemaining = durationSeconds
            var lastTickedSecond = emitExerciseCountdownTickIfNeeded(
                lease = lease,
                remainingSeconds = durationSeconds,
                lastTickedSecond = -1,
            )
            while ((coordinator._timedExerciseRemainingSeconds.value ?: 0) > 0) {
                if (!hasCurrentAuthority(lease, "exercise_timer_tick")) return@launch
                if (coordinator._isExerciseTimerPaused.value) {
                    delay(100L)
                    continue
                }
                delay(1000L)
                if (!hasCurrentAuthority(lease, "exercise_timer_after_delay")) return@launch
                if (!coordinator._isExerciseTimerPaused.value) {
                    var remaining = 0
                    coordinator._timedExerciseRemainingSeconds.update { current ->
                        remaining = ((current ?: 0) - 1).coerceAtLeast(0)
                        remaining
                    }
                    lastTickedSecond = ExerciseCountdownCuePolicy.lastTickedSecondAfterRemainingChange(
                        previousRemainingSeconds = lastObservedRemaining,
                        currentRemainingSeconds = remaining,
                        lastTickedSecond = lastTickedSecond,
                    )
                    lastObservedRemaining = remaining
                    lastTickedSecond = emitExerciseCountdownTickIfNeeded(
                        lease = lease,
                        remainingSeconds = remaining,
                        lastTickedSecond = lastTickedSecond,
                    )
                }
            }
            if (!hasCurrentAuthority(lease, "exercise_timer_completion")) return@launch
            coordinator._timedExerciseRemainingSeconds.value = 0
            handleSetCompletion(lease, SetEndReason.TIMER_EXPIRED)
        }
        coordinator.bodyweightTimerJob = timerJob
        timerJob.start()
    }

    private suspend fun emitExerciseCountdownTickIfNeeded(
        lease: ExecutionLease,
        remainingSeconds: Int,
        lastTickedSecond: Int,
    ): Int {
        if (!hasCurrentAuthority(lease, "exercise_countdown_haptic")) return lastTickedSecond
        val prefs = settingsManager.userPreferences.value
        if (ExerciseCountdownCuePolicy.shouldEmitTick(
                remainingSeconds = remainingSeconds,
                isPaused = coordinator._isExerciseTimerPaused.value,
                lastTickedSecond = lastTickedSecond,
                beepsEnabled = prefs.beepsEnabled,
                countdownBeepsEnabled = prefs.countdownBeepsEnabled,
            )
        ) {
            coordinator._hapticEvents.emit(HapticEvent.COUNTDOWN_TICK(remainingSeconds))
            return remainingSeconds
        }
        return lastTickedSecond
    }

    // ===== Exercise Timer Controls (Issue #190: Pause/Resume/Reset for timed exercises) =====

    /**
     * Pause the exercise timer. Pure state manipulation — no BLE commands.
     * The bodyweightTimerJob loop checks this flag and suspends decrement when true.
     */
    fun pauseExerciseTimer() {
        if (coordinator._timedExerciseRemainingSeconds.value == null) return
        coordinator._isExerciseTimerPaused.value = true
        Logger.d("ActiveSessionEngine") { "pauseExerciseTimer: paused" }
    }

    /**
     * Resume the exercise timer from the current position. Pure state manipulation — no BLE commands.
     */
    fun resumeExerciseTimer() {
        if (coordinator._timedExerciseRemainingSeconds.value == null) return
        coordinator._isExerciseTimerPaused.value = false
        Logger.d("ActiveSessionEngine") { "resumeExerciseTimer: resumed" }
    }

    /**
     * Reset the exercise timer to its original duration and unpause. Pure state manipulation — no BLE commands.
     * Does NOT cancel the bodyweightTimerJob; the loop will pick up the new remaining value.
     */
    fun resetExerciseTimer() {
        if (coordinator._timedExerciseRemainingSeconds.value == null) return
        val original = coordinator.exerciseTimerOriginalDuration
        if (original <= 0) return
        coordinator._isExerciseTimerPaused.value = false
        coordinator._timedExerciseRemainingSeconds.value = original
        Logger.d("ActiveSessionEngine") { "resetExerciseTimer: reset to ${original}s" }
    }

    fun startNextSet() {
        val state = coordinator._workoutState.value
        if (state is WorkoutState.Resting && state.restSecondsRemaining == 0) {
            val plan = coordinator._restTransitionPlan.value
            if (plan != null) {
                if (plan is RestTransitionPlan.NormalAdvance) {
                    applyRestTransition(RestTransitionCommand.SkipRest(plan.actionIdentity()))
                }
                return
            }
            Logger.d("ActiveSessionEngine") { "startNextSet: advancing (no BLE stop - already sent at set end)" }

            if (isSingleExerciseMode(coordinator)) {
                advanceToNextSetInSingleExercise(executionGuard.currentLease)
            } else {
                val lease = executionGuard.currentLease
                if (lease != null) startNextSetOrExercise(lease) else startNextSetOrExerciseFor(null)
            }
        }
    }

    // ===== Auto-Start Timer =====

    private fun startAutoStartTimer(expectedLease: ExecutionLease?) {
        if (coordinator.autoStartJob != null) return
        if (executionGuard.machineTeardownState.value !is MachineTeardownState.Ready) return
        if (!hasAutoStartAuthority(expectedLease, "auto_start_timer_start")) return
        val currentState = coordinator._workoutState.value
        if (currentState !is WorkoutState.Idle && currentState !is WorkoutState.SetSummary) {
            return
        }

        coordinator.autoStartJob = scope.launch {
            val timerJob = coroutineContext[kotlinx.coroutines.Job]
            try {
                val countdownSeconds = settingsManager.userPreferences.value.autoStartCountdownSeconds
                for (i in countdownSeconds downTo 1) {
                    if (!hasAutoStartAuthority(expectedLease, "auto_start_countdown")) return@launch
                    if (executionGuard.machineTeardownState.value !is MachineTeardownState.Ready) return@launch
                    coordinator._autoStartCountdown.value = i
                    delay(1000)
                }
                if (!hasAutoStartAuthority(expectedLease, "auto_start_countdown_complete")) return@launch
                coordinator._autoStartCountdown.value = null

                if (coordinator.autoStartJob?.isActive != true) {
                    Logger.d("Auto-start aborted: job cancelled during countdown")
                    return@launch
                }

                val currentHandle = bleRepository.handleState.value
                if (currentHandle != HandleState.Grabbed && currentHandle != HandleState.Moving) {
                    Logger.d("Auto-start aborted: handles no longer grabbed (state=$currentHandle)")
                    return@launch
                }

                val params = coordinator._workoutParameters.value
                if (!params.useAutoStart) {
                    Logger.d("Auto-start aborted: autoStart disabled in parameters")
                    return@launch
                }

                val state = coordinator._workoutState.value
                if (state !is WorkoutState.Idle && state !is WorkoutState.SetSummary) {
                    Logger.d("Auto-start aborted: workout state changed (state=$state)")
                    return@launch
                }

                if (executionGuard.machineTeardownState.value !is MachineTeardownState.Ready) {
                    Logger.d("Auto-start aborted: machine teardown is not ready")
                    return@launch
                }

                // Cancel Just Lift egg timer when user grabs handles to start next set
                if (params.isJustLift) {
                    cancelJustLiftEggTimer()
                }

                Logger.d { "Issue221: Auto-start timer complete - params.isJustLift=${params.isJustLift}, params.useAutoStart=${params.useAutoStart}" }
                Logger.d { "Issue221: Starting workout with isJustLiftMode=true (auto-start implies Just Lift mode)" }
                if (hasAutoStartAuthority(expectedLease, "auto_start_successor")) {
                    startWorkout(skipCountdown = true, isJustLiftMode = true)
                }
            } finally {
                if (coordinator.autoStartJob === timerJob) {
                    coordinator.autoStartJob = null
                    coordinator._autoStartCountdown.value = null
                }
            }
        }
    }

    private fun cancelAutoStartTimer() {
        coordinator.autoStartJob?.cancel()
        coordinator.autoStartJob = null
        coordinator._autoStartCountdown.value = null
    }

    // ===== Cleanup =====

    fun cleanup() {
        val currentLease = executionGuard.currentLease
        val pendingTeardownLease = pendingTeardownReadyContinuation.value?.lease
        beforeCleanupGuardCloseForTest?.invoke()
        executionGuard.cancelAllOwnedJobs()
        val restoredOwner = restoredRuntimeOwnerRef.value
        val restoredTimerOwner = restoredRestTimerOwnerRef.value
        afterCleanupDisposalBoundaryForTest?.invoke()
        supersedePendingResetStart()
        resetMachineTeardownOwner.value = null
        currentLease?.let(bodyweightCompletionGate::invalidate)
        currentLease?.let(::clearDangerZoneCountdownOverride)
        val lease = executionGuard.invalidateCurrent(ExecutionInvalidationReason.CLEANUP)
        if (lease != null) {
            discardTeardownReadyContinuation(lease)
            repFreshnessGate.invalidate(lease)
            executionGuard.cancelPresentationJobsFor(lease)
        }
        pendingTeardownLease?.let(::discardTeardownReadyContinuation)
        coordinator._weightAdjustmentRecommendation.value = null
        coordinator.monitorDataCollectionJob?.cancel()
        cancelAutoStartTimer()
        if (restoredOwner != null) {
            revokeRestoredActionAuthorityLocked(restoredOwner.guardOwner)
        } else {
            restoredTimerOwner
                ?.guardOwner
                ?.let(::detachRestoredRestTimerIfOwned)
                ?.cancel()
        }
        coordinator.restTimerJob?.cancel()
        coordinator.restDeadlineElapsedRealtimeMs = null
        cancelJustLiftEggTimer()
        coordinator.bodyweightTimerJob?.cancel()
        coordinator.repEventsCollectionJob?.cancel()
        coordinator.workoutJob?.cancel()
        stopMotionStartDetection()
    }

    private companion object {
        const val TEMPLATE_531_ID = "template_531"

        // Issue #649: verbal cues are typically <30s; this ceiling covers the cue
        // plus a short post-cue transition window. Exceeding it releases the defer
        // so a racked mid-set handle can end the set normally.
        const val VERBAL_ENCOURAGEMENT_DEFER_WINDOW_MS = 30_000L
    }
}
