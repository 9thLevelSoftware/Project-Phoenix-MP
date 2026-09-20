package com.devil.phoenixproject.data.repository

import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileRecoveryCoverageTest {
    @Test
    fun `every discovered content table is covered by the recovery move registry`() {
        assertEquals(PROFILE_RECOVERY_CONTENT_TABLES, PROFILE_RECOVERY_MOVED_CONTENT_TABLES)
        assertEquals(
            emptySet(),
            PROFILE_RECOVERY_MERGED_CONTENT_TABLES intersect PROFILE_RECOVERY_REASSIGNED_CONTENT_TABLES,
        )
        assertEquals(
            setOf("WorkoutSession", "Routine", "TrainingCycle", "PersonalRecord"),
            PROFILE_RECOVERY_CLOUD_OWNERSHIP_ROOT_TABLES,
        )
        assertEquals(
            setOf(
                "ExternalActivity",
                "ExternalBodyMeasurement",
                "ExternalExerciseTemplate",
                "ExternalExerciseTemplateMapping",
                "ExternalProgram",
                "ExternalRoutine",
                "ExternalRoutineFolder",
            ),
            PROFILE_RECOVERY_MOVED_CONTENT_TABLES.filterTo(linkedSetOf()) { it.startsWith("External") },
        )
    }
}
