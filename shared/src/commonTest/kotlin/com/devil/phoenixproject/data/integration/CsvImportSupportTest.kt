package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import com.devil.phoenixproject.util.CsvImportResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class CsvImportSupportTest {

    @Test
    fun importStampsActiveProfileIdLeavesDefaultEmptyAndDeduplicatesReimports() = runTest {
        val repository = FakeWorkoutRepository()
        val csv = """
            Date,Time,Exercise,Mode,Target Reps,Warmup Reps,Working Reps,Total Reps,Weight,Progression,Duration (s),Just Lift,Eccentric Load
            2026-03-10,09:00:00,Bench Press,OldSchool,10,0,10,10,80.0,0,45,No,100
            2026-03-10,09:00:00,Bench Press,OldSchool,10,0,10,10,80.0,0,45,No,100
            2026-03-11,09:00:00,Squat,OldSchool,8,0,8,8,100.0,0,60,No,100
        """.trimIndent()

        val first = importWorkoutHistoryCsv(
            uri = "/tmp/history.csv",
            profileId = "profile-2",
            workoutRepository = repository,
            readCsvContent = { csv },
        )

        assertEquals(CsvImportResult(imported = 2, skipped = 1, failed = 0), first)
        assertTrue(repository.allSessions().all { it.profileId == "profile-2" })
        assertTrue(repository.getRecentSessionsSync(profileId = "default", limit = Int.MAX_VALUE).isEmpty())
        assertEquals(2, repository.getRecentSessionsSync(profileId = "profile-2", limit = Int.MAX_VALUE).size)

        val second = importWorkoutHistoryCsv(
            uri = "/tmp/history.csv",
            profileId = "profile-2",
            workoutRepository = repository,
            readCsvContent = { csv },
        )
        assertEquals(CsvImportResult(imported = 0, skipped = 3, failed = 0), second)

        val otherProfile = importWorkoutHistoryCsv(
            uri = "/tmp/history.csv",
            profileId = "profile-3",
            workoutRepository = repository,
            readCsvContent = { csv },
        )
        assertEquals(CsvImportResult(imported = 2, skipped = 1, failed = 0), otherProfile)
        assertEquals(2, repository.getRecentSessionsSync(profileId = "profile-3", limit = Int.MAX_VALUE).size)
    }

    @Test
    fun importPropagatesCancellationFromReadAndSaveWithoutContinuing() = runTest {
        val repository = FakeWorkoutRepository()
        val readCancel = assertFailsWith<CancellationException> {
            importWorkoutHistoryCsv(
                uri = "/tmp/history.csv",
                profileId = "profile-2",
                workoutRepository = repository,
                readCsvContent = { throw CancellationException("read cancelled") },
            )
        }
        assertEquals("read cancelled", readCancel.message)
        assertTrue(repository.allSessions().isEmpty())

        repository.beforeSaveSession = { session ->
            if (session.exerciseName == "Squat") throw CancellationException("save cancelled")
        }
        val csv = """
            Date,Time,Exercise,Mode,Target Reps,Warmup Reps,Working Reps,Total Reps,Weight,Progression,Duration (s),Just Lift,Eccentric Load
            2026-03-10,09:00:00,Bench Press,OldSchool,10,0,10,10,80.0,0,45,No,100
            2026-03-11,09:00:00,Squat,OldSchool,8,0,8,8,100.0,0,60,No,100
            2026-03-12,09:00:00,Row,OldSchool,8,0,8,8,40.0,0,30,No,100
        """.trimIndent()

        val saveCancel = assertFailsWith<CancellationException> {
            importWorkoutHistoryCsv(
                uri = "/tmp/history.csv",
                profileId = "profile-2",
                workoutRepository = repository,
                readCsvContent = { csv },
            )
        }
        assertEquals("save cancelled", saveCancel.message)
        assertEquals(listOf("Bench Press"), repository.allSessions().map { it.exerciseName })
    }
}
