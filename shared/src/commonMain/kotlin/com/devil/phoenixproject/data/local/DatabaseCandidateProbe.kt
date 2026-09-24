package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver

/**
 * What a database candidate in a DB_DUAL_DATABASES conflict holds (#764). Only a candidate
 * proven EMPTY may be set aside automatically; anything uncertain keeps the conflict fail-closed.
 */
internal enum class CandidateContent {
    /** Opened and passed quick_check; holds nothing a fresh install would not write itself. */
    EMPTY,

    /** Holds at least one row a user could have created (or is too large to be a fresh install). */
    HAS_USER_DATA,

    /** Could not be copied, opened, checked or classified. */
    UNINSPECTABLE,
}

/** Folder, beside the database files, that set-aside candidates are moved into (never deleted). */
internal const val DATABASE_QUARANTINE_DIRECTORY = "phoenix-quarantine"

/** Scratch copies used by probes are named with this prefix and deleted after each probe. */
internal const val DATABASE_PROBE_SCRATCH_PREFIX = "phoenix-probe-"

/**
 * A freshly seeded Phoenix database (catalogue, default profile) is a few MB. A candidate larger
 * than this is treated as holding user data without being copied: the classification can only
 * err towards keeping a file, and startup never copies a large database just to inspect it.
 */
internal const val DATABASE_PROBE_SIZE_LIMIT_BYTES = 32L * 1024 * 1024

/** Suffixes copied with a candidate for probing: committed WAL frames and a hot rollback journal. */
internal val DATABASE_PROBE_SIDECAR_SUFFIXES = listOf("-wal", "-journal")

/** Every sidecar a candidate can have, in the order they are moved when it is set aside. */
internal val DATABASE_SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")

/** Name of the folder one set-aside candidate goes into, e.g. `1727150000000-CANONICAL_LEGACY_TARGET-vitruvian.db`. */
internal fun quarantineFolderName(
    timestampMs: Long,
    reason: DatabaseDiagnosticReason,
    fileName: String,
): String = "$timestampMs-${reason.name}-$fileName"

/** The read-only questions [DatabaseUserDataClassifier] asks of a candidate. */
internal interface DatabaseProbeQueries {
    fun quickCheck(): List<String?>

    fun tableNames(): List<String>

    fun columnNames(table: String): Set<String>

    /** True when `SELECT EXISTS(<[select]>)` is 1. */
    fun exists(select: String): Boolean
}

/** [DatabaseProbeQueries] over any SQLDelight driver (iOS native, JVM tests). */
internal class SqlDriverProbeQueries(private val driver: SqlDriver) : DatabaseProbeQueries {
    override fun quickCheck(): List<String?> = rows("PRAGMA quick_check") { it.getString(0) }

    override fun tableNames(): List<String> =
        rows("SELECT name FROM sqlite_master WHERE type = 'table'") { it.getString(0) }.filterNotNull()

    override fun columnNames(table: String): Set<String> =
        rows("PRAGMA table_info(${quoteIdentifier(table)})") { it.getString(1) }.filterNotNull().toSet()

    override fun exists(select: String): Boolean = rows("SELECT EXISTS($select)") { it.getLong(0) }.firstOrNull() == 1L

    private fun <T> rows(sql: String, map: (SqlCursor) -> T): List<T> {
        val values = mutableListOf<T>()
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                while (cursor.next().value) values += map(cursor)
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return values
    }
}

