package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.data.repository.MachineSafetyConnectResult
import com.devil.phoenixproject.data.repository.MachineSafetyHazardCause
import com.devil.phoenixproject.data.repository.MachineSafetyHazardDocument
import com.devil.phoenixproject.data.repository.MachineSafetyPhase
import com.devil.phoenixproject.data.repository.MachineSafetyWorkoutKind
import com.devil.phoenixproject.data.repository.MachineSafetyWriteResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionLostDialogCopyTest {
    @Test
    fun `in-session loss without a durable record reads as a connection loss`() {
        val copy = connectionLostDialogCopy(null)
        assertEquals("Connection Lost", copy.title)
        assertTrue(copy.body.startsWith("Bluetooth to the trainer was lost."))
        assertNull(copy.recoveryStatus)
        assertFalse(copy.recoveryInProgress)
    }

    @Test
    fun `title follows the hazard cause`() {
        assertEquals("Connection Lost", connectionLostDialogCopy(hazard(cause = MachineSafetyHazardCause.CONNECTION_LOST)).title)
        assertEquals("Set End Not Confirmed", connectionLostDialogCopy(hazard(cause = MachineSafetyHazardCause.SET_END_UNCONFIRMED)).title)
        assertEquals("Trainer Load Unconfirmed", connectionLostDialogCopy(hazard(cause = MachineSafetyHazardCause.UNKNOWN)).title)
    }

    @Test
    fun `every body says the load state is unknown`() {
        MachineSafetyHazardCause.entries.forEach { cause ->
            val body = connectionLostDialogCopy(hazard(cause = cause)).body
            assertTrue(body.contains("Do not assume the machine is unloaded."), "$cause: $body")
        }
    }

    @Test
    fun `unresolved hazard has no recovery status`() {
        assertNull(connectionLostDialogCopy(hazard()).recoveryStatus)
    }

    @Test
    fun `connecting phase reports progress and disables a second request`() {
        val copy = connectionLostDialogCopy(hazard(phase = MachineSafetyPhase.CONNECTING))
        assertTrue(copy.recoveryInProgress)
        assertTrue(copy.recoveryStatus.orEmpty().startsWith("Looking for the trainer"))
    }

    @Test
    fun `sent phase never claims the cables are unloaded`() {
        val status = connectionLostDialogCopy(
            hazard(
                phase = MachineSafetyPhase.RELEASE_REQUEST_SENT,
                connect = MachineSafetyConnectResult.MATCHING_READY,
                write = MachineSafetyWriteResult.TRANSPORT_ACK,
            ),
        ).recoveryStatus.orEmpty()
        assertTrue(status.startsWith("Stop requested over Bluetooth"), status)
        assertTrue(status.contains("not proof the cables are unloaded"), status)
    }

    @Test
    fun `failed phase names what went wrong`() {
        fun status(connect: MachineSafetyConnectResult, write: MachineSafetyWriteResult) = connectionLostDialogCopy(
            hazard(phase = MachineSafetyPhase.RELEASE_REQUEST_FAILED, connect = connect, write = write),
        ).recoveryStatus.orEmpty()

        assertTrue(status(MachineSafetyConnectResult.WRONG_TRAINER, MachineSafetyWriteResult.NOT_CONNECTED).startsWith("A different trainer is connected"))
        assertTrue(status(MachineSafetyConnectResult.FAILED, MachineSafetyWriteResult.NOT_CONNECTED).startsWith("Could not reach the trainer"))
        assertTrue(status(MachineSafetyConnectResult.MATCHING_READY, MachineSafetyWriteResult.TRANSPORT_FAIL).startsWith("The stop request failed to send"))
        assertTrue(status(MachineSafetyConnectResult.MATCHING_READY, MachineSafetyWriteResult.DISCONNECTED_DURING_WRITE).startsWith("Bluetooth dropped while the stop was being sent"))
    }

    private fun hazard(
        cause: MachineSafetyHazardCause = MachineSafetyHazardCause.CONNECTION_LOST,
        phase: MachineSafetyPhase = MachineSafetyPhase.UNRESOLVED,
        connect: MachineSafetyConnectResult = MachineSafetyConnectResult.NONE,
        write: MachineSafetyWriteResult = MachineSafetyWriteResult.NONE,
    ) = MachineSafetyHazardDocument(
        generation = 1L,
        trainerAddress = "trainer-1",
        sessionId = "session",
        workoutKind = MachineSafetyWorkoutKind.ROUTINE,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
        phase = phase,
        lastConnectResult = connect,
        lastWriteResult = write,
        cause = cause,
    )
}
