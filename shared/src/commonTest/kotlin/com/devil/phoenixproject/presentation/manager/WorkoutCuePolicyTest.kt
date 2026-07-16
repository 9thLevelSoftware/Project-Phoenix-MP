package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.RepCountTiming
import com.devil.phoenixproject.domain.model.RepEvent
import com.devil.phoenixproject.domain.model.RepType
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class WorkoutCuePolicyTest {
    @Test
    fun `test sounds omit numbered rep count when profile counter is off`() {
        val events = currentProfileTestSoundEvents(UserPreferences(audioRepCountEnabled = false))

        assertEquals(
            listOf(
                HapticEvent.REP_COMPLETED,
                HapticEvent.WARMUP_COMPLETE,
                HapticEvent.WORKOUT_COMPLETE,
            ),
            events,
        )
        assertFalse(events.any { it is HapticEvent.REP_COUNT_ANNOUNCED })
    }

    @Test
    fun `test sounds include numbered rep count when profile counter is on`() {
        assertEquals(
            listOf(
                HapticEvent.REP_COMPLETED,
                HapticEvent.WARMUP_COMPLETE,
                HapticEvent.REP_COUNT_ANNOUNCED(5),
                HapticEvent.WORKOUT_COMPLETE,
            ),
            currentProfileTestSoundEvents(UserPreferences(audioRepCountEnabled = true)),
        )
    }

    @Test
    fun `rep count policy is bounded and independent of verbal encouragement`() {
        assertFalse(shouldEmitRepCountAnnouncement(UserPreferences(), 1))
        assertFalse(shouldEmitRepCountAnnouncement(UserPreferences(audioRepCountEnabled = true), 0))
        assertTrue(
            shouldEmitRepCountAnnouncement(
                UserPreferences(audioRepCountEnabled = true, verbalEncouragementEnabled = false),
                25,
            ),
        )
        assertTrue(
            shouldEmitRepCountAnnouncement(
                UserPreferences(audioRepCountEnabled = true, verbalEncouragementEnabled = true),
                25,
            ),
        )
    }

    @Test
    fun `top and bottom workout events share the rep count gate`() = runTest {
        val harness = DWSMTestHarness(this)
        val events = mutableListOf<HapticEvent>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.hapticEvents.toList(events)
        }

        try {
            advanceUntilIdle()
            val paths = listOf(
                Triple(
                    RepCountTiming.TOP,
                    RepEvent(type = RepType.WORKING_PENDING, warmupCount = 0, workingCount = 4),
                    5,
                ),
                Triple(
                    RepCountTiming.BOTTOM,
                    RepEvent(type = RepType.WORKING_COMPLETED, warmupCount = 0, workingCount = 5),
                    5,
                ),
            )

            paths.forEach { (timing, event, repNumber) ->
                harness.coordinator._workoutParameters.value = harness.coordinator._workoutParameters.value.copy(
                    isJustLift = true,
                    repCountTiming = timing,
                    reps = 8,
                )
                harness.setActiveProfilePreferences(
                    UserPreferences(audioRepCountEnabled = false, repSoundEnabled = true),
                )
                advanceUntilIdle()
                events.clear()
                harness.repCounter.onRepEvent?.invoke(event)
                advanceUntilIdle()
                assertEquals(listOf<HapticEvent>(HapticEvent.REP_COMPLETED), events)

                harness.setActiveProfilePreferences(
                    UserPreferences(audioRepCountEnabled = true, repSoundEnabled = true),
                )
                advanceUntilIdle()
                events.clear()
                harness.repCounter.onRepEvent?.invoke(event)
                advanceUntilIdle()
                assertEquals(listOf<HapticEvent>(HapticEvent.REP_COUNT_ANNOUNCED(repNumber)), events)
            }
        } finally {
            collector.cancel()
            harness.cleanup()
        }
    }
}
