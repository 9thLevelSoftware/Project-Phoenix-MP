package com.devil.phoenixproject.presentation.components.exercisepicker

import com.devil.phoenixproject.data.local.ExerciseImporter
import com.devil.phoenixproject.data.local.FreeExerciseJson
import com.devil.phoenixproject.data.local.LegacyCatalogueIdMap
import com.devil.phoenixproject.data.local.SupplementalCatalogSeed
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.presentation.components.getEquipmentDatabaseValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #883: pins the advertised-equipment-chip contract that the reported bug violated —
 * every visible chip must match rows the app can actually produce, the Belt chip must match
 * real BELT tokens (never barbell/other/machine stand-ins), and dead chips stay retired.
 */
class EquipmentChipContractTest {

    private fun asExercise(row: FreeExerciseJson): Exercise = Exercise(
        id = row.id,
        name = row.name,
        muscleGroup = "Legs",
        muscleGroups = "Legs",
        equipment = ExerciseImporter.canonicalEquipmentLabel(row.equipment),
    )

    @Test
    fun everyAdvertisedChipMapsToDatabaseTokensExceptBodyweight() {
        for (chip in EQUIPMENT_FILTER_CHIPS) {
            if (chip == "Bodyweight") continue // isBodyweight flag branch, not a token match
            assertTrue(
                getEquipmentDatabaseValues(chip).isNotEmpty(),
                "advertised chip '$chip' has no database tokens and would be a dead filter",
            )
        }
    }

    @Test
    fun beltChipMapsToExactlyTheBeltToken() {
        // Condition C for #883: never widen Belt onto barbell/other/machine tokens.
        assertEquals(listOf("BELT"), getEquipmentDatabaseValues("Belt"))
    }

    @Test
    fun deadChipsFromTheIssue883AuditAreRetired() {
        listOf("Short Bar", "Rope", "Ankle Strap", "Bench").forEach { chip ->
            assertFalse(chip in EQUIPMENT_FILTER_CHIPS, "'$chip' matches no producible row and must stay retired")
        }
    }

    @Test
    fun beltChipKeepsSupplementalSeedRowsAndDropsBarbellOtherAndMachine() {
        val beltRows = SupplementalCatalogSeed.rows.map(::asExercise)
        val decoys = listOf(
            Exercise(id = "Barbell_Squat", name = "Barbell Squat", muscleGroup = "Legs", muscleGroups = "Legs", equipment = "barbell"),
            Exercise(id = "Weighted_Squat", name = "Weighted Squat", muscleGroup = "Legs", muscleGroups = "Legs", equipment = "other"),
            Exercise(id = "Squat_Machine", name = "Machine Squat", muscleGroup = "Legs", muscleGroups = "Legs", equipment = "machine"),
        )

        val result = filterExercisePickerCandidates(
            candidates = decoys + beltRows,
            filters = ExercisePickerFilterState(selectedEquipment = setOf("Belt")),
        )

        assertEquals(SupplementalCatalogSeed.rows.map { it.id }.toSet(), result.map { it.id }.toSet())
    }

    @Test
    fun supplementalSeedRowsAreStableIdBeltEquippedBeltSquats() {
        val expectedIds = listOf(
            SupplementalCatalogSeed.BELT_SQUAT_ID,
            SupplementalCatalogSeed.SUMO_BELT_SQUAT_ID,
            SupplementalCatalogSeed.BELT_SQUAT_PULSES_ID,
        )
        assertEquals(expectedIds, SupplementalCatalogSeed.rows.map { it.id })
        assertEquals(expectedIds.toSet().size, SupplementalCatalogSeed.rows.size, "seed ids must be distinct")

        for (row in SupplementalCatalogSeed.rows) {
            assertTrue(row.name.contains("Belt Squat", ignoreCase = true), "'${row.name}' is not a belt squat")
            val tokens = ExerciseImporter.canonicalEquipmentLabel(row.equipment)
                .split(",").map { it.trim().uppercase() }
            assertTrue(
                tokens.any { it in getEquipmentDatabaseValues("Belt") },
                "'${row.name}' carries ${row.equipment} which the Belt chip cannot match",
            )
        }

        // Condition B: the barbell squat identity stays its own row, untouched by the belt seed.
        assertFalse("Barbell_Squat" in expectedIds)
    }

    @Test
    fun legacyBeltSquatIdsResolveToTheSeedWithoutReversingAppliedRemaps() {
        // Reviewed #883 continuity for the two unmapped belt-squat rows of the retired catalogue.
        assertEquals("Sumo_Belt_Squat", LegacyCatalogueIdMap.explicit["CJWWuqMMu0_BvQ2R"])
        assertEquals("Belt_Squat_Pulses", LegacyCatalogueIdMap.explicit["l8SH5y7rpXyMCwJj"])
        // Condition E: lpnNPw86Vud67vWQ -> Barbell_Squat is already applied and must not be
        // reversed here without product approval; condition B: keep Barbell_Squat distinct.
        assertEquals("Barbell_Squat", LegacyCatalogueIdMap.explicit["lpnNPw86Vud67vWQ"])
    }
}
