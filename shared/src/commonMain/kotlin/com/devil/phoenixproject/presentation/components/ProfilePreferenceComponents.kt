package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.CoreProfilePreferences
import com.devil.phoenixproject.domain.model.LedPreferences
import com.devil.phoenixproject.domain.model.ProfileLocalSafetyPreferences
import com.devil.phoenixproject.domain.model.ScalingBasis
import com.devil.phoenixproject.domain.model.UserProfilePreferences
import com.devil.phoenixproject.domain.model.VbtPreferences
import com.devil.phoenixproject.domain.model.VulgarTier
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutPreferences
import com.devil.phoenixproject.presentation.viewmodel.ProfilePreferenceSection
import com.devil.phoenixproject.util.ColorSchemes
import com.devil.phoenixproject.util.Constants
import com.devil.phoenixproject.util.KmpUtils
import com.devil.phoenixproject.util.UnitConverter
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.*

@Composable
fun ProfilePreferenceSections(
    profileId: String,
    preferences: UserProfilePreferences,
    localSafety: ProfileLocalSafetyPreferences,
    importedBodyWeightMeasuredAt: Long?,
    busySections: Set<ProfilePreferenceSection>,
    isConnected: Boolean,
    discoModeActive: Boolean,
    onCoreChange: (CoreProfilePreferences) -> Long?,
    onWorkoutChange: (WorkoutPreferences) -> Long?,
    onLedChange: (LedPreferences) -> Long?,
    onVbtChange: (VbtPreferences) -> Long?,
    onLocalSafetyChange: (ProfileLocalSafetyPreferences) -> Long?,
    onRequestAdultsOnlyConfirmation: () -> Unit,
    onUnlockDiscoMode: () -> Long?,
    onUnlockDominatrixMode: () -> Long?,
    onManageEquipmentRack: () -> Unit,
    onDiscoModeToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val core = preferences.core.value
    val rack = preferences.rack.value
    val workout = preferences.workout.value
    val led = preferences.led.value
    val vbt = preferences.vbt.value

    key(profileId) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        MeasurementsPreferenceCard(
            profileId = profileId,
            core = core,
            importedBodyWeightMeasuredAt = importedBodyWeightMeasuredAt,
            enabled = ProfilePreferenceSection.CORE !in busySections,
            onCoreChange = onCoreChange,
        )
        PreferenceCard(title = stringResource(Res.string.equipment_rack_title)) {
            val enabledItems = rack.items.count { it.enabled }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(onClick = onManageEquipmentRack)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.FitnessCenter,
                    contentDescription = stringResource(Res.string.cd_equipment_rack),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(Res.string.equipment_rack_manage))
                    Text(
                        text = "$enabledItems/${rack.items.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null)
            }
        }
        WorkoutPreferenceCard(
            profileId = profileId,
            workout = workout,
            busySections = busySections,
            onWorkoutChange = onWorkoutChange,
        )
        LedPreferenceCard(
            profileId = profileId,
            led = led,
            busySections = busySections,
            isConnected = isConnected,
            discoModeActive = discoModeActive,
            onLedChange = onLedChange,
            onUnlockDiscoMode = onUnlockDiscoMode,
            onDiscoModeToggle = onDiscoModeToggle,
        )
        VbtPreferenceCard(
            profileId = profileId,
            vbt = vbt,
            localSafety = localSafety,
            busySections = busySections,
            onVbtChange = onVbtChange,
            onRequestAdultsOnlyConfirmation = onRequestAdultsOnlyConfirmation,
            onUnlockDominatrixMode = onUnlockDominatrixMode,
        )
            SafetyPreferenceCard(
            profileId = profileId,
            localSafety = localSafety,
            voiceStopEnabled = workout.voiceStopEnabled,
                busySections = busySections,
                onLocalSafetyChange = onLocalSafetyChange,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun MeasurementsPreferenceCard(
    profileId: String,
    core: CoreProfilePreferences,
    importedBodyWeightMeasuredAt: Long?,
    enabled: Boolean,
    onCoreChange: (CoreProfilePreferences) -> Long?,
) {
    val authoritativeBodyWeight = core.bodyWeightKg
    var bodyWeightDraft by rememberSaveable(profileId, core.weightUnit, authoritativeBodyWeight) {
        mutableStateOf(displayBodyWeight(authoritativeBodyWeight, core.weightUnit))
    }
    var bodyWeightDraftEdited by rememberSaveable(
        profileId,
        core.weightUnit,
        authoritativeBodyWeight,
    ) {
        mutableStateOf(false)
    }
    LaunchedEffect(profileId, core.weightUnit, authoritativeBodyWeight) {
        bodyWeightDraft = displayBodyWeight(authoritativeBodyWeight, core.weightUnit)
        bodyWeightDraftEdited = false
    }
    val bodyWeightKgToSave = bodyWeightKgForSave(
        authoritativeBodyWeightKg = authoritativeBodyWeight,
        draft = bodyWeightDraft,
        weightUnit = core.weightUnit,
        draftEdited = bodyWeightDraftEdited,
    )
    val bodyWeightInvalid = bodyWeightKgToSave == null

    PreferenceCard(title = stringResource(Res.string.profile_measurements)) {
        Text(
            text = stringResource(Res.string.settings_weight_unit),
            style = MaterialTheme.typography.labelLarge,
        )
        ChoiceChips(
            options = listOf(
                WeightUnit.KG to stringResource(Res.string.label_kg),
                WeightUnit.LB to stringResource(Res.string.label_lbs),
            ),
            selected = core.weightUnit,
            enabled = enabled,
            onSelected = { onCoreChange(coreAfterWeightUnitSelection(core, it)) },
        )

        HorizontalDivider()
        Text(
            text = stringResource(Res.string.profile_weight_increment),
            style = MaterialTheme.typography.labelLarge,
        )
        val unitLabel = if (core.weightUnit == WeightUnit.KG) {
            stringResource(Res.string.label_kg)
        } else {
            stringResource(Res.string.label_lbs)
        }
        val incrementOptions = weightIncrementOptionsFor(core.weightUnit)
        val selectedIncrement = displayedWeightIncrement(core)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            incrementOptions.forEach { increment ->
                FilterChip(
                    selected = abs(selectedIncrement - increment) < 0.001f,
                    onClick = { onCoreChange(core.copy(weightIncrement = increment)) },
                    enabled = enabled,
                    label = {
                        Text("${UnitConverter.formatDecimal(increment)} $unitLabel")
                    },
                )
            }
        }

        HorizontalDivider()
        Text(
            text = stringResource(Res.string.profile_body_weight),
            style = MaterialTheme.typography.labelLarge,
        )
        OutlinedTextField(
            bodyWeightDraft,
            { candidate ->
                if (candidate.matches(Regex("^\\d{0,3}(\\.\\d{0,2})?$"))) {
                    bodyWeightDraft = candidate
                    bodyWeightDraftEdited = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            label = {
                Text(
                    if (authoritativeBodyWeight > 0f) {
                        unitLabel
                    } else {
                        stringResource(Res.string.profile_body_weight_unset)
                    },
                )
            },
            isError = bodyWeightDraft.isNotBlank() && bodyWeightInvalid,
            supportingText = {
                when {
                    bodyWeightDraft.isNotBlank() && bodyWeightInvalid -> {
                        Text(stringResource(Res.string.profile_body_weight_invalid))
                    }
                    importedBodyWeightMeasuredAt != null -> {
                        Text(stringResource(Res.string.profile_body_weight_imported))
                    }
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (bodyWeightKgToSave != null) {
                        onCoreChange(core.copy(bodyWeightKg = bodyWeightKgToSave))
                    }
                },
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                onClick = {
                    bodyWeightDraft = ""
                    bodyWeightDraftEdited = true
                    onCoreChange(core.copy(bodyWeightKg = 0f))
                },
                enabled = enabled && authoritativeBodyWeight > 0f,
            ) {
                Text(stringResource(Res.string.action_clear))
            }
            Button(
                onClick = {
                    if (bodyWeightKgToSave != null) {
                        onCoreChange(core.copy(bodyWeightKg = bodyWeightKgToSave))
                    }
                },
                enabled = enabled && !bodyWeightInvalid,
            ) {
                Text(stringResource(Res.string.action_save))
            }
        }
    }
}

@Composable
private fun WorkoutPreferenceCard(
    profileId: String,
    workout: WorkoutPreferences,
    busySections: Set<ProfilePreferenceSection>,
    onWorkoutChange: (WorkoutPreferences) -> Long?,
) {
    val enabled = ProfilePreferenceSection.WORKOUT !in busySections
    val authoritativePercentOfPr = workout.defaultRoutineExerciseWeightPercentOfPR.toFloat()
    var percentOfPrDraft by rememberSaveable(profileId, authoritativePercentOfPr) {
        mutableFloatStateOf(authoritativePercentOfPr)
    }
    LaunchedEffect(profileId, authoritativePercentOfPr) {
        percentOfPrDraft = authoritativePercentOfPr
    }

    PreferenceCard(title = stringResource(Res.string.profile_workout_behavior)) {
        IntegerChoiceRow(
            label = stringResource(Res.string.profile_set_summary),
            value = workout.summaryCountdownSeconds,
            options = listOf(-1, 0, 5, 10, 15, 20, 25, 30),
            enabled = enabled,
            valueLabel = { seconds ->
                if (seconds == -1) stringResource(Res.string.profile_automatic) else "${seconds}s"
            },
            onSelected = { onWorkoutChange(workout.copy(summaryCountdownSeconds = it)) },
        )
        IntegerChoiceRow(
            label = stringResource(Res.string.profile_autostart_countdown),
            value = workout.autoStartCountdownSeconds,
            options = (2..10).toList(),
            enabled = enabled,
            valueLabel = { "${it}s" },
            onSelected = { onWorkoutChange(workout.copy(autoStartCountdownSeconds = it)) },
        )
        PreferenceSwitchRow(
            label = stringResource(Res.string.profile_auto_start_routine),
            checked = workout.autoStartRoutine,
            enabled = enabled,
            onCheckedChange = { onWorkoutChange(workout.copy(autoStartRoutine = it)) },
        )
        PreferenceSwitchRow(
            label = stringResource(Res.string.profile_motion_start),
            checked = workout.motionStartEnabled,
            enabled = enabled,
            onCheckedChange = { onWorkoutChange(workout.copy(motionStartEnabled = it)) },
        )
        PreferenceSwitchRow(
            label = stringResource(Res.string.profile_master_beeps),
            checked = workout.beepsEnabled,
            enabled = enabled,
            onCheckedChange = { onWorkoutChange(workout.copy(beepsEnabled = it)) },
        )
        PreferenceSwitchRow(
            label = stringResource(Res.string.profile_audio_rep_counter),
            checked = workout.audioRepCountEnabled,
            enabled = enabled,
            onCheckedChange = { onWorkoutChange(workout.copy(audioRepCountEnabled = it)) },
        )
        PreferenceSwitchRow(
            label = stringResource(Res.string.profile_countdown_beeps),
            checked = workout.countdownBeepsEnabled,
            enabled = enabled && workout.beepsEnabled,
            onCheckedChange = { onWorkoutChange(workout.copy(countdownBeepsEnabled = it)) },
        )
        PreferenceSwitchRow(
            label = stringResource(Res.string.profile_rep_completion_sound),
            checked = workout.repSoundEnabled,
            enabled = enabled && workout.beepsEnabled,
            onCheckedChange = { onWorkoutChange(workout.copy(repSoundEnabled = it)) },
        )
        PreferenceSwitchRow(
            label = stringResource(Res.string.profile_gamification),
            checked = workout.gamificationEnabled,
            enabled = enabled,
            onCheckedChange = { onWorkoutChange(workout.copy(gamificationEnabled = it)) },
        )
        PreferenceSwitchRow(
            label = stringResource(Res.string.settings_weight_suggestions_title),
            checked = workout.weightSuggestionsEnabled,
            enabled = enabled,
            onCheckedChange = { onWorkoutChange(workout.copy(weightSuggestionsEnabled = it)) },
        )
        PreferenceSwitchRow(
            label = stringResource(Res.string.profile_routine_starting_weights),
            supporting = stringResource(
                Res.string.profile_routine_starting_weights_description,
            ),
            checked = workout.defaultRoutineExerciseUsePercentOfPR,
            enabled = enabled,
            onCheckedChange = {
                onWorkoutChange(workout.copy(defaultRoutineExerciseUsePercentOfPR = it))
            },
        )
        if (workout.defaultRoutineExerciseUsePercentOfPR) {
            Text(
                text = "${percentOfPrDraft.roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Slider(
                value = percentOfPrDraft,
                onValueChange = { percentOfPrDraft = ((it / 5f).roundToInt() * 5).toFloat() },
                onValueChangeFinished = {
                    onWorkoutChange(
                        workout.copy(
                            defaultRoutineExerciseWeightPercentOfPR = percentOfPrDraft.roundToInt(),
                        ),
                    )
                },
                valueRange = 50f..120f,
                steps = 13,
                enabled = ProfilePreferenceSection.WORKOUT !in busySections,
            )
        }
        PreferenceSwitchRow(
            label = stringResource(Res.string.settings_voice_stop_enable),
            supporting = if (workout.voiceStopEnabled) {
                stringResource(Res.string.settings_voice_stop_description)
            } else {
                null
            },
            checked = workout.voiceStopEnabled,
            enabled = enabled,
            onCheckedChange = { onWorkoutChange(workout.copy(voiceStopEnabled = it)) },
        )
    }
}

@Composable
private fun LedPreferenceCard(
    profileId: String,
    led: LedPreferences,
    busySections: Set<ProfilePreferenceSection>,
    isConnected: Boolean,
    discoModeActive: Boolean,
    onLedChange: (LedPreferences) -> Long?,
    onUnlockDiscoMode: () -> Long?,
    onDiscoModeToggle: (Boolean) -> Unit,
) {
    val enabled = ProfilePreferenceSection.LED !in busySections
    var tapCount by rememberSaveable(profileId, led.discoModeUnlocked, enabled) {
        mutableIntStateOf(0)
    }
    var firstTapAt by rememberSaveable(profileId, led.discoModeUnlocked, enabled) {
        mutableLongStateOf(0L)
    }
    val schemes = listOf(
        0 to Res.string.profile_led_scheme_blue,
        1 to Res.string.profile_led_scheme_green,
        2 to Res.string.profile_led_scheme_teal,
        3 to Res.string.profile_led_scheme_yellow,
        4 to Res.string.profile_led_scheme_pink,
        5 to Res.string.profile_led_scheme_red,
        6 to Res.string.profile_led_scheme_purple,
        7 to Res.string.profile_led_scheme_none,
    )
    val selectedScheme = normalizedLedSchemeIndex(led.colorScheme, schemes.size)
    val selectedSchemeName = stringResource(schemes[selectedScheme].second)

    PreferenceCard(
        title = stringResource(Res.string.profile_led),
        titleModifier = Modifier.clickable(enabled = enabled && !led.discoModeUnlocked) {
            val now = KmpUtils.currentTimeMillis()
            if (firstTapAt == 0L || now - firstTapAt > 2000L) {
                firstTapAt = now
                tapCount = 1
            } else {
                tapCount += 1
            }
            if (tapCount >= 7) {
                onUnlockDiscoMode()
                tapCount = 0
                firstTapAt = 0L
            }
        },
    ) {
        Text(
            text = stringResource(Res.string.profile_led_selected_scheme, selectedSchemeName),
            style = MaterialTheme.typography.labelLarge,
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(schemes, key = { it.first }) { (index, labelResource) ->
                val label = stringResource(labelResource)
                val selected = selectedScheme == index
                val schemeDescription = stringResource(Res.string.cd_select_led_scheme, label)
                val scheme = ColorSchemes.ALL[index]
                val gradient = Brush.linearGradient(
                    colors = scheme.colors.map { Color(it.r, it.g, it.b) },
                )
                val offCrossColor = MaterialTheme.colorScheme.onSurface
                Box(
                    modifier = Modifier.size(48.dp)
                        .semantics { contentDescription = schemeDescription }
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onLedChange(led.copy(colorScheme = index)) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier.size(40.dp)
                            .clip(CircleShape)
                            .background(gradient)
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outline
                                },
                                shape = CircleShape,
                            ),
                    )
                    if (index == ColorSchemes.ALL.lastIndex) {
                        Canvas(Modifier.size(28.dp)) {
                            drawLine(
                                color = offCrossColor,
                                start = androidx.compose.ui.geometry.Offset(2f, 2f),
                                end = androidx.compose.ui.geometry.Offset(size.width - 2f, size.height - 2f),
                                strokeWidth = 3.dp.toPx(),
                            )
                        }
                    }
                    if (selected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = Color.White,
                        )
                    }
                }
            }
        }
        if (led.discoModeUnlocked) {
            PreferenceSwitchRow(
                label = stringResource(Res.string.profile_disco_mode),
                supporting = if (isConnected) {
                    null
                } else {
                    stringResource(Res.string.profile_disco_requires_connection)
                },
                checked = discoModeActive,
                enabled = isConnected,
                onCheckedChange = onDiscoModeToggle,
            )
        }
    }
}

