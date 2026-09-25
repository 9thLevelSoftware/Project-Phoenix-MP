package com.devil.phoenixproject.domain.csv

import com.devil.phoenixproject.data.integration.CsvExporter
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RackItemBehavior
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.WarmupSet
import com.devil.phoenixproject.util.CommandLimits
import com.devil.phoenixproject.util.CsvParser

/** One problem in an import file; [line] is 1-based, null for the whole file. */
data class RoutineCsvIssue(val line: Int?, val message: String) {
    override fun toString(): String = if (line != null) "Line $line: $message" else message
}

/**
 * A parsed, self-consistent exercise row. Nothing here is matched against the database yet.
 * The advanced fields below are version 2 columns; a version 1 row leaves them at their
 * defaults, because a version 1 file cannot carry them.
 */
data class RoutineCsvExerciseDraft(
    val line: Int,
    val exerciseId: String?,
    val exerciseName: String,
    val order: Int,
    val supersetKey: String?,
    val supersetName: String?,
    val supersetOrder: Int?,
    val supersetRestSeconds: Int?,
    /** [com.devil.phoenixproject.domain.model.SupersetColors] index; null picks the next free colour. */
    val supersetColor: Int?,
    /** One entry per set; null is an AMRAP set. */
    val setReps: List<Int?>,
    val setWeightsKg: List<Float>,
    val restSeconds: Int,
    val mode: ProgramMode,
    val usePercentOfPR: Boolean = false,
    val weightPercentOfPR: Int = 80,
    val prTypeForScaling: PRType = PRType.MAX_WEIGHT,
    val setWeightsPercentOfPR: List<Int> = emptyList(),
    val scalingBasis: ScalingBasis? = null,
    val warmupSets: List<WarmupSet> = emptyList(),
    val defaultRackItemIds: List<String> = emptyList(),
    val rackBehaviorOverrides: Map<String, RackItemBehavior> = emptyMap(),
    val dropSetEnabled: Boolean = false,
    val dropSetMinWeightKg: Float? = null,
    val durationSeconds: Int? = null,
    val progressionKg: Float = 0f,
    val stallDetectionEnabled: Boolean = true,
    val stopAtTop: Boolean = false,
    val repCountTiming: RepCountTiming = RepCountTiming.TOP,
    val perSetRestTime: Boolean = false,
    /** As stored, one entry per rest time; empty falls back to [restSeconds] for every set. */
    val restSecondsPerSet: List<Int> = emptyList(),
    val echoLevel: EchoLevel = EchoLevel.HARDER,
    val eccentricLoad: EccentricLoad = EccentricLoad.LOAD_100,
    /** Per-set Echo level overrides; null entries fall back to [echoLevel]. */
    val setEchoLevels: List<EchoLevel?> = emptyList(),
    /** The stored legacy AMRAP flag; null means the version 1 rule (every set is AMRAP). */
    val isAmrapFlag: Boolean? = null,
)

/** One routine from the file, rows sorted by [RoutineCsvExerciseDraft.order]. */
data class RoutineCsvRoutineDraft(
    val line: Int,
    val routineId: String?,
    val name: String,
    val description: String,
    val groupName: String?,
    val groupOrder: Int?,
    val exercises: List<RoutineCsvExerciseDraft>,
)

sealed interface RoutineCsvParseResult {
    data class Parsed(val routines: List<RoutineCsvRoutineDraft>) : RoutineCsvParseResult

    data class Invalid(val issues: List<RoutineCsvIssue>) : RoutineCsvParseResult
}

sealed interface RoutineCsvExportResult {
    data class Exported(val fileName: String, val content: String) : RoutineCsvExportResult

    /** Export would lose [reasons]; nothing is written. */
    data class Blocked(val reasons: List<String>) : RoutineCsvExportResult
}

/**
 * Reads [RoutineCsvFormat] v1 and v2 and writes v2 (#896). Every advanced setting a routine
 * uses round-trips through v2, so export only refuses what no CSV row can hold at all. Pure:
 * no repository access.
 */
