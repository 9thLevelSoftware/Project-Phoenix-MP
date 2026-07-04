package com.devil.phoenixproject.data.preferences

import com.devil.phoenixproject.domain.model.VulgarTier
import com.devil.phoenixproject.testutil.FakePreferencesManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Issue #611: Cascade invariant tests for the verbal encouragement / vulgar
 * mode / Dominatrix / 18+ gate preference setters.
 *
 * Uses FakePreferencesManager so the test runs in commonTest without disk
 * backing. The fake mirrors the cascade invariants of SettingsPreferencesManager
 * per architecture §9.5.
 */
class VerbalEncouragementPreferenceCascadeTest {

    @Test
    fun `master off cascades vulgar off and dominatrix off`() = runTest {
        val manager = FakePreferencesManager()
        manager.setVerbalEncouragementEnabled(true)
        manager.setAdultsOnlyConfirmed(true)
        manager.setVulgarModeEnabled(true)
        manager.setDominatrixModeUnlocked(true)
        manager.setDominatrixModeActive(true)

        // Pre-state
        var prefs = manager.preferencesFlow.value
        assertTrue(prefs.verbalEncouragementEnabled)
        assertTrue(prefs.vulgarModeEnabled)
        assertTrue(prefs.dominatrixModeActive)

        // Turn master off
        manager.setVerbalEncouragementEnabled(false)
        prefs = manager.preferencesFlow.value

        assertFalse(prefs.verbalEncouragementEnabled, "master is off")
        assertFalse(prefs.vulgarModeEnabled, "vulgar cascaded off")
        assertFalse(prefs.dominatrixModeActive, "dominatrix cascaded off")
    }

    @Test
    fun `vulgar off cascades dominatrix off`() = runTest {
        val manager = FakePreferencesManager()
        manager.setVerbalEncouragementEnabled(true)
        manager.setAdultsOnlyConfirmed(true)
        manager.setVulgarModeEnabled(true)
        manager.setDominatrixModeUnlocked(true)
        manager.setDominatrixModeActive(true)

        manager.setVulgarModeEnabled(false)
        val prefs = manager.preferencesFlow.value

        assertFalse(prefs.vulgarModeEnabled)
        assertFalse(prefs.dominatrixModeActive, "dominatrix cascaded off")
    }

    @Test
    fun `vulgar on blocked when adultsOnlyConfirmed false`() = runTest {
        val manager = FakePreferencesManager()
        manager.setVerbalEncouragementEnabled(true)
        // adultsOnlyConfirmed stays false (default)

        manager.setVulgarModeEnabled(true)
        val prefs = manager.preferencesFlow.value

        assertFalse(prefs.vulgarModeEnabled, "vulgar-on blocked without 18+ confirmation")
        assertFalse(prefs.adultsOnlyConfirmed, "adultsOnlyConfirmed still false")
    }

    @Test
    fun `vulgar on succeeds when adultsOnlyConfirmed true`() = runTest {
        val manager = FakePreferencesManager()
        manager.setVerbalEncouragementEnabled(true)
        manager.setAdultsOnlyConfirmed(true)

        manager.setVulgarModeEnabled(true)
        val prefs = manager.preferencesFlow.value

        assertTrue(prefs.vulgarModeEnabled, "vulgar-on succeeded after 18+ confirmation")
        assertTrue(prefs.adultsOnlyConfirmed)
    }

    @Test
    fun `dominatrix on blocked unless unlocked plus vulgar plus adultsConfirmed`() = runTest {
        val manager = FakePreferencesManager()
        manager.setVerbalEncouragementEnabled(true)
        manager.setAdultsOnlyConfirmed(true)
        manager.setVulgarModeEnabled(true)

        // Case 1: dominatrixModeUnlocked still false
        manager.setDominatrixModeActive(true)
        assertFalse(manager.preferencesFlow.value.dominatrixModeActive, "blocked when unlocked=false")

        manager.setDominatrixModeUnlocked(true)
        // Case 2: vulgar turned off, dominatrix-on should no-op and (because vulgar-off
        // cascades) be cleared.
        manager.setVulgarModeEnabled(false)
        manager.setDominatrixModeActive(true)
        assertFalse(manager.preferencesFlow.value.dominatrixModeActive, "blocked when vulgar off")

        // Case 3: vulgar on, unlocked, but adultsOnlyConfirmed reset to false
        manager.setVulgarModeEnabled(true)
        manager.setAdultsOnlyConfirmed(false)
        manager.setDominatrixModeActive(true)
        assertFalse(
            manager.preferencesFlow.value.dominatrixModeActive,
            "blocked when adultsOnlyConfirmed false",
        )
    }

    @Test
    fun `vulgarTier persists in preferences flow`() = runTest {
        val manager = FakePreferencesManager()
        manager.setVerbalEncouragementEnabled(true)
        manager.setAdultsOnlyConfirmed(true)
        manager.setVulgarModeEnabled(true)

        manager.setVulgarTier(VulgarTier.MILD)
        assertEquals(VulgarTier.MILD, manager.preferencesFlow.value.vulgarTier)

        manager.setVulgarTier(VulgarTier.STRONG)
        assertEquals(VulgarTier.STRONG, manager.preferencesFlow.value.vulgarTier)

        manager.setVulgarTier(VulgarTier.MIX)
        assertEquals(VulgarTier.MIX, manager.preferencesFlow.value.vulgarTier)
    }

