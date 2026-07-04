package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.VulgarTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #611: Verbal encouragement emission gate tests.
 *
 * Mirrors the velocity-threshold state machine from VbtAutoEndTest but adds
 * the verbal encouragement event emission logic. The test-local tracker
 * isolates the logic from the 17+ dependency ActiveSessionEngine so we can
 * verify the gate and tier routing independently.
 *
 * Spec: implementation-spec.md §2 Packet A "Tests required" + architecture.md
 * §13.1 VerbalEncouragementGateTest.
 */
private class VerbalEncouragementTracker(
    private val prefs: () -> UserPreferences,
) {
    var alertEmitted = false
        private set
    val events = mutableListOf<HapticEvent>()

    fun onRepCompleted(shouldStopSet: Boolean) {
        if (shouldStopSet && !alertEmitted) {
            alertEmitted = true
            events.add(HapticEvent.VELOCITY_THRESHOLD_REACHED)
            val current = prefs()
            if (current.beepsEnabled && current.verbalEncouragementEnabled && current.vulgarModeEnabled) {
                events.add(
                    HapticEvent.VERBAL_ENCOURAGEMENT(
                        vulgarTier = current.vulgarTier,
                        dominatrixMode = current.dominatrixModeActive,
                    ),
                )
            }
        }
    }
}

class VerbalEncouragementGateTest {

    @Test
    fun `verbal encouragement suppressed when beeps disabled`() {
        val prefs = UserPreferences(
            beepsEnabled = false,
            verbalEncouragementEnabled = true,
            vulgarModeEnabled = true,
            vulgarTier = VulgarTier.STRONG,
        )
        val tracker = VerbalEncouragementTracker(prefs = { prefs })

        tracker.onRepCompleted(shouldStopSet = true)

        assertEquals(1, tracker.events.size, "Only VELOCITY_THRESHOLD_REACHED")
        assertTrue(tracker.events[0] is HapticEvent.VELOCITY_THRESHOLD_REACHED)
    }

    @Test
    fun `verbal encouragement suppressed when master toggle off`() {
        val prefs = UserPreferences(
            beepsEnabled = true,
            verbalEncouragementEnabled = false,
            vulgarModeEnabled = true,
            vulgarTier = VulgarTier.STRONG,
        )
        val tracker = VerbalEncouragementTracker(prefs = { prefs })

        tracker.onRepCompleted(shouldStopSet = true)

        assertEquals(1, tracker.events.size, "Only VELOCITY_THRESHOLD_REACHED")
        assertTrue(tracker.events[0] is HapticEvent.VELOCITY_THRESHOLD_REACHED)
    }

    @Test
    fun `verbal encouragement emits STRONG tier when vulgar on`() {
        val prefs = UserPreferences(
            beepsEnabled = true,
            verbalEncouragementEnabled = true,
            vulgarModeEnabled = true,
            vulgarTier = VulgarTier.STRONG,
            dominatrixModeActive = false,
        )
        val tracker = VerbalEncouragementTracker(prefs = { prefs })

        tracker.onRepCompleted(shouldStopSet = true)

        assertEquals(2, tracker.events.size)
        val verbal = tracker.events[1] as HapticEvent.VERBAL_ENCOURAGEMENT
        assertEquals(VulgarTier.STRONG, verbal.vulgarTier)
        assertFalse(verbal.dominatrixMode)
    }

    @Test
    fun `verbal encouragement emits MILD tier when vulgar on`() {
        val prefs = UserPreferences(
            beepsEnabled = true,
            verbalEncouragementEnabled = true,
            vulgarModeEnabled = true,
            vulgarTier = VulgarTier.MILD,
            dominatrixModeActive = false,
        )
        val tracker = VerbalEncouragementTracker(prefs = { prefs })

        tracker.onRepCompleted(shouldStopSet = true)

        val verbal = tracker.events[1] as HapticEvent.VERBAL_ENCOURAGEMENT
        assertEquals(VulgarTier.MILD, verbal.vulgarTier)
        assertFalse(verbal.dominatrixMode)
    }

    @Test
    fun `verbal encouragement emits MIX tier when vulgar on`() {
        val prefs = UserPreferences(
            beepsEnabled = true,
            verbalEncouragementEnabled = true,
            vulgarModeEnabled = true,
            vulgarTier = VulgarTier.MIX,
            dominatrixModeActive = false,
        )
        val tracker = VerbalEncouragementTracker(prefs = { prefs })

        tracker.onRepCompleted(shouldStopSet = true)

        val verbal = tracker.events[1] as HapticEvent.VERBAL_ENCOURAGEMENT
        assertEquals(VulgarTier.MIX, verbal.vulgarTier)
        assertFalse(verbal.dominatrixMode)
    }

    @Test
    fun `dominatrix mode forces dominatrix flag regardless of vulgarTier`() {
        val prefs = UserPreferences(
            beepsEnabled = true,
            verbalEncouragementEnabled = true,
            vulgarModeEnabled = true,
            vulgarTier = VulgarTier.STRONG,
            dominatrixModeActive = true,
        )
        val tracker = VerbalEncouragementTracker(prefs = { prefs })

        tracker.onRepCompleted(shouldStopSet = true)

        val verbal = tracker.events[1] as HapticEvent.VERBAL_ENCOURAGEMENT
        assertEquals(VulgarTier.STRONG, verbal.vulgarTier, "Tier carries STRONG")
        assertTrue(verbal.dominatrixMode, "Dominatrix flag set, router picks dominatrix pool")
    }

    @Test
    fun `verbal encouragement emitted only once per set alongside VELOCITY_THRESHOLD_REACHED`() {
        val prefs = UserPreferences(
            beepsEnabled = true,
            verbalEncouragementEnabled = true,
            vulgarModeEnabled = true,
            vulgarTier = VulgarTier.STRONG,
        )
        val tracker = VerbalEncouragementTracker(prefs = { prefs })

        tracker.onRepCompleted(shouldStopSet = true)
        tracker.onRepCompleted(shouldStopSet = true)
        tracker.onRepCompleted(shouldStopSet = true)

        // One threshold alert + one verbal encouragement, even with multiple threshold reps.
        assertEquals(2, tracker.events.size)
        assertTrue(tracker.events[0] is HapticEvent.VELOCITY_THRESHOLD_REACHED)
        assertTrue(tracker.events[1] is HapticEvent.VERBAL_ENCOURAGEMENT)
    }
}