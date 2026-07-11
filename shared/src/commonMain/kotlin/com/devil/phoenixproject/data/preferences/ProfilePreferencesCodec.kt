package com.devil.phoenixproject.data.preferences

import com.devil.phoenixproject.domain.model.LedPreferences
import com.devil.phoenixproject.domain.model.ProfilePreferenceValidity
import com.devil.phoenixproject.domain.model.RackPreferences
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.VbtPreferences
import com.devil.phoenixproject.domain.model.VulgarTier
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ProfilePreferencesCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    @Serializable
    private data class LedPreferencesDocument(
        val version: Int = 1,
        val discoModeUnlocked: Boolean = false,
    )

    @Serializable
    private data class VbtPreferencesDocument(
        val version: Int = 1,
        val velocityLossThresholdPercent: Int = 20,
        val autoEndOnVelocityLoss: Boolean = false,
        val defaultScalingBasis: ScalingBasis = ScalingBasis.MAX_WEIGHT_PR,
        val verbalEncouragementEnabled: Boolean = false,
        val vulgarModeEnabled: Boolean = false,
        val vulgarTier: VulgarTier = VulgarTier.STRONG,
        val dominatrixModeUnlocked: Boolean = false,
        val dominatrixModeActive: Boolean = false,
    )

    fun encodeRack(value: RackPreferences): String = json.encodeToString(value)
    fun encodeWorkout(value: WorkoutPreferences): String = json.encodeToString(value)
    fun encodeLed(value: LedPreferences): String = json.encodeToString(
        LedPreferencesDocument(value.version, value.discoModeUnlocked),
    )
    fun encodeVbt(value: VbtPreferences): String = json.encodeToString(
        VbtPreferencesDocument(
            value.version,
            value.velocityLossThresholdPercent,
            value.autoEndOnVelocityLoss,
            value.defaultScalingBasis,
            value.verbalEncouragementEnabled,
            value.vulgarModeEnabled,
            value.vulgarTier,
            value.dominatrixModeUnlocked,
            value.dominatrixModeActive,
        ),
    )

    fun decodeRack(raw: String) = decode(raw, RackPreferences(), ProfilePreferencesValidator::rack)
    fun decodeWorkout(raw: String) = decode(raw, WorkoutPreferences(), ProfilePreferencesValidator::workout)
    fun decodeLed(raw: String, colorScheme: Int): DecodedProfileDocument<LedPreferences> =
        decode(raw, LedPreferencesDocument()) { value -> if (value.version == 1) emptyList() else listOf("version") }
            .let { decoded ->
                decoded.mapValue { value -> LedPreferences(value.version, colorScheme, value.discoModeUnlocked) }
            }
    fun decodeVbt(raw: String, enabled: Boolean): DecodedProfileDocument<VbtPreferences> =
        decode(raw, VbtPreferencesDocument()) { value ->
            ProfilePreferencesValidator.vbt(
                VbtPreferences(
                    version = value.version,
                    enabled = enabled,
                    velocityLossThresholdPercent = value.velocityLossThresholdPercent,
                    autoEndOnVelocityLoss = value.autoEndOnVelocityLoss,
                    defaultScalingBasis = value.defaultScalingBasis,
                    verbalEncouragementEnabled = value.verbalEncouragementEnabled,
                    vulgarModeEnabled = value.vulgarModeEnabled,
                    vulgarTier = value.vulgarTier,
                    dominatrixModeUnlocked = value.dominatrixModeUnlocked,
                    dominatrixModeActive = value.dominatrixModeActive,
                ),
            )
        }.let { decoded ->
            decoded.mapValue { value ->
                VbtPreferences(
                    value.version, enabled, value.velocityLossThresholdPercent,
                    value.autoEndOnVelocityLoss, value.defaultScalingBasis,
                    value.verbalEncouragementEnabled, value.vulgarModeEnabled,
                    value.vulgarTier, value.dominatrixModeUnlocked, value.dominatrixModeActive,
                )
            }
        }

    private inline fun <reified T> decode(
        raw: String,
        fallback: T,
        validate: (T) -> List<String>,
    ): DecodedProfileDocument<T> = runCatching { json.decodeFromString<T>(raw) }
        .fold(
            onSuccess = { value ->
                val errors = validate(value)
                DecodedProfileDocument(
                    value = if (errors.isEmpty()) value else fallback,
                    raw = raw,
                    validity = if (errors.isEmpty()) ProfilePreferenceValidity.Valid else ProfilePreferenceValidity.Invalid(errors.joinToString(",")),
                )
            },
            onFailure = { error ->
                DecodedProfileDocument(fallback, raw, ProfilePreferenceValidity.Invalid(error::class.simpleName ?: "decode"))
            },
        )
}

data class DecodedProfileDocument<T>(
    val value: T,
    val raw: String,
    val validity: ProfilePreferenceValidity,
) {
    fun <R> mapValue(transform: (T) -> R) = DecodedProfileDocument(transform(value), raw, validity)
}
