package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.domain.model.CycleProgress
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.presentation.components.ConnectionErrorDialog
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.components.AnimatedActionButton
import com.devil.phoenixproject.presentation.components.IconAnimation
import com.devil.phoenixproject.ui.theme.ThemeMode
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject
import kotlin.time.Clock

/**
 * Home Screen Redesign - "The Command Deck"
 *
 * Material 3 Expressive design principles:
 * - Zero Redundancy: "Start Workout" in ONE place (the FAB)
 * - No Partial Text: Full labels on all buttons
 * - Native Feel: Scaffold handles insets and layout
 * - Context-Aware: FAB adapts to active cycle state
 */
@Composable
fun HomeScreen(
    navController: NavController,
    viewModel: MainViewModel,
    themeMode: ThemeMode,
    isLandscape: Boolean = false
) {
    // State collection
    val connectionError by viewModel.connectionError.collectAsState()
    val routines by viewModel.routines.collectAsState()
    val workoutStreak by viewModel.workoutStreak.collectAsState()
    val recentSessions by viewModel.allWorkoutSessions.collectAsState()

    // Training Cycles state
    val cycleRepository: TrainingCycleRepository = koinInject()
    val activeCycle by cycleRepository.getActiveCycle().collectAsState(initial = null)
    var cycleProgress by remember { mutableStateOf<CycleProgress?>(null) }

    LaunchedEffect(activeCycle) {
        activeCycle?.let { cycle ->
            cycleProgress = cycleRepository.getCycleProgress(cycle.id)
        }
    }


    // Determine actual theme for custom coloring
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    // Clear global title so our custom header shines
    LaunchedEffect(Unit) {
        viewModel.updateTopBarTitle("")
    }

    Scaffold(
        containerColor = Color.Transparent
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // 1. Weekly Compliance Strip (Top of screen)
                item(key = "weekly-compliance") {
                    WeeklyComplianceStrip(
                        history = recentSessions,
                        isDark = isDark,
                        workoutStreak = workoutStreak
                    )
                }

                // 2. The Hero Section (only when cycle active)
                if (activeCycle != null) {
                    item(key = "hero-card") {
                        ActiveCycleHero(
                            cycle = activeCycle!!,
                            progress = cycleProgress,
                            routines = routines,
                            onViewSchedule = { navController.navigate(NavigationRoutes.TrainingCycles.route) }
                        )
                    }
                }

                // 4. Recent Activity Summary
                item(key = "recent-activity") {
                    Text(
                        "Recent Activity",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    RecentActivitySummary(recentSessions)
                }

                // Spacer for FABs clearance
                item(key = "fab-spacer") {
                    Spacer(modifier = Modifier.height(180.dp))
                }
            }

            // Bottom FAB Grid: 2 columns, equal width - positioned directly above the navbar
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Left column: Cycles & Routines
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnimatedActionButton(
                        label = "Cycles",
                        icon = Icons.Default.Loop,
                        onClick = { navController.navigate(NavigationRoutes.TrainingCycles.route) },
                        isPrimary = false,
                        iconAnimation = IconAnimation.ROTATE
                    )
                    AnimatedActionButton(
                        label = "Routines",
                        icon = Icons.Default.FormatListBulleted,
                        onClick = { navController.navigate(NavigationRoutes.DailyRoutines.route) },
                        isPrimary = false,
                        iconAnimation = IconAnimation.NONE
                    )
                }

                // Right column: Single Exercise & Just Lift
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AnimatedActionButton(
                        label = "Single Exercise",
                        icon = Icons.Outlined.FitnessCenter,
                        onClick = { navController.navigate(NavigationRoutes.SingleExercise.route) },
                        isPrimary = false,
                        iconAnimation = IconAnimation.TILT
                    )
                    AnimatedActionButton(
                        label = "Just Lift",
                        icon = null,
                        onClick = {
                            navController.navigate(NavigationRoutes.JustLift.route)
                        },
                        isPrimary = true,
                        isFireButton = true,  // Fire animation effect
                        iconAnimation = IconAnimation.NONE
                    )
                }
            }

            // Connection Error Handling
            connectionError?.let { error ->
                ConnectionErrorDialog(
                    message = error,
                    onDismiss = { viewModel.clearConnectionError() }
                )
            }
        }
    }
}

