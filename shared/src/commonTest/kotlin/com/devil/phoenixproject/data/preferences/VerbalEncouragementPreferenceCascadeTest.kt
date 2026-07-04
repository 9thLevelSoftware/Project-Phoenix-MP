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
}