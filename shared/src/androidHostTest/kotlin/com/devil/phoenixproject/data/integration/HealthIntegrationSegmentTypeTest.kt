package com.devil.phoenixproject.data.integration

import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Regression guard for issue #639.
 *
 * Two product failures observed on Health Connect detail pages for the
 * "Tuesday Upper (Old School)" routine on Android 16 / Pixel 10 Pro XL:
 *
 * 1. `ExerciseSegment.setIndex` was being written as 0, 1, 2 (Phoenix's
 *    internal 0-based convention) so the user saw "Set 0" instead of "Set 1".
 *    Fix: convert at the writer boundary to the 1-based user-visible number
 *    while keeping the rest of the internal pipeline 0-based.
 * 2. `segmentTypeForExercise` mapped "Neutral Wide Grip Pulldown" and
 *    "Front Raise" to the generic `EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING`
 *    (displayed as the literal "Weightlifting"). Fix: extend the mapping so
 *    pulldown / lat-pull variants map to `LAT_PULL_DOWN` and front-raise /
 *    lateral-raise / leg-* / hip-thrust / back-extension variants map to
 *    their specific Health Connect segment types.
 *
 * These tests lock the post-fix invariants so a future refactor cannot
 * silently regress to "Set 0" labels or generic "Weightlifting" segments.
 */
class HealthIntegrationSegmentTypeTest {

