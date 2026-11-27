@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package com.devil.phoenixproject.data.maintenance

import com.devil.phoenixproject.domain.model.SampleStatus
import com.devil.phoenixproject.domain.model.WorkoutMetric
import com.devil.phoenixproject.presentation.manager.CableTelemetryArchivalRetention
import com.devil.phoenixproject.presentation.manager.RetentionDecision
import com.devil.phoenixproject.presentation.manager.RetentionKeepReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * T1.2–T1.6 (issue #1425): gate exactness, boundary-tie/range-delete equivalence (signoff #3),
 * id-order guard (I2), safety retention, and NULL_POSITION preservation (I1).
 */
class RetroactiveTelemetryCompactionPlannerTest {

    @Test
    fun `each gate reports its exact preserve reason`() {
        // G1
        assertEquals(
            SetCompactionPlan.Preserve(PreserveReason.CORRUPT_OR_UNREPAIRED_START),
            RetroactiveTelemetryCompactionPlanner.plan(
                candidate(timestamp = 0L), trimmableTrace(), 2L, FIRST_REP_START_MS, epochRepairApplied = true,
            ),
            "an epoch-zero start keeps the set even with the repair applied",
        )
        assertEquals(
            SetCompactionPlan.Preserve(PreserveReason.CORRUPT_OR_UNREPAIRED_START),
            RetroactiveTelemetryCompactionPlanner.plan(
                candidate(), trimmableTrace(), 2L, FIRST_REP_START_MS, epochRepairApplied = false,
            ),
            "the epoch repair must be recorded before historical trims",
        )
        // G2
        assertEquals(
            SetCompactionPlan.Preserve(PreserveReason.NOT_REP_TARGETED),
            RetroactiveTelemetryCompactionPlanner.plan(
                candidate(targetReps = 0L), trimmableTrace(), 2L, FIRST_REP_START_MS, epochRepairApplied = true,
            ),
            "timed and AMRAP sets keep all samples",
        )
        // G3
        assertEquals(
            SetCompactionPlan.Preserve(PreserveReason.EXERCISE_UNKNOWN_OR_BODYWEIGHT),
            RetroactiveTelemetryCompactionPlanner.plan(
                candidate(exerciseId = null), trimmableTrace(), 2L, FIRST_REP_START_MS, epochRepairApplied = true,
            ),
        )
        assertEquals(
            SetCompactionPlan.Preserve(PreserveReason.EXERCISE_UNKNOWN_OR_BODYWEIGHT),
            RetroactiveTelemetryCompactionPlanner.plan(
                candidate(exerciseIsBodyweight = null), trimmableTrace(), 2L, FIRST_REP_START_MS, epochRepairApplied = true,
            ),
            "an unjoined exercise is unknown and stays preserved",
        )
        assertEquals(
            SetCompactionPlan.Preserve(PreserveReason.EXERCISE_UNKNOWN_OR_BODYWEIGHT),
            RetroactiveTelemetryCompactionPlanner.plan(
                candidate(exerciseIsBodyweight = 1L), trimmableTrace(), 2L, FIRST_REP_START_MS, epochRepairApplied = true,
            ),
        )
        // G4
        assertEquals(
            SetCompactionPlan.Preserve(PreserveReason.LEGACY_UNIT_SCALE),
            RetroactiveTelemetryCompactionPlanner.plan(
                candidate(), trimmableTrace(), 1L, FIRST_REP_START_MS, epochRepairApplied = true,
            ),
        )
        // G5: missing rep evidence and zero/early rep starts keep the set (signoff #4)
        assertEquals(
            SetCompactionPlan.Preserve(PreserveReason.NO_REP_EVIDENCE),
            RetroactiveTelemetryCompactionPlanner.plan(
                candidate(), trimmableTrace(), 2L, null, epochRepairApplied = true,
            ),
        )
        assertEquals(
            SetCompactionPlan.Preserve(PreserveReason.BOUNDARY_INSIDE_REP),
            RetroactiveTelemetryCompactionPlanner.plan(
                candidate(), trimmableTrace(), 2L, 0L, epochRepairApplied = true,
            ),
            "a zero MIN(startTimestamp) keeps the set instead of being filtered out",
        )
        assertEquals(
            SetCompactionPlan.Preserve(PreserveReason.BOUNDARY_INSIDE_REP),
            RetroactiveTelemetryCompactionPlanner.plan(
                candidate(), trimmableTrace(), 2L, 250L, epochRepairApplied = true,
            ),
            "a boundary after the first rep start is never trimmed",
        )
    }

    @Test
    fun `null position anywhere in the set preserves the whole set`() {
        assertEquals(
            SetCompactionPlan.Preserve(PreserveReason.NULL_POSITION),
            RetroactiveTelemetryCompactionPlanner.plan(
                candidate(), null, 2L, FIRST_REP_START_MS, epochRepairApplied = true,
            ),
            "the mapper reports null for any NULL position and the set is kept",
        )
    }

    @Test
    fun `trim boundary ties are retained and the range delete equals the policy discard set`() {
        val samples = trimmableTrace().toMutableList().also {
            it[5] = it[5].copy(timestamp = it[4].timestamp)
        }
        val plan = assertIs<SetCompactionPlan.Trim>(
            RetroactiveTelemetryCompactionPlanner.plan(
                candidate(), samples, 2L, FIRST_REP_START_MS, epochRepairApplied = true,
            ),
        )

        // Policy discard set: the leading prefix below the retained boundary, minus safety rows.
        val decision = assertIs<RetentionDecision.Trim>(
            CableTelemetryArchivalRetention.planRetention(samples, romEnabled = true),
        )
        val policyDiscard = samples.subList(0, decision.firstRetainedIndex).filter {
            (it.status and SampleStatus.SAFETY_STATUS_MASK) == 0
        }

        // In-memory range-delete predicate: timestamp < boundary AND not safety-flagged.
        val rangeDeleteDiscard = samples.filter {
            it.timestamp < plan.boundaryTs && (it.status and SampleStatus.SAFETY_STATUS_MASK) == 0
        }
        assertEquals(policyDiscard, rangeDeleteDiscard, "the range delete is exact for id-ordered rows")
        assertEquals(plan.discardCount, rangeDeleteDiscard.size)

        // Every sample sharing the boundary timestamp is retained.
        samples.filter { it.timestamp == plan.boundaryTs }.forEach { tied ->
            assert(rangeDeleteDiscard.none { it === tied }) { "boundary-tie sample must survive" }
        }
    }

    @Test
    fun `out of order timestamps in id order are preserved and would trim when sorted`() {
        val outOfOrder = trimmableTrace().toMutableList().also {
            it[5] = it[5].copy(timestamp = it[4].timestamp - 1L)
        }
        assertEquals(
            SetCompactionPlan.Preserve(PreserveReason.policyKeep(RetentionKeepReason.INVALID_ORDER_OR_POSITION)),
            RetroactiveTelemetryCompactionPlanner.plan(
                candidate(), outOfOrder, 2L, FIRST_REP_START_MS, epochRepairApplied = true,
            ),
            "the id-ordered non-monotonic guard must fire on history (I2)",
        )
        val sorted = outOfOrder.sortedBy { it.timestamp }
        assertIs<SetCompactionPlan.Trim>(
            RetroactiveTelemetryCompactionPlanner.plan(
                candidate(), sorted, 2L, FIRST_REP_START_MS, epochRepairApplied = true,
            ),
            "timestamp sorting would hide arrival order and wrongly trim",
        )
    }

    @Test
    fun `safety flagged prefix samples are retained and counted`() {
        val samples = trimmableTrace().toMutableList().also {
            it[0] = it[0].copy(status = SampleStatus.ROM_OUTSIDE_HIGH)
            it[2] = it[2].copy(status = SampleStatus.DELOAD_WARN)
        }
        val plan = assertIs<SetCompactionPlan.Trim>(
            RetroactiveTelemetryCompactionPlanner.plan(
                candidate(), samples, 2L, FIRST_REP_START_MS, epochRepairApplied = true,
            ),
        )
        assertEquals(2, plan.retainedSafetyCount)
        assertEquals(samples.size - plan.discardCount, plan.retainedCount)

        // The two safety rows survive the range delete.
        val survivors = samples.filter {
            !(it.timestamp < plan.boundaryTs && (it.status and SampleStatus.SAFETY_STATUS_MASK) == 0)
        }
        assert(survivors.containsAll(listOf(samples[0], samples[2]))) { "safety rows must survive" }
    }

    @Test
    fun `a trim plan preserves at least one sample per session`() {
        val plan = assertIs<SetCompactionPlan.Trim>(
            RetroactiveTelemetryCompactionPlanner.plan(
                candidate(), trimmableTrace(), 2L, FIRST_REP_START_MS, epochRepairApplied = true,
            ),
        )
        assert(plan.retainedCount >= 1) { "G7 keeps at least one sample per touched session" }
        assert(plan.discardCount >= 1) { "a plan without discards is preserved as NOTHING_TO_TRIM" }
    }

    private fun candidate(
        timestamp: Long = 1_000L,
        targetReps: Long = 10L,
        exerciseId: String? = "exercise-1",
        exerciseIsBodyweight: Long? = 0L,
    ): CompactionCandidate = CompactionCandidate(
        sessionId = "session-1",
        timestamp = timestamp,
        targetReps = targetReps,
        exerciseId = exerciseId,
        exerciseIsBodyweight = exerciseIsBodyweight,
    )

    /** 13 samples at 250 ms: idle lead-in then a confirmed stroke; trims 4 samples at write time. */
    private fun trimmableTrace(): List<WorkoutMetric> {
        val displacement = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1.5f, 3f, 5.5f, 8f, 11f)
        return displacement.indices.map { index ->
            WorkoutMetric(
                timestamp = index * 250L,
                loadA = 20f,
                loadB = 18f,
                positionA = 100f + displacement[index],
                positionB = 200f,
            )
        }
    }

    private companion object {
        const val FIRST_REP_START_MS = 10_000L
    }
}
