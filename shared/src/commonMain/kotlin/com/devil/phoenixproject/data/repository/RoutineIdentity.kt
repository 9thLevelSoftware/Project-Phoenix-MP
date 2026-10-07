package com.devil.phoenixproject.data.repository

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.database.PhoenixDatabaseQueries
import com.devil.phoenixproject.database.Routine as RoutineRow

/**
 * Issue #1162 — routine UUID identity contract.
 *
 * Cloud identity for a routine is a UUID: the portal stores it as a PostgreSQL
 * `uuid`, whose textual projection is lowercase, while iOS captures generate
 * uppercase `NSUUID` text. Local storage keys routines as case-sensitive TEXT,
 * so a case-variant id used to miss the same-row lookup and insert a second row
 * for one logical routine (the "one routine appearing twice" symptom).
 *
 * Rules (see the bounded RCA and architecture review on issue #1162):
 * - UUID equivalence applies ONLY to a full 8-4-4-4-12 hex id. Opaque ids
 *   (`cycle_routine_*`, `superset_*`, catalogue exercise ids, group ids) are
 *   never case-normalized and only match by exact text.
 * - Canonical lowercase is a wire and lookup form only. Stored primary keys are
 *   preserved; identity repair never uses INSERT OR REPLACE on a Routine.
 * - Identity resolution is owner-scoped: rows of other profiles are never
 *   coalesced or updated.
 */
internal object RoutineIdentity {
    private val UUID_REGEX = Regex(
        "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
    )

    /** True only for a full 8-4-4-4-12 hex id (any case). */
    fun isUuid(text: String?): Boolean = text != null && UUID_REGEX.matches(text)

    /** Lowercase canonical text for a validated UUID id; null for opaque ids. */
    fun canonicalUuidOrNull(text: String?): String? = text?.takeIf { isUuid(it) }?.lowercase()

    /** Wire/lookup form: lowercase canonical UUID text, or the opaque id unchanged. */
    fun canonicalize(text: String): String = canonicalUuidOrNull(text) ?: text

    /**
     * True when both texts denote one identity: exact text equality, or two
     * spellings of the same validated UUID. Two distinct UUIDs never match, and
     * two opaque ids that differ only in case never match.
     */
    fun matches(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        if (a == b) return true
        val canonicalA = canonicalUuidOrNull(a) ?: return false
        return canonicalA == canonicalUuidOrNull(b)
    }
}

/**
 * Outcome of owner-scoped routine identity resolution.
 *
 * [localId] is the primary key every local read/write for this identity must
 * use. [canonicalId] is the wire/lookup form (lowercase canonical UUID text, or
 * the opaque id unchanged); for an existing row the two can differ, but they
 * always denote one logical identity.
 */
internal data class RoutineIdentityResolution(
    val canonicalId: String,
    val localId: String,
    val keptRow: RoutineRow?,
    /** Primary keys coalesced into [localId] by alias reconciliation. */
    val coalescedAwayIds: List<String>,
)

/** Outcome of eager maintenance over one existing same-identity component. */
data class ComponentReconciliation(
    val keeperId: String,
    val removedIds: List<String>,
    /** True when a tombstoned row was kept (and its `deletedAt` preserved). */
    val tombstonePreserved: Boolean,
)

/**
 * Owner-scoped routine identity resolver (Issue #1162).
 *
 * Resolves an incoming routine id (save, pull merge, or server deletion) to the
 * one local routine row it denotes, matching exact id, exact stored `serverId`
 * aliases, and validated-UUID-equivalent spellings of either. When one identity
 * has already split into several local rows, they are reconciled
 * transactionally into one kept row: children are matched by validated UUID
 * equivalence and updated in place (planned-set parents survive), distinct
 * children and supersets are re-parented with their primary keys preserved, and
 * CycleDay references follow the kept primary key.
 *
 * Callers must run [resolve] inside a database transaction.
 */
internal class RoutineIdentityResolver(private val queries: PhoenixDatabaseQueries) {

