package com.devil.phoenixproject.presentation.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #1227 (signoff B3): label-keyed equipment filter selections follow the token when
 * the custom overlay changes, on every picker surface, so a chip that is no longer shown
 * can never keep filtering out every result.
 */
class EquipmentSelectionRekeyTest {
    private val before = mapOf("U_EZ_BAR" to "EZ Bar", "U_LANDMINE" to "Landmine")

    @Test
    fun aRenamedEntryStaysSelectedUnderItsNewLabel() {
        val after = mapOf("U_EZ_BAR" to "Curl Bar", "U_LANDMINE" to "Landmine")
        assertEquals(setOf("Curl Bar", "Landmine"), rekeyEquipmentSelection(setOf("EZ Bar", "Landmine"), before, after))
    }

    @Test
    fun aRemovedEntryIsDropped() {
        val after = mapOf("U_LANDMINE" to "Landmine")
        assertEquals(setOf("Landmine"), rekeyEquipmentSelection(setOf("EZ Bar", "Landmine"), before, after))
    }

    @Test
    fun anotherProfilesOverlayDropsEveryCustomSelectionButKeepsOfficialOnes() {
        val otherProfile = mapOf("U_SWISS_BAR" to "Swiss Bar")
        assertEquals(
            setOf("Long Bar", "Handles"),
            rekeyEquipmentSelection(setOf("EZ Bar", "Long Bar", "Handles"), before, otherProfile),
        )
    }

    @Test
    fun anUnchangedOverlayKeepsTheSelection() {
        assertEquals(setOf("EZ Bar", "Bench"), rekeyEquipmentSelection(setOf("EZ Bar", "Bench"), before, before))
    }
}
