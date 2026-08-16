package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.devil.phoenixproject.data.repository.ActiveProfileContext
import com.devil.phoenixproject.data.repository.TrainingCycleRepository
import com.devil.phoenixproject.domain.model.CycleProgress
import com.devil.phoenixproject.domain.model.Routine
import com.devil.phoenixproject.domain.model.RoutineLaunchOrigin
import com.devil.phoenixproject.domain.model.TrainingCycle
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.presentation.components.AnimatedActionButton
import com.devil.phoenixproject.presentation.components.ConnectionErrorDialog
import com.devil.phoenixproject.presentation.components.IconAnimation
import com.devil.phoenixproject.presentation.components.ResumeRoutineDialog
import com.devil.phoenixproject.presentation.manager.RoutineResumeDiscovery
import com.devil.phoenixproject.presentation.manager.RoutineResumeHandle
import com.devil.phoenixproject.presentation.navigation.NavigationRoutes
import com.devil.phoenixproject.presentation.util.LocalPlatformAccessibilitySettings
import com.devil.phoenixproject.presentation.util.LocalWindowSizeClass
import com.devil.phoenixproject.presentation.util.WeightDisplayFormatter
import com.devil.phoenixproject.presentation.util.WindowHeightSizeClass
import com.devil.phoenixproject.presentation.viewmodel.MainViewModel
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeActionAuthority
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeCompletionDisposition
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeEntryPoint
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeOperationGate
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeRetryAction
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeUiOperation
import com.devil.phoenixproject.presentation.viewmodel.RoutineResumeUiOutcome
import com.devil.phoenixproject.presentation.viewmodel.classifyRoutineResumeCompletion
import com.devil.phoenixproject.presentation.viewmodel.runFreshCycleUiOperation
import com.devil.phoenixproject.presentation.viewmodel.runRoutineResumeUiOperation
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import vitruvianprojectphoenix.shared.generated.resources.Res
import vitruvianprojectphoenix.shared.generated.resources.cd_start_workout
import vitruvianprojectphoenix.shared.generated.resources.cd_streak
import vitruvianprojectphoenix.shared.generated.resources.start_workout

private const val HOME_CONTENT_MAX_WIDTH = 720
internal const val ONE_REP_MAX_COMING_SOON_TITLE = "Coming Soon!"

