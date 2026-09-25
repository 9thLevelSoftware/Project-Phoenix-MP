package com.devil.phoenixproject.data.repository

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.devil.phoenixproject.database.PendingProfileRecovery
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Discovers ambiguous legacy ownership without mutating any profile-owned rows. */
class ProfileRecoveryDiscovery(
    private val database: PhoenixDatabase,
    private val driver: SqlDriver,
    private val now: () -> Long = ::currentTimeMillis,
    private val newId: () -> String = ::generateUUID,
) {
    private val queries = database.phoenixDatabaseQueries

    fun discoverProfileData(profiles: List<UserProfile>) {
        val registeredById = profiles.associateBy(UserProfile::id)
        val candidates = linkedSetOf<String?>()
        ProfileDeletionMergePolicy.directProfileOwnedTables.sorted().forEach { table ->
            val column = profileColumn(table) ?: return@forEach
            candidates += distinctProfileIds(table, column)
        }
        candidates
            .filter { sourceId ->
                when {
                    sourceId == null -> true
                    sourceId == DEFAULT_RECOVERY_PROFILE_ID -> profiles.size > 1
                    sourceId !in registeredById -> true
                    else -> false
                }
            }
            .forEach { sourceId ->
                val counts = loadCounts(sourceId)
                if (counts.tableCounts
                        .filterKeys(PROFILE_RECOVERY_CONTENT_TABLES::contains)
                        .values
                        .sum() == 0L
                ) {
                    return@forEach
                }
                val sourceKey = sourceId?.let { "profile:$it" } ?: "unscoped"
                val profile = sourceId?.let(registeredById::get)
                queries.insertPendingProfileRecoveryIfAbsent(
                    recoveryId = newId(),
                    kind = ProfileRecoveryKind.PROFILE_DATA.name,
                    sourceKey = sourceKey,
                    sourceProfileId = sourceId,
                    sourceProfileName = profile?.name ?: sourceId?.let { "Unknown profile ${it.take(8)}" } ?: "Unassigned data",
                    ownerUserId = profile?.supabaseUserId,
                    countsJson = encodeProfileRecoveryCounts(counts),
                    discoveredAt = now(),
                )
            }
    }

    fun discoverLegacyBaselines(ambiguousCount: Int) {
        if (ambiguousCount <= 0) return
        queries.insertPendingProfileRecoveryIfAbsent(
            recoveryId = newId(),
            kind = ProfileRecoveryKind.LEGACY_BASELINE.name,
            sourceKey = "legacy-baselines",
            sourceProfileId = null,
            sourceProfileName = "Legacy exercise baselines",
            ownerUserId = null,
            countsJson = encodeProfileRecoveryCounts(
                ProfileRecoveryCounts(mapOf("ProfileExerciseBaseline" to ambiguousCount.toLong())),
            ),
            discoveredAt = now(),
        )
    }

    private fun loadCounts(sourceProfileId: String?): ProfileRecoveryCounts {
        val counts = linkedMapOf<String, Long>()
        var cloudRows = 0L
        ProfileDeletionMergePolicy.directProfileOwnedTables.sorted().forEach { table ->
            val columns = tableColumns(table)
            val profileColumn = columns.firstOrNull { it == "profile_id" || it == "profileId" }
                ?: return@forEach
            // A tombstone (deletedAt set) is not recoverable content: a permanently deleted
            // profile keeps its routine / cycle / PR tombstones to block pull resurrection.
            val liveOnly = if ("deletedAt" in columns) "deletedAt IS NULL" else null
            val count = countRows(table, profileColumn, sourceProfileId, liveOnly)
            counts[table] = count
            if (count > 0L && table in PROFILE_RECOVERY_CLOUD_OWNERSHIP_ROOT_TABLES) {
                val cloudPredicate = when (table) {
                    "WorkoutSession" -> "(serverId IS NOT NULL OR portalOrigin = 1)"
                    "Routine", "PersonalRecord" -> "serverId IS NOT NULL"
                    "TrainingCycle" ->
                        "EXISTS (SELECT 1 FROM CycleSyncState s WHERE s.cycle_id = TrainingCycle.id AND s.account_id IS NOT NULL)"
                    else -> null
                }
                if (cloudPredicate != null) {
                    cloudRows += countRows(
                        table,
                        profileColumn,
                        sourceProfileId,
                        listOfNotNull(liveOnly, cloudPredicate).joinToString(" AND "),
                    )
                }
            }
        }
        return ProfileRecoveryCounts(counts, cloudRows)
    }

    private fun profileColumn(table: String): String? = tableColumns(table)
        .firstOrNull { it == "profile_id" || it == "profileId" }

    private fun tableColumns(table: String): Set<String> {
        check(table in ProfileDeletionMergePolicy.directProfileOwnedTables)
        val result = linkedSetOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA table_info($table)",
            mapper = { cursor ->
                while (cursor.next().value) cursor.getString(1)?.let(result::add)
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return result
    }

    private fun distinctProfileIds(table: String, profileColumn: String): Set<String?> {
        val result = linkedSetOf<String?>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT DISTINCT $profileColumn FROM $table",
            mapper = { cursor ->
                while (cursor.next().value) result += cursor.getString(0)
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return result
    }

    private fun countRows(
        table: String,
        profileColumn: String,
        sourceProfileId: String?,
        extraPredicate: String? = null,
    ): Long {
        check(table in ProfileDeletionMergePolicy.directProfileOwnedTables)
        var count = 0L
        val ownerPredicate = if (sourceProfileId == null) "$profileColumn IS NULL" else "$profileColumn = ?"
        val suffix = extraPredicate?.let { " AND $it" }.orEmpty()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM $table WHERE $ownerPredicate$suffix",
            mapper = { cursor ->
                if (cursor.next().value) count = cursor.getLong(0) ?: 0L
                QueryResult.Value(Unit)
            },
            parameters = if (sourceProfileId == null) 0 else 1,
        ) {
            sourceProfileId?.let { bindString(0, it) }
        }
        return count
    }

}

private const val DEFAULT_RECOVERY_PROFILE_ID = "default"

internal val PROFILE_RECOVERY_CONTENT_TABLES = setOf(
    "AssessmentResult",
    "EarnedBadge",
    "ExerciseMvt",
    "ExternalActivity",
    "ExternalBodyMeasurement",
    "ExternalExerciseTemplate",
    "ExternalExerciseTemplateMapping",
    "ExternalProgram",
    "ExternalRoutine",
    "ExternalRoutineFolder",
    "PersonalRecord",
    "ProgressionEvent",
    "Routine",
    "RoutineGroup",
    "StreakHistory",
    "TrainingCycle",
    "VelocityOneRepMaxEstimate",
    "WorkoutSession",
)

/** Portal profile ownership exists only on these independent entity roots. */
internal val PROFILE_RECOVERY_CLOUD_OWNERSHIP_ROOT_TABLES = setOf(
    "WorkoutSession",
    "Routine",
    "TrainingCycle",
    "PersonalRecord",
)

internal fun PendingProfileRecovery.toRecoveryGroup() = PendingProfileRecoveryGroup(
    recoveryId = recovery_id,
    kind = ProfileRecoveryKind.valueOf(kind),
    sourceKey = source_key,
    sourceProfileId = source_profile_id,
    sourceProfileName = source_profile_name,
    ownerUserId = owner_user_id,
    counts = decodeProfileRecoveryCounts(counts_json),
    discoveredAt = discovered_at,
)

internal fun encodeProfileRecoveryCounts(counts: ProfileRecoveryCounts): String = buildJsonObject {
    put("cloudOriginRowCount", counts.cloudOriginRowCount)
    put(
        "tables",
        JsonObject(
            counts.tableCounts.entries
                .sortedBy { it.key }
                .associate { it.key to JsonPrimitive(it.value) },
        ),
    )
}.toString()

internal fun decodeProfileRecoveryCounts(json: String): ProfileRecoveryCounts {
    val root = OwnershipTransferJson.parseToJsonElement(json).jsonObject
    val tables = root["tables"]?.jsonObject?.mapValues { it.value.jsonPrimitive.long }
        ?: emptyMap()
    return ProfileRecoveryCounts(
        tableCounts = tables,
        cloudOriginRowCount = root["cloudOriginRowCount"]?.jsonPrimitive?.long ?: 0L,
    )
}
