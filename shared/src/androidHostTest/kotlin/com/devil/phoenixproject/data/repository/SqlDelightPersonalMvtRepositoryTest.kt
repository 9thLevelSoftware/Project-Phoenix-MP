package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqlDelightPersonalMvtRepositoryTest {

    private fun createInMemoryTestDatabase(): VitruvianDatabase = createTestDatabase()

    private fun seedExercise(db: VitruvianDatabase, id: String) {
        db.vitruvianDatabaseQueries.insertExercise(
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
            one_rep_max_kg = null,
            mvtOverrideMs = null,
        )
    }

    @Test
    fun `upsert then get round-trips and updates`() = runTest {
        val db = createInMemoryTestDatabase()
        seedExercise(db, id = "ex1")
        val repo = SqlDelightPersonalMvtRepository(db)
        assertNull(repo.get("ex1", "default"))

        repo.upsert("ex1", "default", personalMvtMs = 0.18f, sampleCount = 1)
        assertEquals(1, repo.get("ex1", "default")?.sampleCount)

        repo.upsert("ex1", "default", personalMvtMs = 0.19f, sampleCount = 2)
        val updated = repo.get("ex1", "default")
        assertEquals(2, updated?.sampleCount)
        assertEquals(0.19f, updated?.personalMvtMs)
    }
}