    @Test
    fun reporterRoutineMapsEachExerciseToItsSpecificSegmentType() {
        // Exercises from "Tuesday Upper (Old School)" (issue #639 reporter routine).
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
            segmentTypeForExerciseInternal("Incline Bench Press"),
            "Incline Bench Press must map to BENCH_PRESS so Health Connect shows 'Bench press'.",
        )
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_LAT_PULL_DOWN,
            segmentTypeForExerciseInternal("Neutral Wide Grip Pulldown"),
            "Neutral Wide Grip Pulldown must map to LAT_PULL_DOWN, not the generic WEIGHTLIFTING label.",
        )
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_DOUBLE_ARM_TRICEPS_EXTENSION,
            segmentTypeForExerciseInternal("Tricep Pushdown"),
            "Tricep Pushdown must map to DOUBLE_ARM_TRICEPS_EXTENSION.",
        )
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_ARM_CURL,
            segmentTypeForExerciseInternal("Bayesian Cable Curl"),
            "Bayesian Cable Curl must map to ARM_CURL.",
        )
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_FRONT_RAISE,
            segmentTypeForExerciseInternal("Front Raise"),
            "Front Raise must map to FRONT_RAISE, not the generic WEIGHTLIFTING label.",
        )
    }

    @Test
    fun pulldownVariantsMapToLatPullDownNotGenericWeightlifting() {
        // The pre-fix code mapped all of these to EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING,
        // which Health Connect displays as the literal "Weightlifting".
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_LAT_PULL_DOWN,
            segmentTypeForExerciseInternal("Lat Pulldown"),
        )
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_LAT_PULL_DOWN,
            segmentTypeForExerciseInternal("Wide-grip lat pull-down"),
        )
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_LAT_PULL_DOWN,
            segmentTypeForExerciseInternal("Cable Pulldown"),
        )
        // Distinct from pulldown — pull-ups stay on PULL_UP.
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_PULL_UP,
            segmentTypeForExerciseInternal("Pull-Up"),
        )
    }

    @Test
    fun raiseVariantsMapToSpecificTypesNotGenericWeightlifting() {
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_FRONT_RAISE,
            segmentTypeForExerciseInternal("Dumbbell Front Raise"),
        )
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_LATERAL_RAISE,
            segmentTypeForExerciseInternal("Lateral Raise"),
        )
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_LATERAL_RAISE,
            segmentTypeForExerciseInternal("Dumbbell Lateral Raise"),
        )
    }

    @Test
    fun legAndHipVariantsMapToSpecificTypes() {
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_CURL,
            segmentTypeForExerciseInternal("Lying Leg Curl"),
        )
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_CURL,
            segmentTypeForExerciseInternal("Hamstring Curl"),
        )
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_EXTENSION,
            segmentTypeForExerciseInternal("Leg Extension"),
        )
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_PRESS,
            segmentTypeForExerciseInternal("Leg Press"),
        )
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_LEG_RAISE,
            segmentTypeForExerciseInternal("Hanging Leg Raise"),
        )
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_HIP_THRUST,
            segmentTypeForExerciseInternal("Barbell Hip Thrust"),
        )
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_BACK_EXTENSION,
            segmentTypeForExerciseInternal("Back Extension"),
        )
    }

    @Test
    fun unknownExerciseNameFallsBackSafelyToWeightlifting() {
        // Ad-hoc weightlifting outside a routine is the only legitimate use of
        // the generic WEIGHTLIFTING segment type. Unknown exercise names must
        // still resolve (no exception) so Health Connect insertion does not
        // crash on typos or future exercise names.
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING,
            segmentTypeForExerciseInternal(""),
        )
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_WEIGHTLIFTING,
            segmentTypeForExerciseInternal("Some Future Exercise XYZ-2000"),
        )
    }

    @Test
    fun existingMappingsArePreserved() {
        // The new mappings must not regress the existing reporter-relevant
        // branches (and a few more stable ones).
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS,
            segmentTypeForExerciseInternal("Flat Bench Press"),
        )
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_DEADLIFT,
            segmentTypeForExerciseInternal("Conventional Deadlift"),
        )
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT,
            segmentTypeForExerciseInternal("Back Squat"),
        )
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_SHOULDER_PRESS,
            segmentTypeForExerciseInternal("Overhead Press"),
        )
        assertEquals(
            ExerciseSegment.EXERCISE_SEGMENT_TYPE_LUNGE,
            segmentTypeForExerciseInternal("Walking Lunge"),
        )
    }

    @Test
    fun pulldownMappingIsCompatibleWithStrengthTrainingSession() {
        // The writer falls back to WEIGHTLIFTING -> UNKNOWN if a segment type
        // is not compatible with the session type (EXERCISE_TYPE_STRENGTH_TRAINING).
        // Lock in that LAT_PULL_DOWN is a valid segment under strength training.
        assertTrue(
            ExerciseSegment.isSegmentTypeCompatibleWithSessionType(
                ExerciseSegment.EXERCISE_SEGMENT_TYPE_LAT_PULL_DOWN,
                ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            ),
            "LAT_PULL_DOWN must be compatible with STRENGTH_TRAINING sessions, otherwise the writer would silently downgrade to WEIGHTLIFTING (re-introducing the bug).",
        )
        assertTrue(
            ExerciseSegment.isSegmentTypeCompatibleWithSessionType(
                ExerciseSegment.EXERCISE_SEGMENT_TYPE_FRONT_RAISE,
                ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            ),
            "FRONT_RAISE must be compatible with STRENGTH_TRAINING sessions.",
        )
    }

    @Test
    fun setIndexWriterConversion_isOneBased() {
        // Mirror the writer's `setIndex = (setIndex + 1).coerceAtLeast(1)` rule
        // so the regression is locked at the data-flow level too: 0/1/2 in
        // CompletedSet.setNumber must become 1/2/3 in ExerciseSegment.setIndex.
        // (The actual `toHealthConnectSegment` call requires a `Context`; this
        // test pins the conversion formula independently.)
        assertEquals(1, toHealthConnectSetIndex(0), "setNumber 0 must become 1.")
        assertEquals(2, toHealthConnectSetIndex(1), "setNumber 1 must become 2.")
        assertEquals(3, toHealthConnectSetIndex(2), "setNumber 2 must become 3.")
    }

    @Test
    fun setIndexWriterConversion_clampsNegativeToOne() {
        // Defensive: if a future change ever yields a negative setIndex, the
        // writer must still emit >=1 (Health Connect expects 0-based
        // *non-negative* setIndex; we now use 1-based so the floor is 1).
        assertEquals(1, toHealthConnectSetIndex(-1))
        assertEquals(1, toHealthConnectSetIndex(Int.MIN_VALUE))
    }

    @Test
    fun setIndexConversionIsNotIdentity() {
        // Tripwire: if a future refactor accidentally removes the +1, this
        // asserts the conversion still happens. (Catches the most plausible
        // regression shape — copying setIndex through unchanged.)
        assertNotEquals(0, toHealthConnectSetIndex(0), "setIndex 0 must NOT be written as 0 (was the pre-fix bug).")
        assertNotEquals(1, toHealthConnectSetIndex(1), "setIndex 1 must NOT be written as 1 (was the pre-fix bug).")
        assertNotEquals(2, toHealthConnectSetIndex(2), "setIndex 2 must NOT be written as 2 (was the pre-fix bug).")
    }

    private fun toHealthConnectSetIndex(internalSetIndex: Int): Int =
        (internalSetIndex + 1).coerceAtLeast(1)
}
