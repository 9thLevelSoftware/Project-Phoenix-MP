package com.devil.phoenixproject.presentation.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.devil.phoenixproject.data.repository.CompletedSetRepository
import com.devil.phoenixproject.data.repository.ProfileExerciseBaseline
import com.devil.phoenixproject.data.repository.ProfileExerciseBaselineRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.data.repository.VelocityOneRepMaxRepository
import com.devil.phoenixproject.domain.model.Exercise
import com.devil.phoenixproject.domain.model.ProgramMode
import com.devil.phoenixproject.domain.model.RoutineExercise
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.testutil.FakeCompletedSetRepository
import com.devil.phoenixproject.testutil.FakeExerciseRepository
import com.devil.phoenixproject.testutil.FakePersonalRecordRepository
import com.devil.phoenixproject.testutil.FakeProfileExerciseBaselineRepository
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeVelocityOneRepMaxRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w402dp-h874dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExerciseConfigViewModelLifecycleTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var lookupStarted: CompletableDeferred<Unit>
    private lateinit var lookupGate: CompletableDeferred<Unit>
    private val lookupCancelled = AtomicBoolean(false)

    @Before
    fun setUp() {
        lookupStarted = CompletableDeferred()
        lookupGate = CompletableDeferred()
        lookupCancelled.set(false)

        val fakeBaselineRepo = FakeProfileExerciseBaselineRepository()
        val suspendingBaselineRepo = object : ProfileExerciseBaselineRepository by fakeBaselineRepo {
            override suspend fun get(
                profileId: String,
                exerciseId: String,
            ): ProfileExerciseBaseline? {
                lookupStarted.complete(Unit)
                try {
                    lookupGate.await()
                } catch (e: CancellationException) {
                    lookupCancelled.set(true)
                    throw e
                }
                return null
            }
        }
        val userProfileRepo = FakeUserProfileRepository().apply {
            setActiveProfileForTest(id = "default")
        }

        stopKoin()
        startKoin {
            modules(
                module {
                    single<VelocityOneRepMaxRepository> { FakeVelocityOneRepMaxRepository() }
                    single<ProfileExerciseBaselineRepository> { suspendingBaselineRepo }
                    single<CompletedSetRepository> { FakeCompletedSetRepository() }
                    single<UserProfileRepository> { userProfileRepo }
                },
            )
        }
    }

    @After
    fun tearDown() {
        lookupGate.cancel()
        stopKoin()
    }

    @Test
    fun dismissingExerciseEditBottomSheet_cancelsPendingBaselineLookupJob() {
        val exercise = RoutineExercise(
            id = "rex-lifecycle-1",
            exercise = Exercise(
                id = "bench",
                name = "Bench Press",
                muscleGroup = "Chest",
                muscleGroups = "Chest",
                equipment = "BAR",
            ),
            orderIndex = 0,
            programMode = ProgramMode.OldSchool,
            weightPerCableKg = 20f,
            usePercentOfPR = true,
            weightPercentOfPR = 80,
        )
        var showSheet by mutableStateOf(true)

        rule.setContent {
            MaterialTheme {
                if (showSheet) {
                    ExerciseEditBottomSheet(
                        exercise = exercise,
                        weightUnit = WeightUnit.KG,
                        enableVideoPlayback = false,
                        kgToDisplay = { kg, _ -> kg },
                        displayToKg = { v, _ -> v },
                        exerciseRepository = FakeExerciseRepository(),
                        personalRecordRepository = FakePersonalRecordRepository(),
                        formatWeight = { kg, _ -> "$kg kg" },
                        onSave = {},
                        onDismiss = { showSheet = false },
                    )
                }
            }
        }

        rule.waitUntil(timeoutMillis = 5_000) { lookupStarted.isCompleted }

        // Remove ExerciseEditBottomSheet from composition -> DisposableEffect calls viewModel.clear()
        showSheet = false
        rule.waitForIdle()

        rule.waitUntil(timeoutMillis = 5_000) { lookupCancelled.get() }
        assertTrue(lookupCancelled.get(), "Expected pending baseline lookup job to cancel on dispose")
    }
}
