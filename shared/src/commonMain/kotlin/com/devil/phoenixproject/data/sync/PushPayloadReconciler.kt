package com.devil.phoenixproject.data.sync

import co.touchlab.kermit.Logger

/**
 * Issue #634 (reopened): push payload identity reconciliation.
 *
 * The push preflight used to hard-fail the whole sync on the first table with a
 * repeated payload key ("Duplicate IDs in local push payload"), leaving the
 * device permanently sync-blocked with no recovery. Worse, its personal-record
 * key omitted `workoutMode` and the record UUID, so two legitimate local rows
 * (distinct `idx_pr_unique` identities) could trip it while the portal would
 * happily keep both.
 *
 * This file reconciles the gathered payload against the DEPLOYED portal's
 * identity contract instead of blocking:
 *
 *  - `personal_records` with an id are keyed on that id (F335
 *    `personalRecordIdentityKey`); two dedicated rows with distinct UUIDs are
 *    always distinct, even when their derived identity matches.
 *  - Id-less legacy rows are keyed on the portal's derived identity
 *    (`personalRecordDerivedIdentityKey`: profile, exercise, achieved_at
 *    normalized to epoch milliseconds, record_type, workout_phase —
 *    deliberately WITHOUT workoutMode, which no portal identity includes).
 *  - Every other pushed entity is keyed on its id, case-insensitively: the
 *    portal stores these ids in `uuid` primary keys, which fold case, so
 *    "3C2C…" and "3c2c…" are the same server row even though SQLite's TEXT
 *    primary key treats them as distinct.
 *
 * Per identity group the policy is deterministic and non-lossy:
 *
 *  - One entry → sent unchanged.
 *  - Several byte-identical entries (projection / planner / request
 *    duplication) → ONE canonical entry is sent; nothing is lost because every
 *    occurrence carries the same content.
 *  - Several entries with DIFFERENT content (genuinely conflicting versions,
 *    case-variant twins, legacy rows the portal would collapse) → ALL of them
 *    are held back from this push and reported. Nothing is merged, deleted or
 *    stamped, no winner is picked, and the rows stay local and retryable.
 *
 * The report names every offending table (the old preflight surfaced only the
 * first) and carries redacted per-entry diagnostics (source row id, UUID,
 * workoutMode, timestamps, profile, update/tombstone state) so the next
 * diagnostics pass can see where the collision came from. No user PII.
 */

/** Identity keys mirroring the portal's row-identity contract. */
internal object PushPayloadIdentity {

    /** UUID-keyed identity: the portal's `uuid` primary keys fold case. */
    fun uuidKey(id: String): String = "uuid:" + id.trim().lowercase()

    /** F335 `personalRecordIdentityKey`: dedicated rows key on their id. */
    fun personalRecordKey(record: PortalPersonalRecordDto): String =
        record.id?.takeIf { it.isNotBlank() }?.let(::uuidKey)
            ?: personalRecordDerivedKey(record)

    /**
     * F335 `personalRecordDerivedIdentityKey` (id-less legacy rows): profile,
     * exercise, achieved_at normalized to epoch milliseconds, record type and
     * workout phase. Exact case — the portal's derived identity is an exact
     * string — and NO workoutMode, which no portal identity includes.
     */
    fun personalRecordDerivedKey(record: PortalPersonalRecordDto): String =
        listOf(
            record.localProfileId ?: "default",
            record.exerciseId?.takeIf { it.isNotBlank() }?.let { "id:$it" }
                ?: "name:${record.exerciseName}",
            normalizeAchievedAt(record.achievedAt),
            record.recordType,
            record.workoutPhase.ifBlank { "COMBINED" },
        ).joinToString("|")

    internal fun normalizeAchievedAt(value: String): String {
        val epochMs = parseAchievedAtEpochMs(value)
        return if (epochMs == null) value else "ts:$epochMs"
    }

