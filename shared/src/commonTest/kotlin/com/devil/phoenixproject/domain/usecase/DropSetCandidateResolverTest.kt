package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.DropPercentage
import com.devil.phoenixproject.domain.model.DropSetCandidateInvalidReason
import com.devil.phoenixproject.domain.model.DropSetCandidateResolution
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutParameters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DropSetCandidateResolverTest {
    private val resolver = DropSetCandidateResolver()

    @Test
    fun resolvesStableTenTwentyThirtyChoicesFromConfiguredStart() {
        val expected = listOf(
            DropPercentage.TEN to 45f,
            DropPercentage.TWENTY to 40f,
            DropPercentage.THIRTY to 35f,
        )

        expected.forEach { (percentage, weight) ->
            val candidate = assertIs<DropSetCandidateResolution.Valid>(
                resolve(percentage = percentage),
            ).candidate
            assertEquals(weight, candidate.resolvedWeightPerCableKg)
            assertEquals(weight / 50f, candidate.resultingExerciseMultiplier, 0.0001f)
        }
    }

    @Test
    fun usesActualConfiguredStartInsteadOfCommandTemplateOrProgrammedBase() {
        val result = resolve(
            percentage = DropPercentage.TWENTY,
            start = 55f,
            base = 50f,
            template = commandTemplate(weight = 90f),
        )

        val candidate = assertIs<DropSetCandidateResolution.Valid>(result).candidate
        assertEquals(44f, candidate.resolvedWeightPerCableKg)
        assertEquals(0.88f, candidate.resultingExerciseMultiplier, 0.0001f)
    }

    @Test
    fun preservesExistingHalfKiloAndTieRounding() {
        assertEquals(
            9.5f,
            assertIs<DropSetCandidateResolution.Valid>(
                resolve(DropPercentage.TEN, start = 10.3f, base = 10.3f),
            ).candidate.resolvedWeightPerCableKg,
        )
        assertEquals(
            10f,
            assertIs<DropSetCandidateResolution.Valid>(
                resolve(DropPercentage.TWENTY, start = 12.8125f, base = 12.8125f),
            ).candidate.resolvedWeightPerCableKg,
        )
    }

    @Test
    fun acceptsExactFloorAndRejectsCrossingIt() {
        assertEquals(
            40f,
            assertIs<DropSetCandidateResolution.Valid>(
                resolve(DropPercentage.TWENTY, floor = 40f),
            ).candidate.resolvedWeightPerCableKg,
        )
        assertEquals(
            DropSetCandidateInvalidReason.BELOW_MINIMUM,
            assertIs<DropSetCandidateResolution.Invalid>(
                resolve(DropPercentage.THIRTY, floor = 40f),
            ).reason,
        )
    }

    @Test
    fun rejectsCandidateThatRoundsBackToFailedStart() {
        assertEquals(
            DropSetCandidateInvalidReason.NOT_LOWER,
            assertIs<DropSetCandidateResolution.Invalid>(
                resolve(DropPercentage.TEN, start = 1f, base = 1f, floor = 0.5f),
            ).reason,
        )
    }

    @Test
    fun rejectsNonfiniteAndNonpositiveInputs() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f).forEach { invalid ->
            assertEquals(
                DropSetCandidateInvalidReason.INVALID_CONFIGURED_START,
                assertIs<DropSetCandidateResolution.Invalid>(resolve(start = invalid)).reason,
            )
            assertEquals(
                DropSetCandidateInvalidReason.INVALID_PROGRAMMED_BASE,
                assertIs<DropSetCandidateResolution.Invalid>(resolve(base = invalid)).reason,
            )
            assertEquals(
                DropSetCandidateInvalidReason.INVALID_MINIMUM,
                assertIs<DropSetCandidateResolution.Invalid>(resolve(floor = invalid)).reason,
            )
        }
    }

    @Test
    fun returnsTypedInvalidReasonsInDocumentedPrecedence() {
        assertEquals(
            DropSetCandidateInvalidReason.INVALID_CONFIGURED_START,
            assertIs<DropSetCandidateResolution.Invalid>(
                resolve(start = Float.NaN, base = Float.NaN, floor = Float.NaN),
            ).reason,
        )
        assertEquals(
            DropSetCandidateInvalidReason.INVALID_PROGRAMMED_BASE,
            assertIs<DropSetCandidateResolution.Invalid>(
                resolve(start = 50f, base = Float.NaN, floor = Float.NaN),
            ).reason,
        )
        assertEquals(
            DropSetCandidateInvalidReason.INVALID_MINIMUM,
            assertIs<DropSetCandidateResolution.Invalid>(
                resolve(start = 50f, base = 50f, floor = Float.NaN),
            ).reason,
        )

        val invalidCommand = commandTemplate(reps = 0)
        assertEquals(
            DropSetCandidateInvalidReason.BELOW_MINIMUM,
            assertIs<DropSetCandidateResolution.Invalid>(
                resolve(DropPercentage.TEN, start = 1f, base = 1f, floor = 1.5f, template = invalidCommand),
            ).reason,
        )
        assertEquals(
            DropSetCandidateInvalidReason.INVALID_COMMAND,
            assertIs<DropSetCandidateResolution.Invalid>(
                resolve(DropPercentage.TEN, start = 1f, base = 1f, floor = 1f, template = invalidCommand),
            ).reason,
        )
        assertEquals(
            DropSetCandidateInvalidReason.NOT_LOWER,
            assertIs<DropSetCandidateResolution.Invalid>(
                resolve(DropPercentage.TEN, start = 1f, base = 1f, floor = 1f),
            ).reason,
        )
    }

    @Test
    fun enforcesHardwareBoundsIncludingTrainerPlusMaximum() {
        assertEquals(
            0.5f,
            assertIs<DropSetCandidateResolution.Valid>(
                resolve(DropPercentage.TEN, start = 0.6f, base = 0.6f, floor = 0.5f),
            ).candidate.resolvedWeightPerCableKg,
        )
        assertEquals(
            110f,
            assertIs<DropSetCandidateResolution.Valid>(
                resolve(DropPercentage.TEN, start = 122.25f, base = 122.25f, floor = 1f),
            ).candidate.resolvedWeightPerCableKg,
        )
        assertEquals(
            DropSetCandidateInvalidReason.INVALID_COMMAND,
            assertIs<DropSetCandidateResolution.Invalid>(
                resolve(DropPercentage.TEN, start = 123f, base = 123f, floor = 1f),
            ).reason,
        )
    }

    @Test
    fun validatesUnchangedRepAndProgressionCommandShape() {
        assertEquals(
            DropSetCandidateInvalidReason.INVALID_COMMAND,
            assertIs<DropSetCandidateResolution.Invalid>(
                resolve(template = commandTemplate(reps = 0)),
            ).reason,
        )
        assertEquals(
            DropSetCandidateInvalidReason.INVALID_COMMAND,
            assertIs<DropSetCandidateResolution.Invalid>(
                resolve(template = commandTemplate(progression = Float.NaN)),
            ).reason,
        )
    }

    @Test
    fun normalizesManualAdjustmentAndConsecutiveDropsIntoBaseMultiplier() {
        val manuallyAdjusted = assertIs<DropSetCandidateResolution.Valid>(
            resolve(DropPercentage.TWENTY, start = 55f, base = 50f),
        ).candidate
        assertEquals(0.88f, manuallyAdjusted.resultingExerciseMultiplier, 0.0001f)

        val first = assertIs<DropSetCandidateResolution.Valid>(resolve(DropPercentage.TWENTY)).candidate
        val second = assertIs<DropSetCandidateResolution.Valid>(
            resolve(DropPercentage.TWENTY, start = first.resolvedWeightPerCableKg),
        ).candidate
        assertEquals(40f, first.resolvedWeightPerCableKg)
        assertEquals(32f, second.resolvedWeightPerCableKg)
        assertEquals(0.64f, second.resultingExerciseMultiplier, 0.0001f)
    }

    @Test
    fun copiesOnlyCandidateWeightAndDoesNotMutateSourceTemplate() {
        val template = commandTemplate(weight = 77f).copy(
            activeRackItemIds = listOf("rack"),
            externalAddedLoadKg = 12f,
            counterweightKg = 3f,
            selectedExerciseId = "exercise",
        )
        val before = template.copy(activeRackItemIds = template.activeRackItemIds.toList())

        assertIs<DropSetCandidateResolution.Valid>(resolve(template = template))

        assertEquals(before, template)
        assertTrue(template.activeRackItemIds === before.activeRackItemIds || template.activeRackItemIds == before.activeRackItemIds)
    }

    private fun resolve(
        percentage: DropPercentage = DropPercentage.TWENTY,
        start: Float = 50f,
        base: Float = 50f,
        floor: Float = 1f,
        template: WorkoutParameters = commandTemplate(),
    ): DropSetCandidateResolution = resolver.resolve(
        DropSetCandidateRequest(
            percentage = percentage,
            failedConfiguredStartWeightPerCableKg = start,
            programmedBaseWeightPerCableKg = base,
            minimumWeightPerCableKg = floor,
            commandTemplate = template,
        ),
    )

    private fun commandTemplate(
        weight: Float = 50f,
        reps: Int = 8,
        progression: Float = 0f,
    ) = WorkoutParameters(
        programMode = ProgramMode.OldSchool,
        reps = reps,
        weightPerCableKg = weight,
        warmupReps = 0,
        progressionRegressionKg = progression,
    )
}