internal fun quoteIdentifier(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

/**
 * Decides whether a database candidate holds user data (#764). The rules err towards
 * [CandidateContent.HAS_USER_DATA]: any row in a table not listed here counts, including tables
 * this version does not know, so a misjudgement keeps a file instead of setting it aside.
 */
internal object DatabaseUserDataClassifier {
    /** Tables that only ever hold catalogue media, repair bookkeeping or device logs. */
    private val IGNORED_TABLES = setOf(
        "android_metadata",
        "appliedDataRepair",
        "exerciseImage",
        "exerciseVideo",
        "connectionLog",
        "diagnosticsHistory",
    ).map { it.lowercase() }.toSet()

    private val EXERCISE_USER_FIELDS = listOf(
        "isCustom" to "= 1",
        "isFavorite" to "= 1",
        "timesPerformed" to "> 0",
        "lastPerformed" to "IS NOT NULL",
        "one_rep_max_kg" to "IS NOT NULL",
        "mvtOverrideMs" to "IS NOT NULL",
    )

    private val GAMIFICATION_FIELDS = listOf(
        "totalWorkouts" to "> 0",
        "totalReps" to "> 0",
        "totalVolumeKg" to "> 0",
        "longestStreak" to "> 0",
        "currentStreak" to "> 0",
        "uniqueExercisesUsed" to "> 0",
        "prsAchieved" to "> 0",
        "lastWorkoutDate" to "IS NOT NULL",
        "streakStartDate" to "IS NOT NULL",
        "serverId" to "IS NOT NULL",
    )

    /** Seeded values of the default profile; `createdAt` and `isActive` are bookkeeping, not data. */
    private val PROFILE_FIELDS = listOf(
        "id" to "<> 'default'",
        "name" to "<> 'Default'",
        "colorIndex" to "<> 0",
        "supabase_user_id" to "IS NOT NULL",
        "subscription_status" to "<> 'free'",
        "subscription_expires_at" to "IS NOT NULL",
        "last_auth_at" to "IS NOT NULL",
    )

    /**
     * The default profile's preferences row counts as data once any setting differs from the
     * schema default it is seeded with (PhoenixDatabase.sq). Sync metadata (generations, revisions,
     * timestamps) is not compared: a row whose values all equal the seed loses nothing when set aside.
     */
    private val PREFERENCE_FIELDS = listOf(
        "profile_id" to "<> 'default'",
        "body_weight_kg" to "<> 0",
        "weight_unit" to "<> 'LB'",
        "weight_increment" to "<> -1",
        "equipment_rack_json" to "<> '{\"version\":1,\"items\":[]}'",
        "workout_preferences_json" to "<> '{\"version\":1}'",
        "led_color_scheme_id" to "<> 0",
        "led_preferences_json" to "<> '{\"version\":1}'",
        "vbt_enabled" to "<> 1",
        "vbt_preferences_json" to "<> '{\"version\":1}'",
    )

    private val RPG_FIELDS = listOf(
        "strength" to "> 0",
        "power" to "> 0",
        "stamina" to "> 0",
        "consistency" to "> 0",
        "mastery" to "> 0",
    )

    fun classify(queries: DatabaseProbeQueries): CandidateContent {
        return try {
            if (queries.quickCheck() != listOf("ok")) return CandidateContent.UNINSPECTABLE
            for (table in queries.tableNames()) {
                val key = table.lowercase()
                if (key.startsWith("sqlite_") || key in IGNORED_TABLES) continue
                if (holdsUserData(queries, table, key)) return CandidateContent.HAS_USER_DATA
            }
            CandidateContent.EMPTY
        } catch (_: Throwable) {
            CandidateContent.UNINSPECTABLE
        }
    }

    private fun holdsUserData(queries: DatabaseProbeQueries, table: String, key: String): Boolean {
        val from = "SELECT 1 FROM ${quoteIdentifier(table)}"
        return when (key) {
            // A fresh install creates exactly one profile (UserProfileRepository.ensureDefaultProfileSync).
            // Another profile, or any change to that one's seeded values, is data.
            "userprofile" -> queries.existsWhereAny(from, table, PROFILE_FIELDS)

            // Seeded for the default profile at first launch. Another profile's row, or a default
            // row with any setting changed from its seed value, is data.
            "userprofilepreferences" -> queries.existsWhereAny(from, table, PREFERENCE_FIELDS)

            // The bundled catalogue is seeded; only user-set fields and custom exercises are data.
            "exercise" -> queries.existsWhereAny(from, table, EXERCISE_USER_FIELDS)

            // Opening the gamification screen can write an all-zero row.
            "gamificationstats" -> queries.existsWhereAny(from, table, GAMIFICATION_FIELDS)

            "rpgattributes" -> queries.existsWhereAny(from, table, RPG_FIELDS)

            else -> queries.exists(from)
        }
    }

    /**
     * Rows with any of [fields] set. Only columns this candidate has are checked; when it has none
     * of them, any row at all counts as data.
     */
    private fun DatabaseProbeQueries.existsWhereAny(
        from: String,
        table: String,
        fields: List<Pair<String, String>>,
    ): Boolean {
        val columns = columnNames(table)
        val conditions = fields.filter { (column, _) -> column in columns }
            .map { (column, predicate) -> "${quoteIdentifier(column)} $predicate" }
        if (conditions.isEmpty()) return exists(from)
        return exists("$from WHERE ${conditions.joinToString(" OR ")}")
    }
}
