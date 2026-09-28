package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.domain.model.Exercise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #970: pins the custom-exercise equipment contract — the six signed-off
 * accessory options and their stored tokens, the Bodyweight save, and the
 * preserve-unknown-token rule for save-without-change.
 */
class CustomExerciseEquipmentTest {

    private fun exerciseWith(
        equipment: String,
        isBodyweightOverride: Boolean? = null,
    ): Exercise = Exercise(
        name = "Custom",
        muscleGroup = "Chest",
        muscleGroups = "Chest",
        equipment = equipment,
        isCustom = true,
        isBodyweightOverride = isBodyweightOverride,
    )

    @Test
    fun createDefaultIsCablesWithHandles() {
        val preselect = preselectCustomEquipment(existing = null)

        assertTrue(preselect.usesCables, "create must open in Cables mode")
        assertEquals("HANDLES", preselect.selectedToken)
        assertEquals("Handles", preselect.displayLabel)
        assertTrue(preselect.isListedOption)
    }

    @Test
    fun saveStoresEachListedAccessoryToken() {
        val expected = listOf("HANDLES", "SHORT_BAR", "BAR", "ROPE", "BELT", "STRAPS")
        assertEquals(expected, customCableEquipmentOptions().map { (token, _) -> token })

        for (token in expected) {
            assertEquals(
                token,
                equipmentTokenForCustomSave(usesCables = true, selectedOrPreservedToken = token),
                "listed accessory '$token' must be stored verbatim",
            )
        }
    }

    @Test
    fun bodyweightSaveWritesBodyweightEvenWithACableTokenSelected() {
        assertEquals(
            "BODYWEIGHT",
            equipmentTokenForCustomSave(usesCables = false, selectedOrPreservedToken = "SHORT_BAR"),
        )
        assertEquals(
            "BODYWEIGHT",
            equipmentTokenForCustomSave(usesCables = false, selectedOrPreservedToken = ""),
        )
    }

    @Test
    fun saveWithNoPickYetFallsBackToHandles() {
        assertEquals(
            "HANDLES",
            equipmentTokenForCustomSave(usesCables = true, selectedOrPreservedToken = ""),
        )
    }

    @Test
    fun editKnownTokenSelectsThatOptionWithCablesOn() {
        for ((token, label) in customCableEquipmentOptions()) {
            val preselect = preselectCustomEquipment(exerciseWith(equipment = token))

            assertTrue(preselect.usesCables, "known token '$token' must open in Cables mode")
            assertEquals(token, preselect.selectedToken)
            assertEquals(label, preselect.displayLabel)
            assertTrue(preselect.isListedOption)
        }
    }

    @Test
    fun editBodyweightRowTurnsBodyweightOnAndHidesDropdown() {
        val preselect = preselectCustomEquipment(exerciseWith(equipment = "BODYWEIGHT"))

        assertFalse(preselect.usesCables, "bodyweight rows must hide the dropdown")
        assertEquals(
            "BODYWEIGHT",
            equipmentTokenForCustomSave(
                usesCables = preselect.usesCables,
                selectedOrPreservedToken = preselect.selectedToken,
            ),
        )

        // Derived classification: no explicit override, non-cable equipment.
        val derived = preselectCustomEquipment(exerciseWith(equipment = "BENCH"))
        assertFalse(derived.usesCables, "derived bodyweight rows must hide the dropdown")

        // Explicit override wins over a cable-looking token list.
        val overridden = preselectCustomEquipment(
            exerciseWith(equipment = "BODYWEIGHT", isBodyweightOverride = true),
        )
        assertFalse(overridden.usesCables)
    }

    @Test
    fun editUnrecognizedTokenIsPreservedOnSaveWithoutChange() {
        for (token in listOf("BARBELL", "CABLE")) {
            val preselect = preselectCustomEquipment(exerciseWith(equipment = token))

            assertTrue(preselect.usesCables, "unrecognized token '$token' must open in Cables mode")
            assertFalse(preselect.isListedOption)
            assertEquals(
                token,
                equipmentTokenForCustomSave(
                    usesCables = preselect.usesCables,
                    selectedOrPreservedToken = preselect.selectedToken,
                ),
                "save-without-change must not coerce '$token' to HANDLES",
            )
        }
    }

    @Test
    fun editCommaListIsPreservedButAListedPickReplacesTheWholeString() {
        val raw = "CABLE,HANDLES"
        val preselect = preselectCustomEquipment(exerciseWith(equipment = raw))

        assertTrue(preselect.usesCables)
        assertEquals(
            raw,
            equipmentTokenForCustomSave(
                usesCables = preselect.usesCables,
                selectedOrPreservedToken = preselect.selectedToken,
            ),
            "comma lists survive save-without-change",
        )
        assertEquals(
            "ROPE",
            equipmentTokenForCustomSave(usesCables = true, selectedOrPreservedToken = "ROPE"),
            "an explicit listed pick replaces the whole equipment string",
        )
    }

    @Test
    fun longBarStoresBarAndAnkleStrapStoresStraps() {
        val tokens = customCableEquipmentOptions().map { (token, _) -> token }

        assertTrue("BAR" in tokens)
        assertFalse("LONG_BAR" in tokens, "Long Bar must never store LONG_BAR")
        assertFalse("BARBELL" in tokens, "Long Bar must never store BARBELL")
        assertTrue("STRAPS" in tokens)
        assertFalse("ANKLE_STRAP" in tokens, "Ankle Strap must never store ANKLE_STRAP")
    }

    @Test
    fun optionLabelsAreTheSignedOffSixWithoutCableBenchOrBarbell() {
        val options = customCableEquipmentOptions()

        assertEquals(
            listOf("Handles", "Short Bar", "Long Bar", "Rope", "Belt", "Ankle Strap"),
            options.map { (_, label) -> label },
        )
        val labels = options.map { (_, label) -> label }
        assertFalse("Cable" in labels)
        assertFalse("Bench" in labels)
        assertFalse("Barbell" in labels)
    }

    @Test
    fun tokenLabelsMatchTheListSubtitleCompactMapping() {
        assertEquals("Long Bar", labelForCustomEquipmentToken("BAR"))
        assertEquals("Long Bar", labelForCustomEquipmentToken("BARBELL"))
        assertEquals("Short Bar", labelForCustomEquipmentToken("SHORT_BAR"))
        assertEquals("Ankle Strap", labelForCustomEquipmentToken("STRAPS"))
        assertEquals("Ankle Strap", labelForCustomEquipmentToken("ANKLE_STRAP"))
        assertEquals("Handles", labelForCustomEquipmentToken("SINGLE_HANDLE"))
        assertEquals("Bodyweight", labelForCustomEquipmentToken("BODYWEIGHT"))
        // Unknown tokens fall back to raw instead of going blank.
        assertEquals("CABLE", labelForCustomEquipmentToken("CABLE"))
        assertEquals("WEIRD_TOKEN", labelForCustomEquipmentToken("WEIRD_TOKEN"))
    }
}
