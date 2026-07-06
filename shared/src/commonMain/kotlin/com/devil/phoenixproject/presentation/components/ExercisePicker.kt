package com.devil.phoenixproject.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.ExerciseVideoEntity
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.presentation.components.exercisepicker.ExerciseFilterShelf
import com.devil.phoenixproject.presentation.components.exercisepicker.ExerciseListEmptyState
import com.devil.phoenixproject.presentation.components.exercisepicker.GroupedExerciseList
import com.devil.phoenixproject.presentation.util.isCompactAccessibilityLayout
import com.devil.phoenixproject.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.cd_back
import vitruvianprojectphoenix.shared.generated.resources.cd_clear_search
import vitruvianprojectphoenix.shared.generated.resources.cd_close
import vitruvianprojectphoenix.shared.generated.resources.cd_search
import vitruvianprojectphoenix.shared.generated.resources.cd_video_thumbnail
import vitruvianprojectphoenix.shared.generated.resources.search_exercises
import vitruvianprojectphoenix.shared.generated.resources.select_exercise

/**
 * Map display equipment names back to database values for filtering
 */
internal fun getEquipmentDatabaseValues(displayName: String): List<String> = when (displayName) {
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
    enableCustomExercises: Boolean = true,
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
                val editExerciseId = exerciseToEdit?.id
                showCreateDialog = false
                exerciseToEdit = null
                val action = resolveCustomExerciseSaveAction(
                    draftExercise = exercise,
                    editingExerciseId = editExerciseId,
                )
                coroutineScope.launch {
                    when (action) {
                        is CustomExerciseSaveAction.Create -> {
                            exerciseRepository.createCustomExercise(action.exercise)
                        }

                        is CustomExerciseSaveAction.Update -> {
                            exerciseRepository.updateCustomExercise(action.exercise)
                        }
                    }
                }
            },
            onDelete = if (exerciseToEdit != null) {
                {
                    val deleteExerciseId = exerciseToEdit?.id
                    showCreateDialog = false
                    exerciseToEdit = null
                    val targetId = resolveCustomExerciseDeleteTarget(deleteExerciseId)
                    coroutineScope.launch {
                        targetId?.let { exerciseRepository.deleteCustomExercise(it) }
                    }
                }
            } else {
                null
            },
            onDismiss = {
                showCreateDialog = false
                exerciseToEdit = null
            },
            themeMode = themeMode,
        )
    }

    if (fullScreen) {
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
                        title = { Text(stringResource(Res.string.select_exercise)) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.cd_back))
                            }
                        },
                    )
                },
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
                        onEditExercise = { exercise -> exerciseToEdit = exercise },
                        fullScreen = true,
                    )
                }
            }
        }
    } else {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            modifier = modifier,
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
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
                onEditExercise = { exercise -> exerciseToEdit = exercise },
                fullScreen = false,
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
    onEditExercise: ((Exercise) -> Unit)? = null,
    onViewExerciseDetail: ((Exercise) -> Unit)? = null,
    fullScreen: Boolean,
) {
    var showVideoDialog by remember { mutableStateOf(false) }
    var videoDialogExercise by remember { mutableStateOf<Exercise?>(null) }
    var videoDialogVideos by remember { mutableStateOf<List<ExerciseVideoEntity>>(emptyList()) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val useCompactAccessibility = isCompactAccessibilityLayout()

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
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .then(if (fullScreen) Modifier.fillMaxHeight() else Modifier.fillMaxHeight(0.9f))
            // Tap outside search field dismisses keyboard
            .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Title (only in bottom sheet mode). Omit it in compact accessibility
            // mode so search results keep usable space above the iOS keyboard.
            if (!fullScreen && !useCompactAccessibility) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Select Exercise",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Search field (floating style)
            ConfirmEditTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp),
                placeholder = { Text(stringResource(Res.string.search_exercises)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = stringResource(Res.string.cd_search)) },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.cd_clear_search))
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
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
                modifier = Modifier.padding(bottom = 8.dp),
            )

            if (enableCustomExercises) {
                val createButtonLabel = remember(searchQuery) {
                    val trimmed = searchQuery.trim()
                    if (trimmed.isNotEmpty()) {
                        "Create \"$trimmed\""
                    } else {
                        "Create Custom Exercise"
                    }
                }
                OutlinedButton(
                    onClick = onCreateExercise,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 8.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = createButtonLabel,
                        maxLines = if (useCompactAccessibility) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

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
                onEditExercise = if (enableCustomExercises) onEditExercise else null,
                onViewExerciseDetail = onViewExerciseDetail,
                listState = listState,
                modifier = Modifier.weight(1f),
                emptyContent = {
                    ExerciseListEmptyState(
                        hasActiveFilters = hasActiveFilters,
                        showCustomOnly = showCustomOnly,
                        customExerciseCount = customExerciseCount,
                        enableCustomExercises = enableCustomExercises,
                        onClearFilters = onClearAllFilters,
                        onCreateExercise = onCreateExercise,
                    )
                },
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
    modifier: Modifier = Modifier,
) {
    var selectedAngle by remember {
        mutableStateOf(
            videos.firstOrNull { it.angle == "FRONT" }?.angle
                ?: videos.firstOrNull()?.angle
                ?: "FRONT",
        )
    }

    val currentVideo = videos.firstOrNull { it.angle == selectedAngle }
        ?: videos.firstOrNull()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = exerciseName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.cd_close))
                }
            }

            // Angle selection chips if multiple angles
            if (videos.size > 1) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 16.dp),
                ) {
                    items(videos) { video ->
                        FilterChip(
                            selected = selectedAngle == video.angle,
                            onClick = { selectedAngle = video.angle },
                            label = { Text(video.angle.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
            }

            // Video player area
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f),
                shape = MaterialTheme.shapes.small,
            ) {
                if (enableVideoPlayback) {
                    VideoPlayer(
                        videoUrl = currentVideo?.videoUrl,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // Show thumbnail when video playback is disabled
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
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
                                contentDescription = stringResource(Res.string.cd_video_thumbnail),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } ?: Text(
                            text = "Video playback disabled",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
