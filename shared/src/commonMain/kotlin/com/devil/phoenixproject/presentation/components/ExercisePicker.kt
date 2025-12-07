package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.ExerciseVideoEntity
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.presentation.components.exercisepicker.ExerciseFilterShelf
import com.devil.phoenixproject.presentation.components.exercisepicker.ExerciseListEmptyState
import com.devil.phoenixproject.presentation.components.exercisepicker.GroupedExerciseList
import com.devil.phoenixproject.ui.theme.ThemeMode
import kotlinx.coroutines.launch

/**
 * Map display equipment names back to database values for filtering
 */
private fun getEquipmentDatabaseValues(displayName: String): List<String> {
    return when (displayName) {
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
}

/**
 * Format raw equipment string from database to user-friendly display
 */
private fun formatEquipment(rawEquipment: String): String {
    val equipmentMap = mapOf(
        "BAR" to "Long Bar",
        "LONG_BAR" to "Long Bar",
        "BARBELL" to "Long Bar",
        "SHORT_BAR" to "Short Bar",
        "BENCH" to "Bench",
        "HANDLES" to "Handles",
        "SINGLE_HANDLE" to "Handles",
        "BOTH_HANDLES" to "Handles",
        "STRAPS" to "Ankle Strap",
        "ANKLE_STRAP" to "Ankle Strap",
        "BELT" to "Belt",
        "ROPE" to "Rope",
        "BODYWEIGHT" to "Bodyweight"
    )

    val filteredValues = rawEquipment
        .split(",")
        .map { it.trim().uppercase() }
        .filter {
            it !in listOf("BLACK_CABLES", "RED_CABLES", "GREY_CABLES", "CABLES", "CABLE", "NULL", "", "PUMP_HANDLES", "DUMBBELLS")
        }
        .mapNotNull { equipmentMap[it] }
        .distinct()

    return filteredValues.joinToString(", ")
}

/**
 * Exercise Picker Dialog - Streamlined exercise selection component
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisePickerDialog(
    showDialog: Boolean,
    onDismiss: () -> Unit,
    onExerciseSelected: (Exercise) -> Unit,
    exerciseRepository: ExerciseRepository,
    enableVideoPlayback: Boolean = true,
    modifier: Modifier = Modifier,
    fullScreen: Boolean = false,
    themeMode: ThemeMode = ThemeMode.DARK,
    enableCustomExercises: Boolean = true
) {
    if (!showDialog) return

    val coroutineScope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var showFavoritesOnly by remember { mutableStateOf(false) }
    var showCustomOnly by remember { mutableStateOf(false) }
    var selectedMuscles by remember { mutableStateOf(setOf<String>()) }
    var selectedEquipment by remember { mutableStateOf(setOf<String>()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var exerciseToEdit by remember { mutableStateOf<Exercise?>(null) }

    val customExercises by exerciseRepository.getCustomExercises().collectAsState(initial = emptyList())

    val allExercises by remember(searchQuery, showFavoritesOnly, showCustomOnly) {
        when {
            showCustomOnly -> exerciseRepository.getCustomExercises()
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

    fun clearAllFilters() {
        showFavoritesOnly = false
        showCustomOnly = false
        selectedMuscles = emptySet()
        selectedEquipment = emptySet()
    }

    LaunchedEffect(Unit) {
        exerciseRepository.importExercises()
    }

    // Create/Edit Exercise Dialog
    if (showCreateDialog || exerciseToEdit != null) {
        CreateExerciseDialog(
            existingExercise = exerciseToEdit,
            onSave = { exercise ->
                coroutineScope.launch {
                    if (exerciseToEdit != null) {
                        exerciseRepository.updateCustomExercise(exercise)
                    } else {
                        exerciseRepository.createCustomExercise(exercise)
                    }
                }
                showCreateDialog = false
                exerciseToEdit = null
            },
            onDelete = if (exerciseToEdit != null) {
                {
                    coroutineScope.launch {
                        exerciseToEdit?.id?.let { exerciseRepository.deleteCustomExercise(it) }
                    }
                    exerciseToEdit = null
                }
            } else null,
            onDismiss = {
                showCreateDialog = false
                exerciseToEdit = null
            },
            themeMode = themeMode
        )
    }

    if (fullScreen) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false
            )
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Select Exercise") },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        actions = {
                            if (enableCustomExercises) {
                                IconButton(onClick = { showCreateDialog = true }) {
                                    Icon(Icons.Default.Add, contentDescription = "Create Exercise")
                                }
                            }
                        }
                    )
                }
            ) { paddingValues ->
                Box(modifier = Modifier.padding(paddingValues)) {
                    ExercisePickerContent(
                        exercises = exercises,
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        showFavoritesOnly = showFavoritesOnly,
                        onToggleFavorites = {
                            showFavoritesOnly = !showFavoritesOnly
                            if (showFavoritesOnly) showCustomOnly = false
                        },
                        showCustomOnly = showCustomOnly,
                        onToggleCustom = {
                            showCustomOnly = !showCustomOnly
                            if (showCustomOnly) showFavoritesOnly = false
                        },
                        customExerciseCount = customExercises.size,
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
                        onClearAllFilters = { clearAllFilters() },
                        onExerciseSelected = {
                            onExerciseSelected(it)
                            onDismiss()
                        },
                        onToggleFavorite = { exercise ->
                            exercise.id?.let {
                                coroutineScope.launch {
                                    exerciseRepository.toggleFavorite(it)
                                }
                            }
                        },
                        exerciseRepository = exerciseRepository,
                        enableVideoPlayback = enableVideoPlayback,
                        enableCustomExercises = enableCustomExercises,
                        onCreateExercise = { showCreateDialog = true },
                        fullScreen = true
                    )
                }
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            ExercisePickerContent(
                exercises = exercises,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                showFavoritesOnly = showFavoritesOnly,
                onToggleFavorites = {
                    showFavoritesOnly = !showFavoritesOnly
                    if (showFavoritesOnly) showCustomOnly = false
                },
                showCustomOnly = showCustomOnly,
                onToggleCustom = {
                    showCustomOnly = !showCustomOnly
                    if (showCustomOnly) showFavoritesOnly = false
                },
                customExerciseCount = customExercises.size,
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
                onClearAllFilters = { clearAllFilters() },
                onExerciseSelected = {
                    onExerciseSelected(it)
                    onDismiss()
                },
                onToggleFavorite = { exercise ->
                    exercise.id?.let {
                        coroutineScope.launch {
                            exerciseRepository.toggleFavorite(it)
                        }
                    }
                },
                exerciseRepository = exerciseRepository,
                enableVideoPlayback = enableVideoPlayback,
                enableCustomExercises = enableCustomExercises,
                onCreateExercise = { showCreateDialog = true },
                fullScreen = false
            )
        }
    }
}

/**
 * Exercise Picker Content - The main content for exercise selection
 */
@Composable
fun ExercisePickerContent(
    exercises: List<Exercise>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    showFavoritesOnly: Boolean,
    onToggleFavorites: () -> Unit,
    showCustomOnly: Boolean,
    onToggleCustom: () -> Unit,
    customExerciseCount: Int,
    selectedMuscles: Set<String>,
    onToggleMuscle: (String) -> Unit,
    selectedEquipment: Set<String>,
    onToggleEquipment: (String) -> Unit,
    onClearAllFilters: () -> Unit,
    onExerciseSelected: (Exercise) -> Unit,
    onToggleFavorite: (Exercise) -> Unit,
    exerciseRepository: ExerciseRepository,
    enableVideoPlayback: Boolean,
    enableCustomExercises: Boolean = true,
    onCreateExercise: () -> Unit = {},
    fullScreen: Boolean
) {
    var showVideoDialog by remember { mutableStateOf(false) }
    var videoDialogExercise by remember { mutableStateOf<Exercise?>(null) }
    var videoDialogVideos by remember { mutableStateOf<List<ExerciseVideoEntity>>(emptyList()) }
    val listState = rememberLazyListState()

    val hasActiveFilters = searchQuery.isNotBlank() ||
        showFavoritesOnly ||
        showCustomOnly ||
        selectedMuscles.isNotEmpty() ||
        selectedEquipment.isNotEmpty()

    // Video dialog
    if (showVideoDialog && videoDialogVideos.isNotEmpty() && videoDialogExercise != null) {
        ExerciseVideoDialog(
            exerciseName = videoDialogExercise!!.name,
            videos = videoDialogVideos,
            enableVideoPlayback = enableVideoPlayback,
            onDismiss = {
                showVideoDialog = false
                videoDialogExercise = null
                videoDialogVideos = emptyList()
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (fullScreen) Modifier.fillMaxHeight() else Modifier.fillMaxHeight(0.9f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Title (only in bottom sheet mode)
            if (!fullScreen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Select Exercise",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (enableCustomExercises) {
                        IconButton(onClick = onCreateExercise) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Create Exercise",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Search field (floating style)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
                placeholder = { Text("Search exercises...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search")
                        }
                    }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(28.dp)
            )

            // Unified filter shelf
            ExerciseFilterShelf(
                showFavoritesOnly = showFavoritesOnly,
                onToggleFavorites = onToggleFavorites,
                showCustomOnly = showCustomOnly,
                onToggleCustom = onToggleCustom,
                selectedMuscles = selectedMuscles,
                onToggleMuscle = onToggleMuscle,
                selectedEquipment = selectedEquipment,
                onToggleEquipment = onToggleEquipment,
                onClearAll = onClearAllFilters,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Grouped exercise list
            GroupedExerciseList(
                exercises = exercises,
                exerciseRepository = exerciseRepository,
                onExerciseSelected = onExerciseSelected,
                onToggleFavorite = onToggleFavorite,
                onShowVideo = { exercise, videos ->
                    videoDialogExercise = exercise
                    videoDialogVideos = videos
                    showVideoDialog = true
                },
                listState = listState,
                modifier = Modifier.weight(1f),
                emptyContent = {
                    ExerciseListEmptyState(
                        hasActiveFilters = hasActiveFilters,
                        showCustomOnly = showCustomOnly,
                        customExerciseCount = customExerciseCount,
                        enableCustomExercises = enableCustomExercises,
                        onClearFilters = onClearAllFilters,
                        onCreateExercise = onCreateExercise
                    )
                }
            )
        }
    }
}

/**
 * Exercise Video Dialog
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExerciseVideoDialog(
    exerciseName: String,
    videos: List<ExerciseVideoEntity>,
    enableVideoPlayback: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedAngle by remember {
        mutableStateOf(
            videos.firstOrNull { it.angle == "FRONT" }?.angle
                ?: videos.firstOrNull()?.angle
                ?: "FRONT"
        )
    }

    val currentVideo = videos.firstOrNull { it.angle == selectedAngle }
        ?: videos.firstOrNull()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = exerciseName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close")
                }
            }

            // Angle selection chips if multiple angles
            if (videos.size > 1) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    items(videos) { video ->
                        FilterChip(
                            selected = selectedAngle == video.angle,
                            onClick = { selectedAngle = video.angle },
                            label = { Text(video.angle.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
            }

            // Video player area
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (enableVideoPlayback) {
                    VideoPlayer(
                        videoUrl = currentVideo?.videoUrl,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // Show thumbnail when video playback is disabled
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        currentVideo?.thumbnailUrl?.let { thumbnailUrl ->
                            val formattedUrl = if (thumbnailUrl.contains("image.mux.com") && !thumbnailUrl.contains("?")) {
                                "$thumbnailUrl?width=600&height=400"
                            } else {
                                thumbnailUrl
                            }
                            SubcomposeAsyncImage(
                                model = ImageRequest.Builder(LocalPlatformContext.current)
                                    .data(formattedUrl)
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Video thumbnail",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } ?: Text(
                            text = "Video playback disabled",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
