package com.devil.phoenixproject.presentation.components.exercisepicker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.Exercise

/**
 * Swipeable wrapper for exercise rows.
 * Swipe right to toggle favorite status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableExerciseRow(
    exercise: Exercise,
    thumbnailUrl: String?,
    isLoadingThumbnail: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onThumbnailClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                onToggleFavorite()
            }
            false // Don't actually dismiss; reset position
        }
    )

    // Reset state when exercise changes (e.g., favorite status updated)
    LaunchedEffect(exercise.isFavorite) {
        dismissState.reset()
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (exercise.isFavorite) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        }
                    ),
                contentAlignment = Alignment.CenterStart
            ) {
                Icon(
                    imageVector = if (exercise.isFavorite) {
                        Icons.Outlined.Star
                    } else {
                        Icons.Filled.Star
                    },
                    contentDescription = if (exercise.isFavorite) {
                        "Remove from favorites"
                    } else {
                        "Add to favorites"
                    },
                    modifier = Modifier.padding(start = 24.dp),
                    tint = if (exercise.isFavorite) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    }
                )
            }
        },
        modifier = modifier
    ) {
        ExerciseRowContent(
            exercise = exercise,
            thumbnailUrl = thumbnailUrl,
            isLoadingThumbnail = isLoadingThumbnail,
            onClick = onSelect,
            onThumbnailClick = onThumbnailClick
        )
    }
}
