package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class SetEndReasonTest {

    @Test
    fun `fromPersisted round-trips every declared durable reason`() {
        // Catches a codec mutation that rejects or maps any persisted enum name to another reason.
        SetEndReason.entries.forEach { reason ->
            assertEquals(reason, SetEndReason.fromPersisted(reason.name))
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
}
