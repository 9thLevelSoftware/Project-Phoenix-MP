package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.Exercise
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.action_cancel
import vitruvianprojectphoenix.shared.generated.resources.tag_exercise_action

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniExercisePickerDialog(
    exerciseRepository: ExerciseRepository,
    onDismiss: () -> Unit,
    onExerciseSelected: (Exercise) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var showFavoritesOnly by remember { mutableStateOf(false) }
    var selectedMuscles by remember { mutableStateOf(setOf<String>()) }
    var selectedEquipment by remember { mutableStateOf(setOf<String>()) }

    val allExercises by remember(searchQuery, showFavoritesOnly) {
        when {
            showFavoritesOnly -> exerciseRepository.getFavorites()
            searchQuery.isNotBlank() -> exerciseRepository.searchExercises(searchQuery)
            else -> exerciseRepository.getAllExercises()
        }
    }.collectAsState(initial = emptyList())

    val exercises = remember(allExercises, selectedMuscles, selectedEquipment) {
        allExercises.filter { exercise ->
            val matchesMuscle = selectedMuscles.isEmpty() ||
                selectedMuscles.any { muscle ->
                    exercise.muscleGroups.contains(muscle, ignoreCase = true)
                }
            val matchesEquipment = selectedEquipment.isEmpty() ||
                selectedEquipment.any { equipment ->
                    val databaseValues = getEquipmentDatabaseValues(equipment)
                    val equipmentList = exercise.equipment.uppercase().split(",").map { it.trim() }
                    databaseValues.any { dbValue -> equipmentList.contains(dbValue.uppercase()) }
                }
            matchesMuscle && matchesEquipment
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.large,
        title = {
            Text(
                stringResource(Res.string.tag_exercise_action),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Box(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                ExercisePickerContent(
                    exercises = exercises,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    showFavoritesOnly = showFavoritesOnly,
                    onToggleFavorites = { showFavoritesOnly = !showFavoritesOnly },
                    showCustomOnly = false,
                    onToggleCustom = {},
                    customExerciseCount = 0,
                    selectedMuscles = selectedMuscles,
                    onToggleMuscle = { muscle ->
                        selectedMuscles = if (muscle in selectedMuscles) {
                            selectedMuscles - muscle
                        } else {
                            selectedMuscles + muscle
                        }
                    },
                    selectedEquipment = selectedEquipment,
                    onToggleEquipment = { equipment ->
                        selectedEquipment = if (equipment in selectedEquipment) {
                            selectedEquipment - equipment
                        } else {
                            selectedEquipment + equipment
                        }
                    },
                    onClearAllFilters = {
                        showFavoritesOnly = false
                        selectedMuscles = emptySet()
                        selectedEquipment = emptySet()
                    },
                    onExerciseSelected = { exercise ->
                        onExerciseSelected(exercise)
                        onDismiss()
                    },
                    onToggleFavorite = { exercise ->
                        exercise.id?.let { id ->
                            coroutineScope.launch { exerciseRepository.toggleFavorite(id) }
                        }
                    },
                    exerciseRepository = exerciseRepository,
                    enableVideoPlayback = false,
                    enableCustomExercises = false,
                    // showTitle = false suppresses ExercisePickerContent's own title row,
                    // avoiding a double-title when hosted inside an AlertDialog.
                    // fullScreen = true is used honestly for its height meaning only:
                    // fillMaxHeight(1f) inside the heightIn(max=520.dp) cap reproduces the
                    // pre-showTitle rendered height exactly (5A.5 review: 0.9f shrank the
                    // list by ~one row on compact screens).
                    showTitle = false,
                    fullScreen = true,
                )
            }
        },
        // Lone dismiss action lives in the confirmButton slot so M3 renders it
        // right-aligned (lens-dialog-uniformity-6 pattern, matches RestTimePickerDialog).
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}
