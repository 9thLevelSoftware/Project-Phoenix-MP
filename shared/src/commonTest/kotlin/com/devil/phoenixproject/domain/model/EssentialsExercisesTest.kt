package com.devil.phoenixproject.domain.model

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Issue #770: the Essentials set is 24 real, current catalogue ids. */
class EssentialsExercisesTest {

    @Test
    fun essentialsAreTwentyFourDistinctIds() {
        assertEquals(24, EssentialsExercises.ids.size)
        assertTrue(EssentialsExercises.ids.all { it.isNotBlank() && it == it.trim() })
    }

    @Test
    fun everyEssentialIdExistsInTheBundledCatalogue() {
        val catalogue = requireNotNull(readProjectFile("src/commonMain/composeResources/files/exercises.json"))
        val catalogueIds = Regex(""""id"\s*:\s*"([^"]+)"""").findAll(catalogue).map { it.groupValues[1] }.toSet()

        val missing = EssentialsExercises.ids - catalogueIds
        assertTrue(missing.isEmpty(), "Not in exercises.json: $missing")
    }

    @Test
    fun containsMatchesByTrimmedId() {
        fun exercise(id: String?) = Exercise(id = id, name = "x", muscleGroup = "Legs", muscleGroups = "Legs", equipment = "")

        assertTrue(EssentialsExercises.contains(exercise(" Barbell_Squat ")))
        assertEquals(false, EssentialsExercises.contains(exercise("Barbell_Squat_Custom")))
        assertEquals(false, EssentialsExercises.contains(exercise(null)))
    }
}
