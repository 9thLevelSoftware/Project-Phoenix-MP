package com.devil.phoenixproject.presentation.screen

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.devil.phoenixproject.data.preferences.InMemoryRecentJustLiftExerciseStore
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.ProfileEquipmentRackRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.DropSetFeatureGate
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.RoutineLaunchOrigin
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.usecase.ApplyEquipmentRackLoadUseCase
import com.devil.phoenixproject.domain.usecase.CountVelocityOneRepMaxImprovementsUseCase
import com.devil.phoenixproject.domain.usecase.DropSetCandidateResolver
import com.devil.phoenixproject.domain.usecase.DropSetEligibilityPolicy
import com.devil.phoenixproject.domain.usecase.RecommendWeightAdjustmentUseCase
import com.devil.phoenixproject.domain.usecase.RepCounterFromMachine
import com.devil.phoenixproject.domain.usecase.ResolveRoutineWeightsUseCase
import com.devil.phoenixproject.presentation.manager.MachineSafetyCoordinator
import com.devil.phoenixproject.presentation.manager.MachineSafetyTransport
import com.devil.phoenixproject.presentation.manager.NoOpWorkoutServiceController
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.testutil.FakeActiveWorkoutRuntimeRepository
import com.devil.phoenixproject.testutil.FakeBleRepository
import com.devil.phoenixproject.testutil.FakeCompletedSetRepository
import com.devil.phoenixproject.testutil.FakeDataBackupManager
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakeGamificationRepository
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.FakeProfileExerciseBaselineRepository
import com.devil.phoenixproject.testutil.FakeTrainingCycleRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import com.devil.phoenixproject.testutil.InMemoryMachineSafetyHazardRepository
import com.devil.phoenixproject.testutil.WorkoutStateFixtures
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Runtime harness for issue #1164 rendered-reachability evidence.
 *
 * Builds a REAL [MainViewModel] over the same fakes as [MainViewModelTest] so the
 * production [RoutineCompleteScreen] can be rendered and driven end to end on the
 * Robolectric + Compose runtime harness: real state collection, real layout
 * measurement, real semantics, real exit action order.
 */
internal class RoutineCompleteRuntimeFixture {
    val fakeBleRepository = FakeBleRepository()
    val fakeWorkoutRepository = FakeWorkoutRepository()
    val fakeExerciseRepository = FakeExerciseRepository()
    val fakePersonalRecordRepository = FakePersonalRecordRepository()
    val fakeBaselineRepository = FakeProfileExerciseBaselineRepository()
    val fakePreferencesManager = FakePreferencesManager()
    val fakeGamificationRepository = FakeGamificationRepository()
    val fakeTrainingCycleRepository = FakeTrainingCycleRepository()
    val fakeCompletedSetRepository = FakeCompletedSetRepository()
    val fakeUserProfileRepository = FakeUserProfileRepository().apply { setActiveProfileForTest() }
    private val safetyStore = InMemoryMachineSafetyHazardRepository()
    private val recentStore = InMemoryRecentJustLiftExerciseStore()
    private val equipmentRackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val profileEquipmentRackRepository =
        ProfileEquipmentRackRepository(fakeUserProfileRepository, equipmentRackScope)

