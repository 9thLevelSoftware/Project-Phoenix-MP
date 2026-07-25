package com.devil.phoenixproject.domain.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for WorkoutParameters computed properties — Issue #674 (Old School eccentric overload).
 */
class WorkoutParametersTest {

    @Test
    fun `hasEccentricOverload true for Old School plus LOAD_130`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            eccentricLoad = EccentricLoad.LOAD_130,
        )
        assertTrue(params.hasEccentricOverload)
    }

    @Test
    fun `hasEccentricOverload false for Old School plus LOAD_100`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            eccentricLoad = EccentricLoad.LOAD_100,
        )
        assertFalse(params.hasEccentricOverload)
    }

    @Test
    fun `hasEccentricOverload false for Echo plus LOAD_100`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.Echo,
            eccentricLoad = EccentricLoad.LOAD_100,
        )
        assertFalse(params.hasEccentricOverload)
    }

    @Test
    fun `hasEccentricOverload true for Echo plus LOAD_130`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.Echo,
            eccentricLoad = EccentricLoad.LOAD_130,
        )
        assertTrue(params.hasEccentricOverload)
    }

    @Test
    fun `isEchoMode remains false for Old School with eccentric overload`() {
        val params = WorkoutParameters(
            programMode = ProgramMode.OldSchool,
            eccentricLoad = EccentricLoad.LOAD_130,
        )
        assertFalse(params.isEchoMode)
        assertTrue(params.hasEccentricOverload)
    }
}