@Composable
private fun VbtPreferenceCard(
    profileId: String,
    vbt: VbtPreferences,
    localSafety: ProfileLocalSafetyPreferences,
    busySections: Set<ProfilePreferenceSection>,
    onVbtChange: (VbtPreferences) -> Long?,
    onRequestAdultsOnlyConfirmation: () -> Unit,
    onUnlockDominatrixMode: () -> Long?,
) {
    val sectionEnabled = ProfilePreferenceSection.VBT !in busySections
    val authoritativeVelocityThreshold = vbt.velocityLossThresholdPercent.toFloat()
    var velocityThresholdDraft by rememberSaveable(profileId, authoritativeVelocityThreshold) {
        mutableFloatStateOf(authoritativeVelocityThreshold)
    }
    LaunchedEffect(profileId, authoritativeVelocityThreshold) {
        velocityThresholdDraft = authoritativeVelocityThreshold
    }
    val dominatrixUnlockEligible = localSafety.adultsOnlyConfirmed && vbt.enabled &&
        vbt.verbalEncouragementEnabled && vbt.vulgarModeEnabled && !vbt.dominatrixModeUnlocked
    var dominatrixTapCount by rememberSaveable(
        profileId,
        dominatrixUnlockEligible,
        vbt.dominatrixModeUnlocked,
    ) { mutableIntStateOf(0) }
    var dominatrixFirstTapAt by rememberSaveable(
        profileId,
        dominatrixUnlockEligible,
        vbt.dominatrixModeUnlocked,
    ) { mutableLongStateOf(0L) }

    PreferenceCard(
        title = stringResource(Res.string.profile_vbt),
        titleModifier = Modifier.clickable(
            enabled = sectionEnabled && dominatrixUnlockEligible,
        ) {
            val now = KmpUtils.currentTimeMillis()
            if (dominatrixFirstTapAt == 0L || now - dominatrixFirstTapAt > 2000L) {
                dominatrixFirstTapAt = now
                dominatrixTapCount = 1
            } else {
                dominatrixTapCount += 1
            }
            if (dominatrixTapCount >= 7) {
                onUnlockDominatrixMode()
                dominatrixTapCount = 0
                dominatrixFirstTapAt = 0L
            }
        },
    ) {
        PreferenceSwitchRow(
            label = stringResource(Res.string.profile_vbt_enabled),
            checked = vbt.enabled,
            enabled = sectionEnabled,
            onCheckedChange = { onVbtChange(vbtAfterEnabledSelection(vbt, it)) },
        )
        Text(
            stringResource(Res.string.profile_vbt_history_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(Res.string.profile_velocity_loss_threshold),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = "${velocityThresholdDraft.roundToInt()}%",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Slider(
            value = velocityThresholdDraft,
            onValueChange = { velocityThresholdDraft = it.roundToInt().toFloat() },
            onValueChangeFinished = {
                onVbtChange(
                    vbt.copy(
                        velocityLossThresholdPercent = velocityThresholdDraft.roundToInt(),
                    ),
                )
            },
            valueRange = 10f..50f,
            steps = 39,
            enabled = ProfilePreferenceSection.VBT !in busySections && vbt.enabled,
        )
        PreferenceSwitchRow(
            label = stringResource(Res.string.profile_auto_end_velocity_loss),
            checked = vbt.autoEndOnVelocityLoss,
            enabled = sectionEnabled && vbt.enabled,
            onCheckedChange = { onVbtChange(vbt.copy(autoEndOnVelocityLoss = it)) },
        )
        ChoiceChips(
            label = stringResource(Res.string.profile_default_scaling_basis),
            options = listOf(
                ScalingBasis.MAX_WEIGHT_PR to stringResource(Res.string.profile_pr_max_weight),
                ScalingBasis.MAX_VOLUME_PR to stringResource(Res.string.profile_pr_max_volume),
                ScalingBasis.ESTIMATED_1RM to stringResource(Res.string.profile_pr_estimated_one_rep_max),
            ),
            selected = vbt.defaultScalingBasis,
            enabled = sectionEnabled && vbt.enabled,
            onSelected = { onVbtChange(vbt.copy(defaultScalingBasis = it)) },
        )
        PreferenceSwitchRow(
            label = stringResource(Res.string.settings_verbal_encouragement_title),
            checked = vbt.verbalEncouragementEnabled,
            enabled = sectionEnabled && vbt.enabled,
            onCheckedChange = { requested ->
                onVbtChange(vbtAfterVerbalEncouragementSelection(vbt, requested))
            },
        )
        PreferenceSwitchRow(
            label = stringResource(Res.string.settings_vulgar_mode_title),
            supporting = stringResource(Res.string.settings_vulgar_mode_headphone_warning),
            checked = vbt.vulgarModeEnabled,
            enabled = sectionEnabled && vbt.enabled && vbt.verbalEncouragementEnabled,
            onCheckedChange = { requested ->
                if (requested) {
                    if (localSafety.adultsOnlyConfirmed) {
                        onVbtChange(vbtAfterVulgarModeSelection(vbt, true))
                    } else {
                        onRequestAdultsOnlyConfirmation()
                    }
                } else {
                    onVbtChange(vbtAfterVulgarModeSelection(vbt, false))
                }
            },
        )
        ChoiceChips(
            label = stringResource(Res.string.settings_vulgar_mode_tier_label),
            options = listOf(
                VulgarTier.MILD to stringResource(Res.string.settings_vulgar_mode_tier_mild),
                VulgarTier.STRONG to stringResource(Res.string.settings_vulgar_mode_tier_strong),
                VulgarTier.MIX to stringResource(Res.string.settings_vulgar_mode_tier_mix),
            ),
            selected = vbt.vulgarTier,
            enabled = sectionEnabled && vbt.enabled && vbt.verbalEncouragementEnabled && localSafety.adultsOnlyConfirmed && vbt.vulgarModeEnabled,
            onSelected = { onVbtChange(vbt.copy(vulgarTier = it)) },
        )
        val dominatrixEnabled = localSafety.adultsOnlyConfirmed && vbt.enabled &&
            vbt.verbalEncouragementEnabled && vbt.vulgarModeEnabled &&
            vbt.dominatrixModeUnlocked
        if (vbt.dominatrixModeUnlocked) {
            PreferenceSwitchRow(
                label = stringResource(Res.string.settings_dominatrix_mode_title),
                supporting = stringResource(Res.string.settings_dominatrix_mode_description),
                checked = vbt.dominatrixModeActive,
                enabled = sectionEnabled && dominatrixEnabled,
                onCheckedChange = { onVbtChange(vbt.copy(dominatrixModeActive = it)) },
            )
        }
    }
}

@Composable
private fun SafetyPreferenceCard(
    profileId: String,
    localSafety: ProfileLocalSafetyPreferences,
    voiceStopEnabled: Boolean,
    busySections: Set<ProfilePreferenceSection>,
    onLocalSafetyChange: (ProfileLocalSafetyPreferences) -> Long?,
) {
    val enabled = ProfilePreferenceSection.LOCAL_SAFETY !in busySections
    val authoritativeSafeWord = localSafety.safeWord.orEmpty()
    var safeWordDraft by rememberSaveable(profileId, authoritativeSafeWord) {
        mutableStateOf(authoritativeSafeWord)
    }
    var showCalibrationDialog by rememberSaveable(profileId) { mutableStateOf(false) }
    LaunchedEffect(profileId, authoritativeSafeWord) { safeWordDraft = authoritativeSafeWord }

    PreferenceCard(title = stringResource(Res.string.profile_safety)) {
        if (voiceStopEnabled) {
            Text(
                stringResource(Res.string.settings_voice_stop_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            safeWordDraft,
            { safeWordDraft = it.take(40) },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = true,
            label = { Text(stringResource(Res.string.settings_safe_word_label)) },
            placeholder = { Text(stringResource(Res.string.settings_safe_word_hint)) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            OutlinedButton(
                onClick = { showCalibrationDialog = true },
                enabled = enabled && safeWordDraft.isNotBlank(),
            ) {
                Text(stringResource(Res.string.settings_calibrate_button))
            }
            Button(
                onClick = {
                    val normalized = safeWordDraft.trim().takeIf { it.isNotEmpty() }
                    onLocalSafetyChange(
                        localSafety.copy(
                            safeWord = normalized,
                            safeWordCalibrated = localSafety.safeWordCalibrated &&
                                normalized == localSafety.safeWord,
                        ),
                    )
                },
                enabled = enabled,
            ) {
                Text(stringResource(Res.string.action_save))
            }
        }
        if (localSafety.safeWordCalibrated) {
            Text(
                stringResource(Res.string.settings_calibrated_badge),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        } else {
            Text(
                stringResource(Res.string.settings_calibrate_first),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showCalibrationDialog && safeWordDraft.isNotBlank()) {
        SafeWordCalibrationDialog(
            safeWord = safeWordDraft.trim(),
            onCalibrated = {
                onLocalSafetyChange(
                    localSafety.copy(
                        safeWord = safeWordDraft.trim(),
                        safeWordCalibrated = true,
                    ),
                )
                showCalibrationDialog = false
            },
            onDismiss = { showCalibrationDialog = false },
        )
    }
}

@Composable
private fun PreferenceCard(
    title: String,
    titleModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = titleModifier.fillMaxWidth().heightIn(min = 48.dp)
                    .padding(vertical = 12.dp),
            )
            content()
        }
    }
}

@Composable
private fun PreferenceSwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    supporting: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            supporting?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun <T> ChoiceChips(
    options: List<Pair<T, String>>,
    selected: T,
    enabled: Boolean,
    onSelected: (T) -> Unit,
    label: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        label?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelected(value) },
                    enabled = enabled,
                    label = { Text(text) },
                )
            }
        }
    }
}