object RoutineCsvCodec {
    // Version 2 column positions (the v1 columns keep their fixed positions 0-17).
    private val COL_USE_PERCENT_OF_PR = RoutineCsvFormat.COLUMNS_V2.indexOf("use_percent_of_pr")
    private val COL_WEIGHT_PERCENT_OF_PR = RoutineCsvFormat.COLUMNS_V2.indexOf("weight_percent_of_pr")
    private val COL_PR_TYPE_FOR_SCALING = RoutineCsvFormat.COLUMNS_V2.indexOf("pr_type_for_scaling")
    private val COL_SET_WEIGHTS_PERCENT_OF_PR = RoutineCsvFormat.COLUMNS_V2.indexOf("set_weights_percent_of_pr")
    private val COL_SCALING_BASIS = RoutineCsvFormat.COLUMNS_V2.indexOf("scaling_basis")
    private val COL_WARMUP_SETS = RoutineCsvFormat.COLUMNS_V2.indexOf("warmup_sets")
    private val COL_DEFAULT_RACK_ITEM_IDS = RoutineCsvFormat.COLUMNS_V2.indexOf("default_rack_item_ids")
    private val COL_RACK_BEHAVIOR_OVERRIDES = RoutineCsvFormat.COLUMNS_V2.indexOf("rack_behavior_overrides")
    private val COL_DROP_SET_ENABLED = RoutineCsvFormat.COLUMNS_V2.indexOf("drop_set_enabled")
    private val COL_DROP_SET_MIN_WEIGHT_KG = RoutineCsvFormat.COLUMNS_V2.indexOf("drop_set_min_weight_kg")
    private val COL_DURATION_SECONDS = RoutineCsvFormat.COLUMNS_V2.indexOf("duration_seconds")
    private val COL_PROGRESSION_KG = RoutineCsvFormat.COLUMNS_V2.indexOf("progression_kg")
    private val COL_STALL_DETECTION_ENABLED = RoutineCsvFormat.COLUMNS_V2.indexOf("stall_detection_enabled")
    private val COL_STOP_AT_TOP = RoutineCsvFormat.COLUMNS_V2.indexOf("stop_at_top")
    private val COL_REP_COUNT_TIMING = RoutineCsvFormat.COLUMNS_V2.indexOf("rep_count_timing")
    private val COL_PER_SET_REST_TIME = RoutineCsvFormat.COLUMNS_V2.indexOf("per_set_rest_time")
    private val COL_REST_SECONDS_PER_SET = RoutineCsvFormat.COLUMNS_V2.indexOf("rest_seconds_per_set")
    private val COL_ECHO_LEVEL = RoutineCsvFormat.COLUMNS_V2.indexOf("echo_level")
    private val COL_ECCENTRIC_LOAD = RoutineCsvFormat.COLUMNS_V2.indexOf("eccentric_load")
    private val COL_SET_ECHO_LEVELS = RoutineCsvFormat.COLUMNS_V2.indexOf("set_echo_levels")
    private val COL_IS_AMRAP_FLAG = RoutineCsvFormat.COLUMNS_V2.indexOf("is_amrap_flag")

    /** Which header a file's rows must line up with. */
    private class Layout(val version: Int) {
        val columns: List<String> =
            if (version == RoutineCsvFormat.VERSION_V2) RoutineCsvFormat.COLUMNS_V2 else RoutineCsvFormat.COLUMNS
        val columnCount: Int = columns.size
    }

    // ── Export ──────────────────────────────────────────────────────────────

    /**
     * One routine as a v2 file. [groupName] and [groupOrder] describe the routine's group, if
     * any. Blocked only when a row cannot hold the routine at all (no exercises, an exercise
     * with no sets); every advanced setting is carried by v2.
     */
    fun encode(routine: Routine, groupName: String?, groupOrder: Int?): RoutineCsvExportResult {
        val reasons = exportBlockers(routine)
        if (reasons.isNotEmpty()) return RoutineCsvExportResult.Blocked(reasons)

        val supersetKeys = routine.supersets.sortedBy { it.orderIndex }
            .mapIndexed { index, superset -> superset.id to "ss${index + 1}" }
            .toMap()
        val supersetsById = routine.supersets.associateBy { it.id }

        val text = buildString {
            append(RoutineCsvFormat.VERSION_V2_LINE).append("\r\n")
            append(RoutineCsvFormat.COLUMNS_V2.joinToString(",")).append("\r\n")
            routine.exercises.forEachIndexed { index, exercise ->
                val superset = exercise.supersetId?.let(supersetsById::get)
                val cells = listOf(
                    text(routine.id),
                    text(routine.name),
                    text(routine.description),
                    text(groupName.orEmpty()),
                    groupName?.let { groupOrder?.toString() }.orEmpty(),
                    text(exercise.exercise.id.orEmpty()),
                    text(exercise.exercise.name),
                    index.toString(),
                    superset?.let { supersetKeys[it.id] }.orEmpty(),
                    superset?.let { text(it.name) }.orEmpty(),
                    superset?.orderIndex?.toString().orEmpty(),
                    superset?.restBetweenSeconds?.toString().orEmpty(),
                    exercise.setReps.joinToString("|") { it?.toString() ?: RoutineCsvFormat.AMRAP },
                    exerciseWeights(exercise.setWeightsPerCableKg, exercise.weightPerCableKg, exercise.setReps.size)
                        .joinToString("|") { RoutineCsvFormat.formatNumber(it) },
                    exercise.getRestForSet(0).toString(),
                    RoutineCsvFormat.modeName(exercise.programMode),
                    exercise.setReps.all { it == null }.toString(),
                    superset?.colorIndex?.toString().orEmpty(),
                ) + v2Cells(exercise)
                append(cells.joinToString(",")).append("\r\n")
            }
        }
        return RoutineCsvExportResult.Exported(fileName = fileName(routine.name), content = text)
    }

    /** The version 2 cells for [exercise]; numeric cells stay numbers, structured text cells are guarded. */
    private fun v2Cells(exercise: RoutineExercise): List<String> = listOf(
        exercise.usePercentOfPR.toString(),
        exercise.weightPercentOfPR.toString(),
        exercise.prTypeForScaling.name,
        exercise.setWeightsPercentOfPR.joinToString("|"),
        exercise.scalingBasis?.name.orEmpty(),
        text(exercise.warmupSets.joinToString("|") { "${it.reps}:${it.percentOfWorking}" }),
        text(exercise.defaultRackItemIds.joinToString("|")),
        text(exercise.rackBehaviorOverrides.entries.joinToString("|") { "${it.key}=${it.value.name}" }),
        exercise.dropSetEnabled.toString(),
        exercise.dropSetMinWeightKg?.let(RoutineCsvFormat::formatNumber).orEmpty(),
        exercise.duration?.toString().orEmpty(),
        RoutineCsvFormat.formatNumber(exercise.progressionKg),
        exercise.stallDetectionEnabled.toString(),
        exercise.stopAtTop.toString(),
        exercise.repCountTiming.name,
        exercise.perSetRestTime.toString(),
        exercise.setRestSeconds.joinToString("|"),
        exercise.echoLevel.name,
        exercise.eccentricLoad.name,
        text(exercise.setEchoLevels.joinToString("|") { it?.name ?: "" }),
        exercise.isAMRAP.toString(),
    )

