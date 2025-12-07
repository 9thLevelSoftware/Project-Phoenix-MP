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
    listState: LazyListState = rememberLazyListState(),
    modifier: Modifier = Modifier,
    emptyContent: @Composable () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()

    if (exercises.isEmpty()) {
        emptyContent()
        return
    }

    val groupedExercises = remember(exercises) {
        exercises
            .groupBy { it.name.first().uppercaseChar() }
            .toSortedMap()
    }

    val sectionIndices = remember(groupedExercises) {
        var index = 0
        groupedExercises.mapValues { (_, list) ->
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
            groupedExercises.forEach { (letter, exerciseList) ->
                stickyHeader(key = "header_$letter") {
                    LetterHeader(letter = letter.toString())
                }

                items(
                    items = exerciseList,
                    key = { it.id ?: it.name }
                ) { exercise ->
                    ExerciseItemWithVideo(
                        exercise = exercise,
                        exerciseRepository = exerciseRepository,
                        onSelect = { onExerciseSelected(exercise) },
                        onToggleFavorite = { onToggleFavorite(exercise) },
                        onShowVideo = { videos -> onShowVideo(exercise, videos) }
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
    onShowVideo: (List<ExerciseVideoEntity>) -> Unit
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
        onThumbnailClick = if (videos.isNotEmpty()) {
            { onShowVideo(videos) }
        } else null
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
                            Text("Create Exercise")
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
                        Text("Clear filters")
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