// ============================================================================
// WEEKLY COMPLIANCE STRIP (Top of screen)
// ============================================================================

@Composable
private fun WeeklyComplianceStrip(
    history: List<WorkoutSession>,
    isDark: Boolean,
    workoutStreak: Int?
) {
    // Get current week's Monday
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val mondayOffset = today.dayOfWeek.ordinal
    val mondayEpochDays = today.toEpochDays() - mondayOffset
    val weekDays = (0..6).map { LocalDate.fromEpochDays(mondayEpochDays + it) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Week days row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            weekDays.forEach { date ->
                val hasWorkout = history.any { session ->
                    val sessionDate = Instant.fromEpochMilliseconds(session.timestamp)
                        .toLocalDateTime(TimeZone.currentSystemDefault()).date
                    sessionDate == date
                }
                val isToday = date == today

                ComplianceDot(
                    letter = date.dayOfWeek.name.take(1),
                    isActive = hasWorkout,
                    isToday = isToday
                )
            }
        }

        // Streak badge (centered beneath days of the week)
        if (workoutStreak != null && workoutStreak > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Icon(
                    Icons.Default.LocalFireDepartment,
                    contentDescription = "Streak",
                    tint = Color(0xFFFF6B00),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "$workoutStreak",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun ComplianceDot(letter: String, isActive: Boolean, isToday: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = letter,
            style = MaterialTheme.typography.labelSmall,
            color = if (isToday) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isActive -> MaterialTheme.colorScheme.primary // Workout done
                        isToday -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) // Today empty
                        else -> MaterialTheme.colorScheme.surfaceVariant // Past/Future empty
                    }
                )
                .then(
                    if (isToday && !isActive)
                        Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    else Modifier
                )
        )
    }
}

// ============================================================================
// HERO CARDS (Refined)
// ============================================================================

@Composable
private fun ActiveCycleHero(
    cycle: TrainingCycle,
    progress: CycleProgress?,
    routines: List<Routine>,
    onViewSchedule: () -> Unit
) {
    val currentDayNum = progress?.currentDayNumber ?: 1
    val cycleDay = cycle.days.find { it.dayNumber == currentDayNum }
    val routine = cycleDay?.routineId?.let { id -> routines.find { it.id == id } }
    val isRest = cycleDay?.isRestDay == true || cycleDay?.routineId == null

    Card(
        onClick = onViewSchedule,
        modifier = Modifier.fillMaxWidth().height(180.dp),
        shape = RoundedCornerShape(28.dp), // Expressive shape
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background Image/Icon Placeholder (Right aligned, subtle)
            Icon(
                imageVector = if (isRest) Icons.Default.SelfImprovement else Icons.Default.FitnessCenter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.05f),
                modifier = Modifier
                    .size(200.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 40.dp, y = 40.dp)
            )

            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .align(Alignment.TopStart)
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = if (isRest) "REST DAY" else "UP NEXT",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    text = cycleDay?.name ?: "Day $currentDayNum",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                if (routine != null) {
                    Text(
                        text = routine.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                } else if (isRest) {
                    Text(
                        text = "Take it easy today.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }

            // Progress Bar at bottom (replaces dot indicators)
            if (cycle.days.isNotEmpty()) {
                val progressValue = currentDayNum.toFloat() / cycle.days.size.toFloat()
                LinearProgressIndicator(
                    progress = { progressValue },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .align(Alignment.BottomCenter),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
                    drawStopIndicator = {}
                )
            }
        }
    }
}

// ============================================================================
// RECENT ACTIVITY SUMMARY
// ============================================================================

@Composable
private fun RecentActivitySummary(history: List<WorkoutSession>) {
    if (history.isEmpty()) {
        Text(
            "No recent workouts recorded.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        // Simple list of last 3 workouts
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            history.take(3).forEach { session ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                session.exerciseName ?: "Workout Session",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${session.workingReps} reps • ${session.weightPerCableKg.toInt()} kg/cable",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}