@Composable
private fun IntegerChoiceRow(
    label: String,
    value: Int,
    options: List<Int>,
    enabled: Boolean,
    valueLabel: @Composable (Int) -> String,
    onSelected: (Int) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Box {
            TextButton(onClick = { expanded = true }, enabled = enabled) {
                Text(valueLabel(value))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(valueLabel(option)) },
                        onClick = {
                            expanded = false
                            onSelected(option)
                        },
                    )
                }
            }
        }
    }
}

private fun displayBodyWeight(bodyWeightKg: Float, unit: WeightUnit): String {
    if (!bodyWeightKg.isFinite() || bodyWeightKg <= 0f) return ""
    val display = if (unit == WeightUnit.LB) UnitConverter.kgToLb(bodyWeightKg) else bodyWeightKg
    return UnitConverter.formatDecimal(display)
}

internal fun coreAfterWeightUnitSelection(
    core: CoreProfilePreferences,
    selectedUnit: WeightUnit,
): CoreProfilePreferences {
    if (selectedUnit == core.weightUnit) return core
    val defaultIncrement = when (selectedUnit) {
        WeightUnit.KG -> Constants.DEFAULT_WEIGHT_INCREMENT_KG
        WeightUnit.LB -> Constants.DEFAULT_WEIGHT_INCREMENT_LB
    }
    return core.copy(weightUnit = selectedUnit, weightIncrement = defaultIncrement)
}