    /**
     * All owner-scoped rows denoting the same identity as [incomingId], joined
     * transitively through id/serverId identity edges. Pure read: safe to call
     * before ownership claims are routed. When the owner scope is a single
     * profile, pass [scopeProfileId] so only that profile's rows (the indexed
     * `profile_id` lookup) are read instead of the whole table.
     */
    fun findCandidates(
        incomingId: String,
        scopeProfileId: String? = null,
        scopeMatches: (RoutineRow) -> Boolean,
    ): List<RoutineRow> {
        val scopedRows = (
            if (scopeProfileId != null) {
                queries.selectAllRoutinesByProfileIncludingDeleted(scopeProfileId).executeAsList()
            } else {
                queries.selectAllRoutinesSync().executeAsList()
            }
            ).filter(scopeMatches)
        val found = LinkedHashMap<String, RoutineRow>()
        val frontier = ArrayDeque<String>()
        frontier.add(incomingId)
        val seenKeys = HashSet<String>()
        while (frontier.isNotEmpty()) {
            val key = frontier.removeFirst()
            // Canonical collapse: a case-variant spelling of a UUID key reaches the
            // same rows, so it need not re-enter the frontier.
            if (!seenKeys.add(RoutineIdentity.canonicalize(key))) continue
            for (row in scopedRows) {
                if (found.containsKey(row.id)) continue
                if (RoutineIdentity.matches(row.id, key) || RoutineIdentity.matches(row.serverId, key)) {
                    found[row.id] = row
                    frontier.add(row.id)
                    row.serverId?.let { frontier.add(it) }
                }
            }
        }
        return found.values.toList()
    }

    /**
     * Resolve [incomingId] to the single local routine identity inside the
     * [scopeMatches] owner scope, coalescing already-split aliases into one kept
     * row. When no row exists yet, the incoming id text is preserved verbatim as
     * the new primary key — canonical lowercase is a wire and lookup form only.
     * Must run inside a database transaction.
     */
    fun resolve(
        incomingId: String,
        scopeProfileId: String? = null,
        scopeMatches: (RoutineRow) -> Boolean,
    ): RoutineIdentityResolution {
        val candidates = findCandidates(incomingId, scopeProfileId, scopeMatches)
        val canonicalId = RoutineIdentity.canonicalize(incomingId)
        if (candidates.isEmpty()) {
            return RoutineIdentityResolution(canonicalId, incomingId, null, emptyList())
        }
        val keeper = selectKeeper(incomingId, candidates)
        val losers = candidates.filter { it.id != keeper.id }
        if (losers.isNotEmpty()) {
            coalesce(keeper.id, losers)
        }
        val keptRow = queries.selectRoutineById(keeper.id).executeAsOneOrNull() ?: keeper
        return RoutineIdentityResolution(
            canonicalId = canonicalId,
            localId = keeper.id,
            keptRow = keptRow,
            coalescedAwayIds = losers.map { it.id },
        )
    }

    /**
     * Deterministic kept row: the most recently updated identity wins (a newer
     * local edit survives), then — at equal time — a tombstone beats a live row
     * (Issue #1162 acceptance: an equal-time tombstone can never be coalesced
     * away by an equal-time live row, which would resurrect the identity), then
     * the exact incoming text, then the lexicographically smallest id. Never
     * picks across ownership boundaries — candidates are already owner-scoped.
     */
    private fun selectKeeper(incomingId: String, candidates: List<RoutineRow>): RoutineRow =
        candidates.sortedWith(
            compareByDescending<RoutineRow> { it.deletedAt ?: it.updatedAt ?: it.createdAt }
                .thenBy { if (it.deletedAt != null) 0 else 1 }
                .thenBy { if (it.id == incomingId) 0 else 1 }
                .thenBy { it.id },
        ).first()

    /**
     * Issue #1162 eager maintenance: reconcile one existing same-identity
     * component (already-split alias rows) without any incoming write. Must run
     * inside a database transaction, and the caller must retain the full source
     * graphs in the recovery store before calling, since coalescing is
     * destructive for the alias rows.
     *
     * A tombstoned component never passes through unchanged [resolve]/selectKeeper
     * semantics: if any candidate has `deletedAt`, that tombstoned row is kept with
     * `deletedAt` preserved and the live aliases are removed into it — a tombstoned
     * identity is never cleared or resurrected. Only components with at least two
     * rows are touched; the kept primary key is preserved.
     */
    fun reconcileExistingComponent(candidates: List<RoutineRow>): ComponentReconciliation? {
        if (candidates.size < 2) return null
        val tombstone = candidates.filter { it.deletedAt != null }.sortedWith(
            compareByDescending<RoutineRow> { it.deletedAt ?: it.updatedAt ?: it.createdAt }.thenBy { it.id },
        ).firstOrNull()
        val keeper = tombstone ?: selectKeeper(
            incomingId = candidates.map { it.id }.sorted().first(),
            candidates = candidates,
        )
        val losers = candidates.filter { it.id != keeper.id }
        if (losers.isEmpty()) return null
        coalesce(keeper.id, losers)
        return ComponentReconciliation(
            keeperId = keeper.id,
            removedIds = losers.map { it.id },
            tombstonePreserved = tombstone != null,
        )
    }

