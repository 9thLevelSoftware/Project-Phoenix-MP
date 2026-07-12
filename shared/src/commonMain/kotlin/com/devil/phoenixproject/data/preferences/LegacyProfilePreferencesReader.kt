package com.devil.phoenixproject.data.preferences

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.CoreProfilePreferences
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.JustLiftDefaultsDocument
import com.devil.phoenixproject.domain.model.LedPreferences
import com.devil.phoenixproject.domain.model.ProfileLocalSafetyPreferences
import com.devil.phoenixproject.domain.model.RackItem
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RackItemCategory
import com.devil.phoenixproject.domain.model.RackPreferences
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.SingleExerciseDefaultsDocument
import com.devil.phoenixproject.domain.model.VbtPreferences
import com.devil.phoenixproject.domain.model.VulgarTier
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

internal object LegacyProfilePreferenceKeys {
    const val EQUIPMENT_RACK = "equipment_rack_items_v1"
    const val JUST_LIFT = "just_lift_defaults"
    const val EXERCISE_PREFIX = "exercise_defaults_"
    const val ECHO_HARD_MIGRATION_JUST_LIFT = "echo_hard_default_migrated_just_lift"
    const val ECHO_HARD_MIGRATION_EXERCISE_PREFIX = "echo_hard_default_migrated_exercise_"
}

data class LegacyProfilePreferenceSnapshot(
    val core: CoreProfilePreferences,
    val rack: RackPreferences,
    val workout: WorkoutPreferences,
    val led: LedPreferences,
    val vbt: VbtPreferences,
    val localSafety: ProfileLocalSafetyPreferences,
)

interface LegacyProfilePreferencesReader {
    fun readNormalized(): LegacyProfilePreferenceSnapshot
}

