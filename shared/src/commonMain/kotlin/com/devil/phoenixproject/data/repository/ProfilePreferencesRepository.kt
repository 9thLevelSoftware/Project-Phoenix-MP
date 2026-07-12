package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOne
import com.devil.phoenixproject.data.preferences.ProfilePreferencesCodec
import com.devil.phoenixproject.data.preferences.ProfilePreferencesValidator
import com.devil.phoenixproject.database.UserProfilePreferences as ProfilePreferencesRow
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.CoreProfilePreferences
import com.devil.phoenixproject.domain.model.LedPreferences
import com.devil.phoenixproject.domain.model.ProfilePreferenceSection
import com.devil.phoenixproject.domain.model.ProfilePreferenceSectionName
import com.devil.phoenixproject.domain.model.ProfilePreferenceValidity
import com.devil.phoenixproject.domain.model.ProfileSectionMetadata
import com.devil.phoenixproject.domain.model.RackPreferences
import com.devil.phoenixproject.domain.model.UserProfilePreferences
import com.devil.phoenixproject.domain.model.VbtPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface ProfilePreferencesRepository {
    fun observe(profileId: String): Flow<UserProfilePreferences>
    suspend fun get(profileId: String): UserProfilePreferences
    suspend fun seedMissingProfiles()
    suspend fun insertDefaults(profileId: String)
    suspend fun updateCore(profileId: String, value: CoreProfilePreferences, now: Long)
    suspend fun updateRack(profileId: String, value: RackPreferences, now: Long)
    suspend fun updateWorkout(profileId: String, value: WorkoutPreferences, now: Long)
    suspend fun updateLed(profileId: String, value: LedPreferences, now: Long)
    suspend fun updateVbt(profileId: String, value: VbtPreferences, now: Long)
    suspend fun resetInvalidSection(
        profileId: String,
        section: ProfilePreferenceSectionName,
        now: Long,
    )
    suspend fun delete(profileId: String)
}

