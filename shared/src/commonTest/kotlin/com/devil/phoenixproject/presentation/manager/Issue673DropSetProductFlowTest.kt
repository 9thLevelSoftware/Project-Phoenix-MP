package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.DropSetConfiguration
import com.devil.phoenixproject.domain.model.DropSetEligibilityResult
import com.devil.phoenixproject.domain.model.DropSetFeatureGate
import com.devil.phoenixproject.domain.model.DropSetIneligibleReason
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExecutionIdentity
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.domain.usecase.DropSetCandidateResolver
import com.devil.phoenixproject.domain.usecase.DropSetEligibilityPolicy
import com.devil.phoenixproject.domain.usecase.DropSetEligibilityRequest
import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Issue673DropSetProductFlowTest {
    private val policy = DropSetEligibilityPolicy(DropSetFeatureGate { true }, DropSetCandidateResolver())

    @Test
    fun routineExerciseConfigurationDrivesEligibility() {
        val enabled = routineExercise(dropSetEnabled = true, dropSetMinWeightKg = 5f)
        val disabled = routineExercise(dropSetEnabled = false, dropSetMinWeightKg = 5f)
        val identity = identity()
        val completion = completionFixture(lease(), SetEndReason.STALL_FAILURE).copy(
            routineIdentity = identity,
            programmedBaseWeightPerCableKg = 50f,
            configuredStartWeightPerCableKg = 50f,
            logicalPreRackCommandTemplate = WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = 50f),
        )

        val eligible = assertIs<DropSetEligibilityResult.Eligible>(
            policy.evaluate(
                DropSetEligibilityRequest(
                    offerId = "offer",
                    completion = completion,
                    configuration = DropSetConfiguration(enabled.dropSetEnabled, enabled.dropSetMinWeightKg),
                    expectedLiveIdentity = identity,
                    commandTemplate = WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = 50f),
                ),
            ),
        )
        assertEquals(2, eligible.offer.remainingDrops)
        assertTrue(eligible.offer.candidates.isNotEmpty())

        assertEquals(
            DropSetIneligibleReason.DISABLED,
            assertIs<DropSetEligibilityResult.Ineligible>(
                policy.evaluate(
                    DropSetEligibilityRequest(
                        offerId = "offer",
                        completion = completion,
                        configuration = DropSetConfiguration(disabled.dropSetEnabled, disabled.dropSetMinWeightKg),
                        expectedLiveIdentity = identity,
                        commandTemplate = WorkoutParameters(ProgramMode.OldSchool, reps = 8, weightPerCableKg = 50f),
                    ),
                ),
            ).reason,
        )
    }

    @Test
    fun restActionsCarryPlanIdentityThroughUiAndViewModel() {
        val workoutTab = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt",
        )
        val restTimer = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RestTimerCard.kt",
        )
        val viewModel = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/MainViewModel.kt",
        )
        val activeWorkout = readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt",
        )
        assertNotNull(workoutTab)
        assertNotNull(restTimer)
        assertNotNull(viewModel)
        assertNotNull(activeWorkout)

        assertTrue(workoutTab.contains("dropSetOffer = dropSetOfferUiState("))
        assertTrue(workoutTab.contains("onAcceptDropSet = onAcceptDropSet"))
        assertTrue(workoutTab.contains("onSkipRestWithIdentity(identity)"))
        assertTrue(restTimer.contains("onAcceptDropSet(identity, percentage)"))
        assertTrue(restTimer.contains("enabled = !skipRestBlocked"))
        assertTrue(viewModel.contains("RestTransitionCommand.Accept(identity, percentage)"))
        assertTrue(viewModel.contains("RestTransitionCommand.Decline(identity)"))
        assertTrue(viewModel.contains("RestTransitionCommand.SkipRest(identity)"))
        assertTrue(activeWorkout.contains("restTransitionPlan = restTransitionPlan"))
        assertTrue(activeWorkout.contains("viewModel.acceptDropSet(identity, percentage)"))
    }

    private fun routineExercise(
        dropSetEnabled: Boolean,
        dropSetMinWeightKg: Float?,
    ) = RoutineExercise(
        id = "occurrence",
        exercise = Exercise(id = "bench", name = "Bench Press", muscleGroup = "Chest"),
        orderIndex = 0,
        setReps = listOf(8),
        weightPerCableKg = 50f,
        programMode = ProgramMode.OldSchool,
        dropSetEnabled = dropSetEnabled,
        dropSetMinWeightKg = dropSetMinWeightKg,
    )

    private fun identity() = RoutineExecutionIdentity(
        profileId = "profile",
        routineId = "routine",
        routineSessionId = "routine-session",
        routineExerciseId = "occurrence",
        logicalSetKey = LogicalSetKey("routine-session", "occurrence", 0, SetType.STANDARD),
        plannedSetId = "planned-set",
        exerciseIndex = 0,
        setIndex = 0,
    )

    private fun lease() = ExecutionLease(1, "execution-session", "profile", true, 8, false, false, false, false)
}
