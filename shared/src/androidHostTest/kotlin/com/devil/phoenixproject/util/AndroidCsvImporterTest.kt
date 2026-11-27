package com.devil.phoenixproject.util

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidCsvImporterTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun importFromCsvStampsSecondaryProfileIdAndDeduplicatesReimports() = runTest {
        val repository = FakeWorkoutRepository()
        val csv = """
            Date,Time,Exercise,Mode,Target Reps,Warmup Reps,Working Reps,Total Reps,Weight,Progression,Duration (s),Just Lift,Eccentric Load
            2026-03-10,09:00:00,Bench Press,OldSchool,10,0,10,10,80.0,0,45,No,100
            2026-03-11,09:00:00,Squat,OldSchool,8,0,8,8,100.0,0,60,No,100
        """.trimIndent()
        val uri = "content://files/history-profile-2.csv"
        shadowOf(context.contentResolver).registerInputStream(Uri.parse(uri), csv.byteInputStream())

        val importer = AndroidCsvImporter(context, repository)
        val first = importer.importFromCsv(uri, profileId = "profile-2")

        assertEquals(CsvImportResult(imported = 2, skipped = 0, failed = 0), first)
        assertTrue(repository.getRecentSessionsSync("default", Int.MAX_VALUE).isEmpty())
        assertEquals(
            setOf("Bench Press", "Squat"),
            repository.getRecentSessionsSync("profile-2", Int.MAX_VALUE).mapNotNull { it.exerciseName }.toSet(),
        )

        shadowOf(context.contentResolver).registerInputStream(Uri.parse(uri), csv.byteInputStream())
        val second = importer.importFromCsv(uri, profileId = "profile-2")
        assertEquals(CsvImportResult(imported = 0, skipped = 2, failed = 0), second)
    }
}
