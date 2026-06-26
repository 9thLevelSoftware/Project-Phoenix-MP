package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.RepEvent
import com.devil.phoenixproject.domain.model.RepType
import com.devil.phoenixproject.testutil.DWSMTestHarness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * Issue #531: the warmup -> working transition must play a single clean tone. Previously the
 * rep-event handler emitted both WARMUP_COMPLETE and WARMUP_TO_WORKING, which map to the same
 * `beepboop` file on both platforms -> a stacked double-tone heard mid-set.
 */
class WarmupTransitionToneTest {

    @Test
    fun `warmup complete emits a single transition tone`() = runTest {
        val harness = DWSMTestHarness(this)
        val collected = mutableListOf<HapticEvent>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            harness.coordinator.hapticEvents.toList(collected)
        }

        try {
            // Drive the rep-event path exactly as RepCounterFromMachine does on warmup completion.
            harness.repCounter.onRepEvent?.invoke(
                RepEvent(type = RepType.WARMUP_COMPLETE, warmupCount = 3, workingCount = 0),
            )
            advanceUntilIdle()

            assertEquals(
                1,
                collected.count { it is HapticEvent.WARMUP_COMPLETE },
                "Exactly one WARMUP_COMPLETE should be emitted",
            )
            assertEquals(
                0,
                collected.count { it is HapticEvent.WARMUP_TO_WORKING },
                "WARMUP_TO_WORKING must no longer be emitted (single clean transition tone)",
            )
        } finally {
            job.cancel()
            harness.cleanup()
        }
    }
}
