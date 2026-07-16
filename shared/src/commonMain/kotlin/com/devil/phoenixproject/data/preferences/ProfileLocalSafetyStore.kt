package com.devil.phoenixproject.data.preferences

import com.devil.phoenixproject.domain.model.ProfileLocalSafetyPreferences
import com.russhwolf.settings.Settings

interface ProfileLocalSafetyStore {
    fun read(profileId: String): ProfileLocalSafetyPreferences
    fun write(profileId: String, value: ProfileLocalSafetyPreferences)
    suspend fun copyLegacyToProfiles(
        profileIds: List<String>,
        value: ProfileLocalSafetyPreferences,
    )
    fun delete(profileId: String)
}

class SettingsProfileLocalSafetyStore(
    private val settings: Settings,
) : ProfileLocalSafetyStore {
    override fun read(profileId: String) = ProfileLocalSafetyPreferences(
        safeWord = settings.getStringOrNull(key(profileId, SAFE_WORD)),
        safeWordCalibrated = settings.getBoolean(
            key(profileId, SAFE_WORD_CALIBRATED),
            false,
        ),
        adultsOnlyConfirmed = settings.getBoolean(
            key(profileId, ADULTS_ONLY_CONFIRMED),
            false,
        ),
        adultsOnlyPrompted = settings.getBoolean(
            key(profileId, ADULTS_ONLY_PROMPTED),
            false,
        ),
    )

    override fun write(profileId: String, value: ProfileLocalSafetyPreferences) {
        val previous = read(profileId)
        try {
            writeKeys(profileId, value)
        } catch (error: Exception) {
            runCatching { writeKeys(profileId, previous) }
            throw error
        }
    }

    override suspend fun copyLegacyToProfiles(
        profileIds: List<String>,
        value: ProfileLocalSafetyPreferences,
    ) {
        profileIds.forEach { profileId -> write(profileId, value) }
    }

    override fun delete(profileId: String) {
        KEY_SUFFIXES.forEach { suffix -> settings.remove(key(profileId, suffix)) }
    }

    private fun writeKeys(profileId: String, value: ProfileLocalSafetyPreferences) {
        value.safeWord?.let { phrase ->
            settings.putString(key(profileId, SAFE_WORD), phrase)
        } ?: settings.remove(key(profileId, SAFE_WORD))
        settings.putBoolean(
            key(profileId, SAFE_WORD_CALIBRATED),
            value.safeWordCalibrated,
        )
        settings.putBoolean(
            key(profileId, ADULTS_ONLY_CONFIRMED),
            value.adultsOnlyConfirmed,
        )
        settings.putBoolean(
            key(profileId, ADULTS_ONLY_PROMPTED),
            value.adultsOnlyPrompted,
        )
    }

    private fun key(profileId: String, suffix: String) = "profile_${profileId}_$suffix"

    private companion object {
        const val SAFE_WORD = "safe_word"
        const val SAFE_WORD_CALIBRATED = "safe_word_calibrated"
        const val ADULTS_ONLY_CONFIRMED = "adults_only_confirmed"
        const val ADULTS_ONLY_PROMPTED = "adults_only_prompted"
        val KEY_SUFFIXES = listOf(
            SAFE_WORD,
            SAFE_WORD_CALIBRATED,
            ADULTS_ONLY_CONFIRMED,
            ADULTS_ONLY_PROMPTED,
        )
    }
}
