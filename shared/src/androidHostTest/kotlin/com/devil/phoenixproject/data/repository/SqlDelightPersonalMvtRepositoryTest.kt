package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.testutil.createTestDatabase
import com.devil.phoenixproject.testutil.seedExercise
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SqlDelightPersonalMvtRepositoryTest {

    private fun createInMemoryTestDatabase(): PhoenixDatabase = createTestDatabase()

    @Test
    fun `upsert then get round-trips and updates`() = runTest {
        val db = createInMemoryTestDatabase()
        db.seedExercise("ex1", equipment = "BAR")
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
