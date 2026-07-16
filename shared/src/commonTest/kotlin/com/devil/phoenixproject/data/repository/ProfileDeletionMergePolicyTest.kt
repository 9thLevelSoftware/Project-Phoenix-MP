package com.devil.phoenixproject.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileDeletionMergePolicyTest {
    @Test
    fun `max weight compares weight then one rep max then achieved time`() {
        val target = personalRecord(
            profileId = TARGET,
            weight = 100.0,
            oneRepMax = 130.0,
            achievedAt = 300,
            uuid = "target-uuid",
        )

        assertEquals(101.0, merge(target, personalRecord(SOURCE, weight = 101.0)).weight)
        assertEquals(
            131.0,
            merge(target, personalRecord(SOURCE, weight = 100.0, oneRepMax = 131.0)).oneRepMax,
        )
        assertEquals(
            301,
            merge(
                target,
                personalRecord(SOURCE, weight = 100.0, oneRepMax = 130.0, achievedAt = 301),
            ).achievedAt,
        )
        assertEquals(
            TARGET_ID,
            merge(
                target,
                personalRecord(SOURCE, weight = 100.0, oneRepMax = 130.0, achievedAt = 300),
            ).id,
        )
    }

    @Test
    fun `max volume compares volume then weight then achieved time`() {
        val target = personalRecord(
            profileId = TARGET,
            prType = "MAX_VOLUME",
            volume = 1_000.0,
            weight = 100.0,
            achievedAt = 300,
        )

        assertEquals(
            1_001.0,
            merge(target, personalRecord(SOURCE, prType = "MAX_VOLUME", volume = 1_001.0)).volume,
        )
        assertEquals(
            101.0,
            merge(
                target,
                personalRecord(
                    SOURCE,
                    prType = "MAX_VOLUME",
                    volume = 1_000.0,
                    weight = 101.0,
                ),
            ).weight,
        )
        assertEquals(
            301,
            merge(
                target,
                personalRecord(
                    SOURCE,
                    prType = "MAX_VOLUME",
                    volume = 1_000.0,
                    weight = 100.0,
                    achievedAt = 301,
                ),
            ).achievedAt,
        )
    }

    @Test
    fun `live record beats tombstone and complete tie chooses target`() {
        val liveSource = personalRecord(SOURCE, weight = 1.0, deletedAt = null, serverId = "source")
        val deletedTarget = personalRecord(TARGET, weight = 500.0, deletedAt = 900, serverId = "target")
        val liveWinner = merge(deletedTarget, liveSource)

        assertNull(liveWinner.deletedAt)
        assertEquals("source", liveWinner.serverId)
        assertEquals(TARGET_ID, liveWinner.id)

        val completeTie = merge(
            personalRecord(TARGET, serverId = "target"),
            personalRecord(SOURCE, serverId = "source"),
        )
        assertEquals("target", completeTie.serverId)
    }

    @Test
    fun `winner fields keep target id with uuid and name fallback`() {
        val targetWins = merge(
            personalRecord(
                TARGET,
                exerciseName = "",
                weight = 200.0,
                updatedAt = 20,
                serverId = "target-server",
                uuid = null,
            ),
            personalRecord(
                SOURCE,
                exerciseName = "Bench Press",
                weight = 100.0,
                updatedAt = 10,
                serverId = "source-server",
                uuid = "source-uuid",
            ),
        )
        assertEquals(TARGET_ID, targetWins.id)
        assertEquals("Bench Press", targetWins.exerciseName)
        assertEquals("source-uuid", targetWins.uuid)
        assertEquals(20, targetWins.updatedAt)
        assertEquals("target-server", targetWins.serverId)

        val sourceWins = merge(
            personalRecord(TARGET, weight = 100.0, uuid = "target-uuid"),
            personalRecord(
                SOURCE,
                weight = 200.0,
                updatedAt = 30,
                serverId = "source-server",
                uuid = null,
            ),
        )
        assertEquals(TARGET_ID, sourceWins.id)
        assertEquals(TARGET, sourceWins.profileId)
        assertEquals("target-uuid", sourceWins.uuid)
        assertEquals(30, sourceWins.updatedAt)
        assertEquals("source-server", sourceWins.serverId)
        assertEquals("Old School", sourceWins.workoutMode)
    }

    @Test
    fun `source-only personal record keeps lowest source id`() {
        val retained = ProfileDeletionMergePolicy.mergePersonalRecordGroup(
            records = listOf(
                personalRecord(SOURCE, id = 8, weight = 200.0),
                personalRecord(SOURCE, id = 7, weight = 100.0),
            ),
            targetProfileId = TARGET,
        )

        assertEquals(7, retained.id)
        assertEquals(TARGET, retained.profileId)
        assertEquals(200.0, retained.weight)
    }

    @Test
    fun `normalized alias groups are deterministic regardless of query order`() {
        val rows = listOf(
            personalRecord(TARGET, id = 12, serverId = "target-high", uuid = "target-high-uuid"),
            personalRecord(TARGET, id = 10, serverId = "target-low", uuid = null),
            personalRecord(SOURCE, id = 20, serverId = "source", uuid = "source-uuid"),
        )
        val forward = ProfileDeletionMergePolicy.mergePersonalRecordGroup(rows, TARGET)
        val reversed = ProfileDeletionMergePolicy.mergePersonalRecordGroup(rows.reversed(), TARGET)

        assertEquals(forward, reversed)
        assertEquals(10, forward.id)
        assertEquals("target-low", forward.serverId)
        assertEquals("target-high-uuid", forward.uuid)

        val badges = listOf(
            badge(TARGET, earnedAt = 100, updatedAt = 12, serverId = "target-high").copy(id = 12),
            badge(TARGET, earnedAt = 100, updatedAt = 10, serverId = "target-low").copy(id = 10),
            badge(SOURCE, earnedAt = 100, updatedAt = 20, serverId = "source").copy(id = 20),
        )
        assertEquals(
            ProfileDeletionMergePolicy.mergeEarnedBadgeGroup(badges, TARGET),
            ProfileDeletionMergePolicy.mergeEarnedBadgeGroup(badges.reversed(), TARGET),
        )

        val mvts = listOf(
            mvt(TARGET, personalMvtMs = 200.0, sampleCount = 0, updatedAt = 20),
            mvt(TARGET, personalMvtMs = 300.0, sampleCount = 0, updatedAt = 20),
            mvt(SOURCE, personalMvtMs = 400.0, sampleCount = 0, updatedAt = 20),
        )
        assertEquals(
            ProfileDeletionMergePolicy.mergeExerciseMvtGroup(mvts, TARGET),
            ProfileDeletionMergePolicy.mergeExerciseMvtGroup(mvts.reversed(), TARGET),
        )
    }

    @Test
    fun `badge retains target id and combines earliest times with live metadata donor`() {
        val merged = ProfileDeletionMergePolicy.mergeEarnedBadgeGroup(
            badges = listOf(
                badge(
                    TARGET,
                    earnedAt = 200,
                    celebratedAt = 250,
                    updatedAt = 20,
                    serverId = "target",
                    deletedAt = null,
                ),
                badge(
                    SOURCE,
                    earnedAt = 100,
                    celebratedAt = 300,
                    updatedAt = 10,
                    serverId = "source",
                    deletedAt = 400,
                ),
            ),
            targetProfileId = TARGET,
        )

        assertEquals(BADGE_TARGET_ID, merged.id)
        assertEquals(TARGET, merged.profileId)
        assertEquals(100, merged.earnedAt)
        assertEquals(250, merged.celebratedAt)
        assertEquals(20, merged.updatedAt)
        assertEquals("target", merged.serverId)
        assertNull(merged.deletedAt)
    }

    @Test
    fun `badge donor uses earlier earned then target on full tie`() {
        val earlierSource = ProfileDeletionMergePolicy.mergeEarnedBadgeGroup(
            listOf(
                badge(TARGET, earnedAt = 200, updatedAt = 20, serverId = "target"),
                badge(SOURCE, earnedAt = 100, updatedAt = 10, serverId = "source"),
            ),
            TARGET,
        )
        assertEquals("source", earlierSource.serverId)

        val tied = ProfileDeletionMergePolicy.mergeEarnedBadgeGroup(
            listOf(
                badge(TARGET, earnedAt = 100, updatedAt = 20, serverId = "target"),
                badge(SOURCE, earnedAt = 100, updatedAt = 10, serverId = "source"),
            ),
            TARGET,
        )
        assertEquals("target", tied.serverId)
    }

    @Test
    fun `mvt is count weighted and handles zero counts deterministically`() {
        val weighted = ProfileDeletionMergePolicy.mergeExerciseMvtGroup(
            listOf(
                mvt(TARGET, personalMvtMs = 200.0, sampleCount = 2, updatedAt = 20),
                mvt(SOURCE, personalMvtMs = 400.0, sampleCount = 6, updatedAt = 10),
            ),
            TARGET,
        )
        assertEquals(350.0, weighted.personalMvtMs)
        assertEquals(8, weighted.sampleCount)
        assertEquals(20, weighted.updatedAt)

        val sourceNewer = ProfileDeletionMergePolicy.mergeExerciseMvtGroup(
            listOf(
                mvt(TARGET, personalMvtMs = 200.0, sampleCount = -3, updatedAt = 20),
                mvt(SOURCE, personalMvtMs = 400.0, sampleCount = 0, updatedAt = 21),
            ),
            TARGET,
        )
        assertEquals(400.0, sourceNewer.personalMvtMs)
        assertEquals(0, sourceNewer.sampleCount)

        val targetTie = ProfileDeletionMergePolicy.mergeExerciseMvtGroup(
            listOf(
                mvt(TARGET, personalMvtMs = 200.0, sampleCount = 0, updatedAt = 20),
                mvt(SOURCE, personalMvtMs = 400.0, sampleCount = 0, updatedAt = 20),
            ),
            TARGET,
        )
        assertEquals(200.0, targetTie.personalMvtMs)
    }

    private fun merge(
        target: ProfileMergePersonalRecord,
        source: ProfileMergePersonalRecord,
    ): ProfileMergePersonalRecord = ProfileDeletionMergePolicy.mergePersonalRecordGroup(
        listOf(target, source),
        TARGET,
    )

    private fun personalRecord(
        profileId: String,
        id: Long = if (profileId == TARGET) TARGET_ID else SOURCE_ID,
        exerciseName: String = "Bench",
        weight: Double = 100.0,
        reps: Long = 5,
        oneRepMax: Double = 120.0,
        achievedAt: Long = 300,
        prType: String = "MAX_WEIGHT",
        volume: Double = 500.0,
        updatedAt: Long? = 10,
        serverId: String? = null,
        deletedAt: Long? = null,
        uuid: String? = null,
    ) = ProfileMergePersonalRecord(
        id = id,
        profileId = profileId,
        exerciseId = "bench",
        exerciseName = exerciseName,
        weight = weight,
        reps = reps,
        oneRepMax = oneRepMax,
        achievedAt = achievedAt,
        workoutMode = "OldSchool",
        prType = prType,
        volume = volume,
        phase = "COMBINED",
        updatedAt = updatedAt,
        serverId = serverId,
        deletedAt = deletedAt,
        cableCount = 2,
        uuid = uuid,
    )

    private fun badge(
        profileId: String,
        earnedAt: Long,
        celebratedAt: Long? = null,
        updatedAt: Long? = null,
        serverId: String? = null,
        deletedAt: Long? = null,
    ) = ProfileMergeEarnedBadge(
        id = if (profileId == TARGET) BADGE_TARGET_ID else BADGE_SOURCE_ID,
        profileId = profileId,
        badgeId = "first-workout",
        earnedAt = earnedAt,
        celebratedAt = celebratedAt,
        updatedAt = updatedAt,
        serverId = serverId,
        deletedAt = deletedAt,
    )

    private fun mvt(
        profileId: String,
        personalMvtMs: Double,
        sampleCount: Long,
        updatedAt: Long,
    ) = ProfileMergeExerciseMvt(
        exerciseId = "bench",
        profileId = profileId,
        personalMvtMs = personalMvtMs,
        sampleCount = sampleCount,
        updatedAt = updatedAt,
    )

    private companion object {
        const val TARGET = "target"
        const val SOURCE = "source"
        const val TARGET_ID = 10L
        const val SOURCE_ID = 20L
        const val BADGE_TARGET_ID = 30L
        const val BADGE_SOURCE_ID = 40L
    }
}
