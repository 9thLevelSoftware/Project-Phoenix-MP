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
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.ui.theme.Spacing

/**
 * Segmented pill selector for workout modes.
 * Shows 5 program modes as tappable pills (Beast Mode is a TUT sub-option).
 * TUTBeast is internally mapped to TUT pill - beast toggle is shown in the config panel.
 */
@Composable
fun ModeSelector(
    selectedMode: ProgramMode,
    onModeSelected: (ProgramMode) -> Unit,
    modifier: Modifier = Modifier
) {
    // Only show 5 modes in the selector - Beast Mode is a TUT sub-option
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
                // TUTBeast should highlight TUT pill
                val isSelected = mode == selectedMode ||
                    (mode == ProgramMode.TUT && selectedMode == ProgramMode.TUTBeast)

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
    ProgramMode.TUTBeast -> "BEAST"
    ProgramMode.Echo -> "ECHO"
}
