package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.domain.model.ConnectionState
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
            "machineTeardownState.toStartGatePresentation()",
            "WorkoutStartGateNotice(",
            "enabled = selectedExercise != null && startGate.startEnabled",
        )
        assertContainsAll(
            setReady,
            "viewModel.machineTeardownState.collectAsState()",
            "machineTeardownState.toStartGatePresentation()",
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
            "confirmEnabled = startGate.startEnabled",
            "confirmLabel =",
            "supportingContent =",
            "WorkoutStartGateNotice(",
            "viewModel.retryWorkoutTeardown()",
            "viewModel.reconnectWorkoutTeardown()",
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

    private fun assertContainsAll(source: String, vararg contracts: String) {
        contracts.forEach { contract ->
            assertTrue(
                source.contains(contract),
                "Expected source contract `$contract` was missing",
            )
        }
    }
}
