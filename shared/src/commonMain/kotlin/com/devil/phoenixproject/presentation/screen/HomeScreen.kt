package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.devil.phoenixproject.ui.theme.ThemeMode
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject
import kotlin.time.Clock

/**
 * Home screen - Pro Dashboard design.
 * Answers: "What do I need to do today?" + "How consistent have I been?"
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

    // Determine actual theme
    val useDarkColors = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val backgroundGradient = if (useDarkColors) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFF0F172A),
                Color(0xFF1E1B4B),
                Color(0xFF172554)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xFFE0E7FF),
                Color(0xFFFCE7F3),
                Color(0xFFDDD6FE)
            )
        )
    }

    // Clear title to allow global branding to show
    LaunchedEffect(Unit) {
        viewModel.updateTopBarTitle("")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundGradient)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Scrollable content area
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // 1. Header with streak badge
                DashboardHeader(workoutStreak = workoutStreak)

                // 2. Weekly Compliance Strip
                WeeklyComplianceStrip(history = recentSessions)

                // 3. Hero Card - Up Next
                if (activeCycle != null) {
                    ActiveCycleHero(
                        cycle = activeCycle!!,
                        progress = cycleProgress,
                        routines = routines,
                        onStartRoutine = { routineId ->
                            viewModel.ensureConnection(
                                onConnected = {
                                    viewModel.loadRoutineById(routineId)
                                    viewModel.startWorkout()
                                    navController.navigate(NavigationRoutes.ActiveWorkout.route)
                                },
                                onFailed = { /* Error shown via StateFlow */ }
                            )
                        },
                        onViewCycles = {
                            navController.navigate(NavigationRoutes.TrainingCycles.route)
                        }
                    )
                } else {
                    NoProgramHero(
                        onCreateProgram = { navController.navigate(NavigationRoutes.TrainingCycles.route) },
                        onJustLift = { navController.navigate(NavigationRoutes.JustLift.route) }
                    )
                }

                // 4. Secondary Action Tiles
                SecondaryActionTiles(
                    onLibraryClick = { navController.navigate(NavigationRoutes.DailyRoutines.route) },
                    onProgramsClick = { navController.navigate(NavigationRoutes.TrainingCycles.route) }
                )

                // Bottom padding for scroll content
                Spacer(Modifier.height(20.dp))
            }

            // 5. Fixed Bottom Action Bar (NOT in scroll)
            FixedBottomActionBar(
                onJustLiftClick = { navController.navigate(NavigationRoutes.JustLift.route) },
                onSingleExerciseClick = { navController.navigate(NavigationRoutes.SingleExercise.route) }
            )
        }

        // Connection error dialog
        connectionError?.let { error ->
            ConnectionErrorDialog(
                message = error,
                onDismiss = { viewModel.clearConnectionError() }
            )
        }
    }
}

// ============================================================================
// DASHBOARD HEADER
// ============================================================================

@Composable
private fun DashboardHeader(workoutStreak: Int?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                "Welcome Back",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Let's Crush It",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        // Streak badge (only if streak > 0)
        if (workoutStreak != null && workoutStreak > 0) {
            StreakBadge(days = workoutStreak)
        }
    }
}

@Composable
private fun StreakBadge(days: Int) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.LocalFireDepartment,
                contentDescription = null,
                tint = Color(0xFFFF6B00),
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                "$days Day Streak",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

// ============================================================================
// WEEKLY COMPLIANCE STRIP
// ============================================================================

@Composable
private fun WeeklyComplianceStrip(history: List<WorkoutSession>) {
    // Get current week's Monday
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val mondayOffset = today.dayOfWeek.ordinal // Monday = 0, Sunday = 6
    val mondayEpochDays = today.toEpochDays() - mondayOffset
    val weekDays = (0..6).map { LocalDate.fromEpochDays(mondayEpochDays + it) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            weekDays.forEach { date ->
                val hasWorkout = history.any { session ->
                    val sessionDate = Instant.fromEpochMilliseconds(session.timestamp)
                        .toLocalDateTime(TimeZone.currentSystemDefault()).date
                    sessionDate == date
                }
                val isToday = date == today
                val isFuture = date.toEpochDays() > today.toEpochDays()

                DayDot(
                    dayLetter = date.dayOfWeek.name.first().toString(),
                    isToday = isToday,
                    hasWorkout = hasWorkout,
                    isFuture = isFuture
                )
            }
        }
    }
}

