package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.onerepmax.VelocityOneRepMaxResult
import com.devil.phoenixproject.testutil.createTestDatabase
import com.devil.phoenixproject.testutil.seedExercise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SqlDelightVelocityOneRepMaxRepositoryTest {

    private fun createInMemoryTestDatabase(): PhoenixDatabase = createTestDatabase()

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
        db.seedExercise("ex1", equipment = "BAR")
        val repo = SqlDelightVelocityOneRepMaxRepository(db)

        repo.insert(result(100f, passed = true), exerciseId = "ex1", computedAt = 1_000L, profileId = "default")
        repo.insert(result(120f, passed = false), exerciseId = "ex1", computedAt = 2_000L, profileId = "default")
        repo.insert(result(110f, passed = true), exerciseId = "ex1", computedAt = 3_000L, profileId = "default")

        val latest = repo.getLatestPassing("ex1", "default")
        assertEquals(110f, latest?.estimatedPerCableKg)
        assertTrue(latest!!.passedQualityGate)
    }

    @Test
    fun `latest passing skips a newer failing row`() = runTest {
        val db = createInMemoryTestDatabase()
        db.seedExercise("ex3", equipment = "BAR")
        val repo = SqlDelightVelocityOneRepMaxRepository(db)

        repo.insert(result(100f, passed = true), exerciseId = "ex3", computedAt = 1_000L, profileId = "default")
        repo.insert(result(120f, passed = false), exerciseId = "ex3", computedAt = 2_000L, profileId = "default")

        val latest = repo.getLatestPassing("ex3", "default")
        assertEquals(100f, latest?.estimatedPerCableKg)
        assertTrue(latest!!.passedQualityGate)
    }

    @Test
    fun `latest passing is null when only failing rows exist`() = runTest {
        val db = createInMemoryTestDatabase()
        db.seedExercise("ex2", equipment = "BAR")
        val repo = SqlDelightVelocityOneRepMaxRepository(db)
        repo.insert(result(90f, passed = false), exerciseId = "ex2", computedAt = 1_000L, profileId = "default")
        assertNull(repo.getLatestPassing("ex2", "default"))
    }

    @Test
    fun `getAllPassing returns only passing rows for the profile ordered by exercise then time`() = runTest {
        val db = createInMemoryTestDatabase()
        db.seedExercise("exA", equipment = "BAR"); db.seedExercise("exB", equipment = "BAR")
        val repo = SqlDelightVelocityOneRepMaxRepository(db)
        repo.insert(result(100f, passed = true), "exA", computedAt = 1L, profileId = "default")
        repo.insert(result(110f, passed = true), "exA", computedAt = 2L, profileId = "default")
        repo.insert(result(90f, passed = false), "exA", computedAt = 3L, profileId = "default") // excluded
        repo.insert(result(80f, passed = true), "exB", computedAt = 1L, profileId = "default")
        repo.insert(result(200f, passed = true), "exA", computedAt = 1L, profileId = "other") // other profile

        val all = repo.getAllPassing("default")
        assertEquals(3, all.size)
        assertEquals(listOf("exA", "exA", "exB"), all.map { it.exerciseId })
        assertEquals(listOf(100f, 110f, 80f), all.map { it.estimatedPerCableKg })
    }

    // Issue #517 Phase 5 T1
    @Test
    fun `hasEstimates reflects presence of rows`() = runTest {
        val db = createInMemoryTestDatabase()
        db.seedExercise("ex1", equipment = "BAR")
        val repo = SqlDelightVelocityOneRepMaxRepository(db)
        assertFalse(repo.hasEstimates("ex1", "default"))
        repo.insert(result(100f, passed = true), "ex1", computedAt = 1L, profileId = "default")
        assertTrue(repo.hasEstimates("ex1", "default"))
    }
}
