package com.devil.phoenixproject.data.sync

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.data.preferences.ProfilePreferencesCodec
import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.ProfilePreferenceSectionName

/** Sync-layer boundary; bind only in SyncModule and do not expose through profile/domain APIs. */
interface ProfilePreferenceSyncRepository {
    suspend fun snapshotDirtySections(): ProfilePreferenceDirtySnapshot

    suspend fun applyPushOutcomes(
        outcomes: List<ProfilePreferencePushOutcome>,
    ): ProfilePreferenceSyncApplyReport

    suspend fun applyPulledSections(
        sections: List<CanonicalProfilePreferenceSection>,
    ): ProfilePreferenceSyncApplyReport
}

internal class SqlDelightProfilePreferenceSyncRepository(
    private val database: VitruvianDatabase,
    private val codec: ProfilePreferenceSyncCodec,
    private val logCanonicalDivergence: (ProfilePreferenceSectionName) -> Unit = { section ->
        Logger.w(tag = "ProfilePreferenceSync") {
            "Repairing equal-revision canonical divergence for section=$section"
        }
    },
) : ProfilePreferenceSyncRepository {
    private val queries = database.vitruvianDatabaseQueries

    override suspend fun snapshotDirtySections(): ProfilePreferenceDirtySnapshot {
        val encoded = queries.selectDirtyProfilePreferenceRows().executeAsList()
            .map(codec::encodeDirtyRow)
        return ProfilePreferenceDirtySnapshot(
            valid = encoded.flatMap { it.valid },
            unsyncable = encoded.flatMap { it.unsyncable },
        )
    }

    override suspend fun applyPushOutcomes(
        outcomes: List<ProfilePreferencePushOutcome>,
    ): ProfilePreferenceSyncApplyReport {
        var applied = 0
        var preserved = 0
        var invalid = 0
        outcomes.groupBy { it.key.localProfileId }.forEach { (_, profileOutcomes) ->
            database.transaction {
                profileOutcomes.forEach outcomeLoop@{ outcome ->
                    val canonical = outcome.canonical ?: return@outcomeLoop
                    if (canonical.key != outcome.key ||
                        canonical.serverRevision != outcome.serverRevision
                    ) {
                        invalid++
                        return@outcomeLoop
                    }
                    val decoded = codec.decodeCanonical(canonical)
                    if (decoded is ProfilePreferenceCanonicalColumnsResult.Invalid) {
                        invalid++
                        return@outcomeLoop
                    }
                    val columns =
                        (decoded as ProfilePreferenceCanonicalColumnsResult.Valid).columns
                    if (applyCanonicalForGeneration(columns, outcome.sentLocalGeneration)) {
                        applied++
                    } else if (
                        advanceRevisionForNewerGeneration(columns, outcome.sentLocalGeneration)
                    ) {
                        preserved++
                    }
                }
            }
        }
        return ProfilePreferenceSyncApplyReport(
            applied = applied,
            preservedNewerLocal = preserved,
            invalid = invalid,
        )
    }

    override suspend fun applyPulledSections(
        sections: List<CanonicalProfilePreferenceSection>,
    ): ProfilePreferenceSyncApplyReport {
        var applied = 0
        var unknown = 0
        var invalid = 0
        sections.groupBy { it.key.localProfileId }.forEach { (profileId, canonicals) ->
            database.transaction {
                if (queries.selectProfilePreferenceSyncRow(profileId).executeAsOneOrNull() == null) {
                    unknown += canonicals.size
                    return@transaction
                }
                canonicals.forEach { canonical ->
                    when (val decoded = codec.decodeCanonical(canonical)) {
                        is ProfilePreferenceCanonicalColumnsResult.Invalid -> invalid++
                        is ProfilePreferenceCanonicalColumnsResult.Valid -> {
                            if (applyPulledWhenClean(decoded.columns)) applied++
                        }
                    }
                }
            }
        }
        return ProfilePreferenceSyncApplyReport(
            applied = applied,
            ignoredUnknownProfile = unknown,
            invalid = invalid,
        )
    }

    private fun applyCanonicalForGeneration(
        columns: DecodedProfilePreferenceColumns,
        sentLocalGeneration: Long,
    ): Boolean {
        when (val value = columns.value) {
            is DecodedProfilePreferenceValue.Core -> queries.applyCoreCanonicalForGeneration(
                body_weight_kg = value.value.bodyWeightKg.toDouble(),
                weight_unit = value.value.weightUnit.name,
                weight_increment = value.value.weightIncrement.toDouble(),
                server_updated_at = columns.serverUpdatedAtEpochMs,
                server_revision = columns.serverRevision,
                profile_id = columns.key.localProfileId,
                sent_local_generation = sentLocalGeneration,
            )
            is DecodedProfilePreferenceValue.Rack -> queries.applyRackCanonicalForGeneration(
                equipment_rack_json = ProfilePreferencesCodec.encodeRack(value.value),
                server_updated_at = columns.serverUpdatedAtEpochMs,
                server_revision = columns.serverRevision,
                profile_id = columns.key.localProfileId,
                sent_local_generation = sentLocalGeneration,
            )
            is DecodedProfilePreferenceValue.Workout -> queries.applyWorkoutCanonicalForGeneration(
                workout_preferences_json = ProfilePreferencesCodec.encodeWorkout(value.value),
                server_updated_at = columns.serverUpdatedAtEpochMs,
                server_revision = columns.serverRevision,
                profile_id = columns.key.localProfileId,
                sent_local_generation = sentLocalGeneration,
            )
            is DecodedProfilePreferenceValue.Led -> queries.applyLedCanonicalForGeneration(
                led_color_scheme_id = value.value.colorScheme.toLong(),
                led_preferences_json = ProfilePreferencesCodec.encodeLed(value.value),
                server_updated_at = columns.serverUpdatedAtEpochMs,
                server_revision = columns.serverRevision,
                profile_id = columns.key.localProfileId,
                sent_local_generation = sentLocalGeneration,
            )
            is DecodedProfilePreferenceValue.Vbt -> queries.applyVbtCanonicalForGeneration(
                vbt_enabled = if (value.value.enabled) 1L else 0L,
                vbt_preferences_json = ProfilePreferencesCodec.encodeVbt(value.value),
                server_updated_at = columns.serverUpdatedAtEpochMs,
                server_revision = columns.serverRevision,
                profile_id = columns.key.localProfileId,
                sent_local_generation = sentLocalGeneration,
            )
        }
        return changedRowCount()
    }

    private fun advanceRevisionForNewerGeneration(
        columns: DecodedProfilePreferenceColumns,
        sentLocalGeneration: Long,
    ): Boolean {
        when (columns.value) {
            is DecodedProfilePreferenceValue.Core -> queries.advanceCoreRevisionForNewerGeneration(
                server_revision = columns.serverRevision,
                profile_id = columns.key.localProfileId,
                sent_local_generation = sentLocalGeneration,
            )
            is DecodedProfilePreferenceValue.Rack -> queries.advanceRackRevisionForNewerGeneration(
                server_revision = columns.serverRevision,
                profile_id = columns.key.localProfileId,
                sent_local_generation = sentLocalGeneration,
            )
            is DecodedProfilePreferenceValue.Workout ->
                queries.advanceWorkoutRevisionForNewerGeneration(
                    server_revision = columns.serverRevision,
                    profile_id = columns.key.localProfileId,
                    sent_local_generation = sentLocalGeneration,
                )
            is DecodedProfilePreferenceValue.Led -> queries.advanceLedRevisionForNewerGeneration(
                server_revision = columns.serverRevision,
                profile_id = columns.key.localProfileId,
                sent_local_generation = sentLocalGeneration,
            )
            is DecodedProfilePreferenceValue.Vbt -> queries.advanceVbtRevisionForNewerGeneration(
                server_revision = columns.serverRevision,
                profile_id = columns.key.localProfileId,
                sent_local_generation = sentLocalGeneration,
            )
        }
        return changedRowCount()
    }

    private fun applyPulledWhenClean(columns: DecodedProfilePreferenceColumns): Boolean {
        val row = queries.selectProfilePreferenceSyncRow(columns.key.localProfileId)
            .executeAsOneOrNull()
            ?: return false
        if (codec.hasCanonicalDivergence(row, columns)) {
            logCanonicalDivergence(columns.section)
        }
        when (val value = columns.value) {
            is DecodedProfilePreferenceValue.Core -> queries.applyPulledCoreWhenClean(
                body_weight_kg = value.value.bodyWeightKg.toDouble(),
                weight_unit = value.value.weightUnit.name,
                weight_increment = value.value.weightIncrement.toDouble(),
                server_updated_at = columns.serverUpdatedAtEpochMs,
                server_revision = columns.serverRevision,
                profile_id = columns.key.localProfileId,
            )
            is DecodedProfilePreferenceValue.Rack -> queries.applyPulledRackWhenClean(
                equipment_rack_json = ProfilePreferencesCodec.encodeRack(value.value),
                server_updated_at = columns.serverUpdatedAtEpochMs,
                server_revision = columns.serverRevision,
                profile_id = columns.key.localProfileId,
            )
            is DecodedProfilePreferenceValue.Workout -> queries.applyPulledWorkoutWhenClean(
                workout_preferences_json = ProfilePreferencesCodec.encodeWorkout(value.value),
                server_updated_at = columns.serverUpdatedAtEpochMs,
                server_revision = columns.serverRevision,
                profile_id = columns.key.localProfileId,
            )
            is DecodedProfilePreferenceValue.Led -> queries.applyPulledLedWhenClean(
                led_color_scheme_id = value.value.colorScheme.toLong(),
                led_preferences_json = ProfilePreferencesCodec.encodeLed(value.value),
                server_updated_at = columns.serverUpdatedAtEpochMs,
                server_revision = columns.serverRevision,
                profile_id = columns.key.localProfileId,
            )
            is DecodedProfilePreferenceValue.Vbt -> queries.applyPulledVbtWhenClean(
                vbt_enabled = if (value.value.enabled) 1L else 0L,
                vbt_preferences_json = ProfilePreferencesCodec.encodeVbt(value.value),
                server_updated_at = columns.serverUpdatedAtEpochMs,
                server_revision = columns.serverRevision,
                profile_id = columns.key.localProfileId,
            )
        }
        return changedRowCount()
    }

    private fun changedRowCount(): Boolean =
        queries.selectChangedRowCount().executeAsOne() > 0L
}
