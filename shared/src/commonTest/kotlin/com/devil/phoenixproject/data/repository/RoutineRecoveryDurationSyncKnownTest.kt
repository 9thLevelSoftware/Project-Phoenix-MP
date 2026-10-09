package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Restored routine copies must keep the snapshot's duration sync flag.
 * `insertRoutineExercise` omits `durationSyncKnown`, so a restore that only
 * inserts leaves the column at its default of 0 (unknown) even when the
 * retained graph recorded the authoritative state (1, wire `true`).
 */
class RoutineRecoveryDurationSyncKnownTest {

    @Test
    fun `restored routine copy keeps durationSyncKnown`() {
        val db = createTestDatabase()
        val queries = db.phoenixDatabaseQueries
        val profile = "active-profile"
        val routineId = "abcdefab-1234-4abc-8def-abcdef123456"
        val exerciseId = "duration-known-exercise"

        queries.insertRoutine(
            id = routineId,
            name = "Push Day",
            description = "",
            createdAt = 1L,
            lastUsed = null,
            useCount = 0L,
            profile_id = profile,
            groupId = null,
            deletedAt = null,
        )
        queries.insertRoutineExercise(
            id = exerciseId,
            routineId = routineId,
            exerciseName = "Bench Press",
            exerciseMuscleGroup = "Chest",
            exerciseEquipment = "Cable",
            exerciseDefaultCableConfig = "DOUBLE",
            exerciseId = null,
            cableConfig = "DOUBLE",
            orderIndex = 0L,
            setReps = "10",
            weightPerCableKg = 40.0,
            setWeights = "",
            mode = "OldSchool",
            eccentricLoad = 100L,
            echoLevel = 1L,
            progressionKg = 0.0,
            restSeconds = 60L,
            duration = 45L,
            setRestSeconds = "[]",
            perSetRestTime = 0L,
            isAMRAP = 0L,
            supersetId = null,
            orderInSuperset = 0L,
            usePercentOfPR = 0L,
            weightPercentOfPR = 80L,
            prTypeForScaling = "MAX_WEIGHT",
            setWeightsPercentOfPR = null,
            stallDetectionEnabled = 1L,
            stopAtTop = 0L,
            repCountTiming = "TOP",
            setEchoLevels = "",
            warmupSets = "",
            defaultRackItemIds = "[]",
            rackBehaviorOverrides = "{}",
            scalingBasis = null,
            isBodyweight = null,
            dropSetEnabled = 0L,
            dropSetMinWeightKg = null,
        )
        // 1 is the authoritative known state (Boolean true on the sync wire).
        queries.updateRoutineExerciseDurationSyncKnown(durationSyncKnown = 1L, id = exerciseId)
        assertEquals(1L, queries.selectRoutineExerciseById(exerciseId).executeAsOne().durationSyncKnown)

        val store = RoutineRecoveryStore(queries)
        db.transaction {
            store.retainRoutineGraphs(
                rows = queries.selectAllRoutinesByProfileIncludingDeleted(profile).executeAsList(),
                canonicalIdentity = routineId,
                portalUserId = "owner-user",
                profileId = profile,
                reason = RoutineRecoveryReasons.SERVER_DELETE,
                source = "pull",
                incomingIdentity = routineId,
                appliedAt = 1_000L,
            )
            queries.deleteRoutineById(routineId)
        }

        val retained = queries.selectRoutineRecoveriesByProfile(
            profileId = profile,
            portalUserId = "owner-user",
            now = 1_000L,
        ).executeAsList().single()
        val restoredId = db.transactionWithResult {
            store.restoreAsCopy(
                recoveryId = retained.id,
                graphIndex = 0,
                profileId = profile,
                portalUserId = "owner-user",
                now = 1_000L,
            )
        }

        assertNotNull(restoredId)
        val restored = queries.selectExercisesByRoutine(restoredId).executeAsList().single()
        assertEquals(1L, restored.durationSyncKnown)
        assertEquals(45L, restored.duration)
    }
}