    /**
     * The portal compares achieved_at as an instant (the 2026-07-07 incident was a
     * raw "Z" vs "+00:00" string mismatch). PostgREST returns explicit offsets and
     * mobile sends "Z"; both must normalize to the same epoch millisecond value.
     */
    private fun parseAchievedAtEpochMs(value: String): Long? {
        runCatching { kotlin.time.Instant.parse(value).toEpochMilliseconds() }
            .getOrNull()?.let { return it }
        val zForm = when {
            value.endsWith("+00:00") || value.endsWith("-00:00") -> value.dropLast(6) + "Z"
            value.endsWith("+0000") || value.endsWith("-0000") -> value.dropLast(5) + "Z"
            else -> return null
        }
        return runCatching { kotlin.time.Instant.parse(zForm).toEpochMilliseconds() }.getOrNull()
    }
}

// ─── Canonical content for identity comparison ─────────────────────────
//
// The portal's pushed tables key rows on `uuid` primary keys (UUID type), which
// fold case: "3C2C…" and "3c2c…" are ONE server row even though SQLite TEXT
// keys treat them as distinct. Content comparison must fold id case the same
// way, so case-variant twins with otherwise identical content repair by
// collapsing to one canonical entry (nothing lost), while any real difference
// (days, progress, tombstones, ownership, values) still conflicts and holds
// every occurrence back.

internal fun PortalWorkoutSessionDto.canonicalized(): PortalWorkoutSessionDto = copy(
    id = id.lowercase(),
    routineSessionId = routineSessionId?.lowercase(),
    exercises = exercises.map { it.canonicalized() },
)

internal fun PortalExerciseDto.canonicalized(): PortalExerciseDto = copy(
    id = id.lowercase(),
    sessionId = sessionId.lowercase(),
    sets = sets.map { it.canonicalized() },
)

internal fun PortalSetDto.canonicalized(): PortalSetDto = copy(
    id = id.lowercase(),
    exerciseId = exerciseId.lowercase(),
    repSummaries = repSummaries.map { it.canonicalized() },
)

internal fun PortalRepSummaryDto.canonicalized(): PortalRepSummaryDto = copy(
    id = id.lowercase(),
    setId = setId.lowercase(),
)

internal fun PortalRepTelemetryDto.canonicalized(): PortalRepTelemetryDto = copy(
    id = id.lowercase(),
    setId = setId.lowercase(),
)

internal fun PortalRoutineSyncDto.canonicalized(): PortalRoutineSyncDto = copy(
    id = id.lowercase(),
)

internal fun PortalTrainingCycleSyncDto.canonicalized(): PortalTrainingCycleSyncDto = copy(
    id = id.lowercase(),
)

internal fun CustomExerciseSyncDto.canonicalized(): CustomExerciseSyncDto = copy(
    clientId = clientId.lowercase(),
)

internal fun PortalPersonalRecordDto.canonicalized(): PortalPersonalRecordDto = copy(
    id = id?.lowercase(),
    exerciseId = exerciseId?.lowercase(),
    sessionId = sessionId?.lowercase(),
    achievedAt = PushPayloadIdentity.normalizeAchievedAt(achievedAt),
)

/** A same-identity group whose entries disagree: every entry is held back. */
internal data class PushPayloadConflict(
    val table: String,
    val identity: String,
    /** Redacted per-entry diagnostics (ids, UUIDs, modes, timestamps, versions). */
    val entries: List<String>,
)

/** Identical duplicate payload entries collapsed into one canonical entry. */
internal data class PushPayloadCollapse(
    val table: String,
    val identity: String,
    val duplicatesRemoved: Int,
)

/** Everything one push held back or collapsed, for logs, tests and the UI. */
internal class PushPayloadReconcileReport {
    private val conflictItems = mutableListOf<PushPayloadConflict>()
    private val collapseItems = mutableListOf<PushPayloadCollapse>()

    val conflicts: List<PushPayloadConflict> get() = conflictItems
    val collapsed: List<PushPayloadCollapse> get() = collapseItems

