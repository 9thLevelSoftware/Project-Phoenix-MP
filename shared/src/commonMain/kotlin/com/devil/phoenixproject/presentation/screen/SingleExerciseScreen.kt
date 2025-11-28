package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import com.devil.phoenixproject.data.preferences.SingleExerciseDefaults
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.domain.model.*
import com.devil.phoenixproject.presentation.components.ConnectingOverlay
import com.devil.phoenixproject.presentation.components.ConnectionErrorDialog
import com.devil.phoenixproject.presentation.components.ExercisePickerContent
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Single Exercise screen - allows user to pick and configure a single exercise
 * Full implementation with exercise picker and configuration bottom sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SingleExerciseScreen(
    navController: NavController,
    viewModel: MainViewModel,
    exerciseRepository: ExerciseRepository
) {
    val weightUnit by viewModel.weightUnit.collectAsState()
    val enableVideoPlayback by viewModel.enableVideoPlayback.collectAsState()
    val isAutoConnecting by viewModel.isAutoConnecting.collectAsState()
    val connectionError by viewModel.connectionError.collectAsState()

    var exerciseToConfig by remember { mutableStateOf<RoutineExercise?>(null) }
    var isLoadingDefaults by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Track current loading job to cancel on rapid selection changes
    var loadingJob by remember { mutableStateOf<Job?>(null) }

    // Local state for picker
    var searchQuery by remember { mutableStateOf("") }
    var selectedMuscleFilter by remember { mutableStateOf("All") }
    var selectedEquipmentFilter by remember { mutableStateOf("All") }
    var showFavoritesOnly by remember { mutableStateOf(false) }

    // Get exercises from repository
    val allExercises by remember(searchQuery, selectedMuscleFilter, showFavoritesOnly) {
        when {
            showFavoritesOnly -> exerciseRepository.getFavorites()
            searchQuery.isNotBlank() -> exerciseRepository.searchExercises(searchQuery)
            selectedMuscleFilter != "All" -> exerciseRepository.filterByMuscleGroup(selectedMuscleFilter)
            else -> exerciseRepository.getAllExercises()
        }
    }.collectAsState(initial = emptyList())

    // Apply equipment filter
    val exercises = remember(allExercises, selectedEquipmentFilter) {
        if (selectedEquipmentFilter != "All") {
            allExercises.filter { exercise ->
                val databaseValues = when (selectedEquipmentFilter) {
                    "Long Bar" -> listOf("BAR", "LONG_BAR", "BARBELL")
                    "Short Bar" -> listOf("SHORT_BAR")
                    "Ankle Strap" -> listOf("ANKLE_STRAP", "STRAPS")
                    "Handles" -> listOf("HANDLES", "SINGLE_HANDLE", "BOTH_HANDLES")
                    "Bench" -> listOf("BENCH")
                    "Rope" -> listOf("ROPE")
                    "Belt" -> listOf("BELT")
                    "Bodyweight" -> listOf("BODYWEIGHT")
                    else -> emptyList()
                }
                val equipmentList = exercise.equipment.uppercase().split(",").map { it.trim() }
                databaseValues.any { dbValue -> equipmentList.contains(dbValue.uppercase()) }
            }
        } else {
            allExercises
        }
    }

    // Trigger import
    LaunchedEffect(Unit) {
        exerciseRepository.importExercises()
    }

    // Set global title
    LaunchedEffect(Unit) {
        viewModel.updateTopBarTitle("Single Exercise")
    }

    Scaffold { padding ->
        Box(modifier = Modifier.padding(padding)) {
            // Always show the picker content as the background
            ExercisePickerContent(
                exercises = exercises,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                showFavoritesOnly = showFavoritesOnly,
                onShowFavoritesOnlyChange = {
                    showFavoritesOnly = it
                    if (it) {
                        searchQuery = ""
                        selectedMuscleFilter = "All"
                        selectedEquipmentFilter = "All"
                    }
                },
                selectedMuscleFilter = selectedMuscleFilter,
                onMuscleFilterChange = { selectedMuscleFilter = it },
                selectedEquipmentFilter = selectedEquipmentFilter,
                onEquipmentFilterChange = { selectedEquipmentFilter = it },
                onExerciseSelected = { selectedExercise ->
                    val exercise = Exercise(
                        name = selectedExercise.name,
                        muscleGroup = selectedExercise.muscleGroups.split(",").firstOrNull()?.trim() ?: "Full Body",
                        muscleGroups = selectedExercise.muscleGroups,
                        equipment = selectedExercise.equipment.split(",").firstOrNull()?.trim() ?: "",
                        defaultCableConfig = CableConfiguration.DOUBLE,
                        id = selectedExercise.id
                    )

                    val defaultCableConfig = exercise.resolveDefaultCableConfig()

                    // Cancel any in-progress loading to prevent race conditions
                    loadingJob?.cancel()

                    // Set loading state to prevent showing dialog before defaults are loaded
                    isLoadingDefaults = true

                    // Load saved defaults for this exercise+cable config asynchronously
                    loadingJob = coroutineScope.launch {
                        try {
                            val savedDefaults = selectedExercise.id?.let { exerciseId ->
                                viewModel.getSingleExerciseDefaults(exerciseId, defaultCableConfig.name)
                            }

                            val newRoutineExercise = if (savedDefaults != null) {
                                // Apply saved defaults
                                RoutineExercise(
                                    id = generateUUID(),
                                    exercise = exercise,
                                    cableConfig = savedDefaults.getCableConfiguration(),
                                    orderIndex = 0,
                                    setReps = savedDefaults.setReps,
                                    weightPerCableKg = savedDefaults.weightPerCableKg,
                                    setWeightsPerCableKg = savedDefaults.setWeightsPerCableKg,
                                    progressionKg = savedDefaults.progressionKg,
                                    setRestSeconds = savedDefaults.setRestSeconds,
                                    workoutType = savedDefaults.toWorkoutType(),
                                    eccentricLoad = savedDefaults.getEccentricLoad(),
                                    echoLevel = savedDefaults.getEchoLevel(),
                                    duration = savedDefaults.duration.takeIf { it > 0 },
                                    isAMRAP = savedDefaults.isAMRAP,
                                    perSetRestTime = savedDefaults.perSetRestTime
                                )
                            } else {
                                // No saved defaults - use system defaults
                                RoutineExercise(
                                    id = generateUUID(),
                                    exercise = exercise,
                                    cableConfig = defaultCableConfig,
                                    orderIndex = 0,
                                    setReps = listOf(10, 10, 10),
                                    weightPerCableKg = 20f,
                                    progressionKg = 0f,
                                    setRestSeconds = listOf(60, 60, 60),
                                    workoutType = WorkoutType.Program(ProgramMode.OldSchool),
                                    eccentricLoad = EccentricLoad.LOAD_100,
                                    echoLevel = EchoLevel.HARDER
                                )
                            }
                            exerciseToConfig = newRoutineExercise
                        } finally {
                            isLoadingDefaults = false
                        }
                    }
                },
                exerciseRepository = exerciseRepository,
                enableVideoPlayback = enableVideoPlayback,
                fullScreen = true
            )

            // Show loading indicator while defaults are being loaded
            if (isLoadingDefaults) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            // Show bottom sheet as overlay when an exercise is selected and defaults are loaded
            if (!isLoadingDefaults) {
                exerciseToConfig?.let { routineExercise ->
                    ExerciseEditBottomSheet(
                        exercise = routineExercise,
                        weightUnit = weightUnit,
                        enableVideoPlayback = enableVideoPlayback,
                        kgToDisplay = viewModel::kgToDisplay,
                        displayToKg = viewModel::displayToKg,
                        exerciseRepository = exerciseRepository,
                        personalRecordRepository = viewModel.personalRecordRepository,
                        formatWeight = viewModel::formatWeight,
                        buttonText = "Start Workout",
                        onSave = { configuredExercise ->
                            val tempRoutine = Routine(
                                id = "${MainViewModel.TEMP_SINGLE_EXERCISE_PREFIX}${generateUUID()}",
                                name = "Single Exercise: ${configuredExercise.exercise.name}",
                                description = "Temporary routine for single exercise mode",
                                exercises = listOf(configuredExercise)
                            )

                            viewModel.loadRoutine(tempRoutine)

                            viewModel.ensureConnection(
                                onConnected = {
                                    viewModel.startWorkout()
                                    navController.navigate(NavigationRoutes.ActiveWorkout.route) {
                                        popUpTo(NavigationRoutes.Home.route)
                                    }
                                },
                                onFailed = { }
                            )

                            exerciseToConfig = null
                        },
                        onDismiss = {
                            exerciseToConfig = null
                        }
                    )
                }
            }
        }

        if (isAutoConnecting) {
            ConnectingOverlay(
                onCancel = { viewModel.cancelAutoConnecting() }
            )
        }

        connectionError?.let { error ->
            ConnectionErrorDialog(
                message = error,
                onDismiss = { viewModel.clearConnectionError() }
            )
        }
    }
}