    /**
     * Settings [routine] uses that a CSV row cannot hold at all, one readable line each. Empty
     * when exportable: every other setting the routine uses round-trips through v2 (#896).
     */
    fun exportBlockers(routine: Routine): List<String> {
        if (routine.exercises.isEmpty()) return listOf("The routine has no exercises.")
        val reasons = linkedSetOf<String>()
        for (exercise in routine.exercises) {
            if (exercise.setReps.isEmpty()) reasons += "${exercise.exercise.name} has no sets."
        }
        return reasons.toList()
    }

    private fun exerciseWeights(setWeights: List<Float>, weight: Float, setCount: Int): List<Float> =
        List(setCount) { index -> setWeights.getOrNull(index) ?: weight }

    /** A text cell, formula-guarded and quoted by [CsvExporter.escapeCsvField]; line breaks stay inside the quotes. */
    private fun text(value: String): String = CsvExporter.escapeCsvField(value)

    private fun fileName(routineName: String): String {
        val slug = routineName.lowercase()
            .map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }
            .joinToString("")
            .split('-').filter { it.isNotEmpty() }.joinToString("-")
            .take(40)
        return "phoenix-routine-${slug.ifEmpty { "export" }}.csv"
    }

    // ── Import ──────────────────────────────────────────────────────────────

    /**
     * Parses and checks [content] as a whole. Any problem makes the result [Invalid] with every
     * issue found, so the user can fix the file in one pass; there is no partial result. Version
     * 1 files (including the pre-`superset_color` header) and version 2 files are both read.
     */
    fun parse(content: String): RoutineCsvParseResult {
        if (content.encodeToByteArray().size > RoutineCsvFormat.MAX_BYTES) {
            return invalid(null, RoutineCsvFormat.TOO_LARGE_MESSAGE)
        }
        // Read one line at a time: nothing is kept per blank or comment line, so a file of
        // mostly line breaks costs no more memory than its text.
        val lines = LineReader(content.removePrefix("\ufeff"))
        val issues = mutableListOf<RoutineCsvIssue>()

        fun nextContentLine(): String? {
            while (lines.hasNext()) {
                val line = lines.next()
                if (line.isNotBlank()) return line
            }
            return null
        }

        val versionLine = nextContentLine()?.trim() ?: return invalid(null, "The file is empty.")
        if (!versionLine.startsWith(RoutineCsvFormat.VERSION_PREFIX)) {
            return invalid(lines.number, "The first line must be ${RoutineCsvFormat.VERSION_LINE}.")
        }
        val version = versionLine.removePrefix(RoutineCsvFormat.VERSION_PREFIX).trim().toIntOrNull()
        if (version != RoutineCsvFormat.VERSION && version != RoutineCsvFormat.VERSION_V2) {
            return invalid(
                lines.number,
                "Unsupported file version; this app reads versions ${RoutineCsvFormat.VERSION} and ${RoutineCsvFormat.VERSION_V2}.",
            )
        }
        val layout = Layout(version)

        var headerLine: String
        do {
            headerLine = nextContentLine() ?: return invalid(null, "The header row is missing.")
        } while (headerLine.trimStart().startsWith("#"))
        if (hasUnbalancedQuotes(headerLine)) return invalid(lines.number, "A quoted field is not closed.")
        val header = trimTrailingEmpty(CsvParser.parseCsvRow(headerLine).map { it.trim() })
        if (header.size == 1 && header[0].contains(';')) {
            return invalid(lines.number, "The file uses semicolons. Save it as comma-separated CSV.")
        }
        // Version 1 files written before superset_color existed have the same columns without it.
        val legacyV1Header = version == RoutineCsvFormat.VERSION && header == RoutineCsvFormat.COLUMNS_WITHOUT_SUPERSET_COLOR
        if (header != layout.columns && !legacyV1Header) {
            return invalid(lines.number, "The header must be exactly: ${layout.columns.joinToString(",")}")
        }

        val rows = mutableListOf<RawRow>()
        // Every data line counts, rejected ones and a quoted field's continuation lines too, so a
        // file of malformed lines stays within the same bound.
        var dataLineCount = 0
        while (lines.hasNext()) {
            val first = lines.next()
            val lineNumber = lines.number
            if (first.isBlank() || first.trimStart().startsWith("#")) continue
            if (++dataLineCount > RoutineCsvFormat.MAX_ROWS) {
                return invalid(lineNumber, "The file has more than ${RoutineCsvFormat.MAX_ROWS} rows.")
            }
            // A quoted field may span lines (a line break in a name or description): join lines,
            // with their own breaks, until the quotes balance. Quotes are counted once per line.
            val record = StringBuilder(first)
            var quotes = first.count { it == '"' }
            while (quotes % 2 != 0 && lines.hasNext()) {
                val lineBreak = lines.lastBreak
                val next = lines.next()
                if (++dataLineCount > RoutineCsvFormat.MAX_ROWS) {
                    return invalid(lines.number, "The file has more than ${RoutineCsvFormat.MAX_ROWS} rows.")
                }
                record.append(lineBreak).append(next)
                quotes += next.count { it == '"' }
            }
            if (quotes % 2 != 0) {
                issues += RoutineCsvIssue(lineNumber, "A quoted field that starts on this line is not closed.")
                continue
            }
            val cells = trimTrailingEmpty(CsvParser.parseCsvRow(record.toString()))
            if (cells.size > layout.columnCount) {
                issues += RoutineCsvIssue(lineNumber, "The row has more than ${layout.columnCount} columns.")
                continue
            }
            rows += RawRow(lineNumber, cells + List(layout.columnCount - cells.size) { "" })
        }
        if (rows.isEmpty() && issues.isEmpty()) return invalid(null, "The file has no routine rows.")

        val routines = groupRoutines(rows, issues, layout)
        if (routines.size > RoutineCsvFormat.MAX_ROUTINES) {
            issues += RoutineCsvIssue(null, "The file has more than ${RoutineCsvFormat.MAX_ROUTINES} routines.")
        }
        return if (issues.isEmpty()) RoutineCsvParseResult.Parsed(routines) else RoutineCsvParseResult.Invalid(issues)
    }

    private class RawRow(val line: Int, val cells: List<String>) {
        fun raw(column: Int): String = cells[column].trim()

        /**
         * A text column as written, with [CsvExporter.escapeCsvField]'s formula guard removed. Not
         * trimmed: spaces and line breaks are part of a name or description (RFC 4180).
         */
        fun text(column: Int): String = CsvExporter.unescapeFormulaGuard(cells[column])
    }

    private fun groupRoutines(
        rows: List<RawRow>,
        issues: MutableList<RoutineCsvIssue>,
        layout: Layout,
    ): List<RoutineCsvRoutineDraft> {
        // Rows belong to one routine by id when given, otherwise by name.
        val byRoutine = linkedMapOf<String, MutableList<RawRow>>()
        for (row in rows) {
            val id = row.raw(0)
            val name = row.text(1)
            if (name.isBlank()) {
                issues += RoutineCsvIssue(row.line, "routine_name is required.")
                continue
            }
            val key = if (id.isNotEmpty()) "id:$id" else "name:${name.trim().lowercase()}"
            byRoutine.getOrPut(key) { mutableListOf() } += row
        }
        return byRoutine.values.mapNotNull { routineRows -> routineDraft(routineRows, issues, layout) }
    }

    private fun routineDraft(
        rows: List<RawRow>,
        issues: MutableList<RoutineCsvIssue>,
        layout: Layout,
    ): RoutineCsvRoutineDraft? {
        val first = rows.first()
        val issueCount = issues.size
        for (row in rows.drop(1)) {
            // Text columns compare as written; group_order is a number cell, compared trimmed.
            val differing = listOf(1 to "routine_name", 2 to "routine_description", 3 to "group_name")
                .filter { (column, _) -> row.text(column) != first.text(column) }
                .map { it.second } +
                listOfNotNull("group_order".takeIf { row.raw(4) != first.raw(4) })
            differing.forEach { label ->
                issues += RoutineCsvIssue(row.line, "$label differs from line ${first.line} for the same routine.")
            }
        }
        val groupName = first.text(3).takeIf { it.isNotBlank() }
        val groupOrder = optionalInt(first, 4, "group_order", 0, Int.MAX_VALUE, issues)

        val exercises = rows.mapNotNull { exerciseDraft(it, issues, layout) }
        exercises.groupBy { it.order }.filterValues { it.size > 1 }.forEach { (order, duplicates) ->
            issues += RoutineCsvIssue(duplicates[1].line, "exercise_order $order is used twice in this routine.")
        }
        checkSupersets(exercises, issues)
        if (issues.size != issueCount) return null

        return RoutineCsvRoutineDraft(
            line = first.line,
            routineId = first.raw(0).ifEmpty { null },
            name = first.text(1),
            description = first.text(2),
            groupName = groupName,
            groupOrder = groupOrder,
            exercises = exercises.sortedBy { it.order },
        )
    }

    private fun exerciseDraft(row: RawRow, issues: MutableList<RoutineCsvIssue>, layout: Layout): RoutineCsvExerciseDraft? {
        val issueCount = issues.size
        val exerciseName = row.text(6)
        if (exerciseName.isBlank()) issues += RoutineCsvIssue(row.line, "exercise_name is required.")
        val order = requiredInt(row, 7, "exercise_order", 0, Int.MAX_VALUE, issues)

        val supersetKey = row.raw(8).ifEmpty { null }
        val supersetOrder = optionalInt(row, 10, "superset_order", 0, Int.MAX_VALUE, issues)
        val supersetRest = optionalInt(row, 11, "superset_rest_seconds", 0, RoutineCsvFormat.MAX_REST_SECONDS, issues)
        val supersetColor = optionalInt(row, 17, "superset_color", 0, RoutineCsvFormat.MAX_SUPERSET_COLOR, issues)
        if (supersetKey == null &&
            (row.raw(9).isNotEmpty() || supersetOrder != null || supersetRest != null || supersetColor != null)
        ) {
            issues += RoutineCsvIssue(row.line, "Superset columns are set but superset_key is blank.")
        }

        val reps = parseReps(row, issues)
        val weights = parseWeights(row, issues)
        if (reps != null && weights != null && reps.size != weights.size) {
            issues += RoutineCsvIssue(row.line, "set_reps has ${reps.size} sets but set_weights_kg has ${weights.size}.")
        }

        val rest = optionalInt(row, 14, "rest_seconds", 0, RoutineCsvFormat.MAX_REST_SECONDS, issues)
            ?: RoutineCsvFormat.DEFAULT_REST_SECONDS

        val modeCell = row.raw(15)
        val mode = if (modeCell.isEmpty()) ProgramMode.OldSchool else RoutineCsvFormat.parseMode(modeCell)
        // A v2 file carries every mode; v1 keeps the released rule (Echo and Eccentric Only refused).
        val supportedModes =
            if (layout.version == RoutineCsvFormat.VERSION_V2) RoutineCsvFormat.ALL_MODES else RoutineCsvFormat.SUPPORTED_MODES
        when {
            mode == null -> issues += RoutineCsvIssue(
                row.line,
                "Unknown mode '$modeCell'. Use ${supportedModes.joinToString { RoutineCsvFormat.modeName(it) }}.",
            )
            mode !in supportedModes -> issues += RoutineCsvIssue(
                row.line,
                "${mode.displayName} mode is not supported in CSV files yet.",
            )
        }

        val amrapCell = row.raw(16).lowercase()
        if (amrapCell.isNotEmpty() && amrapCell != "true" && amrapCell != "false") {
            issues += RoutineCsvIssue(row.line, "is_amrap must be true or false.")
        } else if (amrapCell.isNotEmpty() && reps != null) {
            val allAmrap = reps.all { it == null }
            if ((amrapCell == "true") != allAmrap) {
                issues += RoutineCsvIssue(
                    row.line,
                    if (allAmrap) "Every set is AMRAP, so is_amrap must be true." else "is_amrap is true, so every set must be AMRAP.",
                )
            }
        }

        val v2 = parseV2Settings(row, issues, layout)
        if (issues.size != issueCount || order == null || reps == null || weights == null || mode == null) return null
        return RoutineCsvExerciseDraft(
            line = row.line,
            exerciseId = row.raw(5).ifEmpty { null },
            exerciseName = exerciseName,
            order = order,
            supersetKey = supersetKey,
            supersetName = row.text(9).takeIf { it.isNotBlank() },
            supersetOrder = supersetOrder,
            supersetRestSeconds = supersetRest,
            supersetColor = supersetColor,
            setReps = reps,
            setWeightsKg = weights,
            restSeconds = rest,
            mode = mode,
            usePercentOfPR = v2.usePercentOfPR,
            weightPercentOfPR = v2.weightPercentOfPR,
            prTypeForScaling = v2.prTypeForScaling,
            setWeightsPercentOfPR = v2.setWeightsPercentOfPR,
            scalingBasis = v2.scalingBasis,
            warmupSets = v2.warmupSets,
            defaultRackItemIds = v2.defaultRackItemIds,
            rackBehaviorOverrides = v2.rackBehaviorOverrides,
            dropSetEnabled = v2.dropSetEnabled,
            dropSetMinWeightKg = v2.dropSetMinWeightKg,
            durationSeconds = v2.durationSeconds,
            progressionKg = v2.progressionKg,
            stallDetectionEnabled = v2.stallDetectionEnabled,
            stopAtTop = v2.stopAtTop,
            repCountTiming = v2.repCountTiming,
            perSetRestTime = v2.perSetRestTime,
            restSecondsPerSet = v2.restSecondsPerSet,
            echoLevel = v2.echoLevel,
            eccentricLoad = v2.eccentricLoad,
            setEchoLevels = v2.setEchoLevels,
            isAmrapFlag = v2.isAmrapFlag,
        )
    }

    /** The version 2 cells of one row. A version 1 row has none, so every field keeps its default. */
    private data class V2Settings(
        val usePercentOfPR: Boolean = false,
        val weightPercentOfPR: Int = 80,
        val prTypeForScaling: PRType = PRType.MAX_WEIGHT,
        val setWeightsPercentOfPR: List<Int> = emptyList(),
        val scalingBasis: ScalingBasis? = null,
        val warmupSets: List<WarmupSet> = emptyList(),
        val defaultRackItemIds: List<String> = emptyList(),
        val rackBehaviorOverrides: Map<String, RackItemBehavior> = emptyMap(),
        val dropSetEnabled: Boolean = false,
        val dropSetMinWeightKg: Float? = null,
        val durationSeconds: Int? = null,
        val progressionKg: Float = 0f,
        val stallDetectionEnabled: Boolean = true,
        val stopAtTop: Boolean = false,
        val repCountTiming: RepCountTiming = RepCountTiming.TOP,
        val perSetRestTime: Boolean = false,
        val restSecondsPerSet: List<Int> = emptyList(),
        val echoLevel: EchoLevel = EchoLevel.HARDER,
        val eccentricLoad: EccentricLoad = EccentricLoad.LOAD_100,
        val setEchoLevels: List<EchoLevel?> = emptyList(),
        val isAmrapFlag: Boolean? = null,
    )

    private fun parseV2Settings(row: RawRow, issues: MutableList<RoutineCsvIssue>, layout: Layout): V2Settings {
        if (layout.version != RoutineCsvFormat.VERSION_V2) return V2Settings()
        return V2Settings(
            usePercentOfPR = optionalBool(row, COL_USE_PERCENT_OF_PR, "use_percent_of_pr", false, issues) ?: false,
            weightPercentOfPR = optionalInt(row, COL_WEIGHT_PERCENT_OF_PR, "weight_percent_of_pr", 0, RoutineCsvFormat.MAX_PERCENT, issues)
                ?: 80,
            prTypeForScaling = parseEnum<PRType>(row, COL_PR_TYPE_FOR_SCALING, "pr_type_for_scaling", issues) ?: PRType.MAX_WEIGHT,
            setWeightsPercentOfPR = parsePercentList(row, COL_SET_WEIGHTS_PERCENT_OF_PR, "set_weights_percent_of_pr", issues).orEmpty(),
            scalingBasis = parseEnum<ScalingBasis>(row, COL_SCALING_BASIS, "scaling_basis", issues),
            warmupSets = parseWarmupSets(row, issues).orEmpty(),
            defaultRackItemIds = parsePipeList(row.text(COL_DEFAULT_RACK_ITEM_IDS)),
            rackBehaviorOverrides = parseRackBehaviorOverrides(row, issues).orEmpty(),
            dropSetEnabled = optionalBool(row, COL_DROP_SET_ENABLED, "drop_set_enabled", false, issues) ?: false,
            dropSetMinWeightKg = optionalWeight(row, COL_DROP_SET_MIN_WEIGHT_KG, "drop_set_min_weight_kg", issues),
            durationSeconds = optionalInt(row, COL_DURATION_SECONDS, "duration_seconds", RoutineCsvFormat.MIN_TIMED_DURATION_SECONDS, RoutineCsvFormat.MAX_TIMED_DURATION_SECONDS, issues),
            progressionKg = parseProgressionKg(row, issues) ?: 0f,
            stallDetectionEnabled = optionalBool(row, COL_STALL_DETECTION_ENABLED, "stall_detection_enabled", true, issues) ?: true,
            stopAtTop = optionalBool(row, COL_STOP_AT_TOP, "stop_at_top", false, issues) ?: false,
            repCountTiming = parseEnum<RepCountTiming>(row, COL_REP_COUNT_TIMING, "rep_count_timing", issues) ?: RepCountTiming.TOP,
            perSetRestTime = optionalBool(row, COL_PER_SET_REST_TIME, "per_set_rest_time", false, issues) ?: false,
            restSecondsPerSet = parseRestList(row, issues).orEmpty(),
            echoLevel = parseEnum<EchoLevel>(row, COL_ECHO_LEVEL, "echo_level", issues) ?: EchoLevel.HARDER,
            eccentricLoad = parseEnum<EccentricLoad>(row, COL_ECCENTRIC_LOAD, "eccentric_load", issues) ?: EccentricLoad.LOAD_100,
            setEchoLevels = parseEchoLevelList(row, issues).orEmpty(),
            isAmrapFlag = optionalBoolOrNull(row, COL_IS_AMRAP_FLAG, "is_amrap_flag", issues),
        )
    }

    private inline fun <reified T : Enum<T>> parseEnum(row: RawRow, column: Int, label: String, issues: MutableList<RoutineCsvIssue>): T? {
        val cell = row.raw(column)
        if (cell.isEmpty()) return null
        return enumValues<T>().firstOrNull { it.name.equals(cell, ignoreCase = true) } ?: run {
            issues += RoutineCsvIssue(
                row.line,
                "$label must be one of: ${enumValues<T>().joinToString { it.name }}.",
            )
            null
        }
    }

    private fun optionalBool(row: RawRow, column: Int, label: String, default: Boolean, issues: MutableList<RoutineCsvIssue>): Boolean? {
        val cell = row.raw(column).lowercase()
        if (cell.isEmpty()) return default
        return when (cell) {
            "true" -> true
            "false" -> false
            else -> {
                issues += RoutineCsvIssue(row.line, "$label must be true or false.")
                default
            }
        }
    }

    /** Like [optionalBool] but blank means absent (null), for cells whose default depends on the row. */
    private fun optionalBoolOrNull(row: RawRow, column: Int, label: String, issues: MutableList<RoutineCsvIssue>): Boolean? {
        val cell = row.raw(column).lowercase()
        return when (cell) {
            "" -> null
            "true" -> true
            "false" -> false
            else -> {
                issues += RoutineCsvIssue(row.line, "$label must be true or false.")
                null
            }
        }
    }

    /** A single kg-per-cable cell like [parseWeights] accepts; blank means absent. */
    private fun optionalWeight(row: RawRow, column: Int, label: String, issues: MutableList<RoutineCsvIssue>): Float? {
        val cell = row.raw(column)
        if (cell.isEmpty()) return null
        val weight = cell.toFloatOrNull()
        if (weight == null || !weight.isFinite() || weight < 0f || weight > RoutineCsvFormat.MAX_WEIGHT_KG_PER_CABLE || cell.contains(',')) {
            issues += RoutineCsvIssue(
                row.line,
                "'$cell' in $label is not a kg per cable weight from 0 to ${RoutineCsvFormat.formatNumber(RoutineCsvFormat.MAX_WEIGHT_KG_PER_CABLE)}.",
            )
            return null
        }
        return weight
    }

    /**
     * Per-rep progression is a number cell: it stays a number in the file (no text formula
     * guard) and reads back as one. Blank means no progression; otherwise the machine's
     * per-rep limit applies.
     */
    private fun parseProgressionKg(row: RawRow, issues: MutableList<RoutineCsvIssue>): Float? {
        val cell = row.raw(COL_PROGRESSION_KG)
        if (cell.isEmpty()) return null
        val value = cell.toFloatOrNull()
        if (value == null || !value.isFinite() || cell.contains(',') ||
            kotlin.math.abs(value) > CommandLimits.MAX_PROGRESSION_KG
        ) {
            issues += RoutineCsvIssue(
                row.line,
                "'$cell' in progression_kg is not a kg per rep from " +
                    "-${RoutineCsvFormat.formatNumber(CommandLimits.MAX_PROGRESSION_KG)} " +
                    "to ${RoutineCsvFormat.formatNumber(CommandLimits.MAX_PROGRESSION_KG)}.",
            )
            return null
        }
        return value
    }

    /** `reps:percent_of_working` per entry; blank means no warm-up sets. */
    private fun parseWarmupSets(row: RawRow, issues: MutableList<RoutineCsvIssue>): List<WarmupSet>? {
        val cell = row.text(COL_WARMUP_SETS)
        if (cell.isEmpty()) return emptyList()
        val sets = mutableListOf<WarmupSet>()
        for (entry in cell.split('|')) {
            val parts = entry.trim().split(':')
            val reps = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val percent = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (parts.size != 2 || reps == null || percent == null ||
                reps !in 1..RoutineCsvFormat.MAX_REPS || percent !in 0..RoutineCsvFormat.MAX_PERCENT
            ) {
                issues += RoutineCsvIssue(
                    row.line,
                    "'$entry' in warmup_sets is not reps:percent_of_working (reps 1-${RoutineCsvFormat.MAX_REPS}, percent 0-${RoutineCsvFormat.MAX_PERCENT}).",
                )
                return null
            }
            sets += WarmupSet(reps = reps, percentOfWorking = percent)
        }
        return sets
    }

    /** `rack_item_id=BEHAVIOR` per entry; blank means no overrides. Ids stay opaque. */
    private fun parseRackBehaviorOverrides(row: RawRow, issues: MutableList<RoutineCsvIssue>): Map<String, RackItemBehavior>? {
        val cell = row.text(COL_RACK_BEHAVIOR_OVERRIDES)
        if (cell.isEmpty()) return emptyMap()
        val overrides = linkedMapOf<String, RackItemBehavior>()
        for (entry in cell.split('|')) {
            val separator = entry.trim().indexOf('=')
            val id = if (separator > 0) entry.trim().substring(0, separator) else ""
            val behavior = entry.trim().substring(separator + 1).takeIf { separator > 0 }
                ?.let { name -> RackItemBehavior.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            if (id.isEmpty() || behavior == null) {
                issues += RoutineCsvIssue(
                    row.line,
                    "'$entry' in rack_behavior_overrides is not rack_item_id=${RackItemBehavior.entries.joinToString("|") { it.name }}.",
                )
                return null
            }
            if (overrides.containsKey(id)) {
                issues += RoutineCsvIssue(row.line, "'$id' is listed twice in rack_behavior_overrides.")
                return null
            }
            overrides[id] = behavior
        }
        return overrides
    }

    /** Rest times as stored, one per entry; blank means the v1 single `rest_seconds` for every set. */
    private fun parseRestList(row: RawRow, issues: MutableList<RoutineCsvIssue>): List<Int>? {
        val cell = row.raw(COL_REST_SECONDS_PER_SET)
        if (cell.isEmpty()) return emptyList()
        if (hasMoreThanMaxSets(cell)) {
            issues += RoutineCsvIssue(row.line, "rest_seconds_per_set has more than ${RoutineCsvFormat.MAX_SETS_PER_EXERCISE} sets.")
            return null
        }
        return cell.split('|').map { it.trim() }.map { part ->
            val rest = part.toIntOrNull()
            if (rest == null || rest < 0 || rest > RoutineCsvFormat.MAX_REST_SECONDS) {
                issues += RoutineCsvIssue(
                    row.line,
                    "'$part' in rest_seconds_per_set is not a whole number from 0 to ${RoutineCsvFormat.MAX_REST_SECONDS}.",
                )
                return null
            }
            rest
        }
    }

    /** Per-set percentages, one per entry; blank means the base `weight_percent_of_pr` for every set. */
    private fun parsePercentList(row: RawRow, column: Int, label: String, issues: MutableList<RoutineCsvIssue>): List<Int>? {
        val cell = row.raw(column)
        if (cell.isEmpty()) return emptyList()
        if (hasMoreThanMaxSets(cell)) {
            issues += RoutineCsvIssue(row.line, "$label has more than ${RoutineCsvFormat.MAX_SETS_PER_EXERCISE} sets.")
            return null
        }
        return cell.split('|').map { it.trim() }.map { part ->
            val percent = part.toIntOrNull()
            if (percent == null || percent < 0 || percent > RoutineCsvFormat.MAX_PERCENT) {
                issues += RoutineCsvIssue(
                    row.line,
                    "'$part' in $label is not a percent from 0 to ${RoutineCsvFormat.MAX_PERCENT}.",
                )
                return null
            }
            percent
        }
    }

    /** Per-set Echo levels, blank entries falling back to the exercise level; blank cell means none. */
    private fun parseEchoLevelList(row: RawRow, issues: MutableList<RoutineCsvIssue>): List<EchoLevel?>? {
        val cell = row.text(COL_SET_ECHO_LEVELS)
        if (cell.isEmpty()) return emptyList()
        if (hasMoreThanMaxSets(cell)) {
            issues += RoutineCsvIssue(row.line, "set_echo_levels has more than ${RoutineCsvFormat.MAX_SETS_PER_EXERCISE} sets.")
            return null
        }
        return cell.split('|').map { it.trim() }.map { part ->
            if (part.isEmpty()) {
                null
            } else {
                EchoLevel.entries.firstOrNull { it.name.equals(part, ignoreCase = true) } ?: run {
                    issues += RoutineCsvIssue(
                        row.line,
                        "'$part' in set_echo_levels must be blank or one of: ${EchoLevel.entries.joinToString { it.name }}.",
                    )
                    return null
                }
            }
        }
    }

    /** Pipe-separated opaque entries as written (trimmed per entry); blank means none. */
    private fun parsePipeList(cell: String): List<String> =
        if (cell.isEmpty()) emptyList() else cell.split('|').map { it.trim() }

    private fun parseReps(row: RawRow, issues: MutableList<RoutineCsvIssue>): List<Int?>? {
        val cell = row.raw(12)
        if (cell.isEmpty()) {
            issues += RoutineCsvIssue(row.line, "set_reps is required.")
            return null
        }
        if (hasMoreThanMaxSets(cell)) {
            issues += RoutineCsvIssue(row.line, "set_reps has more than ${RoutineCsvFormat.MAX_SETS_PER_EXERCISE} sets.")
            return null
        }
        val parts = cell.split('|').map { it.trim() }
        val reps = parts.map { part ->
            if (part.equals(RoutineCsvFormat.AMRAP, ignoreCase = true)) {
                null
            } else {
                part.toIntOrNull()?.takeIf { it in 1..RoutineCsvFormat.MAX_REPS } ?: run {
                    issues += RoutineCsvIssue(row.line, "'$part' in set_reps is not 1-${RoutineCsvFormat.MAX_REPS} or AMRAP.")
                    return null
                }
            }
        }
        return reps
    }

    /** Counts separators before anything is split, so an oversized list is never materialised. */
    private fun hasMoreThanMaxSets(cell: String): Boolean = cell.count { it == '|' } >= RoutineCsvFormat.MAX_SETS_PER_EXERCISE

    private fun parseWeights(row: RawRow, issues: MutableList<RoutineCsvIssue>): List<Float>? {
        val cell = row.raw(13)
        if (cell.isEmpty()) {
            issues += RoutineCsvIssue(row.line, "set_weights_kg is required.")
            return null
        }
        if (hasMoreThanMaxSets(cell)) {
            issues += RoutineCsvIssue(row.line, "set_weights_kg has more than ${RoutineCsvFormat.MAX_SETS_PER_EXERCISE} sets.")
            return null
        }
        return cell.split('|').map { it.trim() }.map { part ->
            val weight = part.toFloatOrNull()
            if (weight == null || !weight.isFinite() || weight < 0f || weight > RoutineCsvFormat.MAX_WEIGHT_KG_PER_CABLE ||
                part.contains(',')
            ) {
                issues += RoutineCsvIssue(
                    row.line,
                    "'$part' in set_weights_kg is not a kg per cable weight from 0 to ${RoutineCsvFormat.formatNumber(RoutineCsvFormat.MAX_WEIGHT_KG_PER_CABLE)}.",
                )
                return null
            }
            weight
        }
    }

    private fun checkSupersets(exercises: List<RoutineCsvExerciseDraft>, issues: MutableList<RoutineCsvIssue>) {
        exercises.filter { it.supersetKey != null }.groupBy { it.supersetKey }.forEach { (key, members) ->
            if (members.size < 2) {
                issues += RoutineCsvIssue(members.first().line, "Superset '$key' needs at least two exercises.")
            }
            val first = members.first()
            members.drop(1).forEach { member ->
                if (member.supersetName != first.supersetName ||
                    member.supersetOrder != first.supersetOrder ||
                    member.supersetRestSeconds != first.supersetRestSeconds ||
                    member.supersetColor != first.supersetColor
                ) {
                    issues += RoutineCsvIssue(member.line, "Superset '$key' name, order, rest or colour differs from line ${first.line}.")
                }
            }
        }
    }

    private fun requiredInt(row: RawRow, column: Int, label: String, min: Int, max: Int, issues: MutableList<RoutineCsvIssue>): Int? {
        if (row.raw(column).isEmpty()) {
            issues += RoutineCsvIssue(row.line, "$label is required.")
            return null
        }
        return optionalInt(row, column, label, min, max, issues)
    }

    private fun optionalInt(row: RawRow, column: Int, label: String, min: Int, max: Int, issues: MutableList<RoutineCsvIssue>): Int? {
        val cell = row.raw(column)
        if (cell.isEmpty()) return null
        val value = cell.toIntOrNull()
        if (value == null || value < min || value > max) {
            val range = if (max == Int.MAX_VALUE) "a whole number of at least $min" else "a whole number from $min to $max"
            issues += RoutineCsvIssue(row.line, "$label must be $range.")
            return null
        }
        return value
    }

    /** Odd number of quote characters means a field continues onto the next line. */
    private fun hasUnbalancedQuotes(line: String): Boolean = line.count { it == '"' } % 2 != 0

    /**
     * Physical lines of a text, one at a time, with the break that ended each ([lastBreak]: `\n`,
     * `\r\n`, `\r`, or `""` at the end), so a quoted field spanning lines keeps its own breaks.
     */
    private class LineReader(private val text: String) {
        private var position = 0
        private var finished = false

        /** 1-based number of the line [next] returned last. */
        var number = 0
            private set

        var lastBreak = ""
            private set

        fun hasNext(): Boolean = !finished

        fun next(): String {
            val start = position
            var end = start
            while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
            lastBreak = when {
                end >= text.length -> "".also { finished = true }
                text[end] == '\r' && end + 1 < text.length && text[end + 1] == '\n' -> "\r\n"
                text[end] == '\r' -> "\r"
                else -> "\n"
            }
            position = end + lastBreak.length
            number++
            return text.substring(start, end)
        }
    }

    private fun trimTrailingEmpty(cells: List<String>): List<String> = cells.dropLastWhile { it.isBlank() }

    private fun invalid(line: Int?, message: String) = RoutineCsvParseResult.Invalid(listOf(RoutineCsvIssue(line, message)))
}
