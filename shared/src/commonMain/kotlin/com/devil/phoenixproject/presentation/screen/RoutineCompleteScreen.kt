package com.devil.phoenixproject.presentation.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.ui.theme.ExpressiveMotion
import com.devil.phoenixproject.ui.theme.Spacing
import com.devil.phoenixproject.ui.theme.celebrationBackgroundBrush
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.presentation.components.BackHandler
import com.devil.phoenixproject.presentation.navigation.safePopOrNavigate
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import projectphoenix.shared.generated.resources.*
import projectphoenix.shared.generated.resources.Res

/**
 * Routine Complete Screen - Celebration after finishing entire routine.
 *
 * Issue #1164: the exit must never depend on content measurement or the reveal
 * animation. The screen keeps a reserved always-visible Done footer (outside all
 * [AnimatedVisibility] reveal blocks) as a sibling of one weighted, vertically
 * scrollable celebration/stats body. The celebration content may wrap and scroll;
 * it can never shrink, overlap, or clip the exit. Normal app chrome is suppressed
 * on this route and the iOS BackHandler is a no-op, so the in-screen footer is the
 * only guaranteed exit.
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
        navController.safePopOrNavigate(dest)
    }

    // workout-execution-16: staged reveal — icon → title → stats with ~100ms stagger.
    // Uses charter SpringBouncy for the scale-in on each stage.
    // reduceMotion: all stages visible immediately (channels start settled at 1f).
    // Issue #1164: the reveal covers celebration content only. The Done footer is
    // reserved outside these blocks and is present immediately on entry.
    val reduceMotion = LocalPlatformAccessibilitySettings.current.reduceMotion
    var iconVisible by remember { mutableStateOf(false) }
    var titleVisible by remember { mutableStateOf(false) }
    var statsVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (reduceMotion) {
            iconVisible = true; titleVisible = true; statsVisible = true
        } else {
            iconVisible = true
            delay(100L)
            titleVisible = true
            delay(100L)
            statsVisible = true
        }
    }

    // Format duration
    val durationMinutes = (completeState.totalDurationMs / 60000).toInt()
    val durationSeconds = ((completeState.totalDurationMs % 60000) / 1000).toInt()
    val durationFormatted = if (durationMinutes > 0) {
        "${durationMinutes}m ${durationSeconds}s"
    } else {
        "${durationSeconds}s"
    }

    // Issue #1164: safe-area padding lives on the root that contains BOTH the footer
    // and the scroll body — the scaffold applies zero window insets on this route.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(celebrationBackgroundBrush())
            .systemBarsPadding(),
    ) {
        // Max-height column with exactly one weighted vertical-scroll sibling for
        // the celebration body plus a sibling footer. No requiredHeight, no fixed
        // spacers to "make room", no text shrinking, no animation-delay tweaks.
        Column(modifier = Modifier.fillMaxSize()) {
            // Celebration/stats body: the sole scroll region. Content may wrap and
            // scroll on short/narrow roots; it can never take height from the footer.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(top = Spacing.extraLarge)
                    .padding(horizontal = Spacing.extraLarge),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.extraLarge),
            ) {
                // Stage 1: Celebration icon — one-shot SpringBouncy scale-in (replaces infinite tween)
                // reduceMotion: EnterTransition.None so icon is immediately visible (static final frame).
                AnimatedVisibility(
                    visible = iconVisible,
                    enter = if (reduceMotion) EnterTransition.None
                            else scaleIn(animationSpec = ExpressiveMotion.SpringBouncy) + fadeIn(),
                ) {
                    Box(
                        modifier = Modifier
                            .size(150.dp)
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
                }

                // Stage 2: Congratulations title
                AnimatedVisibility(
                    visible = titleVisible,
                    enter = if (reduceMotion) EnterTransition.None
                            else scaleIn(animationSpec = ExpressiveMotion.SpringBouncy) + fadeIn(),
                ) {
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
                }

                // Stage 3: Stats card only (issue #1164: Done moved to the reserved footer)
                AnimatedVisibility(
                    visible = statsVisible,
                    enter = if (reduceMotion) EnterTransition.None
                            else scaleIn(animationSpec = ExpressiveMotion.SpringBouncy) + fadeIn(),
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        // Issue #1164: balanced stat cells at ordinary sizes; stack/reflow
                        // at narrow widths or enlarged accessibility text so the full
                        // values (including duration) stay readable — no ellipsis hiding.
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.large),
                        ) {
                            val fontScale = LocalDensity.current.fontScale
                            val stackStats = maxWidth < 320.dp || fontScale >= 1.3f

                            if (stackStats) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(Spacing.medium),
                                ) {
                                    StatItem(
                                        icon = Icons.Default.FitnessCenter,
                                        value = "${completeState.totalExercises}",
                                        label = "Exercises",
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    StatItem(
                                        icon = Icons.Default.Repeat,
                                        value = "${completeState.totalSets}",
                                        label = "Sets",
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    StatItem(
                                        icon = Icons.Default.Timer,
                                        value = durationFormatted,
                                        label = "Duration",
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            } else {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.medium),
                                ) {
                                    StatItem(
                                        icon = Icons.Default.FitnessCenter,
                                        value = "${completeState.totalExercises}",
                                        label = "Exercises",
                                        modifier = Modifier.weight(1f),
                                    )
                                    StatItem(
                                        icon = Icons.Default.Repeat,
                                        value = "${completeState.totalSets}",
                                        label = "Sets",
                                        modifier = Modifier.weight(1f),
                                    )
                                    StatItem(
                                        icon = Icons.Default.Timer,
                                        value = durationFormatted,
                                        label = "Duration",
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Reserved Done footer — sibling of the scroll body, outside every
            // AnimatedVisibility reveal block (issue #1164). Present and tappable
            // immediately on entry, including during the reveal and with Reduce
            // Motion; its height is never shared with celebration content.
            // lens-navigation-ux-2: read destination BEFORE exitRoutineFlow() clears the origin.
            Button(
                onClick = {
                    val dest = viewModel.routineExitDestination()
                    viewModel.exitRoutineFlow()
                    navController.safePopOrNavigate(dest)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.extraLarge)
                    .padding(top = Spacing.medium, bottom = Spacing.extraLarge)
                    .height(56.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(stringResource(Res.string.label_done), fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * One completion stat cell. Merged semantics keep the icon, full value, and label
 * announced as one associated unit (issue #1164: values must never be hidden —
 * long values wrap inside the balanced width instead of ellipsizing).
 */
@Composable
private fun StatItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
