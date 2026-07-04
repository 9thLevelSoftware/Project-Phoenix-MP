package com.devil.phoenixproject.presentation.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #611: 7-tap easter egg counter scoping + Dominatrix unlock gate tests.
 *
 * Mirrors the disco mode 7-tap pattern from SettingsTab.kt but with the gated
 * counter state machine (architecture §11.1). The test-local class mirrors the
 * counter logic in pure Kotlin so the test does not depend on Compose state.
 */
private class DominatrixEasterEggCounter(
    private val currentTimeMillis: () -> Long,
    private val onUnlock: () -> Unit,
    private val vulgarModeEnabled: () -> Boolean,
    private val alreadyUnlocked: () -> Boolean,
) {
    private var tapCount = 0
    private var lastTapTime = 0L

    fun tap() {
        // Gate: counter only counts when vulgar mode is on AND not yet unlocked
        if (!vulgarModeEnabled() || alreadyUnlocked()) return

        val now = currentTimeMillis()
        if (now - lastTapTime > 2000L) {
            tapCount = 1
        } else {
            tapCount++
        }
        lastTapTime = now

        if (tapCount >= 7) {
            onUnlock()
            tapCount = 0
        }
    }

    fun tapCount(): Int = tapCount
}

class DominatrixUnlockGateTest {

    @Test
    fun `7-tap counter is no-op when vulgar mode is off`() {
        var unlocked = false
        val counter = DominatrixEasterEggCounter(
            currentTimeMillis = { 0L },
            onUnlock = { unlocked = true },
            vulgarModeEnabled = { false },
            alreadyUnlocked = { unlocked },
        )

        repeat(10) { counter.tap() }

        assertFalse(unlocked, "Counter never increments when vulgar mode is off")
        assertEquals(0, counter.tapCount())
    }

    @Test
    fun `7-tap counter increments when vulgar mode is on`() {
        var time = 0L
        var unlocked = false
        val counter = DominatrixEasterEggCounter(
            currentTimeMillis = { time },
            onUnlock = { unlocked = true },
            vulgarModeEnabled = { true },
            alreadyUnlocked = { unlocked },
        )

        // 7 rapid taps within 2 seconds of each other
        repeat(7) {
            counter.tap()
            time += 100L // 100ms between taps
        }

        assertTrue(unlocked, "Unlocked after 7 rapid taps")
    }

    @Test
    fun `7-tap counter resets after 2-second gap`() {
        var time = 0L
        var unlocked = false
        val counter = DominatrixEasterEggCounter(
            currentTimeMillis = { time },
            onUnlock = { unlocked = true },
            vulgarModeEnabled = { true },
            alreadyUnlocked = { unlocked },
        )

        // 5 taps
        repeat(5) {
            counter.tap()
            time += 100L
        }
        // 2.5s gap
        time += 2500L
        // 6th tap should reset counter to 1 (not increment to 6)
        counter.tap()

        assertFalse(unlocked, "Counter reset prevents unlock")
        assertEquals(1, counter.tapCount(), "After 2s gap, counter resets to 1")
    }

    @Test
    fun `adultsOnlyConfirmed precondition blocks Dominatrix row render`() {
        // Modeled as a state predicate — the Settings UI uses
        // `if (vulgarModeEnabled && dominatrixModeUnlocked && adultsOnlyConfirmed)`
        // as the row visibility guard.
        val showDominatrixRow = { vulgar: Boolean, unlocked: Boolean, adults: Boolean ->
            vulgar && unlocked && adults
        }

        assertFalse(
            showDominatrixRow(true, true, false),
            "Dominatrix row hidden when adultsOnlyConfirmed=false",
        )
        assertTrue(
            showDominatrixRow(true, true, true),
            "Dominatrix row visible when all three preconditions met",
        )
    }

    @Test
    fun `counter is no-op when already unlocked`() {
        var time = 0L
        var unlockCount = 0
        val counter = DominatrixEasterEggCounter(
            currentTimeMillis = { time },
            onUnlock = { unlockCount++ },
            vulgarModeEnabled = { true },
            alreadyUnlocked = { true }, // pretend already unlocked
        )

        repeat(10) {
            counter.tap()
            time += 100L
        }

        assertEquals(0, unlockCount, "Already-unlocked state is permanent")
    }
}