package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabaseQueries
import com.devil.phoenixproject.database.Routine as RoutineRow
import com.devil.phoenixproject.domain.model.currentTimeMillis
import com.devil.phoenixproject.domain.model.generateUUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Issue #1162 — local-only routine recovery snapshots.
 *
 * Before a destructive routine operation (a server-reported hard delete, or the
 * coalesce of already-split identity aliases), the complete source routine graph
 * is retained in the `RoutineRecovery` table in the SAME transaction. The table
 * is deliberately outside every sync surface: routine lists, `deletedRoutineIds`,
 * known-id lists, ownership claims and push DTOs never see it, and it never
 * replaces the server-deleted identity.
 *
 * Retention contract (architecture review on issue #1162):
 * - inserts are insert-if-absent per (portal user, profile, canonical identity,
 *   reason): a repeated delivery can neither overwrite a complete snapshot nor
 *   refresh its 30-day expiry;
 * - a snapshot write failure throws, rolling back the destructive write (and
 *   failing the sync page so its checkpoint does not advance);
 * - snapshots are shown/restored only for the signed-in portal user and the
 *   active profile; `cycle_routine_*` template deletions are never surfaced as
 *   user routines;
 * - restore is an explicit restore-as-copy: fresh routine and child UUIDs in the
 *   same profile, never the server-deleted identity, never a silent cycle or
 *   history reconnect, never automatic.
 */
internal object RoutineRecoveryReasons {
    const val SERVER_DELETE = "server_delete"
    const val ALIAS_COALESCE = "alias_coalesce"
}

/** Provenance recorded with every retained source graph (structured receipt metadata). */
@Serializable
data class RoutineRecoveryProvenance(
    val reason: String,
    /** Where the destructive operation came from: pull / pull_cycle / named_write / identity maintenance. */
    val source: String,
    val incomingIdentity: String? = null,
    val resolvedLocalIdentity: String? = null,
    val localUpdatedAt: Long? = null,
    val appliedAt: Long,
)

@Serializable
data class RoutineRecoveryRoutineSnapshot(
    val id: String,
    val name: String,
    val description: String,
    val createdAt: Long,
    val lastUsed: Long?,
    val useCount: Long,
    val updatedAt: Long?,
    val serverId: String?,
    val deletedAt: Long?,
    val profileId: String,
    val groupId: String?,
)

@Serializable
data class RoutineRecoveryExerciseSnapshot(
    val id: String,
    val routineId: String,
    val exerciseName: String,
    val exerciseMuscleGroup: String,
    val exerciseEquipment: String,
    val exerciseDefaultCableConfig: String,
    val exerciseId: String?,
    val cableConfig: String,
    val orderIndex: Long,
    val setReps: String,
    val weightPerCableKg: Double,
    val setWeights: String,
    val mode: String,
    val eccentricLoad: Long,
    val echoLevel: Long,
    val progressionKg: Double,
    val restSeconds: Long,
    val duration: Long?,
    val setRestSeconds: String,
    val perSetRestTime: Long,
    val isAMRAP: Long,
    val supersetId: String?,
    val orderInSuperset: Long,
    val usePercentOfPR: Long,
    val weightPercentOfPR: Long,
    val prTypeForScaling: String,
    val setWeightsPercentOfPR: String?,
    val scalingBasis: String?,
    val stallDetectionEnabled: Long,
    val stopAtTop: Long,
    val repCountTiming: String,
    val setEchoLevels: String,
    val warmupSets: String,
    val defaultRackItemIds: String,
    val rackBehaviorOverrides: String,
    val isBodyweight: Long?,
    val dropSetEnabled: Long,
    val dropSetMinWeightKg: Double?,
    val durationSyncKnown: Long,
)

@Serializable
data class RoutineRecoverySupersetSnapshot(
    val id: String,
    val routineId: String,
    val name: String,
    val colorIndex: Long,
    val restBetweenSeconds: Long,
    val orderIndex: Long,
)

@Serializable
data class RoutineRecoveryPlannedSetSnapshot(
    val id: String,
    val routineExerciseId: String,
    val setNumber: Long,
    val setType: String,
    val targetReps: Long?,
    val targetWeightKg: Double?,
    val targetRpe: Long?,
    val restSeconds: Long?,
)

@Serializable
data class RoutineRecoveryCycleDayRefSnapshot(
    val id: String,
    val cycleId: String,
    val dayNumber: Long,
)

/** One complete source routine graph as it existed immediately before the destructive write. */
@Serializable
data class RoutineRecoveryRoutineGraph(
    val routine: RoutineRecoveryRoutineSnapshot,
    val exercises: List<RoutineRecoveryExerciseSnapshot>,
    val supersets: List<RoutineRecoverySupersetSnapshot>,
    val plannedSets: List<RoutineRecoveryPlannedSetSnapshot>,
    val cycleDayRefs: List<RoutineRecoveryCycleDayRefSnapshot>,
)

@Serializable
data class RoutineRecoveryPayload(
    val provenance: RoutineRecoveryProvenance,
    val routines: List<RoutineRecoveryRoutineGraph>,
)

/** Preview of one recoverable routine's programming (including planned-set slots). */
data class RoutineRecoverySetPreview(
    val setNumber: Long,
    val setType: String,
    val targetReps: Long?,
    val targetWeightKg: Double?,
)

data class RoutineRecoveryExercisePreview(
    val exerciseName: String,
    val setReps: String,
    val plannedSets: List<RoutineRecoverySetPreview>,
)

data class RoutineRecoveryItem(
    val recoveryId: String,
    val graphIndex: Int,
    val reason: String,
    val routineName: String,
    val exercises: List<RoutineRecoveryExercisePreview>,
)

open class RoutineRecoveryStore(private val queries: PhoenixDatabaseQueries) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Retain the complete source graph of every row in [rows] before a destructive
     * coalesce or hard delete. Must run inside the caller's transaction: any failure
     * throws so the destructive write rolls back with it. Insert-if-absent per
     * (portal user, profile, canonical identity, reason).
     */
    open fun retainRoutineGraphs(
        rows: List<RoutineRow>,
        canonicalIdentity: String,
        portalUserId: String,
        profileId: String,
        reason: String,
        source: String,
        incomingIdentity: String?,
        appliedAt: Long,
    ) {
        if (rows.isEmpty()) return
        val payload = RoutineRecoveryPayload(
            provenance = RoutineRecoveryProvenance(
                reason = reason,
                source = source,
                incomingIdentity = incomingIdentity,
                resolvedLocalIdentity = rows.firstOrNull()?.id,
                localUpdatedAt = rows.mapNotNull { it.updatedAt ?: it.createdAt }.maxOrNull(),
                appliedAt = appliedAt,
            ),
            routines = rows.map(::buildGraph),
        )
        queries.insertRoutineRecoveryIfAbsent(
            id = RoutineIdentity.canonicalize(generateUUID()),
            portalUserId = portalUserId,
            profileId = profileId,
            canonicalIdentity = canonicalIdentity,
            reason = reason,
            payloadJson = json.encodeToString(RoutineRecoveryPayload.serializer(), payload),
            createdAt = appliedAt,
            // 30-day retention window, fixed at first retention: a repeated delivery
            // must not refresh the expiry.
            expiresAt = appliedAt + RETENTION_WINDOW_MILLIS,
        )
    }

    private fun buildGraph(row: RoutineRow): RoutineRecoveryRoutineGraph {
        val exercises = queries.selectExercisesByRoutine(row.id).executeAsList()
        val supersets = queries.selectSupersetsByRoutine(row.id).executeAsList()
        val plannedSets = exercises.flatMap { exercise ->
            queries.selectPlannedSetsByRoutineExercise(exercise.id).executeAsList()
        }
        val cycleDayRefs = queries.selectCycleDayRefsByRoutine(row.id).executeAsList()
        return RoutineRecoveryRoutineGraph(
            routine = RoutineRecoveryRoutineSnapshot(
                id = row.id,
                name = row.name,
                description = row.description,
                createdAt = row.createdAt,
                lastUsed = row.lastUsed,
                useCount = row.useCount,
                updatedAt = row.updatedAt,
                serverId = row.serverId,
                deletedAt = row.deletedAt,
                profileId = row.profile_id,
                groupId = row.groupId,
            ),
            exercises = exercises.map { exercise ->
                RoutineRecoveryExerciseSnapshot(
                    id = exercise.id,
                    routineId = exercise.routineId,
                    exerciseName = exercise.exerciseName,
                    exerciseMuscleGroup = exercise.exerciseMuscleGroup,
                    exerciseEquipment = exercise.exerciseEquipment,
                    exerciseDefaultCableConfig = exercise.exerciseDefaultCableConfig,
                    exerciseId = exercise.exerciseId,
                    cableConfig = exercise.cableConfig,
                    orderIndex = exercise.orderIndex,
                    setReps = exercise.setReps,
                    weightPerCableKg = exercise.weightPerCableKg,
                    setWeights = exercise.setWeights,
                    mode = exercise.mode,
                    eccentricLoad = exercise.eccentricLoad,
                    echoLevel = exercise.echoLevel,
                    progressionKg = exercise.progressionKg,
                    restSeconds = exercise.restSeconds,
                    duration = exercise.duration,
                    setRestSeconds = exercise.setRestSeconds,
                    perSetRestTime = exercise.perSetRestTime,
                    isAMRAP = exercise.isAMRAP,
                    supersetId = exercise.supersetId,
                    orderInSuperset = exercise.orderInSuperset,
                    usePercentOfPR = exercise.usePercentOfPR,
                    weightPercentOfPR = exercise.weightPercentOfPR,
                    prTypeForScaling = exercise.prTypeForScaling,
                    setWeightsPercentOfPR = exercise.setWeightsPercentOfPR,
                    scalingBasis = exercise.scalingBasis,
                    stallDetectionEnabled = exercise.stallDetectionEnabled,
                    stopAtTop = exercise.stopAtTop,
                    repCountTiming = exercise.repCountTiming,
                    setEchoLevels = exercise.setEchoLevels,
                    warmupSets = exercise.warmupSets,
                    defaultRackItemIds = exercise.defaultRackItemIds,
                    rackBehaviorOverrides = exercise.rackBehaviorOverrides,
                    isBodyweight = exercise.isBodyweight,
                    dropSetEnabled = exercise.dropSetEnabled,
                    dropSetMinWeightKg = exercise.dropSetMinWeightKg,
                    durationSyncKnown = exercise.durationSyncKnown,
                )
            },
            supersets = supersets.map { superset ->
                RoutineRecoverySupersetSnapshot(
                    id = superset.id,
                    routineId = superset.routineId,
                    name = superset.name,
                    colorIndex = superset.colorIndex,
                    restBetweenSeconds = superset.restBetweenSeconds,
                    orderIndex = superset.orderIndex,
                )
            },
            plannedSets = plannedSets.map { plannedSet ->
                RoutineRecoveryPlannedSetSnapshot(
                    id = plannedSet.id,
                    routineExerciseId = plannedSet.routine_exercise_id,
                    setNumber = plannedSet.set_number,
                    setType = plannedSet.set_type,
                    targetReps = plannedSet.target_reps,
                    targetWeightKg = plannedSet.target_weight_kg,
                    targetRpe = plannedSet.target_rpe,
                    restSeconds = plannedSet.rest_seconds,
                )
            },
            cycleDayRefs = cycleDayRefs.map { ref ->
                RoutineRecoveryCycleDayRefSnapshot(
                    id = ref.id,
                    cycleId = ref.cycle_id,
                    dayNumber = ref.day_number,
                )
            },
        )
    }

    /**
     * Recoverable routines of one signed-in portal user and profile. Only graphs
     * whose original routine no longer exists live are shown (a coalesce keeper
     * that still exists stays in the ordinary routine list), `cycle_routine_*`
     * template deletions are never surfaced as user routines, and expired
     * snapshots are excluded.
     */
    fun listRecoverableRoutines(profileId: String, portalUserId: String, now: Long): List<RoutineRecoveryItem> {
        val items = mutableListOf<RoutineRecoveryItem>()
        for (row in queries.selectRoutineRecoveriesByProfile(
            profileId = profileId,
            portalUserId = portalUserId,
            now = now,
        ).executeAsList()) {
            val payload = try {
                json.decodeFromString(RoutineRecoveryPayload.serializer(), row.payload_json)
            } catch (error: Exception) {
                continue
            }
            payload.routines.forEachIndexed { index, graph ->
                val original = graph.routine
                if (original.id.startsWith(CYCLE_TEMPLATE_ROUTINE_PREFIX)) return@forEachIndexed
                val live = queries.selectRoutineById(original.id).executeAsOneOrNull()
                if (live != null && live.deletedAt == null) return@forEachIndexed
                items += RoutineRecoveryItem(
                    recoveryId = row.id,
                    graphIndex = index,
                    reason = payload.provenance.reason,
                    routineName = original.name,
                    exercises = graph.exercises.map { exercise ->
                        RoutineRecoveryExercisePreview(
                            exerciseName = exercise.exerciseName,
                            setReps = exercise.setReps,
                            plannedSets = graph.plannedSets
                                .filter { it.routineExerciseId == exercise.id }
                                .map {
                                    RoutineRecoverySetPreview(
                                        setNumber = it.setNumber,
                                        setType = it.setType,
                                        targetReps = it.targetReps,
                                        targetWeightKg = it.targetWeightKg,
                                    )
                                },
                        )
                    },
                )
            }
        }
        return items
    }

    /**
     * Explicit restore-as-copy: write the retained graph back as NEW rows with
     * fresh routine and child UUIDs in [profileId]. The server-deleted identity is
     * never reused, cycle days and completed history are never reconnected, and
     * the snapshot itself is kept (restore is never automatic and does not consume
     * the retained evidence). Returns the new routine id.
     */
    fun restoreAsCopy(
        recoveryId: String,
        graphIndex: Int,
        profileId: String,
        portalUserId: String,
        now: Long,
    ): String? {
        val row = queries.selectRoutineRecoveryById(recoveryId).executeAsOneOrNull() ?: return null
        if (row.profile_id != profileId || row.portal_user_id != portalUserId) return null
        // Retention-window enforcement at execution time (merge gate R3): the SQL
        // listing filters by `expires_at >= :now`, and a sheet may have cached its
        // selection past that point. A snapshot is restorable exactly while it is
        // listable, and never after — before any insert.
        if (row.expires_at < now) return null
        val payload = try {
            json.decodeFromString(RoutineRecoveryPayload.serializer(), row.payload_json)
        } catch (error: Exception) {
            return null
        }
        val graph = payload.routines.getOrNull(graphIndex) ?: return null
        if (graph.routine.id.startsWith(CYCLE_TEMPLATE_ROUTINE_PREFIX)) return null

        val source = graph.routine
        val newRoutineId = RoutineIdentity.canonicalize(generateUUID())
        val restoredGroupId = source.groupId?.takeIf { groupId ->
            queries.selectRoutineGroupById(groupId).executeAsOneOrNull()?.profile_id == profileId
        }
        queries.insertRoutine(
            id = newRoutineId,
            name = "${source.name} (Restored)",
            description = source.description,
            createdAt = now,
            lastUsed = null,
            useCount = 0L,
            profile_id = profileId,
            groupId = restoredGroupId,
            deletedAt = null,
        )

        val supersetIds = graph.supersets.associate { it.id to RoutineIdentity.canonicalize(generateUUID()) }
        for (superset in graph.supersets) {
            queries.insertSuperset(
                id = supersetIds.getValue(superset.id),
                routineId = newRoutineId,
                name = superset.name,
                colorIndex = superset.colorIndex,
                restBetweenSeconds = superset.restBetweenSeconds,
                orderIndex = superset.orderIndex,
            )
        }

        val exerciseIds = mutableMapOf<String, String>()
        for (exercise in graph.exercises) {
            val newExerciseId = RoutineIdentity.canonicalize(generateUUID())
            exerciseIds[exercise.id] = newExerciseId
            // Catalogue exercise ids are not routine identity: they are reused
            // unchanged while the row still exists, never rewritten.
            val catalogueExerciseId = exercise.exerciseId?.takeIf {
                queries.selectExerciseById(it).executeAsOneOrNull() != null
            }
            queries.insertRoutineExercise(
                id = newExerciseId,
                routineId = newRoutineId,
                exerciseName = exercise.exerciseName,
                exerciseMuscleGroup = exercise.exerciseMuscleGroup,
                exerciseEquipment = exercise.exerciseEquipment,
                exerciseDefaultCableConfig = exercise.exerciseDefaultCableConfig,
                exerciseId = catalogueExerciseId,
                cableConfig = exercise.cableConfig,
                orderIndex = exercise.orderIndex,
                setReps = exercise.setReps,
                weightPerCableKg = exercise.weightPerCableKg,
                setWeights = exercise.setWeights,
                mode = exercise.mode,
                eccentricLoad = exercise.eccentricLoad,
                echoLevel = exercise.echoLevel,
                progressionKg = exercise.progressionKg,
                restSeconds = exercise.restSeconds,
                duration = exercise.duration,
                setRestSeconds = exercise.setRestSeconds,
                perSetRestTime = exercise.perSetRestTime,
                isAMRAP = exercise.isAMRAP,
                supersetId = exercise.supersetId?.let { supersetIds[it] },
                orderInSuperset = exercise.orderInSuperset,
                usePercentOfPR = exercise.usePercentOfPR,
                weightPercentOfPR = exercise.weightPercentOfPR,
                prTypeForScaling = exercise.prTypeForScaling,
                setWeightsPercentOfPR = exercise.setWeightsPercentOfPR,
                stallDetectionEnabled = exercise.stallDetectionEnabled,
                stopAtTop = exercise.stopAtTop,
                repCountTiming = exercise.repCountTiming,
                setEchoLevels = exercise.setEchoLevels,
                warmupSets = exercise.warmupSets,
                defaultRackItemIds = exercise.defaultRackItemIds,
                rackBehaviorOverrides = exercise.rackBehaviorOverrides,
                scalingBasis = exercise.scalingBasis,
                isBodyweight = exercise.isBodyweight,
                dropSetEnabled = exercise.dropSetEnabled,
                dropSetMinWeightKg = exercise.dropSetMinWeightKg,
            )
            // insertRoutineExercise leaves durationSyncKnown at its default 0
            // (unknown), which re-selects the routine on every push and forces a
            // full pull until hydrated. The copy is a new identity created by this
            // build, so its duration is known (same rule as writeRoutineExerciseRow);
            // the snapshot's flag described the deleted identity, not this one.
            queries.updateRoutineExerciseDurationSyncKnown(durationSyncKnown = 1L, id = newExerciseId)
        }

        for (plannedSet in graph.plannedSets) {
            val parentExerciseId = exerciseIds[plannedSet.routineExerciseId] ?: continue
            queries.insertPlannedSet(
                id = RoutineIdentity.canonicalize(generateUUID()),
                routine_exercise_id = parentExerciseId,
                set_number = plannedSet.setNumber,
                set_type = plannedSet.setType,
                target_reps = plannedSet.targetReps,
                target_weight_kg = plannedSet.targetWeightKg,
                target_rpe = plannedSet.targetRpe,
                rest_seconds = plannedSet.restSeconds,
            )
        }

        // CycleDay references and completed history are deliberately NOT reconnected:
        // restore is a copy of the programming, never a resurrection of the deleted
        // identity's links.
        return newRoutineId
    }

    /** Drop snapshots past their 30-day retention window. */
    fun pruneExpired(now: Long) {
        queries.pruneExpiredRoutineRecoveries(now)
    }

    private companion object {
        const val RETENTION_WINDOW_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
