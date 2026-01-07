# Exercise Configuration Modal Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Create a per-exercise configuration modal for predefined templates that allows users to configure mode-specific settings (weight, echo level, eccentric load) without modifying template-defined reps/sets/rest.

**Architecture:** Replace the current ModeConfirmationScreen's flat list of mode dropdowns with tappable exercise cards. When an exercise is tapped, show a modal with segmented mode selector and mode-specific configuration panels. Store configuration in a new `ExerciseConfig` data class that captures all mode-specific settings.

**Tech Stack:** Kotlin Multiplatform, Jetpack Compose, Material3, existing Phoenix theme

---

## Task 1: Create ExerciseConfig Data Class

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/ExerciseConfig.kt`

**Step 1: Create the data class**

```kotlin
package com.devil.phoenixproject.domain.model

/**
 * Configuration for a single exercise in a template.
 * Captures mode selection and mode-specific settings.
 * Excludes reps/sets/rest as those are template-defined.
 */
data class ExerciseConfig(
    val exerciseName: String,
    val mode: ProgramMode = ProgramMode.OldSchool,

    // Weight (used by all cable modes except Echo)
    val weightPerCableKg: Float = 0f,

    // OldSchool-specific
    val autoProgression: Boolean = true,

    // Eccentric-specific (also used by EccentricOnly mode)
    val eccentricLoadPercent: Int = 100, // 100-150%

    // Echo-specific
    val echoLevel: EchoLevel = EchoLevel.HARD
) {
    companion object {
        /**
         * Create default config for an exercise based on template suggestion.
         */
        fun fromTemplate(
            exerciseName: String,
            suggestedMode: ProgramMode?,
            oneRepMaxKg: Float? = null
        ): ExerciseConfig {
            val mode = suggestedMode ?: ProgramMode.OldSchool
            // Default weight is 70% of 1RM if available
            val weight = oneRepMaxKg?.let { (it * 0.70f * 2).toInt() / 2f } ?: 0f

            return ExerciseConfig(
                exerciseName = exerciseName,
                mode = mode,
                weightPerCableKg = weight
            )
        }
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :shared:compileDebugKotlinAndroid --quiet`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/model/ExerciseConfig.kt
git commit -m "feat: add ExerciseConfig data class for template exercise configuration"
```

---

## Task 2: Create Weight Stepper Component

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/WeightStepper.kt`

**Step 1: Create reusable weight input component**

```kotlin
package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.ui.theme.Spacing

/**
 * Weight input with +/- stepper buttons.
 * Increments/decrements by 2.5kg (standard plate increment).
 */
@Composable
fun WeightStepper(
    weight: Float,
    onWeightChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    minWeight: Float = 0f,
    maxWeight: Float = 220f,
    step: Float = 2.5f,
    label: String = "Weight"
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(Spacing.small))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLow,
                    RoundedCornerShape(Spacing.medium)
                )
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    RoundedCornerShape(Spacing.medium)
                )
                .padding(Spacing.extraSmall),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Minus button
            FilledTonalIconButton(
                onClick = {
                    val newWeight = (weight - step).coerceAtLeast(minWeight)
                    onWeightChange(newWeight)
                },
                enabled = weight > minWeight,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Decrease weight")
            }

            // Weight display
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (weight == weight.toLong().toFloat()) {
                        weight.toLong().toString()
                    } else {
                        String.format("%.1f", weight)
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "kg per cable",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Plus button
            FilledTonalIconButton(
                onClick = {
                    val newWeight = (weight + step).coerceAtMost(maxWeight)
                    onWeightChange(newWeight)
                },
                enabled = weight < maxWeight,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Increase weight")
            }
        }
    }
}
```

**Step 2: Add missing import**

Add at top of file: `import androidx.compose.ui.unit.sp`

**Step 3: Verify compilation**

Run: `./gradlew :shared:compileDebugKotlinAndroid --quiet`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/WeightStepper.kt
git commit -m "feat: add WeightStepper component for weight input"
```

---

## Task 3: Create Mode Selector Pills Component

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ModeSelector.kt`

**Step 1: Create segmented mode selector**

```kotlin
package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.ui.theme.Spacing

/**
 * Segmented pill selector for workout modes.
 * Shows all 5 program modes as tappable pills.
 */