    /** Rows held back from the push (never sent, never stamped, still local). */
    val hasHeldBack: Boolean get() = conflictItems.isNotEmpty()

    fun recordConflict(table: String, identity: String, entries: List<String>) {
        conflictItems += PushPayloadConflict(table, identity, entries)
    }

    fun recordCollapse(table: String, identity: String, duplicatesRemoved: Int) {
        collapseItems += PushPayloadCollapse(table, identity, duplicatesRemoved)
    }

    /** Tables with at least one held-back conflict, in first-seen order. */
    fun heldBackTables(): List<String> = conflictItems.map { it.table }.distinct()

    /**
     * User-facing summary: names every offending table and how many rows were
     * held back. Conflicts stay visible on every sync until they are resolved
     * locally; nothing was deleted or merged.
     */
    fun heldBackSummary(): String? {
        if (conflictItems.isEmpty()) return null
        val perTable = conflictItems.groupBy { it.table }.entries.joinToString(", ") { (table, items) ->
            "$table (${items.size} key${if (items.size == 1) "" else "s"})"
        }
        val collapsedSuffix = if (collapseItems.isEmpty()) {
            ""
        } else {
            val total = collapseItems.sumOf { it.duplicatesRemoved }
            " Collapsed $total identical duplicate payload entr${if (total == 1) "y" else "ies"} " +
                "(${collapseItems.map { it.table }.distinct().joinToString()})."
        }
        return "Held back ${conflictItems.size} conflicting local record key(s) for retry — " +
            "nothing was merged or deleted: $perTable.$collapsedSuffix " +
            "Resolve the conflicting row(s) and sync again; details are in the sync log."
    }

    /** Redacted diagnostics for the sync log. No user PII, no free-text names. */
    fun logDetails(context: String) {
        if (conflictItems.isEmpty() && collapseItems.isEmpty()) return
        val lines = buildString {
            appendLine("$context: payload identity reconcile — " +
                "${conflictItems.size} held-back conflict key(s), " +
                "${collapseItems.sumOf { it.duplicatesRemoved }} collapsed duplicate entr(ies)")
            conflictItems.forEach { conflict ->
                appendLine("  CONFLICT ${conflict.table} key=${conflict.identity}")
                conflict.entries.forEach { entry -> appendLine("    $entry") }
            }
            collapseItems.forEach { collapse ->
                appendLine("  COLLAPSED ${collapse.table} key=${collapse.identity} " +
                    "removed=${collapse.duplicatesRemoved}")
            }
        }
        if (conflictItems.isNotEmpty()) {
            Logger.w("SyncManager") { lines.trimEnd() }
        } else {
            Logger.d("SyncManager") { lines.trimEnd() }
        }
    }
}

/** Kept entries plus the entries held back by a divergent identity conflict. */
internal data class ReconciledEntries<T>(
    val kept: List<T>,
    /** Conflict-held entries only (collapsed-away duplicates are not listed). */
    val heldBack: List<T>,
)

/**
 * Groups [entries] by [identityOf]; collapses identical duplicates to one
 * canonical entry and holds back every entry of a divergent group. [contentOf]
 * selects the compared content (usually the DTO itself), [describe] builds the
 * redacted diagnostic line for a held-back entry.
 */
internal fun <T> reconcileByIdentity(
    entries: List<T>,
    table: String,
    identityOf: (T) -> String,
    describe: (T) -> String,
    report: PushPayloadReconcileReport,
    contentOf: (T) -> Any = { it as Any },
): ReconciledEntries<T> {
    if (entries.isEmpty()) return ReconciledEntries(entries, emptyList())
    val kept = mutableListOf<T>()
    val heldBack = mutableListOf<T>()
    for ((identity, group) in entries.groupBy(identityOf)) {
        if (group.size == 1) {
            kept += group.single()
            continue
        }
        val distinctContent = group.map(contentOf).toSet()
        if (distinctContent.size == 1) {
            // Projection / planner / request duplication: the same row reached
            // the payload more than once with identical content. One canonical
            // entry loses nothing.
            kept += group.first()
            report.recordCollapse(table, identity, group.size - 1)
        } else {
            // Genuinely conflicting versions of one server identity. Sending any
            // subset would let the portal silently collapse them; keeping one
            // would arbitrarily discard another. Hold ALL back, report, retry.
            heldBack += group
            report.recordConflict(table, identity, group.map(describe))
        }
    }
    return ReconciledEntries(kept, heldBack)
}