@Composable
fun HomeScreen(navController: NavController, viewModel: MainViewModel) {
    val connectionError by viewModel.connectionError.collectAsState()
    val routines by viewModel.routines.collectAsState()
    val workoutStreak by viewModel.workoutStreak.collectAsState()
    val recentSessions by viewModel.allWorkoutSessions.collectAsState()
    val weightUnit by viewModel.weightUnit.collectAsState()

    val cycleRepository: TrainingCycleRepository = koinInject()
    val userProfileRepository: com.devil.phoenixproject.data.repository.UserProfileRepository = koinInject()
    val activeProfile by userProfileRepository.activeProfile.collectAsState()
    val activeProfileContext by userProfileRepository.activeProfileContext.collectAsState()
    val profileId = activeProfile?.id ?: "default"
    val activeCycle by cycleRepository.getActiveCycle(profileId).collectAsState(initial = null)
    var cycleProgress by remember { mutableStateOf<CycleProgress?>(null) }
    var pendingResumeHandle by remember { mutableStateOf<RoutineResumeHandle?>(null) }
    var resumeOperationInFlight by remember { mutableStateOf(false) }
    var discardRetryPending by remember { mutableStateOf(false) }
    var manualLoadRetry by remember { mutableStateOf<RoutineResumeUiOperation.RetryManualLoad?>(null) }
    val resumeOperationGate = remember { RoutineResumeOperationGate() }
    var showOneRepMaxComingSoonDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(activeCycle) {
        // Issue #549: route the Home banner through `checkAndAutoAdvance` so the cycle banner
        // refreshes to the current day on initial Home load. Previously this called
        // `getCycleProgress` directly, which is a pure read and never reconciles
        // `lastAdvancedAt` against the current calendar day. `TrainingCyclesScreen` is the only
        // other production caller of `checkAndAutoAdvance` — that is exactly why the reporter's
        // tap-Cycles-then-back workaround refreshed the banner.
        cycleProgress = activeCycle?.let { cycle -> runCatching { loadHomeCycleProgress(cycleRepository, cycle) }.getOrNull() }
    }

    LaunchedEffect(Unit) {
        viewModel.updateTopBarTitle("")
    }

    val currentCycleDayNumber = cycleProgress?.currentDayNumber ?: 1
    val currentCycleDay = remember(activeCycle, currentCycleDayNumber) {
        activeCycle?.days?.find { it.dayNumber == currentCycleDayNumber }
    }
    val currentCycleRoutine = remember(currentCycleDay?.routineId, routines) {
        currentCycleDay?.routineId?.let { routineId ->
            routines.find { it.id == routineId }
        }
    }
    val fontScale = LocalDensity.current.fontScale
    val platformAccessibilitySettings = LocalPlatformAccessibilitySettings.current
    val windowSizeClass = LocalWindowSizeClass.current
    val stackedShortcuts = fontScale >= 1.15f || platformAccessibilitySettings.boldTextEnabled
    val compactVerticalSpace = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact && !stackedShortcuts
    val cycle = activeCycle
    val runnableCycleStart = if (
        cycle != null &&
        currentCycleDay?.isRestDay != true &&
        currentCycleDay?.routineId != null &&
        currentCycleRoutine != null
    ) {
        CycleStartRequest(
            routineId = currentCycleRoutine.id,
            cycleId = cycle.id,
            dayNumber = currentCycleDayNumber,
        )
    } else {
        null
    }

    fun clearResumeDialog() {
        pendingResumeHandle = null
        resumeOperationInFlight = false
        discardRetryPending = false
        manualLoadRetry = null
    }

    LaunchedEffect(activeProfileContext) {
        val ready = activeProfileContext as? ActiveProfileContext.Ready ?: return@LaunchedEffect
        val handle = pendingResumeHandle ?: return@LaunchedEffect
        if (ready.profile.id != handle.selectedProfileId) {
            resumeOperationGate.supersede()
            clearResumeDialog()
        }
    }

    fun dispatchResumeOutcome(outcome: RoutineResumeUiOutcome) {
        when (outcome) {
            RoutineResumeUiOutcome.NavigateActiveWorkout -> {
                clearResumeDialog()
                navController.navigate(NavigationRoutes.ActiveWorkout.route)
            }

            is RoutineResumeUiOutcome.EnterSetReady -> {
                viewModel.enterSetReady(outcome.exerciseIndex, outcome.setIndex)
                clearResumeDialog()
                navController.navigate(NavigationRoutes.SetReady.route)
            }

            is RoutineResumeUiOutcome.RetainDialog -> {
                resumeOperationInFlight = false
                discardRetryPending = outcome.retryAction == RoutineResumeRetryAction.DISCARD
            }

            RoutineResumeUiOutcome.ConnectionFailed -> resumeOperationInFlight = false

            is RoutineResumeUiOutcome.LoadFailed -> {
                manualLoadRetry = outcome.retryOperation
                resumeOperationInFlight = false
            }

            RoutineResumeUiOutcome.DismissDialog,
            RoutineResumeUiOutcome.StartAndNavigateActiveWorkout,
            is RoutineResumeUiOutcome.EnterDailyOverview,
            -> clearResumeDialog()

            RoutineResumeUiOutcome.StaleNoOp -> Unit
        }
    }

    fun launchResumeOperation(operation: RoutineResumeUiOperation) {
        resumeOperationInFlight = true
        resumeOperationGate.launch(scope) { actionToken ->
            val authority = RoutineResumeActionAuthority(
                entryPoint = RoutineResumeEntryPoint.HOME_CYCLE,
                actionToken = actionToken,
                currentToken = { resumeOperationGate.currentToken },
                contextIsCurrent = {
                    viewModel.isRoutineResumeProfileCurrent(operation.handle.selectedProfileId)
                },
            )
            val outcome = runRoutineResumeUiOperation(
                operation = operation,
                authority = authority,
                port = viewModel.routineResumeUiPort(),
            )
            when (
                val disposition = classifyRoutineResumeCompletion(
                    tokenCurrent = authority.tokenIsCurrent(),
                    contextCurrent = authority.contextIsCurrent(),
                    outcome = outcome,
                )
            ) {
                RoutineResumeCompletionDisposition.IgnoreStaleToken -> return@launch
                RoutineResumeCompletionDisposition.UnlockRetainedDialog -> resumeOperationInFlight = false
                is RoutineResumeCompletionDisposition.Apply -> dispatchResumeOutcome(disposition.outcome)
            }
        }
    }

    fun startCycleWorkout(request: CycleStartRequest) {
        val routine = routines.find { it.id == request.routineId } ?: return
        pendingResumeHandle = null
        resumeOperationInFlight = true
        discardRetryPending = false
        manualLoadRetry = null
        resumeOperationGate.launch(scope) { selectionToken ->
            val authority = RoutineResumeActionAuthority(
                entryPoint = RoutineResumeEntryPoint.HOME_CYCLE,
                actionToken = selectionToken,
                currentToken = { resumeOperationGate.currentToken },
                contextIsCurrent = { viewModel.isRoutineResumeProfileCurrent(routine.profileId) },
            )
            when (
                val discovery = viewModel.discoverRoutineResume(
                    routine = routine,
                    launchOrigin = RoutineLaunchOrigin.TRAINING_CYCLES,
                    cycleId = request.cycleId,
                    cycleDayNumber = request.dayNumber,
                )
            ) {
                is RoutineResumeDiscovery.Candidate -> if (authority.isCurrent()) {
                    pendingResumeHandle = discovery.handle
                    resumeOperationInFlight = false
                }

                RoutineResumeDiscovery.Missing -> if (authority.isCurrent()) {
                    dispatchResumeOutcome(
                        runFreshCycleUiOperation(
                            routine = routine,
                            cycleId = request.cycleId,
                            dayNumber = request.dayNumber,
                            authority = authority,
                            port = viewModel.routineResumeUiPort(),
                        ),
                    )
                }

                RoutineResumeDiscovery.RetryableFailure -> if (authority.isCurrent()) {
                    resumeOperationInFlight = false
                }

                RoutineResumeDiscovery.Superseded -> if (authority.isCurrent()) {
                    clearResumeDialog()
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(if (compactVerticalSpace) 10.dp else 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "launch-pad") {
                HomeLaunchPad(
                    activeCycle = activeCycle,
                    currentDayNumber = currentCycleDayNumber,
                    currentDayName = currentCycleDay?.name,
                    currentRoutine = currentCycleRoutine,
                    runnableCycleStart = runnableCycleStart,
                    stackedShortcuts = stackedShortcuts,
                    compactVerticalSpace = compactVerticalSpace,
                    onStartCycleWorkout = ::startCycleWorkout,
                    onJustLift = { navController.navigate(NavigationRoutes.JustLift.route) },
                    onSingleExercise = { navController.navigate(NavigationRoutes.SingleExercise.route) },
                    onRoutines = { navController.navigate(NavigationRoutes.DailyRoutines.route) },
                    onCycles = { navController.navigate(NavigationRoutes.TrainingCycles.route) },
                    onAssessOneRepMaxComingSoon = { showOneRepMaxComingSoonDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = HOME_CONTENT_MAX_WIDTH.dp),
                )
            }

            item(key = "weekly-compliance") {
                WeeklyComplianceStrip(
                    history = recentSessions,
                    workoutStreak = workoutStreak,
                    compact = compactVerticalSpace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = HOME_CONTENT_MAX_WIDTH.dp),
                )
            }

            item(key = "recent-activity") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = HOME_CONTENT_MAX_WIDTH.dp),
                ) {
                    Text(
                        "Recent Activity",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    RecentActivitySummary(
                        history = recentSessions,
                        weightUnit = weightUnit,
                        onExerciseClick = { exerciseId ->
                            navController.navigate(NavigationRoutes.SingleExerciseForExercise.createRoute(exerciseId))
                        },
                    )
                }
            }
        }

        connectionError?.let { error ->
            ConnectionErrorDialog(
                message = error,
                onDismiss = { viewModel.clearConnectionError() },
            )
        }

        if (showOneRepMaxComingSoonDialog) {
            AlertDialog(
                onDismissRequest = { showOneRepMaxComingSoonDialog = false },
                title = { Text(ONE_REP_MAX_COMING_SOON_TITLE) },
                confirmButton = {
                    TextButton(onClick = { showOneRepMaxComingSoonDialog = false }) {
                        Text("OK")
                    }
                },
            )
        }

        pendingResumeHandle?.let { handle ->
            ResumeRoutineDialog(
                progressInfo = handle.progressInfo,
                onResume = {
                    if (resumeOperationInFlight || discardRetryPending) return@ResumeRoutineDialog
                    launchResumeOperation(manualLoadRetry ?: RoutineResumeUiOperation.Resume(handle))
                },
                onRestart = {
                    if (resumeOperationInFlight) return@ResumeRoutineDialog
                    manualLoadRetry = null
                    launchResumeOperation(RoutineResumeUiOperation.Restart(handle))
                },
                onDismiss = {
                    if (!resumeOperationInFlight) {
                        resumeOperationGate.supersede()
                        clearResumeDialog()
                    }
                },
                confirmEnabled = !resumeOperationInFlight && !discardRetryPending,
            )
        }
    }
}

