package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class SqlDelightProfileExerciseBaselineRepositoryTest {
    private lateinit var database: PhoenixDatabase
    private lateinit var repository: SqlDelightProfileExerciseBaselineRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        repository = SqlDelightProfileExerciseBaselineRepository(database)
        insertProfile("profile-a")
        insertProfile("profile-b")
        insertExercise("bench")
        insertExercise("squat")
    }

    @Test
    fun `set is profile scoped and nullable clear remains a revisioned tombstone`() = runTest {
        val first = repository.set("profile-a", "bench", 80f, updatedAt = 10L)
        repository.set("profile-b", "bench", 45f, updatedAt = 11L)
        val cleared = repository.set("profile-a", "bench", null, updatedAt = 12L)

        assertEquals(1L, first.revision)
        assertNull(cleared.oneRepMaxPerCableKg)
        assertEquals(2L, cleared.revision)
        assertEquals(45f, repository.get("profile-b", "bench")?.oneRepMaxPerCableKg)
    }

    @Test
    fun `public operations reject blank ids and non-positive or non-finite values`() = runTest {
        assertFailsWith<IllegalArgumentException> { repository.get(" ", "bench") }
        assertFailsWith<IllegalArgumentException> { repository.set("profile-a", "", 10f, 1L) }
        assertFailsWith<IllegalArgumentException> { repository.set("profile-a", "bench", 0f, 1L) }
        assertFailsWith<IllegalArgumentException> { repository.set("profile-a", "bench", Float.NaN, 1L) }
        assertFailsWith<IllegalArgumentException> { repository.raiseIfGreater("profile-a", "bench", Float.POSITIVE_INFINITY, 1L) }
    }

    @Test
    fun `raise if greater inserts and advances only for a greater value`() = runTest {
        assertTrue(repository.raiseIfGreater("profile-a", "bench", 70f, 10L))
        assertFalse(repository.raiseIfGreater("profile-a", "bench", 65f, 11L))
        assertTrue(repository.raiseIfGreater("profile-a", "bench", 75f, 12L))

        val baseline = repository.get("profile-a", "bench")
        assertEquals(75f, baseline?.oneRepMaxPerCableKg)
        assertEquals(2L, baseline?.revision)
        assertEquals(12L, baseline?.updatedAt)
    }

    @Test
    fun `atomic increment advances the latest value without moving time backward`() = runTest {
        repository.set("profile-a", "bench", 40f, 100L)

        val incremented = repository.increment("profile-a", "bench", 2.5f, 50L)

        assertEquals(42.5f, incremented?.oneRepMaxPerCableKg)
        assertEquals(100L, incremented?.updatedAt)
        assertEquals(2L, incremented?.revision)

        repository.set("profile-a", "bench", null, 110L)
        assertNull(repository.increment("profile-a", "bench", 2.5f, 120L))
        assertNull(repository.get("profile-a", "bench")?.oneRepMaxPerCableKg)
    }

    @Test
    fun `older timestamp raise remains newer authority during catalog remap`() = runTest {
        insertExercise("bench-new")
        repository.set("profile-a", "bench", 70f, 100L)
        repository.set("profile-a", "bench-new", 75f, 75L)

        assertTrue(repository.raiseIfGreater("profile-a", "bench", 80f, 50L))
        assertEquals(100L, repository.get("profile-a", "bench")?.updatedAt)

        repository.remapExercise("bench", "bench-new")
        assertEquals(80f, repository.get("profile-a", "bench-new")?.oneRepMaxPerCableKg)
    }

    @Test
    fun `assessment compensation restores only the exact written revision`() = runTest {
        val previous = repository.set("profile-a", "bench", 40f, 1L)
        val assessment = repository.set("profile-a", "bench", 50f, 2L)

        assertTrue(
            repository.compensateAssessmentWrite(
                "profile-a",
                "bench",
                expectedWrittenRevision = assessment.revision,
                previous = previous,
            ),
        )
        assertEquals(40f, repository.get("profile-a", "bench")?.oneRepMaxPerCableKg)

        val newer = repository.set("profile-a", "bench", 60f, 3L)
        assertFalse(
            repository.compensateAssessmentWrite(
                "profile-a",
                "bench",
                expectedWrittenRevision = assessment.revision,
                previous = previous,
            ),
        )
        assertEquals(newer, repository.get("profile-a", "bench"))
    }

    @Test
    fun `assessment write atomically returns the exact prior row and committed revision`() = runTest {
        val previous = repository.set("profile-a", "bench", 40f, 1L)

        val receipt = repository.writeForAssessment("profile-a", "bench", 50f, 2L)

        assertEquals(previous, receipt.previous)
        assertEquals(2L, receipt.written.revision)
        assertEquals(50f, receipt.written.oneRepMaxPerCableKg)

        repository.raiseIfGreater("profile-a", "bench", 60f, 3L)
        assertFalse(
            repository.compensateAssessmentWrite(
                profileId = "profile-a",
                exerciseId = "bench",
                expectedWrittenRevision = receipt.written.revision,
                previous = receipt.previous,
            ),
        )
        assertEquals(60f, repository.get("profile-a", "bench")?.oneRepMaxPerCableKg)
    }

    @Test
    fun `sole profile repair copies and consumes legacy values transactionally`() = runTest {
        database.phoenixDatabaseQueries.deleteProfile("profile-b")
        val exactLegacyValue = 90.123456789
        insertExercise("legacy-bench", exactLegacyValue)

        assertEquals(1, repository.copyAndConsumeLegacyForSoleProfile("profile-a", 100L))
        assertEquals(exactLegacyValue.toFloat(), repository.get("profile-a", "legacy-bench")?.oneRepMaxPerCableKg)
        assertNull(database.phoenixDatabaseQueries.selectExerciseById("legacy-bench").executeAsOne().one_rep_max_kg)
    }

    @Test
    fun `sole profile repair refuses ambiguous multi-profile ownership without consuming`() = runTest {
        insertExercise("legacy-bench", 90.0)

        assertFailsWith<IllegalStateException> {
            repository.copyAndConsumeLegacyForSoleProfile("profile-a", 100L)
        }
        assertNull(repository.get("profile-a", "legacy-bench"))
        assertEquals(90.0, database.phoenixDatabaseQueries.selectExerciseById("legacy-bench").executeAsOne().one_rep_max_kg)
    }

    @Test
    fun `profile deletion copy preserves target and fills target absence`() = runTest {
        repository.set("profile-a", "bench", 80f, 10L)
        repository.set("profile-a", "squat", 100f, 11L)
        repository.set("profile-b", "bench", 60f, 12L)

        repository.copyForProfileDeletion("profile-a", "profile-b")
        database.phoenixDatabaseQueries.deleteProfile("profile-a")

        assertEquals(60f, repository.get("profile-b", "bench")?.oneRepMaxPerCableKg)
        assertEquals(100f, repository.get("profile-b", "squat")?.oneRepMaxPerCableKg)
    }

    @Test
    fun `catalog remap uses newer timestamp and keeps target on tie`() = runTest {
        insertExercise("bench-new")
        repository.set("profile-a", "bench", 80f, 20L)
        repository.set("profile-a", "bench-new", 70f, 10L)
        repository.set("profile-b", "bench", 50f, 30L)
        repository.set("profile-b", "bench-new", 60f, 30L)

        repository.remapExercise("bench", "bench-new")

        assertEquals(80f, repository.get("profile-a", "bench-new")?.oneRepMaxPerCableKg)
        assertEquals(60f, repository.get("profile-b", "bench-new")?.oneRepMaxPerCableKg)
        assertNull(repository.get("profile-a", "bench"))
        assertNull(repository.get("profile-b", "bench"))
    }

    private fun insertProfile(id: String) {
        database.phoenixDatabaseQueries.insertProfile(id, id, 0L, 1L, 0L)
    }

    private fun insertExercise(id: String, legacyOneRepMaxKg: Double? = null) {
        database.phoenixDatabaseQueries.insertExercise(
            id = id,
            name = id,
            displayName = null,
            description = null,
            created = 0L,
            muscleGroup = "Chest",
            muscleGroups = "Chest",
            muscles = null,
            equipment = "BAR",
            movement = null,
            sidedness = null,
            grip = null,
            gripWidth = null,
            minRepRange = null,
            popularity = 0.0,
            archived = 0L,
            isFavorite = 0L,
            isCustom = 0L,
            timesPerformed = 0L,
            lastPerformed = null,
            aliases = null,
            defaultCableConfig = "DOUBLE",
            one_rep_max_kg = legacyOneRepMaxKg,
            mvtOverrideMs = null,
            isBodyweight = null,
        )
    }
}
