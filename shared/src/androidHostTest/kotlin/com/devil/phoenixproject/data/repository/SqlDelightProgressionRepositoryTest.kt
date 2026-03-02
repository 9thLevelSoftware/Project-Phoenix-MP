package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.VitruvianDatabase
import com.devil.phoenixproject.domain.model.ProgressionEvent
import com.devil.phoenixproject.domain.model.ProgressionReason
import com.devil.phoenixproject.domain.model.ProgressionResponse
import com.devil.phoenixproject.testutil.createTestDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqlDelightProgressionRepositoryTest {

    private lateinit var database: VitruvianDatabase
    private lateinit var repository: SqlDelightProgressionRepository

    @Before
    fun setup() {
        database = createTestDatabase()
        repository = SqlDelightProgressionRepository(database)
    }

    @Test
    fun `createProgressionSuggestion stores pending event`() = runTest {
        val event = ProgressionEvent.create(
            exerciseId = "bench",
            previousWeightKg = 50f,
            reason = ProgressionReason.REPS_ACHIEVED
        )

        repository.createProgressionSuggestion(event)

        assertTrue(repository.hasPendingProgression("bench"))
        val latest = repository.getLatestProgressionEvent("bench")
        assertEquals(event.id, latest?.id)
    }

    @Test
    fun `recordResponse clears pending status`() = runTest {
        val event = ProgressionEvent.create(
            exerciseId = "bench",
            previousWeightKg = 50f,
            reason = ProgressionReason.LOW_RPE
        )
        repository.createProgressionSuggestion(event)

        repository.recordResponse(event.id, ProgressionResponse.ACCEPTED, actualWeight = 52.5f)

        assertFalse(repository.hasPendingProgression("bench"))
        val updated = repository.getLatestProgressionEvent("bench")
        assertEquals(ProgressionResponse.ACCEPTED, updated?.userResponse)
    }
}
