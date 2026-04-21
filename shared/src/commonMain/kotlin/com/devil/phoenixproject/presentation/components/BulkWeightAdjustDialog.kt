package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.util.Constants
import com.devil.phoenixproject.util.UnitConverter

// ── Data types ──────────────────────────────────────────────────────

/**
 * Adjustment mode for bulk weight changes.
 *
 * Design decisions:
 * - progressionKg is intentionally NOT scaled. Progression defines the per-session
 *   auto-increment (e.g., +0.5kg each workout) and is a training-plan parameter,
 *   not a current-weight parameter. Scaling it would silently alter the user's
 *   progression strategy.
 * - Warmup sets use percentOfWorking (relative to working weight) so they shift
 *   implicitly when the working weight changes. No separate adjustment needed.
 */
sealed class BulkAdjustMode {
    /** Adjust all weights by [percent] (e.g., 10.0 = +10%, -5.0 = -5%). */
    data class Percentage(val percent: Float) : BulkAdjustMode()

    /** Adjust all weights by [deltaKg] in kilograms (positive = increase). */
    data class Absolute(val deltaKg: Float) : BulkAdjustMode()
}

// ── Pure logic (unit-testable, no Compose dependency) ───────────────

/**
 * Apply a bulk weight adjustment to a list of routine exercises.
 *
 * Rules:
 * - Exercises with [RoutineExercise.usePercentOfPR] == true are SKIPPED
 *   (their weight is PR-derived at runtime; adjusting the absolute field would be misleading).
 * - [RoutineExercise.weightPerCableKg] is adjusted.
 * - [RoutineExercise.setWeightsPerCableKg], if non-empty, has each entry adjusted.
 * - Results are clamped to [Constants.MIN_WEIGHT_KG]..[Constants.MAX_WEIGHT_KG].
 *   NOTE: Trainer+ supports 110kg per cable, but MAX_WEIGHT_KG is 100kg as a safe default.
 *   This is a known limitation tracked in Constants.kt.
 * - Results are rounded to the 0.5kg machine increment via [UnitConverter.roundToMachineIncrement].
 * - All non-weight fields (id, exercise, orderIndex, etc.) are preserved unchanged.
 *
 * @return A new list with adjusted weights. List size and element IDs are identical to [exercises].
 */
fun applyBulkAdjust(
    exercises: List<RoutineExercise>,
    mode: BulkAdjustMode,
): List<RoutineExercise> = exercises.map { ex ->
    if (ex.usePercentOfPR) return@map ex

    fun adjust(weight: Float): Float {
        val raw = when (mode) {
            is BulkAdjustMode.Percentage -> weight * (1f + mode.percent / 100f)
            is BulkAdjustMode.Absolute -> weight + mode.deltaKg
        }
        return UnitConverter.roundToMachineIncrement(
            raw.coerceIn(Constants.MIN_WEIGHT_KG, Constants.MAX_WEIGHT_KG),
        )
    }

    val newWeight = adjust(ex.weightPerCableKg)
    val newSetWeights = if (ex.setWeightsPerCableKg.isNotEmpty()) {
        ex.setWeightsPerCableKg.map { adjust(it) }
    } else {
        emptyList()
    }

    ex.copy(
        weightPerCableKg = newWeight,
        setWeightsPerCableKg = newSetWeights,
    )
}

// ── Composable ──────────────────────────────────────────────────────

