package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.RoutineExercise
import kotlin.test.Test
import kotlin.test.assertEquals

class RoutineSetWeightResolverTest {

    private val exercise = Exercise(
        id = "resolver-test-exercise",
        name = "Resolver Test Exercise",
        muscleGroup = "Test",
    )

    @Test
    fun `resolves programmed, scaled, and manually adjusted routine set weights`() {
        data class Case(
            val name: String,
            val routineExercise: RoutineExercise,
            val setIndex: Int,
            val currentPrKg: Float? = null,
            val occurrenceMultiplier: Float = 1f,
            val manualAdjustmentPerCableKg: Float? = null,
            val expectedKg: Float,
        )

        val fixedWeight = routineExercise(weightPerCableKg = 19.25f)
        val perSetWeight = routineExercise(
            weightPerCableKg = 19.25f,
            setWeightsPerCableKg = listOf(20.3f, 21.7f),
        )
        val percentageWeight = routineExercise(
            weightPerCableKg = 19.25f,
            setWeightsPerCableKg = listOf(20.3f, 21.7f),
            usePercentOfPR = true,
            weightPercentOfPR = 80,
        )
        val perSetPercentage = percentageWeight.copy(setWeightsPercentOfPR = listOf(70, 80))
        val amrapSuperset = perSetWeight.copy(
            setReps = listOf(null),
            isAMRAP = true,
            supersetId = "superset-a",
            orderInSuperset = 1,
        )

        val cases = listOf(
            Case("fixed absolute weight is not rounded", fixedWeight, 0, expectedKg = 19.25f),
            Case("per-set absolute weight is not rounded", perSetWeight, 1, expectedKg = 21.7f),
            Case("percentage of PR uses half-kilogram rounding", percentageWeight, 0, currentPrKg = 47f, expectedKg = 37.5f),
            Case("percentage without PR falls back to the stored per-set weight", percentageWeight, 1, expectedKg = 21.7f),
            Case("set-specific percentage takes precedence over base percentage", perSetPercentage, 0, currentPrKg = 47f, expectedKg = 33f),
            Case("AMRAP superset uses its selected set weight", amrapSuperset, 0, expectedKg = 20.3f),
            Case("occurrence multiplier is applied before percentage rounding", percentageWeight, 0, currentPrKg = 47f, occurrenceMultiplier = 0.5f, expectedKg = 19f),
            Case("manual adjustment is applied last without clamping", percentageWeight, 0, currentPrKg = 47f, occurrenceMultiplier = 1.1f, manualAdjustmentPerCableKg = 200f, expectedKg = 241.5f),
        )

        cases.forEach { case ->
            val actualKg: Float = RoutineSetWeightResolver(
                RoutineSetWeightRequest(
                    exercise = case.routineExercise,
                    setIndex = case.setIndex,
                    currentPrKg = case.currentPrKg,
                    occurrenceMultiplier = case.occurrenceMultiplier,
                    manualAdjustmentPerCableKg = case.manualAdjustmentPerCableKg,
                ),
            )
            assertEquals(
                case.expectedKg,
                actualKg,
                case.name,
            )
        }
    }

    @Test
    fun `uses base absolute weight when a valid PR has a disabled selected set percentage`() {
        val routineExercise = routineExercise(
            weightPerCableKg = 19.25f,
            setWeightsPerCableKg = listOf(20.3f, 21.7f),
            usePercentOfPR = true,
        ).copy(setWeightsPercentOfPR = listOf(80, 0))

        val actualKg = RoutineSetWeightResolver(
            RoutineSetWeightRequest(
                exercise = routineExercise,
                setIndex = 1,
                currentPrKg = 47f,
            ),
        )

        assertEquals(19.25f, actualKg)
    }

    private fun routineExercise(
        weightPerCableKg: Float,
        setWeightsPerCableKg: List<Float> = emptyList(),
        usePercentOfPR: Boolean = false,
        weightPercentOfPR: Int = 80,
    ) = RoutineExercise(
        id = "resolver-test-routine-exercise",
        exercise = exercise,
        orderIndex = 0,
        setReps = listOf(10, 8),
        weightPerCableKg = weightPerCableKg,
        setWeightsPerCableKg = setWeightsPerCableKg,
        usePercentOfPR = usePercentOfPR,
        weightPercentOfPR = weightPercentOfPR,
    )
}