/**
 * Reconciles session DTOs including their nested exercise/set/rep-summary
 * trees. Nested duplicates are collapsed when identical; a divergent nested
 * duplicate holds back every session carrying one of its occurrences (the
 * session is the pushable unit and the portal replaces session children on
 * upsert, so partial trees would rewrite sibling rows).
 */
internal fun reconcileSessionTrees(
    sessions: List<PortalWorkoutSessionDto>,
    report: PushPayloadReconcileReport,
): List<PortalWorkoutSessionDto> {
    if (sessions.isEmpty()) return sessions

    // 1. Session-level identity first, so an identical duplicate session pair
    //    collapses before its nested trees can look divergent against each other.
    val topLevel = reconcileByIdentity(
        entries = sessions,
        table = "workout_sessions",
        identityOf = { PushPayloadIdentity.uuidKey(it.id) },
        describe = {
            "sessionId=${it.id} routineSessionId=${it.routineSessionId} " +
                "updatedAt=${it.updatedAt} setCount=${it.exercises.sumOf { e -> e.sets.size }}"
        },
        report = report,
        contentOf = { it.canonicalized() },
    ).kept

    data class NestedOccurrence(
        val sessionIndex: Int,
        val content: Any,
        val describe: String,
    )

    // Session indices whose nested trees hold divergent same-identity entries.
    val heldSessionIndices = mutableSetOf<Int>()

    fun reconcileNested(
        table: String,
        occurrences: List<Pair<NestedOccurrence, String>>,
    ) {
        val byIdentity = occurrences.groupBy({ it.second }, { it.first })
        for ((identity, group) in byIdentity) {
            if (group.size == 1) continue
            if (group.map { it.content }.toSet().size == 1) {
                report.recordCollapse(table, identity, group.size - 1)
            } else {
                report.recordConflict(table, identity, group.map { it.describe })
                group.forEach { heldSessionIndices += it.sessionIndex }
            }
        }
    }

    // Identical nested duplicates collapse to their first occurrence; occurrences
    // inside held sessions disappear with the session.
    val firstOccurrenceKey = mutableMapOf<String, MutableSet<String>>()

    fun <E> nestedEntries(
        items: List<E>,
        sessionIndex: Int,
        session: PortalWorkoutSessionDto,
        idOf: (E) -> String,
        describe: (E) -> String,
        contentOf: (E) -> Any,
    ): List<Pair<NestedOccurrence, String>> = items.map { item ->
        NestedOccurrence(
            sessionIndex = sessionIndex,
            content = contentOf(item),
            describe = "session=${session.id} " + describe(item),
        ) to PushPayloadIdentity.uuidKey(idOf(item))
    }

    val exerciseOccurrences = topLevel.flatMapIndexed { index, session ->
        nestedEntries(
            session.exercises, index, session,
            idOf = { it.id },
            describe = { "exerciseId=${it.id} setCount=${it.sets.size}" },
            contentOf = { it.canonicalized() },
        )
    }
    val setOccurrences = topLevel.flatMapIndexed { index, session ->
        session.exercises.flatMap { exercise ->
            nestedEntries(
                exercise.sets, index, session,
                idOf = { it.id },
                describe = { "setId=${it.id} setNumber=${it.setNumber}" },
                contentOf = { it.canonicalized() },
            )
        }
    }
    val repOccurrences = topLevel.flatMapIndexed { index, session ->
        session.exercises.flatMap { exercise ->
            exercise.sets.flatMap { set ->
                nestedEntries(
                    set.repSummaries, index, session,
                    idOf = { it.id },
                    describe = { "repSummaryId=${it.id} repNumber=${it.repNumber}" },
                    contentOf = { it.canonicalized() },
                )
            }
        }
    }
    reconcileNested("exercises", exerciseOccurrences)
    reconcileNested("sets", setOccurrences)
    reconcileNested("rep_summaries", repOccurrences)

    // Rebuild the surviving sessions without the collapsed duplicate nested
    // entries (first occurrence of each nested identity wins).
    fun <E> keepFirstOccurrences(items: List<E>, table: String, idOf: (E) -> String): List<E> {
        val seen = firstOccurrenceKey.getOrPut(table) { mutableSetOf() }
        return items.filter { seen.add(PushPayloadIdentity.uuidKey(idOf(it))) }
    }

    return topLevel.mapIndexedNotNull { index, session ->
        if (index in heldSessionIndices) return@mapIndexedNotNull null
        val exercises = session.exercises
            .let { keepFirstOccurrences(it, "exercises", PortalExerciseDto::id) }
            .map { exercise ->
                val sets = exercise.sets
                    .let { keepFirstOccurrences(it, "sets", PortalSetDto::id) }
                    .map { set ->
                        set.copy(
                            repSummaries = keepFirstOccurrences(
                                set.repSummaries, "rep_summaries", PortalRepSummaryDto::id,
                            ),
                        )
                    }
                exercise.copy(sets = sets)
            }
        session.copy(exercises = exercises)
    }
}

