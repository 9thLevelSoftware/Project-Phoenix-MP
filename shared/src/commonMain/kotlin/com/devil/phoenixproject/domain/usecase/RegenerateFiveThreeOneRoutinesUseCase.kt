package com.devil.phoenixproject.domain.usecase

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.FiveThreeOneWeeks
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.computeFiveThreeOneSetWeightsForWeek

class RegenerateFiveThreeOneRoutinesUseCase(
    private val trainingCycleRepository: TrainingCycleRepository,
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
) {
    suspend fun execute(cycleId: String, targetWeek: Int, bumpTrainingMax: Boolean) {
        val cycle = trainingCycleRepository.getCycleById(cycleId) ?: return
        val matchedLiftIds = linkedSetOf<String>()

        for (day in cycle.days) {
            val routineId = day.routineId ?: continue
            val routine = workoutRepository.getRoutineById(routineId)
            if (routine == null) {
                Logger.w { "5/3/1 regeneration skipped missing routine: cycleId=$cycleId routineId=$routineId" }
                continue
            }

            val updatedRoutine = regenerateRoutineForWeek(routine, targetWeek, matchedLiftIds)
            if (updatedRoutine != routine) {
                workoutRepository.updateRoutine(updatedRoutine)
            }
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
    }

    private fun regenerateRoutineForWeek(
        routine: Routine,
        targetWeek: Int,
        matchedLiftIds: MutableSet<String>,
    ): Routine {
        var changed = false
        val targetSets = FiveThreeOneWeeks.forWeek(targetWeek)
        val targetPercentages = computeFiveThreeOneSetWeightsForWeek(targetWeek)

        val updatedExercises = routine.exercises.map { exercise ->
            if (!exercise.isCurrentFiveThreeOneMainLift()) {
                exercise
            } else {
                matchedLiftIds += exercise.exercise.id.orEmpty()
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

        return if (changed) {
            routine.copy(exercises = updatedExercises)
        } else {
            routine
        }
    }

    private fun RoutineExercise.isCurrentFiveThreeOneMainLift(): Boolean {
        val exerciseId = exercise.id ?: return false
        return usePercentOfPR &&
            exerciseId in MAIN_LIFT_IDS &&
            setWeightsPercentOfPR in FIVE_THREE_ONE_PERCENTAGES
    }

    private companion object {
        const val BENCH_ID = "ZZ92N8QsBdp6HCh3"
        const val SHOULDER_PRESS_ID = "0040d53f-85c7-4564-b14e-9b38c979b461"
        const val SQUAT_ID = "UjIGHxCav-lS9B2I"
        const val DEADLIFT_ID = "e64c7837-52e2-4b97-b771-cf08ab861af1"

        val MAIN_LIFT_IDS = setOf(BENCH_ID, SHOULDER_PRESS_ID, SQUAT_ID, DEADLIFT_ID)
        val UPPER_LIFT_IDS = setOf(BENCH_ID, SHOULDER_PRESS_ID)
        val FIVE_THREE_ONE_PERCENTAGES = setOf(
            computeFiveThreeOneSetWeightsForWeek(1),
            computeFiveThreeOneSetWeightsForWeek(2),
            computeFiveThreeOneSetWeightsForWeek(3),
            computeFiveThreeOneSetWeightsForWeek(4),
        )

        const val UPPER_ONE_REP_MAX_BUMP_KG = 1.25f / 0.9f
        const val LOWER_ONE_REP_MAX_BUMP_KG = 2.5f / 0.9f
    }
}
