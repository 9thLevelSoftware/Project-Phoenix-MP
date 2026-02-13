package com.devil.phoenixproject.presentation.components.exercisepicker

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.devil.phoenixproject.domain.model.Exercise

/**
 * Enhanced exercise row content with larger thumbnail, inline favorite indicator,
 * and compact layout.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExerciseRowContent(
    exercise: Exercise,
    thumbnailUrl: String?,
    isLoadingThumbnail: Boolean,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    onThumbnailClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            ),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail (64dp, with custom border indicator)
            ExerciseThumbnailEnhanced(
                thumbnailUrl = thumbnailUrl,
                exerciseName = exercise.name,
                isLoading = isLoadingThumbnail,
                isCustom = exercise.isCustom,
                onClick = onThumbnailClick
            )

            // Content
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Title with favorite indicator
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = exercise.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (exercise.isFavorite) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape
                                )
                        )
                    }
                }

                // Subtitle: Muscle • Equipment
                val subtitle = buildSubtitle(exercise)
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Times performed badge
            if (exercise.timesPerformed > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "${exercise.timesPerformed}x",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun ExerciseThumbnailEnhanced(
    thumbnailUrl: String?,
    exerciseName: String,
    isLoading: Boolean,
    isCustom: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val platformContext = LocalPlatformContext.current
    val borderModifier = if (isCustom) {
        Modifier.border(
            width = 2.dp,
            color = MaterialTheme.colorScheme.tertiary,
            shape = RoundedCornerShape(12.dp)
        )
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .size(64.dp)
            .then(borderModifier)
            .clip(RoundedCornerShape(12.dp))
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
                        ExerciseInitialEnhanced(exerciseName)
                    },
                    success = {
                        SubcomposeAsyncImageContent()
                    }
                )
            }
            else -> {
                ExerciseInitialEnhanced(exerciseName)
            }
        }
    }
}

@Composable
private fun ExerciseInitialEnhanced(
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
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}

private fun buildSubtitle(exercise: Exercise): String {
    val parts = mutableListOf<String>()

    if (exercise.muscleGroups.isNotBlank()) {
        val muscle = exercise.muscleGroups
            .split(",")
            .firstOrNull()
            ?.trim()
            ?.lowercase()
            ?.replaceFirstChar { it.uppercase() }
        if (muscle != null) {
            parts.add(muscle)
        }
    }

    if (exercise.equipment.isNotBlank() && exercise.equipment.lowercase() != "null") {
        val equipment = formatEquipmentCompact(exercise.equipment)
        if (equipment.isNotBlank()) {
            parts.add(equipment)
        }
    }

    return parts.joinToString(" • ")
}

private fun formatEquipmentCompact(rawEquipment: String): String {
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

    return rawEquipment
        .split(",")
        .map { it.trim().uppercase() }
        .filter { it !in listOf("BLACK_CABLES", "RED_CABLES", "GREY_CABLES", "CABLES", "CABLE", "NULL", "", "PUMP_HANDLES", "DUMBBELLS") }
        .mapNotNull { equipmentMap[it] }
        .distinct()
        .joinToString(", ")
}