/**
 * Final safety net over assembled requests (Layer 2): the planner may still
 * emit one entity twice inside a single request. Identical duplicates collapse
 * to one canonical entry; divergent ones are removed from the request and
 * reported. Cross-request re-sends of the same dedicated row are intentional
 * (the portal upserts on id) and are not touched here — this only enforces
 * "one canonical entry per identity per request".
 */
internal fun reconcileAssembledRequests(
    requests: List<PortalSyncPayload>,
    report: PushPayloadReconcileReport,
): List<PortalSyncPayload> = requests.map { payload ->
    val sessions = reconcileSessionTrees(payload.sessions, report)
    val routines = reconcileByIdentity(
        entries = payload.routines,
        table = "routines",
        identityOf = { PushPayloadIdentity.uuidKey(it.id) },
        describe = { "routineId=${it.id} userId=${it.userId} updatedAt=${it.updatedAt}" },
        report = report,
        contentOf = { it.canonicalized() },
    ).kept
    val cycles = reconcileByIdentity(
        entries = payload.cycles,
        table = "training_cycles",
        identityOf = { PushPayloadIdentity.uuidKey(it.id) },
        describe = {
            "cycleId=${it.id} updatedAt=${it.updatedAt} startedAt=${it.startedAt} " +
                "durationWeeks=${it.durationWeeks} days=${it.days.size}"
        },
        report = report,
        contentOf = { it.canonicalized() },
    ).kept
    val customExercises = reconcileByIdentity(
        entries = payload.customExercises,
        table = "exercise_catalog",
        identityOf = { PushPayloadIdentity.uuidKey(it.clientId) },
        describe = { "clientId=${it.clientId} updatedAt=${it.updatedAt} deletedAt=${it.deletedAt}" },
        report = report,
        contentOf = { it.canonicalized() },
    ).kept
    val personalRecords = reconcileByIdentity(
        entries = payload.personalRecords,
        table = "personal_records",
        identityOf = PushPayloadIdentity::personalRecordKey,
        describe = ::describePersonalRecord,
        report = report,
        contentOf = { it.canonicalized() },
    ).kept
    val telemetry = reconcileByIdentity(
        entries = payload.telemetry,
        table = "rep_telemetry",
        identityOf = { PushPayloadIdentity.uuidKey(it.id) },
        describe = { "telemetryId=${it.id} setId=${it.setId} timestampMs=${it.timestampMs}" },
        report = report,
        contentOf = { it.canonicalized() },
    ).kept
    payload.copy(
        sessions = sessions,
        routines = routines,
        cycles = cycles,
        customExercises = customExercises,
        personalRecords = personalRecords,
        telemetry = telemetry,
    )
}

