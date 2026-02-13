package com.devil.phoenixproject.presentation.manager

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.domain.model.Badge
import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.PRCelebrationEvent
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Manages gamification events: personal record checking and badge awarding.
 * Extracted from MainViewModel.saveWorkoutSession() during monolith decomposition.
 */
class GamificationManager(
    private val gamificationRepository: GamificationRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val exerciseRepository: ExerciseRepository,
    private val hapticEvents: MutableSharedFlow<HapticEvent>,
    private val scope: CoroutineScope
) {
    private val _prCelebrationEvent = MutableSharedFlow<PRCelebrationEvent>()
    val prCelebrationEvent: SharedFlow<PRCelebrationEvent> = _prCelebrationEvent.asSharedFlow()

    private val _badgeEarnedEvents = MutableSharedFlow<List<Badge>>()
    val badgeEarnedEvents: SharedFlow<List<Badge>> = _badgeEarnedEvents.asSharedFlow()

    /**
     * Check for PRs and badges after a workout session is saved.
     * Extracted from MainViewModel.saveWorkoutSession() L3494-3564.
     * @return true if a celebration sound will play (to avoid sound stacking)
     */
    suspend fun processPostSaveEvents(
        exerciseId: String?,
        workingReps: Int,
        measuredWeightKg: Float,
        programMode: ProgramMode,
        isJustLift: Boolean,
        isEchoMode: Boolean
    ): Boolean {
        var hasCelebrationSound = false

        // Check for personal record (skip for Just Lift and Echo modes)
        // Uses mode-specific PR lookup to track PRs separately per workout mode (#111)
        exerciseId?.let { exId ->
            if (workingReps > 0 && !isJustLift && !isEchoMode) {
                try {
                    val workoutMode = programMode.displayName
                    val timestamp = currentTimeMillis()

                    // Use personalRecordRepository for mode-specific PR tracking
                    // This returns which PR types were actually broken (weight, volume, or both)
                    val result = personalRecordRepository.updatePRsIfBetter(
                        exerciseId = exId,
                        weightPerCableKg = measuredWeightKg,
                        reps = workingReps,
                        workoutMode = workoutMode,
                        timestamp = timestamp
                    )

                    // Only celebrate if an actual PR was broken
                    result.onSuccess { brokenPRs ->
                        if (brokenPRs.isNotEmpty()) {
                            hasCelebrationSound = true // PR dialog will play sound via callback
                            val exercise = exerciseRepository.getExerciseById(exId)
                            val prTypeDescription = when {
                                brokenPRs.contains(PRType.MAX_WEIGHT) && brokenPRs.contains(PRType.MAX_VOLUME) -> "Weight & Volume"
                                brokenPRs.contains(PRType.MAX_WEIGHT) -> "Weight"
                                brokenPRs.contains(PRType.MAX_VOLUME) -> "Volume"
                                else -> ""
                            }
                            _prCelebrationEvent.emit(
                                PRCelebrationEvent(
                                    exerciseName = exercise?.name ?: "Unknown Exercise",
                                    weightPerCableKg = measuredWeightKg,
                                    reps = workingReps,
                                    workoutMode = workoutMode,
                                    brokenPRTypes = brokenPRs
                                )
                            )
                            Logger.d("NEW PR ($prTypeDescription): ${exercise?.name} - $measuredWeightKg kg x $workingReps reps in $workoutMode mode")
                        }
                    }.onFailure { e ->
                        Logger.e(e) { "Error updating PR: ${e.message}" }
                    }
                } catch (e: Exception) {
                    Logger.e(e) { "Error checking PR: ${e.message}" }
                }
            }
        }

        // Update gamification stats and check for badges
        try {
            gamificationRepository.updateStats()
            val newBadges = gamificationRepository.checkAndAwardBadges()
            if (newBadges.isNotEmpty()) {
                // Only emit badge sound if no other celebration sound is playing (avoid sound stacking)
                // PR celebration dialog plays its own sound via callback, so skip badge sound when PR earned
                if (!hasCelebrationSound) {
                    hapticEvents.emit(HapticEvent.BADGE_EARNED)
                    Logger.d("Badge sound emitted (no PR celebration)")
                } else {
                    Logger.d("Badge sound skipped (PR celebration will play)")
                }
                _badgeEarnedEvents.emit(newBadges)
                Logger.d("New badges earned: ${newBadges.map { it.name }}")
            }
        } catch (e: Exception) {
            Logger.e(e) { "Error updating gamification: ${e.message}" }
        }

        return hasCelebrationSound
    }

    fun emitBadgeSound() {
        scope.launch { hapticEvents.emit(HapticEvent.BADGE_EARNED) }
    }

    fun emitPRSound() {
        scope.launch { hapticEvents.emit(HapticEvent.PERSONAL_RECORD) }
    }
}
