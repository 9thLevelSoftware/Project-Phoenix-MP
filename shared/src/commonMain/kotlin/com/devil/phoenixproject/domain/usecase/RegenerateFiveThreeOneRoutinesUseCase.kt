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
        var failedMainLiftRegeneration = false
        val routineUpdates = mutableListOf<Pair<Routine, Routine>>()

        for (day in cycle.days.sortedBy { it.dayNumber }) {
            if (day.isRestDay) {
                continue
            }

            val routineId = day.routineId
            if (routineId == null) {
                Logger.w {
                    "5/3/1 regeneration could not find routine for training day: cycleId=$cycleId dayNumber=${day.dayNumber}"
                }
                failedMainLiftRegeneration = true
                continue
            }

            val routine = workoutRepository.getRoutineById(routineId)
            if (routine == null) {
                Logger.w { "5/3/1 regeneration skipped missing routine: cycleId=$cycleId routineId=$routineId" }
                failedMainLiftRegeneration = true
                continue
            }

            val updatedRoutine = try {
                regenerateRoutineForWeek(
                    day = day,
                    routine = routine,
                    targetWeek = targetWeek,
                    onMatchedLift = { matchedLiftIds += it },
                )
            } catch (e: IllegalStateException) {
                Logger.w(e) { "5/3/1 regeneration found ambiguous main lift state: cycleId=$cycleId routineId=$routineId" }
                failedMainLiftRegeneration = true
                continue
            }
            if (updatedRoutine == null) {
                Logger.w {
                    "5/3/1 regeneration skipped routine with no 5/3/1 main lift: cycleId=$cycleId dayNumber=${day.dayNumber} routineId=$routineId"
                }
                continue
            }

            if (updatedRoutine != routine) {
                routineUpdates += routine to updatedRoutine
            }
        }

        val missingMainLiftIds = MAIN_LIFT_IDS - matchedLiftIds
        if (failedMainLiftRegeneration || missingMainLiftIds.isNotEmpty()) {
            Logger.w {
                "5/3/1 regeneration aborted before advancing week sentinel: cycleId=$cycleId targetWeek=$targetWeek missingLiftIds=${missingMainLiftIds.joinToString(",")}"
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
        targetWeek: Int,
        onMatchedLift: (String) -> Unit,
    ): Routine? {
        val matches = routine.exercises.mapIndexedNotNull { index, exercise ->
            exercise.fiveThreeOneMainLiftId()?.let { liftId ->
                MainLiftMatch(
                    index = index,
                    liftId = liftId,
                    hasFiveThreeOneSetShape = exercise.hasFiveThreeOneSetShape(),
                )
            }
        }
        if (matches.isEmpty()) {
            return null
        }

        val duplicateLift = matches
            .groupBy { it.liftId }
            .firstNotNullOfOrNull { (liftId, liftMatches) ->
                liftMatches.takeIf { it.size > 1 }?.let { liftId to it }
            }
        if (duplicateLift != null) {
            val (liftId, duplicateMatches) = duplicateLift
            throw IllegalStateException(
                "5/3/1 regeneration found multiple matches for liftId=$liftId routineId=${routine.id} dayNumber=${day.dayNumber} matches=${duplicateMatches.joinToString(",") { it.index.toString() }}",
            )
        }

        val shapedMatches = matches.filter { it.hasFiveThreeOneSetShape }
        val mainLiftMatch = when {
            matches.size == 1 -> matches.single()
            shapedMatches.size == 1 -> shapedMatches.single()
            else -> {
                throw IllegalStateException(
                    "5/3/1 regeneration found multiple possible main lifts: routineId=${routine.id} dayNumber=${day.dayNumber} matches=${matches.joinToString(",") { "${it.liftId}@${it.index}" }}",
                )
            }
        }

        onMatchedLift(mainLiftMatch.liftId)
        val mainLiftIndex = mainLiftMatch.index
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

    private fun RoutineExercise.fiveThreeOneMainLiftId(): String? {
        val exerciseId = exercise.id ?: return null
        return if (usePercentOfPR && exerciseId in MAIN_LIFT_IDS) exerciseId else null
    }

    private fun RoutineExercise.hasFiveThreeOneSetShape(): Boolean = FIVE_THREE_ONE_SET_SHAPES.any { shape ->
        setReps == shape.reps && isAMRAP == shape.isAmrap
    }

    private data class MainLiftMatch(
        val index: Int,
        val liftId: String,
        val hasFiveThreeOneSetShape: Boolean,
    )

    private data class FiveThreeOneSetShape(
        val reps: List<Int?>,
        val isAmrap: Boolean,
    )

    private companion object {
        const val BENCH_ID = "ZZ92N8QsBdp6HCh3"
        const val SHOULDER_PRESS_ID = "0040d53f-85c7-4564-b14e-9b38c979b461"
        const val SQUAT_ID = "UjIGHxCav-lS9B2I"
        const val DEADLIFT_ID = "e64c7837-52e2-4b97-b771-cf08ab861af1"

        val UPPER_LIFT_IDS = setOf(BENCH_ID, SHOULDER_PRESS_ID)
        val MAIN_LIFT_IDS = setOf(BENCH_ID, SHOULDER_PRESS_ID, SQUAT_ID, DEADLIFT_ID)
        val FIVE_THREE_ONE_SET_SHAPES = listOf(
            FiveThreeOneWeeks.WEEK_1,
            FiveThreeOneWeeks.WEEK_2,
            FiveThreeOneWeeks.WEEK_3,
            FiveThreeOneWeeks.WEEK_4_DELOAD,
        ).map { sets ->
            FiveThreeOneSetShape(
                reps = sets.map { it.targetReps },
                isAmrap = sets.any { it.isAmrap },
            )
        }

        const val UPPER_ONE_REP_MAX_BUMP_KG = 1.25f / 0.9f
        const val LOWER_ONE_REP_MAX_BUMP_KG = 2.5f / 0.9f
    }
}
