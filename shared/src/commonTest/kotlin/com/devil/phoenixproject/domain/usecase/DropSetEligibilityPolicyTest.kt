package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.DropSetConfiguration
import com.devil.phoenixproject.domain.model.DropSetEligibilityResult
import com.devil.phoenixproject.domain.model.DropSetFeatureGate
import com.devil.phoenixproject.domain.model.DropSetIneligibleReason
import com.devil.phoenixproject.domain.model.LogicalSetKey
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExecutionIdentity
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.presentation.manager.ExecutionLease
import com.devil.phoenixproject.presentation.manager.SetExecutionCompletion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DropSetEligibilityPolicyTest {
    private val policy = DropSetEligibilityPolicy(DropSetFeatureGate { true }, DropSetCandidateResolver())

    @Test
    fun returnsStableCandidatesAndRemainingDropsForEligibleCompletion() {
        val result = evaluate(offerId = "offer-7")

        val offer = assertIs<DropSetEligibilityResult.Eligible>(result).offer
        assertEquals("offer-7", offer.offerId)
        assertEquals(listOf(45f, 40f, 35f), offer.candidates.map { it.resolvedWeightPerCableKg })
        assertEquals(2, offer.remainingDrops)
    }

    @Test
    fun featureGateShortCircuitsInvalidConfigurationAndCandidateInputs() {
        val gated = DropSetEligibilityPolicy(DropSetFeatureGate { false }, DropSetCandidateResolver())
        val result = gated.evaluate(
            request(
                completion = completion(reason = SetEndReason.TARGET_REPS_REACHED),
                configuration = DropSetConfiguration(enabled = false, minimumWeightPerCableKg = Float.NaN),
                commandTemplate = commandTemplate(reps = 0),
            ),
        )
        assertIneligible(DropSetIneligibleReason.FEATURE_GATED, result)
    }

    @Test
    fun returnsExactReasonForEveryPolicyCondition() {
        val cases = listOf(
            DropSetIneligibleReason.NOT_STALL_FAILURE to request(completion = completion(reason = SetEndReason.USER_STOPPED)),
            DropSetIneligibleReason.DISABLED to request(configuration = DropSetConfiguration(false, 1f)),
            DropSetIneligibleReason.INVALID_MINIMUM to request(configuration = DropSetConfiguration(true, 0f)),
            DropSetIneligibleReason.NOT_OLD_SCHOOL to request(completion = completion(programMode = ProgramMode.Pump)),
            DropSetIneligibleReason.NOT_CABLE_WORKING_SET to request(completion = completion(isCable = false)),
            DropSetIneligibleReason.WARMUP to request(completion = completion(isWarmup = true)),
            DropSetIneligibleReason.ECHO to request(completion = completion(isEcho = true)),
            DropSetIneligibleReason.JUST_LIFT to request(completion = completion(isJustLift = true)),
            DropSetIneligibleReason.BODYWEIGHT to request(completion = completion(isBodyweight = true, isCable = false)),
            DropSetIneligibleReason.DROP_LIMIT_REACHED to request(completion = completion(acceptedDropCount = 2)),
            DropSetIneligibleReason.IDENTITY_MISMATCH to request(completion = completion(routineIdentity = null)),
        )

        cases.forEach { (reason, request) -> assertIneligible(reason, policy.evaluate(request)) }
    }

    @Test
    fun timedCableIsNotACableWorkingSet() {
        assertIneligible(
            DropSetIneligibleReason.NOT_CABLE_WORKING_SET,
            evaluate(completion = completion(isTimed = true, isCable = true)),
        )
    }

    @Test
    fun requiresFullLiveIdentityAndCapturedPlannedSetWhenPresent() {
        val base = identity()
        val mismatches = listOf(
            base.copy(profileId = "other-profile"),
            base.copy(routineId = "other-routine"),
            base.copy(routineSessionId = "other-session", logicalSetKey = base.logicalSetKey.copy(routineSessionId = "other-session")),
            base.copy(routineExerciseId = "other-occurrence", logicalSetKey = base.logicalSetKey.copy(routineExerciseId = "other-occurrence")),
            base.copy(exerciseIndex = 1),
            base.copy(setIndex = 1, logicalSetKey = base.logicalSetKey.copy(setIndex = 1)),
            base.copy(logicalSetKey = base.logicalSetKey.copy(setKind = SetType.AMRAP)),
            base.copy(plannedSetId = "other-plan"),
        )
        mismatches.forEach { live ->
            assertIneligible(
                DropSetIneligibleReason.IDENTITY_MISMATCH,
                evaluate(expectedLiveIdentity = live),
            )
        }

        assertIs<DropSetEligibilityResult.Eligible>(
            evaluate(
                completion = completion(routineIdentity = base.copy(plannedSetId = null)),
                expectedLiveIdentity = base.copy(plannedSetId = "live-only-plan"),
            ),
        )
    }

    @Test
    fun rejectsWhenAllCandidatesAreInvalidAndKeepsSingleValidCandidate() {
        assertIneligible(
            DropSetIneligibleReason.NO_VALID_CANDIDATE,
            evaluate(configuration = DropSetConfiguration(true, 46f)),
        )
        val result = assertIs<DropSetEligibilityResult.Eligible>(
            evaluate(configuration = DropSetConfiguration(true, 43f)),
        )
        assertEquals(listOf(45f), result.offer.candidates.map { it.resolvedWeightPerCableKg })
    }

    @Test
    fun computesRemainingDropsForCountsZeroAndOneAndRejectsTwo() {
        assertEquals(2, assertIs<DropSetEligibilityResult.Eligible>(evaluate(completion = completion(acceptedDropCount = 0))).offer.remainingDrops)
        assertEquals(1, assertIs<DropSetEligibilityResult.Eligible>(evaluate(completion = completion(acceptedDropCount = 1))).offer.remainingDrops)
        assertIneligible(DropSetIneligibleReason.DROP_LIMIT_REACHED, evaluate(completion = completion(acceptedDropCount = 2)))
    }

    @Test
    fun preservesSemanticAmrapAndDoesNotUseRawDetectorSamples() {
        val amrapIdentity = identity(setType = SetType.AMRAP)
        val result = assertIs<DropSetEligibilityResult.Eligible>(
            evaluate(
                completion = completion(
                    plannedSetType = SetType.AMRAP,
                    routineIdentity = amrapIdentity,
                    isAmrap = true,
                ),
                expectedLiveIdentity = amrapIdentity,
            ),
        )
        assertEquals(SetType.AMRAP, result.offer.routineIdentity.logicalSetKey.setKind)

        // Raw DELOAD_WARN/DELOAD_OCCURRED/position/velocity are deliberately absent
        // from the request. Without the terminal reason, otherwise eligible facts fail closed.
        assertIneligible(
            DropSetIneligibleReason.NOT_STALL_FAILURE,
            evaluate(completion = completion(reason = SetEndReason.UNKNOWN)),
        )
    }

    private fun evaluate(
        offerId: String = "offer",
        completion: SetExecutionCompletion = completion(),
        configuration: DropSetConfiguration = DropSetConfiguration(true, 1f),
        expectedLiveIdentity: RoutineExecutionIdentity = identity(),
        commandTemplate: WorkoutParameters = commandTemplate(),
    ) = policy.evaluate(request(offerId, completion, configuration, expectedLiveIdentity, commandTemplate))

    private fun request(
        offerId: String = "offer",
        completion: SetExecutionCompletion = completion(),
        configuration: DropSetConfiguration = DropSetConfiguration(true, 1f),
        expectedLiveIdentity: RoutineExecutionIdentity = identity(),
        commandTemplate: WorkoutParameters = commandTemplate(),
    ) = DropSetEligibilityRequest(offerId, completion, configuration, expectedLiveIdentity, commandTemplate)

    private fun completion(
        reason: SetEndReason = SetEndReason.STALL_FAILURE,
        routineIdentity: RoutineExecutionIdentity? = identity(),
        acceptedDropCount: Int = 0,
        plannedSetType: SetType = routineIdentity?.logicalSetKey?.setKind ?: SetType.STANDARD,
        programMode: ProgramMode = ProgramMode.OldSchool,
        isWarmup: Boolean = false,
        isEcho: Boolean = false,
        isJustLift: Boolean = false,
        isBodyweight: Boolean = false,
        isTimed: Boolean = false,
        isAmrap: Boolean = plannedSetType == SetType.AMRAP,
        isCable: Boolean = true,
    ) = SetExecutionCompletion(
        lease = lease(),
        reason = reason,
        routineIdentity = routineIdentity,
        attemptNumber = 1,
        acceptedDropCount = acceptedDropCount,
        plannedSetType = plannedSetType,
        programMode = programMode,
        programmedBaseWeightPerCableKg = 50f,
        configuredStartWeightPerCableKg = 50f,
        progressionKg = 0f,
        actualReps = 5,
        targetReps = if (isAmrap) null else 8,
        isWarmup = isWarmup,
        isEcho = isEcho,
        isJustLift = isJustLift,
        isBodyweight = isBodyweight,
        isTimed = isTimed,
        isAmrap = isAmrap,
        isCableExercise = isCable,
    )

    private fun identity(setType: SetType = SetType.STANDARD) = RoutineExecutionIdentity(
        profileId = "profile",
        routineId = "routine",
        routineSessionId = "routine-session",
        routineExerciseId = "occurrence",
        logicalSetKey = LogicalSetKey("routine-session", "occurrence", 0, setType),
        plannedSetId = "planned-set",
        exerciseIndex = 0,
        setIndex = 0,
    )

    private fun lease() = ExecutionLease(1, "execution-session", "profile", true, 8, false, false, false, false)

    private fun commandTemplate(reps: Int = 8) = WorkoutParameters(
        programMode = ProgramMode.OldSchool,
        reps = reps,
        weightPerCableKg = 50f,
        warmupReps = 0,
    )

    private fun assertIneligible(reason: DropSetIneligibleReason, result: DropSetEligibilityResult) {
        assertEquals(reason, assertIs<DropSetEligibilityResult.Ineligible>(result).reason)
    }
}
