package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxResult
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before

class SqlDelightVelocityOneRepMaxRepositoryTest {

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
        )
    }

    private fun result(estimate: Float, passed: Boolean) =
        VelocityOneRepMaxResult(
            estimatedPerCableKg = estimate,
            mvtUsedMs = 0.3f,
            r2 = if (passed) 0.95f else 0.4f,
            distinctLoads = 3,
            passedQualityGate = passed,
        )

    @Test
    fun `insert then latest passing returns most recent passing row`() = runTest {
        val db = createInMemoryTestDatabase()
        seedExercise(db, id = "ex1")
        val repo = SqlDelightVelocityOneRepMaxRepository(db)

        repo.insert(result(100f, passed = true), exerciseId = "ex1", computedAt = 1_000L, profileId = "default")
        repo.insert(result(120f, passed = false), exerciseId = "ex1", computedAt = 2_000L, profileId = "default")
        repo.insert(result(110f, passed = true), exerciseId = "ex1", computedAt = 3_000L, profileId = "default")

        val latest = repo.getLatestPassing("ex1", "default")
        assertEquals(110f, latest?.estimatedPerCableKg)
        assertTrue(latest!!.passedQualityGate)
    }

    @Test
    fun `latest passing is null when only failing rows exist`() = runTest {
        val db = createInMemoryTestDatabase()
        seedExercise(db, id = "ex2")
        val repo = SqlDelightVelocityOneRepMaxRepository(db)
        repo.insert(result(90f, passed = false), exerciseId = "ex2", computedAt = 1_000L, profileId = "default")
        assertNull(repo.getLatestPassing("ex2", "default"))
    }
}
