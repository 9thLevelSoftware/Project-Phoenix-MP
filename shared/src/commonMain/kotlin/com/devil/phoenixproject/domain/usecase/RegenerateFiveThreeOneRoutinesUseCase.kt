package com.devil.phoenixproject.domain.usecase

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.FiveThreeOneWeeks
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.computeFiveThreeOneSetWeightsForWeek

class RegenerateFiveThreeOneRoutinesUseCase(
    private val trainingCycleRepository: TrainingCycleRepository,
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
) {
    suspend fun execute(cycleId: String, targetWeek: Int, bumpTrainingMax: Boolean): Boolean {
        val cycle = trainingCycleRepository.getCycleById(cycleId) ?: return false
        val matchedLiftIds = linkedSetOf<String>()
        var matchedAnyExpectedMainLift = false
        var failedExpectedMainLift = false
        val routineUpdates = mutableListOf<Pair<Routine, Routine>>()

        for (day in cycle.days.sortedBy { it.dayNumber }) {
            val expectedMainLiftId = day.expectedFiveThreeOneMainLiftId()
            if (expectedMainLiftId == null) {
                if (!day.isRestDay && day.routineId != null) {
                    Logger.w {
                        "5/3/1 regeneration skipped unrecognized cycle day: cycleId=$cycleId dayNumber=${day.dayNumber} dayName=${day.name}"
                    }
                    failedExpectedMainLift = true
                }
                continue
            }

            val routineId = day.routineId
            if (routineId == null) {
                Logger.w {
                    "5/3/1 regeneration could not find routine for expected main lift: cycleId=$cycleId dayNumber=${day.dayNumber} expectedExerciseId=$expectedMainLiftId"
                }
                failedExpectedMainLift = true
                continue
            }

            val routine = workoutRepository.getRoutineById(routineId)
            if (routine == null) {
                Logger.w { "5/3/1 regeneration skipped missing routine: cycleId=$cycleId routineId=$routineId" }
                failedExpectedMainLift = true
                continue
            }

            val updatedRoutine = regenerateRoutineForWeek(
                day = day,
                routine = routine,
                expectedMainLiftId = expectedMainLiftId,
                targetWeek = targetWeek,
            )
            if (updatedRoutine == null) {
                Logger.w {
                    "5/3/1 regeneration could not find expected main lift: cycleId=$cycleId dayNumber=${day.dayNumber} routineId=$routineId expectedExerciseId=$expectedMainLiftId"
                }
                failedExpectedMainLift = true
                continue
            }

            matchedAnyExpectedMainLift = true
            matchedLiftIds += expectedMainLiftId
            if (updatedRoutine != routine) {
                routineUpdates += routine to updatedRoutine
            }
        }

        if (failedExpectedMainLift || !matchedAnyExpectedMainLift) {
            Logger.w {
                "5/3/1 regeneration aborted before advancing week sentinel: cycleId=$cycleId targetWeek=$targetWeek"
            }
            return false
        }

        for ((_, updatedRoutine) in routineUpdates) {
            workoutRepository.updateRoutine(updatedRoutine)
        }

        if (bumpTrainingMax) {
            for (exerciseId in matchedLiftIds) {
                val exercise = exerciseRepository.getExerciseById(exerciseId)
                if (exercise == null) {
                    Logger.w { "5/3/1 TM bump skipped missing exercise row: exerciseId=$exerciseId" }
                    continue
                }

                val currentOneRepMax = exercise.oneRepMaxKg
                if (currentOneRepMax == null) {
                    Logger.w { "5/3/1 TM bump skipped null oneRepMax: exerciseId=$exerciseId" }
                    continue
                }

                val bump = if (exerciseId in UPPER_LIFT_IDS) {
                    UPPER_ONE_REP_MAX_BUMP_KG
                } else {
                    LOWER_ONE_REP_MAX_BUMP_KG
                }
                exerciseRepository.updateOneRepMax(exerciseId, currentOneRepMax + bump)
            }
        }

        trainingCycleRepository.updateWeekNumber(cycleId, targetWeek)
        return true
    }

    private fun regenerateRoutineForWeek(
        day: CycleDay,
        routine: Routine,
        expectedMainLiftId: String,
        targetWeek: Int,
    ): Routine? {
        val matchingIndexes = routine.exercises.mapIndexedNotNull { index, exercise ->
            if (exercise.matchesExpectedFiveThreeOneMainLift(expectedMainLiftId)) index else null
        }
        if (matchingIndexes.isEmpty()) {
            return null
        }
        if (matchingIndexes.size > 1) {
            Logger.w {
                "5/3/1 regeneration found multiple expected main lift matches; rewriting first match only: routineId=${routine.id} dayNumber=${day.dayNumber} expectedExerciseId=$expectedMainLiftId matches=${matchingIndexes.joinToString(",")}"
            }
        }

        val mainLiftIndex = matchingIndexes.first()
        var changed = false
        val targetSets = FiveThreeOneWeeks.forWeek(targetWeek)
        val targetPercentages = computeFiveThreeOneSetWeightsForWeek(targetWeek)

        val updatedExercises = routine.exercises.mapIndexed { index, exercise ->
            if (index != mainLiftIndex) {
                exercise
            } else {
                val updatedExercise = exercise.copy(
                    setReps = targetSets.map { it.targetReps },
                    isAMRAP = targetSets.any { it.isAmrap },
                    setWeightsPercentOfPR = targetPercentages,
                )
                if (updatedExercise != exercise) {
                    changed = true
                }
                updatedExercise
            }
        }

        return if (changed) routine.copy(exercises = updatedExercises) else routine
    }

    private fun CycleDay.expectedFiveThreeOneMainLiftId(): String? {
        val normalizedName = name?.trim()?.lowercase().orEmpty()
        return when {
            "deadlift" in normalizedName -> DEADLIFT_ID
            "squat" in normalizedName -> SQUAT_ID
            "bench" in normalizedName -> BENCH_ID
            "press" in normalizedName -> SHOULDER_PRESS_ID
            else -> null
        }
    }

    private fun RoutineExercise.matchesExpectedFiveThreeOneMainLift(expectedMainLiftId: String): Boolean {
        val exerciseId = exercise.id ?: return false
        return usePercentOfPR && exerciseId == expectedMainLiftId
    }

    private companion object {
        const val BENCH_ID = "ZZ92N8QsBdp6HCh3"
        const val SHOULDER_PRESS_ID = "0040d53f-85c7-4564-b14e-9b38c979b461"
        const val SQUAT_ID = "UjIGHxCav-lS9B2I"
        const val DEADLIFT_ID = "e64c7837-52e2-4b97-b771-cf08ab861af1"

        val UPPER_LIFT_IDS = setOf(BENCH_ID, SHOULDER_PRESS_ID)

        const val UPPER_ONE_REP_MAX_BUMP_KG = 1.25f / 0.9f
        const val LOWER_ONE_REP_MAX_BUMP_KG = 2.5f / 0.9f
    }
}
