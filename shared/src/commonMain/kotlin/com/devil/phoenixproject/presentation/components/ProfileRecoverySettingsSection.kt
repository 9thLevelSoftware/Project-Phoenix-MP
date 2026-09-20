package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.AuthRepository
import com.devil.phoenixproject.data.repository.OwnershipTransferMutation
import com.devil.phoenixproject.data.repository.OwnershipTransferRepository
import com.devil.phoenixproject.data.repository.PendingProfileRecoveryGroup
import com.devil.phoenixproject.data.repository.ProfileRecoveryRepository
import com.devil.phoenixproject.data.repository.ProfileRecoveryResolution
import com.devil.phoenixproject.data.repository.UserProfile
import com.devil.phoenixproject.data.repository.UserProfileRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** User-visible resolution for ambiguous startup ownership and pending cloud acknowledgement. */
@Composable
fun ProfileRecoverySettingsSection() {
    val recoveryRepository: ProfileRecoveryRepository = koinInject()
    val ownershipTransfers: OwnershipTransferRepository = koinInject()
    val profilesRepository: UserProfileRepository = koinInject()
    val authRepository: AuthRepository = koinInject()
    val pending by recoveryRepository.pendingRecoveries.collectAsState()
    val profiles by profilesRepository.allProfiles.collectAsState()
    val authState by authRepository.authState.collectAsState()
    val scope = rememberCoroutineScope()
    var cloudPending by remember { mutableStateOf<List<OwnershipTransferMutation>>(emptyList()) }
    var operationInProgress by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        recoveryRepository.refresh()
        cloudPending = ownershipTransfers.pendingAll()
    }

    suspend fun runRecovery(operation: suspend () -> ProfileRecoveryResolution) {
        operationInProgress = true
        try {
            resultMessage = resolutionMessage(operation())
            refresh()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            resultMessage = "Recovery could not be completed. Try again."
            runCatching { refresh() }
        } finally {
            operationInProgress = false
        }
    }

    LaunchedEffect(authState) {
        refresh()
        while (true) {
            delay(3_000)
            cloudPending = ownershipTransfers.pendingAll()
        }
    }
    if (pending.isEmpty() && cloudPending.isEmpty()) return

    ProfileRecoveryCard(
        pending = pending,
        profiles = profiles,
        cloudPending = cloudPending,
        operationInProgress = operationInProgress,
        resultMessage = resultMessage,
        onKeepDefault = { recoveryId ->
            scope.launch {
                runRecovery {
                    recoveryRepository.keepWithDefault(recoveryId, authRepository.currentUser?.id)
                }
            }
        },
        onMove = { recoveryId, targetProfileId ->
            scope.launch {
                runRecovery {
                    recoveryRepository.moveToProfile(
                        recoveryId,
                        targetProfileId,
                        authRepository.currentUser?.id,
                    )
                }
            }
        },
    )
}

@Composable
internal fun ProfileRecoveryCard(
    pending: List<PendingProfileRecoveryGroup>,
    profiles: List<UserProfile>,
    cloudPending: List<OwnershipTransferMutation>,
    operationInProgress: Boolean,
    resultMessage: String?,
    onKeepDefault: (String) -> Unit,
    onMove: (String, String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Profile data recovery", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Choose where ambiguous local data belongs. Phoenix keeps every stable record ID and does not change its account owner.",
                style = MaterialTheme.typography.bodyMedium,
            )
            pending.forEach { recovery ->
                RecoveryDecision(
                    recovery = recovery,
                    profiles = profiles,
                    enabled = !operationInProgress,
                    onKeepDefault = onKeepDefault,
                    onMove = onMove,
                )
            }
            if (cloudPending.isNotEmpty()) {
                Text(
                    "${cloudPending.size} ownership transfer${if (cloudPending.size == 1) " is" else "s are"} waiting for cloud acknowledgement.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "The local recovery is complete. Sync will retry these exact IDs before ordinary uploads.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            resultMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun RecoveryDecision(
    recovery: PendingProfileRecoveryGroup,
    profiles: List<UserProfile>,
    enabled: Boolean,
    onKeepDefault: (String) -> Unit,
    onMove: (String, String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(recovery.sourceProfileName, fontWeight = FontWeight.SemiBold)
        Text(
            recovery.counts.tableCounts
                .filterValues { it > 0 }
                .entries
                .joinToString { (table, count) -> "$count ${friendlyRecoveryTableName(table, count)}" },
            style = MaterialTheme.typography.bodySmall,
        )
        if (recovery.ownerUserId == null && recovery.counts.cloudOriginRowCount > 0) {
            Text(
                "Relink the original account before moving these cloud records.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { onKeepDefault(recovery.recoveryId) },
                enabled = enabled,
            ) {
                Text("Keep with Default")
            }
        }
        profiles
            .filter { it.id != recovery.sourceProfileId && it.id != "default" }
            .forEach { profile ->
                Button(
                    onClick = { onMove(recovery.recoveryId, profile.id) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Move to ${profile.name}")
                }
            }
    }
}

private fun friendlyRecoveryTableName(table: String, count: Long): String {
    val singular = when (table) {
        "WorkoutSession" -> "workout record"
        "PersonalRecord" -> "personal record"
        "Routine" -> "routine"
        "RoutineGroup" -> "routine group"
        "TrainingCycle" -> "training cycle"
        "AssessmentResult" -> "assessment result"
        "VelocityOneRepMaxEstimate" -> "strength estimate"
        "ProgressionEvent" -> "progression event"
        "EarnedBadge" -> "earned badge"
        "ExerciseMvt" -> "exercise velocity setting"
        "StreakHistory" -> "streak record"
        "ProfileExerciseBaseline" -> "exercise baseline"
        else -> "profile record"
    }
    return if (count == 1L) singular else "${singular}s"
}

private fun resolutionMessage(result: ProfileRecoveryResolution): String = when (result) {
    ProfileRecoveryResolution.Resolved -> "Recovery completed."
    ProfileRecoveryResolution.Busy -> "Finish the active workout or assessment before recovering profile data."
    ProfileRecoveryResolution.RelinkRequired -> "Relink the original account before moving these cloud records."
    ProfileRecoveryResolution.VerificationFailed -> "Phoenix could not verify this data with the signed-in account. Check the account and try again."
    is ProfileRecoveryResolution.AccountMismatch -> "Sign out of the other account before recovering this data."
    ProfileRecoveryResolution.Missing -> "This recovery item is no longer pending."
}
