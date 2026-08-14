package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.presentation.manager.MachineTeardownState
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.reconnect
import vitruvianprojectphoenix.shared.generated.resources.workout_teardown_failed
import vitruvianprojectphoenix.shared.generated.resources.workout_teardown_finishing
import vitruvianprojectphoenix.shared.generated.resources.workout_teardown_retry

enum class StartGateLabel {
    START,
    FINISHING_PREVIOUS_WORKOUT,
}

data class WorkoutStartGatePresentation(
    val startEnabled: Boolean,
    val label: StartGateLabel,
    val showRecoveryActions: Boolean,
)

fun MachineTeardownState.toStartGatePresentation(
    requiresMachine: Boolean = true,
): WorkoutStartGatePresentation {
    val machinePresentation = when (this) {
        MachineTeardownState.Ready -> WorkoutStartGatePresentation(
            startEnabled = true,
            label = StartGateLabel.START,
            showRecoveryActions = false,
        )

        is MachineTeardownState.TearingDown -> WorkoutStartGatePresentation(
            startEnabled = false,
            label = StartGateLabel.FINISHING_PREVIOUS_WORKOUT,
            showRecoveryActions = false,
        )

        is MachineTeardownState.RecoveryRequired -> WorkoutStartGatePresentation(
            startEnabled = false,
            label = StartGateLabel.START,
            showRecoveryActions = true,
        )
    }
    return if (requiresMachine) {
        machinePresentation
    } else {
        machinePresentation.copy(
            startEnabled = true,
            label = StartGateLabel.START,
        )
    }
}

@Composable
fun WorkoutStartGateNotice(
    state: MachineTeardownState,
    onRetry: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (state) {
        MachineTeardownState.Ready -> Unit

        is MachineTeardownState.TearingDown -> {
            Text(
                text = stringResource(Res.string.workout_teardown_finishing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier,
            )
        }

        is MachineTeardownState.RecoveryRequired -> {
            Card(
                modifier = modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.workout_teardown_failed),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onRetry) {
                            Text(stringResource(Res.string.workout_teardown_retry))
                        }
                        OutlinedButton(onClick = onReconnect) {
                            Text(stringResource(Res.string.reconnect))
                        }
                    }
                }
            }
        }
    }
}
