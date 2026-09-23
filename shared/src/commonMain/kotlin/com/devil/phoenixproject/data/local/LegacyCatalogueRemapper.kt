package com.devil.phoenixproject.data.local

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.database.PersonalRecord
import com.devil.phoenixproject.database.PhoenixDatabase
import kotlin.coroutines.cancellation.CancellationException

/** One stock catalogue row as the legacy id resolution sees it. */
data class LegacyCatalogueRow(val id: String, val name: String)

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
 *
 * Retired ids the device holds no row for at all (a fresh install) never reach this remap;
 * restore and pull translate those on the way in with [LegacyCatalogueTranslator], which
 * uses the same [resolveMappings].
 */
class LegacyCatalogueRemapper(database: PhoenixDatabase) {
    private val queries = database.phoenixDatabaseQueries

    /** @return how many legacy ids were remapped (0 when nothing needed it). */
    fun remapIfNeeded(): Int {
        val pending = queries.selectArchivedStockExerciseIdsNeedingRemap().executeAsList().toSet()
        if (pending.isEmpty()) return 0

        // Mappings are resolved over the whole stock catalogue (name matching depends on
        // other archived rows' explicit targets), then applied only where data still waits.
        val stock = queries.selectStockExercisesForRemap().executeAsList()
        val mappings = resolveMappings(
            active = stock.filter { it.archived == 0L }.map { LegacyCatalogueRow(it.id, it.name) },
            archived = stock.filter { it.archived == 1L }.map { LegacyCatalogueRow(it.id, it.name) },
        ).filterKeys { it in pending }
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
         * The one legacy -> replacement resolution every entry point shares (this remap, restore
         * translation, pull lookup): an explicit id mapping first, then the reviewed name
         * fallbacks: exact name, [LegacyCatalogueIdMap.nameAliases], a name another archived row
         * was explicitly mapped under, then [LegacyCatalogueIdMap.stemKey].
         */
        fun resolveMappings(active: List<LegacyCatalogueRow>, archived: List<LegacyCatalogueRow>): Map<String, String> {
            val activeById = active.associateBy { it.id }
            val activeByName = active
                .groupBy { LegacyCatalogueIdMap.matchKey(it.name) }
                .mapNotNull { (name, rows) -> rows.singleOrNull()?.let { name to it.id } }
                .toMap()
            val activeByStem = active
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

        /**
         * Runs [remapIfNeeded] after a bulk write (pull merge, restore, startup) without letting
         * a remap failure fail that write: the remap is atomic and data-gated, so a failure
         * leaves the legacy rows in place and the next entry point retries.
         */
        fun healAfterBulkWrite(database: PhoenixDatabase, source: String) {
            try {
                LegacyCatalogueRemapper(database).remapIfNeeded()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e) { "Legacy catalogue remap after $source failed; it will retry on the next entry point" }
            }
        }
    }
}

/**
 * Translates a retired catalogue id that this device holds **no** row for (a fresh install,
 * where the bundled catalogue carries only the replacement ids) onto its replacement, with
 * exactly the resolution [LegacyCatalogueRemapper] uses: the id is resolved as if it were an
 * archived row named [translate]'s `exerciseName`, alongside the device's real archived rows.
 *
 * One instance per bulk write (a restore, a pull): the stock catalogue is read once, results
 * are memoised, and names seen for an id are remembered so later rows of the same write that
 * carry no name (baselines, progression events) resolve the same way.
 */
class LegacyCatalogueTranslator(database: PhoenixDatabase) {
    private val queries = database.phoenixDatabaseQueries
    private val stock by lazy { queries.selectStockExercisesForRemap().executeAsList() }
    private val learnedNames = HashMap<String, String>()
    private val resolved = HashMap<Pair<String, String?>, String?>()

    /**
     * @return [exerciseId] itself when this device holds that row (an archived legacy row is
     * kept for [LegacyCatalogueRemapper] to merge) or when nothing resolves; otherwise the
     * replacement catalogue id.
     */
    fun translate(exerciseId: String, exerciseName: String?): String {
        if (queries.selectExerciseById(exerciseId).executeAsOneOrNull() != null) return exerciseId
        // A custom exercise is the user's own; never link it to a stock row by name.
        if (exerciseId.startsWith(CUSTOM_EXERCISE_ID_PREFIX)) return exerciseId
        val name = exerciseName?.takeIf { it.isNotBlank() }
            ?.also { learnedNames.getOrPut(exerciseId) { it } }
            ?: learnedNames[exerciseId]
        return resolved.getOrPut(exerciseId to name) { resolve(exerciseId, name) } ?: exerciseId
    }

    private fun resolve(exerciseId: String, name: String?): String? {
        val active = stock.filter { it.archived == 0L }.map { LegacyCatalogueRow(it.id, it.name) }
        val archived = stock.filter { it.archived == 1L }.map { LegacyCatalogueRow(it.id, it.name) }
        val target = LegacyCatalogueRemapper.resolveMappings(
            active = active,
            archived = archived + LegacyCatalogueRow(exerciseId, name.orEmpty()),
        )[exerciseId] ?: return null
        // Without a name only the explicit id mapping can be trusted.
        return target.takeIf { name != null || LegacyCatalogueIdMap.explicit[exerciseId] == it }
    }

    private companion object {
        const val CUSTOM_EXERCISE_ID_PREFIX = "custom_"
    }
}
