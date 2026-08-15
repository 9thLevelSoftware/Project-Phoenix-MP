package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.DropPercentage
import com.devil.phoenixproject.domain.model.DropSetCandidateResolution
import com.devil.phoenixproject.domain.model.DropSetConfiguration
import com.devil.phoenixproject.domain.model.DropSetEligibilityResult
import com.devil.phoenixproject.domain.model.DropSetFeatureGate
import com.devil.phoenixproject.domain.model.DropSetIneligibleReason
import com.devil.phoenixproject.domain.model.DropSetOffer
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExecutionIdentity
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.presentation.manager.SetExecutionCompletion

internal data class DropSetEligibilityRequest(
    val offerId: String,
    val completion: SetExecutionCompletion,
    val configuration: DropSetConfiguration,
    val expectedLiveIdentity: RoutineExecutionIdentity?,
    val commandTemplate: WorkoutParameters,
)

internal class DropSetEligibilityPolicy(
    private val featureGate: DropSetFeatureGate,
    private val candidateResolver: DropSetCandidateResolver,
) {
    fun evaluate(request: DropSetEligibilityRequest): DropSetEligibilityResult {
        if (!featureGate.isEnabled()) return ineligible(DropSetIneligibleReason.FEATURE_GATED)

        val completion = request.completion
        if (completion.reason != SetEndReason.STALL_FAILURE) return ineligible(DropSetIneligibleReason.NOT_STALL_FAILURE)
        if (!request.configuration.enabled) return ineligible(DropSetIneligibleReason.DISABLED)
        val minimum = request.configuration.minimumWeightPerCableKg
        if (minimum == null || !minimum.isFinite() || minimum <= 0f) {
            return ineligible(DropSetIneligibleReason.INVALID_MINIMUM)
        }
        if (completion.isWarmup) return ineligible(DropSetIneligibleReason.WARMUP)
        if (completion.isEcho) return ineligible(DropSetIneligibleReason.ECHO)
        if (completion.isJustLift) return ineligible(DropSetIneligibleReason.JUST_LIFT)
        if (completion.isBodyweight) return ineligible(DropSetIneligibleReason.BODYWEIGHT)
        if (completion.programMode != ProgramMode.OldSchool) return ineligible(DropSetIneligibleReason.NOT_OLD_SCHOOL)
        if (!completion.isCableExercise || completion.isTimed) return ineligible(DropSetIneligibleReason.NOT_CABLE_WORKING_SET)
        if (completion.acceptedDropCount >= MAX_ACCEPTED_DROPS) return ineligible(DropSetIneligibleReason.DROP_LIMIT_REACHED)

        val identity = completion.routineIdentity ?: return ineligible(DropSetIneligibleReason.IDENTITY_MISMATCH)
        val liveIdentity = request.expectedLiveIdentity ?: return ineligible(DropSetIneligibleReason.IDENTITY_MISMATCH)
        if (!identity.matches(liveIdentity) ||
            completion.plannedSetType != identity.logicalSetKey.setKind ||
            identity.logicalSetKey.setIndex != identity.setIndex ||
            identity.logicalSetKey.routineSessionId != identity.routineSessionId ||
            identity.logicalSetKey.routineExerciseId != identity.routineExerciseId
        ) {
            return ineligible(DropSetIneligibleReason.IDENTITY_MISMATCH)
        }

        val candidates = DropPercentage.entries.mapNotNull { percentage ->
            when (
                val resolution = candidateResolver.resolve(
                    DropSetCandidateRequest(
                        percentage = percentage,
                        failedConfiguredStartWeightPerCableKg = completion.configuredStartWeightPerCableKg,
                        programmedBaseWeightPerCableKg = completion.programmedBaseWeightPerCableKg,
                        minimumWeightPerCableKg = minimum,
                        commandTemplate = request.commandTemplate,
                    ),
                )
            ) {
                is DropSetCandidateResolution.Valid -> resolution.candidate
                is DropSetCandidateResolution.Invalid -> null
            }
        }
        if (candidates.isEmpty()) return ineligible(DropSetIneligibleReason.NO_VALID_CANDIDATE)

        return DropSetEligibilityResult.Eligible(
            DropSetOffer(
                offerId = request.offerId,
                routineIdentity = identity,
                candidates = candidates,
                remainingDrops = MAX_ACCEPTED_DROPS - completion.acceptedDropCount,
            ),
        )
    }

    private fun RoutineExecutionIdentity.matches(live: RoutineExecutionIdentity): Boolean =
        profileId == live.profileId &&
            routineId == live.routineId &&
            routineSessionId == live.routineSessionId &&
            routineExerciseId == live.routineExerciseId &&
            logicalSetKey == live.logicalSetKey &&
            exerciseIndex == live.exerciseIndex &&
            setIndex == live.setIndex &&
            (plannedSetId == null || plannedSetId == live.plannedSetId)

    private fun ineligible(reason: DropSetIneligibleReason) = DropSetEligibilityResult.Ineligible(reason)

    private companion object {
        const val MAX_ACCEPTED_DROPS = 2
    }
}