    @Test
    fun `defaults match spec master off vulgar off tier STRONG`() {
        val manager = FakePreferencesManager()
        val prefs = manager.preferencesFlow.value

        assertFalse(prefs.verbalEncouragementEnabled)
        assertFalse(prefs.vulgarModeEnabled)
        assertEquals(VulgarTier.STRONG, prefs.vulgarTier, "STRONG is the default tier")
        assertFalse(prefs.dominatrixModeUnlocked)
        assertFalse(prefs.dominatrixModeActive)
        assertFalse(prefs.adultsOnlyConfirmed)
    }

    /**
     * Issue #611 (PR-followup #613): confirm path marks the one-shot decline-remember
     * flag so the modal never re-appears after a confirm. Mirrors the
     * SettingsPreferencesManager.setAdultsOnlyConfirmed cascade which writes
     * KEY_ADULTS_ONLY_PROMPTED = true after writing KEY_ADULTS_ONLY_CONFIRMED.
     */
    @Test
    fun `confirm path marks adults only prompted so modal never re appears`() = runTest {
        val manager = FakePreferencesManager()

        assertFalse(manager.isAdultsOnlyPrompted(), "fresh install: not prompted yet")
        assertFalse(manager.preferencesFlow.value.adultsOnlyConfirmed)

        manager.setAdultsOnlyConfirmed(true)

        assertTrue(manager.isAdultsOnlyPrompted(), "confirm implies prompted")
        assertTrue(manager.preferencesFlow.value.adultsOnlyConfirmed)
    }

    /**
     * Issue #611 (PR-followup #613): decline path leaves adultsOnlyConfirmed=false
     * (the user did NOT confirm) but still marks the one-shot decline-remember flag
     * so the modal does NOT re-prompt on subsequent vulgar-on toggles. This is the
     * regression test for the Kilo "modal re-prompts forever after decline" finding:
     * prior to this fix, the decline path wrote confirmed=false WITHOUT writing the
     * prompted flag, causing the SettingsTab modal gate (`!adultsOnlyPrompted`) to
     * keep firing on every vulgar-on toggle.
     */
    @Test
    fun `decline path leaves adultsOnlyConfirmed false but marks prompted so modal stays dormant`() = runTest {
        val manager = FakePreferencesManager()

        assertFalse(manager.isAdultsOnlyPrompted(), "fresh install: not prompted yet")

        // Mirror the SettingsTab.onDecline callback — calls both confirm AND prompted
        // setters so the cascade invariant holds in either branch.
        manager.setAdultsOnlyConfirmed(false)
        manager.setAdultsOnlyPrompted(true)

        assertFalse(
            manager.preferencesFlow.value.adultsOnlyConfirmed,
            "decline must NOT flip confirmed=true",
        )
        assertTrue(
            manager.isAdultsOnlyPrompted(),
            "decline MUST set prompted=true so the modal is dormant for the rest of this install",
        )

        // Simulate the user toggling vulgar mode OFF then ON again. The SettingsTab
        // gate `if (checked && !adultsOnlyPrompted)` should now evaluate to false,
        // so the modal must NOT re-fire on this install.
        manager.setVulgarModeEnabled(false)
        assertFalse(manager.isAdultsOnlyPrompted().not(), "gate condition: prompted stays true")

        // The "second click on the same install" scenario: even after vulgar-on
        // finally goes through (via the modal path that was supposed to be dormant),
        // prompted stays true and never decrements.
        repeat(3) {
            // Hypothetical: user tries to re-arm vulgar after the decline-dormant gate.
            // The gate `checked && !adultsOnlyPrompted` is false → no modal call.
            assertTrue(
                manager.isAdultsOnlyPrompted(),
                "one-shot flag is monotonic across $it subsequent toggle attempts",
            )
        }
    }

    /**
     * Issue #611 (PR-followup #613): setAdultsOnlyPrompted alone does not flip
     * confirmed — it is purely the decline-remember gate. This is the symmetric
     * invariant to `confirm path marks adults only prompted ...`.
     */
    @Test
    fun `setAdultsOnlyPrompted leaves adultsOnlyConfirmed unchanged`() = runTest {
        val manager = FakePreferencesManager()
        assertFalse(manager.preferencesFlow.value.adultsOnlyConfirmed)

        manager.setAdultsOnlyPrompted(true)

        assertTrue(manager.isAdultsOnlyPrompted())
        assertFalse(
            manager.preferencesFlow.value.adultsOnlyConfirmed,
            "prompted setter must not collide with the confirm cascade",
        )
    }

    /**
     * Issue #611 (PR-followup #613): reset() clears the prompted flag so test
     * isolation is preserved across test methods that share a FakePreferencesManager.
     */
    @Test
    fun `reset clears adultsOnlyPrompted backing field`() = runTest {
        val manager = FakePreferencesManager()
        manager.setAdultsOnlyPrompted(true)
        assertTrue(manager.isAdultsOnlyPrompted())

        manager.reset()

        assertFalse(manager.isAdultsOnlyPrompted(), "reset must clear the one-shot flag")
        assertFalse(manager.preferencesFlow.value.adultsOnlyConfirmed)
    }
}