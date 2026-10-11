package com.devil.phoenixproject.data.integration

/**
 * What an iOS HealthKit workout export should do after looking up
 * `HKMetadataKeyExternalUUID` (stored as `HKExternalUUID`).
 */
enum class HealthKitWorkoutExportAction {
    /** A workout from this app already has the session external UUID. */
    SKIP_AS_SUCCESS,

    /** Write the workout with `saveObject`. */
    SAVE,
}

/**
 * Chooses whether to save a HealthKit workout or treat an existing copy as success.
 *
 * A match skips the save so retry and re-sync do not insert a second workout.
 * No match saves. A failed lookup also saves: failing the export would drop a
 * workout that may not be in Apple Health yet.
 */
fun healthKitWorkoutExportAction(
    queryFailed: Boolean,
    workoutsFromThisApp: Int,
): HealthKitWorkoutExportAction {
    if (queryFailed) return HealthKitWorkoutExportAction.SAVE
    return if (workoutsFromThisApp > 0) {
        HealthKitWorkoutExportAction.SKIP_AS_SUCCESS
    } else {
        HealthKitWorkoutExportAction.SAVE
    }
}