    val viewModel: MainViewModel = MainViewModel(
        bleRepository = fakeBleRepository,
        workoutRepository = fakeWorkoutRepository,
        exerciseRepository = fakeExerciseRepository,
        personalRecordRepository = fakePersonalRecordRepository,
        profileExerciseBaselineRepository = fakeBaselineRepository,
        repCounter = RepCounterFromMachine(),
        preferencesManager = fakePreferencesManager,
        gamificationRepository = fakeGamificationRepository,
        trainingCycleRepository = fakeTrainingCycleRepository,
        completedSetRepository = fakeCompletedSetRepository,
        activeWorkoutRuntimeRepository = FakeActiveWorkoutRuntimeRepository(),
        dropSetEligibilityPolicy = DropSetEligibilityPolicy(
            DropSetFeatureGate { false },
            DropSetCandidateResolver(),
        ),
        resolveWeightsUseCase = ResolveRoutineWeightsUseCase(
            fakePersonalRecordRepository,
            fakeBaselineRepository,
            FakeVelocityOneRepMaxRepository(),
        ),
        recommendWeightAdjustmentUseCase = RecommendWeightAdjustmentUseCase(),
        equipmentRackRepository = profileEquipmentRackRepository,
        applyEquipmentRackLoadUseCase = ApplyEquipmentRackLoadUseCase(),
        dataBackupManager = FakeDataBackupManager(),
        userProfileRepository = fakeUserProfileRepository,
        workoutServiceController = NoOpWorkoutServiceController,
        computeVelocityOneRepMaxUseCase = com.devil.phoenixproject.domain.usecase.ComputeVelocityOneRepMaxUseCase(
            workoutPoints = { _, _, _ -> emptyList() },
            exerciseLookup = { null },
            personalMvtLookup = { _, _ -> null },
            mvtProvider = com.devil.phoenixproject.domain.onerepmax.MvtProvider(),
            estimator = com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxEstimator(
                com.devil.phoenixproject.domain.assessment.AssessmentEngine(),
            ),
            persist = { _, _, _, _ -> },
        ),
        recordPersonalMvtSampleUseCase = com.devil.phoenixproject.domain.usecase.RecordPersonalMvtSampleUseCase(
            object : com.devil.phoenixproject.data.repository.PersonalMvtRepository {
                override suspend fun get(exerciseId: String, profileId: String) = null
                override suspend fun upsert(
                    exerciseId: String,
                    profileId: String,
                    personalMvtMs: Float,
                    sampleCount: Int,
                ) {
                }
            },
        ),
        velocityOneRepMaxRepository = object : com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository {
            override suspend fun insert(
                result: com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxResult,
                exerciseId: String,
                computedAt: Long,
                profileId: String,
            ) {
            }

            override suspend fun getLatestPassing(
                exerciseId: String,
                profileId: String,
            ): com.devil.phoenixproject.data.repository.VelocityOneRepMaxEntity? = null

            override suspend fun getAllPassing(
                profileId: String,
            ): List<com.devil.phoenixproject.data.repository.VelocityOneRepMaxEntity> = emptyList()

            override fun getHistory(
                exerciseId: String,
                profileId: String,
            ): kotlinx.coroutines.flow.Flow<List<com.devil.phoenixproject.data.repository.VelocityOneRepMaxEntity>> =
                kotlinx.coroutines.flow.flowOf(emptyList())

            override suspend fun hasEstimates(exerciseId: String, profileId: String): Boolean = false
        },
        countVelocityOneRepMaxImprovementsUseCase = CountVelocityOneRepMaxImprovementsUseCase(),
        backfillVelocityOneRepMaxUseCase = com.devil.phoenixproject.domain.usecase.BackfillVelocityOneRepMaxUseCase(
            exerciseIds = { emptyList() },
            hasEstimates = { _, _ -> false },
            computeAllTime = { _, _, _ -> null },
        ),
        machineSafetyCoordinator = MachineSafetyCoordinator(
            repository = safetyStore,
            transport = FakeBleMachineSafetyTransport(fakeBleRepository),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            nowEpochMs = { System.currentTimeMillis() },
        ),
        recentJustLiftExerciseStore = recentStore,
    )

    /**
     * Drives the REAL production state path into [RoutineFlowState.Complete]:
     * loadRoutine (stamping DAILY_ROUTINES origin) or the TrainingCycles origin state,
     * completed set/exercise identities, and routineStartTime for the displayed duration
     * (showRoutineComplete computes totalDurationMs = currentTimeMillis() - routineStartTime).
     *
     * The caller provides the exact completed-key/exercise counts so the fixture can pin
     * the reported presentation values (beginner / 5 exercises / 12 sets / 20m 14s) or
     * long overflow values.
     */
    fun driveToComplete(
        routineName: String,
        exerciseCount: Int,
        setsPerExercise: Int,
        completedSetKeys: Int,
        durationMs: Long,
        trainingCyclesOrigin: Boolean = false,
    ): RoutineFlowState.Complete {
        val routine: Routine = WorkoutStateFixtures
            .createTestRoutine(exerciseCount = exerciseCount, setsPerExercise = setsPerExercise)
            .copy(name = routineName)
        routine.exercises.forEach { fakeExerciseRepository.addExercise(it.exercise) }
        fakeWorkoutRepository.addRoutine(routine)

        viewModel.workoutSessionManager.loadRoutine(routine)
        if (trainingCyclesOrigin) {
            // The route test needs the TRAINING_CYCLES end state; the real
            // loadRoutineFromCycleAsync path that stamps it is exercised in
            // DWSMLaunchOriginTest (commonTest, all targets).
            viewModel.workoutSessionManager.coordinator.routineLaunchOrigin =
                RoutineLaunchOrigin.TRAINING_CYCLES
        }

        val coordinator = viewModel.workoutSessionManager.coordinator
        val keys = mutableSetOf<Pair<Int, Int>>()
        var i = 0
        while (keys.size < completedSetKeys) {
            keys.add((i % exerciseCount) to i)
            i++
        }
        coordinator._completedRoutineSetKeys.value = keys
        coordinator._completedExercises.value = (0 until exerciseCount).toSet()
        // Duration anchor: showRoutineComplete() computes elapsed = now - routineStartTime
        // and the screen FORMATS BY TRUNCATION ((ms / 60000) and ((ms % 60000) / 1000)), so
        // the anchor lands the elapsed time 400ms PAST the requested value. The displayed
        // second is then stable despite real-clock drift of up to ~600ms between this call
        // and showRoutineComplete() (measured drift on this harness is ~0ms).
        coordinator.routineStartTime = currentTimeMillis() - durationMs - 400

        viewModel.showRoutineComplete()
        val state = viewModel.routineFlowState.value
        check(state is RoutineFlowState.Complete) {
            "Harness failed to reach RoutineFlowState.Complete (got $state)"
        }
        return state
    }

