package com.devil.phoenixproject.presentation.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.devil.phoenixproject.data.repository.SmartSuggestionsRepository
import com.devil.phoenixproject.data.repository.UserProfileRepository
import com.devil.phoenixproject.domain.model.SessionSummary
import com.devil.phoenixproject.domain.model.WeightUnit
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
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
class SmartInsightsTabCancellationTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var profileRepo: FakeUserProfileRepository
    private lateinit var firstProfileGate: CompletableDeferred<Unit>
    private lateinit var secondProfileReady: CompletableDeferred<Unit>
    private val callCount = AtomicInteger(0)

    @Before
    fun setUp() {
        profileRepo = FakeUserProfileRepository().apply {
            seedReadyProfileForTest("profile-1", name = "First")
            seedReadyProfileForTest("profile-2", name = "Second")
            emitReadyForTest("profile-1")
        }
        firstProfileGate = CompletableDeferred()
        secondProfileReady = CompletableDeferred()
        callCount.set(0)

        val repo = object : SmartSuggestionsRepository {
            override suspend fun getSessionSummariesSince(
                sinceTimestamp: Long,
                profileId: String,
            ): List<SessionSummary> {
                val n = callCount.incrementAndGet()
                if (profileId == "profile-1" && n == 1) {
                    firstProfileGate.await()
                }
                if (profileId == "profile-2") {
                    secondProfileReady.complete(Unit)
                }
                return emptyList()
            }

            override suspend fun getExerciseLastPerformed(profileId: String): List<SessionSummary> = emptyList()
            override suspend fun getExerciseWeightHistory(profileId: String): List<SessionSummary> = emptyList()
        }

        stopKoin()
        startKoin {
            modules(
                module {
                    single<SmartSuggestionsRepository> { repo }
                    single<UserProfileRepository> { profileRepo }
                },
            )
        }
    }

    @After
    fun tearDown() {
        firstProfileGate.cancel()
        stopKoin()
    }

    @Test
    fun switchingProfileWhileFetching_cancelsWithoutShowingErrorState() {
        rule.setContent {
            MaterialTheme {
                SmartInsightsTab(weightUnit = WeightUnit.KG)
            }
        }
        rule.waitUntil(timeoutMillis = 5_000) { callCount.get() >= 1 }

        // Switch active profile while profile-1's fetch is suspended in Dispatchers.IO.
        // LaunchedEffect(profileId, retryKey) cancels the old coroutine; CancellationException
        // must propagate instead of setting hasError = true.
        runBlocking {
            profileRepo.setActiveProfile("profile-2")
        }
        rule.waitUntil(timeoutMillis = 5_000) { secondProfileReady.isCompleted }
        rule.waitForIdle()

        rule.onNodeWithText("Unable to Load Insights").assertDoesNotExist()
    }
}
