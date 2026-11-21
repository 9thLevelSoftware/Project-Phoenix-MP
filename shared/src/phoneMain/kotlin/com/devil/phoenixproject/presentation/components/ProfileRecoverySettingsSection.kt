package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/** User-visible resolution for ambiguous startup ownership and pending cloud acknowledgement. */
@Composable
fun ProfileRecoverySettingsSection() {
    val recoveryRepository: ProfileRecoveryRepository = koinInject()
    val ownershipTransfers: OwnershipTransferRepository = koinInject()
    val pending by recoveryRepository.observeUnresolved().collectAsState(initial = emptyList())
    val cloudPending by ownershipTransfers.observePending().collectAsState(initial = emptyList())
    // Survives the card leaving composition so the last recovery is not cancelled mid-write.
    val operationScope = rememberCoroutineScope()
    if (pending.isEmpty() && cloudPending.isEmpty()) return

    VisibleProfileRecoverySettings(
        recoveryRepository = recoveryRepository,
        pending = pending,
        cloudPending = cloudPending,
        operationScope = operationScope,
    )
}

@Composable
private fun VisibleProfileRecoverySettings(
    recoveryRepository: ProfileRecoveryRepository,
    pending: List<PendingProfileRecoveryGroup>,
    cloudPending: List<OwnershipTransferMutation>,
    operationScope: CoroutineScope,
) {
    val profilesRepository: UserProfileRepository = koinInject()
    val authRepository: AuthRepository = koinInject()
    val profiles by profilesRepository.allProfiles.collectAsState()
    var operationInProgress by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    // This section is composed only while a recovery is visible. Leaving it stops the refresh.
    LaunchedEffect(recoveryRepository) {
        refreshProfileRecoveryWhileShown(
            shown = MutableStateFlow(true),
            refresh = recoveryRepository::refresh,
        )
    }

    suspend fun runRecovery(operation: suspend () -> ProfileRecoveryResolution) {
        operationInProgress = true
        try {
            resultMessage = resolutionMessage(operation())
            recoveryRepository.refresh()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            resultMessage = getString(Res.string.recovery_failed_generic)
            runCatching { recoveryRepository.refresh() }
        } finally {
            operationInProgress = false
        }
    }

    ProfileRecoveryCard(
        pending = pending,
        profiles = profiles,
        cloudPending = cloudPending,
        operationInProgress = operationInProgress,
        resultMessage = resultMessage,
        onKeepDefault = { recoveryId ->
            operationScope.launch {
                runRecovery {
                    recoveryRepository.keepWithDefault(recoveryId, authRepository.currentUser?.id)
                }
            }
        },
        onMove = { recoveryId, targetProfileId ->
            operationScope.launch {
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
private fun ProfileRecoveryCard(
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
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(Res.string.recovery_section_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                stringResource(Res.string.recovery_section_subtitle),
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
                    if (cloudPending.size == 1) {
                        stringResource(Res.string.recovery_cloud_transfers_pending_one)
                    } else {
                        stringResource(Res.string.recovery_cloud_transfers_pending_other, cloudPending.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(Res.string.recovery_cloud_retry_note),
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
        val nonZeroEntries = recovery.counts.tableCounts
            .filterValues { it > 0 }
            .entries
            .toList()
        val summaryText = nonZeroEntries
            .map { (table, count) -> friendlyRecoveryTableCount(table, count) }
            .joinToString()
        Text(
            summaryText,
            style = MaterialTheme.typography.bodySmall,
        )
        if (recovery.ownerUserId == null && recovery.counts.cloudOriginRowCount > 0) {
            Text(
                stringResource(Res.string.recovery_relink_required),
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { onKeepDefault(recovery.recoveryId) },
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.6f),
                ),
            ) {
                Text(stringResource(Res.string.recovery_keep_with_default))
            }
            profiles
                .filter { it.id != recovery.sourceProfileId && it.id != "default" }
                .forEach { profile ->
                    Button(
                        onClick = { onMove(recovery.recoveryId, profile.id) },
                        enabled = enabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) {
                        Text(stringResource(Res.string.recovery_move_to_profile, profile.name))
                    }
                }
        }
    }
}

@Composable
private fun friendlyRecoveryTableCount(table: String, count: Long): String {
    val isSingle = count == 1L
    val res = when (table) {
        "WorkoutSession" -> if (isSingle) Res.string.recovery_table_workout_one else Res.string.recovery_table_workout_other
        "PersonalRecord" -> if (isSingle) Res.string.recovery_table_pr_one else Res.string.recovery_table_pr_other
        "Routine" -> if (isSingle) Res.string.recovery_table_routine_one else Res.string.recovery_table_routine_other
        "RoutineGroup" -> if (isSingle) Res.string.recovery_table_routine_group_one else Res.string.recovery_table_routine_group_other
        "TrainingCycle" -> if (isSingle) Res.string.recovery_table_cycle_one else Res.string.recovery_table_cycle_other
        "AssessmentResult" -> if (isSingle) Res.string.recovery_table_assessment_one else Res.string.recovery_table_assessment_other
        "VelocityOneRepMaxEstimate" -> if (isSingle) Res.string.recovery_table_strength_estimate_one else Res.string.recovery_table_strength_estimate_other
        "ProgressionEvent" -> if (isSingle) Res.string.recovery_table_progression_event_one else Res.string.recovery_table_progression_event_other
        "EarnedBadge" -> if (isSingle) Res.string.recovery_table_earned_badge_one else Res.string.recovery_table_earned_badge_other
        "ExerciseMvt" -> if (isSingle) Res.string.recovery_table_mvt_one else Res.string.recovery_table_mvt_other
        "StreakHistory" -> if (isSingle) Res.string.recovery_table_streak_one else Res.string.recovery_table_streak_other
        "ProfileExerciseBaseline" -> if (isSingle) Res.string.recovery_table_baseline_one else Res.string.recovery_table_baseline_other
        else -> if (isSingle) Res.string.recovery_table_default_one else Res.string.recovery_table_default_other
    }
    return stringResource(res, count)
}

private suspend fun resolutionMessage(result: ProfileRecoveryResolution): String = when (result) {
    ProfileRecoveryResolution.Resolved -> getString(Res.string.recovery_resolution_resolved)
    ProfileRecoveryResolution.Busy -> getString(Res.string.recovery_resolution_busy)
    ProfileRecoveryResolution.RelinkRequired -> getString(Res.string.recovery_relink_required)
    ProfileRecoveryResolution.VerificationFailed -> getString(Res.string.recovery_resolution_verification_failed)
    is ProfileRecoveryResolution.AccountMismatch -> getString(Res.string.recovery_resolution_account_mismatch)
    ProfileRecoveryResolution.Missing -> getString(Res.string.recovery_resolution_missing)
}
