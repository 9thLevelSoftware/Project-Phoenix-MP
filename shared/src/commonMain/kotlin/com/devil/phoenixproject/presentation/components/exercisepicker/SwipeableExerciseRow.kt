package com.devil.phoenixproject.presentation.components.exercisepicker

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.domain.model.Exercise
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val REVEAL_WIDTH = 56.dp
private val THRESHOLD_RATIO = 0.4f // Drag 40% of reveal width to trigger

/**
 * Exercise row with swipe-to-reveal favorite button.
 *
 * Swipe RIGHT to reveal star button on the LEFT. Button stays revealed until:
 * - Tapped (toggles favorite)
 * - Swiped back left
 * - Another row is revealed (handled by parent via revealedExerciseId)
 */
@Composable
fun SwipeableExerciseRow(
    exercise: Exercise,
    thumbnailUrl: String?,
    isLoadingThumbnail: Boolean,
    onSelect: () -> Unit,
    onToggleFavorite: () -> Unit,
    onThumbnailClick: (() -> Unit)? = null,
    isRevealed: Boolean = false,
    onRevealChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val revealWidthPx = with(density) { REVEAL_WIDTH.toPx() }
    val thresholdPx = revealWidthPx * THRESHOLD_RATIO
    val scope = rememberCoroutineScope()

    // Single animatable for smooth drag + animate
    val offsetX = remember { Animatable(0f) }

    // Animate to target when isRevealed changes externally
    LaunchedEffect(isRevealed) {
        val target = if (isRevealed) revealWidthPx else 0f
        if (offsetX.value != target) {
            offsetX.animateTo(target, tween(200))
        }
    }

    Box(modifier = modifier) {
        // Background with star button on LEFT side
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(REVEAL_WIDTH)
                .fillMaxHeight()
                .background(
                    if (exercise.isFavorite) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = {
                    onToggleFavorite()
                    onRevealChange(false)
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (exercise.isFavorite) {
                        Icons.Filled.Star
                    } else {
                        Icons.Outlined.Star
                    },
                    contentDescription = if (exercise.isFavorite) {
                        "Remove from favorites"
                    } else {
                        "Add to favorites"
                    },
                    tint = if (exercise.isFavorite) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    },
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Foreground content (slides RIGHT to reveal button on left)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                // Determine target based on current position
                                val shouldReveal = offsetX.value > thresholdPx
                                val target = if (shouldReveal) revealWidthPx else 0f
                                offsetX.animateTo(target, tween(150))
                                onRevealChange(shouldReveal)
                            }
                        },
                        onDragCancel = {
                            scope.launch {
                                val target = if (isRevealed) revealWidthPx else 0f
                                offsetX.animateTo(target, tween(150))
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            scope.launch {
                                val newValue = (offsetX.value + dragAmount).coerceIn(0f, revealWidthPx)
                                offsetX.snapTo(newValue)
                            }
                        }
                    )
                }
        ) {
            ExerciseRowContent(
                exercise = exercise,
                thumbnailUrl = thumbnailUrl,
                isLoadingThumbnail = isLoadingThumbnail,
                onClick = {
                    if (offsetX.value > 0f) {
                        scope.launch {
                            offsetX.animateTo(0f, tween(150))
                            onRevealChange(false)
                        }
                    } else {
                        onSelect()
                    }
                },
                onThumbnailClick = onThumbnailClick
            )
        }
    }
}
