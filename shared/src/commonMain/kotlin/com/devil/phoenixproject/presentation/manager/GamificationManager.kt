package com.devil.phoenixproject.presentation.manager

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.GamificationRepository
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.local.BadgeDefinitions
import com.devil.phoenixproject.domain.model.Badge
import com.devil.phoenixproject.domain.model.BadgeRequirement
import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.PRCelebrationEvent
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Manages gamification events: personal record checking and badge awarding.
 * Extracted from MainViewModel.saveWorkoutSession() during monolith decomposition.
 *
 * All gamification operations are profile-scoped: the caller must pass the
 * active profile ID so that badges, streaks, and stats are isolated per profile.
 */
class GamificationManager(
    private val gamificationRepository: GamificationRepository,
    private val personalRecordRepository: PersonalRecordRepository,
    private val exerciseRepository: ExerciseRepository,
    private val hapticEvents: MutableSharedFlow<HapticEvent>,
    private val scope: CoroutineScope,
    private val gamificationEnabled: StateFlow<Boolean>
) {
    private val _prCelebrationEvent = MutableSharedFlow<PRCelebrationEvent>()
    val prCelebrationEvent: SharedFlow<PRCelebrationEvent> = _prCelebrationEvent.asSharedFlow()

    private val _badgeEarnedEvents = MutableSharedFlow<List<Badge>>()
    val badgeEarnedEvents: SharedFlow<List<Badge>> = _badgeEarnedEvents.asSharedFlow()

    /** Consecutive sets with quality score above minimum threshold (session-scoped) */
    private var consecutiveQualitySets: Int = 0

    /**
     * Check for PRs and badges after a workout session is saved.
     * Extracted from MainViewModel.saveWorkoutSession() L3494-3564.
     *
     * @param peakConcentricForceKg Peak concentric force per cable (max of A/B), 0 if unavailable
     * @param peakEccentricForceKg Peak eccentric force per cable (max of A/B), 0 if unavailable
     * @param profileId Active profile ID for profile-scoped gamification
     * @return true if a celebration sound will play (to avoid sound stacking)
     */
    suspend fun processPostSaveEvents(
        exerciseId: String?,
        workingReps: Int,
        achievedWeightKg: Float,
        volumeWeightKg: Float,
        programMode: ProgramMode,
        isJustLift: Boolean,
        isEchoMode: Boolean,
        peakConcentricForceKg: Float = 0f,
        peakEccentricForceKg: Float = 0f,
        profileId: String = "default"
    ): Boolean {
        var hasCelebrationSound = false

        // Always track PRs (skip for Just Lift and Echo modes)
        // Uses mode-specific PR lookup to track PRs separately per workout mode (#111)
        exerciseId?.let { exId ->
            if (workingReps > 0 && !isJustLift && !isEchoMode) {
                try {
                    val workoutMode = programMode.displayName
                    val timestamp = currentTimeMillis()

                    // Check COMBINED (traditional) PRs
                    val result = personalRecordRepository.updatePRsIfBetter(
                        exerciseId = exId,
                        weightPRWeightPerCableKg = achievedWeightKg,
                        volumePRWeightPerCableKg = volumeWeightKg,
                        reps = workingReps,
                        workoutMode = workoutMode,
                        timestamp = timestamp,
                        profileId = profileId
                    )

                    // Check phase-specific PRs (Issue #111)
                    if (peakConcentricForceKg > 0f || peakEccentricForceKg > 0f) {
                        personalRecordRepository.updatePhaseSpecificPRs(
                            exerciseId = exId,
                            workoutMode = workoutMode,
                            timestamp = timestamp,
                            reps = workingReps,
                            peakConcentricForceKg = peakConcentricForceKg,
                            peakEccentricForceKg = peakEccentricForceKg,
                            profileId = profileId
                        ).onFailure { e ->
                            Logger.e(e) { "Error updating phase-specific PRs: ${e.message}" }
                        }
                    }

                    // Only celebrate if gamification is enabled and an actual PR was broken
                    if (gamificationEnabled.value) {
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
                                        weightPerCableKg = achievedWeightKg,
                                        reps = workingReps,
                                        workoutMode = workoutMode,
                                        brokenPRTypes = brokenPRs
                                    )
                                )
                                Logger.d("NEW PR ($prTypeDescription): ${exercise?.name} - $achievedWeightKg kg x $workingReps reps in $workoutMode mode")
                            }
                        }.onFailure { e ->
                            Logger.e(e) { "Error updating PR: ${e.message}" }
                        }
                    }
                } catch (e: Exception) {
                    Logger.e(e) { "Error checking PR: ${e.message}" }
                }
            }
        }

        // Skip badge checking/awarding when gamification is disabled
        if (!gamificationEnabled.value) return false

        // Update gamification stats and check for badges
        try {
            gamificationRepository.updateStats(profileId)
            val newBadges = gamificationRepository.checkAndAwardBadges(profileId)
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

    /**
     * Process a set's average quality score for Form Master badge tracking.
     * Called after each set completion when quality scoring is active.
     *
     * Tracks consecutive sets above the minimum threshold (85) and awards
     * Form Master badges when streak criteria are met.
     *
     * @param profileId Active profile ID for profile-scoped badge awarding
     */
    suspend fun processSetQualityEvent(averageSetQuality: Int, profileId: String = "default") {
        if (averageSetQuality >= 85) {
            consecutiveQualitySets++
            Logger.d("Quality streak: $consecutiveQualitySets consecutive sets (score=$averageSetQuality)")
        } else {
            Logger.d("Quality streak reset: score $averageSetQuality < 85 (was $consecutiveQualitySets)")
            consecutiveQualitySets = 0
            return // No badge check needed if streak broken
        }

        // Check Form Master badge criteria against current streak
        val formMasterBadges = BadgeDefinitions.allBadges.filter {
            it.requirement is BadgeRequirement.QualityStreak
        }

        val newlyEarned = mutableListOf<Badge>()
        for (badge in formMasterBadges) {
            val req = badge.requirement as BadgeRequirement.QualityStreak
            if (consecutiveQualitySets >= req.sets && averageSetQuality >= req.minScore) {
                if (!gamificationRepository.isBadgeEarned(badge.id, profileId)) {
                    val awarded = gamificationRepository.awardBadge(badge.id, profileId)
                    if (awarded) {
                        newlyEarned.add(badge)
                        Logger.d("Form Master badge earned: ${badge.name} (streak=$consecutiveQualitySets, score=$averageSetQuality)")
                    }
                }
            }
        }

        if (newlyEarned.isNotEmpty()) {
            hapticEvents.emit(HapticEvent.BADGE_EARNED)
            _badgeEarnedEvents.emit(newlyEarned)
            Logger.d("Form Master badges earned: ${newlyEarned.map { it.name }}")
        }
    }

    /**
     * Reset quality streak counter. Called when starting a new workout session.
     */
    fun resetQualityStreak() {
        consecutiveQualitySets = 0
    }

    fun emitBadgeSound() {
        if (!gamificationEnabled.value) return
        scope.launch { hapticEvents.emit(HapticEvent.BADGE_EARNED) }
    }

    fun emitPRSound() {
        if (!gamificationEnabled.value) return
        scope.launch { hapticEvents.emit(HapticEvent.PERSONAL_RECORD) }
    }
}
