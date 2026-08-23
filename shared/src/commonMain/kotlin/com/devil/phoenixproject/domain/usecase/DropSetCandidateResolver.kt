package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.DropPercentage
import com.devil.phoenixproject.domain.model.DropSetCandidate
import com.devil.phoenixproject.domain.model.DropSetCandidateInvalidReason
import com.devil.phoenixproject.domain.model.DropSetCandidateResolution
import com.devil.phoenixproject.domain.model.PhoenixModel
import com.devil.phoenixproject.domain.model.WorkoutParameters
import com.devil.phoenixproject.util.UnitConverter
import com.devil.phoenixproject.util.WorkoutCommandValidator

data class DropSetCandidateRequest(
    val percentage: DropPercentage,
    val failedConfiguredStartWeightPerCableKg: Float,
    val programmedBaseWeightPerCableKg: Float,
    val minimumWeightPerCableKg: Float,
    val commandTemplate: WorkoutParameters,
    val hardwareModel: PhoenixModel,
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
        if (WorkoutCommandValidator.validateProgramParams(candidateCommand, request.hardwareModel).isFailure) {
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
