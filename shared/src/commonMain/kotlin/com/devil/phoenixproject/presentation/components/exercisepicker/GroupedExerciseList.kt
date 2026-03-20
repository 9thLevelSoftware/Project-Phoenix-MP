package com.devil.phoenixproject.presentation.components.exercisepicker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.data.repository.ExerciseRepository
import com.devil.phoenixproject.data.repository.ExerciseVideoEntity
import com.devil.phoenixproject.domain.model.Exercise
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.*

/**
 * Grouped exercise list with sticky alphabetical headers and alphabet strip navigation.
 */
@Composable
fun GroupedExerciseList(
    exercises: List<Exercise>,
    exerciseRepository: ExerciseRepository,
    onExerciseSelected: (Exercise) -> Unit,
    onToggleFavorite: (Exercise) -> Unit,
    onShowVideo: (Exercise, List<ExerciseVideoEntity>) -> Unit,
    onEditExercise: ((Exercise) -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
    emptyContent: @Composable () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()

    // Track which exercise row is currently revealed (only one at a time)
    var revealedExerciseId by remember { mutableStateOf<String?>(null) }

    if (exercises.isEmpty()) {
        emptyContent()
        return
    }

    val groupedExercises: Map<Char, List<Exercise>> = remember(exercises) {
        exercises
            .groupBy { exercise: Exercise -> exercise.name.first().uppercaseChar() }
            .toList()
            .sortedBy { it.first }
            .toMap()
    }

    val sectionIndices: Map<Char, Int> = remember(groupedExercises) {
        var index = 0
        groupedExercises.mapValues { (_, list: List<Exercise>) ->
            val sectionIndex = index
            index += 1 + list.size // header + items
            sectionIndex
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            groupedExercises.forEach { (letter: Char, exerciseList: List<Exercise>) ->
                stickyHeader(key = "header_$letter") {
                    LetterHeader(letter = letter.toString())
                }

                items(
                    items = exerciseList,
                    key = { exercise: Exercise -> exercise.id ?: exercise.name }
                ) { exercise: Exercise ->
                    ExerciseItemWithVideo(
                        exercise = exercise,
                        exerciseRepository = exerciseRepository,
                        onSelect = { onExerciseSelected(exercise) },
                        onToggleFavorite = { onToggleFavorite(exercise) },
                        onShowVideo = { videos -> onShowVideo(exercise, videos) },
                        onLongPress = if (exercise.isCustom && onEditExercise != null) {
                            { onEditExercise(exercise) }
                        } else null,
                        isRevealed = exercise.id == revealedExerciseId,
                        onRevealChange = { revealed ->
                            revealedExerciseId = if (revealed) exercise.id else null
                        }
                    )
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
        }

        // Alphabet strip overlay
        AlphabetStrip(
            letters = groupedExercises.keys.toList(),
            onLetterTap = { letter ->
                revealedExerciseId = null // Close any revealed row when jumping
                sectionIndices[letter]?.let { index ->
                    coroutineScope.launch {
                        listState.animateScrollToItem(index)
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
        )
    }
}

@Composable
private fun ExerciseItemWithVideo(
    exercise: Exercise,
    exerciseRepository: ExerciseRepository,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShowVideo: (List<ExerciseVideoEntity>) -> Unit,
    onLongPress: (() -> Unit)? = null,
    isRevealed: Boolean = false,
    onRevealChange: (Boolean) -> Unit = {}
) {
    var videos by remember { mutableStateOf<List<ExerciseVideoEntity>>(emptyList()) }
    var isLoadingVideo by remember { mutableStateOf(true) }

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

    val thumbnailUrl = remember(videos) {
        val baseThumbnailUrl = videos.firstOrNull { it.angle == "FRONT" }?.thumbnailUrl
            ?: videos.firstOrNull()?.thumbnailUrl
        baseThumbnailUrl?.let { url ->
            if (url.contains("image.mux.com") && !url.contains("?")) {
                "$url?width=300&height=300&fit_mode=crop&crop=center&time=2"
            } else {
                url
            }
        }
    }

    SwipeableExerciseRow(
        exercise = exercise,
        thumbnailUrl = thumbnailUrl,
        isLoadingThumbnail = isLoadingVideo,
        onSelect = onSelect,
        onToggleFavorite = onToggleFavorite,
        onLongPress = onLongPress,
        onThumbnailClick = if (videos.isNotEmpty()) {
            { onShowVideo(videos) }
        } else null,
        isRevealed = isRevealed,
        onRevealChange = onRevealChange
    )
}

/**
 * Empty state for when no exercises match filters.
 */
@Composable
fun ExerciseListEmptyState(
    hasActiveFilters: Boolean,
    showCustomOnly: Boolean,
    customExerciseCount: Int,
    enableCustomExercises: Boolean,
    onClearFilters: () -> Unit,
    onCreateExercise: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when {
            showCustomOnly && customExerciseCount == 0 -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "No custom exercises yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Create your own exercises to track workouts\nnot in the library",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    if (enableCustomExercises) {
                        Button(onClick = onCreateExercise) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = androidx.compose.ui.Modifier.padding(4.dp))
                            Text(stringResource(Res.string.create_exercise))
                        }
                    }
                }
            }
            hasActiveFilters -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "No exercises found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onClearFilters) {
                        Text(stringResource(Res.string.clear_filters))
                    }
                }
            }
            else -> {
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
    }
}
