package com.devil.phoenixproject.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ProfileMutationBarrierTest {
    @Test
    fun recoveryAndSyncMutationsCannotOverlap() = runTest {
        val barrier = ProfileMutationBarrier()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()

        val recovery = async {
            barrier.withExclusive {
                events += "recovery-enter"
                firstEntered.complete(Unit)
                releaseFirst.await()
                events += "recovery-exit"
            }
        }
        firstEntered.await()

        val sync = async {
            barrier.withExclusive {
                events += "sync-enter"
            }
        }
        runCurrent()
        assertEquals(listOf("recovery-enter"), events)

        releaseFirst.complete(Unit)
        recovery.await()
        sync.await()
        assertEquals(listOf("recovery-enter", "recovery-exit", "sync-enter"), events)
    }

    @Test
    fun exclusiveMutationReturnsItsResult() = runTest {
        val result = ProfileMutationBarrier().withExclusive { "complete" }

        assertEquals("complete", result)
    }

    @Test
    fun accountSwitchWaitsForInflightOwnerBoundAcknowledgement() = runTest {
        val barrier = ProfileMutationBarrier()
        var authenticatedOwner = "owner-a"
        var acknowledgedOwner: String? = null
        val requestStarted = CompletableDeferred<Unit>()
        val releaseResponse = CompletableDeferred<Unit>()

        val sync = async {
            barrier.withExclusive {
                val capturedOwner = authenticatedOwner
                requestStarted.complete(Unit)
                releaseResponse.await()
                acknowledgedOwner = capturedOwner
            }
        }
        requestStarted.await()
        val accountSwitch = async {
            barrier.withExclusive { authenticatedOwner = "owner-b" }
        }
        runCurrent()
        assertEquals("owner-a", authenticatedOwner)

        releaseResponse.complete(Unit)
        sync.await()
        accountSwitch.await()

        assertEquals("owner-a", acknowledgedOwner)
        assertEquals("owner-b", authenticatedOwner)
    }
}
