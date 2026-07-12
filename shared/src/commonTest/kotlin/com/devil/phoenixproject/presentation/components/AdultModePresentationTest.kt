package com.devil.phoenixproject.presentation.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdultModePresentationTest {

    @Test
    fun `18 plus confirmation uses themed surface and stacked full width actions`() {
        val presentation = AdultModePresentation.adultsOnlyConfirmation()

        assertEquals(AdultModeDialogTone.ThemeSurface, presentation.containerTone)
        assertEquals(AdultModeActionLayout.StackedFullWidth, presentation.actionLayout)
        assertEquals(AdultModeActionTone.Primary, presentation.confirmTone)
        assertEquals(AdultModeActionTone.Outline, presentation.declineTone)
        assertFalse(presentation.usesBespokePinkAccent)
    }

    @Test
    fun `dominatrix unlock celebration uses brand theme instead of bespoke palette`() {
        val presentation = AdultModePresentation.dominatrixUnlock()

        assertEquals(AdultModeDialogTone.ThemeSurface, presentation.containerTone)
        assertEquals(AdultModeActionLayout.SinglePrimary, presentation.actionLayout)
        assertEquals(AdultModeActionTone.Primary, presentation.confirmTone)
        assertTrue(presentation.usesBrandAccent)
        assertFalse(presentation.usesBespokePinkAccent)
    }

    @Test
    fun `dominatrix hint only shows while vulgar mode is enabled and unlock is still hidden`() {
        assertTrue(
            AdultModePresentation.shouldShowDominatrixHint(
                verbalEncouragementEnabled = true,
                vulgarModeEnabled = true,
                dominatrixModeUnlocked = false,
            ),
        )
        assertFalse(
            AdultModePresentation.shouldShowDominatrixHint(
                verbalEncouragementEnabled = true,
                vulgarModeEnabled = false,
                dominatrixModeUnlocked = false,
            ),
        )
        assertFalse(
            AdultModePresentation.shouldShowDominatrixHint(
                verbalEncouragementEnabled = true,
                vulgarModeEnabled = true,
                dominatrixModeUnlocked = true,
            ),
        )
    }
}
