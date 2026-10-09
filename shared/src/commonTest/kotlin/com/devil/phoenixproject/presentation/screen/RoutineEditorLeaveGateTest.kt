package com.devil.phoenixproject.presentation.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * B2 leave gate (#1242): not composed or clean leaves at once with no dialog; dirty asks with
 * the editor's existing discard dialog — confirm leaves forward (never popBackStack), cancel
 * navigates nowhere and keeps the draft. Registration lifetime is the editor's composition.
 */
class RoutineEditorLeaveGateTest {
    private class FakeRegistration(
        override val routineId: String,
        private val dirty: () -> Boolean,
    ) : RoutineEditorLeaveRegistration {
        var discardRequests = 0
            private set

        override fun isDirty(): Boolean = dirty()

        override fun requestDiscard(onConfirmed: () -> Unit, onCancelled: () -> Unit) {
            discardRequests++
            pendingConfirm = onConfirmed
            pendingCancel = onCancelled
        }

        var pendingConfirm: (() -> Unit)? = null
        var pendingCancel: (() -> Unit)? = null
    }

    private fun withRegistration(
        routineId: String = "r1",
        dirty: () -> Boolean = { false },
        block: (FakeRegistration) -> Unit,
    ) {
        val registration = FakeRegistration(routineId, dirty)
        RoutineEditorLeaveGate.register(registration)
        try {
            block(registration)
        } finally {
            RoutineEditorLeaveGate.unregister(registration)
        }
    }

    @Test
    fun notComposedLeavesImmediatelyWithNoDialog() {
        var left = 0
        var cancelled = 0
        RoutineEditorLeaveGate.requestLeave(onLeft = { left++ }, onCancelled = { cancelled++ })
        assertEquals(1, left)
        assertEquals(0, cancelled)
    }

    @Test
    fun cleanEditorLeavesImmediatelyWithoutTheDiscardDialog() {
        withRegistration(dirty = { false }) { registration ->
            var left = 0
            RoutineEditorLeaveGate.requestLeave(onLeft = { left++ })
            assertEquals(1, left, "clean leaves at once")
            assertEquals(0, registration.discardRequests, "no dialog for a clean editor")
        }
    }

    @Test
    fun dirtyEditorAsksAndConfirmLeavesForwardWithoutPopping() {
        withRegistration(dirty = { true }) { registration ->
            var left = 0
            RoutineEditorLeaveGate.requestLeave(onLeft = { left++ })
            assertEquals(0, left, "nothing happens until the user decides")
            assertEquals(1, registration.discardRequests)

            registration.pendingConfirm?.invoke()
            assertEquals(1, left, "Discard confirms the forward leave")
            assertTrue(registration.discardRequests == 1, "the gate shows exactly one dialog")
        }
    }

    @Test
    fun dirtyEditorCancelNavigatesNowhereAndKeepsTheDraft() {
        withRegistration(dirty = { true }) { registration ->
            var left = 0
            var cancelled = 0
            RoutineEditorLeaveGate.requestLeave(onLeft = { left++ }, onCancelled = { cancelled++ })
            registration.pendingCancel?.invoke()
            assertEquals(0, left)
            assertEquals(1, cancelled)
        }
    }

    @Test
    fun unregisteringClearsTheRegistration() {
        withRegistration(dirty = { true }) { _ -> }
        assertNull(RoutineEditorLeaveGate.registeredRoutineId())
        assertFalse(RoutineEditorLeaveGate.isDirty())
        var left = 0
        RoutineEditorLeaveGate.requestLeave(onLeft = { left++ })
        assertEquals(1, left, "after dispose the gate is inert again")
    }

    @Test
    fun theRegistrationExposesTheEditedRoutineForTheOverwriteCheck() {
        withRegistration(routineId = "existing-Push", dirty = { true }) {
            assertEquals("existing-Push", RoutineEditorLeaveGate.registeredRoutineId())
            assertTrue(RoutineEditorLeaveGate.isDirty())
        }
    }
}
