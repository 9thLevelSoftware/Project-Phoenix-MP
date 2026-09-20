package com.devil.phoenixproject.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProfileRecoveryPolicyTest {
    @Test
    fun `known owner remains immutable while logged out and rejects another account`() {
        assertEquals(
            ProfileRecoveryOwnerDecision.QueueForKnownOwner("owner-a"),
            decideProfileRecoveryOwner(
                persistedOwnerUserId = "owner-a",
                signedInOwnerUserId = null,
                cloudOriginRowCount = 4,
            ),
        )
        assertEquals(
            ProfileRecoveryOwnerDecision.AccountMismatch("owner-a", "owner-b"),
            decideProfileRecoveryOwner(
                persistedOwnerUserId = "owner-a",
                signedInOwnerUserId = "owner-b",
                cloudOriginRowCount = 4,
            ),
        )
    }

    @Test
    fun `unknown cloud owner requires relink while local rows need no transfer`() {
        assertEquals(
            ProfileRecoveryOwnerDecision.RelinkRequired,
            decideProfileRecoveryOwner(null, null, cloudOriginRowCount = 1),
        )
        assertEquals(
            ProfileRecoveryOwnerDecision.LocalOnly,
            decideProfileRecoveryOwner(null, "currently-signed-in", cloudOriginRowCount = 0),
        )
    }

    @Test
    fun `ownership event canonical body is stable but detects mutation reuse`() {
        val first = event(
            workoutSessionIds = listOf("workout-b", "workout-a", "workout-a"),
            routineIds = listOf("routine-b", "routine-a"),
        )
        val replay = event(
            workoutSessionIds = listOf("workout-a", "workout-b"),
            routineIds = listOf("routine-a", "routine-b", "routine-a"),
        )
        val conflict = replay.copy(targetProfileName = "Different target")

        assertEquals(first.canonicalBody(), replay.canonicalBody())
        assertEquals(first.canonicalBodyHash(), replay.canonicalBodyHash())
        assertFailsWith<OwnershipEventConflictException> {
            validateOwnershipEventReplay(
                ownerUserId = "owner-a",
                mutationId = "mutation-1",
                persistedCanonicalBodyHash = first.canonicalBodyHash(),
                event = conflict,
            )
        }
    }

    private fun event(
        workoutSessionIds: List<String>,
        routineIds: List<String>,
    ) = OwnershipEvent(
        mutationId = "mutation-1",
        sourceProfileId = "source",
        targetProfileId = "target",
        targetProfileName = "Recovered",
        targetProfileColorIndex = 3,
        workoutSessionIds = workoutSessionIds,
        routineIds = routineIds,
        cycleIds = listOf("cycle-1"),
        personalRecordIds = listOf("pr-1"),
        transferredAt = 42L,
    )
}
