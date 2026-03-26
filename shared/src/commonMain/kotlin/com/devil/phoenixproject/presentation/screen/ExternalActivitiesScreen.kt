package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.ExternalActivity
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.presentation.viewmodel.IntegrationsViewModel
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.KmpUtils
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalActivitiesScreen(
    onSetTitle: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val viewModel: IntegrationsViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        onSetTitle("External Activities")
    }

    // ── Provider filter state ─────────────────────────────────────────────────
    var selectedProvider by remember { mutableStateOf<IntegrationProvider?>(null) }

    val allActivities = uiState.externalActivities
    val filteredActivities = remember(allActivities, selectedProvider) {
        if (selectedProvider == null) allActivities
        else allActivities.filter { it.provider == selectedProvider }
    }

    // Providers that actually have activities
    val presentProviders = remember(allActivities) {
        allActivities.map { it.provider }.distinct()
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ── Filter chips ──────────────────────────────────────────────────────
        if (presentProviders.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = Spacing.medium, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedProvider == null,
                        onClick = { selectedProvider = null },
                        label = { Text("All") }
                    )
                }
                items(presentProviders) { provider ->
                    FilterChip(
                        selected = selectedProvider == provider,
                        onClick = {
                            selectedProvider = if (selectedProvider == provider) null else provider
                        },
                        label = { Text(provider.displayName) }
                    )
                }
            }
        }

        // ── Content ───────────────────────────────────────────────────────────
        if (filteredActivities.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.small)
                ) {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        "No activities imported yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Connect Hevy, Liftosaur, or import a CSV\nfrom the Integrations screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(
                    start = Spacing.medium,
                    end = Spacing.medium,
                    bottom = Spacing.medium
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredActivities, key = { it.id }) { activity ->
                    ExternalActivityItem(activity = activity)
                }
            }
        }
    }
}

@Composable
private fun ExternalActivityItem(activity: ExternalActivity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small)
        ) {
            // Activity type icon
            Icon(
                when (activity.activityType.lowercase()) {
                    "run", "running" -> Icons.Default.DirectionsRun
                    "cycling", "ride" -> Icons.Default.DirectionsBike
                    else -> Icons.Default.FitnessCenter
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    activity.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Provider label
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            activity.provider.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        KmpUtils.formatTimestamp(activity.startedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Duration
            if (activity.durationSeconds > 0) {
                Column(horizontalAlignment = Alignment.End) {
                    val mins = activity.durationSeconds / 60
                    Text(
                        if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        activity.activityType.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
