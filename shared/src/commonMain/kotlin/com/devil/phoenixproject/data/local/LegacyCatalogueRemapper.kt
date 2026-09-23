package com.devil.phoenixproject.data.local

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.database.PersonalRecord
import com.devil.phoenixproject.database.PhoenixDatabase

/**
 * Re-points history, PRs, routines and every other per-exercise row from archived legacy
 * catalogue ids onto their replacement rows.
 *
 * Gated on the data, not on a stored marker: legacy ids keep arriving after the first run
 * (a sync pull of history an older client uploaded, a restore of a pre-remap backup), so
 * every entry point asks the database whether any row still references an archived
 * catalogue id. That check is one indexed probe per archived exercise and finds nothing in
 * the steady state, so callers can run [remapIfNeeded] freely: at startup, after a pull
 * merge, after a restore, and on catalogue import.
 */
class LegacyCatalogueRemapper(database: PhoenixDatabase) {
    private val queries = database.phoenixDatabaseQueries

    /** @return how many legacy ids were remapped (0 when nothing needed it). */
    fun remapIfNeeded(): Int {
        val pending = queries.selectArchivedStockExerciseIdsNeedingRemap().executeAsList().toSet()
        if (pending.isEmpty()) return 0

        // Mappings are resolved over the whole stock catalogue (name matching depends on
        // other archived rows' explicit targets), then applied only where data still waits.
        val mappings = resolveMappings().filterKeys { it in pending }
        if (mappings.isEmpty()) return 0

        queries.transaction {
            for ((oldId, newId) in mappings) {
                queries.mergeLegacyExerciseUserFields(oldId = oldId, newId = newId)
                queries.consumeLegacyExerciseUserFields(oldId)
                queries.copyProfileExerciseBaselinesForCatalogRemap(oldId, newId)
                queries.mergeProfileExerciseBaselinesForCatalogRemap(oldId, newId)
                queries.deleteProfileExerciseBaselinesByExercise(oldId)
                queries.reassignWorkoutSessionExerciseId(newId = newId, oldId = oldId)
                queries.reassignRoutineExerciseId(newId = newId, oldId = oldId)
                resolvePersonalRecordCollisions(oldId = oldId, newId = newId)
                queries.reassignPersonalRecordExerciseId(newId = newId, oldId = oldId)
                queries.reassignExerciseSignatureExerciseId(newId = newId, oldId = oldId)
                queries.reassignAssessmentResultExerciseId(newId = newId, oldId = oldId)
                queries.reassignVelocityOneRepMaxExerciseId(newId = newId, oldId = oldId)
                mergeExerciseMvtCollisions(oldId = oldId, newId = newId)
                queries.reassignExerciseMvtExerciseId(newId = newId, oldId = oldId)
                queries.reassignProgressionEventExerciseId(newId = newId, oldId = oldId)
            }
        }
        Logger.d { "Remapped ${mappings.size} legacy catalogue IDs onto replacement rows" }
        return mappings.size
    }

    private fun resolveMappings(): Map<String, String> {
        val stock = queries.selectStockExercisesForRemap().executeAsList()
        val activeById = stock.filter { it.archived == 0L }.associateBy { it.id }
        val activeByName = stock.filter { it.archived == 0L }
            .groupBy { LegacyCatalogueIdMap.matchKey(it.name) }
            .mapNotNull { (name, rows) -> rows.singleOrNull()?.let { name to it.id } }
            .toMap()
        val archived = stock.filter { it.archived == 1L }

        val activeByStem = stock.filter { it.archived == 0L }
            .groupBy { LegacyCatalogueIdMap.stemKey(it.name) }
            .mapNotNull { (key, rows) -> rows.singleOrNull()?.let { key to it.id } }
            .toMap()

        val mappings = LinkedHashMap<String, String>()
        val mappedTargetByName = LinkedHashMap<String, String>()
        for (row in archived) {
            val explicit = LegacyCatalogueIdMap.explicit[row.id]
            if (explicit != null && activeById.containsKey(explicit)) {
                mappings[row.id] = explicit
                val key = LegacyCatalogueIdMap.matchKey(row.name)
                val existing = mappedTargetByName[key]
                if (existing == null) {
                    mappedTargetByName[key] = explicit
                } else if (existing != explicit) {
                    mappedTargetByName.remove(key)
                }
            }
        }
        for (row in archived) {
            if (row.id in mappings) continue
            val exact = LegacyCatalogueIdMap.matchKey(row.name)
            val byName = activeByName[exact]
                ?: LegacyCatalogueIdMap.nameAliases[exact]?.let { activeByName[it] }
                ?: mappedTargetByName[exact]
                ?: activeByStem[LegacyCatalogueIdMap.stemKey(row.name)]
            if (byName != null && byName != row.id) {
                mappings[row.id] = byName
            }
        }
        return mappings
    }

