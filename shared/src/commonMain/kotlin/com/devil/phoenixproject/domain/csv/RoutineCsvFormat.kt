package com.devil.phoenixproject.domain.csv

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.util.Constants

/**
 * Phoenix routine CSV, versions 1 (#772) and 2 (#896).
 *
 * Both versions share the first line `# phoenix_routine_csv_version=<version>` and one row per
 * routine exercise. Rows of one routine share its id or name. Sets are pipe-separated:
 * `set_reps` holds positive integers or `AMRAP`, `set_weights_kg` holds kg per cable with `.` as
 * the decimal separator, and both lists have one entry per set. `is_amrap` is true exactly when
 * every set is AMRAP. A `superset_key` groups two or more rows of a routine into one superset;
 * blank means standalone. `exercise_order` is the flat display order, supersets included; a
 * superset sits at its first exercise's position, so `superset_order` is written for reference
 * and only checked for consistency on import; `superset_color` (optional) is a SupersetColors
 * index, blank picks the next free colour. Blank lines and further `#` lines are ignored. Cells
 * follow RFC 4180: text cells are taken as written (spaces included), and a quoted cell may
 * contain commas, quotes and line breaks. Number and code cells are trimmed.
 *
 * Version 1 ([VERSION_LINE], header [COLUMNS]; the first release dropped the optional
 * `superset_color` column, [COLUMNS_WITHOUT_SUPERSET_COLOR], and that header is still read) only
 * has the fields above. Export refuses a routine that uses anything else, so a round trip never
 * drops a setting silently. Version 1 is still read exactly as released but no longer written.
 *
 * Version 2 ([VERSION_V2_LINE], header [COLUMNS_V2]: [COLUMNS] plus [COLUMNS_V2_ONLY]) adds every
 * advanced routine setting, so routines using them export and re-import losslessly:
 * [COLUMNS_V2_ONLY] names carry % of PR configuration (toggle, percent, PR type, per-set
 * percents, scaling basis), warm-up sets, equipment rack defaults and behavior overrides (opaque
 * local UUIDs plus behavior names — the rack catalog is never in the file), drop sets with their
 * minimum weight, timed sets, per-rep weight progression, stall detection, stop-at-top, rep
 * count timing, per-set rest times, Echo level, Eccentric load, per-set Echo levels, and the
 * stored legacy AMRAP flag (older routines mark only the last set AMRAP while `set_reps` keeps
 * numeric reps — never overloaded onto `is_amrap`). `durationSyncKnown` and
 * `isLaunchAdjustedDuration` are runtime/sync state and never written to the file. A version 2
 * file also carries Echo and Eccentric Only modes in `mode`. Apps reading version 1 only cannot
 * open version 2 files.
 *
 * Structured text cells (`warmup_sets`, `default_rack_item_ids`, `rack_behavior_overrides`,
 * `set_echo_levels`) are RFC 4180 text cells with the formula guard: `warmup_sets` is
 * `reps:percent_of_working` per entry, `rack_behavior_overrides` is `rack_item_id=BEHAVIOR` per
 * entry, and the other two are pipe-separated entries (`set_echo_levels` leaves an entry blank to
 * fall back to the exercise-level Echo level). Import unescapes the formula guard before parsing
 * them. Numeric cells are written as-is, so negative progression stays a number and never takes
 * the text formula guard. Code cells hold enum constant names.
 *
 * Not carried, because nothing reads them outside the modes that use them: Echo levels and % of
 * PR values of exercises using neither, when those lists are empty. Imported exercises take the
 * first set's weight as their base weight, as the exercise editor does.
 */
object RoutineCsvFormat {
    const val VERSION = 1
    const val VERSION_V2 = 2
    const val VERSION_PREFIX = "# phoenix_routine_csv_version="
    const val VERSION_LINE = "$VERSION_PREFIX$VERSION"
    const val VERSION_V2_LINE = "$VERSION_PREFIX$VERSION_V2"

    val COLUMNS = listOf(
        "routine_id",
        "routine_name",
        "routine_description",
        "group_name",
        "group_order",
        "exercise_id",
        "exercise_name",
        "exercise_order",
        "superset_key",
        "superset_name",
        "superset_order",
        "superset_rest_seconds",
        "set_reps",
        "set_weights_kg",
        "rest_seconds",
        "mode",
        "is_amrap",
        "superset_color",
    )

