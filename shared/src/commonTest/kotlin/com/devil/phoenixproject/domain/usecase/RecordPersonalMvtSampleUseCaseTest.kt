package com.devil.phoenixproject.domain.usecase

import com.devil.phoenixproject.data.repository.PersonalMvtEntity
import com.devil.phoenixproject.data.repository.PersonalMvtRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakePersonalMvtRepo : PersonalMvtRepository {
    val store = mutableMapOf<String, PersonalMvtEntity>()
    override suspend fun get(exerciseId: String, profileId: String) = store["$exerciseId/$profileId"]
    override suspend fun upsert(exerciseId: String, profileId: String, personalMvtMs: Float, sampleCount: Int) {
        store["$exerciseId/$profileId"] = PersonalMvtEntity(exerciseId, profileId, personalMvtMs, sampleCount)
    }
}

class RecordPersonalMvtSampleUseCaseTest {
    @Test fun `captures sample when velocity collapsed near squat threshold`() = runTest {
        val repo = FakePersonalMvtRepo()
        val useCase = RecordPersonalMvtSampleUseCase(repo)
        // squat default 0.30 m/s; 0.31 m/s (310 mm/s) <= 1.1*0.30 -> capture
        val captured = useCase("ex1", "default", "Back Squat", "Legs", sessionMcvMmS = 310f)
        assertTrue(captured)
        assertEquals(1, repo.get("ex1", "default")?.sampleCount)
        assertEquals(0.31f, repo.get("ex1", "default")?.personalMvtMs!!, absoluteTolerance = 0.001f)
    }

    @Test fun `ignores fast non-failure set`() = runTest {
        val repo = FakePersonalMvtRepo()
        val useCase = RecordPersonalMvtSampleUseCase(repo)
        // 0.60 m/s is well above threshold -> not a failure proxy
        assertFalse(useCase("ex1", "default", "Back Squat", "Legs", sessionMcvMmS = 600f))
        assertEquals(null, repo.get("ex1", "default"))
    }

    @Test fun `rolling mean across samples`() = runTest {
        val repo = FakePersonalMvtRepo()
        val useCase = RecordPersonalMvtSampleUseCase(repo)
        useCase("ex1", "default", "Back Squat", "Legs", 300f) // 0.30
        useCase("ex1", "default", "Back Squat", "Legs", 320f) // 0.32
        val e = repo.get("ex1", "default")!!
        assertEquals(2, e.sampleCount)
        assertEquals(0.31f, e.personalMvtMs, absoluteTolerance = 0.001f)
    }
}