internal fun weightIncrementOptionsFor(weightUnit: WeightUnit): List<Float> = when (weightUnit) {
    WeightUnit.KG -> Constants.WEIGHT_INCREMENT_OPTIONS_KG
    WeightUnit.LB -> Constants.WEIGHT_INCREMENT_OPTIONS_LB
}

internal fun displayedWeightIncrement(core: CoreProfilePreferences): Float =
    core.weightIncrement.takeIf { it >= 0f } ?: when (core.weightUnit) {
        WeightUnit.KG -> Constants.DEFAULT_WEIGHT_INCREMENT_KG
        WeightUnit.LB -> Constants.DEFAULT_WEIGHT_INCREMENT_LB
    }

internal fun vbtAfterEnabledSelection(vbt: VbtPreferences, enabled: Boolean): VbtPreferences =
    if (enabled) {
        vbt.copy(enabled = true)
    } else {
        vbt.copy(enabled = false, dominatrixModeActive = false)
    }

internal fun vbtAfterVerbalEncouragementSelection(
    vbt: VbtPreferences,
    enabled: Boolean,
): VbtPreferences = if (enabled) {
    vbt.copy(verbalEncouragementEnabled = true)
} else {
    vbt.copy(
        verbalEncouragementEnabled = false,
        vulgarModeEnabled = false,
        dominatrixModeActive = false,
    )
}

internal fun vbtAfterVulgarModeSelection(
    vbt: VbtPreferences,
    enabled: Boolean,
): VbtPreferences = if (enabled) {
    vbt.copy(vulgarModeEnabled = true)
} else {
    vbt.copy(vulgarModeEnabled = false, dominatrixModeActive = false)
}

internal fun bodyWeightKgForSave(
    authoritativeBodyWeightKg: Float,
    draft: String,
    weightUnit: WeightUnit,
    draftEdited: Boolean,
): Float? {
    if (draft.isBlank()) return null
    val bodyWeightKg = if (draftEdited) {
        val displayWeight = draft.toFloatOrNull() ?: return null
        if (weightUnit == WeightUnit.LB) UnitConverter.lbToKg(displayWeight) else displayWeight
    } else {
        authoritativeBodyWeightKg
    }
    return bodyWeightKg.takeIf { it.isFinite() && it in 20f..300f }
}