    /**
     * Merge each alias row into [keeperId] without discarding either row's data:
     * - exercises/supersets matching by validated UUID equivalence are one logical
     *   child: the kept parent's child row stays exactly as it is (never deleted or
     *   overwritten just because id text case differs; intentional default settings
     *   survive), the same-identity duplicate row goes away, and its planned sets
     *   migrate onto the kept child;
     * - distinct children/supersets are re-parented with their primary keys and
     *   columns preserved;
     * - CycleDay references follow the kept primary key when the reference is a
     *   validated UUID; cycle/workout identity is never rewritten;
     * - a genuine `serverId` alias and group/usage metadata survive on the kept
     *   row; the kept row's content is the recency winner.
     * Must run inside a database transaction.
     */
    private fun coalesce(keeperId: String, losers: List<RoutineRow>) {
        for (loser in losers) {
            val keeper = queries.selectRoutineById(keeperId).executeAsOneOrNull() ?: continue

            // Supersets first: exercises reference them.
            val keeperSupersets = queries.selectSupersetsByRoutine(keeperId).executeAsList()
            for (loserSuperset in queries.selectSupersetsByRoutine(loser.id).executeAsList()) {
                val twin = keeperSupersets.firstOrNull { RoutineIdentity.matches(it.id, loserSuperset.id) }
                if (twin != null) {
                    queries.repointRoutineExerciseSupersets(
                        keptSupersetId = twin.id,
                        oldSupersetId = loserSuperset.id,
                    )
                    queries.deleteSuperset(loserSuperset.id)
                } else {
                    queries.reparentSupersetToRoutine(routineId = keeperId, id = loserSuperset.id)
                }
            }

            // Exercises: UUID-equivalent twins merge into the kept child row, planned
            // sets migrate so the kept child keeps the full programming; distinct
            // children move with their primary keys preserved.
            val keeperExercises = queries.selectExercisesByRoutine(keeperId).executeAsList()
            for (loserExercise in queries.selectExercisesByRoutine(loser.id).executeAsList()) {
                val twin = keeperExercises.firstOrNull { RoutineIdentity.matches(it.id, loserExercise.id) }
                if (twin == null) {
                    queries.reparentRoutineExerciseToRoutine(routineId = keeperId, id = loserExercise.id)
                    continue
                }
                val takenSetNumbers = queries.selectPlannedSetsByRoutineExercise(twin.id)
                    .executeAsList()
                    .mapTo(HashSet()) { it.set_number }
                for (plannedSet in queries.selectPlannedSetsByRoutineExercise(loserExercise.id).executeAsList()) {
                    if (plannedSet.set_number !in takenSetNumbers) {
                        takenSetNumbers += plannedSet.set_number
                        queries.repointPlannedSetParent(routineExerciseId = twin.id, id = plannedSet.id)
                    } else {
                        queries.deletePlannedSet(plannedSet.id)
                    }
                }
                // The kept twin row keeps its own columns — intentional schema-default
                // settings are never overwritten from the alias row (no child-level
                // timestamp ranks edits; the kept parent is the LWW recency winner).
                queries.deleteRoutineExerciseById(loserExercise.id)
            }

            // CycleDay links follow the kept routine primary key for validated UUID
            // references only (opaque template references are left alone here and
            // cleared like a hard delete so they cannot dangle).
            if (RoutineIdentity.isUuid(loser.id)) {
                queries.repointCycleDayRoutineReference(keptRoutineId = keeperId, oldRoutineId = loser.id)
            } else {
                queries.clearCycleDayRoutineReferences(loser.id)
            }

            if (keeper.serverId == null && loser.serverId != null) {
                queries.updateRoutineServerId(loser.serverId, keeperId)
            }

            // Group/usage metadata merges into the kept row. Name/description and
            // sync timestamps stay the kept row's (it is the recency winner).
            queries.updateRoutineFields(
                name = keeper.name,
                description = keeper.description,
                createdAt = minOf(keeper.createdAt, loser.createdAt),
                lastUsed = maxOf(keeper.lastUsed ?: 0L, loser.lastUsed ?: 0L).takeIf { it > 0L }
                    ?: keeper.lastUsed,
                useCount = keeper.useCount + loser.useCount,
                updatedAt = keeper.updatedAt,
                profile_id = keeper.profile_id,
                groupId = keeper.groupId ?: loser.groupId,
                id = keeperId,
            )

            queries.deleteRoutineById(loser.id)
            Logger.d("RoutineIdentity") {
                "Issue #1162: coalesced routine alias '${loser.id}' into kept row '$keeperId'"
            }
        }
    }
}
