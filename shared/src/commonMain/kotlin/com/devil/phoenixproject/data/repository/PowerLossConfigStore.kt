package com.devil.phoenixproject.data.repository

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.database.PhoenixDatabaseQueries
import com.devil.phoenixproject.domain.powerloss.PowerLossConfig
import com.devil.phoenixproject.domain.powerloss.TargetMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Issue #1392 (packet P4 remainder) — durable per-exercise power-loss target config.
 *
 * One row per routine exercise in the additive `PowerLossExerciseConfig` table
 * (migration 65). Contract invariants:
 *
 *  - **Absent row = inert default:** [PowerLossConfig.DEFAULT] — REP_TARGET with
 *    nulls, never fabricated zeros. Legacy rows keep their REP meaning.
 *  - **Strict readers fail safe:** an unknown persisted target enum or unknown
 *    stored format version resolves to the inert default (never arms POWER —
 *    spec §7, SD1). The raw value stays inspectable in the row itself.
 *  - **Local-only:** no sync wire semantics — never pushed, never marked synced
 *    or backed up. An acknowledged legacy push must not touch these rows.
 *  - **Restore is additive:** backup restore uses INSERT-if-absent (local wins);
 *    absent backup sections never wipe local config.
 *  - **Idempotent local writes:** delete + reinsert inside the caller's
 *    transaction (leaf row, no children; never INSERT OR REPLACE a parent).
 */
internal object PowerLossConfigStore {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
        coerceInputValues = false
        encodeDefaults = true
        explicitNulls = true
    }

    /** Current stored-row format version. */
    const val FORMAT_VERSION: Int = 1

    /**
     * Persist [config] for one routine exercise. A default config removes any row
     * (absent = default), so the table only ever carries real user intent. Runs
     * inside the caller's transaction.
     */
    internal fun writeConfig(
        queries: PhoenixDatabaseQueries,
        routineExerciseId: String,
        config: PowerLossConfig,
        nowMs: Long,
    ) {
        queries.deletePowerLossExerciseConfig(routineExerciseId)
        if (config == PowerLossConfig.DEFAULT) return
        queries.insertPowerLossExerciseConfigIfAbsent(
            routine_exercise_id = routineExerciseId,
            format_version = FORMAT_VERSION.toLong(),
            target_mode = config.targetMode.name,
            threshold_percent = config.thresholdPercent.toLong(),
            max_working_reps = config.maxWorkingReps?.toLong(),
            require_two_consecutive = if (config.requireTwoConsecutive) 1L else 0L,
            saved_nominal_reps = config.savedNominalReps?.toLong(),
            updated_at = nowMs,
        )
    }

    /**
     * Read one exercise's config. Absent or unsupported rows resolve to
     * [PowerLossConfig.DEFAULT] (inert REP_TARGET, nulls not zeros).
     */
    internal fun readConfig(queries: PhoenixDatabaseQueries, routineExerciseId: String): PowerLossConfig {
        val row = queries.selectPowerLossExerciseConfigsByRoutineExerciseIds(listOf(routineExerciseId))
            .executeAsList()
            .firstOrNull { it.routine_exercise_id == routineExerciseId }
            ?: return PowerLossConfig.DEFAULT
        return decodeRow(
            targetMode = row.target_mode,
            thresholdPercent = row.threshold_percent.toInt(),
            maxWorkingReps = row.max_working_reps?.toInt(),
            requireTwoConsecutive = row.require_two_consecutive != 0L,
            savedNominalReps = row.saved_nominal_reps?.toInt(),
            formatVersion = row.format_version.toInt(),
        )
    }

    /**
     * Strict row decode. Unknown stored format version or unknown target enum →
     * the full inert default (never POWER; never a fabricated zero). In-range
     * values keep their exact stored meaning.
     */
    internal fun decodeRow(
        targetMode: String,
        thresholdPercent: Int,
        maxWorkingReps: Int?,
        requireTwoConsecutive: Boolean,
        savedNominalReps: Int?,
        formatVersion: Int = FORMAT_VERSION,
    ): PowerLossConfig {
        if (formatVersion != FORMAT_VERSION) {
            Logger.w {
                "PowerLossConfigStore: unknown stored format version $formatVersion; reading inert REP defaults"
            }
            return PowerLossConfig.DEFAULT
        }
        val mode = TargetMode.fromPersisted(targetMode)
        if (mode == null) {
            Logger.w {
                "PowerLossConfigStore: unknown target mode '$targetMode'; reading inert REP defaults (never arms POWER)"
            }
            return PowerLossConfig.DEFAULT
        }
        return PowerLossConfig(
            targetMode = mode,
            thresholdPercent = thresholdPercent,
            maxWorkingReps = maxWorkingReps ?: PowerLossConfig.DEFAULT.maxWorkingReps,
            requireTwoConsecutive = requireTwoConsecutive,
            savedNominalReps = savedNominalReps,
        )
    }

    /**
     * INSERT-if-absent restore (backup restore path): local config wins, absent
     * rows are filled. An existing row is never replaced or wiped. Unknown enum
     * or version rows are stored verbatim (raw values stay inspectable) but are
     * read back inert by [readConfig].
     */
    internal fun restoreIfAbsent(
        queries: PhoenixDatabaseQueries,
        routineExerciseId: String,
        targetMode: String,
        thresholdPercent: Int,
        maxWorkingReps: Int?,
        requireTwoConsecutive: Boolean,
        savedNominalReps: Int?,
        formatVersion: Int,
        nowMs: Long,
    ) {
        queries.insertPowerLossExerciseConfigIfAbsent(
            routine_exercise_id = routineExerciseId,
            format_version = formatVersion.toLong(),
            target_mode = targetMode,
            threshold_percent = thresholdPercent.toLong(),
            max_working_reps = maxWorkingReps?.toLong(),
            require_two_consecutive = if (requireTwoConsecutive) 1L else 0L,
            saved_nominal_reps = savedNominalReps?.toLong(),
            updated_at = nowMs,
        )
    }
}

