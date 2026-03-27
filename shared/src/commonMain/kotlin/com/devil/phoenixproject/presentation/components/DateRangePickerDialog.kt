package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
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
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res

/**
 * Date range options for export filtering
 */
enum class DateRangeOption(val label: String, val daysBack: Int?) {
    ALL_TIME("All Time", null),
    LAST_7_DAYS("Last 7 Days", 7),
    LAST_30_DAYS("Last 30 Days", 30),
    LAST_90_DAYS("Last 90 Days", 90),
    THIS_YEAR("This Year", 365),
    CUSTOM("Custom Range", null),
}

/**
 * Dialog for selecting a date range for export filtering.
 * Provides quick presets and custom range options.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangePickerDialog(
    totalRecords: Int,
    onDateRangeSelected: (startDate: Long?, endDate: Long?) -> Unit,
    onDismiss: () -> Unit,
    filterRecordCount: (startDate: Long?, endDate: Long?) -> Int = { _, _ -> totalRecords },
) {
    var selectedOption by remember { mutableStateOf(DateRangeOption.ALL_TIME) }

    // Calculate dates based on selection
    val currentTime = remember { KmpUtils.currentTimeMillis() }
    val (startDate, endDate) = remember(selectedOption) {
        when (selectedOption) {
            DateRangeOption.ALL_TIME -> null to null

            DateRangeOption.CUSTOM -> null to null

            // Custom handled separately
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
                    "Select Date Range",
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
                    "Choose a time period to export:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Date range options (excluding CUSTOM for now - simpler MVP)
                DateRangeOption.entries
                    .filter { it != DateRangeOption.CUSTOM }
                    .forEach { option ->
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
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Workouts to export:",
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
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(
                    "Export",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(20.dp),
            ) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(28.dp),
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
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = option.label,
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