    /** Asserts the shared exit cleanup contract after the production exit action ran. */
    fun assertExitCleanup(): String {
        val coordinator = viewModel.workoutSessionManager.coordinator
        val flowState = coordinator.routineFlowState.value
        val workoutState = coordinator.workoutState.value
        val loadedRoutine = coordinator.loadedRoutine.value
        val origin = coordinator.routineLaunchOrigin
        check(flowState is RoutineFlowState.NotInRoutine) { "flowState=$flowState" }
        check(loadedRoutine == null) { "loadedRoutine=$loadedRoutine" }
        check(workoutState is com.devil.phoenixproject.domain.model.WorkoutState.Idle) {
            "workoutState=$workoutState"
        }
        check(origin == null) { "routineLaunchOrigin=$origin" }
        return "NotInRoutine; loadedRoutine=null; Idle; origin=null"
    }

    fun close() {
        viewModel.viewModelScope.cancel()
        profileEquipmentRackRepository.close()
        equipmentRackScope.cancel()
    }
}

private class FakeBleMachineSafetyTransport(
    private val bleRepository: FakeBleRepository,
) : MachineSafetyTransport {
    override val connectedTrainerAddress: String?
        get() = (bleRepository.connectionState.value as? ConnectionState.Connected)?.deviceAddress

    override suspend fun connectMatchingTrainer(trainerAddress: String): Result<Unit> =
        if (connectedTrainerAddress == trainerAddress) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("matching trainer unavailable"))
        }

    override suspend fun stopWorkout(): Result<Unit> = bleRepository.stopWorkout()
}

/**
 * Evidence recorder for the issue #1164 runtime measurements. Every record is printed
 * to the test stream (captured in the Gradle test report) and, when
 * `-Dphoenix.evidence.dir` is set on the Gradle JVM, appended as JSON lines to a file.
 */
internal object RoutineCompleteRuntimeEvidence {
    private val lock = Any()

    fun record(scenario: String, fields: Map<String, Any?>) {
        val flat = fields.entries.joinToString(" ") { "${it.key}=${it.value}" }
        println("EVIDENCE|routine-complete|$scenario|$flat")
        val dir = System.getProperty("phoenix.evidence.dir").orEmpty()
        if (dir.isBlank()) return
        synchronized(lock) {
            val file = File(dir, "routine-complete-runtime-evidence.jsonl")
            file.parentFile?.mkdirs()
            val json = buildString {
                append("{\"scenario\":").append(quote(scenario))
                fields.forEach { (k, v) ->
                    append(",\"").append(k).append("\":").append(quote(v?.toString() ?: "null"))
                }
                append("}\n")
            }
            file.appendText(json)
        }
    }

    private fun quote(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}

/**
 * Injects system-bar window insets into the composition's window-insets holder so the
 * production `systemBarsPadding()` (which reads WindowInsets.systemBars through
 * `WindowInsetsHolder`) sees the simulated safe area.
 *
 * `WindowInsetsHolder` is Kotlin-internal API, so the holder is mutated through
 * reflection exactly as an inset dispatch would (`getOrCreateFor(view).update(insets,
 * typeMask)`), and the insets are ALSO dispatched through the view hierarchy for
 * listeners. The scenario asserts observe the resulting footer position, so a no-op
 * injection cannot pass silently.
 */
internal fun AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>.applySystemBarInsets(
    topPx: Int,
    bottomPx: Int,
) {
    val insets = WindowInsetsCompat.Builder()
        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, topPx, 0, bottomPx))
        .build()
    runOnIdle {
        forEachView(activity.window.decorView) { view ->
            // Path 1: direct holder mutation (reflection; internal API).
            runCatching {
                val holderClass = Class.forName("androidx.compose.foundation.layout.WindowInsetsHolder")
                val companion = holderClass.getDeclaredField("Companion").get(null)
                val getOrCreate = companion.javaClass.methods.first { it.name == "getOrCreateFor" }
                val holder = getOrCreate.invoke(companion, view) ?: return@runCatching
                val update = holder.javaClass.methods.first {
                    it.name == "update" && it.parameterCount == 2
                }
                update.invoke(holder, insets, WindowInsetsCompat.Type.systemBars())
            }
            // Path 2: dispatch through the view hierarchy for any inset listeners.
            runCatching {
                @Suppress("DEPRECATION")
                val platformInsets = android.view.WindowInsets.Builder()
                    .setInsets(
                        android.view.WindowInsets.Type.systemBars(),
                        android.graphics.Insets.of(0, topPx, 0, bottomPx),
                    )
                    .build()
                view.dispatchApplyWindowInsets(platformInsets)
            }
        }
    }
    waitForIdle()
}

private fun forEachView(root: View, block: (View) -> Unit) {
    block(root)
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) {
            forEachView(root.getChildAt(i), block)
        }
    }
}
