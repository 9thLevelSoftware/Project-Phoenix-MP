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
import com.devil.phoenixproject.presentation.components.ConnectionErrorDialog
import com.devil.phoenixproject.presentation.components.ExercisePickerContent
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import co.touchlab.kermit.Logger
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
    @Suppress("UNUSED_VARIABLE") // Reserved for future connecting overlay
    val isAutoConnecting by viewModel.isAutoConnecting.collectAsState()
    val connectionError by viewModel.connectionError.collectAsState()

    var exerciseToConfig by remember { mutableStateOf<RoutineExercise?>(null) }
    var isLoadingDefaults by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Track current loading job to cancel on rapid selection changes
    var loadingJob by remember { mutableStateOf<Job?>(null) }

    // Local state for picker
    var searchQuery by remember { mutableStateOf("") }
    var selectedMuscles by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedEquipment by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showFavoritesOnly by remember { mutableStateOf(false) }
    var showCustomOnly by remember { mutableStateOf(false) }

    // Get exercises from repository
    val allExercises by remember(searchQuery, selectedMuscles, showFavoritesOnly, showCustomOnly) {
        when {
            showFavoritesOnly -> exerciseRepository.getFavorites()
            showCustomOnly -> exerciseRepository.getCustomExercises()
            searchQuery.isNotBlank() -> exerciseRepository.searchExercises(searchQuery)
            selectedMuscles.isNotEmpty() -> {
                // Get exercises for all selected muscle groups and combine
                val flows = selectedMuscles.map { muscle ->
                    exerciseRepository.filterByMuscleGroup(muscle)
                }
                // For now, just use the first one - ideally we'd combine all flows
                flows.firstOrNull() ?: exerciseRepository.getAllExercises()
            }
            else -> exerciseRepository.getAllExercises()
        }
    }.collectAsState(initial = emptyList())

    // Apply equipment filter
    val exercises = remember(allExercises, selectedEquipment) {
        if (selectedEquipment.isNotEmpty()) {
            allExercises.filter { exercise ->
                selectedEquipment.any { selectedEq ->
                    val databaseValues = when (selectedEq) {
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
            }
        } else {
            allExercises
        }
    }

    // Get custom exercise count
    val customExerciseCount by exerciseRepository.getCustomExercises().collectAsState(initial = emptyList())
    val customCount = customExerciseCount.size

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
                onToggleFavorites = {
                    showFavoritesOnly = !showFavoritesOnly
                    if (showFavoritesOnly) {
                        searchQuery = ""
                        selectedMuscles = emptySet()
                        selectedEquipment = emptySet()
                        showCustomOnly = false
                    }
                },
                showCustomOnly = showCustomOnly,
                onToggleCustom = {
                    showCustomOnly = !showCustomOnly
                    if (showCustomOnly) {
                        searchQuery = ""
                        selectedMuscles = emptySet()
                        selectedEquipment = emptySet()
                        showFavoritesOnly = false
                    }
                },
                customExerciseCount = customCount,
                selectedMuscles = selectedMuscles,
                onToggleMuscle = { muscle ->
                    selectedMuscles = if (selectedMuscles.contains(muscle)) {
                        selectedMuscles - muscle
                    } else {
                        selectedMuscles + muscle
                    }
                },
                selectedEquipment = selectedEquipment,
                onToggleEquipment = { equipment ->
                    selectedEquipment = if (selectedEquipment.contains(equipment)) {
                        selectedEquipment - equipment
                    } else {
                        selectedEquipment + equipment
                    }
                },
                onClearAllFilters = {
                    searchQuery = ""
                    selectedMuscles = emptySet()
                    selectedEquipment = emptySet()
                    showFavoritesOnly = false
                    showCustomOnly = false
                },
                onToggleFavorite = { exercise ->
                    exercise.id?.let { id ->
                        coroutineScope.launch {
                            exerciseRepository.toggleFavorite(id)
                        }
                    }
                },
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
                    // Uses fallback to try other cable configs if the default doesn't have saved values
                    loadingJob = coroutineScope.launch {
                        try {
                            val savedDefaults = selectedExercise.id?.let { exerciseId ->
                                viewModel.getSingleExerciseDefaultsWithFallback(exerciseId, defaultCableConfig.name)
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
                                    programMode = savedDefaults.toProgramMode(),
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
                                    programMode = ProgramMode.OldSchool,
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
                enableCustomExercises = false,
                onCreateExercise = {},
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
                            Logger.d { "SingleExercise: Start button clicked for ${configuredExercise.exercise.name}" }
                            val tempRoutine = Routine(
                                id = "${MainViewModel.TEMP_SINGLE_EXERCISE_PREFIX}${generateUUID()}",
                                name = "Single Exercise: ${configuredExercise.exercise.name}",
                                exercises = listOf(configuredExercise)
                            )

                            Logger.d { "SingleExercise: Loading temp routine" }
                            viewModel.loadRoutine(tempRoutine)

                            Logger.d { "SingleExercise: Calling ensureConnection" }
                            viewModel.ensureConnection(
                                onConnected = {
                                    Logger.d { "SingleExercise: onConnected callback - starting workout" }
                                    viewModel.startWorkout()
                                    Logger.d { "SingleExercise: Navigating to ActiveWorkout" }
                                    navController.navigate(NavigationRoutes.ActiveWorkout.route) {
                                        popUpTo(NavigationRoutes.Home.route)
                                    }
                                },
                                onFailed = {
                                    Logger.e { "SingleExercise: onFailed callback - connection failed" }
                                }
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

        // Connection error dialog (ConnectingOverlay removed - status shown in top bar button)
        connectionError?.let { error ->
            ConnectionErrorDialog(
                message = error,
                onDismiss = { viewModel.clearConnectionError() }
            )
        }
    }
}
