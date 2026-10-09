package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.data.preferences.CustomEquipmentContract
import com.devil.phoenixproject.data.preferences.ProfilePreferencesValidator
import com.devil.phoenixproject.domain.model.CustomEquipmentItem
import com.devil.phoenixproject.domain.model.CustomEquipmentPreferences
import com.devil.phoenixproject.domain.model.Exercise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Issue #1227: the custom-equipment vocabulary overlay contract (signoff B1/B2/B3/B8/B9).
 *
 * Additive only — the official expected lists in `CustomExerciseEquipmentTest` and
 * `EquipmentChipContractTest` are untouched and stay authoritative for empty-overlay
 * behavior.
 */
class CustomEquipmentOverlayTest {

    private val ezBar = mapOf("U_EZ_BAR" to "EZ Bar")

    private fun exerciseWith(equipment: String, isBodyweightOverride: Boolean? = null): Exercise =
        Exercise(
            name = "Custom",
            muscleGroup = "Chest",
            muscleGroups = "Chest",
            equipment = equipment,
            isCustom = true,
            isBodyweightOverride = isBodyweightOverride,
        )

    private fun item(label: String, token: String = "U_" + CustomEquipmentContract.slugFor(label)): CustomEquipmentItem =
        CustomEquipmentItem(token = token, label = label, createdAt = 1_791_500_000_000L)

    // --- Dropdown (B9: official six frozen, user names appended) ---

    @Test
    fun emptyOverlayDropdownIsExactlyTheOfficialSix() {
        assertEquals(customCableEquipmentOptions(), customEquipmentDropdownOptions())
        assertEquals(6, customCableEquipmentOptions().size)
    }

    @Test
    fun overlayAppendsAfterAnkleStrap() {
        val options = customEquipmentDropdownOptions(ezBar)
        assertEquals(7, options.size)
        assertEquals("Ankle Strap", options[5].second)
        assertEquals("U_EZ_BAR" to "EZ Bar", options[6])
    }

    // --- Subtitle (B2: label, then humanized slug) ---

    @Test
    fun subtitleRendersOverlayLabel() {
        assertEquals("EZ Bar", compactEquipmentLabel("U_EZ_BAR", ezBar))
    }

    @Test
    fun subtitleHumanizesUnmatchedCustomToken() {
        assertEquals("EZ Bar", compactEquipmentLabel("U_EZ_BAR", emptyMap()))
        assertEquals("Curl Bar", compactEquipmentLabel("U_CURL_BAR", emptyMap()))
    }

    @Test
    fun subtitleLeavesUnknownNonCustomTokensDropped() {
        assertEquals("", compactEquipmentLabel("WINGDING", ezBar))
        assertEquals("Handles", compactEquipmentLabel("HANDLES", ezBar))
    }

    // --- Filtering (official lookup first; overlay label resolves to its token) ---

    @Test
    fun overlayLabelResolvesToItsTokenForFiltering() {
        assertEquals(listOf("U_EZ_BAR"), getEquipmentDatabaseValues("EZ Bar", ezBar))
    }

    @Test
    fun officialLookupStaysFirstSoAUserLabelCannotStealAnOfficialToken() {
        val overlay = mapOf("U_LONG_BAR" to "Long Bar")
        assertEquals(listOf("BAR", "LONG_BAR", "BARBELL"), getEquipmentDatabaseValues("Long Bar", overlay))
    }

    // --- Save / preselect (B1 mint-once, B2 preservation) ---

    @Test
    fun reopenSelectsTheCustomRowWhenOverlayContainsIt() {
        val preselect = preselectCustomEquipment(exerciseWith("U_EZ_BAR"), ezBar)
        assertTrue(preselect.usesCables)
        assertTrue(preselect.isListedOption)
        assertEquals("EZ Bar", preselect.displayLabel)
    }

    @Test
    fun customTokenMissingFromOverlayIsPreservedNeverCoerced() {
        val preselect = preselectCustomEquipment(exerciseWith("U_EZ_BAR"), emptyMap())
        assertTrue(preselect.usesCables)
        assertFalse(preselect.isListedOption)
        assertEquals("U_EZ_BAR", preselect.selectedToken)
        assertEquals("EZ Bar", preselect.displayLabel)
        // Save-without-change keeps the token exactly (never BODYWEIGHT / HANDLES).
        assertEquals("U_EZ_BAR", equipmentTokenForCustomSave(usesCables = true, selectedOrPreservedToken = "U_EZ_BAR"))
    }

    @Test
    fun renameChangesLabelOnly() {
        val renamed = mapOf("U_EZ_BAR" to "Curl Bar")
        assertEquals("Curl Bar", labelForCustomEquipmentToken("U_EZ_BAR", renamed))
        assertEquals("Curl Bar", preselectCustomEquipment(exerciseWith("U_EZ_BAR"), renamed).displayLabel)
    }

    @Test
    fun bodyweightModeStillSavesExactlyBodyweight() {
        assertEquals("BODYWEIGHT", equipmentTokenForCustomSave(usesCables = false, selectedOrPreservedToken = "U_EZ_BAR"))
    }

    // --- Classification firewall (B8: hasCableAccessory prefix rule only) ---

    @Test
    fun customTokenCountsAsCableAccessoryButNeverAsUnifiedAttachment() {
        val exercise = exerciseWith("U_EZ_BAR")
        assertTrue(exercise.hasCableAccessory)
        assertFalse(exercise.isBodyweight)
        assertFalse(exercise.usesUnifiedAttachment)
    }

