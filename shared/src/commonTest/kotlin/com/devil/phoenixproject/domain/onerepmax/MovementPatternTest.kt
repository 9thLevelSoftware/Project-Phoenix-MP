package com.devil.phoenixproject.domain.onerepmax

import kotlin.test.Test
import kotlin.test.assertEquals

class MovementPatternTest {
    @Test fun `bench press classifies as horizontal press`() =
        assertEquals(MovementPattern.HORIZONTAL_PRESS, classifyMovementPattern("Barbell Bench Press", "Chest"))

    @Test fun `overhead press classifies as vertical press`() =
        assertEquals(MovementPattern.VERTICAL_PRESS, classifyMovementPattern("Overhead Press", "Shoulders"))

    @Test fun `back squat classifies as squat`() =
        assertEquals(MovementPattern.SQUAT, classifyMovementPattern("Back Squat", "Legs"))

    @Test fun `deadlift classifies as hinge`() =
        assertEquals(MovementPattern.HINGE, classifyMovementPattern("Romanian Deadlift", "Hamstrings"))

    @Test fun `bicep curl falls back to other`() =
        assertEquals(MovementPattern.OTHER, classifyMovementPattern("Bicep Curl", "Biceps"))

    @Test fun `default mvt values match spec`() {
        assertEquals(0.15f, MovementPattern.HORIZONTAL_PRESS.defaultMvtMs)
        assertEquals(0.20f, MovementPattern.VERTICAL_PRESS.defaultMvtMs)
        assertEquals(0.30f, MovementPattern.SQUAT.defaultMvtMs)
        assertEquals(0.15f, MovementPattern.HINGE.defaultMvtMs)
        assertEquals(0.20f, MovementPattern.OTHER.defaultMvtMs)
    }
}