/**
 * Dialog for adjusting all (or selected) exercise weights in bulk.
 *
 * Offers two modes via a tab row:
 * - **Percentage**: preset chips (-10%, -5%, +5%, +10%) plus custom input
 * - **Absolute**: delta in the user's display unit (kg or lb), converted to kg internally
 *
 * Shows a preview of each exercise with current -> new weight.
 * PR-scaled exercises are shown greyed out with an explanatory note.
 * Clamped values are flagged with "(clamped)".
 *
 * @param exercises The exercises to adjust
 * @param weightUnit The user's preferred display unit
 * @param formatWeight Formats (kgPerCable, WeightUnit) -> display string
 * @param onApply Called with the adjusted exercise list when the user confirms
 * @param onDismiss Called when the dialog is cancelled
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BulkWeightAdjustDialog(
    exercises: List<RoutineExercise>,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    onApply: (List<RoutineExercise>) -> Unit,
    onDismiss: () -> Unit,
) {
    // 0 = Percentage, 1 = Absolute
    var selectedTab by remember { mutableIntStateOf(0) }

    // Percentage mode state
    var percentValue by remember { mutableStateOf("") }
    var selectedPreset by remember { mutableStateOf<Float?>(null) }

    // Absolute mode state
    var absoluteValue by remember { mutableStateOf("") }

    // Derive the current BulkAdjustMode from UI state
    val currentMode by remember(selectedTab, percentValue, selectedPreset, absoluteValue) {
        derivedStateOf {
            when (selectedTab) {
                0 -> {
                    val pct = selectedPreset ?: percentValue.toFloatOrNull()
                    pct?.let { BulkAdjustMode.Percentage(it) }
                }
                else -> {
                    val delta = absoluteValue.toFloatOrNull()
                    delta?.let { displayDelta ->
                        val deltaKg = when (weightUnit) {
                            WeightUnit.LB -> UnitConverter.lbToKg(displayDelta)
                            WeightUnit.KG -> displayDelta
                        }
                        BulkAdjustMode.Absolute(deltaKg)
                    }
                }
            }
        }
    }

    // Compute preview
    val preview by remember(currentMode, exercises) {
        derivedStateOf {
            currentMode?.let { mode -> applyBulkAdjust(exercises, mode) }
        }
    }

    // Count adjustable exercises that actually changed
    val changeCount by remember(preview, exercises) {
        derivedStateOf {
            if (preview == null) return@derivedStateOf 0
            exercises.zip(preview!!).count { (old, new) ->
                !old.usePercentOfPR && old.weightPerCableKg != new.weightPerCableKg
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adjust All Weights") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Tab row: Percentage | Absolute
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Percentage") },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Absolute") },
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Mode-specific input
                when (selectedTab) {
                    0 -> PercentageModeContent(
                        percentValue = percentValue,
                        selectedPreset = selectedPreset,
                        onPresetSelected = { preset ->
                            selectedPreset = preset
                            percentValue = ""
                        },
                        onCustomValueChange = { value ->
                            percentValue = value
                            selectedPreset = null
                        },
                    )
                    1 -> AbsoluteModeContent(
                        absoluteValue = absoluteValue,
                        onValueChange = { absoluteValue = it },
                        unitLabel = if (weightUnit == WeightUnit.LB) "lbs" else "kg",
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // Preview header
                Text(
                    text = "Preview",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )

                Spacer(Modifier.height(4.dp))

                // Preview list
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(exercises.indices.toList()) { index ->
                        val original = exercises[index]
                        val adjusted = preview?.getOrNull(index)
                        PreviewRow(
                            exercise = original,
                            newWeight = adjusted?.weightPerCableKg,
                            weightUnit = weightUnit,
                            formatWeight = formatWeight,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { preview?.let { onApply(it) } },
                enabled = changeCount > 0,
            ) {
                Text(if (changeCount > 0) "Apply ($changeCount)" else "Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

// ── Percentage mode content ─────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PercentageModeContent(
    percentValue: String,
    selectedPreset: Float?,
    onPresetSelected: (Float) -> Unit,
    onCustomValueChange: (String) -> Unit,
) {
    val presets = listOf(-10f, -5f, 5f, 10f)

    Column {
        // Preset chips
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { preset ->
                val isSelected = selectedPreset == preset
                val label = if (preset > 0) "+${preset.toInt()}%" else "${preset.toInt()}%"
                Surface(
                    modifier = Modifier.clickable { onPresetSelected(preset) },
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Custom percentage input
        OutlinedTextField(
            value = percentValue,
            onValueChange = onCustomValueChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Custom %") },
            placeholder = { Text("e.g. 15 or -7.5") },
            suffix = { Text("%") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )
    }
}

// ── Absolute mode content ───────────────────────────────────────────

@Composable
private fun AbsoluteModeContent(
    absoluteValue: String,
    onValueChange: (String) -> Unit,
    unitLabel: String,
) {
    OutlinedTextField(
        value = absoluteValue,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Weight change") },
        placeholder = { Text("e.g. 5 or -2.5") },
        suffix = { Text(unitLabel) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
    )
}

// ── Preview row ─────────────────────────────────────────────────────

@Composable
private fun PreviewRow(
    exercise: RoutineExercise,
    newWeight: Float?,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
) {
    val isPRScaled = exercise.usePercentOfPR
    val currentFormatted = formatWeight(exercise.weightPerCableKg, weightUnit)
    val isClamped = newWeight != null && (
        newWeight <= Constants.MIN_WEIGHT_KG || newWeight >= Constants.MAX_WEIGHT_KG
        )
    val hasChanged = newWeight != null && newWeight != exercise.weightPerCableKg

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Exercise name
        Text(
            text = exercise.exercise.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (isPRScaled) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )

        Spacer(Modifier.width(8.dp))

        // Weight change display
        when {
            isPRScaled -> {
                Text(
                    text = "PR-scaled",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
            hasChanged && newWeight != null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = currentFormatted,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = " \u2192 ", // →
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatWeight(newWeight, weightUnit),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = if (newWeight > exercise.weightPerCableKg) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    if (isClamped) {
                        Text(
                            text = " (clamped)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            else -> {
                Text(
                    text = currentFormatted,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
