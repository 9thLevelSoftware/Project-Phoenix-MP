package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.domain.model.ConnectionState
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.presentation.components.StartGateLabel
import com.devil.phoenixproject.presentation.components.toStartGatePresentation
import com.devil.phoenixproject.presentation.manager.MachineTeardownState
import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Issue687WorkoutStartGateUiTest {

    @Test
    fun `ready enables start with the normal label`() {
        val presentation = MachineTeardownState.Ready.toStartGatePresentation()

        assertTrue(presentation.startEnabled)
        assertEquals(StartGateLabel.START, presentation.label)
        assertFalse(presentation.showRecoveryActions)
    }

    @Test
    fun `tearing down disables start with finishing label`() {
        val presentation = MachineTeardownState.TearingDown(
            executionId = 7L,
            attempt = 1,
        ).toStartGatePresentation()

        assertFalse(presentation.startEnabled)
        assertEquals(StartGateLabel.FINISHING_PREVIOUS_WORKOUT, presentation.label)
        assertFalse(presentation.showRecoveryActions)
    }

    @Test
    fun `recovery required disables start and exposes recovery actions`() {
        val presentation = MachineTeardownState.RecoveryRequired(
            executionId = 7L,
        ).toStartGatePresentation()

        assertFalse(presentation.startEnabled)
        assertEquals(StartGateLabel.START, presentation.label)
        assertTrue(presentation.showRecoveryActions)
    }

    @Test
    fun `bodyweight start is disabled while cable teardown is in progress`() {
        val presentation = MachineTeardownState.TearingDown(
            executionId = 7L,
            attempt = 1,
        ).toStartGatePresentation(requiresMachine = false)

        assertFalse(presentation.startEnabled)
        assertEquals(StartGateLabel.FINISHING_PREVIOUS_WORKOUT, presentation.label)
        assertFalse(presentation.showRecoveryActions)
    }

    @Test
    fun `bodyweight start is disabled while recovery actions stay visible`() {
        val presentation = MachineTeardownState.RecoveryRequired(
            executionId = 7L,
        ).toStartGatePresentation(requiresMachine = false)

        assertFalse(presentation.startEnabled)
        assertEquals(StartGateLabel.START, presentation.label)
        assertTrue(presentation.showRecoveryActions)
    }

    @Test
    fun `post completion bodyweight successor waits for cable teardown Ready`() {
        val nextExercise = successor(isBodyweight = true)
        val states = listOf(
            MachineTeardownState.TearingDown(executionId = 7L, attempt = 1) to false,
            MachineTeardownState.RecoveryRequired(executionId = 7L) to true,
        )

        states.forEach { (state, expectsRecoveryActions) ->
            val presentation = state.toStartGatePresentation(
                requiresMachine = !nextExercise.exercise.isBodyweight,
            )

            assertFalse(presentation.startEnabled)
            assertEquals(expectsRecoveryActions, presentation.showRecoveryActions)
        }
    }

    @Test
    fun `post completion machine successor remains disabled through cable teardown states`() {
        val nextExercise = successor(isBodyweight = false)

        val tearingDown = MachineTeardownState.TearingDown(
            executionId = 7L,
            attempt = 1,
        ).toStartGatePresentation(
            requiresMachine = !nextExercise.exercise.isBodyweight,
        )
        assertFalse(tearingDown.startEnabled)
        assertEquals(StartGateLabel.FINISHING_PREVIOUS_WORKOUT, tearingDown.label)
        assertFalse(tearingDown.showRecoveryActions)

        val recoveryRequired = MachineTeardownState.RecoveryRequired(
            executionId = 7L,
        ).toStartGatePresentation(
            requiresMachine = !nextExercise.exercise.isBodyweight,
        )
        assertFalse(recoveryRequired.startEnabled)
        assertEquals(StartGateLabel.START, recoveryRequired.label)
        assertTrue(recoveryRequired.showRecoveryActions)
    }

    @Test
    fun `post completion missing successor keeps the machine safe default`() {
        val nextExercise: RoutineExercise? = null
        val states = listOf(
            MachineTeardownState.TearingDown(executionId = 7L, attempt = 1),
            MachineTeardownState.RecoveryRequired(executionId = 7L),
        )

        states.forEach { state ->
            val presentation = state.toStartGatePresentation(
                requiresMachine = nextExercise?.exercise?.isBodyweight != true,
            )

            assertFalse(presentation.startEnabled)
        }
        assertTrue(
            states.last().toStartGatePresentation(
                requiresMachine = nextExercise?.exercise?.isBodyweight != true,
            ).showRecoveryActions,
        )
    }

    @Test
    fun `workout tab post completion button derives its gate from the actual successor`() {
        val completedCard = source("presentation/screen/WorkoutTab.kt")
            .substringAfter("private fun CompletedCard(")
            .substringBefore("private fun BodyweightRepEntryDialog(")

        assertContainsAll(
            completedCard,
            "val nextExercise = loadedRoutine?.exercises?.getOrNull(currentExerciseIndex + 1)",
            "val nextExerciseStartGate = machineTeardownState.toStartGatePresentation(",
            "requiresMachine = nextExercise?.exercise?.isBodyweight != true",
            "enabled = nextExerciseStartGate.startEnabled",
        )
    }

    @Test
    fun `recovery actions remain available while disconnected`() {
        assertRecoveryAvailableOutsideConnectedOnlyContent(ConnectionState.Disconnected)
    }

    @Test
    fun `recovery actions remain available while reconnecting`() {
        assertRecoveryAvailableOutsideConnectedOnlyContent(ConnectionState.Connecting)
    }

    @Test
    fun `state holder forwards the gate and recovery actions to workout setup and restart`() {
        val state = source("presentation/screen/WorkoutUiState.kt")
        val tab = source("presentation/screen/WorkoutTab.kt")
        val active = source("presentation/screen/ActiveWorkoutScreen.kt")

        assertContainsAll(
            state,
            "val machineTeardownState: MachineTeardownState = MachineTeardownState.Ready",
            "fun onRetryWorkoutTeardown()",
            "fun onReconnectWorkoutTeardown()",
        )
        assertContainsAll(
            tab,
            "machineTeardownState = state.machineTeardownState",
            "onRetryWorkoutTeardown = actions::onRetryWorkoutTeardown",
            "onReconnectWorkoutTeardown = actions::onReconnectWorkoutTeardown",
            "WorkoutStartGateNotice(",
            "enabled = startGate.startEnabled",
        )
        assertContainsAll(
            active,
            "viewModel.machineTeardownState.collectAsState()",
            "machineTeardownState = machineTeardownState",
            "onRetryWorkoutTeardown = { viewModel.retryWorkoutTeardown() }",
            "onReconnectWorkoutTeardown = { viewModel.reconnectWorkoutTeardown() }",
        )
    }

    @Test
    fun `workout setup and set ready gate only their direct start actions`() {
        val setup = source("presentation/screen/WorkoutSetupDialog.kt")
        val setReady = source("presentation/screen/SetReadyScreen.kt")

        assertContainsAll(
            setup,
            "machineTeardownState: MachineTeardownState",
            "machineTeardownState.toStartGatePresentation(requiresMachine = selectedExercise?.isBodyweight != true)",
            "WorkoutStartGateNotice(",
            "enabled = selectedExercise != null && startGate.startEnabled",
        )
        assertContainsAll(
            setReady,
            "viewModel.machineTeardownState.collectAsState()",
            "machineTeardownState.toStartGatePresentation(requiresMachine = !isBodyweight)",
            "WorkoutStartGateNotice(",
            "viewModel.retryWorkoutTeardown()",
            "viewModel.reconnectWorkoutTeardown()",
            "startGate.startEnabled",
        )
    }

    @Test
    fun `single exercise gates the bottom sheet primary action without changing shared defaults`() {
        val singleExercise = source("presentation/screen/SingleExerciseScreen.kt")
        val bottomSheet = source("presentation/screen/ExerciseEditBottomSheet.kt")

        assertContainsAll(
            singleExercise,
            "viewModel.machineTeardownState.collectAsState()",
            "machineTeardownState.toStartGatePresentation(",
            "requiresMachine = exerciseToConfig?.exercise?.isBodyweight != true",
            "primaryActionEnabled = startGate.startEnabled",
            "primaryActionSupportingContent =",
            "WorkoutStartGateNotice(",
            "viewModel.retryWorkoutTeardown()",
            "viewModel.reconnectWorkoutTeardown()",
        )
        assertContainsAll(
            bottomSheet,
            "primaryActionEnabled: Boolean = true",
            "primaryActionSupportingContent: (@Composable () -> Unit)? = null",
            "primaryActionSupportingContent?.invoke()",
            "enabled = sets.isNotEmpty() && primaryActionEnabled",
        )
    }

    @Test
    fun `daily routines gates only direct resume while shared resume callers keep defaults`() {
        val daily = source("presentation/screen/DailyRoutinesScreen.kt")
        val dialog = source("presentation/components/ResumeRoutineDialog.kt")

        assertContainsAll(
            daily,
            "viewModel.machineTeardownState.collectAsState()",
            "val inMemoryHandle = handle as? RoutineResumeHandle.InMemory",
            "captured.activeRoutineSnapshot.exercises",
            ".getOrNull(captured.exerciseIndex)",
            "?.exercise?.isBodyweight != true",
            "confirmEnabled = !resumeOperationInFlight &&",
            "!discardRetryPending &&",
            "(inMemoryHandle == null || startGate.startEnabled)",
            "confirmLabel =",
        )

        val supportingContentStart = daily.indexOf(
            "supportingContent = if (inMemoryHandle != null) {",
        )
        val supportingContentEnd = daily.indexOf("\n            )", supportingContentStart)
        assertTrue(supportingContentStart >= 0, "Daily direct-Resume support gate is missing")
        assertTrue(supportingContentEnd > supportingContentStart, "Daily Resume dialog boundary is missing")
        val supportingContentBlock = daily
            .substring(supportingContentStart, supportingContentEnd)
            .replace(Regex("\\s+"), " ")
        assertContainsAll(
            supportingContentBlock,
            "supportingContent = if (inMemoryHandle != null) {",
            "WorkoutStartGateNotice(",
            "viewModel.retryWorkoutTeardown()",
            "viewModel.reconnectWorkoutTeardown()",
            "} else { null }",
        )
        assertContainsAll(
            dialog,
            "confirmEnabled: Boolean = true",
            "confirmLabel: String? = null",
            "supportingContent: (@Composable () -> Unit)? = null",
            "supportingContent?.let",
            "enabled = confirmEnabled",
        )
    }

    private fun source(relativePath: String): String {
        val source = readProjectFile("src/commonMain/kotlin/com/devil/phoenixproject/$relativePath")
        assertNotNull(source, "Could not read $relativePath")
        return source
    }

    private fun assertRecoveryAvailableOutsideConnectedOnlyContent(connectionState: ConnectionState) {
        assertFalse(
            connectionState is ConnectionState.Connected,
            "This regression case must exercise a non-connected state: $connectionState",
        )
        val presentation = MachineTeardownState.RecoveryRequired(
            executionId = 19L,
        ).toStartGatePresentation()
        assertFalse(presentation.startEnabled)
        assertTrue(presentation.showRecoveryActions)

        val workoutTab = source("presentation/screen/WorkoutTab.kt")
        val connectedOnlyBranch = workoutTab.indexOf(
            "if (connectionState is ConnectionState.Connected)",
        )
        val recoveryNotice = workoutTab.indexOf("WorkoutStartGateNotice(")
        assertTrue(connectedOnlyBranch >= 0, "WorkoutTab connected-only content branch is missing")
        assertTrue(recoveryNotice >= 0, "WorkoutTab recovery notice is missing")
        assertTrue(
            recoveryNotice < connectedOnlyBranch,
            "RecoveryRequired must keep Retry/Reconnect visible while $connectionState; " +
                "WorkoutStartGateNotice is currently nested under connected-only content",
        )

        val alwaysAvailableRecoveryBlock = workoutTab.substring(recoveryNotice, connectedOnlyBranch)
        assertContainsAll(
            alwaysAvailableRecoveryBlock,
            "onRetry = onRetryWorkoutTeardown",
            "onReconnect = onReconnectWorkoutTeardown",
        )
    }

    private fun successor(isBodyweight: Boolean): RoutineExercise = RoutineExercise(
        id = "post-completion-successor-${if (isBodyweight) "bodyweight" else "machine"}",
        exercise = Exercise(
            name = if (isBodyweight) "Push Up" else "Cable Row",
            muscleGroup = "Test",
            equipment = if (isBodyweight) "" else "HANDLES",
            id = "post-completion-exercise-${if (isBodyweight) "bodyweight" else "machine"}",
            isBodyweightOverride = isBodyweight,
        ),
        orderIndex = 1,
        weightPerCableKg = if (isBodyweight) 0f else 10f,
    )

    private fun assertContainsAll(source: String, vararg contracts: String) {
        contracts.forEach { contract ->
            assertTrue(
                source.contains(contract),
                "Expected source contract `$contract` was missing",
            )
        }
    }
}
