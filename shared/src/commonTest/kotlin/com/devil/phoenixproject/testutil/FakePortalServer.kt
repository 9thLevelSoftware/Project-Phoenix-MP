package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.sync.KnownEntityIds
import com.devil.phoenixproject.data.sync.PortalSyncPayload
import com.devil.phoenixproject.data.sync.PortalSyncPullResponse
import com.devil.phoenixproject.data.sync.PortalSyncPushResponse
import com.devil.phoenixproject.data.sync.PullExerciseDto
import com.devil.phoenixproject.data.sync.PullSetDto
import com.devil.phoenixproject.data.sync.PullWorkoutSessionDto
import com.devil.phoenixproject.data.sync.SyncRejectionDto
import com.devil.phoenixproject.data.sync.SyncRejectionsDto

/**
 * In-memory stand-in for the portal's `workout_sessions` tree, modelling the three
 * server behaviours the mobile push has to live with:
 *
 *  1. **`replace_session_children`** — an accepted session DELETEs every exercise it
 *     owns (cascading to sets) and re-inserts exactly what the payload carried. A
 *     payload that omits a sibling therefore destroys it.
 *  2. **The `sessions_updated_at` trigger** — every accepted UPDATE stores
 *     `updated_at = now()` on the SERVER clock, not the client's value. An INSERT keeps
 *     the client value, because the trigger is BEFORE UPDATE only.
 *  3. **The LWW gate** — an update is accepted only while
 *     `stored.updated_at <= incoming.updated_at`; otherwise the row is returned as a
 *     rejection carrying the stored timestamp.
 *
 * `notes` follows the real `notes = EXCLUDED.notes`: whatever the payload carries wins
 * on an accepted push, including null.
 */
class FakePortalServer(var serverNow: () -> Long = { 1_700_000_000_000L }) {

    data class StoredSet(val id: String, val weightKg: Float, val actualReps: Int)

    data class StoredExercise(
        val id: String,
        val name: String,
        val sets: List<StoredSet>,
    )

    data class StoredSession(
        val id: String,
        var name: String?,
        var notes: String?,
        var updatedAt: Long,
        var startedAt: String?,
        var exerciseCount: Int,
        var setCount: Int,
        var routineSessionId: String?,
        var exercises: List<StoredExercise>,
    )

    val sessions: MutableMap<String, StoredSession> = linkedMapOf()

    /** Portal session ids this server rejected, in order, for assertions. */
    val rejectedIds: MutableList<String> = mutableListOf()

    fun session(id: String): StoredSession? = sessions[id]

    fun exerciseIds(sessionId: String): List<String> = sessions[sessionId]?.exercises?.map { it.id }.orEmpty()

    fun setCount(sessionId: String): Int = sessions[sessionId]?.exercises?.sumOf { it.sets.size } ?: 0

    /**
     * A note typed on the website. Like the real mutation it only writes `notes` and
     * lets the trigger bump `updated_at` — which is exactly why a later mobile push of
     * the same workout is the thing that can erase it.
     */
    fun writeWebNote(sessionId: String, note: String?, updatedAt: Long = serverNow()) {
        val existing = requireNotNull(sessions[sessionId]) { "no portal session $sessionId" }
        existing.notes = note
        existing.updatedAt = updatedAt
    }

    /** Seed a session the mobile device will later see through a pull. */
    fun seedSession(
        id: String,
        exercises: List<StoredExercise>,
        notes: String? = null,
        startedAt: String? = "2026-01-01T00:00:00Z",
        updatedAt: Long = serverNow(),
        routineSessionId: String? = id,
        name: String? = "Seeded",
    ) {
        sessions[id] = StoredSession(
            id = id,
            name = name,
            notes = notes,
            updatedAt = updatedAt,
            startedAt = startedAt,
            exerciseCount = exercises.size,
            setCount = exercises.sumOf { it.sets.size },
            routineSessionId = routineSessionId,
            exercises = exercises,
        )
    }

