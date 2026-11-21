package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.WeightAdjustmentDirection
import com.devil.phoenixproject.domain.model.WeightAdjustmentRecommendation
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.ui.theme.AccessibilityTheme
import com.devil.phoenixproject.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.action_apply
import projectphoenix.shared.generated.resources.action_dismiss
import projectphoenix.shared.generated.resources.weight_recommendation_decrease
import projectphoenix.shared.generated.resources.weight_recommendation_increase
import projectphoenix.shared.generated.resources.weight_recommendation_maintain
import projectphoenix.shared.generated.resources.weight_recommendation_weight_change

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WeightRecommendationCard(
    recommendation: WeightAdjustmentRecommendation,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Prevent double-tap while BLE write is in flight (gap-3-2).
    // Keyed to recommendation so a new/changed recommendation resets the latch.
    var isApplying by remember(recommendation) { mutableStateOf(false) }

    val cardContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val semanticColor = when (recommendation.direction) {
        WeightAdjustmentDirection.INCREASE -> AccessibilityTheme.colors.success
        WeightAdjustmentDirection.DECREASE -> AccessibilityTheme.colors.warning
        WeightAdjustmentDirection.MAINTAIN -> MaterialTheme.colorScheme.primary
    }
    // D-13: Tonal container with high-contrast ink avoids white-on-light-green/amber failure in Light mode
    val applyButtonContainerColor = semanticColor.copy(alpha = 0.16f).compositeOver(cardContainerColor)

    val icon = when (recommendation.direction) {
        WeightAdjustmentDirection.INCREASE -> Icons.Default.ArrowUpward
        WeightAdjustmentDirection.DECREASE -> Icons.Default.ArrowDownward
        WeightAdjustmentDirection.MAINTAIN -> Icons.Default.CheckCircle
    }
    val headline = when (recommendation.direction) {
        WeightAdjustmentDirection.INCREASE -> stringResource(Res.string.weight_recommendation_increase)
        WeightAdjustmentDirection.DECREASE -> stringResource(Res.string.weight_recommendation_decrease)
        WeightAdjustmentDirection.MAINTAIN -> stringResource(Res.string.weight_recommendation_maintain)
    }
    val currentWeight = formatWeight(recommendation.currentWeightKgPerCable, weightUnit)
    val recommendedWeight = formatWeight(recommendation.recommendedWeightKgPerCable, weightUnit)
    val weightChangeText = stringResource(
        Res.string.weight_recommendation_weight_change,
        currentWeight,
        recommendedWeight,
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = cardContainerColor,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = semanticColor,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(Spacing.small))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = headline,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = weightChangeText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = recommendation.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(2.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(Spacing.small, Alignment.CenterVertically),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(Res.string.action_dismiss))
                }
                FilledTonalButton(
                    onClick = {
                        isApplying = true
                        onApply()
                    },
                    enabled = !isApplying,
                    modifier = Modifier.heightIn(min = 48.dp),
                    border = BorderStroke(1.dp, semanticColor),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = applyButtonContainerColor,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                ) {
                    Text(
                        text = stringResource(Res.string.action_apply),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
