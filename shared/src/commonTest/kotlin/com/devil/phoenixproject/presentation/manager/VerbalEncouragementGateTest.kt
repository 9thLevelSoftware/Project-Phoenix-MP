package com.devil.phoenixproject.presentation.manager

import com.devil.phoenixproject.domain.model.UserPreferences
import com.devil.phoenixproject.domain.model.VulgarTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Production-policy tests for the VBT threshold verbal-feedback gate. */
class VerbalEncouragementGateTest {

    @Test
    fun `verbal encouragement is suppressed when beeps are disabled`() {
        assertNull(
            UserPreferences(
                beepsEnabled = false,
                verbalEncouragementEnabled = true,
            ).verbalEncouragementEventOrNull(),
        )
    }

    @Test
    fun `verbal encouragement is suppressed when its master is disabled`() {
        assertNull(
            UserPreferences(
                beepsEnabled = true,
                verbalEncouragementEnabled = false,
            ).verbalEncouragementEventOrNull(),
        )
    }

    @Test
    fun `missing adult confirmation routes persisted vulgar intent to neutral audio`() {
        val event = assertNotNull(
            UserPreferences(
                beepsEnabled = true,
                verbalEncouragementEnabled = true,
                vulgarModeEnabled = true,
                vulgarTier = VulgarTier.MIX,
                dominatrixModeUnlocked = true,
                dominatrixModeActive = true,
                adultsOnlyConfirmed = false,
            ).verbalEncouragementEventOrNull(),
        )

        assertEquals(VulgarTier.MIX, event.vulgarTier)
        assertFalse(event.vulgarMode)
        assertFalse(event.dominatrixMode)
    }

    @Test
    fun `adult confirmed vulgar intent keeps selected tier`() {
        val event = assertNotNull(
            UserPreferences(
                beepsEnabled = true,
                verbalEncouragementEnabled = true,
                vulgarModeEnabled = true,
                vulgarTier = VulgarTier.MILD,
                adultsOnlyConfirmed = true,
            ).verbalEncouragementEventOrNull(),
        )

        assertEquals(VulgarTier.MILD, event.vulgarTier)
        assertTrue(event.vulgarMode)
        assertFalse(event.dominatrixMode)
    }

    @Test
    fun `dominatrix routing requires adult vulgar unlocked and active`() {
        val locked = assertNotNull(
            UserPreferences(
                beepsEnabled = true,
                verbalEncouragementEnabled = true,
                vulgarModeEnabled = true,
                dominatrixModeUnlocked = false,
                dominatrixModeActive = true,
                adultsOnlyConfirmed = true,
            ).verbalEncouragementEventOrNull(),
        )
        assertFalse(locked.dominatrixMode)

        val enabled = assertNotNull(
            UserPreferences(
                beepsEnabled = true,
                verbalEncouragementEnabled = true,
                vulgarModeEnabled = true,
                dominatrixModeUnlocked = true,
                dominatrixModeActive = true,
                adultsOnlyConfirmed = true,
            ).verbalEncouragementEventOrNull(),
        )
        assertTrue(enabled.dominatrixMode)
    }

    @Test
    fun `non vulgar encouragement still emits through the neutral pool`() {
        val event = assertNotNull(
            UserPreferences(
                beepsEnabled = true,
                verbalEncouragementEnabled = true,
                vulgarModeEnabled = false,
            ).verbalEncouragementEventOrNull(),
        )

        assertFalse(event.vulgarMode)
        assertFalse(event.dominatrixMode)
    }
}
