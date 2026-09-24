package com.devil.phoenixproject.domain.csv

import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.Superset
import com.devil.phoenixproject.util.Constants

/**
 * Phoenix routine CSV, version 1 (#772).
 *
 * The first line is `# phoenix_routine_csv_version=1`, the second the fixed [COLUMNS] header,
 * then one row per routine exercise. Rows of one routine share its id or name. Sets are
 * pipe-separated: `set_reps` holds positive integers or `AMRAP`, `set_weights_kg` holds kg per
 * cable with `.` as the decimal separator, and both lists have one entry per set. `is_amrap`
 * is true exactly when every set is AMRAP. A `superset_key` groups two or more rows of a
 * routine into one superset; blank means standalone. `exercise_order` is the flat display
 * order, supersets included; a superset sits at its first exercise's position, so
 * `superset_order` is written for reference and only checked for consistency on import;
 * `superset_color` (optional) is a SupersetColors index, blank picks the next free colour.
 * Blank lines and further `#` lines are ignored. Cells follow RFC 4180: text cells are taken
 * as written (spaces included), and a quoted cell may contain commas, quotes and line breaks.
 * Number and code cells are trimmed.
 *
 * Only the fields below exist in v1. Export refuses a routine that uses anything else (Echo
 * and Eccentric Only modes, % of PR, warm-ups, rack defaults, drop sets, timed sets, the older
 * last-set AMRAP flag, and non-default per-exercise behaviour), so a round trip never drops a
 * setting silently. Not carried, because nothing reads them in the modes v1 exports: Echo
 * levels and % of PR values of exercises using neither, and the per-set rest toggle when every
 * set rests the same. Imported exercises also take the first set's weight as their base weight,
 * as the exercise editor does.
 */
object RoutineCsvFormat {
    const val VERSION = 1
    const val VERSION_PREFIX = "# phoenix_routine_csv_version="
    const val VERSION_LINE = "$VERSION_PREFIX$VERSION"

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

    val SUPPORTED_MODES: List<ProgramMode> =
        listOf(ProgramMode.OldSchool, ProgramMode.Pump, ProgramMode.TUT, ProgramMode.TUTBeast)

    /** Canonical mode name written on export. */
    fun modeName(mode: ProgramMode): String = mode.toSyncString()

    /**
     * Mode from a cell: the canonical name or a common spelling of it ("Old School",
     * "OldSchool", "tut-beast"). Null for an unknown name; Echo and Eccentric Only parse, so
     * the caller can name them as unsupported rather than unknown.
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
