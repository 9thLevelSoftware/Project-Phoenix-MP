package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.devil.phoenixproject.domain.model.percentLabel
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.ui.theme.labelSmallAllCaps
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.rest_eccentric_load

/**
 * Shared eccentric-load slider (0–150% in 5% steps).
 *
 * Unifies the copies from SetReadyScreen, RestTimerCard, and RoutineOverviewScreen.
 * The label uses [Res.string.rest_eccentric_load] and the value uses [percentLabel]
 * so Set Ready follows the same locale as Rest and Overview.
 */
@Composable
fun EccentricLoadSlider(
    percent: Int,
    onPercentChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(Res.string.rest_eccentric_load),
                style = labelSmallAllCaps,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = percentLabel(percent),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(modifier = Modifier.height(Spacing.small))

        // Fine-grained slider (5% increments). Callers snap to the nearest EccentricLoad.
        ExpressiveSlider(
            value = percent.toFloat(),
            onValueChange = { onPercentChange(it.toInt()) },
            valueRange = 0f..150f,
            steps = 29, // 5% increments: 0, 5, 10, ... 150
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
