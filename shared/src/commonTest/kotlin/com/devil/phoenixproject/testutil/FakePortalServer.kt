package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.data.sync.KnownEntityIds
import com.devil.phoenixproject.data.sync.PortalRepSummaryDto
import com.devil.phoenixproject.data.sync.PortalSyncPayload
import com.devil.phoenixproject.data.sync.PortalSyncPullResponse
import com.devil.phoenixproject.data.sync.PortalSyncPushResponse
import com.devil.phoenixproject.data.sync.PullExerciseDto
import com.devil.phoenixproject.data.sync.PullRepSummaryDto
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

    data class StoredSet(
        val id: String,
        val weightKg: Float,
        val actualReps: Int,
        /**
         * Scalar rep summaries. The real portal stores these per set and returns them on
         * pull (`_repSummaries`), and the mobile push ALWAYS sends them - `includeTelemetry`
         * gates only the 50 Hz force curves. Dropping them here would hide the fact that
         * pulled force telemetry is what proves a measured Echo load is a measurement.
         */
        val repSummaries: List<PullRepSummaryDto> = emptyList(),
    )

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
        /**
         * Session-level config the real portal stores and returns on pull
         * (`mobile-sync-pull/index.ts` projects workout_mode, eccentric_load, echo_level,
         * warmup_reps, working_reps and heaviest_lift_kg). Without these a round-tripped
         * Echo session would come back as OldSchool and the round trip would look clean
         * for the wrong reason.
         */
        var workoutMode: String? = null,
        var eccentricLoad: Int? = null,
        var echoLevel: Int? = null,
        var warmupReps: Int? = null,
        var workingReps: Int? = null,
        var heaviestLiftKg: Float? = null,
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
                    sets = ex.sets.map { set ->
                        StoredSet(
                            id = set.id,
                            weightKg = set.weightKg,
                            actualReps = set.actualReps,
                            repSummaries = set.repSummaries.map { it.toPullRepSummary() },
                        )
                    },
                )
            }
            accepted += dto.id
            if (existing == null) {
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
                    workoutMode = dto.workoutMode,
                    eccentricLoad = dto.eccentricLoad,
                    echoLevel = dto.echoLevel,
                    warmupReps = dto.warmupReps,
                    workingReps = dto.workingReps,
                    heaviestLiftKg = dto.heaviestLiftKg,
                )
            } else {
                existing.name = dto.name
                existing.notes = dto.notes
                existing.startedAt = dto.startedAt
                existing.exerciseCount = dto.exerciseCount
                existing.setCount = dto.setCount
                existing.routineSessionId = dto.routineSessionId
                existing.workoutMode = dto.workoutMode
                existing.eccentricLoad = dto.eccentricLoad
                existing.echoLevel = dto.echoLevel
                existing.warmupReps = dto.warmupReps
                existing.workingReps = dto.workingReps
                existing.heaviestLiftKg = dto.heaviestLiftKg
                // replace_session_children: the old exercises and their sets are gone.
                existing.exercises = children
                // The trigger overwrites whatever the client sent.
                existing.updatedAt = serverNow()
            }
        }
        return PortalSyncPushResponse(
            syncTime = toIso(serverNow()),
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
                    // Session-level config, exactly as mobile-sync-pull/index.ts projects it.
                    workoutMode = stored.workoutMode,
                    eccentricLoad = stored.eccentricLoad,
                    echoLevel = stored.echoLevel,
                    warmupReps = stored.warmupReps,
                    workingReps = stored.workingReps,
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
                                    repSummaries = set.repSummaries,
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

/**
 * The push and pull rep-summary DTOs are field-identical; the portal stores the pushed row
 * and projects it back verbatim (`mobile-sync-pull/index.ts` `_repSummaries`).
 */
private fun PortalRepSummaryDto.toPullRepSummary(): PullRepSummaryDto = PullRepSummaryDto(
    id = id,
    setId = setId,
    repNumber = repNumber,
    meanVelocityMps = meanVelocityMps,
    peakVelocityMps = peakVelocityMps,
    meanForceN = meanForceN,
    peakForceN = peakForceN,
    powerWatts = powerWatts,
    romMm = romMm,
    tutMs = tutMs,
    leftForceAvg = leftForceAvg,
    rightForceAvg = rightForceAvg,
    asymmetryPct = asymmetryPct,
    vbtZone = vbtZone,
)

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
