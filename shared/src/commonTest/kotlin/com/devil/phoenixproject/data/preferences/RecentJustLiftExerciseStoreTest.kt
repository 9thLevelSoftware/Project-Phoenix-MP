package com.devil.phoenixproject.data.preferences

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Issue #850: the per-profile Recent list behind the Just Lift tagging picker. */
class RecentJustLiftExerciseStoreTest {
    private fun stores(): List<RecentJustLiftExerciseStore> =
        listOf(SettingsRecentJustLiftExerciseStore(MapSettings()), InMemoryRecentJustLiftExerciseStore())

    @Test
    fun recordingMovesTheExerciseToTheFrontWithoutDuplicates() {
        for (store in stores()) {
            store.record("p", "bench")
            store.record("p", "squat")
            store.record("p", "bench")

            assertEquals(listOf("bench", "squat"), store.read("p"), store::class.simpleName)
        }
    }

    @Test
    fun theListKeepsOnlyTheNewestEntries() {
        for (store in stores()) {
            repeat(RecentJustLiftExerciseStore.MAX_ENTRIES + 5) { index -> store.record("p", "exercise-$index") }

            val recent = store.read("p")
            assertEquals(RecentJustLiftExerciseStore.MAX_ENTRIES, recent.size, store::class.simpleName)
            assertEquals("exercise-${RecentJustLiftExerciseStore.MAX_ENTRIES + 4}", recent.first())
            assertFalse("exercise-0" in recent)
        }
    }

    @Test
    fun profilesKeepSeparateLists() {
        for (store in stores()) {
            store.record("a", "bench")
            store.record("b", "squat")

            assertEquals(listOf("bench"), store.read("a"), store::class.simpleName)
            assertEquals(listOf("squat"), store.read("b"), store::class.simpleName)
        }
    }

    @Test
    fun initializeIfAbsentSeedsOnceAndNeverOverwritesARecordedList() {
        for (store in stores()) {
            assertFalse(store.hasEntry("p"))
            store.initializeIfAbsent("p", listOf("squat", " ", "squat", "bench"))
            assertEquals(listOf("squat", "bench"), store.read("p"), store::class.simpleName)

            store.record("p", "row")
            store.initializeIfAbsent("p", listOf("curl"))
            assertEquals(listOf("row", "squat", "bench"), store.read("p"), store::class.simpleName)
        }
    }

    @Test
    fun anEmptySeedStillMarksTheProfileAsInitialized() {
        for (store in stores()) {
            store.initializeIfAbsent("p", emptyList())

            assertTrue(store.hasEntry("p"), store::class.simpleName)
            assertEquals(emptyList(), store.read("p"))
        }
    }

    @Test
    fun deleteRemovesOnlyThatProfilesList() {
        for (store in stores()) {
            store.record("a", "bench")
            store.record("b", "squat")

            store.delete("a")

            assertFalse(store.hasEntry("a"), store::class.simpleName)
            assertEquals(listOf("squat"), store.read("b"))
        }
    }

    @Test
    fun observeReEmitsAfterARecord() = runTest {
        for (store in stores()) {
            assertEquals(emptyList(), store.observe("p").first())
            store.record("p", "bench")
            assertEquals(listOf("bench"), store.observe("p").first(), store::class.simpleName)
        }
    }

    @Test
    fun anUnreadableStoredValueReadsAsEmpty() {
        val settings = MapSettings().apply {
            putString("profile_p_recent_just_lift_exercise_ids", "not json")
        }
        val store = SettingsRecentJustLiftExerciseStore(settings)

        assertEquals(emptyList(), store.read("p"))
        store.record("p", "bench")
        assertEquals(listOf("bench"), store.read("p"))
    }
}
