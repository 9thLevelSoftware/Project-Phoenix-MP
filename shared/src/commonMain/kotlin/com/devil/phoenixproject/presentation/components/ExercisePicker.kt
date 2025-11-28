package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
    fullScreen: Boolean = false
) {
    if (!showDialog) return

    var searchQuery by remember { mutableStateOf("") }
    var selectedMuscleFilter by remember { mutableStateOf("All") }
    var selectedEquipmentFilter by remember { mutableStateOf("All") }
    var showFavoritesOnly by remember { mutableStateOf(false) }

    val allExercises by remember(searchQuery, selectedMuscleFilter, showFavoritesOnly) {
        when {
            showFavoritesOnly -> exerciseRepository.getFavorites()
            searchQuery.isNotBlank() -> exerciseRepository.searchExercises(searchQuery)
            selectedMuscleFilter != "All" -> exerciseRepository.filterByMuscleGroup(selectedMuscleFilter)
            else -> exerciseRepository.getAllExercises()
        }
    }.collectAsState(initial = emptyList())

    val exercises = remember(allExercises, selectedEquipmentFilter) {
        if (selectedEquipmentFilter != "All") {
            allExercises.filter { exercise ->
                val databaseValues = getEquipmentDatabaseValues(selectedEquipmentFilter)
                val equipmentList = exercise.equipment.uppercase().split(",").map { it.trim() }
                databaseValues.any { dbValue -> equipmentList.contains(dbValue.uppercase()) }
            }
        } else {
            allExercises
        }
    }

    LaunchedEffect(Unit) {
        exerciseRepository.importExercises()
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
                        onExerciseSelected = {
                            onExerciseSelected(it)
                            onDismiss()
                        },
                        exerciseRepository = exerciseRepository,
                        enableVideoPlayback = enableVideoPlayback,
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
                onExerciseSelected = {
                    onExerciseSelected(it)
                    onDismiss()
                },
                exerciseRepository = exerciseRepository,
                enableVideoPlayback = enableVideoPlayback,
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
    onShowFavoritesOnlyChange: (Boolean) -> Unit,
    selectedMuscleFilter: String,
    onMuscleFilterChange: (String) -> Unit,
    selectedEquipmentFilter: String,
    onEquipmentFilterChange: (String) -> Unit,
    onExerciseSelected: (Exercise) -> Unit,
    exerciseRepository: ExerciseRepository,
    enableVideoPlayback: Boolean,
    fullScreen: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (fullScreen) Modifier.fillMaxHeight() else Modifier.fillMaxHeight(0.9f))
            .padding(horizontal = 16.dp)
    ) {
        // Title (only in bottom sheet mode)
        if (!fullScreen) {
            Text(
                text = "Select Exercise",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }

        // Search field
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            placeholder = { Text("Search exercises...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            singleLine = true
        )

        // Favorites toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Show Favorites Only",
                style = MaterialTheme.typography.labelMedium
            )
            Switch(
                checked = showFavoritesOnly,
                onCheckedChange = onShowFavoritesOnlyChange
            )
        }

        // Muscle group filter chips
        Text(
            text = "Muscle Groups",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            val muscleFilters = listOf("All", "Chest", "Back", "Legs", "Shoulders", "Arms", "Core")
            items(muscleFilters) { filter ->
                FilterChip(
                    selected = selectedMuscleFilter == filter,
                    onClick = { onMuscleFilterChange(filter) },
                    label = { Text(filter) }
                )
            }
        }

        // Equipment filter chips
        Text(
            text = "Equipment",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            val equipmentFilters = listOf(
                "All", "Long Bar", "Short Bar", "Handles", "Rope",
                "Belt", "Ankle Strap", "Bench", "Bodyweight"
            )
            items(equipmentFilters) { filter ->
                FilterChip(
                    selected = selectedEquipmentFilter == filter,
                    onClick = { onEquipmentFilterChange(filter) },
                    label = { Text(filter) }
                )
            }
        }

        // Exercise list
        if (exercises.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val hasActiveFilters = searchQuery.isNotBlank() ||
                        selectedMuscleFilter != "All" ||
                        selectedEquipmentFilter != "All"

                if (hasActiveFilters) {
                    Text(
                        text = "No exercises found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "Loading exercises...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(exercises) { exercise ->
                    ExerciseListItem(
                        exercise = exercise,
                        exerciseRepository = exerciseRepository,
                        onClick = { onExerciseSelected(exercise) },
                        enableVideoPlayback = enableVideoPlayback
                    )
                }
            }
        }
    }
}

