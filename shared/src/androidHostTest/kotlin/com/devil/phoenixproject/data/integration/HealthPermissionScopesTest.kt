package com.devil.phoenixproject.data.integration

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression guard for issue #531.
 *
 * PR #515 added `HealthPermission.getReadPermission(WeightRecord::class)` to
 * [requiredHealthPermissions]. Because [HealthIntegration.hasPermissions] is the
 * gate used by [HealthIntegration.writeHealthWorkout], every user who upgraded
 * the app while having Health Connect connected with only the workout-write
 * permission silently lost their workout export — `hasPermissions()` started
 * returning false and the workout push surfaced a SecurityException that was
 * only logged at `Logger.w` level.
 *
 * This test pins the post-fix invariant: workout export must depend only on
 * the workout-write permission, body-weight read is requested in the same
 * launcher prompt but is gated separately by [bodyWeightReadHealthPermissions]
 * / [HealthIntegration.hasBodyWeightReadPermission].
 */
class HealthPermissionScopesTest {

    private val workoutWritePermission: String =
        HealthPermission.getWritePermission(ExerciseSessionRecord::class)
    private val caloriesWritePermission: String =
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class)
    private val weightReadPermission: String =
        HealthPermission.getReadPermission(WeightRecord::class)

    @Test
    fun requiredHealthPermissions_onlyGatesWorkoutWrite() {
        assertEquals(
            setOf(workoutWritePermission),
            requiredHealthPermissions,
            "requiredHealthPermissions must only contain the workout-write permission so existing Health Connect users keep their workout sync after an upgrade (issue #531).",
        )
    }

    @Test
    fun requiredHealthPermissions_doesNotIncludeBodyWeightRead() {
        assertFalse(
            requiredHealthPermissions.contains(weightReadPermission),
            "Body-weight read must NOT be part of requiredHealthPermissions; otherwise hasPermissions() returns false for write-only users and writeHealthWorkout fails silently with a SecurityException.",
        )
    }

    @Test
    fun bodyWeightReadHealthPermissions_isolatesWeightReadPermission() {
        assertEquals(
            setOf(weightReadPermission),
            bodyWeightReadHealthPermissions,
            "bodyWeightReadHealthPermissions must isolate the WeightRecord read permission so hasBodyWeightReadPermission() can gate body-weight sync without affecting workout export.",
        )
    }

    @Test
    fun requestedHealthPermissions_includesAllUserFacingPermissions() {
        // The single permission prompt still asks for write + optional calories +
        // body-weight read so users opt into the body-weight import in one flow.
        assertTrue(
            requestedHealthPermissions.contains(workoutWritePermission),
            "Permission prompt must request the workout-write permission.",
        )
        assertTrue(
            requestedHealthPermissions.contains(caloriesWritePermission),
            "Permission prompt must request the optional calories-write permission.",
        )
        assertTrue(
            requestedHealthPermissions.contains(weightReadPermission),
            "Permission prompt must still request the body-weight read permission so new users can opt into body-weight sync from the same dialog.",
        )
    }

    @Test
    fun workoutExportRequestedHealthPermissions_doesNotIncludeBodyWeightRead() {
        // The post-onboarding workout-export retry path
        // ([com.devil.phoenixproject.presentation.components.OptionalPermissionsHandler])
        // only requests workout write + optional calories. Sneaking body-weight
        // read into this set would degrade onboarding UX with an unrelated
        // permission for users who never enabled the body-weight feature.
        assertFalse(
            workoutExportRequestedHealthPermissions.contains(weightReadPermission),
            "workoutExportRequestedHealthPermissions must NOT request body-weight read; onboarding only needs workout-export permissions.",
        )
        assertTrue(
            workoutExportRequestedHealthPermissions.contains(workoutWritePermission),
            "workoutExportRequestedHealthPermissions must request the workout-write permission.",
        )
    }
}
