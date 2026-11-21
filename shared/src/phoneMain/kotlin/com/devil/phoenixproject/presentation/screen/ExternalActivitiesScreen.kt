package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.preferences.PreferencesManager
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.ExternalActivity
import com.devil.phoenixproject.domain.model.IntegrationProvider
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.presentation.components.EmptyState
import com.devil.phoenixproject.presentation.components.ExpressiveCard
import com.devil.phoenixproject.presentation.components.ShimmerBox
import com.devil.phoenixproject.presentation.util.ResponsiveDimensions
import com.devil.phoenixproject.presentation.viewmodel.ExternalActivitiesViewModel
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.util.KmpUtils
import com.devil.phoenixproject.util.UnitConverter
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.empty_no_external_activities_message
import projectphoenix.shared.generated.resources.empty_no_external_activities_title
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalActivitiesScreen(
    onSetTitle: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    weightUnit: WeightUnit? = null,
) {
    val viewModel: ExternalActivitiesViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val resolvedWeightUnit = rememberActivityPreferredWeightUnit(weightUnit)
    val contentMaxWidth = ResponsiveDimensions.contentMaxWidth()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    LaunchedEffect(Unit) {
        onSetTitle("External Activities")
    }

    // ── Provider filter state ─────────────────────────────────────────────────
    var selectedProvider by remember { mutableStateOf<IntegrationProvider?>(null) }

    val allActivities = uiState.activities
    val filteredActivities = remember(allActivities, selectedProvider) {
        if (selectedProvider == null) {
            allActivities
        } else {
            allActivities.filter { it.provider == selectedProvider }
        }
    }

    // Providers that actually have activities
    val presentProviders = remember(allActivities) {
        allActivities.map { it.provider }.distinct()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .then(if (contentMaxWidth != null) Modifier.widthIn(max = contentMaxWidth) else Modifier)
                .fillMaxSize(),
        ) {
            // ── Filter chips ──────────────────────────────────────────────────
            if (presentProviders.isNotEmpty()) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.medium, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        FilterChip(
                            selected = selectedProvider == null,
                            onClick = { selectedProvider = null },
                            modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text("All") },
                        )
                    }
                    items(presentProviders) { provider ->
                        FilterChip(
                            selected = selectedProvider == provider,
                            onClick = {
                                selectedProvider = if (selectedProvider == provider) null else provider
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                            label = { Text(provider.displayName) },
                        )
                    }
                }
            }

            // ── Content ───────────────────────────────────────────────────────
            if (uiState.isLoading) {
                // Shimmer placeholders while repository populates the first emission
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.medium),
                    verticalArrangement = Arrangement.spacedBy(Spacing.small),
                ) {
                    repeat(3) {
                        ShimmerBox(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp),
                        )
                    }
                }
            } else if (filteredActivities.isEmpty()) {
                // Empty state — only shown after loading completes
                EmptyState(
                    icon = Icons.Default.CloudOff,
                    title = stringResource(Res.string.empty_no_external_activities_title),
                    message = stringResource(Res.string.empty_no_external_activities_message),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = Spacing.medium,
                        end = Spacing.medium,
                        bottom = Spacing.medium + navBottom,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredActivities, key = { it.id }) { activity ->
                        ExternalActivityItem(
                            activity = activity,
                            weightUnit = resolvedWeightUnit,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExternalActivityItem(
    activity: ExternalActivity,
    weightUnit: WeightUnit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showTechnicalDetails by remember { mutableStateOf(false) }

    ExpressiveCard(
        onClick = { expanded = !expanded },
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.small),
        ) {
            // Activity type icon
            Icon(
                when (activity.activityType.lowercase()) {
                    "run", "running" -> Icons.AutoMirrored.Filled.DirectionsRun
                    "cycling", "ride" -> Icons.AutoMirrored.Filled.DirectionsBike
                    else -> Icons.Default.FitnessCenter
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    activity.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    // Provider label
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            activity.provider.displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    Text(
                        KmpUtils.formatTimestamp(activity.startedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        activity.activityType.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.medium, end = Spacing.medium, bottom = Spacing.medium),
                verticalArrangement = Arrangement.spacedBy(Spacing.small),
            ) {
                val metricItems = buildList {
                    activity.calories?.let { add("$it kcal") }
                    activity.distanceMeters?.let { add(formatDistance(it, weightUnit)) }
                    activity.elevationGainMeters?.let { add(formatElevation(it, weightUnit)) }
                    activity.avgHeartRate?.let { add("$it avg bpm") }
                    activity.maxHeartRate?.let { add("$it max bpm") }
                }

                if (metricItems.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.small),
                        verticalArrangement = Arrangement.spacedBy(Spacing.extraSmall),
                    ) {
                        metricItems.forEach { metric ->
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                            ) {
                                Text(
                                    text = metric,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = "No additional workout metrics recorded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (activity.externalId.isNotBlank() || !activity.rawData.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .clip(MaterialTheme.shapes.small)
                            .clickable(role = Role.Button) { showTechnicalDetails = !showTechnicalDetails }
                            .semantics {
                                stateDescription = if (showTechnicalDetails) "Expanded" else "Collapsed"
                            }
                            .padding(horizontal = Spacing.extraSmall),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Technical details",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            imageVector = if (showTechnicalDetails) {
                                Icons.Default.KeyboardArrowUp
                            } else {
                                Icons.Default.KeyboardArrowDown
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (showTechnicalDetails) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.padding(Spacing.small),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (activity.externalId.isNotBlank()) {
                                    Text(
                                        text = "Provider ID: ${activity.externalId}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                activity.rawData?.takeIf { it.isNotBlank() }?.let { raw ->
                                    Text(
                                        text = raw.take(320),
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberActivityPreferredWeightUnit(override: WeightUnit?): WeightUnit {
    if (override != null) return override
    val profileRepository: UserProfileRepository = koinInject()
    val preferencesManager: PreferencesManager = koinInject()
    val profileContext by profileRepository.activeProfileContext.collectAsState()
    val globalPreferences by preferencesManager.preferencesFlow.collectAsState()
    return (profileContext as? ActiveProfileContext.Ready)?.preferences?.core?.value?.weightUnit
        ?: globalPreferences.weightUnit
}

private fun formatDistance(distanceMeters: Double, weightUnit: WeightUnit): String = when (weightUnit) {
    WeightUnit.LB -> {
        val miles = distanceMeters / 1609.344
        if (miles >= 0.1) {
            "${UnitConverter.formatDecimal(miles.toFloat())} mi"
        } else {
            "${(distanceMeters * 3.28084).roundToInt()} ft"
        }
    }
    WeightUnit.KG -> {
        if (distanceMeters >= 1000.0) {
            "${UnitConverter.formatDecimal((distanceMeters / 1000.0).toFloat())} km"
        } else {
            "${distanceMeters.roundToInt()} m"
        }
    }
}

private fun formatElevation(elevationMeters: Double, weightUnit: WeightUnit): String = when (weightUnit) {
    WeightUnit.LB -> "${(elevationMeters * 3.28084).roundToInt()} ft elev"
    WeightUnit.KG -> "${elevationMeters.roundToInt()} m elev"
}
