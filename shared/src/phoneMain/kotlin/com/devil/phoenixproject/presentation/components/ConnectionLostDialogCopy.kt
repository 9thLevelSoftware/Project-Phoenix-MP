package com.devil.phoenixproject.presentation.components

import androidx.compose.runtime.Composable
import com.devil.phoenixproject.data.repository.MachineSafetyConnectResult
import com.devil.phoenixproject.data.repository.MachineSafetyHazardCause
import com.devil.phoenixproject.data.repository.MachineSafetyHazardDocument
import com.devil.phoenixproject.data.repository.MachineSafetyPhase
import com.devil.phoenixproject.data.repository.MachineSafetyWriteResult
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.connection_lost_message
import projectphoenix.shared.generated.resources.connection_lost_phase_acknowledged
import projectphoenix.shared.generated.resources.connection_lost_phase_connecting
import projectphoenix.shared.generated.resources.connection_lost_phase_could_not_reach
import projectphoenix.shared.generated.resources.connection_lost_phase_disconnect_during_stop
import projectphoenix.shared.generated.resources.connection_lost_phase_transport_fail
import projectphoenix.shared.generated.resources.connection_lost_phase_wrong_device
import projectphoenix.shared.generated.resources.connection_lost_set_end_message
import projectphoenix.shared.generated.resources.connection_lost_set_end_title
import projectphoenix.shared.generated.resources.connection_lost_title
import projectphoenix.shared.generated.resources.connection_lost_unconfirmed_message
import projectphoenix.shared.generated.resources.connection_lost_unconfirmed_title
import projectphoenix.shared.generated.resources.connection_lost_unknown_load

/**
 * Text for [ConnectionLostDialog], derived from the durable hazard record so the warning says
 * what actually happened and what the last recovery attempt did. Kept out of the composable so
 * the cause/phase mapping is unit-testable.
 */
internal data class ConnectionLostDialogCopy(
    val title: String,
    val body: String,
    /** Outcome of the last "Reconnect to request release" attempt, or null before the first. */
    val recoveryStatus: String?,
    val recoveryInProgress: Boolean,
)

internal const val CONNECTION_LOST_SECONDARY_COPY =
    "A successful reconnect can request a software stop, but that is not proof the cables are " +
        "unloaded. Follow the trainer's safety guidance; powering the trainer off is a valid last resort."

internal const val CONNECTION_LOST_ACK_COPY =
    "New sets stay blocked until you confirm. Check the trainer yourself: handles back at rest " +
        "and cables not pulling beyond their normal light resting tension."

private const val LOAD_UNKNOWN_COPY =
    "Phoenix cannot confirm whether resistance is still engaged. Do not assume the machine is unloaded."

/**
 * [hazard] is null when only the in-session connection-loss flag is set (no durable record yet).
 */
internal fun connectionLostDialogCopy(hazard: MachineSafetyHazardDocument?): ConnectionLostDialogCopy {
    val (title, body) = when (hazard?.cause) {
        null, MachineSafetyHazardCause.CONNECTION_LOST ->
            "Connection Lost" to "Bluetooth to the trainer was lost. $LOAD_UNKNOWN_COPY"

        MachineSafetyHazardCause.SET_END_UNCONFIRMED ->
            "Set End Not Confirmed" to
                "Phoenix did not get confirmation that your last set on this trainer ended with a " +
                "stop command. $LOAD_UNKNOWN_COPY"

        MachineSafetyHazardCause.UNKNOWN ->
            "Trainer Load Unconfirmed" to
                "Phoenix has an unresolved safety record for this trainer. $LOAD_UNKNOWN_COPY"
    }
    return ConnectionLostDialogCopy(
        title = title,
        body = body,
        recoveryStatus = hazard?.let(::recoveryStatusCopy),
        recoveryInProgress = hazard?.phase == MachineSafetyPhase.CONNECTING,
    )
}

private fun recoveryStatusCopy(hazard: MachineSafetyHazardDocument): String? = when (hazard.phase) {
    MachineSafetyPhase.UNRESOLVED -> null
    MachineSafetyPhase.CONNECTING -> "Looking for the trainer and requesting a software stop…"
    MachineSafetyPhase.RELEASE_REQUEST_SENT ->
        "Stop requested over Bluetooth and acknowledged by the trainer. That is not proof the " +
            "cables are unloaded: check the handles, then confirm below."

    MachineSafetyPhase.RELEASE_REQUEST_FAILED -> when {
        hazard.lastConnectResult == MachineSafetyConnectResult.WRONG_TRAINER ->
            "A different trainer is connected, so no stop was requested. Connect to the original " +
                "trainer, or check it yourself and confirm below."

        hazard.lastWriteResult == MachineSafetyWriteResult.DISCONNECTED_DURING_WRITE ->
            "Bluetooth dropped while the stop was being sent. Try again, or check the trainer " +
                "yourself and confirm below."

        hazard.lastWriteResult == MachineSafetyWriteResult.TRANSPORT_FAIL ->
            "The stop request failed to send. Try again, or check the trainer yourself and " +
                "confirm below."

        else ->
            "Could not reach the trainer over Bluetooth, so no stop was requested. If it is " +
                "powered off or asleep, check the handles yourself and confirm below."
    }
}

@Composable
internal fun localizedConnectionLostDialogCopy(hazard: MachineSafetyHazardDocument?): ConnectionLostDialogCopy {
    val loadUnknown = stringResource(Res.string.connection_lost_unknown_load)
    val (title, body) = when (hazard?.cause) {
        null, MachineSafetyHazardCause.CONNECTION_LOST ->
            stringResource(Res.string.connection_lost_title) to
                stringResource(Res.string.connection_lost_message, loadUnknown)

        MachineSafetyHazardCause.SET_END_UNCONFIRMED ->
            stringResource(Res.string.connection_lost_set_end_title) to
                stringResource(Res.string.connection_lost_set_end_message, loadUnknown)

        MachineSafetyHazardCause.UNKNOWN ->
            stringResource(Res.string.connection_lost_unconfirmed_title) to
                stringResource(Res.string.connection_lost_unconfirmed_message, loadUnknown)
    }
    return ConnectionLostDialogCopy(
        title = title,
        body = body,
        recoveryStatus = hazard?.let { localizedRecoveryStatusCopy(it) },
        recoveryInProgress = hazard?.phase == MachineSafetyPhase.CONNECTING,
    )
}

@Composable
private fun localizedRecoveryStatusCopy(hazard: MachineSafetyHazardDocument): String? = when (hazard.phase) {
    MachineSafetyPhase.UNRESOLVED -> null
    MachineSafetyPhase.CONNECTING -> stringResource(Res.string.connection_lost_phase_connecting)
    MachineSafetyPhase.RELEASE_REQUEST_SENT -> stringResource(Res.string.connection_lost_phase_acknowledged)
    MachineSafetyPhase.RELEASE_REQUEST_FAILED -> when {
        hazard.lastConnectResult == MachineSafetyConnectResult.WRONG_TRAINER ->
            stringResource(Res.string.connection_lost_phase_wrong_device)

        hazard.lastWriteResult == MachineSafetyWriteResult.DISCONNECTED_DURING_WRITE ->
            stringResource(Res.string.connection_lost_phase_disconnect_during_stop)

        hazard.lastWriteResult == MachineSafetyWriteResult.TRANSPORT_FAIL ->
            stringResource(Res.string.connection_lost_phase_transport_fail)

        else ->
            stringResource(Res.string.connection_lost_phase_could_not_reach)
    }
}