    private fun resolvePersonalRecordCollisions(oldId: String, newId: String) {
        data class Key(val workoutMode: String, val prType: String, val phase: String, val profileId: String)
        val oldRows = queries.selectPersonalRecordsByExerciseId(oldId).executeAsList()
        val newRows = queries.selectPersonalRecordsByExerciseId(newId).executeAsList()
            .associateBy { Key(it.workoutMode, it.prType, it.phase, it.profile_id) }
        for (old in oldRows) {
            val rival = newRows[Key(old.workoutMode, old.prType, old.phase, old.profile_id)] ?: continue
            val comparator = when (old.prType) {
                "MAX_VOLUME" -> compareBy<PersonalRecord>({ it.volume }, { it.achievedAt })
                else -> compareBy<PersonalRecord>({ it.weight }, { it.oneRepMax }, { it.achievedAt })
            }
            val oldLive = old.deletedAt == null
            val rivalLive = rival.deletedAt == null
            // Live rows beat tombstones before metrics, matching ProfileDeletionMergePolicy.
            val oldWins = when {
                oldLive != rivalLive -> oldLive
                else -> comparator.compare(old, rival) >= 0
            }
            queries.deletePersonalRecordById(if (oldWins) rival.id else old.id)
        }
    }

    private fun mergeExerciseMvtCollisions(oldId: String, newId: String) {
        val oldRows = queries.selectExerciseMvtByExerciseId(oldId).executeAsList()
        val newByProfile = queries.selectExerciseMvtByExerciseId(newId).executeAsList()
            .associateBy { it.profile_id }
        for (old in oldRows) {
            val rival = newByProfile[old.profile_id] ?: continue
            val oldCount = old.sampleCount.coerceAtLeast(0)
            val newCount = rival.sampleCount.coerceAtLeast(0)
            val totalCount = oldCount + newCount
            val mergedMs = if (totalCount == 0L) {
                if (old.updatedAt >= rival.updatedAt) old.personalMvtMs else rival.personalMvtMs
            } else {
                (old.personalMvtMs * oldCount + rival.personalMvtMs * newCount) / totalCount
            }
            queries.upsertExerciseMvt(
                exerciseId = newId,
                profileId = old.profile_id,
                personalMvtMs = mergedMs,
                sampleCount = totalCount,
                updatedAt = maxOf(old.updatedAt, rival.updatedAt),
            )
            queries.deleteExerciseMvt(old.exerciseId, old.profile_id)
        }
    }

    companion object {
        /**
         * Runs [remapIfNeeded] after a bulk write (pull merge, restore, startup) without letting
         * a remap failure fail that write: the remap is atomic and data-gated, so a failure
         * leaves the legacy rows in place and the next entry point retries.
         */
        fun healAfterBulkWrite(database: PhoenixDatabase, source: String) {
            try {
                LegacyCatalogueRemapper(database).remapIfNeeded()
            } catch (e: Exception) {
                Logger.w(e) { "Legacy catalogue remap after $source failed; it will retry on the next entry point" }
            }
        }
    }
}