@Composable
private fun HomeLaunchPad(
    activeCycle: TrainingCycle?,
    currentDayNumber: Int,
    currentDayName: String?,
    currentRoutine: Routine?,
    runnableCycleStart: CycleStartRequest?,
    stackedShortcuts: Boolean,
    compactVerticalSpace: Boolean,
    onStartCycleWorkout: (CycleStartRequest) -> Unit,
    onJustLift: () -> Unit,
    onSingleExercise: () -> Unit,
    onRoutines: () -> Unit,
    onCycles: () -> Unit,
    onAssessOneRepMaxComingSoon: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cycleStatus = activeCycle?.let { cycle ->
        when {
            runnableCycleStart != null && currentRoutine != null -> currentRoutine.name
            currentDayName != null -> "$currentDayName in ${cycle.name}"
            else -> "Day $currentDayNumber in ${cycle.name}"
        }
    }
    val primaryHeight = if (compactVerticalSpace) 56.dp else 72.dp
    val shortcutHeight = if (compactVerticalSpace) 48.dp else 60.dp
    val launchSpacing = if (compactVerticalSpace) 6.dp else 10.dp
    val startWorkoutLabel = stringResource(Res.string.start_workout)
    val startWorkoutContentDescription = stringResource(Res.string.cd_start_workout)

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(launchSpacing),
    ) {
        val cycleStart = runnableCycleStart
        if (cycleStart != null && currentRoutine != null) {
            AnimatedActionButton(
                label = startWorkoutLabel,
                supportingText = cycleStatus,
                icon = Icons.Default.PlayArrow,
                onClick = {
                    onStartCycleWorkout(cycleStart)
                },
                isPrimary = true,
                iconAnimation = IconAnimation.PULSE,
                contentDescription = startWorkoutContentDescription,
                heightOverride = primaryHeight,
                allowTwoLineLabel = true,
            )
        }

        ShortcutActions(
            actions = buildHomeShortcutActions(
                onSingleExercise = onSingleExercise,
                onRoutines = onRoutines,
                onCycles = onCycles,
                onAssessOneRepMaxComingSoon = onAssessOneRepMaxComingSoon,
            ),
            stacked = stackedShortcuts,
            buttonHeight = shortcutHeight,
        )

        AnimatedActionButton(
            label = "Just Lift",
            icon = Icons.Default.LocalFireDepartment,
            onClick = onJustLift,
            isPrimary = true,
            isFireButton = true,
            iconAnimation = IconAnimation.FIRE,
            contentDescription = "Open Just Lift",
            heightOverride = primaryHeight,
            allowTwoLineLabel = true,
        )

        if (activeCycle != null) {
            CycleContextRow(
                cycle = activeCycle,
                dayNumber = currentDayNumber,
                dayName = currentDayName,
                routineName = currentRoutine?.name,
                onClick = onCycles,
            )
        }
    }
}

