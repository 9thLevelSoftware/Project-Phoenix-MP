package com.devil.phoenixproject.data.sync

import com.devil.phoenixproject.domain.model.SessionTiming
import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Defence in depth for the "1970" bug: rows already saved with `timestamp = 0` and
 * `duration = now - 0` must never reach the portal as `startedAt = 1970-01-01` with an
 * epoch-sized `durationSeconds`, and building the payload must not throw.
 */
class PortalSyncAdapterSessionTimingTest {
    private val now = 1_790_000_000_000L

    private fun session(
        id: String,
        timestamp: Long,
        durationMs: Long,
        updatedAt: Long? = null,
        routineSessionId: String? = null,
    ) = WorkoutSession(
        id = id,
        timestamp = timestamp,
        mode = "Old School",
        reps = 10,
        weightPerCableKg = 20f,
        duration = durationMs,
        totalReps = 10,
        workingReps = 10,
        exerciseName = "Row",
        routineSessionId = routineSessionId,
        updatedAt = updatedAt,
    )

    @Test
    fun `a healthy row is sent unchanged`() {
        val timing = PortalSyncAdapter.wireSessionTiming(listOf(session("a", now - 120_000, 90_000)), now)

        assertEquals(PortalSyncAdapter.WireSessionTiming(startedAtMs = now - 120_000, durationSeconds = 90), timing)
    }

    @Test
    fun `a 1970 row with an epoch duration falls back to updatedAt and a zero duration`() {
        val bad = session("a", timestamp = 0L, durationMs = now - 5_000, updatedAt = now - 5_000)

        val timing = PortalSyncAdapter.wireSessionTiming(listOf(bad), now)

        assertEquals(PortalSyncAdapter.WireSessionTiming(startedAtMs = now - 5_000, durationSeconds = 0), timing)
    }

    @Test
    fun `a 1970 row without updatedAt starts now rather than in 1970`() {
        val timing = PortalSyncAdapter.wireSessionTiming(listOf(session("a", 0L, now)), now)

        assertEquals(PortalSyncAdapter.WireSessionTiming(startedAtMs = now, durationSeconds = 0), timing)
    }

    @Test
    fun `a bad row in a routine group does not move the start or inflate the duration`() {
        val rows = listOf(
            session("a", timestamp = now - 600_000, durationMs = 60_000, routineSessionId = "g"),
            session("b", timestamp = 0L, durationMs = now, routineSessionId = "g"),
            session("c", timestamp = now - 300_000, durationMs = 30_000, routineSessionId = "g"),
        )

        val timing = PortalSyncAdapter.wireSessionTiming(rows, now)

        assertEquals(PortalSyncAdapter.WireSessionTiming(startedAtMs = now - 600_000, durationSeconds = 90), timing)
    }

    @Test
    fun `negative and epoch sized durations count as zero`() {
        val rows = listOf(
            session("a", timestamp = now - 600_000, durationMs = -5_000, routineSessionId = "g"),
            session("b", timestamp = now - 500_000, durationMs = SessionTiming.MIN_EPOCH_SIZED_DURATION_MS, routineSessionId = "g"),
        )

        assertEquals(0, PortalSyncAdapter.wireSessionTiming(rows, now).durationSeconds)
    }

    @Test
    fun `a pre 2015 imported workout keeps its start and duration`() {
        val start2012 = 1_336_000_000_000L // 2012-05-02, e.g. from a CSV import
        val imported = session("csv", timestamp = start2012, durationMs = 3_600_000)

        val timing = PortalSyncAdapter.wireSessionTiming(listOf(imported), now)
        val dto = PortalSyncAdapter.toPortalWorkoutSessions(
            listOf(PortalSyncAdapter.SessionWithReps(session = imported)),
            "user-1",
        ).single()

        assertEquals(PortalSyncAdapter.WireSessionTiming(startedAtMs = start2012, durationSeconds = 3_600), timing)
        assertTrue(dto.startedAt.startsWith("2012-05-02"), "startedAt was ${dto.startedAt}")
        assertEquals(3_600, dto.durationSeconds)
    }

    @Test
    fun `a future dated row in a routine group adds neither its start nor its duration`() {
        val rows = listOf(
            session("a", timestamp = now - 600_000, durationMs = 60_000, routineSessionId = "g"),
            session("future", timestamp = now + 3_600_000, durationMs = 120_000, routineSessionId = "g"),
        )

        val timing = PortalSyncAdapter.wireSessionTiming(rows, now)

        assertEquals(PortalSyncAdapter.WireSessionTiming(startedAtMs = now - 600_000, durationSeconds = 60), timing)
    }

    @Test
    fun `the duration never exceeds the time elapsed since the start`() {
        val rows = listOf(
            session("a", timestamp = now - 60_000, durationMs = 50_000, routineSessionId = "g"),
            session("b", timestamp = now - 30_000, durationMs = 50_000, routineSessionId = "g"),
        )

        assertEquals(60, PortalSyncAdapter.wireSessionTiming(rows, now).durationSeconds)
    }

    @Test
    fun `the pushed dto never carries a 1970 startedAt or an epoch duration`() {
        val bad = PortalSyncAdapter.SessionWithReps(
            session = session("bad", timestamp = 0L, durationMs = 1_758_000_000_000L, updatedAt = 1_758_000_000_000L),
        )

        val dto = PortalSyncAdapter.toPortalWorkoutSessions(listOf(bad), "user-1").single()

        assertTrue(dto.startedAt.startsWith("2025-"), "startedAt was ${dto.startedAt}")
        assertEquals(0, dto.durationSeconds)
    }
}
