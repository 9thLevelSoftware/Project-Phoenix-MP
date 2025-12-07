# Exercise Picker Redesign Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Redesign the ExercisePicker to compress filter controls from 4 rows to 1 unified shelf, add alphabetical grouping with sticky headers and tap-to-jump navigation, and implement swipe-to-favorite on list items.

**Architecture:** Replace the current header stack (search + 2 switches + 2 chip rows) with a single `ExerciseFilterShelf` component. Replace the flat `LazyColumn` with a `GroupedExerciseList` that uses `stickyHeader` and an `AlphabetStrip` overlay. Wrap list items in `SwipeToDismissBox` for favorite toggling.

**Tech Stack:** Kotlin, Compose Multiplatform, Material 3

**Working Directory:** `C:\Users\dasbl\AndroidStudioProjects\Project-Phoenix-MP\.worktrees\exercise-picker-redesign`

---

## Task 1: Create LetterHeader Component

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/exercisepicker/LetterHeader.kt`

**Step 1: Create the component file**

```kotlin
package com.devil.phoenixproject.presentation.components.exercisepicker

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Sticky header for alphabetical sections in the exercise list.
 * Semi-transparent background so content shows through slightly.
 */
@Composable
fun LetterHeader(
    letter: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = letter,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
```

**Step 2: Verify it compiles**

Run: `./gradlew :shared:compileKotlinMetadata --no-daemon -q`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/exercisepicker/
git commit -m "feat(exercise-picker): add LetterHeader component for sticky alphabetical sections"
```

---

## Task 2: Create AlphabetStrip Component

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/exercisepicker/AlphabetStrip.kt`

**Step 1: Create the component file**

```kotlin
package com.devil.phoenixproject.presentation.components.exercisepicker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Vertical alphabet strip for quick navigation to exercise sections.
 * Only shows letters that have exercises.
 */
@Composable
fun AlphabetStrip(
    letters: List<Char>,
    onLetterTap: (Char) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f))
            .padding(horizontal = 4.dp, vertical = 8.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            letters.forEach { letter ->
                Text(
                    text = letter.toString(),
                    modifier = Modifier
                        .clickable { onLetterTap(letter) }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

**Step 2: Verify it compiles**

Run: `./gradlew :shared:compileKotlinMetadata --no-daemon -q`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/exercisepicker/AlphabetStrip.kt
git commit -m "feat(exercise-picker): add AlphabetStrip component for tap-to-jump navigation"
```

---

## Task 3: Create ExerciseFilterShelf Component

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/exercisepicker/ExerciseFilterShelf.kt`

**Step 1: Create the component file**

```kotlin
package com.devil.phoenixproject.presentation.components.exercisepicker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Unified horizontal filter shelf combining favorites, custom, muscle, and equipment filters.
 * Replaces the previous 4-row filter UI with a single scrollable row.
 */
@Composable
fun ExerciseFilterShelf(
    showFavoritesOnly: Boolean,
    onToggleFavorites: () -> Unit,
    showCustomOnly: Boolean,
    onToggleCustom: () -> Unit,
    selectedMuscles: Set<String>,
    onToggleMuscle: (String) -> Unit,
    selectedEquipment: Set<String>,
    onToggleEquipment: (String) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    val muscleGroups = listOf("Chest", "Back", "Legs", "Shoulders", "Arms", "Core")
    val equipmentTypes = listOf("Long Bar", "Short Bar", "Handles", "Rope", "Belt", "Ankle Strap", "Bench", "Bodyweight")

    val hasActiveFilters = showFavoritesOnly || showCustomOnly ||
        selectedMuscles.isNotEmpty() || selectedEquipment.isNotEmpty()

    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.height(48.dp)
    ) {
        // Clear button (only when filters active)
        if (hasActiveFilters) {
            item {
                InputChip(
                    selected = false,
                    onClick = onClearAll,
                    label = { Text("Clear") },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Clear filters",
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    colors = InputChipDefaults.inputChipColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        labelColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                )
            }
        }

        // Favorites chip
        item {
            FilterChip(
                selected = showFavoritesOnly,
                onClick = onToggleFavorites,
                label = { Text("Favorites") },
                leadingIcon = {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }

        // Custom chip
        item {
            FilterChip(
                selected = showCustomOnly,
                onClick = onToggleCustom,
                label = { Text("Custom") },
                leadingIcon = {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }

        // Divider
        item {
            VerticalDivider(
                modifier = Modifier.height(24.dp).padding(horizontal = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }

        // Muscle group chips
        items(muscleGroups) { muscle ->
            FilterChip(
                selected = muscle in selectedMuscles,
                onClick = { onToggleMuscle(muscle) },
                label = { Text(muscle) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            )
        }

        // Divider
        item {
            VerticalDivider(
                modifier = Modifier.height(24.dp).padding(horizontal = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }

        // Equipment chips
        items(equipmentTypes) { equipment ->
            FilterChip(
                selected = equipment in selectedEquipment,
                onClick = { onToggleEquipment(equipment) },
                label = { Text(equipment) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            )
        }
    }
}
```

**Step 2: Verify it compiles**

Run: `./gradlew :shared:compileKotlinMetadata --no-daemon -q`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/exercisepicker/ExerciseFilterShelf.kt
git commit -m "feat(exercise-picker): add ExerciseFilterShelf unified filter component"
```

---

## Task 4: Create ExerciseRowContent Component

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/exercisepicker/ExerciseRowContent.kt`

**Step 1: Create the component file**

```kotlin
package com.devil.phoenixproject.presentation.components.exercisepicker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
@Composable
fun ExerciseRowContent(
    exercise: Exercise,
    thumbnailUrl: String?,
    isLoadingThumbnail: Boolean,
    onClick: () -> Unit,
    onThumbnailClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
        .firstOrNull() ?: ""
}
```

**Step 2: Verify it compiles**

Run: `./gradlew :shared:compileKotlinMetadata --no-daemon -q`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/exercisepicker/ExerciseRowContent.kt
git commit -m "feat(exercise-picker): add ExerciseRowContent with 64dp thumbnail and inline favorite dot"
```

---

## Task 5: Create SwipeableExerciseRow Component

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/exercisepicker/SwipeableExerciseRow.kt`

**Step 1: Create the component file**

```kotlin
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
```

**Step 2: Verify it compiles**

Run: `./gradlew :shared:compileKotlinMetadata --no-daemon -q`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/exercisepicker/SwipeableExerciseRow.kt
git commit -m "feat(exercise-picker): add SwipeableExerciseRow with swipe-to-favorite"
```

---

## Task 6: Create GroupedExerciseList Component

**Files:**
- Create: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/exercisepicker/GroupedExerciseList.kt`

**Step 1: Create the component file**

```kotlin
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
import androidx.compose.material3.Spacer
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
```

**Step 2: Verify it compiles**

Run: `./gradlew :shared:compileKotlinMetadata --no-daemon -q`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/exercisepicker/GroupedExerciseList.kt
git commit -m "feat(exercise-picker): add GroupedExerciseList with sticky headers and alphabet strip"
```

---

## Task 7: Update ExercisePickerContent to Use New Components

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ExercisePicker.kt`

**Step 1: Update imports and add new state types**

At the top of the file, after existing imports, add:

```kotlin
import androidx.compose.foundation.lazy.rememberLazyListState
import com.devil.phoenixproject.presentation.components.exercisepicker.ExerciseFilterShelf
import com.devil.phoenixproject.presentation.components.exercisepicker.ExerciseListEmptyState
import com.devil.phoenixproject.presentation.components.exercisepicker.GroupedExerciseList
```

**Step 2: Update ExercisePickerContent signature**

Replace the current `ExercisePickerContent` function signature (lines 305-326) with:

```kotlin
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
)
```

**Step 3: Replace ExercisePickerContent body**

Replace the entire body of `ExercisePickerContent` (lines 327-536) with:

```kotlin
{
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
```

**Step 4: Verify it compiles**

Run: `./gradlew :shared:compileKotlinMetadata --no-daemon -q`
Expected: BUILD SUCCESSFUL (may have errors - we'll fix in next task)

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ExercisePicker.kt
git commit -m "refactor(exercise-picker): update ExercisePickerContent to use new unified components"
```

---

## Task 8: Update ExercisePickerDialog with New State Management

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ExercisePicker.kt`

**Step 1: Update state variables in ExercisePickerDialog**

Replace the state declarations (lines 107-114) with:

```kotlin
    val coroutineScope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var showFavoritesOnly by remember { mutableStateOf(false) }
    var showCustomOnly by remember { mutableStateOf(false) }
    var selectedMuscles by remember { mutableStateOf(setOf<String>()) }
    var selectedEquipment by remember { mutableStateOf(setOf<String>()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var exerciseToEdit by remember { mutableStateOf<Exercise?>(null) }
```

**Step 2: Update filtering logic**

Replace the allExercises and exercises derivation (lines 116-138) with:

```kotlin
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
```

**Step 3: Update ExercisePickerContent calls**

In both fullScreen and ModalBottomSheet branches, replace the `ExercisePickerContent` call with:

```kotlin
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
                        fullScreen = true // or false depending on branch
                    )
```

**Step 4: Verify it compiles**

Run: `./gradlew :shared:compileKotlinMetadata --no-daemon -q`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ExercisePicker.kt
git commit -m "refactor(exercise-picker): update ExercisePickerDialog to multi-select filter state"
```

---

## Task 9: Remove Obsolete Code

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ExercisePicker.kt`

**Step 1: Remove old ExerciseListItem function**

Delete the entire `ExerciseListItem` composable function (lines 542-688 in original file) as it's replaced by `SwipeableExerciseRow` and `ExerciseRowContent`.

**Step 2: Remove old ExerciseThumbnail function**

Delete the `ExerciseThumbnail` composable (lines 694-748 in original file) as it's replaced by `ExerciseThumbnailEnhanced` in `ExerciseRowContent.kt`.

**Step 3: Remove old ExerciseInitial function**

Delete the `ExerciseInitial` composable (lines 753-770 in original file) as it's replaced by `ExerciseInitialEnhanced` in `ExerciseRowContent.kt`.

**Step 4: Verify it compiles**

Run: `./gradlew :shared:compileKotlinMetadata --no-daemon -q`
Expected: BUILD SUCCESSFUL

**Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ExercisePicker.kt
git commit -m "refactor(exercise-picker): remove obsolete ExerciseListItem and related components"
```

---

## Task 10: Build Android App and Verify

**Step 1: Build Android debug APK**

Run: `./gradlew :androidApp:assembleDebug --no-daemon`
Expected: BUILD SUCCESSFUL

**Step 2: Verify no compile errors**

Run: `./gradlew :shared:allTests --no-daemon`
Expected: All tests pass

**Step 3: Final commit**

```bash
git add -A
git commit -m "feat(exercise-picker): complete Unified Shelf redesign

- Compress 4 filter rows into 1 unified ExerciseFilterShelf
- Add alphabetical sticky headers with LetterHeader
- Add tap-to-jump AlphabetStrip navigation
- Implement swipe-to-favorite via SwipeableExerciseRow
- Upgrade thumbnails to 64dp with custom exercise border indicator
- Add inline favorite dot indicator
- Multi-select support for muscle and equipment filters
- Reclaim ~144dp of vertical space for exercise list"
```

---

## Summary

| Task | Component | Purpose |
|------|-----------|---------|
| 1 | LetterHeader | Sticky alphabetical section headers |
| 2 | AlphabetStrip | Tap-to-jump letter navigation |
| 3 | ExerciseFilterShelf | Unified horizontal filter row |
| 4 | ExerciseRowContent | Enhanced list item with 64dp thumbnail |
| 5 | SwipeableExerciseRow | Swipe-to-favorite wrapper |
| 6 | GroupedExerciseList | Grouped list with alphabet strip overlay |
| 7 | ExercisePickerContent | Wire up new components |
| 8 | ExercisePickerDialog | Multi-select state management |
| 9 | Cleanup | Remove obsolete code |
| 10 | Verify | Build and test |

**Estimated commits:** 10
**New files:** 6
**Modified files:** 1