/**
 * Exercise list item
 */
@Composable
private fun ExerciseListItem(
    exercise: Exercise,
    exerciseRepository: ExerciseRepository,
    onClick: () -> Unit,
    enableVideoPlayback: Boolean,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var videos by remember { mutableStateOf<List<ExerciseVideoEntity>>(emptyList()) }
    var isLoadingVideo by remember { mutableStateOf(true) }
    var showVideoDialog by remember { mutableStateOf(false) }

    LaunchedEffect(exercise.id) {
        try {
            exercise.id?.let {
                videos = exerciseRepository.getVideos(it)
            }
            isLoadingVideo = false
        } catch (e: Exception) {
            isLoadingVideo = false
        }
    }

    val baseThumbnailUrl = videos.firstOrNull { it.angle == "FRONT" }?.thumbnailUrl
        ?: videos.firstOrNull()?.thumbnailUrl
    val thumbnailUrl = baseThumbnailUrl?.let { url ->
        if (url.contains("image.mux.com") && !url.contains("?")) {
            "$url?width=300&height=300&fit_mode=crop&crop=center&time=2"
        } else {
            url
        }
    }

    if (showVideoDialog && videos.isNotEmpty()) {
        ExerciseVideoDialog(
            exerciseName = exercise.name,
            videos = videos,
            enableVideoPlayback = enableVideoPlayback,
            onDismiss = { showVideoDialog = false }
        )
    }

    ListItem(
        headlineContent = { Text(exercise.name) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (exercise.muscleGroups.isNotBlank()) {
                    Text(
                        text = "Muscle Group: ${exercise.muscleGroups.split(",").joinToString(", ") { it.trim().lowercase().replaceFirstChar { c -> c.uppercase() } }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (exercise.equipment.isNotBlank() && exercise.equipment.lowercase() != "null") {
                    val formattedEquipment = formatEquipment(exercise.equipment)
                    if (formattedEquipment.isNotBlank()) {
                        Text(
                            text = "Equipment: $formattedEquipment",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        leadingContent = {
            ExerciseThumbnail(
                thumbnailUrl = thumbnailUrl,
                exerciseName = exercise.name,
                isLoading = isLoadingVideo,
                onClick = if (videos.isNotEmpty()) {
                    { showVideoDialog = true }
                } else null
            )
        },
        trailingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (exercise.timesPerformed > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = "Performed ${exercise.timesPerformed}x",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                IconButton(
                    onClick = {
                        exercise.id?.let {
                            coroutineScope.launch {
                                exerciseRepository.toggleFavorite(it)
                            }
                        }
                    }
                ) {
                    Icon(
                        imageVector = if (exercise.isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = if (exercise.isFavorite) "Remove from favorites" else "Add to favorites",
                        tint = if (exercise.isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        modifier = modifier.clickable(onClick = onClick)
    )
}

/**
 * Exercise thumbnail with async image loading
 */
@Composable
private fun ExerciseThumbnail(
    thumbnailUrl: String?,
    exerciseName: String,
    isLoading: Boolean,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val platformContext = LocalPlatformContext.current

    Box(
        modifier = modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
            }
            !thumbnailUrl.isNullOrBlank() -> {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(platformContext)
                        .data(thumbnailUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "Thumbnail for $exerciseName",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    loading = {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                    },
                    error = {
                        ExerciseInitial(exerciseName)
                    },
                    success = {
                        SubcomposeAsyncImageContent()
                    }
                )
            }
            else -> {
                ExerciseInitial(exerciseName)
            }
        }
    }
}

/**
 * Exercise initial - Fallback when no thumbnail available
 */
@Composable
private fun ExerciseInitial(
    exerciseName: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = exerciseName.firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
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
