package com.devil.phoenixproject.data.preferences

import com.devil.phoenixproject.domain.model.CustomEquipmentItem
import com.devil.phoenixproject.domain.model.CustomEquipmentPreferences
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Issue #1227: an edit applies to the list as stored when it is written, so an entry that
 * arrived in between (a sync pull, a second screen) is kept, and identity is mint-once.
 */
class CustomEquipmentEditTest {
    private val ezBar = CustomEquipmentItem(token = "U_EZ_BAR", label = "EZ Bar", createdAt = 10L)
    private val landmine = CustomEquipmentItem(token = "U_LANDMINE", label = "Landmine", createdAt = 20L)
    private val stored = CustomEquipmentPreferences(items = listOf(ezBar, landmine))

    @Test
    fun addMintsTheTokenFromTheTrimmedLabelAndKeepsEverythingStored() {
        val result = CustomEquipmentEdit.Add("  Swiss Bar ", createdAt = 30L).applyTo(stored)

        assertEquals(listOf(ezBar, landmine, CustomEquipmentItem("U_SWISS_BAR", "Swiss Bar", 30L)), result.items)
    }

    @Test
    fun addOfANameWithNoLettersOrDigitsChangesNothing() {
        assertEquals(stored, CustomEquipmentEdit.Add(" -- ", createdAt = 30L).applyTo(stored))
    }

    @Test
    fun renameMovesTheLabelOnly() {
        val result = CustomEquipmentEdit.Rename("U_EZ_BAR", " Curl Bar ").applyTo(stored)

        assertEquals(listOf(CustomEquipmentItem("U_EZ_BAR", "Curl Bar", 10L), landmine), result.items)
    }

    @Test
    fun removeDropsOnlyThatToken() {
        assertEquals(listOf(landmine), CustomEquipmentEdit.Remove("U_EZ_BAR").applyTo(stored).items)
    }

    @Test
    fun editsOfAnEntryThatIsGoneChangeNothing() {
        assertEquals(stored, CustomEquipmentEdit.Rename("U_GONE", "Gone").applyTo(stored))
        assertEquals(stored, CustomEquipmentEdit.Remove("U_GONE").applyTo(stored))
    }

    @Test
    fun anAddRacingADuplicateFromSyncFailsValidationInsteadOfOverwriting() {
        // The screen validated against a list without EZ Bar; the stored list has it now.
        val result = CustomEquipmentEdit.Add("ez-bar", createdAt = 30L).applyTo(stored)

        assertEquals(3, result.items.size, "the stored entries are never dropped")
        assertEquals(true, ProfilePreferencesValidator.customEquipment(result).isNotEmpty(), "and the write is refused")
    }
}
