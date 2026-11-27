package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.util.KmpUtils
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/**
 * Date range options for export filtering
 */
private enum class DateRangeOption(val labelRes: StringResource, val daysBack: Int?) {
    ALL_TIME(Res.string.date_range_all_time, null),
    LAST_7_DAYS(Res.string.date_range_last_7_days, 7),
    LAST_30_DAYS(Res.string.date_range_last_30_days, 30),
    LAST_90_DAYS(Res.string.date_range_last_90_days, 90),
    THIS_YEAR(Res.string.date_range_this_year, 365),
}

/**
 * Dialog for selecting a date range for export filtering.
 * Provides preset ranges.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangePickerDialog(
    onDateRangeSelected: (startDate: Long?, endDate: Long?) -> Unit,
    onDismiss: () -> Unit,
    filterRecordCount: (startDate: Long?, endDate: Long?) -> Int,
) {
    var selectedOption by remember { mutableStateOf(DateRangeOption.ALL_TIME) }

    // Calculate dates based on selection
    val currentTime = remember { KmpUtils.currentTimeMillis() }
    val (startDate, endDate) = remember(selectedOption) {
        when (selectedOption) {
            DateRangeOption.ALL_TIME -> null to null

            else -> {
                val daysBack = selectedOption.daysBack ?: 0
                val start = currentTime - (daysBack.toLong() * 24 * 60 * 60 * 1000)
                start to null
            }
        }
    }

    // Calculate preview count
    val previewCount = remember(selectedOption, startDate, endDate) {
        filterRecordCount(startDate, endDate)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Default.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    stringResource(Res.string.date_range_select_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(Res.string.date_range_choose_period),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(8.dp))

                DateRangeOption.entries.forEach { option ->
                    DateRangeOptionRow(
                        option = option,
                        isSelected = selectedOption == option,
                        onClick = { selectedOption = option },
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // Preview count
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
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
                        Text(
                            stringResource(Res.string.date_range_workouts_to_export),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "$previewCount",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onDateRangeSelected(startDate, endDate) },
                enabled = previewCount > 0,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(
                    stringResource(Res.string.action_export),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.large,
    )
}

@Composable
private fun DateRangeOptionRow(option: DateRangeOption, isSelected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(option.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )

            if (isSelected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(Res.string.cd_selected),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
