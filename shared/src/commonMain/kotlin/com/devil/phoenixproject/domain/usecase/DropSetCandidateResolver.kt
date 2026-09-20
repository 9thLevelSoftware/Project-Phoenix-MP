package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.DropPercentage
import com.devil.phoenixproject.domain.model.DropSetCandidate
import com.devil.phoenixproject.domain.model.DropSetCandidateInvalidReason
import com.devil.phoenixproject.domain.model.DropSetCandidateResolution
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.util.Constants
import com.devil.phoenixproject.util.UnitConverter
import com.devil.phoenixproject.util.WorkoutCommandValidator

data class DropSetCandidateRequest(
    val percentage: DropPercentage,
    val failedConfiguredStartWeightPerCableKg: Float,
    val programmedBaseWeightPerCableKg: Float,
    val minimumWeightPerCableKg: Float,
    val commandTemplate: WorkoutParameters,
)

class DropSetCandidateResolver {
    fun resolve(request: DropSetCandidateRequest): DropSetCandidateResolution {
        val start = request.failedConfiguredStartWeightPerCableKg
        if (!start.isFinite() || start <= 0f) {
            return DropSetCandidateResolution.Invalid(DropSetCandidateInvalidReason.INVALID_CONFIGURED_START)
        }
        val base = request.programmedBaseWeightPerCableKg
        if (!base.isFinite() || base <= 0f) {
            return DropSetCandidateResolution.Invalid(DropSetCandidateInvalidReason.INVALID_PROGRAMMED_BASE)
        }
        val minimum = request.minimumWeightPerCableKg
        if (!minimum.isFinite() || minimum <= 0f) {
            return DropSetCandidateResolution.Invalid(DropSetCandidateInvalidReason.INVALID_MINIMUM)
        }

        val candidateWeight = UnitConverter.roundToMachineIncrement(
            start * (1f - request.percentage.fraction),
        )
        if (candidateWeight < minimum) {
            return DropSetCandidateResolution.Invalid(DropSetCandidateInvalidReason.BELOW_MINIMUM)
        }
        val candidateCommand = request.commandTemplate.copy(weightPerCableKg = candidateWeight)
        // A drop-set candidate is always LOWER than the weight that just failed, so a model
        // ceiling can never be the reason it is rejected here. Use the absolute hardware
        // maximum; the send site applies the connected model's ceiling.
        if (WorkoutCommandValidator.validateProgramParams(
                candidateCommand,
                Constants.MAX_WEIGHT_PER_CABLE_KG,
            ).isFailure
        ) {
            return DropSetCandidateResolution.Invalid(DropSetCandidateInvalidReason.INVALID_COMMAND)
        }
        if (candidateWeight >= start) {
            return DropSetCandidateResolution.Invalid(DropSetCandidateInvalidReason.NOT_LOWER)
        }

        return DropSetCandidateResolution.Valid(
            DropSetCandidate(
                percentage = request.percentage,
                resolvedWeightPerCableKg = candidateWeight,
                resultingExerciseMultiplier = candidateWeight / base,
            ),
        )
    }
}
