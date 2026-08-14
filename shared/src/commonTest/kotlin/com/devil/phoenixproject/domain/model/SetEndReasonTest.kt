package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SetEndReasonTest {

    @Test
    fun `SetEndReason declares exactly the seven persisted values`() {
        // Catches a durable-schema mutation that adds, removes, renames, or reorders a persisted reason.
        assertEquals(
            listOf(
                "TARGET_REPS_REACHED",
                "STALL_FAILURE",
                "VBT_AUTO_END",
                "USER_STOPPED",
                "CABLE_RELEASED",
                "TIMER_EXPIRED",
                "UNKNOWN",
            ),
            SetEndReason.entries.map { it.name },
        )
    }

    @Test
    fun `fromPersisted round-trips each canonical persisted reason`() {
        // Catches a codec mutation that rejects or maps any canonical persisted name to another reason.
        val canonicalReasons = listOf(
            "TARGET_REPS_REACHED" to SetEndReason.TARGET_REPS_REACHED,
            "STALL_FAILURE" to SetEndReason.STALL_FAILURE,
            "VBT_AUTO_END" to SetEndReason.VBT_AUTO_END,
            "USER_STOPPED" to SetEndReason.USER_STOPPED,
            "CABLE_RELEASED" to SetEndReason.CABLE_RELEASED,
            "TIMER_EXPIRED" to SetEndReason.TIMER_EXPIRED,
            "UNKNOWN" to SetEndReason.UNKNOWN,
        )

        canonicalReasons.forEach { (persisted, reason) ->
            assertEquals(reason, SetEndReason.fromPersisted(persisted), "value=$persisted")
        }
    }

    @Test
    fun `fromPersisted maps non-canonical persisted values to UNKNOWN`() {
        // Catches a codec mutation that accepts absent, non-canonical, corrupt, or future persisted values.
        val invalidValues = listOf<String?>(null, "", "target_reps_reached", "corrupt-value", "FUTURE_REASON")

        invalidValues.forEach { value ->
            assertEquals(SetEndReason.UNKNOWN, SetEndReason.fromPersisted(value), "value=$value")
        }
    }

    @Test
    fun `CompletedSet factory defaults an unspecified end reason to UNKNOWN`() {
        // Catches a factory default mutation that assigns a historical completion reason to new unspecified sets.
        val completedSet = CompletedSet.create(
            sessionId = "session-1",
            setNumber = 1,
            actualReps = 5,
            actualWeightKg = 100f,
        )

        assertEquals(SetEndReason.UNKNOWN, completedSet.setEndReason)
    }

    @Test
    fun `CompletedSet constructor defaults an unspecified end reason to UNKNOWN`() {
        // Catches a constructor default mutation that assigns a historical completion reason to direct callers.
        val completedSet = CompletedSet(
            id = "set-1",
            sessionId = "session-1",
            plannedSetId = null,
            setNumber = 1,
            setType = SetType.STANDARD,
            actualReps = 5,
            actualWeightKg = 100f,
            loggedRpe = null,
            isPr = false,
            completedAt = 0L,
        )

        assertEquals(SetEndReason.UNKNOWN, completedSet.setEndReason)
    }
}
