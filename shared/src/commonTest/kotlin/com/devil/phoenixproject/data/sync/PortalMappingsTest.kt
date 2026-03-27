package com.devil.phoenixproject.data.sync

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PortalMappingsTest {

    // ========== velocityMmSToMps ==========

    @Test
    fun `velocityMmSToMps converts 1000 mm-s to 1 m-s`() {
        assertEquals(1.0f, PortalMappings.velocityMmSToMps(1000f))
    }

    @Test
    fun `velocityMmSToMps converts 0 to 0`() {
        assertEquals(0.0f, PortalMappings.velocityMmSToMps(0f))
    }

    @Test
    fun `velocityMmSToMps converts 500 mm-s to 0_5 m-s`() {
        assertEquals(0.5f, PortalMappings.velocityMmSToMps(500f))
    }

    // ========== loadKgToNewtons ==========

    @Test
    fun `loadKgToNewtons converts 1 kg to 9_80665 N`() {
        assertFloatEquals(9.80665f, PortalMappings.loadKgToNewtons(1.0f))
    }

    @Test
    fun `loadKgToNewtons converts 0 kg to 0 N`() {
        assertEquals(0.0f, PortalMappings.loadKgToNewtons(0f))
    }

    @Test
    fun `loadKgToNewtons converts 100 kg to 980_665 N`() {
        assertFloatEquals(980.665f, PortalMappings.loadKgToNewtons(100f))
    }

    // ========== newtonsToLoadKg round-trip ==========

    @Test
    fun `newtonsToLoadKg round-trip preserves value`() {
        val originalKg = 42.5f
        val newtons = PortalMappings.loadKgToNewtons(originalKg)
        val roundTripped = PortalMappings.newtonsToLoadKg(newtons)
        assertFloatEquals(originalKg, roundTripped)
    }

    @Test
    fun `newtonsToLoadKg converts 0 N to 0 kg`() {
        assertEquals(0.0f, PortalMappings.newtonsToLoadKg(0f))
    }

    // ========== cableToPortal ==========

    @Test
    fun `cableToPortal maps A to left`() {
        assertEquals("left", PortalMappings.cableToPortal("A"))
    }

    @Test
    fun `cableToPortal maps B to right`() {
        assertEquals("right", PortalMappings.cableToPortal("B"))
    }

    @Test
    fun `cableToPortal handles lowercase a`() {
        assertEquals("left", PortalMappings.cableToPortal("a"))
    }

    @Test
    fun `cableToPortal falls through unknown value as lowercase`() {
        assertEquals("c", PortalMappings.cableToPortal("C"))
    }

    // ========== workoutModeToSync ==========

    @Test
    fun `workoutModeToSync converts Old School to OLD_SCHOOL`() {
        assertEquals("OLD_SCHOOL", PortalMappings.workoutModeToSync("Old School"))
    }

    @Test
    fun `workoutModeToSync converts Pump to PUMP`() {
        assertEquals("PUMP", PortalMappings.workoutModeToSync("Pump"))
    }

    @Test
    fun `workoutModeToSync converts TUT to TUT`() {
        assertEquals("TUT", PortalMappings.workoutModeToSync("TUT"))
    }

    @Test
    fun `workoutModeToSync converts TUT Beast to TUT_BEAST`() {
        assertEquals("TUT_BEAST", PortalMappings.workoutModeToSync("TUT Beast"))
    }

    @Test
    fun `workoutModeToSync converts Eccentric Only to ECCENTRIC_ONLY`() {
        assertEquals("ECCENTRIC_ONLY", PortalMappings.workoutModeToSync("Eccentric Only"))
    }

    @Test
    fun `workoutModeToSync converts Echo to ECHO`() {
        assertEquals("ECHO", PortalMappings.workoutModeToSync("Echo"))
    }

    @Test
    fun `workoutModeToSync falls back to uppercase with underscores for unknown`() {
        assertEquals("SOME_MODE", PortalMappings.workoutModeToSync("Some Mode"))
    }

    // ========== workoutModeToSync — DB-stored class names ==========

    @Test
    fun `workoutModeToSync converts DB OldSchool to OLD_SCHOOL`() {
        assertEquals("OLD_SCHOOL", PortalMappings.workoutModeToSync("OldSchool"))
    }

    @Test
    fun `workoutModeToSync converts DB Pump to PUMP`() {
        assertEquals("PUMP", PortalMappings.workoutModeToSync("Pump"))
    }

    @Test
    fun `workoutModeToSync converts DB TUT to TUT`() {
        assertEquals("TUT", PortalMappings.workoutModeToSync("TUT"))
    }

    @Test
    fun `workoutModeToSync converts DB TUTBeast to TUT_BEAST`() {
        assertEquals("TUT_BEAST", PortalMappings.workoutModeToSync("TUTBeast"))
    }

    @Test
    fun `workoutModeToSync converts DB EccentricOnly to ECCENTRIC_ONLY`() {
        assertEquals("ECCENTRIC_ONLY", PortalMappings.workoutModeToSync("EccentricOnly"))
    }

    @Test
    fun `workoutModeToSync converts DB Echo to ECHO`() {
        assertEquals("ECHO", PortalMappings.workoutModeToSync("Echo"))
    }

    // ========== workoutModeToSync — already sync strings (idempotent) ==========

    @Test
    fun `workoutModeToSync passes through OLD_SCHOOL sync string`() {
        assertEquals("OLD_SCHOOL", PortalMappings.workoutModeToSync("OLD_SCHOOL"))
    }

    @Test
    fun `workoutModeToSync passes through TUT_BEAST sync string`() {
        assertEquals("TUT_BEAST", PortalMappings.workoutModeToSync("TUT_BEAST"))
    }

    @Test
    fun `workoutModeToSync passes through ECCENTRIC_ONLY sync string`() {
        assertEquals("ECCENTRIC_ONLY", PortalMappings.workoutModeToSync("ECCENTRIC_ONLY"))
    }

    // ========== workoutModeFromSync ==========

    @Test
    fun `workoutModeFromSync converts OLD_SCHOOL to Old School`() {
        assertEquals("Old School", PortalMappings.workoutModeFromSync("OLD_SCHOOL"))
    }

    @Test
    fun `workoutModeFromSync converts ECHO to Echo`() {
        assertEquals("Echo", PortalMappings.workoutModeFromSync("ECHO"))
    }

    @Test
    fun `workoutModeFromSync converts CLASSIC alias to Old School`() {
        assertEquals("Old School", PortalMappings.workoutModeFromSync("CLASSIC"))
    }

    @Test
    fun `workoutModeFromSync returns input for unknown sync string`() {
        assertEquals("SOME_UNKNOWN", PortalMappings.workoutModeFromSync("SOME_UNKNOWN"))
    }

    // ========== toPortalCategory ==========

    @Test
    fun `toPortalCategory maps Biceps to Arms`() {
        assertEquals("Arms", PortalMappings.toPortalCategory("Biceps"))
    }

    @Test
    fun `toPortalCategory maps Triceps to Arms`() {
        assertEquals("Arms", PortalMappings.toPortalCategory("Triceps"))
    }

    @Test
    fun `toPortalCategory maps Quads to Legs`() {
        assertEquals("Legs", PortalMappings.toPortalCategory("Quads"))
    }

    @Test
    fun `toPortalCategory maps Hamstrings to Legs`() {
        assertEquals("Legs", PortalMappings.toPortalCategory("Hamstrings"))
    }

    @Test
    fun `toPortalCategory maps Abs to Core`() {
        assertEquals("Core", PortalMappings.toPortalCategory("Abs"))
    }

    @Test
    fun `toPortalCategory maps Core to Core`() {
        assertEquals("Core", PortalMappings.toPortalCategory("Core"))
    }

    @Test
    fun `toPortalCategory passes through Chest unchanged`() {
        assertEquals("Chest", PortalMappings.toPortalCategory("Chest"))
    }

    @Test
    fun `toPortalCategory passes through unknown group unchanged`() {
        assertEquals("Traps", PortalMappings.toPortalCategory("Traps"))
    }

    // ========== portalCategories ==========

    @Test
    fun `portalCategories contains all expected categories`() {
        val expected =
            listOf("Chest", "Back", "Shoulders", "Arms", "Legs", "Glutes", "Core", "Full Body")
        assertEquals(expected, PortalMappings.portalCategories)
    }

    // ========== Helpers ==========

    private fun assertFloatEquals(expected: Float, actual: Float, tolerance: Float = 0.001f) {
        assertTrue(
            abs(expected - actual) < tolerance,
            "Expected $expected but got $actual (tolerance $tolerance)",
        )
    }
}
