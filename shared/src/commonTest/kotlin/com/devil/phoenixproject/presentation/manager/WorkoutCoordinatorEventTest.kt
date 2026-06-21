package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.RoutineFlowState
import com.devil.phoenixproject.domain.model.WorkoutState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Tests for dual HapticEvent routing through WorkoutCoordinator.
 *
 * Verifies that FINAL_REP (Phase 41, Issue #100) and VELOCITY_THRESHOLD_REACHED
 * (Phase 43, Issue #313) can coexist in the same haptic event stream without
 * interference or event loss. Both are emitted via the coordinator's shared
 * _hapticEvents flow and consumed by the UI layer.
 */
class WorkoutCoordinatorEventTest {

    private fun createCoordinator(): Pair<WorkoutCoordinator, MutableSharedFlow<HapticEvent>> {
        val hapticFlow = MutableSharedFlow<HapticEvent>(
            extraBufferCapacity = 10,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val coordinator = WorkoutCoordinator(
            _hapticEvents = hapticFlow,
            velocityLossThresholdPercent = 20f,
            autoEndOnVelocityLoss = false,
        )
        return coordinator to hapticFlow
    }

    @Test
    fun finalRepOnlyEmittedAndCollected() = runTest {
        val (coordinator, hapticFlow) = createCoordinator()
        val collected = mutableListOf<HapticEvent>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.hapticEvents.toList(collected)
        }

        hapticFlow.emit(HapticEvent.FINAL_REP)

        assertEquals(1, collected.size, "Should have collected exactly 1 event")
        assertIs<HapticEvent.FINAL_REP>(collected[0], "Event should be FINAL_REP")

        job.cancel()
    }

    @Test
    fun velocityThresholdOnlyEmittedAndCollected() = runTest {
        val (coordinator, hapticFlow) = createCoordinator()
        val collected = mutableListOf<HapticEvent>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.hapticEvents.toList(collected)
        }

        hapticFlow.emit(HapticEvent.VELOCITY_THRESHOLD_REACHED)

        assertEquals(1, collected.size, "Should have collected exactly 1 event")
        assertIs<HapticEvent.VELOCITY_THRESHOLD_REACHED>(
            collected[0],
            "Event should be VELOCITY_THRESHOLD_REACHED",
        )

        job.cancel()
    }

    @Test
    fun sequentialEmissionPreservesOrder() = runTest {
        val (coordinator, hapticFlow) = createCoordinator()
        val collected = mutableListOf<HapticEvent>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.hapticEvents.toList(collected)
        }

        hapticFlow.emit(HapticEvent.FINAL_REP)
        hapticFlow.emit(HapticEvent.VELOCITY_THRESHOLD_REACHED)

        assertEquals(2, collected.size, "Should have collected exactly 2 events")
        assertIs<HapticEvent.FINAL_REP>(collected[0], "First event should be FINAL_REP")
        assertIs<HapticEvent.VELOCITY_THRESHOLD_REACHED>(
            collected[1],
            "Second event should be VELOCITY_THRESHOLD_REACHED",
        )

        job.cancel()
    }

    @Test
    fun rapidEmissionDoesNotDropEvents() = runTest {
        val (coordinator, hapticFlow) = createCoordinator()
        val collected = mutableListOf<HapticEvent>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            coordinator.hapticEvents.toList(collected)
        }

        // Emit both events as rapidly as possible
        hapticFlow.emit(HapticEvent.VELOCITY_THRESHOLD_REACHED)
        hapticFlow.emit(HapticEvent.FINAL_REP)

        assertEquals(
            2,
            collected.size,
            "SharedFlow with extraBufferCapacity=10 should not drop 2 events",
        )

        job.cancel()
    }

    @Test
    fun justLiftRestCountdownKeepsWorkoutSessionActiveWhileIdle() = runTest {
        val (coordinator, _) = createCoordinator()

        assertFalse(
            coordinator.isInWorkoutSession.first(),
            "Idle without a Just Lift rest countdown should not keep the screen awake",
        )

        coordinator._justLiftRestCountdown.value = 30

        assertTrue(
            coordinator.isInWorkoutSession.first(),
            "Just Lift rest countdown should keep the workout session active while state is Idle",
        )

        coordinator._justLiftRestCountdown.value = 0

        assertFalse(
            coordinator.isInWorkoutSession.first(),
            "Expired Just Lift rest countdown should release the workout session wake lock",
        )
    }

    @Test
    fun routineSetReadyKeepsWorkoutSessionActiveWhileIdle() = runTest {
        val (coordinator, _) = createCoordinator()

        coordinator._routineFlowState.value = RoutineFlowState.SetReady(
            exerciseIndex = 0,
            setIndex = 0,
            adjustedWeight = 20f,
            adjustedReps = 8,
        )

        assertTrue(
            coordinator.isInWorkoutSession.first(),
            "Routine SetReady should keep the workout session active while state is Idle",
        )
    }

    @Test
    fun activeWorkoutStateKeepsWorkoutSessionActive() = runTest {
        val (coordinator, _) = createCoordinator()

        coordinator._workoutState.value = WorkoutState.Active

        assertTrue(
            coordinator.isInWorkoutSession.first(),
            "Active workout state should keep the workout session active",
        )
    }
}
