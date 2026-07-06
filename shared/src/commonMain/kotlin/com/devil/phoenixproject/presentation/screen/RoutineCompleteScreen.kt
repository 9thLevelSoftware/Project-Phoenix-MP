package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.ui.theme.celebrationBackgroundBrush
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.presentation.components.BackHandler
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import org.jetbrains.compose.resources.stringResource
import vitruvianprojectphoenix.shared.generated.resources.*
import vitruvianprojectphoenix.shared.generated.resources.Res

/**
 * Routine Complete Screen - Celebration after finishing entire routine.
 */
@Composable
fun RoutineCompleteScreen(navController: NavController, viewModel: MainViewModel) {
    val routineFlowState by viewModel.routineFlowState.collectAsState()

    val completeState = routineFlowState as? RoutineFlowState.Complete

    if (completeState == null) {
        LaunchedEffect(Unit) {
            navController.navigateUp()
        }
        return
    }

    // lens-navigation-ux-5: intercept system back so it mirrors Done and calls exitRoutineFlow()
    // before navigating, preventing stale RoutineFlowState.Complete from persisting.
    // Read destination BEFORE exitRoutineFlow() — exitRoutineFlow() clears the launch origin.
    BackHandler {
        val dest = viewModel.routineExitDestination()
        viewModel.exitRoutineFlow()
        navController.popBackStack(dest, false)
    }

    // Pulse animation for celebration
    val infiniteTransition = rememberInfiniteTransition(label = "celebration")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )

    // Format duration
    val durationMinutes = (completeState.totalDurationMs / 60000).toInt()
    val durationSeconds = ((completeState.totalDurationMs % 60000) / 1000).toInt()
    val durationFormatted = if (durationMinutes > 0) {
        "${durationMinutes}m ${durationSeconds}s"
    } else {
        "${durationSeconds}s"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(celebrationBackgroundBrush())
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.extraLarge),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            // Celebration icon
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .scale(scale)
                    .background(
                        MaterialTheme.colorScheme.primary,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.EmojiEvents,
                    "Trophy",
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.onPrimary,
                )
            }

            // Congratulations text
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "ROUTINE COMPLETE!",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(Spacing.small))
                Text(
                    completeState.routineName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            // Stats card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.large),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    StatItem(
                        icon = Icons.Default.FitnessCenter,
                        value = "${completeState.totalExercises}",
                        label = "Exercises",
                    )
                    StatItem(
                        icon = Icons.Default.Repeat,
                        value = "${completeState.totalSets}",
                        label = "Sets",
                    )
                    StatItem(
                        icon = Icons.Default.Timer,
                        value = durationFormatted,
                        label = "Duration",
                    )
                }
            }

            // Done button
            // lens-navigation-ux-2: read destination BEFORE exitRoutineFlow() clears the origin.
            Button(
                onClick = {
                    val dest = viewModel.routineExitDestination()
                    viewModel.exitRoutineFlow()
                    navController.popBackStack(dest, false)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(stringResource(Res.string.label_done), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StatItem(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            icon,
            label,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(Spacing.small))
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