/** Redacted PR diagnostics: identity/version metadata, never free-text names. */
internal fun describePersonalRecord(record: PortalPersonalRecordDto): String =
    "id=${record.id} mode=${record.workoutMode} at=${record.achievedAt} " +
        "type=${record.recordType} phase=${record.workoutPhase} " +
        "profile=${record.localProfileId ?: "default"} " +
        "updatedAt=${record.updatedAt} deletedAt=${record.deletedAt}"

// ─── Duplicate detector (pre-send hard guard) ─────────────────────────

internal data class PushPayloadDuplicateKeys(
    val table: String,
    val ids: List<String>,
) {
    fun toExceptionMessage(): String =
        "$table contains duplicate key(s): ${ids.joinToString()}"
}

/**
 * Reports every table whose payload still carries repeated identity keys after
 * reconciliation (the pre-#634 code surfaced only the first offending table).
 * Kept as the hard safety guard behind [reconcileAssembledRequests].
 */
internal fun findPushPayloadDuplicateKeys(
    payload: PortalSyncPayload,
): List<PushPayloadDuplicateKeys> {
    val reports = mutableListOf<PushPayloadDuplicateKeys>()

    reports.addDuplicateKeys(
        table = "workout_sessions",
        entries = payload.sessions.map { it.id to PushPayloadIdentity.uuidKey(it.id) },
    )
    reports.addDuplicateKeys(
        table = "routines",
        entries = payload.routines.map { it.id to PushPayloadIdentity.uuidKey(it.id) },
    )
    reports.addDuplicateKeys(
        table = "training_cycles",
        entries = payload.cycles.map { it.id to PushPayloadIdentity.uuidKey(it.id) },
    )
    reports.addDuplicateKeys(
        table = "exercise_catalog",
        entries = payload.customExercises.map { it.clientId to PushPayloadIdentity.uuidKey(it.clientId) },
    )
    reports.addDuplicateKeys(
        table = "exercises",
        entries = payload.sessions.flatMap { session ->
            session.exercises.map { it.id to PushPayloadIdentity.uuidKey(it.id) }
        },
    )
    reports.addDuplicateKeys(
        table = "sets",
        entries = payload.sessions.flatMap { session ->
            session.exercises.flatMap { exercise ->
                exercise.sets.map { it.id to PushPayloadIdentity.uuidKey(it.id) }
            }
        },
    )
    reports.addDuplicateKeys(
        table = "rep_summaries",
        entries = payload.sessions.flatMap { session ->
            session.exercises.flatMap { exercise ->
                exercise.sets.flatMap { set ->
                    set.repSummaries.map { it.id to PushPayloadIdentity.uuidKey(it.id) }
                }
            }
        },
    )
    reports.addDuplicateKeys(
        table = "rep_telemetry",
        entries = payload.telemetry.map { it.id to PushPayloadIdentity.uuidKey(it.id) },
    )
    reports.addDuplicateKeys(
        table = "personal_records",
        entries = payload.personalRecords.map {
            val key = PushPayloadIdentity.personalRecordKey(it)
            key to key
        },
    )

    return reports
}

private fun MutableList<PushPayloadDuplicateKeys>.addDuplicateKeys(
    table: String,
    entries: List<Pair<String, String>>,
) {
    val seen = mutableSetOf<String>()
    val duplicates = linkedSetOf<String>()
    entries.forEach { (raw, identity) ->
        if (identity.isNotBlank() && !seen.add(identity)) {
            duplicates.add(raw)
        }
    }
    if (duplicates.isNotEmpty()) {
        add(PushPayloadDuplicateKeys(table, duplicates.toList()))
    }
}