@Composable
private fun ShortcutActions(actions: List<HomeActionSpec>, stacked: Boolean, buttonHeight: androidx.compose.ui.unit.Dp) {
    if (stacked) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.forEach { action ->
                AnimatedActionButton(
                    label = action.label,
                    icon = action.icon,
                    onClick = action.onClick,
                    isPrimary = false,
                    iconAnimation = action.iconAnimation,
                    contentDescription = action.contentDescription,
                    heightOverride = buttonHeight,
                    allowTwoLineLabel = true,
                    enabled = action.enabled,
                )
            }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.chunked(2).forEach { rowActions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rowActions.forEach { action ->
                        AnimatedActionButton(
                            label = action.label,
                            icon = action.icon,
                            onClick = action.onClick,
                            isPrimary = false,
                            iconAnimation = action.iconAnimation,
                            contentDescription = action.contentDescription,
                            heightOverride = buttonHeight,
                            enabled = action.enabled,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (rowActions.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun CycleContextRow(cycle: TrainingCycle, dayNumber: Int, dayName: String?, routineName: String?, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = cycle.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull("Day $dayNumber", dayName, routineName ?: "No routine assigned").joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun WeeklyComplianceStrip(
    history: List<WorkoutSession>,
    workoutStreak: Int?,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    val mondayOffset = today.dayOfWeek.ordinal
    val mondayEpochDays = today.toEpochDays() - mondayOffset
    val weekDays = (0..6).map { LocalDate.fromEpochDays(mondayEpochDays + it) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            weekDays.forEach { date ->
                val hasWorkout = history.any { session ->
                    val sessionDate = Instant.fromEpochMilliseconds(session.timestamp)
                        .toLocalDateTime(TimeZone.currentSystemDefault()).date
                    sessionDate == date
                }

                ComplianceDot(
                    letter = date.dayOfWeek.name.take(1),
                    isActive = hasWorkout,
                    isToday = date == today,
                )
            }
        }

        if (!compact && workoutStreak != null && workoutStreak > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp),
            ) {
                Icon(
                    Icons.Default.LocalFireDepartment,
                    contentDescription = stringResource(Res.string.cd_streak),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "$workoutStreak",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
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
            color = if (isToday) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            },
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
        )
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isActive -> MaterialTheme.colorScheme.primary
                        isToday -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .then(
                    if (isToday && !isActive) {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    } else {
                        Modifier
                    },
                ),
        )
    }
}

@Composable
private fun RecentActivitySummary(
    history: List<WorkoutSession>,
    weightUnit: WeightUnit = WeightUnit.KG,
    onExerciseClick: (String) -> Unit,
) {
    if (history.isEmpty()) {
        Text(
            "No recent workouts yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            history.take(2).forEach { session ->
                val replayExerciseId = session.replayExerciseId()
                if (replayExerciseId != null) {
                    Surface(
                        onClick = { onExerciseClick(replayExerciseId) },
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RecentActivityRowContent(
                            session = session,
                            weightUnit = weightUnit,
                            showChevron = true,
                        )
                    }
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RecentActivityRowContent(
                            session = session,
                            weightUnit = weightUnit,
                            showChevron = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentActivityRowContent(session: WorkoutSession, weightUnit: WeightUnit, showChevron: Boolean) {
    Row(
        modifier = Modifier.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            val displayWeight = WeightDisplayFormatter.formatDisplayWeight(
                session.weightPerCableKg,
                null,
                weightUnit,
            )
            val unitLabel = if (weightUnit == WeightUnit.LB) "lbs" else "kg"
            Text(
                session.exerciseName ?: "Workout Session",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${session.workingReps} reps • $displayWeight $unitLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showChevron) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private data class CycleStartRequest(
    val routineId: String,
    val cycleId: String,
    val dayNumber: Int,
)

/**
 * Issue #549: load the active cycle's progress for the Home cycle banner.
 *
 * Calls [TrainingCycleRepository.checkAndAutoAdvance] first so the banner reflects the current
 * calendar day on initial Home load (the previous `getCycleProgress`-only path left the banner
 * showing the prior day's workout until the user visited the Cycles tab, which is the only
 * other production caller of `checkAndAutoAdvance`). Falls back to `getCycleProgress` when
 * `checkAndAutoAdvance` returns null (e.g. no progress row exists for the active cycle) so
 * the Home path still renders the persisted value if one is present.
 *
 * Exposed as an `internal` suspend function so it can be unit-tested with a fake repository
 * without spinning up a Compose UI host.
 */
internal suspend fun loadHomeCycleProgress(
    repository: TrainingCycleRepository,
    cycle: TrainingCycle,
): CycleProgress? = repository.checkAndAutoAdvance(cycle.id) ?: repository.getCycleProgress(cycle.id)

internal fun buildHomeShortcutActions(
    onSingleExercise: () -> Unit,
    onRoutines: () -> Unit,
    onCycles: () -> Unit,
    onAssessOneRepMaxComingSoon: () -> Unit,
): List<HomeActionSpec> = listOf(
    HomeActionSpec(
        label = "Single Exercise",
        contentDescription = "Open Single Exercise workout",
        icon = Icons.Outlined.FitnessCenter,
        onClick = onSingleExercise,
    ),
    HomeActionSpec(
        label = "Routines",
        contentDescription = "Open Routines",
        icon = Icons.AutoMirrored.Filled.FormatListBulleted,
        onClick = onRoutines,
    ),
    HomeActionSpec(
        label = "Cycles",
        contentDescription = "Open Training Cycles",
        icon = Icons.Default.Loop,
        onClick = onCycles,
        iconAnimation = IconAnimation.ROTATE,
    ),
    HomeActionSpec(
        label = "Assess 1RM",
        contentDescription = "Assess 1RM coming soon",
        icon = Icons.Default.FitnessCenter,
        onClick = onAssessOneRepMaxComingSoon,
        enabled = false,
    ),
)

internal data class HomeActionSpec(
    val label: String,
    val contentDescription: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val iconAnimation: IconAnimation = IconAnimation.NONE,
    val enabled: Boolean = true,
)
