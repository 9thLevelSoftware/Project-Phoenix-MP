package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.AppliedRoutineModifier
import com.devil.phoenixproject.domain.model.RoutineModifierType
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.routine_modifier_active_recovery
import vitruvianprojectphoenix.shared.generated.resources.routine_modifier_active_recovery_description
import vitruvianprojectphoenix.shared.generated.resources.routine_modifier_cancel
import vitruvianprojectphoenix.shared.generated.resources.routine_modifier_heavy_deload
import vitruvianprojectphoenix.shared.generated.resources.routine_modifier_heavy_deload_description
import vitruvianprojectphoenix.shared.generated.resources.routine_modifier_percent_label
import vitruvianprojectphoenix.shared.generated.resources.routine_modifier_start

@Composable
fun RoutineModifierDialog(
    type: RoutineModifierType,
    onDismiss: () -> Unit,
    onConfirm: (AppliedRoutineModifier) -> Unit,
) {
    var selectedPercent by remember(type) { mutableIntStateOf(AppliedRoutineModifier.selectablePercents.first()) }
    val title = when (type) {
        RoutineModifierType.ACTIVE_RECOVERY -> stringResource(Res.string.routine_modifier_active_recovery)
        RoutineModifierType.HEAVY_DELOAD -> stringResource(Res.string.routine_modifier_heavy_deload)
    }
    val description = when (type) {
        RoutineModifierType.ACTIVE_RECOVERY -> stringResource(Res.string.routine_modifier_active_recovery_description)
        RoutineModifierType.HEAVY_DELOAD -> stringResource(Res.string.routine_modifier_heavy_deload_description)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            androidx.compose.foundation.layout.Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(description)
                Text(stringResource(Res.string.routine_modifier_percent_label))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppliedRoutineModifier.selectablePercents.forEach { percent ->
                        FilterChip(
                            selected = selectedPercent == percent,
                            onClick = { selectedPercent = percent },
                            label = { Text("$percent%") },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(AppliedRoutineModifier(type, selectedPercent)) }) {
                Text(stringResource(Res.string.routine_modifier_start))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.routine_modifier_cancel))
            }
        },
    )
}