/**
 * Versioned JSON encoding of [PowerLossConfig] for opaque single-column storage
 * surfaces that need it (CSV metadata mirrors use plain columns instead).
 * [decodeConfigJson] fails safe: corrupt or unknown-version payloads read as
 * [PowerLossConfig.DEFAULT], never as armed POWER and never as fabricated zeros.
 */
@Serializable
internal data class PowerLossConfigStored(
    val formatVersion: Int = PowerLossConfigStore.FORMAT_VERSION,
    val targetMode: String = TargetMode.REP_TARGET.name,
    val thresholdPercent: Int = PowerLossConfig.DEFAULT.thresholdPercent,
    val maxWorkingReps: Int? = null,
    val requireTwoConsecutive: Boolean = false,
    val savedNominalReps: Int? = null,
)

private val configJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
    coerceInputValues = false
}

internal fun encodeConfigJson(config: PowerLossConfig): String = configJson.encodeToString(
    PowerLossConfigStored.serializer(),
    PowerLossConfigStored(
        formatVersion = PowerLossConfigStore.FORMAT_VERSION,
        targetMode = config.targetMode.name,
        thresholdPercent = config.thresholdPercent,
        maxWorkingReps = config.maxWorkingReps,
        requireTwoConsecutive = config.requireTwoConsecutive,
        savedNominalReps = config.savedNominalReps,
    ),
)

internal fun decodeConfigJson(raw: String): PowerLossConfig = try {
    val stored = configJson.decodeFromString(PowerLossConfigStored.serializer(), raw)
    PowerLossConfigStore.decodeRow(
        targetMode = stored.targetMode,
        thresholdPercent = stored.thresholdPercent,
        maxWorkingReps = stored.maxWorkingReps,
        requireTwoConsecutive = stored.requireTwoConsecutive,
        savedNominalReps = stored.savedNominalReps,
        formatVersion = stored.formatVersion,
    )
} catch (e: Exception) {
    Logger.w { "PowerLossConfigStore: unreadable config payload; reading inert REP defaults" }
    PowerLossConfig.DEFAULT
}