    fun push(payload: PortalSyncPayload): PortalSyncPushResponse {
        val rejections = mutableListOf<SyncRejectionDto>()
        var inserted = 0
        val accepted = mutableListOf<String>()
        for (dto in payload.sessions) {
            val incoming = dto.updatedAt?.let { parseIso(it) } ?: serverNow()
            val existing = sessions[dto.id]
            if (existing != null && existing.updatedAt > incoming) {
                rejections += SyncRejectionDto(dto.id, serverUpdatedAt = toIso(existing.updatedAt))
                rejectedIds += dto.id
                continue
            }
            val children = dto.exercises.map { ex ->
                StoredExercise(
                    id = ex.id,
                    name = ex.name,
                    sets = ex.sets.map { set -> StoredSet(set.id, set.weightKg, set.actualReps) },
                )
            }
            accepted += dto.id
            if (existing == null) {
                inserted++
                sessions[dto.id] = StoredSession(
                    id = dto.id,
                    name = dto.name,
                    notes = dto.notes,
                    // INSERT keeps the client timestamp: the trigger is BEFORE UPDATE.
                    updatedAt = incoming,
                    startedAt = dto.startedAt,
                    exerciseCount = dto.exerciseCount,
                    setCount = dto.setCount,
                    routineSessionId = dto.routineSessionId,
                    exercises = children,
                )
            } else {
                existing.name = dto.name
                existing.notes = dto.notes
                existing.startedAt = dto.startedAt
                existing.exerciseCount = dto.exerciseCount
                existing.setCount = dto.setCount
                existing.routineSessionId = dto.routineSessionId
                // replace_session_children: the old exercises and their sets are gone.
                existing.exercises = children
                // The trigger overwrites whatever the client sent.
                existing.updatedAt = serverNow()
            }
        }
        return PortalSyncPushResponse(
            syncTime = toIso(serverNow()),
            sessionsInserted = inserted,
            exercisesInserted = payload.sessions.sumOf { it.exercises.size },
            setsInserted = payload.sessions.sumOf { s -> s.exercises.sumOf { it.sets.size } },
            repSummariesInserted = 0,
            routinesUpserted = 0,
            badgesUpserted = 0,
            exerciseProgressInserted = 0,
            personalRecordsInserted = 0,
            rejections = SyncRejectionsDto(sessions = rejections),
            // upsert_workout_session_lww: every accepted row is acknowledged.
            acknowledgedWorkoutSessionIds = accepted,
        )
    }

    /**
     * Parity pull: the portal returns every session the device did NOT list as known.
     * A grouped routine workout's portal id is its `routineSessionId`, which is not a
     * local row id, so it always comes back — that is how a web note reaches the phone.
     */
    fun pull(knownEntityIds: KnownEntityIds): PortalSyncPullResponse {
        val known = knownEntityIds.sessionIds.toSet()
        return PortalSyncPullResponse(
            syncTime = serverNow(),
            sessions = sessions.values.filter { it.id !in known }.map { stored ->
                PullWorkoutSessionDto(
                    id = stored.id,
                    name = stored.name,
                    startedAt = stored.startedAt,
                    setCount = stored.setCount,
                    exerciseCount = stored.exerciseCount,
                    routineSessionId = stored.routineSessionId,
                    notes = stored.notes,
                    updatedAt = toIso(stored.updatedAt),
                    exercises = stored.exercises.mapIndexed { index, ex ->
                        PullExerciseDto(
                            id = ex.id,
                            sessionId = stored.id,
                            name = ex.name,
                            orderIndex = index,
                            sets = ex.sets.map { set ->
                                PullSetDto(
                                    id = set.id,
                                    exerciseId = ex.id,
                                    setNumber = 1,
                                    actualReps = set.actualReps,
                                    weightKg = set.weightKg,
                                )
                            },
                        )
                    },
                )
            },
            routines = emptyList(),
            rpgAttributes = null,
            badges = emptyList(),
            gamificationStats = null,
        )
    }

    private fun parseIso(value: String): Long = kotlin.time.Instant.parse(value).toEpochMilliseconds()

    private fun toIso(epochMs: Long): String = kotlin.time.Instant.fromEpochMilliseconds(epochMs).toString()
}

/** [FakePortalApiClient] wired to a [FakePortalServer] instead of canned results. */
class PortalServerApiClient(val server: FakePortalServer) : FakePortalApiClient() {

    /** Runs once, at the start of the next push — i.e. after the payload was gathered. */
    var onPush: (() -> Unit)? = null

    /**
     * Drops `serverUpdatedAt` from rejections, as a pre-LWW server that reports the
     * rejection without a timestamp would. Nothing can then be re-pushed against it.
     */
    var stripRejectionTimestamps: Boolean = false

    /**
     * Returns a success response that acknowledges no session and rejects none: the
     * portal did not apply them (e.g. a partial/older response shape). Nothing about
     * those sessions may be treated as accepted.
     */
    var stripAcknowledgements: Boolean = false

    override suspend fun pushPortalPayload(payload: PortalSyncPayload): Result<PortalSyncPushResponse> {
        onPush?.let {
            onPush = null
            it()
        }
        super.pushPortalPayload(payload)
        val served = server.push(payload)
        val response = if (stripAcknowledgements) {
            served.copy(acknowledgedWorkoutSessionIds = emptyList())
        } else {
            served
        }
        if (!stripRejectionTimestamps) return Result.success(response)
        return Result.success(
            response.copy(
                rejections = response.rejections.copy(
                    sessions = response.rejections.sessions.map { it.copy(serverUpdatedAt = null) },
                ),
            ),
        )
    }

    override suspend fun pullPortalPayload(
        knownEntityIds: KnownEntityIds,
        deviceId: String,
        profileId: String?,
        cursor: String?,
        pageSize: Int?,
        lastSync: Long,
    ): Result<PortalSyncPullResponse> {
        // Kotlin forbids default arguments in a super-call, so every parameter
        // (including lastSync, which the base defaults to 0L) must be passed.
        super.pullPortalPayload(knownEntityIds, deviceId, profileId, cursor, pageSize, lastSync)
        return Result.success(server.pull(knownEntityIds))
    }
}
