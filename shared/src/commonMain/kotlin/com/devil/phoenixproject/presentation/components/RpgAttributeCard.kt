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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.CharacterClass
import com.devil.phoenixproject.domain.model.RpgAttribute
import com.devil.phoenixproject.domain.model.RpgProfile
import com.devil.phoenixproject.ui.theme.Spacing

/**
 * RPG Attribute Card -- shows character class header, five attribute bars (0-100),
 * and a Portal deep-link button. Displayed on BadgesScreen for Phoenix+ tier users.
 *
 * Follows ReadinessBriefingCard composable pattern (Card > Column > header + content + portal link).
 */
@Composable
fun RpgAttributeCard(
    profile: RpgProfile,
    onPortalLink: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium)
        ) {
            // Header: icon + class name + description
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.small)
            ) {
                Icon(
                    imageVector = getCharacterClassIcon(profile.characterClass),
                    contentDescription = profile.characterClass.displayName,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Column {
                    Text(
                        text = profile.characterClass.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = profile.characterClass.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            // Five attribute bars
            RpgAttribute.entries.forEach { attribute ->
                val value = when (attribute) {
                    RpgAttribute.STRENGTH -> profile.strength
                    RpgAttribute.POWER -> profile.power
                    RpgAttribute.STAMINA -> profile.stamina
                    RpgAttribute.CONSISTENCY -> profile.consistency
                    RpgAttribute.MASTERY -> profile.mastery
                }
                AttributeBar(
                    name = attribute.displayName,
                    value = value
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            Spacer(modifier = Modifier.height(Spacing.small))

            // Portal deep link
            TextButton(
                onClick = onPortalLink,
                contentPadding = PaddingValues(0.dp)
            ) {
                Text(
                    text = "View full skill tree on Phoenix Portal",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun AttributeBar(
    name: String,
    value: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(90.dp)
        )
        LinearProgressIndicator(
            progress = { value / 100f },
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Text(
            text = "$value",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(30.dp)
        )
    }
}

private fun getCharacterClassIcon(characterClass: CharacterClass): ImageVector {
    return when (characterClass) {
        CharacterClass.POWERLIFTER -> Icons.Default.FitnessCenter
        CharacterClass.ATHLETE -> Icons.Default.Bolt
        CharacterClass.IRONMAN -> Icons.Default.Repeat
        CharacterClass.MONK -> Icons.Default.SelfImprovement
        CharacterClass.PHOENIX -> Icons.Default.LocalFireDepartment
    }
}
