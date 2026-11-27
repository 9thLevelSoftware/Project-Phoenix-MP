package com.devil.phoenixproject.presentation.components.cycle

import com.devil.phoenixproject.presentation.components.ExpressiveCard
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.CycleTemplates
import com.devil.phoenixproject.domain.model.CycleTemplate
import com.devil.phoenixproject.presentation.components.PhoenixSheetHeader
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/**
 * Bottom sheet for creating training cycles.
 * Offers prebuilt templates or a custom cycle starting from scratch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedCycleCreationSheet(
    onSelectTemplate: (CycleTemplate) -> Unit,
    onCreateCustom: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    val templates = remember { CycleTemplates.all() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            PhoenixSheetHeader(
                title = stringResource(Res.string.cycle_create_sheet_title),
                subtitle = stringResource(Res.string.cycle_create_sheet_subtitle),
                onClose = onDismiss,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // TEMPLATES Section
            Text(
                text = stringResource(Res.string.cycle_create_templates_header),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                templates.forEach { template ->
                    TemplateCard(
                        template = template,
                        onClick = { onSelectTemplate(template) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))

            Spacer(modifier = Modifier.height(16.dp))

            // Custom cycle button
            Text(
                text = stringResource(Res.string.cycle_create_or_scratch),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onCreateCustom,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.small,
                contentPadding = PaddingValues(vertical = 14.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(Res.string.cycle_create_custom_button),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Template card displaying template name, description, day breakdown, and 1RM badge if required.
 */
@Composable
private fun TemplateCard(template: CycleTemplate, onClick: () -> Unit) {
    val requiresOneRepMax = template.requiresOneRepMax

    val restBadgeLabel = stringResource(Res.string.cycles_rest_badge)
    // Build day breakdown string (e.g., "Day 1: Push A, Day 2: Pull A, ...")
    val dayBreakdown = template.days.joinToString(" / ") { day ->
        if (day.isRestDay) restBadgeLabel else (day.routine?.name ?: day.name)
    }

    ExpressiveCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = template.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (requiresOneRepMax) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(4.dp),
                        ) {
                            Text(
                                text = stringResource(Res.string.config_meta_1rm),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.day_count_other, template.days.size),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = template.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Day breakdown preview
                Text(
                    text = dayBreakdown,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 2,
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = stringResource(Res.string.cd_select_template),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
