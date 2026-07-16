package com.devil.phoenixproject.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class CoreProfilePreferences(
    val bodyWeightKg: Float = 0f,
    val weightUnit: WeightUnit = WeightUnit.LB,
    val weightIncrement: Float = -1f,
)

@Serializable
data class RackPreferences(
    val version: Int = 1,
    val items: List<RackItem> = emptyList(),
)

@Serializable
data class JustLiftDefaultsDocument(
    val workoutModeId: Int = 0,
    val weightPerCableKg: Float = 20f,
    val weightChangePerRep: Float = 0f,
    val eccentricLoadPercentage: Int = 100,
    val echoLevelValue: Int = 1,
    val stallDetectionEnabled: Boolean = true,
    val repCountTimingName: String = "TOP",
    val restSeconds: Int = 60,
)

@Serializable
data class SingleExerciseDefaultsDocument(
    val exerciseId: String,
    val setReps: List<Int?>,
    val weightPerCableKg: Float,
    val setWeightsPerCableKg: List<Float>,
    val progressionKg: Float,
    val setRestSeconds: List<Int>,
    val workoutModeId: Int,
    val eccentricLoadPercentage: Int,
    val echoLevelValue: Int,
    val duration: Int,
    val isAMRAP: Boolean,
    val perSetRestTime: Boolean,
    val defaultRackItemIds: List<String> = emptyList(),
)

@Serializable
data class WorkoutPreferences(
    val version: Int = 1,
    val stopAtTop: Boolean = false,
    val beepsEnabled: Boolean = true,
    val stallDetectionEnabled: Boolean = true,
    val audioRepCountEnabled: Boolean = false,
    val repCountTiming: RepCountTiming = RepCountTiming.TOP,
    val summaryCountdownSeconds: Int = 10,
    val autoStartCountdownSeconds: Int = 5,
    val gamificationEnabled: Boolean = true,
    val autoStartRoutine: Boolean = false,
    val countdownBeepsEnabled: Boolean = true,
    val repSoundEnabled: Boolean = true,
    val motionStartEnabled: Boolean = false,
    val weightSuggestionsEnabled: Boolean = true,
    val defaultRoutineExerciseUsePercentOfPR: Boolean = false,
    val defaultRoutineExerciseWeightPercentOfPR: Int = 80,
    val voiceStopEnabled: Boolean = false,
    val justLiftDefaults: JustLiftDefaultsDocument = JustLiftDefaultsDocument(),
    val singleExerciseDefaults: Map<String, SingleExerciseDefaultsDocument> = emptyMap(),
)

@Serializable
data class LedPreferences(
    val version: Int = 1,
    val colorScheme: Int = 0,
    val discoModeUnlocked: Boolean = false,
)

@Serializable
data class VbtPreferences(
    val version: Int = 1,
    val enabled: Boolean = true,
    val velocityLossThresholdPercent: Int = 20,
    val autoEndOnVelocityLoss: Boolean = false,
    val defaultScalingBasis: ScalingBasis = ScalingBasis.MAX_WEIGHT_PR,
    val verbalEncouragementEnabled: Boolean = false,
    val vulgarModeEnabled: Boolean = false,
    val vulgarTier: VulgarTier = VulgarTier.STRONG,
    val dominatrixModeUnlocked: Boolean = false,
    val dominatrixModeActive: Boolean = false,
)

data class ProfileLocalSafetyPreferences(
    val safeWord: String? = null,
    val safeWordCalibrated: Boolean = false,
    val adultsOnlyConfirmed: Boolean = false,
    val adultsOnlyPrompted: Boolean = false,
)

enum class ProfilePreferenceSectionName { CORE, RACK, WORKOUT, LED, VBT }

sealed interface ProfilePreferenceValidity {
    data object Valid : ProfilePreferenceValidity
    data class Invalid(val reason: String) : ProfilePreferenceValidity
}

data class ProfileSectionMetadata(
    val updatedAt: Long,
    val localGeneration: Long,
    val serverRevision: Long,
    val dirty: Boolean,
)

data class ProfilePreferenceSection<T>(
    val value: T,
    val raw: String? = null,
    val validity: ProfilePreferenceValidity,
    val metadata: ProfileSectionMetadata,
)

data class UserProfilePreferences(
    val profileId: String,
    val schemaVersion: Int,
    val legacyMigrationVersion: Int,
    val core: ProfilePreferenceSection<CoreProfilePreferences>,
    val rack: ProfilePreferenceSection<RackPreferences>,
    val workout: ProfilePreferenceSection<WorkoutPreferences>,
    val led: ProfilePreferenceSection<LedPreferences>,
    val vbt: ProfilePreferenceSection<VbtPreferences>,
)
