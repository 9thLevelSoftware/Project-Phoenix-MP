package com.devil.phoenixproject.domain.csv

import com.devil.phoenixproject.data.integration.CsvExporter
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.util.CsvParser

/** One problem in an import file; [line] is 1-based, null for the whole file. */
data class RoutineCsvIssue(val line: Int?, val message: String) {
    override fun toString(): String = if (line != null) "Line $line: $message" else message
}

/** A parsed, self-consistent exercise row. Nothing here is matched against the database yet. */
data class RoutineCsvExerciseDraft(
    val line: Int,
    val exerciseId: String?,
    val exerciseName: String,
    val order: Int,
    val supersetKey: String?,
    val supersetName: String?,
    val supersetOrder: Int?,
    val supersetRestSeconds: Int?,
    /** One entry per set; null is an AMRAP set. */
    val setReps: List<Int?>,
    val setWeightsKg: List<Float>,
    val restSeconds: Int,
    val mode: ProgramMode,
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

/** Reads and writes [RoutineCsvFormat] v1. Pure: no repository access. */
object RoutineCsvCodec {
    private const val COLUMN_COUNT = 17

    // ── Export ──────────────────────────────────────────────────────────────

    /**
     * One routine as a v1 file. [groupName] and [groupOrder] describe the routine's group, if
     * any. Blocked when the routine uses a setting v1 cannot carry.
     */
    fun encode(routine: Routine, groupName: String?, groupOrder: Int?): RoutineCsvExportResult {
        val reasons = exportBlockers(routine)
        if (reasons.isNotEmpty()) return RoutineCsvExportResult.Blocked(reasons)

        val supersetKeys = routine.supersets.sortedBy { it.orderIndex }
            .mapIndexed { index, superset -> superset.id to "ss${index + 1}" }
            .toMap()
        val supersetsById = routine.supersets.associateBy { it.id }

        val text = buildString {
            append(RoutineCsvFormat.VERSION_LINE).append("\r\n")
            append(RoutineCsvFormat.COLUMNS.joinToString(",")).append("\r\n")
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
                    (exercise.setRestSeconds.firstOrNull() ?: RoutineCsvFormat.DEFAULT_REST_SECONDS).toString(),
                    RoutineCsvFormat.modeName(exercise.programMode),
                    exercise.setReps.all { it == null }.toString(),
                )
                append(cells.joinToString(",")).append("\r\n")
            }
        }
        return RoutineCsvExportResult.Exported(fileName = fileName(routine.name), content = text)
    }

    /** Settings [routine] uses that v1 cannot carry, one readable line each. Empty when exportable. */
    fun exportBlockers(routine: Routine): List<String> {
        if (routine.exercises.isEmpty()) return listOf("The routine has no exercises.")
        val reasons = linkedSetOf<String>()
        for (exercise in routine.exercises) {
            val name = exercise.exercise.name
            if (exercise.programMode !in RoutineCsvFormat.SUPPORTED_MODES) {
                reasons += "$name uses ${exercise.programMode.displayName} mode."
            }
            if (exercise.usePercentOfPR) reasons += "$name uses % of PR weights."
            if (exercise.warmupSets.isNotEmpty()) reasons += "$name has warm-up sets."
            if (exercise.defaultRackItemIds.isNotEmpty() || exercise.rackBehaviorOverrides.isNotEmpty()) {
                reasons += "$name has equipment rack defaults."
            }
            if (exercise.dropSetEnabled) reasons += "$name offers drop sets."
            if (exercise.duration != null) reasons += "$name uses timed sets."
            if (exercise.progressionKg != 0f) reasons += "$name has weight progression."
            if (!exercise.stallDetectionEnabled) reasons += "$name has stall detection turned off."
            if (exercise.stopAtTop) reasons += "$name stops at the top."
            if (exercise.repCountTiming != RepCountTiming.TOP) reasons += "$name counts reps at the bottom."
            if (exercise.setRestSeconds.distinct().size > 1) reasons += "$name has different rest times per set."
            if (exercise.setReps.isEmpty()) reasons += "$name has no sets."
        }
        return reasons.toList()
    }

    private fun exerciseWeights(setWeights: List<Float>, weight: Float, setCount: Int): List<Float> =
        List(setCount) { index -> setWeights.getOrNull(index) ?: weight }

    /** A text cell: formula-guarded and quoted by [CsvExporter.escapeCsvField], on one line. */
    private fun text(value: String): String =
        CsvExporter.escapeCsvField(value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' '))

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
     * issue found, so the user can fix the file in one pass; there is no partial result.
     */
    fun parse(content: String): RoutineCsvParseResult {
        if (content.encodeToByteArray().size > RoutineCsvFormat.MAX_BYTES) {
            return invalid(null, RoutineCsvFormat.TOO_LARGE_MESSAGE)
        }
        val lines = content.removePrefix("﻿").split("\r\n", "\n", "\r")
        val issues = mutableListOf<RoutineCsvIssue>()

        var index = 0
        fun nextContentLine(): Int? {
            while (index < lines.size && lines[index].isBlank()) index++
            return index.takeIf { it < lines.size }
        }

        val versionIndex = nextContentLine()
            ?: return invalid(null, "The file is empty.")
        val versionLine = lines[versionIndex].trim()
        if (!versionLine.startsWith(RoutineCsvFormat.VERSION_PREFIX)) {
            return invalid(versionIndex + 1, "The first line must be ${RoutineCsvFormat.VERSION_LINE}.")
        }
        val version = versionLine.removePrefix(RoutineCsvFormat.VERSION_PREFIX).trim().toIntOrNull()
        if (version != RoutineCsvFormat.VERSION) {
            return invalid(versionIndex + 1, "Unsupported file version; this app reads version ${RoutineCsvFormat.VERSION}.")
        }
        index++

        var headerIndex: Int
        while (true) {
            headerIndex = nextContentLine() ?: return invalid(null, "The header row is missing.")
            if (!lines[headerIndex].trimStart().startsWith("#")) break
            index++
        }
        val headerLine = lines[headerIndex]
        if (hasUnbalancedQuotes(headerLine)) return invalid(headerIndex + 1, "A quoted field is not closed.")
        val header = trimTrailingEmpty(CsvParser.parseCsvRow(headerLine).map { it.trim() })
        if (header.size == 1 && header[0].contains(';')) {
            return invalid(headerIndex + 1, "The file uses semicolons. Save it as comma-separated CSV.")
        }
        if (header != RoutineCsvFormat.COLUMNS) {
            return invalid(headerIndex + 1, "The header must be exactly: ${RoutineCsvFormat.COLUMNS.joinToString(",")}")
        }
        index = headerIndex + 1

        val rows = mutableListOf<RawRow>()
        // Rejected rows count too, so a file of malformed lines stays within the same bound.
        var dataRowCount = 0
        while (index < lines.size) {
            val line = lines[index]
            val lineNumber = index + 1
            index++
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            if (++dataRowCount > RoutineCsvFormat.MAX_ROWS) {
                return invalid(lineNumber, "The file has more than ${RoutineCsvFormat.MAX_ROWS} rows.")
            }
            if (hasUnbalancedQuotes(line)) {
                issues += RoutineCsvIssue(lineNumber, "A quoted field is not closed (line breaks inside a field are not supported).")
                continue
            }
            val cells = trimTrailingEmpty(CsvParser.parseCsvRow(line))
            if (cells.size > COLUMN_COUNT) {
                issues += RoutineCsvIssue(lineNumber, "The row has more than $COLUMN_COUNT columns.")
                continue
            }
            rows += RawRow(lineNumber, cells + List(COLUMN_COUNT - cells.size) { "" })
        }
        if (rows.isEmpty() && issues.isEmpty()) return invalid(null, "The file has no routine rows.")

        val routines = groupRoutines(rows, issues)
        if (routines.size > RoutineCsvFormat.MAX_ROUTINES) {
            issues += RoutineCsvIssue(null, "The file has more than ${RoutineCsvFormat.MAX_ROUTINES} routines.")
        }
        return if (issues.isEmpty()) RoutineCsvParseResult.Parsed(routines) else RoutineCsvParseResult.Invalid(issues)
    }

    private class RawRow(val line: Int, val cells: List<String>) {
        fun raw(column: Int): String = cells[column].trim()

        /** A text column, with [CsvExporter.escapeCsvField]'s formula guard removed. */
        fun text(column: Int): String = CsvExporter.unescapeFormulaGuard(raw(column))
    }

    private fun groupRoutines(rows: List<RawRow>, issues: MutableList<RoutineCsvIssue>): List<RoutineCsvRoutineDraft> {
        // Rows belong to one routine by id when given, otherwise by name.
        val byRoutine = linkedMapOf<String, MutableList<RawRow>>()
        for (row in rows) {
            val id = row.raw(0)
            val name = row.text(1)
            if (name.isEmpty()) {
                issues += RoutineCsvIssue(row.line, "routine_name is required.")
                continue
            }
            val key = if (id.isNotEmpty()) "id:$id" else "name:${name.lowercase()}"
            byRoutine.getOrPut(key) { mutableListOf() } += row
        }
        return byRoutine.values.mapNotNull { routineRows -> routineDraft(routineRows, issues) }
    }

    private fun routineDraft(rows: List<RawRow>, issues: MutableList<RoutineCsvIssue>): RoutineCsvRoutineDraft? {
        val first = rows.first()
        val issueCount = issues.size
        for (row in rows.drop(1)) {
            for ((column, label) in listOf(1 to "routine_name", 2 to "routine_description", 3 to "group_name", 4 to "group_order")) {
                if (row.text(column) != first.text(column)) {
                    issues += RoutineCsvIssue(row.line, "$label differs from line ${first.line} for the same routine.")
                }
            }
        }
        val groupName = first.text(3).ifEmpty { null }
        val groupOrder = optionalInt(first, 4, "group_order", 0, Int.MAX_VALUE, issues)

        val exercises = rows.mapNotNull { exerciseDraft(it, issues) }
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

    private fun exerciseDraft(row: RawRow, issues: MutableList<RoutineCsvIssue>): RoutineCsvExerciseDraft? {
        val issueCount = issues.size
        val exerciseName = row.text(6)
        if (exerciseName.isEmpty()) issues += RoutineCsvIssue(row.line, "exercise_name is required.")
        val order = requiredInt(row, 7, "exercise_order", 0, Int.MAX_VALUE, issues)

        val supersetKey = row.raw(8).ifEmpty { null }
        val supersetOrder = optionalInt(row, 10, "superset_order", 0, Int.MAX_VALUE, issues)
        val supersetRest = optionalInt(row, 11, "superset_rest_seconds", 0, RoutineCsvFormat.MAX_REST_SECONDS, issues)
        if (supersetKey == null && (row.raw(9).isNotEmpty() || supersetOrder != null || supersetRest != null)) {
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
        when {
            mode == null -> issues += RoutineCsvIssue(
                row.line,
                "Unknown mode '$modeCell'. Use ${RoutineCsvFormat.SUPPORTED_MODES.joinToString { RoutineCsvFormat.modeName(it) }}.",
            )
            mode !in RoutineCsvFormat.SUPPORTED_MODES -> issues += RoutineCsvIssue(
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

        if (issues.size != issueCount || order == null || reps == null || weights == null || mode == null) return null
        return RoutineCsvExerciseDraft(
            line = row.line,
            exerciseId = row.raw(5).ifEmpty { null },
            exerciseName = exerciseName,
            order = order,
            supersetKey = supersetKey,
            supersetName = row.text(9).ifEmpty { null },
            supersetOrder = supersetOrder,
            supersetRestSeconds = supersetRest,
            setReps = reps,
            setWeightsKg = weights,
            restSeconds = rest,
            mode = mode,
        )
    }

    private fun parseReps(row: RawRow, issues: MutableList<RoutineCsvIssue>): List<Int?>? {
        val cell = row.raw(12)
        if (cell.isEmpty()) {
            issues += RoutineCsvIssue(row.line, "set_reps is required.")
            return null
        }
        val parts = cell.split('|').map { it.trim() }
        if (parts.size > RoutineCsvFormat.MAX_SETS_PER_EXERCISE) {
            issues += RoutineCsvIssue(row.line, "More than ${RoutineCsvFormat.MAX_SETS_PER_EXERCISE} sets.")
            return null
        }
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

    private fun parseWeights(row: RawRow, issues: MutableList<RoutineCsvIssue>): List<Float>? {
        val cell = row.raw(13)
        if (cell.isEmpty()) {
            issues += RoutineCsvIssue(row.line, "set_weights_kg is required.")
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
                    member.supersetRestSeconds != first.supersetRestSeconds
                ) {
                    issues += RoutineCsvIssue(member.line, "Superset '$key' name, order or rest differs from line ${first.line}.")
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

    private fun trimTrailingEmpty(cells: List<String>): List<String> = cells.dropLastWhile { it.isBlank() }

    private fun invalid(line: Int?, message: String) = RoutineCsvParseResult.Invalid(listOf(RoutineCsvIssue(line, message)))
}
