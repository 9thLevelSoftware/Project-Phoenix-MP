package com.devil.phoenixproject.util

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import java.io.IOException
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [CancellationException] extends [Exception], so [AndroidCsvImporter] must rethrow it
 * and stop writing later rows. A real read or save failure still comes back as
 * [CsvImportResult].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidCsvImporterCancellationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun importFromCsv_rethrowsCancellationAndDoesNotWriteLaterRows() = runTest {
        val cancellation = CancellationException("import scope cancelled")
        val repository = FakeWorkoutRepository().apply {
            beforeSaveSession = { session ->
                if (session.exerciseName == "Squat") throw cancellation
            }
        }
        register(HISTORY_URI, historyCsv())

        val thrown = assertFailsWith<CancellationException> {
            importer(repository).importFromCsv(HISTORY_URI, PROFILE_ID)
        }

        assertEquals(cancellation.message, thrown.message)
        assertEquals(listOf("Bench Press"), savedNames(repository))
        assertEquals(listOf("Bench Press", "Squat"), attemptedNames(repository))
        assertEquals(PROFILE_ID, repository.allSessions().single().profileId)
    }

    @Test
    fun importFromCsv_rethrowsCancellationFromTheFileRead() = runTest {
        val cancellation = CancellationException("left screen")
        val repository = FakeWorkoutRepository()
        register(HISTORY_URI, ThrowingStream(cancellation))

        val thrown = assertFailsWith<CancellationException> {
            importer(repository).importFromCsv(HISTORY_URI, PROFILE_ID)
        }

        assertEquals(cancellation.message, thrown.message)
        assertTrue(repository.saveSessionAttempts.isEmpty())
    }

    @Test
    fun importFromCsv_reportsSaveFailureAndContinues() = runTest {
        val repository = FakeWorkoutRepository().apply {
            beforeSaveSession = { session ->
                if (session.exerciseName == "Squat") throw IOException("disk full")
            }
        }
        register(HISTORY_URI, historyCsv())

        val result = importer(repository).importFromCsv(HISTORY_URI, PROFILE_ID)

        assertEquals(2, result.imported)
        assertEquals(0, result.skipped)
        assertEquals(1, result.failed)
        assertEquals(listOf("Failed to save session (Squat): disk full"), result.errors)
        assertEquals(listOf("Bench Press", "Row"), savedNames(repository))
    }

    @Test
    fun importFromCsv_reportsUnreadableFile() = runTest {
        val repository = FakeWorkoutRepository()
        register(HISTORY_URI, ThrowingStream(IOException("denied")))

        val result = importer(repository).importFromCsv(HISTORY_URI, PROFILE_ID)

        assertEquals(
            CsvImportResult(imported = 0, skipped = 0, failed = 0, errors = listOf("Import failed: denied")),
            result,
        )
        assertTrue(repository.saveSessionAttempts.isEmpty())
    }

    private fun importer(repository: FakeWorkoutRepository) = AndroidCsvImporter(context, repository)

    private fun register(uri: String, stream: InputStream) {
        shadowOf(context.contentResolver).registerInputStream(Uri.parse(uri), stream)
    }

    private fun savedNames(repository: FakeWorkoutRepository): List<String> =
        repository.allSessions().map { it.exerciseName.orEmpty() }

    private fun attemptedNames(repository: FakeWorkoutRepository): List<String> =
        repository.saveSessionAttempts.map { it.exerciseName.orEmpty() }

    private fun historyCsv(): InputStream = """
        Date,Time,Exercise,Mode,Target Reps,Warmup Reps,Working Reps,Total Reps,Weight,Progression,Duration (s),Just Lift,Eccentric Load
        2026-03-10,09:00:00,Bench Press,OldSchool,10,0,10,10,80.0,0,45,No,100
        2026-03-11,09:00:00,Squat,OldSchool,8,0,8,8,100.0,0,60,No,100
        2026-03-12,09:00:00,Row,OldSchool,8,0,8,8,40.0,0,30,No,100
    """.trimIndent().byteInputStream()

    private class ThrowingStream(private val error: Exception) : InputStream() {
        override fun read(): Int = throw error

        override fun read(b: ByteArray, off: Int, len: Int): Int = throw error
    }

    private companion object {
        const val HISTORY_URI = "content://files/history.csv"
        const val PROFILE_ID = "profile-a"
    }
}
