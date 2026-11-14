package com.devil.phoenixproject.presentation.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.devil.phoenixproject.presentation.manager.RestControlAction
import com.devil.phoenixproject.presentation.manager.RestControlFeedbackEvent
import com.devil.phoenixproject.presentation.manager.RestControlRejectionReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #1360: exercise touch dispatch through the real rest card and action factory.
 * State is deliberately a small observable sink: engine countdown behavior is tested
 * separately. Robolectric cannot establish native iOS gesture behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Issue1360RestTimerUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun addThirtySeconds_updatesDisplayedTimeThroughWorkoutActions() {
        renderRestTimer()

        assertDisplayed("1:00")
        tap("30s")
        assertDisplayed("1:30")
        tap("30s")
        assertDisplayed("2:00")
    }

    @Test
    fun pauseAndResume_updatesDisplayedStateAndStillAllowsExtension() {
        renderRestTimer()

        tap("Pause")
        assertDisplayed("PAUSED")
        assertDisplayed("Resume")
        assertDisplayed("1:00")

        tap("30s")
        assertDisplayed("1:30")
        assertDisplayed("PAUSED")

        tap("Resume")
        assertDisplayed("Pause")
        rule.onNodeWithText("PAUSED").assertDoesNotExist()
        assertDisplayed("1:30")
    }

    @Test
    fun restControlFeedbackReceiptFollowsDisplayedSnackbarAndRetainsCorrelation() {
        val hostState = SnackbarHostState()
        val event = RestControlFeedbackEvent(
            actionId = 1360L,
            action = RestControlAction.EXTEND,
            ownerGeneration = 7L,
            reason = RestControlRejectionReason.PERSISTENCE_FAILED,
            seconds = 30,
        )
        val visibleEvents = mutableListOf<RestControlFeedbackEvent>()
        lateinit var snackbarJob: Job

        rule.setContent {
            MaterialTheme {
                SnackbarHost(hostState = hostState)
            }
        }
        rule.runOnIdle {
            snackbarJob = CoroutineScope(Dispatchers.Main.immediate).launch {
                showRestControlFeedback(
                    snackbarHostState = hostState,
                    message = "Rest timer update failed",
                    event = event,
                    recordVisible = visibleEvents::add,
                )
            }
        }

        rule.waitUntil(timeoutMillis = 5_000) { hostState.currentSnackbarData != null }
        rule.onNodeWithText("Rest timer update failed").assertIsDisplayed()
        assertTrue(visibleEvents.isEmpty(), "Visibility receipt must wait for normal snackbar completion")

        rule.runOnIdle { hostState.currentSnackbarData?.dismiss() }
        rule.waitUntil(timeoutMillis = 5_000) { snackbarJob.isCompleted }
        assertEquals(listOf(event), visibleEvents)
    }

    @Test
    fun cancelingRestControlSnackbarDoesNotRecordFeedbackVisible() {
        val hostState = SnackbarHostState()
        val event = RestControlFeedbackEvent(
            actionId = 1361L,
            action = RestControlAction.TOGGLE_PAUSE,
            ownerGeneration = 8L,
            reason = RestControlRejectionReason.COMMIT_UNCERTAIN,
            seconds = null,
        )
        val visibleEvents = mutableListOf<RestControlFeedbackEvent>()
        lateinit var snackbarJob: Job

        rule.setContent {
            MaterialTheme {
                SnackbarHost(hostState = hostState)
            }
        }
        rule.runOnIdle {
            snackbarJob = CoroutineScope(Dispatchers.Main.immediate).launch {
                showRestControlFeedback(
                    snackbarHostState = hostState,
                    message = "Rest timer update failed",
                    event = event,
                    recordVisible = visibleEvents::add,
                )
            }
        }

        rule.waitUntil(timeoutMillis = 5_000) { hostState.currentSnackbarData != null }
        rule.onNodeWithText("Rest timer update failed").assertIsDisplayed()
        rule.runOnIdle { snackbarJob.cancel() }
        rule.waitUntil(timeoutMillis = 5_000) { snackbarJob.isCompleted }
        assertTrue(visibleEvents.isEmpty(), "Cancellation must not fabricate a feedback-visible receipt")
    }

    private fun renderRestTimer() {
        val seconds = mutableStateOf(60)
        val paused = mutableStateOf(false)
        val actions = workoutActions(
            onScan = {},
            onStopWorkout = {},
            onSkipRest = {},
            onExtendRest = { seconds.value += it },
            onToggleRestPause = { paused.value = !paused.value },
            onSkipCountdown = {},
            onProceedFromSummary = {},
            onRpeLogged = {},
            onResetForNewWorkout = {},
            onStartNextExercise = {},
            onJumpToExercise = {},
            onUpdateParameters = {},
            kgToDisplay = { value, _ -> value },
            displayToKg = { value, _ -> value },
            formatWeight = { value, _ -> "$value kg" },
        )
        rule.setContent {
            MaterialTheme {
                // Match WorkoutTab's scrolling host, with a deliberately short viewport.
                Column(
                    Modifier.fillMaxWidth().height(400.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    RestTimerCard(
                        restSecondsRemaining = seconds.value,
                        nextExerciseName = "Squat",
                        isLastExercise = false,
                        currentSet = 1,
                        totalSets = 3,
                        isRestPaused = paused.value,
                        formatWeightWithUnit = actions::formatWeight,
                        onSkipRest = { actions.onSkipRest() },
                        onExtendRest = actions::onExtendRest,
                        onToggleRestPause = actions::onToggleRestPause,
                        onResetRest = actions::onResetRest,
                        onEndWorkout = actions::onStopWorkout,
                    )
                }
            }
        }
    }

    private fun tap(text: String) {
        rule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
            .performTouchInput { click() }
    }

    private fun assertDisplayed(text: String) {
        rule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }
}