    @Test
    fun storedCustomRowsKeepNullBodyweightOverride() {
        assertNull(exerciseWith("U_EZ_BAR").isBodyweightOverride)
    }

    // --- Shared validator: B1 mint-once collision sequence ---

    @Test
    fun mintOnceThenRenameThenReAddIsRejected() {
        val added = CustomEquipmentPreferences(items = listOf(item("EZ Bar")))
        assertEquals(emptyList(), ProfilePreferencesValidator.customEquipment(added))

        // Rename to "Curl Bar": token and createdAt unchanged — valid.
        val renamed = CustomEquipmentPreferences(
            items = listOf(item("Curl Bar", token = "U_EZ_BAR")),
        )
        assertEquals(emptyList(), ProfilePreferencesValidator.customEquipment(renamed))

        // Re-adding "EZ Bar" is rejected: the minted token U_EZ_BAR is still occupied.
        val reAddByOriginalName = CustomEquipmentPreferences(
            items = renamed.items + item("EZ Bar"),
        )
        assertTrue(ProfilePreferencesValidator.customEquipment(reAddByOriginalName).contains("tokenDuplicate"))

        // Re-adding "curl-bar" is rejected: the label slug collides with the renamed entry.
        val reAddBySlug = CustomEquipmentPreferences(
            items = renamed.items + item("curl-bar", token = "U_CURL_BAR_2"),
        )
        assertTrue(ProfilePreferencesValidator.customEquipment(reAddBySlug).contains("labelDuplicate"))
    }

    @Test
    fun removalThenReAddOfTheSameSlugReusesTheToken() {
        // B2: the removed token is simply absent; re-adding the same slug mints the same
        // token again and is valid.
        val empty = CustomEquipmentPreferences()
        assertEquals(emptyList(), ProfilePreferencesValidator.customEquipment(empty))
        val reAdded = CustomEquipmentPreferences(items = listOf(item("EZ Bar")))
        assertEquals(emptyList(), ProfilePreferencesValidator.customEquipment(reAdded))
        assertEquals("U_EZ_BAR", reAdded.items.single().token)
    }

    // --- Shared validator: the rejection list ---

    @Test
    fun officialDisplayNamesAreRejected() {
        listOf("Long Bar", "Cable", "Bench", "Handles", "Belt").forEach { label ->
            val document = CustomEquipmentPreferences(items = listOf(item(label, token = "U_TEST")))
            assertTrue(
                ProfilePreferencesValidator.customEquipment(document).contains("labelReserved"),
                "expected $label to be reserved",
            )
        }
    }

    @Test
    fun caseAndPunctuationEquivalentsOfAnExistingNameAreRejected() {
        val document = CustomEquipmentPreferences(
            items = listOf(item("EZ Bar"), item("ez bar", token = "U_SECOND")),
        )
        assertTrue(ProfilePreferencesValidator.customEquipment(document).contains("labelDuplicate"))

        val dashEquivalent = CustomEquipmentPreferences(
            items = listOf(item("EZ Bar"), item("EZ-Bar", token = "U_SECOND")),
        )
        assertTrue(ProfilePreferencesValidator.customEquipment(dashEquivalent).contains("labelDuplicate"))
    }

    @Test
    fun malformedLabelsAreRejected() {
        val blank = CustomEquipmentPreferences(items = listOf(item("  ", token = "U_TEST")))
        assertTrue(ProfilePreferencesValidator.customEquipment(blank).contains("emptySlug"))

        val withComma = CustomEquipmentPreferences(items = listOf(item("A,B", token = "U_TEST")))
        assertTrue(ProfilePreferencesValidator.customEquipment(withComma).contains("labelHasComma"))

        val withControl = CustomEquipmentPreferences(items = listOf(item("A\u0001B", token = "U_TEST")))
        assertTrue(ProfilePreferencesValidator.customEquipment(withControl).contains("labelHasControlCharacter"))

        val tooLong = CustomEquipmentPreferences(items = listOf(item("x".repeat(33), token = "U_TEST")))
        assertTrue(ProfilePreferencesValidator.customEquipment(tooLong).contains("labelTooLong"))

        val badToken = CustomEquipmentPreferences(
            items = listOf(CustomEquipmentItem(token = "EZ_BAR", label = "EZ Bar", createdAt = 1L)),
        )
        assertTrue(ProfilePreferencesValidator.customEquipment(badToken).contains("tokenInvalid"))
    }

    @Test
    fun twentyFifthItemIsRejected() {
        val items = (1..25).map { n -> item("Rig$n", token = "U_RIG_$n") }
        val document = CustomEquipmentPreferences(items = items)
        assertTrue(ProfilePreferencesValidator.customEquipment(document).contains("tooManyItems"))
    }

    @Test
    fun validDocumentIsAcceptedAndMintHelperMatchesTheContract() {
        val document = CustomEquipmentPreferences(items = listOf(item("EZ Bar")))
        assertEquals(emptyList(), ProfilePreferencesValidator.customEquipment(document))
        assertEquals("U_EZ_BAR", CustomEquipmentContract.tokenFor("EZ Bar"))
        assertNull(CustomEquipmentContract.tokenFor("  ---  "))
        assertNotNull(CustomEquipmentContract.tokenFor("Curl Bar"))
    }
}
