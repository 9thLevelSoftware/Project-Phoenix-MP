package com.devil.phoenixproject.data.integration.intervals

import com.devil.phoenixproject.domain.model.CompletedSet
import com.devil.phoenixproject.domain.model.SetEndReason
import com.devil.phoenixproject.domain.model.SetType
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.domain.usecase.EchoAchievedLoadResolver
import kotlin.math.roundToLong
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Pure transform from persisted rows + the receipt capture to one
 * [IntervalsManualActivity] (issue #1384, integration-model §5).
 *
 * Boundary (frozen): no networking, no settings, no active-profile reads and no device
 * zone reads — the capture supplies zone/offset/local-start, so a retry rebuilds the same
 * wire body without touching the device time zone. Everything here is deterministic and
 * locale-independent.
 */
object IntervalsActivityBuilder {

    /**
     * Builds the canonical activity for one finalized completion.
     *
     * [rows] is the RECEIPT MEMBERSHIP: each receipt session appears exactly once with the
     * persisted `CompletedSet`s that are members of the receipt (never a `WHERE
     * routineSessionId` expansion). Persisted sets take precedence over the session-derived
     * fallback set, so a `CompletedSet` and its session representation are never
     * double-counted and distinct attempts are never collapsed.
     */
    fun build(
        capture: IntervalsCompletionCapture,
        rows: List<IntervalsActivitySessionRow>,
    ): IntervalsActivityResult {
        if (capture.externalId.isBlank()) {
            return IntervalsActivityResult.Invalid("empty external id")
        }
        if (rows.isEmpty()) {
            return IntervalsActivityResult.Invalid("no qualifying work in receipt membership")
        }
        if (capture.key.localKind == IntervalsLocalKind.STANDALONE && rows.size != 1) {
            return IntervalsActivityResult.Invalid("standalone completion expects exactly one session")
        }

        val elapsedSeconds = when (capture.key.localKind) {
            IntervalsLocalKind.ROUTINE -> {
                val elapsedMs = capture.completedEpochMs - capture.startEpochMs
                if (elapsedMs < 1_000L) {
                    return IntervalsActivityResult.Invalid("routine elapsed $elapsedMs ms is below one second")
                }
                elapsedMs / 1_000L
            }
            IntervalsLocalKind.STANDALONE -> {
                val durationMs = rows.single().session.duration
                if (durationMs < 1_000L) {
                    return IntervalsActivityResult.Invalid("standalone duration $durationMs ms is below one second")
                }
                durationMs / 1_000L
            }
        }
        if (elapsedSeconds > Int.MAX_VALUE.toLong()) {
            return IntervalsActivityResult.Invalid("elapsed $elapsedSeconds s overflows int32")
        }

        val sortedSessions = rows.sortedWith(compareBy({ it.session.timestamp }, { it.session.id }))

        val name = when (capture.key.localKind) {
            IntervalsLocalKind.ROUTINE -> capture.routineTitle?.takeIf { it.isNotBlank() } ?: "Workout"
            IntervalsLocalKind.STANDALONE -> rows.single().session.exerciseName?.takeIf { it.isNotBlank() } ?: "Workout"
        }

        val description = buildDescription(capture, sortedSessions)

        if (name.encodeToByteArray().size > IntervalsPayloadLimits.MAX_NAME_BYTES) {
            return IntervalsActivityResult.PayloadTooLarge
        }
        if (description.encodeToByteArray().size > IntervalsPayloadLimits.MAX_DESCRIPTION_BYTES) {
            return IntervalsActivityResult.PayloadTooLarge
        }

        val activity = IntervalsManualActivity(
            type = "WeightTraining",
            externalId = capture.externalId,
            name = name,
            description = description,
            startDate = formatUtcIso(capture.startEpochMs),
            startDateLocal = capture.startLocalIso,
            elapsedTimeSeconds = elapsedSeconds.toInt(),
            averageHeartrate = buildAverageHeartrate(sortedSessions),
            maxHeartrate = buildMaxHeartrate(sortedSessions),
        )
        return IntervalsActivityResult.Built(activity)
    }

    /**
     * Work that qualifies as a completable set (integration-model §5):
     * positive duration plus actual working reps, or a positively completed timed-work
     * interval with captured timed-mode evidence. Zero-rep `STALL_FAILURE` attempts and
     * aborted/empty sets alone never qualify; a positively completed timed hold qualifies
     * without inventing repetitions. A manual early finish with persisted valid work is
     * not automatically an abort.
     */
    fun qualifiesAsValidWork(
        session: WorkoutSession,
        completedSet: CompletedSet?,
        timedModeEvidence: Boolean,
    ): Boolean {
        if (completedSet != null) {
            if (completedSet.actualReps > 0) return true
            return timedModeEvidence &&
                completedSet.setEndReason == SetEndReason.TIMER_EXPIRED &&
                session.duration > 0L
        }
        return session.workingReps > 0 && session.duration > 0L
    }

    // ==================== description ====================

    private fun buildDescription(
        capture: IntervalsCompletionCapture,
        sortedSessions: List<IntervalsActivitySessionRow>,
    ): String {
        val lines = mutableListOf<String>()
        lines += when (capture.key.localKind) {
            IntervalsLocalKind.ROUTINE -> "Routine: ${capture.routineTitle?.takeIf { it.isNotBlank() } ?: "Workout"}"
            IntervalsLocalKind.STANDALONE -> "Workout: ${sortedSessions.single().session.exerciseName?.takeIf { it.isNotBlank() } ?: "Workout"}"
        }
        sortedSessions.forEachIndexed { index, row ->
            val session = row.session
            lines += "Session ${index + 1}: ${session.exerciseName?.takeIf { it.isNotBlank() } ?: session.id} (mode ${session.mode})"
            val sets = row.completedSets.sortedWith(
                compareBy({ it.setNumber }, { it.completedAt }, { it.id }),
            )
            if (sets.isEmpty()) {
                // Session-derived fallback set: one set for the session, warmup labelled
                // separately — never an extra copy of the same set.
                val reps = mutableListOf<String>()
                reps += "${session.workingReps} working reps"
                if (session.warmupReps > 0) reps += "${session.warmupReps} warmup reps"
                lines += "  Set 1 (working): ${reps.joinToString(", ")}; ${sessionFallbackLoadClause(session)}"
            } else {
                sets.forEach { set ->
                    lines += "  Set ${set.setNumber} (attempt ${set.attemptNumber}, ${setLabel(set)}): " +
                        "${repsClause(set)}; ${completedSetLoadClause(set, session)}"
                }
            }
            val isEcho = EchoAchievedLoadResolver.isEcho(session)
            val echoHasMeasuredLoad = !isEcho ||
                EchoAchievedLoadResolver.fromSession(session) != null ||
                sets.any { EchoAchievedLoadResolver.completedSetLoadKg(it, session) != null }
            session.workingAvgWeightKg?.takeIf { it.isFinite() && it > 0f && echoHasMeasuredLoad }?.let { mean ->
                lines += "  Mean working load: ${formatDecimal(mean)} kg per cable (Echo measured)"
            }
            peakLoadEquivalentsClause(session)?.let { lines += "  A/B peaks (per-cable kg load equivalents): $it" }
            session.totalVolumeKg?.takeIf { it.isFinite() && (!isEcho || (echoHasMeasuredLoad && it > 0f)) }?.let { volume ->
                lines += "  Total volume (kg·reps): ${formatDecimal(volume)}"
            }
        }
        val meanHr = buildAverageHeartrate(sortedSessions)
        val maxHr = buildMaxHeartrate(sortedSessions)
        if (meanHr != null || maxHr != null) {
            val parts = mutableListOf<String>()
            meanHr?.let { parts += "mean $it bpm" }
            maxHr?.let { parts += "max $it bpm" }
            lines += "Heart rate: ${parts.joinToString(", ")} (estimate over sampled working periods)"
        }
        return lines.joinToString("\n")
    }

    private fun setLabel(set: CompletedSet): String = when (set.setType) {
        SetType.WARMUP -> "warmup"
        else -> "working"
    }

    private fun repsClause(set: CompletedSet): String {
        if (set.actualReps == 0 && set.setEndReason == SetEndReason.TIMER_EXPIRED) {
            // Positively completed timed work: never invent repetitions.
            return "timed hold completed (${SetEndReason.TIMER_EXPIRED.name}; no repetitions recorded)"
        }
        val label = if (set.setType == SetType.WARMUP) "warmup reps" else "working reps"
        return "${set.actualReps} $label"
    }

    private fun sessionFallbackLoadClause(session: WorkoutSession): String {
        if (EchoAchievedLoadResolver.isEcho(session)) {
            val achieved = EchoAchievedLoadResolver.fromSession(session)
                ?: return "load unavailable (no measured load)"
            return "measured peak ${formatDecimal(achieved)} kg per cable"
        }
        val configured = session.weightPerCableKg
        if (!configured.isFinite()) return "load unavailable (no measured load)"
        return "${formatDecimal(configured)} configured kg per cable"
    }

    private fun completedSetLoadClause(set: CompletedSet, session: WorkoutSession): String {
        if (EchoAchievedLoadResolver.isEcho(session)) {
            val achieved = EchoAchievedLoadResolver.completedSetLoadKg(set, session)
                ?: return "load unavailable (no measured load)"
            return "measured peak ${formatDecimal(achieved)} kg per cable"
        }
        val load = set.actualWeightKg
        if (!load.isFinite()) return "load unavailable (no measured load)"
        return "${formatDecimal(load)} configured kg per cable"
    }

    /**
     * A/B concentric/eccentric peaks are **per-cable kg load equivalents**. Zero defaults
     * without an accepted measurement are omitted, never fabricated as measured peaks.
     */
    private fun peakLoadEquivalentsClause(session: WorkoutSession): String? {
        val parts = mutableListOf<String>()
        listOf(
            "concentric A" to session.peakForceConcentricA,
            "concentric B" to session.peakForceConcentricB,
            "eccentric A" to session.peakForceEccentricA,
            "eccentric B" to session.peakForceEccentricB,
        ).forEach { (label, value) ->
            if (value != null && value.isFinite() && value != 0f) {
                parts += "$label ${formatDecimal(value)} kg"
            }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    // ==================== heart rate ====================

    /**
     * Mean = duration-weighted mean of valid session means over positive-duration rows,
     * counted once per session regardless of CompletedSet count. Validity: 1..300 bpm.
     */
    private fun buildAverageHeartrate(sortedSessions: List<IntervalsActivitySessionRow>): Int? {
        var weightedSum = 0.0
        var totalDuration = 0L
        sortedSessions.forEach { row ->
            val mean = row.session.avgHeartRateBpm
            if (mean != null && mean in 1..300 && row.session.duration > 0L) {
                weightedSum += mean.toDouble() * row.session.duration.toDouble()
                totalDuration += row.session.duration
            }
        }
        if (totalDuration <= 0L) return null
        return (weightedSum / totalDuration.toDouble()).roundToLong().coerceIn(1L, 300L).toInt()
    }

    /** Max = maximum valid observed session max (1..300 bpm), once per session. */
    private fun buildMaxHeartrate(sortedSessions: List<IntervalsActivitySessionRow>): Int? =
        sortedSessions
            .mapNotNull { it.session.maxHeartRateBpm }
            .filter { it in 1..300 }
            .maxOrNull()

    // ==================== deterministic formatting ====================

    /** UTC ISO8601 with second precision, always `Z`, independent of device zone. */
    fun formatUtcIso(epochMs: Long): String {
        val local = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.of("UTC"))
        return buildString {
            append(local.year.toString().padStart(4, '0'))
            append('-')
            append(local.month.number.toString().padStart(2, '0'))
            append('-')
            append(local.day.toString().padStart(2, '0'))
            append('T')
            append(local.hour.toString().padStart(2, '0'))
            append(':')
            append(local.minute.toString().padStart(2, '0'))
            append(':')
            append(local.second.toString().padStart(2, '0'))
            append('Z')
        }
    }

    /**
     * Locale-independent number formatting: at most two decimals, trailing zeros trimmed.
     * NaN/infinite values must be filtered before calling; they render as the literal
     * "nonfinite" only as a last-resort guard and are never presented as measurements.
     */
    fun formatDecimal(value: Float): String {
        if (!value.isFinite()) return "nonfinite"
        // Sign applied explicitly: truncating `scaled / 100` toward zero would drop the
        // sign of small negatives (e.g. -0.05 must render "-0.05", never "0.05").
        val scaled = kotlin.math.abs(value.toDouble() * 100.0).roundToLong()
        val sign = if (value < 0f && scaled != 0L) "-" else ""
        val whole = scaled / 100
        val frac = scaled % 100
        return if (frac == 0L) {
            "$sign$whole"
        } else {
            val fracStr = frac.toString().padStart(2, '0').trimEnd('0')
            "$sign$whole.$fracStr"
        }
    }
}

/** One receipt-member session with its receipt-member `CompletedSet`s. */
data class IntervalsActivitySessionRow(
    val session: WorkoutSession,
    val completedSets: List<CompletedSet>,
)

/** Sealed builder result: a visible failure, never truncation or fabrication. */
sealed class IntervalsActivityResult {
    data class Built(val activity: IntervalsManualActivity) : IntervalsActivityResult()
    data object PayloadTooLarge : IntervalsActivityResult()
    data class Invalid(val reason: String) : IntervalsActivityResult()
}
