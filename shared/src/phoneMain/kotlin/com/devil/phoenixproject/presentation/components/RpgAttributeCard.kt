package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.CharacterClass
import com.devil.phoenixproject.domain.model.RpgAttribute
import com.devil.phoenixproject.domain.model.RpgProfile
import com.devil.phoenixproject.presentation.util.isCompactAccessibilityLayout
import com.devil.phoenixproject.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.rpg_no_data_yet

/**
 * RPG Attribute Card -- shows character class header and five attribute bars (0-100).
 * Displayed on BadgesScreen.
 */
@Composable
fun RpgAttributeCard(profile: RpgProfile, modifier: Modifier = Modifier) {
    // M13: Show a friendly empty state for zero-workout users instead of a card
    // with all-zero attribute bars, which is confusing and uninformative.
    if (profile == RpgProfile.EMPTY) {
        PhoenixSectionCard(modifier = modifier) {
            Text(
                text = stringResource(Res.string.rpg_no_data_yet),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.small),
            )
        }
        return
    }

    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.bodySmall
    val valueStyle = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
    val attributeEntries = RpgAttribute.entries
    val maxLabelWidth = remember(attributeEntries, labelStyle, density) {
        with(density) {
            attributeEntries.maxOfOrNull { attr ->
                textMeasurer.measure(AnnotatedString(attr.displayName), style = labelStyle).size.width.toDp()
            } ?: 90.dp
        }
    }
    val maxValueWidth = remember(valueStyle, density) {
        with(density) {
            textMeasurer.measure(AnnotatedString("100"), style = valueStyle).size.width.toDp()
        }
    }
    val stackAttributeRows = isCompactAccessibilityLayout() || maxLabelWidth > 140.dp

    PhoenixSectionCard(modifier = modifier) {
        // Header: icon + class name + description
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            SectionHeaderIconBadge(
                imageVector = getCharacterClassIcon(profile.characterClass),
                contentDescription = profile.characterClass.displayName,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.characterClass.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = profile.characterClass.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.medium))

        // Five attribute bars
        attributeEntries.forEach { attribute ->
            val value = when (attribute) {
                RpgAttribute.STRENGTH -> profile.strength
                RpgAttribute.POWER -> profile.power
                RpgAttribute.STAMINA -> profile.stamina
                RpgAttribute.CONSISTENCY -> profile.consistency
                RpgAttribute.MASTERY -> profile.mastery
            }
            AttributeBar(
                name = attribute.displayName,
                value = value,
                labelMinWidth = maxLabelWidth,
                valueMinWidth = maxValueWidth,
                stacked = stackAttributeRows,
            )
            Spacer(modifier = Modifier.height(Spacing.extraSmall))
        }
    }
}

@Composable
private fun AttributeBar(
    name: String,
    value: Int,
    labelMinWidth: Dp,
    valueMinWidth: Dp,
    stacked: Boolean,
    modifier: Modifier = Modifier,
) {
    if (stacked) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(Spacing.small))
                Text(
                    text = "$value",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                    maxLines = 1,
                )
            }
            LinearProgressIndicator(
                progress = { (value / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false,
                maxLines = 1,
                modifier = Modifier.widthIn(min = labelMinWidth),
            )
            LinearProgressIndicator(
                progress = { (value / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Text(
                text = "$value",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.End,
                softWrap = false,
                maxLines = 1,
                modifier = Modifier.widthIn(min = valueMinWidth),
            )
        }
    }
}

private fun getCharacterClassIcon(characterClass: CharacterClass): ImageVector = when (characterClass) {
    CharacterClass.POWERLIFTER -> Icons.Default.FitnessCenter
    CharacterClass.ATHLETE -> Icons.Default.Bolt
    CharacterClass.IRONMAN -> Icons.Default.Repeat
    CharacterClass.MONK -> Icons.Default.SelfImprovement
    CharacterClass.PHOENIX -> Icons.Default.LocalFireDepartment
}