    /** The v1 header as first released, before the optional [COLUMNS] `superset_color`; still read. */
    val COLUMNS_WITHOUT_SUPERSET_COLOR = COLUMNS.dropLast(1)

    /** Columns version 2 adds to [COLUMNS]; every advanced routine setting round-trips through them. */
    val COLUMNS_V2_ONLY = listOf(
        "use_percent_of_pr",
        "weight_percent_of_pr",
        "pr_type_for_scaling",
        "set_weights_percent_of_pr",
        "scaling_basis",
        "warmup_sets",
        "default_rack_item_ids",
        "rack_behavior_overrides",
        "drop_set_enabled",
        "drop_set_min_weight_kg",
        "duration_seconds",
        "progression_kg",
        "stall_detection_enabled",
        "stop_at_top",
        "rep_count_timing",
        "per_set_rest_time",
        "rest_seconds_per_set",
        "echo_level",
        "eccentric_load",
        "set_echo_levels",
        "is_amrap_flag",
    )

    /** The version 2 header: the v1 columns, unchanged, then [COLUMNS_V2_ONLY]. */
    val COLUMNS_V2 = COLUMNS + COLUMNS_V2_ONLY

    /** Denial-of-service bounds for a picked file. */
    const val MAX_BYTES = 2 * 1024 * 1024
    const val TOO_LARGE_MESSAGE = "The file is larger than 2 MB."
    const val MAX_ROWS = 2_000
    const val MAX_ROUTINES = 50
    const val MAX_SETS_PER_EXERCISE = 50

    /** Highest [com.devil.phoenixproject.domain.model.SupersetColors] index. */
    const val MAX_SUPERSET_COLOR = 3

    const val AMRAP = "AMRAP"
    const val MAX_REPS = 100

    /** Same range the exercise editor allows for rest between sets. */
    const val MAX_REST_SECONDS = 300
    const val DEFAULT_REST_SECONDS = 60
    val DEFAULT_SUPERSET_REST_SECONDS = Superset(id = "", routineId = "", name = "").restBetweenSeconds
    const val MAX_WEIGHT_KG_PER_CABLE = Constants.MAX_WEIGHT_PER_CABLE_KG

    /** Percent cells (of a PR or of a working weight) read 0-200. */
    const val MAX_PERCENT = 200

    /** Timed sets run 1-300 s, the range the editor and the machine accept. */
    const val MIN_TIMED_DURATION_SECONDS = 1
    const val MAX_TIMED_DURATION_SECONDS = 300

    val SUPPORTED_MODES: List<ProgramMode> =
        listOf(ProgramMode.OldSchool, ProgramMode.Pump, ProgramMode.TUT, ProgramMode.TUTBeast)

    /** Every mode a version 2 file carries, including Echo and Eccentric Only. */
    val ALL_MODES: List<ProgramMode> =
        SUPPORTED_MODES + listOf(ProgramMode.EccentricOnly, ProgramMode.Echo)

    /** Canonical mode name written on export. */
    fun modeName(mode: ProgramMode): String = mode.toSyncString()

    /**
     * Mode from a cell: the canonical name or a common spelling of it ("Old School",
     * "OldSchool", "tut-beast"). Null for an unknown name. Version 1 files accept
     * [SUPPORTED_MODES] only; version 2 accepts every name in [ALL_MODES].
     */
    fun parseMode(value: String): ProgramMode? = when (value.uppercase().filter { it.isLetter() }) {
        "OLDSCHOOL", "CLASSIC" -> ProgramMode.OldSchool
        "PUMP" -> ProgramMode.Pump
        "TUT" -> ProgramMode.TUT
        "TUTBEAST" -> ProgramMode.TUTBeast
        "ECHO" -> ProgramMode.Echo
        "ECCENTRICONLY", "ECCENTRIC" -> ProgramMode.EccentricOnly
        else -> null
    }

    /** Shortest decimal form: 40.0 -> "40", 42.5 -> "42.5". */
    fun formatNumber(value: Float): String {
        val asLong = value.toLong()
        return if (value == asLong.toFloat()) asLong.toString() else value.toString()
    }
}
