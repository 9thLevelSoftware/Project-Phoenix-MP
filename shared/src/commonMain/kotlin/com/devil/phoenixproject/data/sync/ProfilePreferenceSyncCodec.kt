package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.data.preferences.ProfilePreferencesCodec
import com.devil.phoenixproject.data.preferences.ProfilePreferencesValidator
import com.devil.phoenixproject.database.UserProfilePreferences as ProfilePreferenceRow
import com.devil.phoenixproject.domain.model.CoreProfilePreferences
import com.devil.phoenixproject.domain.model.LedPreferences
import com.devil.phoenixproject.domain.model.ProfilePreferenceSectionName
import com.devil.phoenixproject.domain.model.ProfilePreferenceValidity
import com.devil.phoenixproject.domain.model.RackPreferences
import com.devil.phoenixproject.domain.model.VbtPreferences
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class ProfilePreferenceSyncCodec {
    companion object {
        const val MAX_EXACT_JSON_INTEGER = 9_007_199_254_740_991L
        const val MIN_EXACT_JSON_INTEGER = -9_007_199_254_740_991L
        const val MIN_RFC3339_EPOCH_MILLIS = -62_135_596_800_000L
        const val MAX_RFC3339_EPOCH_MILLIS = 253_402_300_799_999L
    }

    private data class LocalPayload(
        val documentVersion: Int,
        val payload: JsonObject,
    )

    private sealed interface LocalPayloadResult {
        data class Valid(val value: LocalPayload) : LocalPayloadResult
        data class Invalid(val reason: ProfilePreferenceSyncIssueReason) : LocalPayloadResult
    }

    private fun exactJsonIntegerIssue(value: Long): ProfilePreferenceSyncIssueReason? =
        if (value in MIN_EXACT_JSON_INTEGER..MAX_EXACT_JSON_INTEGER) {
            null
        } else {
            ProfilePreferenceSyncIssueReason.UNREPRESENTABLE_JSON_INTEGER
        }

    private fun document(encoded: String): JsonObject =
        PortalWireJson.parseToJsonElement(encoded).jsonObject

    fun ledPayload(value: LedPreferences): JsonObject = buildJsonObject {
        put("ledColorSchemeId", value.colorScheme)
        put("preferences", document(ProfilePreferencesCodec.encodeLed(value)))
    }

    fun vbtPayload(value: VbtPreferences): JsonObject = buildJsonObject {
        put("vbtEnabled", value.enabled)
        put("preferences", document(ProfilePreferencesCodec.encodeVbt(value)))
    }

    fun corePayload(value: CoreProfilePreferences): JsonObject = buildJsonObject {
        put("bodyWeightKg", value.bodyWeightKg)
        put("weightUnit", value.weightUnit.name)
        put("weightIncrement", value.weightIncrement)
    }

    fun rackPayload(value: RackPreferences): JsonObject =
        document(ProfilePreferencesCodec.encodeRack(value))

    fun workoutPayload(value: WorkoutPreferences): JsonObject =
        document(ProfilePreferencesCodec.encodeWorkout(value))

    fun normalizedPayload(value: DecodedProfilePreferenceValue): JsonObject = when (value) {
        is DecodedProfilePreferenceValue.Core -> corePayload(value.value)
        is DecodedProfilePreferenceValue.Rack -> rackPayload(value.value)
        is DecodedProfilePreferenceValue.Workout -> workoutPayload(value.value)
        is DecodedProfilePreferenceValue.Led -> ledPayload(value.value)
        is DecodedProfilePreferenceValue.Vbt -> vbtPayload(value.value)
    }

    fun validateCanonicalPayload(
        section: ProfilePreferenceSectionName,
        documentVersion: Int,
        payload: JsonObject,
    ): ProfilePreferencePayloadValidation {
        val reason = canonicalPayloadIssue(section, documentVersion, payload)
        return ProfilePreferencePayloadValidation(
            isValid = reason == null,
            reason = reason?.name.orEmpty(),
        )
    }

    fun encodeDirtyRow(row: ProfilePreferenceRow): EncodedDirtyProfilePreferenceRow {
        val dirtySections = ProfilePreferenceSectionName.entries.filter { section ->
            sectionDirty(row, section)
        }
        if (row.profile_id.isBlank() || !isPostgresCompatibleText(row.profile_id)) {
            return EncodedDirtyProfilePreferenceRow(
                valid = emptyList(),
                unsyncable = dirtySections.map { section ->
                    issue(
                        row = row,
                        section = section,
                        reason = ProfilePreferenceSyncIssueReason.INVALID_PROFILE_ID,
                    )
                },
            )
        }

        val valid = mutableListOf<ProfilePreferenceSectionSyncDto>()
        val unsyncable = mutableListOf<ProfilePreferenceSyncIssue>()
        dirtySections.forEach { section ->
            val baseRevision = sectionServerRevision(row, section)
            val reason = when {
                baseRevision < 0 -> ProfilePreferenceSyncIssueReason.INVALID_LOCAL_DOCUMENT
                exactJsonIntegerIssue(baseRevision) != null -> exactJsonIntegerIssue(baseRevision)
                sectionUpdatedAt(row, section) !in
                    MIN_RFC3339_EPOCH_MILLIS..MAX_RFC3339_EPOCH_MILLIS ->
                    ProfilePreferenceSyncIssueReason.INVALID_CLIENT_MODIFIED_AT
                else -> null
            }
            if (reason != null) {
                unsyncable += issue(row, section, reason)
                return@forEach
            }

            when (val localPayload = localPayload(row, section)) {
                is LocalPayloadResult.Invalid ->
                    unsyncable += issue(row, section, localPayload.reason)
                is LocalPayloadResult.Valid -> {
                    val payload = localPayload.value.payload
                    val wireIssue = when (profilePreferenceWireSafetyViolation(payload)) {
                        ProfilePreferenceWireSafetyViolation.INVALID_TEXT_TREE ->
                            ProfilePreferenceSyncIssueReason.INVALID_TEXT_TREE
                        ProfilePreferenceWireSafetyViolation.LOCAL_ONLY_KEY ->
                            ProfilePreferenceSyncIssueReason.LOCAL_ONLY_WIRE_KEY
                        null -> null
                    }
                    if (wireIssue != null) {
                        unsyncable += issue(row, section, wireIssue)
                    } else {
                        valid += ProfilePreferenceSectionSyncDto(
                            key = ProfilePreferenceSectionKey(row.profile_id, section),
                            documentVersion = localPayload.value.documentVersion,
                            baseRevision = baseRevision,
                            clientModifiedAtEpochMs = sectionUpdatedAt(row, section),
                            localGeneration = sectionLocalGeneration(row, section),
                            payload = payload,
                        )
                    }
                }
            }
        }
        return EncodedDirtyProfilePreferenceRow(valid, unsyncable)
    }

    private fun localPayload(
        row: ProfilePreferenceRow,
        section: ProfilePreferenceSectionName,
    ): LocalPayloadResult = runCatching {
        when (section) {
            ProfilePreferenceSectionName.CORE -> {
                val value = CoreProfilePreferences(
                    bodyWeightKg = row.body_weight_kg.toFloat(),
                    weightUnit = WeightUnit.valueOf(row.weight_unit),
                    weightIncrement = row.weight_increment.toFloat(),
                )
                if (ProfilePreferencesValidator.core(value).isNotEmpty()) {
                    LocalPayloadResult.Invalid(ProfilePreferenceSyncIssueReason.INVALID_LOCAL_DOCUMENT)
                } else {
                    LocalPayloadResult.Valid(LocalPayload(1, corePayload(value)))
                }
            }
            ProfilePreferenceSectionName.RACK -> {
                val decoded = ProfilePreferencesCodec.decodeRack(row.equipment_rack_json)
                if (decoded.validity !is ProfilePreferenceValidity.Valid) {
                    LocalPayloadResult.Invalid(ProfilePreferenceSyncIssueReason.INVALID_LOCAL_DOCUMENT)
                } else if (decoded.value.items.any { item ->
                        exactJsonIntegerIssue(item.createdAt) != null ||
                            exactJsonIntegerIssue(item.updatedAt) != null
                    }
                ) {
                    LocalPayloadResult.Invalid(
                        ProfilePreferenceSyncIssueReason.UNREPRESENTABLE_JSON_INTEGER,
                    )
                } else {
                    LocalPayloadResult.Valid(
                        LocalPayload(decoded.value.version, rackPayload(decoded.value)),
                    )
                }
            }
            ProfilePreferenceSectionName.WORKOUT -> {
                val decoded = ProfilePreferencesCodec.decodeWorkout(row.workout_preferences_json)
                if (decoded.validity !is ProfilePreferenceValidity.Valid) {
                    LocalPayloadResult.Invalid(ProfilePreferenceSyncIssueReason.INVALID_LOCAL_DOCUMENT)
                } else {
                    LocalPayloadResult.Valid(
                        LocalPayload(decoded.value.version, workoutPayload(decoded.value)),
                    )
                }
            }
            ProfilePreferenceSectionName.LED -> {
                if (row.led_color_scheme_id !in
                    Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                ) {
                    LocalPayloadResult.Invalid(ProfilePreferenceSyncIssueReason.INVALID_INT32)
                } else {
                    val decoded = ProfilePreferencesCodec.decodeLed(
                        row.led_preferences_json,
                        row.led_color_scheme_id.toInt(),
                    )
                    if (decoded.validity !is ProfilePreferenceValidity.Valid) {
                        LocalPayloadResult.Invalid(
                            ProfilePreferenceSyncIssueReason.INVALID_LOCAL_DOCUMENT,
                        )
                    } else {
                        LocalPayloadResult.Valid(
                            LocalPayload(decoded.value.version, ledPayload(decoded.value)),
                        )
                    }
                }
            }
            ProfilePreferenceSectionName.VBT -> {
                val decoded = ProfilePreferencesCodec.decodeVbt(
                    row.vbt_preferences_json,
                    row.vbt_enabled == 1L,
                )
                if (decoded.validity !is ProfilePreferenceValidity.Valid) {
                    LocalPayloadResult.Invalid(ProfilePreferenceSyncIssueReason.INVALID_LOCAL_DOCUMENT)
                } else {
                    LocalPayloadResult.Valid(
                        LocalPayload(decoded.value.version, vbtPayload(decoded.value)),
                    )
                }
            }
        }
    }.getOrElse {
        LocalPayloadResult.Invalid(ProfilePreferenceSyncIssueReason.INVALID_LOCAL_DOCUMENT)
    }

    private fun issue(
        row: ProfilePreferenceRow,
        section: ProfilePreferenceSectionName,
        reason: ProfilePreferenceSyncIssueReason,
    ) = ProfilePreferenceSyncIssue(
        key = ProfilePreferenceSectionKey(row.profile_id, section),
        localGeneration = sectionLocalGeneration(row, section),
        reason = reason.name,
    )

    private fun sectionDirty(
        row: ProfilePreferenceRow,
        section: ProfilePreferenceSectionName,
    ): Boolean = when (section) {
        ProfilePreferenceSectionName.CORE -> row.core_dirty == 1L
        ProfilePreferenceSectionName.RACK -> row.rack_dirty == 1L
        ProfilePreferenceSectionName.WORKOUT -> row.workout_dirty == 1L
        ProfilePreferenceSectionName.LED -> row.led_dirty == 1L
        ProfilePreferenceSectionName.VBT -> row.vbt_dirty == 1L
    }

    private fun sectionUpdatedAt(
        row: ProfilePreferenceRow,
        section: ProfilePreferenceSectionName,
    ): Long = when (section) {
        ProfilePreferenceSectionName.CORE -> row.core_updated_at
        ProfilePreferenceSectionName.RACK -> row.rack_updated_at
        ProfilePreferenceSectionName.WORKOUT -> row.workout_updated_at
        ProfilePreferenceSectionName.LED -> row.led_updated_at
        ProfilePreferenceSectionName.VBT -> row.vbt_updated_at
    }

    private fun sectionLocalGeneration(
        row: ProfilePreferenceRow,
        section: ProfilePreferenceSectionName,
    ): Long = when (section) {
        ProfilePreferenceSectionName.CORE -> row.core_local_generation
        ProfilePreferenceSectionName.RACK -> row.rack_local_generation
        ProfilePreferenceSectionName.WORKOUT -> row.workout_local_generation
        ProfilePreferenceSectionName.LED -> row.led_local_generation
        ProfilePreferenceSectionName.VBT -> row.vbt_local_generation
    }

    private fun sectionServerRevision(
        row: ProfilePreferenceRow,
        section: ProfilePreferenceSectionName,
    ): Long = when (section) {
        ProfilePreferenceSectionName.CORE -> row.core_server_revision
        ProfilePreferenceSectionName.RACK -> row.rack_server_revision
        ProfilePreferenceSectionName.WORKOUT -> row.workout_server_revision
        ProfilePreferenceSectionName.LED -> row.led_server_revision
        ProfilePreferenceSectionName.VBT -> row.vbt_server_revision
    }

    private fun numericPrimitive(payload: JsonObject, key: String): JsonPrimitive =
        payload.getValue(key).jsonPrimitive.also { require(!it.isString) }

    private fun stringPrimitive(payload: JsonObject, key: String): JsonPrimitive =
        payload.getValue(key).jsonPrimitive.also { require(it.isString) }

    private fun booleanPrimitive(payload: JsonObject, key: String): JsonPrimitive =
        numericPrimitive(payload, key).also {
            require(it.content == "true" || it.content == "false")
        }

    private fun decodeAndValidateTypedValue(
        section: ProfilePreferenceSectionName,
        payload: JsonObject,
    ): DecodedProfilePreferenceValue {
        val decoded = when (section) {
            ProfilePreferenceSectionName.CORE -> DecodedProfilePreferenceValue.Core(
                CoreProfilePreferences(
                    bodyWeightKg = numericPrimitive(payload, "bodyWeightKg").float,
                    weightUnit = WeightUnit.valueOf(stringPrimitive(payload, "weightUnit").content),
                    weightIncrement = numericPrimitive(payload, "weightIncrement").float,
                ),
            )
            ProfilePreferenceSectionName.RACK -> DecodedProfilePreferenceValue.Rack(
                PortalWireJson.decodeFromJsonElement<RackPreferences>(payload),
            )
            ProfilePreferenceSectionName.WORKOUT -> DecodedProfilePreferenceValue.Workout(
                PortalWireJson.decodeFromJsonElement<WorkoutPreferences>(payload),
            )
            ProfilePreferenceSectionName.LED -> DecodedProfilePreferenceValue.Led(
                PortalWireJson.decodeFromJsonElement<LedPreferences>(
                    payload.getValue("preferences").jsonObject,
                ).copy(
                    colorScheme = numericPrimitive(payload, "ledColorSchemeId").int,
                ),
            )
            ProfilePreferenceSectionName.VBT -> DecodedProfilePreferenceValue.Vbt(
                PortalWireJson.decodeFromJsonElement<VbtPreferences>(
                    payload.getValue("preferences").jsonObject,
                ).copy(
                    enabled = booleanPrimitive(payload, "vbtEnabled").boolean,
                ),
            )
        }
        val errors = when (decoded) {
            is DecodedProfilePreferenceValue.Core -> ProfilePreferencesValidator.core(decoded.value)
            is DecodedProfilePreferenceValue.Rack -> ProfilePreferencesValidator.rack(decoded.value)
            is DecodedProfilePreferenceValue.Workout -> ProfilePreferencesValidator.workout(decoded.value)
            is DecodedProfilePreferenceValue.Led -> ProfilePreferencesValidator.led(decoded.value)
            is DecodedProfilePreferenceValue.Vbt -> ProfilePreferencesValidator.vbt(decoded.value)
        }
        require(errors.isEmpty())
        return decoded
    }

    private fun canonicalPayloadIssue(
        section: ProfilePreferenceSectionName,
        documentVersion: Int,
        payload: JsonObject,
    ): ProfilePreferenceSyncIssueReason? {
        if (documentVersion != 1) {
            return ProfilePreferenceSyncIssueReason.UNSUPPORTED_DOCUMENT_VERSION
        }
        when (profilePreferenceWireSafetyViolation(payload)) {
            ProfilePreferenceWireSafetyViolation.INVALID_TEXT_TREE ->
                return ProfilePreferenceSyncIssueReason.INVALID_TEXT_TREE
            ProfilePreferenceWireSafetyViolation.LOCAL_ONLY_KEY ->
                return ProfilePreferenceSyncIssueReason.LOCAL_ONLY_WIRE_KEY
            null -> Unit
        }
        val explicitEmbeddedVersion = runCatching {
            val element = when (section) {
                ProfilePreferenceSectionName.CORE -> null
                ProfilePreferenceSectionName.RACK,
                ProfilePreferenceSectionName.WORKOUT -> payload["version"]
                ProfilePreferenceSectionName.LED,
                ProfilePreferenceSectionName.VBT ->
                    payload.getValue("preferences").jsonObject["version"]
            }
            element?.jsonPrimitive?.also { require(!it.isString) }?.int
        }.getOrElse {
            return ProfilePreferenceSyncIssueReason.INVALID_CANONICAL_PAYLOAD
        }
        if (explicitEmbeddedVersion != null && explicitEmbeddedVersion != documentVersion) {
            return ProfilePreferenceSyncIssueReason.UNSUPPORTED_DOCUMENT_VERSION
        }
        val decoded = runCatching {
            decodeAndValidateTypedValue(section, payload)
        }.getOrElse {
            return ProfilePreferenceSyncIssueReason.INVALID_CANONICAL_PAYLOAD
        }
        if (decoded is DecodedProfilePreferenceValue.Rack &&
            decoded.value.items.any { item ->
                exactJsonIntegerIssue(item.createdAt) != null ||
                    exactJsonIntegerIssue(item.updatedAt) != null
            }
        ) {
            return ProfilePreferenceSyncIssueReason.UNREPRESENTABLE_JSON_INTEGER
        }
        val decodedVersion = when (decoded) {
            is DecodedProfilePreferenceValue.Core -> 1
            is DecodedProfilePreferenceValue.Rack -> decoded.value.version
            is DecodedProfilePreferenceValue.Workout -> decoded.value.version
            is DecodedProfilePreferenceValue.Led -> decoded.value.version
            is DecodedProfilePreferenceValue.Vbt -> decoded.value.version
        }
        return if (decodedVersion == documentVersion) {
            null
        } else {
            ProfilePreferenceSyncIssueReason.UNSUPPORTED_DOCUMENT_VERSION
        }
    }

    fun decodeCanonical(
        canonical: CanonicalProfilePreferenceSection,
    ): ProfilePreferenceCanonicalColumnsResult {
        if (canonical.key.localProfileId.isBlank() ||
            !isPostgresCompatibleText(canonical.key.localProfileId)
        ) {
            return ProfilePreferenceCanonicalColumnsResult.Invalid(
                ProfilePreferenceSyncIssueReason.INVALID_PROFILE_ID,
            )
        }
        if (canonical.serverUpdatedAtEpochMs !in
            MIN_RFC3339_EPOCH_MILLIS..MAX_RFC3339_EPOCH_MILLIS
        ) {
            return ProfilePreferenceCanonicalColumnsResult.Invalid(
                ProfilePreferenceSyncIssueReason.INVALID_CANONICAL_TIMESTAMP,
            )
        }
        if (canonical.serverRevision !in 0..MAX_EXACT_JSON_INTEGER) {
            return ProfilePreferenceCanonicalColumnsResult.Invalid(
                ProfilePreferenceSyncIssueReason.INVALID_CANONICAL_REVISION,
            )
        }
        canonicalPayloadIssue(
            canonical.key.section,
            canonical.documentVersion,
            canonical.payload,
        )?.let { reason ->
            return ProfilePreferenceCanonicalColumnsResult.Invalid(reason)
        }
        val value = runCatching {
            decodeAndValidateTypedValue(canonical.key.section, canonical.payload)
        }.getOrElse {
            return ProfilePreferenceCanonicalColumnsResult.Invalid(
                ProfilePreferenceSyncIssueReason.INVALID_CANONICAL_PAYLOAD,
            )
        }
        return ProfilePreferenceCanonicalColumnsResult.Valid(
            DecodedProfilePreferenceColumns(
                key = canonical.key,
                documentVersion = canonical.documentVersion,
                serverRevision = canonical.serverRevision,
                serverUpdatedAtEpochMs = canonical.serverUpdatedAtEpochMs,
                value = value,
                normalizedPayload = normalizedPayload(value),
            ),
        )
    }

    fun currentState(
        row: ProfilePreferenceRow,
        section: ProfilePreferenceSectionName,
    ): CurrentProfilePreferenceSyncState = when (section) {
        ProfilePreferenceSectionName.CORE -> CurrentProfilePreferenceSyncState(
            row.core_server_revision,
            row.core_dirty == 1L,
            runCatching {
                CoreProfilePreferences(
                    row.body_weight_kg.toFloat(),
                    WeightUnit.valueOf(row.weight_unit),
                    row.weight_increment.toFloat(),
                ).takeIf { ProfilePreferencesValidator.core(it).isEmpty() }
                    ?.let(::corePayload)
            }.getOrNull(),
        )
        ProfilePreferenceSectionName.RACK -> CurrentProfilePreferenceSyncState(
            row.rack_server_revision,
            row.rack_dirty == 1L,
            runCatching {
                ProfilePreferencesCodec.decodeRack(row.equipment_rack_json)
                    .takeIf { it.validity is ProfilePreferenceValidity.Valid }
                    ?.value
                    ?.let(::rackPayload)
            }.getOrNull(),
        )
        ProfilePreferenceSectionName.WORKOUT -> CurrentProfilePreferenceSyncState(
            row.workout_server_revision,
            row.workout_dirty == 1L,
            runCatching {
                ProfilePreferencesCodec.decodeWorkout(row.workout_preferences_json)
                    .takeIf { it.validity is ProfilePreferenceValidity.Valid }
                    ?.value
                    ?.let(::workoutPayload)
            }.getOrNull(),
        )
        ProfilePreferenceSectionName.LED -> CurrentProfilePreferenceSyncState(
            row.led_server_revision,
            row.led_dirty == 1L,
            runCatching {
                row.led_color_scheme_id
                    .takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
                    ?.toInt()
                    ?.let { ProfilePreferencesCodec.decodeLed(row.led_preferences_json, it) }
                    ?.takeIf { it.validity is ProfilePreferenceValidity.Valid }
                    ?.value
                    ?.let(::ledPayload)
            }.getOrNull(),
        )
        ProfilePreferenceSectionName.VBT -> CurrentProfilePreferenceSyncState(
            row.vbt_server_revision,
            row.vbt_dirty == 1L,
            runCatching {
                ProfilePreferencesCodec.decodeVbt(
                    row.vbt_preferences_json,
                    row.vbt_enabled == 1L,
                ).takeIf { it.validity is ProfilePreferenceValidity.Valid }
                    ?.value
                    ?.let(::vbtPayload)
            }.getOrNull(),
        )
    }

    fun hasCanonicalDivergence(
        row: ProfilePreferenceRow,
        columns: DecodedProfilePreferenceColumns,
    ): Boolean {
        val current = currentState(row, columns.section)
        return !current.dirty &&
            current.serverRevision == columns.serverRevision &&
            current.normalizedPayload != columns.normalizedPayload
    }

    fun hasCanonicalDivergence(
        row: ProfilePreferenceRow,
        canonical: CanonicalProfilePreferenceSection,
    ): Boolean = when (val decoded = decodeCanonical(canonical)) {
        is ProfilePreferenceCanonicalColumnsResult.Invalid -> false
        is ProfilePreferenceCanonicalColumnsResult.Valid ->
            hasCanonicalDivergence(row, decoded.columns)
    }
}

