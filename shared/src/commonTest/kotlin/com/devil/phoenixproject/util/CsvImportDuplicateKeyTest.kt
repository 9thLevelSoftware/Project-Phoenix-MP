package com.devil.phoenixproject.util

import com.devil.phoenixproject.domain.model.WorkoutSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CsvImportDuplicateKeyTest {

    @Test
    fun `null exercise name uses the empty fallback on both sides`() {
        val existing = WorkoutSession(
            id = "existing",
            timestamp = 1_700_000_000_000L,
            exerciseId = "bench-press",
            exerciseName = null,
        )
        val incoming = WorkoutSession(
            id = "incoming",
            timestamp = 1_700_000_000_000L,
            exerciseId = null,
            exerciseName = null,
        )

        assertEquals(
            CsvImportDuplicateKey(timestamp = 1_700_000_000_000L, exerciseName = ""),
            csvImportDuplicateKey(existing),
        )
        assertEquals(csvImportDuplicateKey(existing), csvImportDuplicateKey(incoming))
    }

    @Test
    fun `a present name is the key even when an exercise id is set`() {
        val session = WorkoutSession(
            timestamp = 42L,
            exerciseId = "other-id",
            exerciseName = "Bench Press",
        )

        assertEquals(
            CsvImportDuplicateKey(timestamp = 42L, exerciseName = "Bench Press"),
            csvImportDuplicateKey(session),
        )
    }

    @Test
    fun `different timestamps stay distinct when the name is null`() {
        val first = csvImportDuplicateKey(WorkoutSession(timestamp = 1L, exerciseName = null))
        val second = csvImportDuplicateKey(WorkoutSession(timestamp = 2L, exerciseName = null))

        assertNotEquals(first, second)
    }
}
