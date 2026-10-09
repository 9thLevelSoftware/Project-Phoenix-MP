package com.devil.phoenixproject.data.integration

import kotlin.test.Test
import kotlin.test.assertEquals

class HealthKitWorkoutExportActionTest {

    @Test
    fun matchSkipsSaveAsSuccess() {
        assertEquals(
            HealthKitWorkoutExportAction.SKIP_AS_SUCCESS,
            healthKitWorkoutExportAction(queryFailed = false, workoutsFromThisApp = 1),
        )
        assertEquals(
            HealthKitWorkoutExportAction.SKIP_AS_SUCCESS,
            healthKitWorkoutExportAction(queryFailed = false, workoutsFromThisApp = 2),
        )
    }

    @Test
    fun noMatchSaves() {
        assertEquals(
            HealthKitWorkoutExportAction.SAVE,
            healthKitWorkoutExportAction(queryFailed = false, workoutsFromThisApp = 0),
        )
    }

    @Test
    fun queryErrorSaves() {
        assertEquals(
            HealthKitWorkoutExportAction.SAVE,
            healthKitWorkoutExportAction(queryFailed = true, workoutsFromThisApp = 0),
        )
        assertEquals(
            HealthKitWorkoutExportAction.SAVE,
            healthKitWorkoutExportAction(queryFailed = true, workoutsFromThisApp = 1),
            "A lookup error must still save, even if a sample count was also returned.",
        )
    }
}