@Composable
private fun DayDot(
    dayLetter: String,
    isToday: Boolean,
    hasWorkout: Boolean,
    isFuture: Boolean
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = dayLetter,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = if (isToday) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )

        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(
                    when {
                        hasWorkout -> Color(0xFF10B981) // Green
                        isToday -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                    }
                )
                .then(
                    if (isToday) Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                        CircleShape
                    )
                    else Modifier
                )
        )
    }
}

// ============================================================================
// HERO CARDS
// ============================================================================

@Composable
private fun ActiveCycleHero(
    cycle: TrainingCycle,
    progress: CycleProgress?,
    routines: List<Routine>,
    onStartRoutine: (String) -> Unit,
    onViewCycles: () -> Unit
) {
    val currentDay = progress?.currentDayNumber ?: 1
    val currentCycleDay = cycle.days.find { it.dayNumber == currentDay }
    val routine = currentCycleDay?.routineId?.let { routineId ->
        routines.find { it.id == routineId }
    }
    val isRestDay = currentCycleDay?.isRestDay == true || currentCycleDay?.routineId == null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier.background(
                if (!isRestDay) {
                    Brush.linearGradient(listOf(Color(0xFF4F46E5), Color(0xFF7C3AED)))
                } else {
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceContainerHighest,
                            MaterialTheme.colorScheme.surfaceContainer
                        )
                    )
                }
            )
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                // Label row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = if (!isRestDay) Color.White.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (isRestDay) "REST DAY" else "UP NEXT",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (!isRestDay) Color.White else MaterialTheme.colorScheme.primary
                        )
                    }

                    TextButton(
                        onClick = onViewCycles,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (!isRestDay) Color.White.copy(alpha = 0.9f)
                            else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("View Schedule")
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (isRestDay) {
                    // Rest day content
                    Text(
                        "Recovery & Rest",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Day $currentDay of ${cycle.days.size} - Enjoy your rest day.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // Workout day content
                    Text(
                        currentCycleDay?.name ?: "Day $currentDay",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Spacer(Modifier.height(8.dp))

                    routine?.let { r ->
                        Text(
                            r.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                        Text(
                            "${r.exercises.size} Exercises",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    Button(
                        onClick = { currentCycleDay?.routineId?.let { onStartRoutine(it) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF4F46E5)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Start Workout", fontWeight = FontWeight.Bold)
                    }
                }

                // Progress indicator
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    cycle.days.take(10).forEach { day ->
                        val isCurrent = day.dayNumber == currentDay
                        val isPast = day.dayNumber < currentDay
                        Box(
                            modifier = Modifier
                                .size(if (isCurrent) 10.dp else 6.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        isCurrent -> if (!isRestDay) Color.White else MaterialTheme.colorScheme.primary
                                        isPast -> if (!isRestDay) Color.White.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                                        else -> if (!isRestDay) Color.White.copy(alpha = 0.3f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                                    }
                                )
                        )
                        if (day.dayNumber < cycle.days.size.coerceAtMost(10)) {
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    if (cycle.days.size > 10) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "+${cycle.days.size - 10}",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (!isRestDay) Color.White.copy(alpha = 0.7f)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoProgramHero(
    onCreateProgram: () -> Unit,
    onJustLift: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.FitnessCenter,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "No Active Training Cycle",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Create a rolling schedule or jump straight into lifting.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(
                    onClick = onJustLift,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Just Lift")
                }
                Button(
                    onClick = onCreateProgram,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Create Cycle")
                }
            }
        }
    }
}

// ============================================================================
// SECONDARY ACTION TILES
// ============================================================================

@Composable
private fun SecondaryActionTiles(
    onLibraryClick: () -> Unit,
    onProgramsClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        QuickActionCard(
            title = "Routines",
            icon = Icons.Default.CollectionsBookmark,
            color = Color(0xFF3B82F6),
            modifier = Modifier.weight(1f),
            onClick = onLibraryClick
        )
        QuickActionCard(
            title = "Cycles",
            icon = Icons.Default.Loop,
            color = Color(0xFF10B981),
            modifier = Modifier.weight(1f),
            onClick = onProgramsClick
        )
    }
}

@Composable
private fun QuickActionCard(
    title: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(100.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(color.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(20.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ============================================================================
// FIXED BOTTOM ACTION BAR
// ============================================================================

@Composable
private fun FixedBottomActionBar(
    onJustLiftClick: () -> Unit,
    onSingleExerciseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Just Lift Button
            Button(
                onClick = onJustLiftClick,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF9333EA) // Purple
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.FitnessCenter, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Just Lift", fontWeight = FontWeight.Bold)
            }

            // Single Exercise Button
            Button(
                onClick = onSingleExerciseClick,
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFEC4899) // Pink
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Single Ex", fontWeight = FontWeight.Bold)
            }
        }
    }
}
