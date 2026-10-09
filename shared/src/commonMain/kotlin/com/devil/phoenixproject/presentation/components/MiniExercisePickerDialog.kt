package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.presentation.components.exercisepicker.ExercisePickerFilterState
import com.devil.phoenixproject.presentation.components.exercisepicker.filterExercisePickerCandidates
import com.devil.phoenixproject.presentation.components.exercisepicker.orderByRecentExercises
import com.devil.phoenixproject.presentation.components.exercisepicker.selectableRecentExerciseIds
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.Res
import projectphoenix.shared.generated.resources.cd_back
import projectphoenix.shared.generated.resources.tag_exercise_action

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniExercisePickerDialog(
    exerciseRepository: ExerciseRepository,
    onDismiss: () -> Unit,
    onExerciseSelected: (Exercise) -> Unit,
    /**
     * Newest-first IDs for the Recent chip (#850). The chip only shows when this is non-empty,
     * and then starts selected, so the usual choices are one tap away.
     */
    recentExerciseIds: List<String> = emptyList(),
    customEquipmentOverlay: Map<String, String> = emptyMap(),
) {
    val coroutineScope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var showFavoritesOnly by remember { mutableStateOf(false) }
    var showEssentialsOnly by remember { mutableStateOf(false) }
    // Only recent ids that still name an exercise count: a deleted custom exercise must not
    // leave Recent selected over an empty list.
    val library by remember { exerciseRepository.getAllExercises() }.collectAsState(initial = emptyList())
    val recentIds = remember(recentExerciseIds, library) { selectableRecentExerciseIds(recentExerciseIds, library) }
    var showRecentOnly by remember { mutableStateOf(false) }
    // The ids and the library load after the dialog opens; select Recent once when they do.
    var recentDefaultApplied by remember { mutableStateOf(false) }
    LaunchedEffect(recentIds.isNotEmpty()) {
        if (recentIds.isNotEmpty() && !recentDefaultApplied) {
            showRecentOnly = true
            recentDefaultApplied = true
        }
    }
    val recentActive = showRecentOnly && recentIds.isNotEmpty()
    var selectedMuscles by remember { mutableStateOf(setOf<String>()) }
    var selectedEquipment by remember { mutableStateOf(setOf<String>()) }

    // Search picks the candidates; every chip then narrows them through the shared helper,
    // as in the full picker, so Favorites and Essentials respect the search text.
    val candidateExercises by remember(searchQuery) {
        when {
            searchQuery.isNotBlank() -> exerciseRepository.searchExercises(searchQuery)
            else -> exerciseRepository.getAllExercises()
        }
    }.collectAsState(initial = emptyList())

    val exercises = remember(
        candidateExercises,
        showFavoritesOnly,
        showEssentialsOnly,
        selectedMuscles,
        selectedEquipment,
        recentActive,
        recentIds,
    ) {
        val filtered = filterExercisePickerCandidates(
            candidates = candidateExercises,
            filters = ExercisePickerFilterState(
                showFavoritesOnly = showFavoritesOnly,
                selectedMuscles = selectedMuscles,
                selectedEquipment = selectedEquipment,
                showEssentialsOnly = showEssentialsOnly,
            ),
            customEquipmentOverlay = customEquipmentOverlay,
        )
        if (recentActive) orderByRecentExercises(filtered, recentIds) else filtered
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(Res.string.tag_exercise_action)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(Res.string.cd_back),
                            )
                        }
                    },
                )
            },
        ) { paddingValues ->
            // fillMaxSize is required: the picker list uses Modifier.weight(1f), which
            // needs bounded height (issue #893 crash otherwise).
            Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
                ExercisePickerContent(
                    exercises = exercises,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    showFavoritesOnly = showFavoritesOnly,
                    onToggleFavorites = { showFavoritesOnly = !showFavoritesOnly },
                    showCustomOnly = false,
                    onToggleCustom = {},
                    // Tagging never creates exercises, so the Custom chip would be a dead control.
                    showCustomFilter = false,
                    enableEssentialsFilter = true,
                    showEssentialsOnly = showEssentialsOnly,
                    onToggleEssentials = { showEssentialsOnly = !showEssentialsOnly },
                    enableRecentFilter = recentIds.isNotEmpty(),
                    showRecentOnly = recentActive,
                    onToggleRecent = { showRecentOnly = !showRecentOnly },
                    customExerciseCount = 0,
                    customEquipmentOverlay = customEquipmentOverlay,
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
                        searchQuery = ""
                        showFavoritesOnly = false
                        showEssentialsOnly = false
                        showRecentOnly = false
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
                    // showTitle = false suppresses ExercisePickerContent's own title row;
                    // the full-screen TopAppBar owns the title here. fullScreen = true lets
                    // the list take the full height under the top bar.
                    showTitle = false,
                    fullScreen = true,
                    // Issue #363: slightly smaller row names in this dialog only.
                    rowNameStyle = MaterialTheme.typography.titleSmall,
                )
            }
        }
    }
}
