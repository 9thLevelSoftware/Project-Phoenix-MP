package com.devil.phoenixproject.presentation.components.cycle

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.CycleItem
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

@Composable
fun RestDayRow(rest: CycleItem.Rest, onTap: (() -> Unit)? = null, dragModifier: Modifier = Modifier, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().let { m ->
            if (onTap != null) m.clickable(onClick = onTap) else m
        },
        // training-cycles-13: 0.15f alpha made rest-day cards near-invisible against the
        // list background. Use full tertiaryContainer so the card reads as a tappable surface.
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Drag handle (48dp touch target)
            Box(
                modifier = dragModifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = stringResource(Res.string.cd_reorder),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Content
            Text(
                text = stringResource(
                    Res.string.cycle_rest_day_row,
                    rest.dayNumber,
                    rest.note ?: stringResource(Res.string.cycles_rest_badge),
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )

            // Moon icon
            Icon(
                imageVector = Icons.Default.NightsStay,
                contentDescription = stringResource(Res.string.cd_rest_day),
                tint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f),
            )
        }
    }
}
