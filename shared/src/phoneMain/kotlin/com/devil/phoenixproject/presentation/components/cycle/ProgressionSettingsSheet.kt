package com.devil.phoenixproject.presentation.components.cycle

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.CycleProgression
import com.devil.phoenixproject.domain.model.percentLabel
import com.devil.phoenixproject.presentation.components.ExpressiveSlider
import com.devil.phoenixproject.presentation.components.PhoenixSheetHeader
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressionSettingsSheet(
    progression: CycleProgression,
    currentRotation: Int,
    onSave: (CycleProgression) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    var frequency by remember { mutableStateOf(progression.frequencyCycles) }
    var weightEnabled by remember { mutableStateOf(progression.weightIncreasePercent != null) }
    var weightPercent by remember { mutableStateOf(progression.weightIncreasePercent ?: 2.5f) }
    var echoEnabled by remember { mutableStateOf(progression.echoLevelIncrease) }
    var eccentricEnabled by remember { mutableStateOf(progression.eccentricLoadIncreasePercent != null) }
    var eccentricPercent by remember { mutableStateOf(progression.eccentricLoadIncreasePercent ?: 5) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            PhoenixSheetHeader(
                title = stringResource(Res.string.cd_progression_settings),
                onClose = onDismiss,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Frequency selector
            Text(
                text = stringResource(Res.string.progression_apply_every),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(
                    onClick = { if (frequency > 1) frequency-- },
                    enabled = frequency > 1,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Text(stringResource(Res.string.nav_previous), style = MaterialTheme.typography.titleLarge)
                }
                Text(
                    text = stringResource(Res.string.progression_frequency_completions, frequency),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                IconButton(
                    onClick = { if (frequency < 10) frequency++ },
                    enabled = frequency < 10,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Text(stringResource(Res.string.nav_next), style = MaterialTheme.typography.titleLarge)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Weight increase
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(
                    checked = weightEnabled,
                    onCheckedChange = { weightEnabled = it },
                )
                Text(
                    text = stringResource(Res.string.progression_increase_weight_by),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            if (weightEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExpressiveSlider(
                        value = weightPercent,
                        onValueChange = { weightPercent = it },
                        valueRange = 0.5f..10f,
                        steps = 18,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = percentLabel(weightIncreasePercentLabelValue(weightPercent)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.width(50.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Echo level increase
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(
                    checked = echoEnabled,
                    onCheckedChange = { echoEnabled = it },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.progression_increase_echo_by_1),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(Res.string.progression_applies_to_echo),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Eccentric load increase
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Checkbox(
                    checked = eccentricEnabled,
                    onCheckedChange = { eccentricEnabled = it },
                )
                Text(
                    text = stringResource(Res.string.progression_eccentric_load_increase_by),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            if (eccentricEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ExpressiveSlider(
                        value = eccentricPercent.toFloat(),
                        onValueChange = { eccentricPercent = it.toInt() },
                        valueRange = 1f..20f,
                        steps = 18,
                        remoteStep = 1f,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = percentLabel(eccentricPercent),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.widthIn(min = 60.dp),
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // Progress indicator
            val cyclesUntilNext = frequency - (currentRotation % frequency)
            Text(
                text = stringResource(Res.string.progression_current_rotation, currentRotation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = if (cyclesUntilNext == 1) {
                    stringResource(Res.string.progression_next_after_one)
                } else {
                    stringResource(Res.string.progression_next_after_other, cyclesUntilNext)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Apply button
            Button(
                onClick = {
                    onSave(
                        CycleProgression(
                            cycleId = progression.cycleId,
                            frequencyCycles = frequency,
                            weightIncreasePercent = if (weightEnabled) weightPercent else null,
                            echoLevelIncrease = echoEnabled,
                            eccentricLoadIncreasePercent = if (eccentricEnabled) eccentricPercent else null,
                        ),
                    )
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(Res.string.action_apply))
            }
        }
    }
}

/**
 * Display value for the weight-increase slider label.
 *
 * [kotlin.math.round] on a Float returns Int, so dividing by the Int literal 10
 * truncates tenths (the default 2.5 reads as 2, and 0.5 reads as 0). Divide by
 * 10f, then drop a trailing .0 so whole percents stay "2%" while 2.5 and 0.5
 * keep their tenth. The saved [CycleProgression.weightIncreasePercent] is unchanged.
 */
internal fun weightIncreasePercentLabelValue(weightPercent: Float): Number {
    val rounded = kotlin.math.round(weightPercent * 10) / 10f
    return if (rounded == rounded.toInt().toFloat()) rounded.toInt() else rounded
}