@Composable
fun ModeSelector(
    selectedMode: ProgramMode,
    onModeSelected: (ProgramMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val modes = listOf(
        ProgramMode.OldSchool,
        ProgramMode.TUT,
        ProgramMode.Pump,
        ProgramMode.EccentricOnly,
        ProgramMode.Echo
    )

    Column(modifier = modifier) {
        Text(
            text = "WORKOUT MODE",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(Spacing.small))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLowest,
                    RoundedCornerShape(Spacing.medium)
                )
                .padding(Spacing.extraSmall),
            horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall)
        ) {
            modes.forEach { mode ->
                val isSelected = mode == selectedMode

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Spacing.small))
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLowest
                            }
                        )
                        .clickable { onModeSelected(mode) }
                        .padding(vertical = Spacing.small, horizontal = Spacing.extraSmall),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = getModeAbbreviation(mode),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Get short abbreviation for mode display in pills.
 */
private fun getModeAbbreviation(mode: ProgramMode): String = when (mode) {
    ProgramMode.OldSchool -> "OLD"
    ProgramMode.TUT -> "TUT"
    ProgramMode.Pump -> "PUMP"
    ProgramMode.EccentricOnly -> "ECC"
    ProgramMode.Echo -> "ECHO"
    else -> mode.displayName.take(4).uppercase()
}
```

**Step 2: Verify compilation**

Run: `./gradlew :shared:compileDebugKotlinAndroid --quiet`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ModeSelector.kt
git commit -m "feat: add ModeSelector segmented pill component"
```

---

## Task 4: Create ExerciseConfigModal Composable

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ExerciseConfigModal.kt`

**Step 1: Create the modal with mode-specific panels**

```kotlin
package com.devil.phoenixproject.presentation.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ExerciseConfig
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.ui.theme.Spacing

/**
 * Modal dialog for configuring a single exercise's mode and mode-specific settings.
 * Does NOT include reps/sets/rest - those come from the template.
 */
