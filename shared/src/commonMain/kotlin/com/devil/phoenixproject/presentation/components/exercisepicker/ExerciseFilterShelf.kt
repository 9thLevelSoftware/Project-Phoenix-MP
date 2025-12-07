package com.devil.phoenixproject.presentation.components.exercisepicker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Unified horizontal filter shelf combining favorites, custom, muscle, and equipment filters.
 * Replaces the previous 4-row filter UI with a single scrollable row.
 */
@Composable
fun ExerciseFilterShelf(
    showFavoritesOnly: Boolean,
    onToggleFavorites: () -> Unit,
    showCustomOnly: Boolean,
    onToggleCustom: () -> Unit,
    selectedMuscles: Set<String>,
    onToggleMuscle: (String) -> Unit,
    selectedEquipment: Set<String>,
    onToggleEquipment: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val muscleGroups = listOf("Chest", "Back", "Legs", "Shoulders", "Arms", "Core")
    val equipmentTypes = listOf("Long Bar", "Short Bar", "Handles", "Rope", "Belt", "Ankle Strap", "Bench", "Bodyweight")

    val hasActiveFilters = showFavoritesOnly || showCustomOnly ||
        selectedMuscles.isNotEmpty() || selectedEquipment.isNotEmpty()

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.height(48.dp)
    ) {
        // Clear button (only when filters active)
        if (hasActiveFilters) {
            item {
                InputChip(
                    selected = false,
                    onClick = onClearAll,
                    label = { Text("Clear") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear filters",
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    colors = InputChipDefaults.inputChipColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        labelColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                )
            }
        }

        // Favorites chip
        item {
            FilterChip(
                selected = showFavoritesOnly,
                onClick = onToggleFavorites,
                label = { Text("Favorites") },
                leadingIcon = {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }

        // Custom chip
        item {
            FilterChip(
                selected = showCustomOnly,
                onClick = onToggleCustom,
                label = { Text("Custom") },
                leadingIcon = {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }

        // Divider
        item {
            VerticalDivider(
                modifier = Modifier.height(24.dp).padding(horizontal = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }

        // Muscle group chips
        items(muscleGroups) { muscle ->
            FilterChip(
                selected = muscle in selectedMuscles,
                onClick = { onToggleMuscle(muscle) },
                label = { Text(muscle) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            )
        }

        // Divider
        item {
            VerticalDivider(
                modifier = Modifier.height(24.dp).padding(horizontal = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }

        // Equipment chips
        items(equipmentTypes) { equipment ->
            FilterChip(
                selected = equipment in selectedEquipment,
                onClick = { onToggleEquipment(equipment) },
                label = { Text(equipment) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            )
        }
    }
}
