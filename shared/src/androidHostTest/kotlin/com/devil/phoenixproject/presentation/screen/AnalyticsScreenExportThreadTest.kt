package com.devil.phoenixproject.presentation.screen

import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.devil.phoenixproject.data.repository.PersonalRecordRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.PRType
import com.devil.phoenixproject.domain.model.PersonalRecord
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.domain.model.WorkoutSession
import com.devil.phoenixproject.util.CsvExporter
import com.devil.phoenixproject.util.CsvImportResult
import com.devil.phoenixproject.util.CsvImporter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnalyticsScreenExportThreadTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var fixture: RoutineCompleteRuntimeFixture
    private lateinit var recordingExporter: RecordingCsvExporter

    private class RecordingCsvExporter : CsvExporter {
        val exportThreadsMain = mutableListOf<Boolean>()
        val shareThreadsMain = mutableListOf<Boolean>()
        val sharedFiles = mutableListOf<Pair<String, String>>()

        override fun exportPersonalRecords(
            personalRecords: List<PersonalRecord>,
            exerciseNames: Map<String, String>,
            weightUnit: WeightUnit,
            formatWeight: (Float, WeightUnit) -> String,
        ): Result<String> {
            exportThreadsMain.add(Looper.getMainLooper().isCurrentThread)
            return Result.success("/tmp/personal_records.csv")
        }

        override fun exportWorkoutHistory(
            workoutSessions: List<WorkoutSession>,
            exerciseNames: Map<String, String>,
            weightUnit: WeightUnit,
            formatWeight: (Float, WeightUnit) -> String,
        ): Result<String> {
            exportThreadsMain.add(Looper.getMainLooper().isCurrentThread)
            return Result.success("/tmp/workout_history.csv")
        }

        override fun exportPRProgression(
            personalRecords: List<PersonalRecord>,
            exerciseNames: Map<String, String>,
            weightUnit: WeightUnit,
            formatWeight: (Float, WeightUnit) -> String,
        ): Result<String> {
            exportThreadsMain.add(Looper.getMainLooper().isCurrentThread)
            return Result.success("/tmp/pr_progression.csv")
        }

        override fun shareCSV(fileUri: String, fileName: String) {
            shareThreadsMain.add(Looper.getMainLooper().isCurrentThread)
            sharedFiles.add(fileUri to fileName)
        }
    }

    @Before
    fun setUp() {
        fixture = RoutineCompleteRuntimeFixture()
        recordingExporter = RecordingCsvExporter()
        fixture.fakePersonalRecordRepository.addRecord(
            PersonalRecord(
                id = 1L,
                exerciseId = "bench",
                exerciseName = "Bench Press",
                weightPerCableKg = 40f,
                reps = 5,
                oneRepMax = 45f,
                timestamp = 1_700_000_000_000L,
                workoutMode = "Old School",
                prType = PRType.MAX_WEIGHT,
                volume = 200f,
                profileId = "default",
            ),
        )
        val noOpImporter = object : CsvImporter {
            override suspend fun importFromCsv(uri: String, profileId: String): CsvImportResult =
                CsvImportResult(imported = 0, skipped = 0, failed = 0)
        }
        stopKoin()
        startKoin {
            modules(
                module {
                    single<CsvExporter> { recordingExporter }
                    single<CsvImporter> { noOpImporter }
                    single<UserProfileRepository> { fixture.fakeUserProfileRepository }
                    single<PersonalRecordRepository> { fixture.fakePersonalRecordRepository }
                },
            )
        }
    }

    @After
    fun tearDown() {
        fixture.close()
        stopKoin()
    }

    @Test
    fun exportPersonalRecords_runsExportOffMainAndShareCsvOnMain() {
        rule.setContent {
            MaterialTheme {
                AnalyticsScreen(
                    viewModel = fixture.viewModel,
                    assessmentProfileId = null,
                    onNavigateToStrengthAssessment = {},
                    onNavigateToExerciseDetail = {},
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithContentDescription("Export data").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Personal Records (1)").performClick()

        rule.waitUntil(timeoutMillis = 5_000) {
            ShadowLooper.idleMainLooper()
            recordingExporter.sharedFiles.size == 1
        }
        rule.waitForIdle()

        assertEquals(1, recordingExporter.exportThreadsMain.size)
        assertTrue(
            recordingExporter.exportThreadsMain.all { !it },
            "CSV export work must execute off the Main thread, got=${recordingExporter.exportThreadsMain}",
        )
        assertEquals(1, recordingExporter.shareThreadsMain.size)
        assertFalse(
            recordingExporter.shareThreadsMain.any { !it },
            "shareCSV must execute on the Main thread, got=${recordingExporter.shareThreadsMain}",
        )
        assertEquals(
            listOf("/tmp/personal_records.csv" to "personal_records.csv"),
            recordingExporter.sharedFiles,
        )
    }

    @Test
    fun exportPrProgression_runsExportOffMainAndShareCsvOnMain() {
        rule.setContent {
            MaterialTheme {
                AnalyticsScreen(
                    viewModel = fixture.viewModel,
                    assessmentProfileId = null,
                    onNavigateToStrengthAssessment = {},
                    onNavigateToExerciseDetail = {},
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithContentDescription("Export data").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("PR Progression").performClick()

        rule.waitUntil(timeoutMillis = 5_000) {
            ShadowLooper.idleMainLooper()
            recordingExporter.sharedFiles.size == 1
        }
        rule.waitForIdle()

        assertEquals(1, recordingExporter.exportThreadsMain.size)
        assertTrue(
            recordingExporter.exportThreadsMain.all { !it },
            "CSV export work must execute off the Main thread, got=${recordingExporter.exportThreadsMain}",
        )
        assertEquals(1, recordingExporter.shareThreadsMain.size)
        assertFalse(
            recordingExporter.shareThreadsMain.any { !it },
            "shareCSV must execute on the Main thread, got=${recordingExporter.shareThreadsMain}",
        )
        assertEquals(
            listOf("/tmp/pr_progression.csv" to "pr_progression.csv"),
            recordingExporter.sharedFiles,
        )
    }
}
