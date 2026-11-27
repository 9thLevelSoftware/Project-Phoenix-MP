@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.util

import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID

class IosCsvImporterTest {

    @Test
    fun importFromCsvStampsProfile2LeavesDefaultEmptyAndDeduplicatesReimports() = runTest {
        val repository = FakeWorkoutRepository()
        val csv = """
            Date,Time,Exercise,Mode,Target Reps,Warmup Reps,Working Reps,Total Reps,Weight,Progression,Duration (s),Just Lift,Eccentric Load
            2026-03-10,09:00:00,Bench Press,OldSchool,10,0,10,10,80.0,0,45,No,100
            2026-03-11,09:00:00,Squat,OldSchool,8,0,8,8,100.0,0,60,No,100
        """.trimIndent()
        val fileName = "ios_csv_importer_test_${NSUUID().UUIDString}.csv"
        val filePath = assertNotNull(writeUtf8TempFile(fileName, csv))
        try {
            val importer = IosCsvImporter(repository)
            val first = importer.importFromCsv(filePath, profileId = "profile-2")

            assertEquals(CsvImportResult(imported = 2, skipped = 0, failed = 0), first)
            assertTrue(repository.getRecentSessionsSync("default", Int.MAX_VALUE).isEmpty())
            val profile2Sessions = repository.getRecentSessionsSync("profile-2", Int.MAX_VALUE)
            assertEquals(2, profile2Sessions.size)
            assertTrue(profile2Sessions.all { it.profileId == "profile-2" })

            val second = importer.importFromCsv(filePath, profileId = "profile-2")
            assertEquals(CsvImportResult(imported = 0, skipped = 2, failed = 0), second)
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(filePath, null)
        }
    }

    @Test
    fun importFromCsvPropagatesCancellationException() = runTest {
        val repository = FakeWorkoutRepository().apply {
            beforeSaveSession = { session ->
                if (session.exerciseName == "Squat") {
                    throw CancellationException("import cancelled")
                }
            }
        }
        val csv = """
            Date,Time,Exercise,Mode,Target Reps,Warmup Reps,Working Reps,Total Reps,Weight,Progression,Duration (s),Just Lift,Eccentric Load
            2026-03-10,09:00:00,Bench Press,OldSchool,10,0,10,10,80.0,0,45,No,100
            2026-03-11,09:00:00,Squat,OldSchool,8,0,8,8,100.0,0,60,No,100
            2026-03-12,09:00:00,Row,OldSchool,8,0,8,8,40.0,0,30,No,100
        """.trimIndent()
        val fileName = "ios_csv_cancel_test_${NSUUID().UUIDString}.csv"
        val filePath = assertNotNull(writeUtf8TempFile(fileName, csv))
        try {
            val importer = IosCsvImporter(repository)
            val thrown = assertFailsWith<CancellationException> {
                importer.importFromCsv(filePath, profileId = "profile-2")
            }
            assertEquals("import cancelled", thrown.message)
            assertEquals(listOf("Bench Press"), repository.allSessions().map { it.exerciseName })
            assertEquals(listOf("profile-2"), repository.allSessions().map { it.profileId })
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(filePath, null)
        }
    }
}
