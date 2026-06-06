package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.WeightAdjustmentDirection
import com.devil.phoenixproject.domain.model.WeightAdjustmentRecommendation
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.ui.theme.Spacing
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.action_apply
import vitruvianprojectphoenix.shared.generated.resources.action_dismiss
import vitruvianprojectphoenix.shared.generated.resources.weight_recommendation_decrease
import vitruvianprojectphoenix.shared.generated.resources.weight_recommendation_increase
import vitruvianprojectphoenix.shared.generated.resources.weight_recommendation_maintain
import vitruvianprojectphoenix.shared.generated.resources.weight_recommendation_weight_change

@Composable
fun WeightRecommendationCard(
    recommendation: WeightAdjustmentRecommendation,
    weightUnit: WeightUnit,
    formatWeight: (Float, WeightUnit) -> String,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            verticalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(Spacing.small))
                    Column {
                        Text(
                            text = headline,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(
                            text = weightChangeText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }

            Text(
                text = recommendation.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
            )

            Spacer(Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.small, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.action_dismiss))
                }
                Button(onClick = onApply) {
                    Text(stringResource(Res.string.action_apply))
                }
            }
        }
    }
}
