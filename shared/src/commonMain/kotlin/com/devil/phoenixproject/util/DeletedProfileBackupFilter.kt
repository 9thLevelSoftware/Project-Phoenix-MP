package com.devil.phoenixproject.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Keeps a profile the user permanently deleted (PR 20) out of backups in both directions:
 * an export never writes it, and a restore of an older backup never brings it back.
 *
 * A row belongs to a deleted profile when any of its profile keys names one. Children of a
 * dropped session, routine or cycle are dropped with it (exports write parents first), so
 * a restore does not report them as orphans.
 */
internal class DeletedProfileBackupFilter(private val deletedProfileIds: Set<String>) {

    /** Rows dropped so far; a restore reports them as skipped, never as failed. */
    var dropped: Int = 0
        private set

    private val droppedParents = mapOf(
        "session" to mutableSetOf<String>(),
        "routine" to mutableSetOf<String>(),
        "routineExercise" to mutableSetOf<String>(),
        "cycle" to mutableSetOf<String>(),
    )

    val isActive: Boolean get() = deletedProfileIds.isNotEmpty()

    /** Whether [raw], one element of array [section], may be written or restored. */
    fun keep(section: String, raw: String): Boolean {
        if (!isActive) return true
        val row = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return true
        return keep(section, row)
    }

    fun keep(section: String, row: JsonObject): Boolean {
        if (!isActive) return true
        val ownedByDeletedProfile = if (section == "userProfiles") {
            row.string("id") in deletedProfileIds
        } else {
            PROFILE_KEYS.any { key -> row.string(key)?.let { it in deletedProfileIds } == true }
        }
        val orphaned = PARENT_KEYS[section]?.let { (namespace, key) ->
            row.string(key)?.let { it in droppedParents.getValue(namespace) } == true
        } == true
        if (!ownedByDeletedProfile && !orphaned) return true
        PARENT_OF[section]?.let { namespace -> row.string("id")?.let { droppedParents.getValue(namespace) += it } }
        dropped++
        return false
    }

    /** Filters every array section of a backup's `data` object. */
    fun filterData(data: JsonObject): JsonObject {
        if (!isActive) return data
        return JsonObject(
            data.mapValues { (section, value) ->
                if (value is JsonArray) {
                    JsonArray(value.filter { element -> element !is JsonObject || keep(section, element) })
                } else {
                    value
                }
            },
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        val PROFILE_KEYS = listOf("profileId", "sourceProfileId", "targetProfileId", "originalProfileId")

        /** Sections whose ids are parents of other sections' rows. */
        val PARENT_OF = mapOf(
            "workoutSessions" to "session",
            "routines" to "routine",
            "routineExercises" to "routineExercise",
            "trainingCycles" to "cycle",
        )

        /** Child section -> (parent namespace, key naming the parent). */
        val PARENT_KEYS = mapOf(
            "metricSamples" to ("session" to "sessionId"),
            "completedSets" to ("session" to "sessionId"),
            "routineExercises" to ("routine" to "routineId"),
            "supersets" to ("routine" to "routineId"),
            "plannedSets" to ("routineExercise" to "routineExerciseId"),
            "cycleDays" to ("cycle" to "cycleId"),
            "cycleProgress" to ("cycle" to "cycleId"),
            "cycleProgressions" to ("cycle" to "cycleId"),
        )
    }
}