@Composable
fun ExerciseConfigModal(
    exerciseName: String,
    templateSets: Int,
    templateReps: Int?,
    oneRepMaxKg: Float?,
    initialConfig: ExerciseConfig,
    onConfirm: (ExerciseConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var config by remember { mutableStateOf(initialConfig) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = RoundedCornerShape(Spacing.large),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Header
                ExerciseConfigHeader(
                    exerciseName = exerciseName,
                    templateSets = templateSets,
                    templateReps = templateReps,
                    oneRepMaxKg = oneRepMaxKg
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Mode selector
                Column(
                    modifier = Modifier.padding(Spacing.medium)
                ) {
                    ModeSelector(
                        selectedMode = config.mode,
                        onModeSelected = { newMode ->
                            config = config.copy(mode = newMode)
                        }
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Mode-specific config panel
                AnimatedContent(
                    targetState = config.mode,
                    transitionSpec = {
                        fadeIn() + slideInVertically { it / 4 } togetherWith
                        fadeOut() + slideOutVertically { -it / 4 }
                    },
                    label = "mode_panel"
                ) { mode ->
                    Column(
                        modifier = Modifier.padding(Spacing.medium),
                        verticalArrangement = Arrangement.spacedBy(Spacing.medium)
                    ) {
                        when (mode) {
                            ProgramMode.OldSchool -> OldSchoolConfigPanel(
                                weight = config.weightPerCableKg,
                                onWeightChange = { config = config.copy(weightPerCableKg = it) },
                                autoProgression = config.autoProgression,
                                onAutoProgressionChange = { config = config.copy(autoProgression = it) }
                            )
                            ProgramMode.TUT -> TutConfigPanel(
                                weight = config.weightPerCableKg,
                                onWeightChange = { config = config.copy(weightPerCableKg = it) }
                            )
                            ProgramMode.Pump -> PumpConfigPanel(
                                weight = config.weightPerCableKg,
                                onWeightChange = { config = config.copy(weightPerCableKg = it) }
                            )
                            ProgramMode.EccentricOnly -> EccentricConfigPanel(
                                weight = config.weightPerCableKg,
                                onWeightChange = { config = config.copy(weightPerCableKg = it) },
                                eccentricPercent = config.eccentricLoadPercent,
                                onEccentricPercentChange = { config = config.copy(eccentricLoadPercent = it) }
                            )
                            ProgramMode.Echo -> EchoConfigPanel(
                                echoLevel = config.echoLevel,
                                onEchoLevelChange = { config = config.copy(echoLevel = it) },
                                eccentricPercent = config.eccentricLoadPercent,
                                onEccentricPercentChange = { config = config.copy(eccentricLoadPercent = it) }
                            )
                            else -> {
                                // Fallback for any future modes
                                WeightStepper(
                                    weight = config.weightPerCableKg,
                                    onWeightChange = { config = config.copy(weightPerCableKg = it) }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Footer buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.medium),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.small)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = { onConfirm(config) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Apply")
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseConfigHeader(
    exerciseName: String,
    templateSets: Int,
    templateReps: Int?,
    oneRepMaxKg: Float?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(Spacing.medium)
    ) {
        Text(
            text = exerciseName,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(Spacing.extraSmall))

        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.medium)
        ) {
            MetaChip(label = "Sets", value = templateSets.toString())
            MetaChip(label = "Reps", value = templateReps?.toString() ?: "AMRAP")
            oneRepMaxKg?.let {
                MetaChip(label = "1RM", value = "${it.toInt()}kg")
            }
        }
    }
}

@Composable
private fun MetaChip(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

// ==================== MODE-SPECIFIC PANELS ====================

@Composable
private fun OldSchoolConfigPanel(
    weight: Float,
    onWeightChange: (Float) -> Unit,
    autoProgression: Boolean,
    onAutoProgressionChange: (Boolean) -> Unit
) {
    WeightStepper(
        weight = weight,
        onWeightChange = onWeightChange,
        label = "Starting Weight"
    )

    // Auto-progression toggle
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Spacing.medium),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Auto Progression",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "+2.5% after each completed cycle",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Switch(
                checked = autoProgression,
                onCheckedChange = onAutoProgressionChange
            )
        }
    }
}

@Composable
private fun TutConfigPanel(
    weight: Float,
    onWeightChange: (Float) -> Unit
) {
    WeightStepper(
        weight = weight,
        onWeightChange = onWeightChange,
        label = "Starting Weight"
    )

    ModeInfoCard(
        title = "Time Under Tension",
        description = "Maintains constant resistance throughout the movement for maximum muscle engagement."
    )
}

@Composable
private fun PumpConfigPanel(
    weight: Float,
    onWeightChange: (Float) -> Unit
) {
    WeightStepper(
        weight = weight,
        onWeightChange = onWeightChange,
        label = "Starting Weight"
    )

    ModeInfoCard(
        title = "Pump Mode",
        description = "Uses shorter range of motion with sustained tension for maximum blood flow and muscle volume."
    )
}

@Composable
private fun EccentricConfigPanel(
    weight: Float,
    onWeightChange: (Float) -> Unit,
    eccentricPercent: Int,
    onEccentricPercentChange: (Int) -> Unit
) {
    WeightStepper(
        weight = weight,
        onWeightChange = onWeightChange,
        label = "Starting Weight"
    )

    EccentricSlider(
        percent = eccentricPercent,
        onPercentChange = onEccentricPercentChange,
        label = "Eccentric Overload"
    )
}

@Composable
private fun EchoConfigPanel(
    echoLevel: EchoLevel,
    onEchoLevelChange: (EchoLevel) -> Unit,
    eccentricPercent: Int,
    onEccentricPercentChange: (Int) -> Unit
) {
    // Echo Level selector
    Column {
        Text(
            text = "ECHO LEVEL",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(Spacing.small))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLowest,
                    RoundedCornerShape(Spacing.medium)
                )
                .padding(Spacing.extraSmall),
            horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall)
        ) {
            listOf(EchoLevel.HARD, EchoLevel.HARDER, EchoLevel.HARDEST).forEach { level ->
                val isSelected = level == echoLevel

                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(Spacing.small),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLowest
                    },
                    onClick = { onEchoLevelChange(level) }
                ) {
                    Text(
                        text = level.displayName,
                        modifier = Modifier.padding(vertical = Spacing.small),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    EccentricSlider(
        percent = eccentricPercent,
        onPercentChange = onEccentricPercentChange,
        label = "Eccentric Load"
    )
}

@Composable
private fun EccentricSlider(
    percent: Int,
    onPercentChange: (Int) -> Unit,
    label: String
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.sp
            )
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Spacer(modifier = Modifier.height(Spacing.small))

        Slider(
            value = percent.toFloat(),
            onValueChange = { onPercentChange(it.toInt()) },
            valueRange = 100f..150f,
            steps = 9, // 100, 105, 110... 150
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "100%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "150%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ModeInfoCard(
    title: String,
    description: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Spacing.medium),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier.padding(Spacing.medium)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(Spacing.extraSmall))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
```

**Step 2: Verify compilation**

Run: `./gradlew :shared:compileDebugKotlinAndroid --quiet`
Expected: BUILD SUCCESSFUL (may have minor fixes needed)

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ExerciseConfigModal.kt
git commit -m "feat: add ExerciseConfigModal with mode-specific panels"
```

---

## Task 5: Update ModeConfirmationScreen to Use Tappable Cards

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ModeConfirmationScreen.kt`

**Step 1: Update to collect ExerciseConfig map and show tappable cards**

Replace the entire file content with an updated version that:
1. Changes return type from `Map<String, ProgramMode>` to `Map<String, ExerciseConfig>`
2. Shows exercises as tappable cards instead of inline dropdowns
3. Opens ExerciseConfigModal on tap
4. Displays configured settings as badges on each card

**Key changes:**
- Change `onConfirm: (Map<String, ProgramMode>) -> Unit` to `onConfirm: (Map<String, ExerciseConfig>) -> Unit`
- Replace `mutableStateMapOf<String, ProgramMode>()` with `mutableStateMapOf<String, ExerciseConfig>()`
- Replace `ExerciseModeCard` with `ConfigurableExerciseCard` that shows mode badge and opens modal on tap
- Add state for `showConfigModal: TemplateExercise?`

**Step 2: Verify compilation**

Run: `./gradlew :shared:compileDebugKotlinAndroid --quiet`

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ModeConfirmationScreen.kt
git commit -m "refactor: update ModeConfirmationScreen to use ExerciseConfig and tappable cards"
```

---

## Task 6: Update TemplateConverter to Accept ExerciseConfig

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/TemplateConverter.kt`

**Step 1: Update convert() signature and implementation**

Change:
```kotlin
suspend fun convert(
    template: CycleTemplate,
    modeSelections: Map<String, ProgramMode> = emptyMap(),
    oneRepMaxValues: Map<String, Float> = emptyMap()
): ConversionResult
```

To:
```kotlin
suspend fun convert(
    template: CycleTemplate,
    exerciseConfigs: Map<String, ExerciseConfig> = emptyMap()
): ConversionResult
```

Update the RoutineExercise creation to use all ExerciseConfig properties:
- `workoutType` from `config.mode`
- `weightPerCableKg` from `config.weightPerCableKg`
- Add `eccentricLoadPercent` and `echoLevel` if RoutineExercise supports them (may need to extend)

**Step 2: Add import**

```kotlin
import com.devil.phoenixproject.domain.model.ExerciseConfig
```

**Step 3: Verify compilation**

Run: `./gradlew :shared:compileDebugKotlinAndroid --quiet`

**Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/domain/usecase/TemplateConverter.kt
git commit -m "refactor: update TemplateConverter to accept ExerciseConfig map"
```

---

## Task 7: Update TrainingCyclesScreen Flow

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/TrainingCyclesScreen.kt`

**Step 1: Update CycleCreationState to hold ExerciseConfig**

Change `ModeConfirmation` state:
```kotlin
data class ModeConfirmation(
    val template: CycleTemplate,
    val oneRepMaxValues: Map<String, Float>,
    val exerciseConfigs: Map<String, ExerciseConfig> = emptyMap()
) : CycleCreationState()
```

**Step 2: Update the flow to pass ExerciseConfig**

In the `ModeConfirmationScreen` `onConfirm` callback:
```kotlin
onConfirm = { exerciseConfigs ->
    // ... existing code ...
    val conversionResult = templateConverter.convert(
        template = state.template,
        exerciseConfigs = exerciseConfigs
    )
    // ... rest of save logic ...
}
```

**Step 3: Add import**

```kotlin
import com.devil.phoenixproject.domain.model.ExerciseConfig
```

**Step 4: Verify compilation**

Run: `./gradlew :shared:compileDebugKotlinAndroid --quiet`

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/TrainingCyclesScreen.kt
git commit -m "refactor: update TrainingCyclesScreen to use ExerciseConfig in creation flow"
```

---

## Task 8: Final Integration and Testing

**Files:**
- All modified files

**Step 1: Run full build**

Run: `./gradlew :androidApp:assembleDebug`
Expected: BUILD SUCCESSFUL

**Step 2: Manual testing checklist**

- [ ] Select Push/Pull/Legs template
- [ ] Tap an exercise in preview
- [ ] Modal opens with correct exercise name
- [ ] Mode pills switch between modes
- [ ] Each mode shows correct config panel
- [ ] Weight stepper increments/decrements by 2.5kg
- [ ] Echo level selector switches levels
- [ ] Eccentric slider moves 100-150%
- [ ] Apply saves config and closes modal
- [ ] Cancel discards changes
- [ ] Create cycle completes without FK error
- [ ] Created routines have correct weights/modes

**Step 3: Final commit**

```bash
git add .
git commit -m "feat: complete exercise config modal for template workflows"
```

---

## Summary

| Task | Component | Estimated Size |
|------|-----------|----------------|
| 1 | ExerciseConfig data class | Small |
| 2 | WeightStepper component | Small |
| 3 | ModeSelector component | Small |
| 4 | ExerciseConfigModal | Medium |
| 5 | ModeConfirmationScreen update | Medium |
| 6 | TemplateConverter update | Small |
| 7 | TrainingCyclesScreen update | Small |
| 8 | Integration testing | Manual |

**Dependencies:**
- Task 1 must complete before Tasks 4-7
- Tasks 2-3 must complete before Task 4
- Task 4 must complete before Task 5
- Tasks 5-6 must complete before Task 7
