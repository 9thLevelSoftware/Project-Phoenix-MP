package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.devil.phoenixproject.domain.model.EchoLevel
import com.devil.phoenixproject.domain.model.echoLevelLabel
import com.devil.phoenixproject.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.rest_echo_level

/**
 * Shared pill selector for Echo level (Hard / Harder / Hardest / Epic).
 *
 * Unifies the verbatim copies from SetReadyScreen, RestTimerCard, and RoutineOverviewScreen.
 * Fixes the unlocalized "ECHO LEVEL" label that existed in SetReadyScreen (was a hardcoded string).
 * Adds selectableGroup + RadioButton semantics matching the Phase 1 pattern in ModeSelector.
 */
@Composable
fun EchoLevelPillSelector(
    selectedLevel: EchoLevel,
    onLevelChange: (EchoLevel) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Resolve level names up-front for locale-awareness — mirrors ModeSelector's modeNames pattern.
    // echoLevelLabel uses string resources so names update with the active locale.
    val levelNames = mapOf(
        EchoLevel.HARD to echoLevelLabel(EchoLevel.HARD),
        EchoLevel.HARDER to echoLevelLabel(EchoLevel.HARDER),
        EchoLevel.HARDEST to echoLevelLabel(EchoLevel.HARDEST),
        EchoLevel.EPIC to echoLevelLabel(EchoLevel.EPIC),
    )

    Column(modifier = modifier) {
        Text(
            text = stringResource(Res.string.rest_echo_level),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp,
        )

        Spacer(modifier = Modifier.height(Spacing.small))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerLowest,
                    RoundedCornerShape(Spacing.medium),
                )
                .padding(Spacing.extraSmall),
            horizontalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            EchoLevel.entries.forEach { level ->
                val isSelected = level == selectedLevel
                val levelName = levelNames[level] ?: level.displayName

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Spacing.small))
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLowest
                            },
                        )
                        .semantics(mergeDescendants = true) {
                            role = Role.RadioButton
                            selected = isSelected
                            contentDescription = levelName
                        }
                        .clickable { onLevelChange(level) }
                        .padding(vertical = Spacing.small),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = levelName,
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