class SqlDelightProfilePreferencesRepository(
    private val database: VitruvianDatabase,
) : ProfilePreferencesRepository {
    private val queries = database.vitruvianDatabaseQueries

    override fun observe(profileId: String): Flow<UserProfilePreferences> =
        queries.selectProfilePreferences(profileId)
            .asFlow()
            .mapToOne(Dispatchers.IO)
            .map(::mapRow)

    override suspend fun get(profileId: String): UserProfilePreferences =
        mapRow(queries.selectProfilePreferences(profileId).executeAsOne())

    override suspend fun seedMissingProfiles() {
        queries.seedMissingProfilePreferences()
    }

    override suspend fun insertDefaults(profileId: String) {
        queries.insertDefaultProfilePreferences(profileId, 1L)
    }

    override suspend fun updateCore(
        profileId: String,
        value: CoreProfilePreferences,
        now: Long,
    ) {
        require(ProfilePreferencesValidator.core(value).isEmpty())
        queries.updateCoreProfilePreferences(
            body_weight_kg = value.bodyWeightKg.toDouble(),
            weight_unit = value.weightUnit.name,
            weight_increment = value.weightIncrement.toDouble(),
            core_updated_at = now,
            profile_id = profileId,
        )
    }

    override suspend fun updateRack(profileId: String, value: RackPreferences, now: Long) {
        require(ProfilePreferencesValidator.rack(value).isEmpty())
        queries.updateRackProfilePreferences(
            equipment_rack_json = ProfilePreferencesCodec.encodeRack(value),
            rack_updated_at = now,
            profile_id = profileId,
        )
    }

    override suspend fun updateWorkout(
        profileId: String,
        value: WorkoutPreferences,
        now: Long,
    ) {
        require(ProfilePreferencesValidator.workout(value).isEmpty())
        queries.updateWorkoutProfilePreferences(
            workout_preferences_json = ProfilePreferencesCodec.encodeWorkout(value),
            workout_updated_at = now,
            profile_id = profileId,
        )
    }

    override suspend fun updateLed(profileId: String, value: LedPreferences, now: Long) {
        require(ProfilePreferencesValidator.led(value).isEmpty())
        queries.updateLedProfilePreferences(
            led_color_scheme_id = value.colorScheme.toLong(),
            led_preferences_json = ProfilePreferencesCodec.encodeLed(value),
            led_updated_at = now,
            profile_id = profileId,
        )
    }

    override suspend fun updateVbt(profileId: String, value: VbtPreferences, now: Long) {
        require(ProfilePreferencesValidator.vbt(value).isEmpty())
        queries.updateVbtProfilePreferences(
            vbt_enabled = if (value.enabled) 1L else 0L,
            vbt_preferences_json = ProfilePreferencesCodec.encodeVbt(value),
            vbt_updated_at = now,
            profile_id = profileId,
        )
    }

    override suspend fun resetInvalidSection(
        profileId: String,
        section: ProfilePreferenceSectionName,
        now: Long,
    ) {
        when (section) {
            ProfilePreferenceSectionName.CORE -> updateCore(profileId, CoreProfilePreferences(), now)
            ProfilePreferenceSectionName.RACK -> updateRack(profileId, RackPreferences(), now)
            ProfilePreferenceSectionName.WORKOUT -> updateWorkout(profileId, WorkoutPreferences(), now)
            ProfilePreferenceSectionName.LED -> updateLed(profileId, LedPreferences(), now)
            ProfilePreferenceSectionName.VBT -> updateVbt(profileId, VbtPreferences(), now)
        }
    }

    override suspend fun delete(profileId: String) {
        queries.deleteProfilePreferences(profileId)
    }

    private fun mapRow(row: ProfilePreferencesRow): UserProfilePreferences {
        fun metadata(
            updatedAt: Long,
            generation: Long,
            revision: Long,
            dirty: Long,
        ) = ProfileSectionMetadata(updatedAt, generation, revision, dirty == 1L)

        val storedCore = runCatching {
            CoreProfilePreferences(
                bodyWeightKg = row.body_weight_kg.toFloat(),
                weightUnit = WeightUnit.valueOf(row.weight_unit),
                weightIncrement = row.weight_increment.toFloat(),
            )
        }.getOrNull()
        val coreErrors = storedCore
            ?.let(ProfilePreferencesValidator::core)
            ?: listOf("weightUnit")
        val rack = ProfilePreferencesCodec.decodeRack(row.equipment_rack_json)
        val workout = ProfilePreferencesCodec.decodeWorkout(row.workout_preferences_json)
        val led = ProfilePreferencesCodec.decodeLed(
            row.led_preferences_json,
            row.led_color_scheme_id.toInt(),
        )
        val vbt = ProfilePreferencesCodec.decodeVbt(
            row.vbt_preferences_json,
            row.vbt_enabled == 1L,
        )

        return UserProfilePreferences(
            profileId = row.profile_id,
            schemaVersion = row.schema_version.toInt(),
            legacyMigrationVersion = row.legacy_migration_version.toInt(),
            core = ProfilePreferenceSection(
                value = if (coreErrors.isEmpty()) requireNotNull(storedCore) else CoreProfilePreferences(),
                validity = if (coreErrors.isEmpty()) {
                    ProfilePreferenceValidity.Valid
                } else {
                    ProfilePreferenceValidity.Invalid(coreErrors.joinToString(","))
                },
                metadata = metadata(
                    row.core_updated_at,
                    row.core_local_generation,
                    row.core_server_revision,
                    row.core_dirty,
                ),
            ),
            rack = ProfilePreferenceSection(
                rack.value,
                rack.raw,
                rack.validity,
                metadata(
                    row.rack_updated_at,
                    row.rack_local_generation,
                    row.rack_server_revision,
                    row.rack_dirty,
                ),
            ),
            workout = ProfilePreferenceSection(
                workout.value,
                workout.raw,
                workout.validity,
                metadata(
                    row.workout_updated_at,
                    row.workout_local_generation,
                    row.workout_server_revision,
                    row.workout_dirty,
                ),
            ),
            led = ProfilePreferenceSection(
                led.value,
                led.raw,
                led.validity,
                metadata(
                    row.led_updated_at,
                    row.led_local_generation,
                    row.led_server_revision,
                    row.led_dirty,
                ),
            ),
            vbt = ProfilePreferenceSection(
                vbt.value,
                vbt.raw,
                vbt.validity,
                metadata(
                    row.vbt_updated_at,
                    row.vbt_local_generation,
                    row.vbt_server_revision,
                    row.vbt_dirty,
                ),
            ),
        )
    }
}