internal data class ProfilePreferencePayloadValidation(
    val isValid: Boolean,
    val reason: String,
)

internal enum class ProfilePreferenceSyncIssueReason {
    INVALID_PROFILE_ID,
    INVALID_LOCAL_DOCUMENT,
    INVALID_CLIENT_MODIFIED_AT,
    UNREPRESENTABLE_JSON_INTEGER,
    INVALID_INT32,
    INVALID_TEXT_TREE,
    LOCAL_ONLY_WIRE_KEY,
    UNSUPPORTED_SECTION,
    UNSUPPORTED_DOCUMENT_VERSION,
    INVALID_CANONICAL_PAYLOAD,
    INVALID_CANONICAL_REVISION,
    INVALID_CANONICAL_TIMESTAMP,
    SECTION_TOO_LARGE,
    REQUEST_TOO_LARGE,
    DUPLICATE_SECTION,
}

internal sealed interface DecodedProfilePreferenceValue {
    data class Core(val value: CoreProfilePreferences) : DecodedProfilePreferenceValue
    data class Rack(val value: RackPreferences) : DecodedProfilePreferenceValue
    data class Workout(val value: WorkoutPreferences) : DecodedProfilePreferenceValue
    data class Led(val value: LedPreferences) : DecodedProfilePreferenceValue
    data class Vbt(val value: VbtPreferences) : DecodedProfilePreferenceValue
}

internal data class DecodedProfilePreferenceColumns(
    val key: ProfilePreferenceSectionKey,
    val documentVersion: Int,
    val serverRevision: Long,
    val serverUpdatedAtEpochMs: Long,
    val value: DecodedProfilePreferenceValue,
    val normalizedPayload: JsonObject,
) {
    val section: ProfilePreferenceSectionName get() = key.section
}

internal sealed interface ProfilePreferenceCanonicalColumnsResult {
    data class Valid(
        val columns: DecodedProfilePreferenceColumns,
    ) : ProfilePreferenceCanonicalColumnsResult

    data class Invalid(
        val reason: ProfilePreferenceSyncIssueReason,
    ) : ProfilePreferenceCanonicalColumnsResult
}

internal data class EncodedDirtyProfilePreferenceRow(
    val valid: List<ProfilePreferenceSectionSyncDto>,
    val unsyncable: List<ProfilePreferenceSyncIssue>,
)

internal data class CurrentProfilePreferenceSyncState(
    val serverRevision: Long,
    val dirty: Boolean,
    val normalizedPayload: JsonObject?,
)