class SettingsLegacyProfilePreferencesReader(
    private val preferencesManager: PreferencesManager,
    private val settings: Settings,
) : LegacyProfilePreferencesReader {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class LegacyRackItemDocument(
        val id: String? = null,
        val name: String? = null,
        val category: RackItemCategory = RackItemCategory.OTHER,
        val weightKg: Float? = null,
        val behavior: RackItemBehavior = RackItemBehavior.ADDED_RESISTANCE,
        val enabled: Boolean = true,
        val sortOrder: Int = 0,
        val createdAt: Long? = null,
        val updatedAt: Long? = null,
    )

    private data class LegacyExerciseDefaultsCandidate(
        val exerciseId: String,
        val document: SingleExerciseDefaultsDocument,
        val key: String,
        val precedence: Int,
    )

    override fun readNormalized(): LegacyProfilePreferenceSnapshot {
        val legacy = preferencesManager.preferencesFlow.value
        return LegacyProfilePreferenceSnapshot(
            core = CoreProfilePreferences(
                bodyWeightKg = normalizeBodyWeight(legacy.bodyWeightKg),
                weightUnit = readEnum("weight_unit", WeightUnit.LB),
                weightIncrement = normalizeWeightIncrement(legacy.weightIncrement),
            ),
            rack = RackPreferences(items = decodeLegacyRack(settings.getStringOrNull(LegacyProfilePreferenceKeys.EQUIPMENT_RACK))),
            workout = WorkoutPreferences(
                stopAtTop = legacy.stopAtTop,
                beepsEnabled = legacy.beepsEnabled,
                stallDetectionEnabled = legacy.stallDetectionEnabled,
                audioRepCountEnabled = legacy.audioRepCountEnabled,
                repCountTiming = readEnum("rep_count_timing", RepCountTiming.TOP),
                summaryCountdownSeconds = normalizeInt(
                    key = "summary_countdown_seconds",
                    value = legacy.summaryCountdownSeconds,
                    fallback = 10,
                ) { it in SUMMARY_COUNTDOWN_VALUES },
                autoStartCountdownSeconds = normalizeInt(
                    key = "autostart_countdown_seconds",
                    value = legacy.autoStartCountdownSeconds,
                    fallback = 5,
                ) { it in 2..10 },
                gamificationEnabled = legacy.gamificationEnabled,
                autoStartRoutine = legacy.autoStartRoutine,
                countdownBeepsEnabled = legacy.countdownBeepsEnabled,
                repSoundEnabled = legacy.repSoundEnabled,
                motionStartEnabled = legacy.motionStartEnabled,
                weightSuggestionsEnabled = legacy.weightSuggestionsEnabled,
                defaultRoutineExerciseUsePercentOfPR = legacy.defaultRoutineExerciseUsePercentOfPR,
                defaultRoutineExerciseWeightPercentOfPR = normalizeInt(
                    key = "default_routine_exercise_weight_percent_of_pr",
                    value = settings.getInt("default_routine_exercise_weight_percent_of_pr", 80),
                    fallback = 80,
                ) { it in 50..120 },
                voiceStopEnabled = legacy.voiceStopEnabled,
                justLiftDefaults = decodeJustLiftDefaults(),
                singleExerciseDefaults = decodeSingleExerciseDefaults(),
            ),
            led = LedPreferences(
                colorScheme = normalizeInt(
                    key = "color_scheme",
                    value = legacy.colorScheme,
                    fallback = 0,
                ) { it >= 0 },
                discoModeUnlocked = legacy.discoModeUnlocked,
            ),
            vbt = VbtPreferences(
                enabled = true,
                velocityLossThresholdPercent = normalizeInt(
                    key = "velocity_loss_threshold_percent",
                    value = settings.getInt("velocity_loss_threshold_percent", 20),
                    fallback = 20,
                ) { it in 10..50 },
                autoEndOnVelocityLoss = legacy.autoEndOnVelocityLoss,
                defaultScalingBasis = readEnum("default_scaling_basis", ScalingBasis.MAX_WEIGHT_PR),
                verbalEncouragementEnabled = legacy.verbalEncouragementEnabled,
                vulgarModeEnabled = legacy.vulgarModeEnabled,
                vulgarTier = readEnum("vulgar_tier", VulgarTier.STRONG),
                dominatrixModeUnlocked = legacy.dominatrixModeUnlocked,
                dominatrixModeActive = legacy.dominatrixModeActive,
            ),
            localSafety = ProfileLocalSafetyPreferences(
                safeWord = legacy.safeWord,
                safeWordCalibrated = legacy.safeWordCalibrated,
                adultsOnlyConfirmed = legacy.adultsOnlyConfirmed,
                adultsOnlyPrompted = legacy.adultsOnlyPrompted,
            ),
        )
    }

    private fun normalizeBodyWeight(value: Float): Float =
        value.takeIf { it.isFinite() && (it == 0f || it in 20f..300f) }
            ?: normalized("body_weight_kg", "non-finite or out of range", 0f)

    private fun normalizeWeightIncrement(value: Float): Float =
        value.takeIf { it.isFinite() && (it == -1f || it > 0f) }
            ?: normalized("weight_increment", "non-finite or out of range", -1f)

    private fun decodeLegacyRack(raw: String?): List<RackItem> = buildList {
        val ids = mutableSetOf<String>()
        val elements = runCatching {
            raw?.let { json.parseToJsonElement(it).jsonArray } ?: JsonArray(emptyList())
        }.getOrElse {
            logNormalization(LegacyProfilePreferenceKeys.EQUIPMENT_RACK, "malformed array")
            JsonArray(emptyList())
        }
        elements.forEach { element ->
            val document = runCatching {
                json.decodeFromJsonElement<LegacyRackItemDocument>(element)
            }.getOrNull()
            val createdAt = document?.createdAt ?: 0L
            val item = document?.let { value ->
                val id = value.id?.takeIf(String::isNotBlank) ?: return@let null
                val name = value.name?.takeIf(String::isNotBlank) ?: return@let null
                val weightKg = value.weightKg?.takeIf { it.isFinite() && it >= 0f }
                    ?: return@let null
                RackItem(
                    id = id,
                    name = name,
                    category = value.category,
                    weightKg = weightKg,
                    behavior = value.behavior,
                    enabled = value.enabled,
                    sortOrder = value.sortOrder,
                    createdAt = createdAt,
                    updatedAt = value.updatedAt ?: createdAt,
                )
            }
            val accepted = item?.takeIf { ids.add(it.id) }
            if (accepted == null) {
                logNormalization(LegacyProfilePreferenceKeys.EQUIPMENT_RACK, "invalid item")
            } else {
                add(accepted)
            }
        }
    }

    private fun decodeJustLiftDefaults(): JustLiftDefaultsDocument {
        val defaults = JustLiftDefaultsDocument()
        val raw = settings.getStringOrNull(LegacyProfilePreferenceKeys.JUST_LIFT) ?: return defaults
        val decoded = runCatching { json.decodeFromString<JustLiftDefaults>(raw) }
            .getOrElse {
                logNormalization(LegacyProfilePreferenceKeys.JUST_LIFT, "malformed defaults")
                return defaults
            }
        return JustLiftDefaultsDocument(
            workoutModeId = normalizeInt(
                LegacyProfilePreferenceKeys.JUST_LIFT,
                decoded.workoutModeId,
                defaults.workoutModeId,
            ) { it in WORKOUT_MODE_IDS },
            weightPerCableKg = normalizeFloat(
                LegacyProfilePreferenceKeys.JUST_LIFT,
                decoded.weightPerCableKg,
                defaults.weightPerCableKg,
            ) { it >= 0f },
            weightChangePerRep = normalizeFloat(
                LegacyProfilePreferenceKeys.JUST_LIFT,
                decoded.weightChangePerRep,
                defaults.weightChangePerRep,
            ) { true },
            eccentricLoadPercentage = normalizeInt(
                LegacyProfilePreferenceKeys.JUST_LIFT,
                decoded.eccentricLoadPercentage,
                defaults.eccentricLoadPercentage,
            ) { it in 0..150 },
            echoLevelValue = normalizeInt(
                LegacyProfilePreferenceKeys.JUST_LIFT,
                normalizeLegacyEchoHardPlaceholder(
                    key = LegacyProfilePreferenceKeys.JUST_LIFT,
                    markerKey = LegacyProfilePreferenceKeys.ECHO_HARD_MIGRATION_JUST_LIFT,
                    value = decoded.echoLevelValue,
                ),
                defaults.echoLevelValue,
            ) { it in 0..3 },
            stallDetectionEnabled = decoded.stallDetectionEnabled,
            repCountTimingName = decoded.repCountTimingName.takeIf { name ->
                RepCountTiming.entries.any { it.name == name }
            } ?: normalized(
                LegacyProfilePreferenceKeys.JUST_LIFT,
                "unknown rep count timing",
                defaults.repCountTimingName,
            ),
            restSeconds = normalizeInt(
                LegacyProfilePreferenceKeys.JUST_LIFT,
                decoded.restSeconds,
                defaults.restSeconds,
            ) { it == 0 || it in 5..300 },
        )
    }

    private fun decodeSingleExerciseDefaults(): Map<String, SingleExerciseDefaultsDocument> =
        settings.keys
            .asSequence()
            .filter { it.startsWith(LegacyProfilePreferenceKeys.EXERCISE_PREFIX) }
            .mapNotNull { key ->
                val decoded = runCatching {
                    settings.getStringOrNull(key)?.let { json.decodeFromString<SingleExerciseDefaults>(it) }
                }.getOrNull()
                if (decoded == null || decoded.exerciseId.isBlank()) {
                    logNormalization(key, "malformed or invalid defaults")
                    null
                } else {
                    val normalizedDefaults = decoded.copy(
                        echoLevelValue = normalizeLegacyEchoHardPlaceholder(
                            key = key,
                            markerKey = LegacyProfilePreferenceKeys.ECHO_HARD_MIGRATION_EXERCISE_PREFIX + decoded.exerciseId,
                            value = decoded.echoLevelValue,
                        ),
                    )
                    val document = normalizedDefaults.toDocument()
                    if (
                        ProfilePreferencesValidator.workout(
                            WorkoutPreferences(singleExerciseDefaults = mapOf(decoded.exerciseId to document)),
                        ).isNotEmpty()
                    ) {
                        logNormalization(key, "malformed or invalid defaults")
                        null
                    } else {
                        LegacyExerciseDefaultsCandidate(
                            exerciseId = decoded.exerciseId,
                            document = document,
                            key = key,
                            precedence = exerciseDefaultsKeyPrecedence(key, decoded.exerciseId),
                        )
                    }
                }
            }
            .sortedWith(
                compareBy<LegacyExerciseDefaultsCandidate>(
                    { it.exerciseId },
                    { it.precedence },
                    { it.key },
                ),
            )
            .distinctBy { it.exerciseId }
            .associate { it.exerciseId to it.document }

    private fun exerciseDefaultsKeyPrecedence(key: String, exerciseId: String): Int {
        val canonicalKey = LegacyProfilePreferenceKeys.EXERCISE_PREFIX + exerciseId
        return when (key) {
            canonicalKey -> 0
            "${canonicalKey}_DOUBLE" -> 1
            "${canonicalKey}_SINGLE" -> 2
            "${canonicalKey}_EITHER" -> 3
            else -> 4
        }
    }

    private fun normalizeInt(
        key: String,
        value: Int,
        fallback: Int,
        isValid: (Int) -> Boolean,
    ): Int = value.takeIf(isValid) ?: normalized(key, "out of range", fallback)

    private fun normalizeFloat(
        key: String,
        value: Float,
        fallback: Float,
        isValid: (Float) -> Boolean,
    ): Float = value.takeIf { it.isFinite() && isValid(it) }
        ?: normalized(key, "non-finite or out of range", fallback)

    private inline fun <reified T : Enum<T>> readEnum(key: String, fallback: T): T {
        val raw = settings.getStringOrNull(key) ?: return fallback
        return enumValues<T>().firstOrNull { it.name == raw }
            ?: normalized(key, "unknown enum", fallback)
    }

    private fun normalizeLegacyEchoHardPlaceholder(
        key: String,
        markerKey: String,
        value: Int,
    ): Int = if (
        value == EchoLevel.HARD.levelValue &&
        !settings.getBoolean(markerKey, false)
    ) {
        normalized(key, "legacy HARD placeholder", EchoLevel.HARDER.levelValue)
    } else {
        value
    }

    private fun <T> normalized(key: String, reason: String, fallback: T): T {
        logNormalization(key, reason)
        return fallback
    }

    private fun logNormalization(key: String, reason: String) {
        Logger.w { "PROFILE_PREF_MIGRATION normalized legacy key $key: $reason" }
    }

    private companion object {
        val SUMMARY_COUNTDOWN_VALUES = setOf(-1, 0, 5, 10, 15, 20, 25, 30)
        val WORKOUT_MODE_IDS = setOf(0, 2, 3, 4, 6, 10)
    }
}
