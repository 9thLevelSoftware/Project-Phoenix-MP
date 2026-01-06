package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.domain.model.EccentricLoad
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.WorkoutType
import com.devil.phoenixproject.ui.theme.Spacing

/**
 * UI representation of selectable modes.
 * Includes both ProgramModes and Echo.
 */
enum class SelectableMode(val abbreviation: String, val displayName: String) {
    OLD_SCHOOL("OLD", "Old School"),
    TUT("TUT", "TUT"),
    PUMP("PUMP", "Pump"),
    ECCENTRIC("ECC", "Eccentric"),
    TUT_BEAST("BEAST", "TUT Beast"),
    ECHO("ECHO", "Echo");

    fun toWorkoutType(
        echoLevel: EchoLevel = EchoLevel.HARD,
        eccentricLoad: EccentricLoad = EccentricLoad.LOAD_100
    ): WorkoutType = when (this) {
        OLD_SCHOOL -> WorkoutType.Program(ProgramMode.OldSchool)
        TUT -> WorkoutType.Program(ProgramMode.TUT)
        PUMP -> WorkoutType.Program(ProgramMode.Pump)
        ECCENTRIC -> WorkoutType.Program(ProgramMode.EccentricOnly)
        TUT_BEAST -> WorkoutType.Program(ProgramMode.TUTBeast)
        ECHO -> WorkoutType.Echo(echoLevel, eccentricLoad)
    }

    companion object {
        fun fromWorkoutType(workoutType: WorkoutType): SelectableMode = when (workoutType) {
            is WorkoutType.Program -> when (workoutType.mode) {
                ProgramMode.OldSchool -> OLD_SCHOOL
                ProgramMode.TUT -> TUT
                ProgramMode.Pump -> PUMP
                ProgramMode.EccentricOnly -> ECCENTRIC
                ProgramMode.TUTBeast -> TUT_BEAST
            }
            is WorkoutType.Echo -> ECHO
        }
    }
}

/**
 * Segmented pill selector for workout modes.
 * Shows all 6 modes (5 ProgramModes + Echo) as tappable pills.
 */
@Composable
fun ModeSelector(
    selectedMode: SelectableMode,
    onModeSelected: (SelectableMode) -> Unit,
    modifier: Modifier = Modifier
) {
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
            SelectableMode.entries.forEach { mode ->
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
                        text = mode.abbreviation,
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
