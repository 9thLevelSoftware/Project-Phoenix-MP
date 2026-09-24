package com.devil.phoenixproject.domain.usecase

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ProfileExerciseBaselineRepository
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.data.repository.WorkoutRepository
import com.devil.phoenixproject.domain.model.CycleDay
import com.devil.phoenixproject.domain.model.FiveThreeOneRoutineDetector
import com.devil.phoenixproject.domain.model.FiveThreeOneWeeks
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.computeFiveThreeOneSetWeightsForWeek

class RegenerateFiveThreeOneRoutinesUseCase(
    private val trainingCycleRepository: TrainingCycleRepository,
    private val workoutRepository: WorkoutRepository,
    private val baselineRepository: ProfileExerciseBaselineRepository,
) {
    suspend fun execute(cycleId: String, targetWeek: Int, bumpTrainingMax: Boolean): Boolean {
        val cycle = trainingCycleRepository.getCycleById(cycleId) ?: return false
        val matchedLiftIds = linkedSetOf<String>()
        val storedLiftIdsByCanonical = linkedMapOf<String, String>()
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
                    onMatchedLift = { canonicalId, storedId ->
                        matchedLiftIds += canonicalId
                        if (canonicalId !in storedLiftIdsByCanonical) {
                            storedLiftIdsByCanonical[canonicalId] = storedId
                        }
                    },
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

        val missingMainLiftIds = FiveThreeOneRoutineDetector.MAIN_LIFT_IDS - matchedLiftIds
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
            for (canonicalId in matchedLiftIds) {
                val exerciseId = storedLiftIdsByCanonical[canonicalId] ?: canonicalId
                val bump = if (canonicalId in FiveThreeOneRoutineDetector.UPPER_LIFT_IDS) {
                    UPPER_ONE_REP_MAX_BUMP_KG
                } else {
                    LOWER_ONE_REP_MAX_BUMP_KG
                }
                val incremented = baselineRepository.increment(
                    profileId = cycle.profileId,
                    exerciseId = exerciseId,
                    incrementKg = bump,
                    updatedAt = com.devil.phoenixproject.domain.model.currentTimeMillis(),
                )
                if (incremented == null) {
                    Logger.w {
                        "5/3/1 TM bump skipped null scoped baseline: " +
                            "profileId=${cycle.profileId} exerciseId=$exerciseId"
                    }
                }
            }
        }

        trainingCycleRepository.updateWeekNumber(cycleId, targetWeek)
        return true
    }

    private fun regenerateRoutineForWeek(
        day: CycleDay,
        routine: Routine,
        targetWeek: Int,
        onMatchedLift: (canonicalId: String, storedId: String) -> Unit,
    ): Routine? {
        val mainLiftMatch = when (val resolution = FiveThreeOneRoutineDetector.resolveMainLift(routine.exercises)) {
            FiveThreeOneRoutineDetector.MainLiftResolution.None -> return null
            is FiveThreeOneRoutineDetector.MainLiftResolution.Found -> resolution
            is FiveThreeOneRoutineDetector.MainLiftResolution.DuplicateLift -> throw IllegalStateException(
                "5/3/1 regeneration found multiple matches for liftId=${resolution.canonicalId} routineId=${routine.id} dayNumber=${day.dayNumber} matches=${resolution.indexes.joinToString(",")}",
            )
            is FiveThreeOneRoutineDetector.MainLiftResolution.MultipleCandidates -> throw IllegalStateException(
                "5/3/1 regeneration found multiple possible main lifts: routineId=${routine.id} dayNumber=${day.dayNumber} matches=${resolution.candidates.joinToString(",") { "${it.canonicalId}@${it.index}" }}",
            )
        }

        onMatchedLift(mainLiftMatch.canonicalId, mainLiftMatch.storedId)
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

    private companion object {
        const val UPPER_ONE_REP_MAX_BUMP_KG = 1.25f / 0.9f
        const val LOWER_ONE_REP_MAX_BUMP_KG = 2.5f / 0.9f
    }
}
