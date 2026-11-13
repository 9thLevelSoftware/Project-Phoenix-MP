package com.devil.phoenixproject.presentation.screen

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import com.devil.phoenixproject.data.integration.intervals.IntervalsConnectionStatus
import com.devil.phoenixproject.data.integration.intervals.IntervalsExportStatus
import com.devil.phoenixproject.data.integration.intervals.IntervalsJobCounts
import com.devil.phoenixproject.data.integration.intervals.IntervalsJobState
import com.devil.phoenixproject.data.integration.intervals.IntervalsReasonCode
import com.devil.phoenixproject.data.integration.intervals.IntervalsValidationResult
import com.devil.phoenixproject.presentation.viewmodel.IntervalsCheckUploadResult
import com.devil.phoenixproject.presentation.viewmodel.IntervalsCheckState
import com.devil.phoenixproject.presentation.viewmodel.IntervalsPhase
import com.devil.phoenixproject.presentation.viewmodel.IntervalsSettingsViewModel
import com.devil.phoenixproject.presentation.viewmodel.IntervalsStatusSource
import com.devil.phoenixproject.testutil.TestCoroutineRule
import com.devil.phoenixproject.ui.theme.PhoenixTheme
import com.devil.phoenixproject.ui.theme.ThemeMode
import com.devil.phoenixproject.util.KmpUtils
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Real Compose semantics coverage for the Intervals.icu Settings surface (issue #1384, P5).
 *
 * Every state renders from a real [IntervalsSettingsViewModel] state machine driven by a
 * stateful fake of the [IntervalsStatusSource] seam — no mock controls in the product UI.
 * Also guards the packet boundaries: Intervals presentation code stays in phoneMain,
 * commonMain edits stay navigation + strings only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IntervalsSettingsUiTest {

    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val coroutineRule = TestCoroutineRule(UnconfinedTestDispatcher())

    // ── Fake seam (stateful: mutations reflect into status like the real store) ──

    private class FakeIntervalsStatusSource : IntervalsStatusSource {
        val statusFlow = MutableStateFlow(
            IntervalsExportStatus(connection = null, jobCounts = emptyList(), lastSentAt = null),
        )
        val secureStoreFlow = MutableStateFlow(true)
        val networkFlow = MutableStateFlow(true)
        val activeProfileFlow = MutableStateFlow<String?>(null)
        val failureReasonFlow = MutableStateFlow<IntervalsReasonCode?>(null)

        val savedCredentials = mutableListOf<Pair<String, String>>()
        val testedCredentials = mutableListOf<Pair<String, String>>()
        var enableCalls = 0
        var pauseCalls = 0
        var resumeCalls = 0
        var disconnectCalls = 0
        var checkCalls = 0

        var saveResult: IntervalsValidationResult = IntervalsValidationResult.Valid("i123456", null)
        var testResult: IntervalsValidationResult = IntervalsValidationResult.Valid("i123456", null)
        var checkResult: IntervalsCheckUploadResult = IntervalsCheckUploadResult.Checked

        /** When set, testConnection suspends here before returning [testResult]. */
        var testGate: CompletableDeferred<Unit>? = null

        override fun status(): Flow<IntervalsExportStatus> = statusFlow

        override fun secureStoreAvailable(): StateFlow<Boolean> = secureStoreFlow

        override fun networkAvailable(): StateFlow<Boolean> = networkFlow

        override fun activeProfileId(): StateFlow<String?> = activeProfileFlow

        override fun permanentFailureReason(): StateFlow<IntervalsReasonCode?> = failureReasonFlow

        override suspend fun saveCredentials(athleteId: String, apiKey: String): IntervalsValidationResult {
            savedCredentials += athleteId to apiKey
            return saveResult
        }

        override suspend fun testConnection(athleteId: String, apiKey: String): IntervalsValidationResult {
            testedCredentials += athleteId to apiKey
            testGate?.await()
            return testResult
        }

        override suspend fun enable(): Boolean {
            enableCalls++
            val connection = statusFlow.value.connection ?: connection(enabled = true)
            statusFlow.value = statusFlow.value.copy(connection = connection.copy(enabled = true, paused = false))
            return true
        }

        override suspend fun pause() {
            pauseCalls++
            statusFlow.value.connection?.let {
                statusFlow.value = statusFlow.value.copy(connection = it.copy(paused = true))
            }
        }

        override suspend fun resume() {
            resumeCalls++
            statusFlow.value.connection?.let {
                statusFlow.value = statusFlow.value.copy(connection = it.copy(paused = false))
            }
        }

        override suspend fun disconnect() {
            disconnectCalls++
            statusFlow.value = IntervalsExportStatus(connection = null, jobCounts = emptyList(), lastSentAt = null)
        }

        override suspend fun checkUpload(): IntervalsCheckUploadResult {
            checkCalls++
            return checkResult
        }

        fun connection(
            profileId: String = "profile-1",
            athleteId: String = "i123456",
            enabled: Boolean = true,
            paused: Boolean = false,
            authRequired: Boolean = false,
        ) = IntervalsConnectionStatus(
            profileId = profileId,
            resolvedAthleteId = athleteId,
            connectionGeneration = 1L,
            dispatchAuthGeneration = 1L,
            installationId = "install-1",
            enabled = enabled,
            paused = paused,
            authRequired = authRequired,
            validatedAt = 1_000L,
            enabledAt = if (enabled) 2_000L else null,
            pausedAt = if (paused) 3_000L else null,
            consentVersion = 1,
            disclosureVersion = 1,
            eligibilityEpoch = 1L,
            updatedAt = 4_000L,
        )

        /** Emits a profile/destination-scoped status — counters are never global. */
        fun emit(
            connection: IntervalsConnectionStatus?,
            counts: List<Pair<IntervalsJobState, Long>> = emptyList(),
            lastSentAt: Long? = null,
            failureReason: IntervalsReasonCode? = null,
        ) {
            statusFlow.value = IntervalsExportStatus(
                connection = connection,
                jobCounts = counts.map { (state, count) -> IntervalsJobCounts(state, count, lastSentAt) },
                lastSentAt = lastSentAt,
            )
            connection?.let { activeProfileFlow.value = it.profileId }
            failureReasonFlow.value = failureReason
        }
    }

    private fun newViewModel(source: FakeIntervalsStatusSource) = IntervalsSettingsViewModel(source)

    /** One password dot per character of the typed key (PasswordVisualTransformation). */
    private val MASKED_KEY: String = "•".repeat("secret-key-123".length)

    private fun setContent(
        viewModel: IntervalsSettingsViewModel,
        dark: Boolean = false,
        fontScale: Float = 1f,
        onNavigateBack: () -> Unit = {},
    ) {
        rule.setContent {
            val current = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(current.density, fontScale)) {
                PhoenixTheme(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                    IntervalsSettingsScreen(
                        viewModel = viewModel,
                        onNavigateBack = onNavigateBack,
                    )
                }
            }
        }
    }

    // ── Off + disclosure + three-way distinction ──────────────────────────────

    @Test
    fun offStateShowsDisclosureThreeWayDistinctionAndDisabledEnableAction() {
        val source = FakeIntervalsStatusSource()
        setContent(newViewModel(source))

        rule.onNodeWithText("Off").assertIsDisplayed()
        rule.onNodeWithText("Sending finished workouts to Intervals.icu is off.").assertIsDisplayed()

        // Scrollable disclosure block covers every signed-off clause.
        rule.onNodeWithText(
            "• Each finished workout is sent as one strength-training activity with every set's reps and weight.",
        ).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(
            "• Your athlete ID and API key are stored only on this phone, in its secure storage.",
        ).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(
            "• Only workouts finished after you turn this on are sent. Existing history is never uploaded.",
        ).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(
            "• Edits and deletions made in Phoenix are not sent later. Each workout uploads once.",
        ).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(
            "• Phoenix sends workouts only to Intervals.icu — never on to other services.",
        ).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(
            "• Workouts send while Phoenix is open. Background sending is not guaranteed.",
        ).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(
            "• Because raw database files cannot be scrubbed of these credentials, support ZIP exports stay off in builds that include this integration. Your regular JSON training backups still work.",
        ).performScrollTo().assertIsDisplayed()

        // Clear three-way distinction: local saved vs export queued vs remote sent.
        rule.onNodeWithText("Saved, queued, sent").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Saved — the workout is stored on this phone.").assertIsDisplayed()
        rule.onNodeWithText("Queued — waiting to upload to Intervals.icu.").assertIsDisplayed()
        rule.onNodeWithText("Sent — confirmed on Intervals.icu.").assertIsDisplayed()

        // Consent is a separate explicit action; nothing is on by default.
        rule.onNodeWithText("Turn on sending").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
    }

    // ── Draft + validation ────────────────────────────────────────────────────

    @Test
    fun draftValidationSurfacesInvalidAthleteIdAndMissingKeyErrors() {
        val source = FakeIntervalsStatusSource()
        val viewModel = newViewModel(source)
        setContent(viewModel)

        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("bad id!!")
        rule.waitForIdle()
        rule.onNodeWithText("Not set up").assertIsDisplayed()

        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNodeWithText(
            "That athlete ID doesn't look right. Use letters, digits, dashes or underscores, like i123456.",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Enter your Intervals.icu API key.", substring = true).assertIsDisplayed()
        assertEquals(emptyList(), source.savedCredentials, "invalid input must never reach the store")
    }

    @Test
    fun apiKeyInputIsMaskedWithSemanticToggle() {
        val source = FakeIntervalsStatusSource()
        val viewModel = newViewModel(source)
        setContent(viewModel)

        rule.onAllNodes(hasSetTextAction())[1].performTextInput("secret-key-123")
        rule.waitForIdle()

        // Masked by default (PasswordVisualTransformation): the displayed editable text
        // is one password dot per character, never the plaintext key. Observed semantics
        // surface (asserted via the masked-text match below): EditableText — what
        // accessibility services announce — is the masked dots and the node carries the
        // [Password] flag; the raw value only exists in InputText, the IME/test edit
        // plumbing, which must stay raw for editing to work.
        rule.onNodeWithText(MASKED_KEY).assertIsDisplayed()

        rule.onNodeWithContentDescription("Show API key").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText(MASKED_KEY).assertDoesNotExist()
        rule.onNodeWithContentDescription("Hide API key").assertIsDisplayed()

        // The draft is ephemeral and lives outside the nonsecret UI state.
        assertEquals("secret-key-123", viewModel.apiKeyDraft.value)
        assertFalse(
            viewModel.uiState.value.toString().contains("secret-key-123"),
            "the API-key draft must never enter the saved/nonsecret UI state",
        )
    }

    // ── Draft clearing ────────────────────────────────────────────────────────

    @Test
    fun backNavigationClearsTheEphemeralDraft() {
        val source = FakeIntervalsStatusSource()
        val viewModel = newViewModel(source)
        var backCalls = 0
        setContent(viewModel, onNavigateBack = { backCalls++ })

        rule.onAllNodes(hasSetTextAction())[1].performTextInput("secret-key-123")
        rule.waitForIdle()

        rule.onNodeWithContentDescription("Go back").performClick()
        rule.waitForIdle()

        assertEquals(1, backCalls)
        assertEquals("", viewModel.apiKeyDraft.value, "draft must be cleared on navigation-away")
    }

    @Test
    fun profileTransitionClearsTheDraft() {
        val source = FakeIntervalsStatusSource()
        source.activeProfileFlow.value = "profile-1"
        val viewModel = newViewModel(source)
        setContent(viewModel)

        rule.onAllNodes(hasSetTextAction())[0].performTextInput("i123456")
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("secret-key-123")
        rule.waitForIdle()
        assertNotEquals("", viewModel.apiKeyDraft.value)

        // A different profile/destination drops the draft — even before any connection
        // exists, when the status carries no profile of its own.
        source.activeProfileFlow.value = "profile-2"
        rule.waitForIdle()

        assertEquals("", viewModel.apiKeyDraft.value, "draft must be cleared on profile transition")
        assertEquals("", viewModel.uiState.value.athleteIdDraft)
    }

    @Test
    fun saveClearsTheDraftAndStoresOnDeviceOnly() {
        val source = FakeIntervalsStatusSource()
        val viewModel = newViewModel(source)
        setContent(viewModel)

        rule.onAllNodes(hasSetTextAction())[0].performTextInput("i123456")
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("secret-key-123")
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals(listOf("i123456" to "secret-key-123"), source.savedCredentials)
        assertEquals("", viewModel.apiKeyDraft.value, "draft must be cleared after save")
        rule.onNodeWithText("Credentials saved on this phone.").performScrollTo().assertIsDisplayed()
    }

    // ── Testing / tested-but-still-disabled / enable ──────────────────────────

    @Test
    fun testingAndTestedStatesNeverEnableTheExport() {
        val source = FakeIntervalsStatusSource()
        val gate = CompletableDeferred<Unit>()
        source.testGate = gate
        val viewModel = newViewModel(source)
        setContent(viewModel)

        rule.onAllNodes(hasSetTextAction())[0].performTextInput("i123456")
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("secret-key-123")
        rule.onNodeWithText("Test connection").performScrollTo().performClick()
        rule.waitForIdle()

        // Testing state while the nonmutating validation call is in flight.
        assertEquals(IntervalsPhase.TESTING, viewModel.uiState.value.phase)
        rule.onNodeWithText("Testing connection").assertIsDisplayed()
        assertEquals(0, source.enableCalls, "testing must never enable")

        gate.complete(Unit)
        rule.waitForIdle()

        // Tested — but the export is still off until the explicit enable action, and
        // opt-in itself stays unavailable until the credential is SAVED: a test of the
        // draft key is not a stored credential.
        assertEquals(IntervalsPhase.TESTED_DISABLED, viewModel.uiState.value.phase)
        rule.onNodeWithText("Tested — still off").assertIsDisplayed()
        rule.onNodeWithText("Connection tested. Sending stays off until you turn it on.").assertIsDisplayed()
        assertEquals(0, source.enableCalls)
        rule.onNodeWithText("Turn on sending").performScrollTo().assertIsNotEnabled()

        // With the credential saved the explicit opt-in becomes available.
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Turn on sending").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, source.enableCalls)
        rule.onNodeWithText("On").assertIsDisplayed()
    }

    @Test
    fun rejectedCredentialsSurfaceAVisibleReasonAndKeepEnableOff() {
        val source = FakeIntervalsStatusSource()
        source.testResult = IntervalsValidationResult.InvalidCredentials(detail = "nope")
        val viewModel = newViewModel(source)
        setContent(viewModel)

        rule.onAllNodes(hasSetTextAction())[0].performTextInput("i123456")
        rule.onAllNodes(hasSetTextAction())[1].performTextInput("wrong-key")
        rule.onNodeWithText("Test connection").performScrollTo().performClick()
        rule.waitForIdle()

        rule.onNodeWithText(
            "Intervals.icu rejected these credentials. Check the athlete ID and API key.",
            substring = true,
        ).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Turn on sending").assertIsNotEnabled()
        assertEquals(0, source.enableCalls)
    }

    // ── Enabled + profile/destination-scoped counters ─────────────────────────

    @Test
    fun enabledStateShowsScopedCountersLastSentAndUpdatesOnProfileSwitch() {
        val source = FakeIntervalsStatusSource()
        source.emit(
            connection = source.connection(profileId = "profile-1"),
            counts = listOf(IntervalsJobState.PENDING to 3L, IntervalsJobState.SENT to 7L),
            lastSentAt = 1_728_000_000_000L,
        )
        val viewModel = newViewModel(source)
        setContent(viewModel)

        rule.onNodeWithText("On").assertIsDisplayed()
        rule.onNodeWithText("New finished workouts are sent to Intervals.icu.").assertIsDisplayed()
        rule.onNodeWithText("This profile and athlete").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(KmpUtils.formatTimestamp(1_728_000_000_000L)).assertIsDisplayed()
        assertEquals(3L, viewModel.uiState.value.queuedCount)
        assertEquals(7L, viewModel.uiState.value.sentCount)

        // Counters track the scoped profile/destination — switching swaps them wholesale.
        source.emit(
            connection = source.connection(profileId = "profile-2", athleteId = "i999999"),
            counts = listOf(IntervalsJobState.PENDING to 1L, IntervalsJobState.SENT to 2L),
            lastSentAt = null,
        )
        rule.waitForIdle()

        assertEquals("profile-2", viewModel.uiState.value.profileId)
        assertEquals(1L, viewModel.uiState.value.queuedCount)
        assertEquals(2L, viewModel.uiState.value.sentCount)
        rule.onNodeWithText("Never").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("i999999").performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("3").assertCountEquals(0)
        rule.onAllNodesWithText("7").assertCountEquals(0)
    }

    // ── Pause / resume ────────────────────────────────────────────────────────

    @Test
    fun pausedStateBlocksFutureCompletionsAndResumesExplicitly() {
        val source = FakeIntervalsStatusSource()
        source.emit(connection = source.connection(paused = true))
        val viewModel = newViewModel(source)
        setContent(viewModel)

        rule.onNodeWithText("Paused").assertIsDisplayed()
        rule.onNodeWithText(
            "Sending is paused. Workouts finished now stay on this phone and are not sent later.",
        ).performScrollTo().assertIsDisplayed()
        rule.onAllNodesWithText("Pause sending").assertCountEquals(0)

        rule.onNodeWithText("Resume sending").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, source.resumeCalls)
        rule.onNodeWithText("On").assertIsDisplayed()
    }

    @Test
    fun enabledStateCanPause() {
        val source = FakeIntervalsStatusSource()
        source.emit(connection = source.connection())
        val viewModel = newViewModel(source)
        setContent(viewModel)

        rule.onNodeWithText("Pause sending").performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals(1, source.pauseCalls)
        rule.onNodeWithText("Paused").assertIsDisplayed()
    }

    // ── Auth-required / secure-store-unavailable / offline ────────────────────

    @Test
    fun authRequiredStateOffersCredentialRepair() {
        val source = FakeIntervalsStatusSource()
        source.emit(connection = source.connection(authRequired = true))
        val viewModel = newViewModel(source)
        setContent(viewModel)

        rule.onNodeWithText("Sign-in needed").assertIsDisplayed()
        rule.onNodeWithText(
            "Intervals.icu rejected the saved credentials. Update your API key to send again.",
        ).performScrollTo().assertIsDisplayed()
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(2)
    }

    @Test
    fun secureStoreUnavailableStateFailsClosedWithoutPlaintextFallback() {
        val source = FakeIntervalsStatusSource()
        source.secureStoreFlow.value = false
        val viewModel = newViewModel(source)
        setContent(viewModel)

        rule.onNodeWithText("Secure storage unavailable").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(
            "Phoenix keeps your key in the device's secure storage, which is unavailable right now. Sending stays off and nothing is saved in plain text.",
        ).assertIsDisplayed()
    }

    @Test
    fun offlineQueuedStateDistinguishesSavedFromQueued() {
        val source = FakeIntervalsStatusSource()
        source.networkFlow.value = false
        source.emit(
            connection = source.connection(),
            counts = listOf(IntervalsJobState.PENDING to 2L),
        )
        val viewModel = newViewModel(source)
        setContent(viewModel)

        rule.onNodeWithText("Queued — waiting to send").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(
            "Workouts are saved and queued. They send when a connection is available.",
        ).assertIsDisplayed()
    }

    // ── Retry exhaustion / permanent validation failure with visible reason ───

    @Test
    fun retryExhaustedStateShowsTheStoredPermanentFailureReason() {
        val source = FakeIntervalsStatusSource()
        source.emit(
            connection = source.connection(),
            counts = listOf(IntervalsJobState.PERMANENT_FAILURE to 1L),
            failureReason = IntervalsReasonCode.RETRY_EXHAUSTED,
        )
        val viewModel = newViewModel(source)
        setContent(viewModel)

        rule.onAllNodesWithText("Upload stopped").assertCountEquals(2)
        rule.onAllNodesWithText("Upload stopped")[0].performScrollTo().assertIsDisplayed()
        // Message is visible in both the status card and the failure detail card.
        rule.onAllNodesWithText(
            "Some uploads stopped after too many attempts. Nothing is uploaded again automatically.",
        ).assertCountEquals(2)
        rule.onNodeWithText("Reason").assertIsDisplayed()
        rule.onNodeWithText("Too many attempts").assertIsDisplayed()
    }

    // ── Uncertain outcome: check status, never upload-again ───────────────────

    @Test
    fun uncertainStateOffersCheckUploadAndNeverUploadAgain() {
        val source = FakeIntervalsStatusSource()
        source.emit(
            connection = source.connection(),
            counts = listOf(IntervalsJobState.UNCERTAIN to 1L),
        )
        val viewModel = newViewModel(source)
        setContent(viewModel)

        rule.onAllNodesWithText("Upload status unknown").assertCountEquals(2)
        rule.onAllNodesWithText("Upload status unknown")[0].performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Check upload status").assertIsDisplayed()

        // The forbidden blind-retry copy must never appear in any state.
        rule.onAllNodesWithText("Upload again").assertCountEquals(0)

        rule.onNodeWithText("Check upload status").performClick()
        rule.waitForIdle()
        assertEquals(1, source.checkCalls)
        rule.onNodeWithText("Upload status checked.").assertIsDisplayed()
        assertEquals(IntervalsCheckState.CHECKED, viewModel.uiState.value.checkState)

        // A failing check is retried via a status readback — still never a re-upload.
        source.checkResult = IntervalsCheckUploadResult.Failed(
            reason = IntervalsReasonCode.LEASE_EXPIRED,
            retryable = true,
        )
        rule.onNodeWithText("Check upload status").performClick()
        rule.waitForIdle()
        rule.onNodeWithText(
            "Couldn't check the upload status. Nothing was uploaded again.",
        ).assertIsDisplayed()
        rule.onNodeWithText("Try the check again").performClick()
        rule.waitForIdle()
        assertEquals(3, source.checkCalls)
    }

    // ── Disconnect confirmation ───────────────────────────────────────────────

    @Test
    fun disconnectConfirmationStatesRemoteActivitiesRemain() {
        val source = FakeIntervalsStatusSource()
        source.emit(connection = source.connection())
        val viewModel = newViewModel(source)
        setContent(viewModel)

        rule.onNodeWithText("Disconnect").performScrollTo().performClick()
        rule.waitForIdle()

        assertEquals(IntervalsPhase.DISCONNECT_CONFIRM, viewModel.uiState.value.phase)
        rule.onNodeWithText("Disconnect Intervals.icu?").assertIsDisplayed()
        // The remote-activities-remain copy is visible in the confirmation state
        // (status card + dialog).
        rule.onAllNodesWithText(
            "Phoenix turns off sending and deletes your key from this phone. Workouts already sent to Intervals.icu stay on Intervals.icu.",
        ).assertCountEquals(2)

        rule.onNode(
            hasText("Disconnect") and hasAnyAncestor(isDialog()),
        ).performClick()
        rule.waitForIdle()

        assertEquals(1, source.disconnectCalls)
        rule.onNodeWithText("Off").assertIsDisplayed()
    }

    // ── Themes and large font ─────────────────────────────────────────────────

    @Test
    fun screenRendersInLightTheme() {
        val source = FakeIntervalsStatusSource()
        setContent(newViewModel(source), dark = false)
        rule.onNodeWithText("Intervals.icu").assertIsDisplayed()
        rule.onNodeWithText("Off").assertIsDisplayed()
        rule.onNodeWithText("How sharing works").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun screenRendersInDarkTheme() {
        val source = FakeIntervalsStatusSource()
        setContent(newViewModel(source), dark = true)
        rule.onNodeWithText("Intervals.icu").assertIsDisplayed()
        rule.onNodeWithText("Off").assertIsDisplayed()
        rule.onNodeWithText("How sharing works").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun screenRendersAtLargeFontScale() {
        val source = FakeIntervalsStatusSource()
        setContent(newViewModel(source), fontScale = 1.3f)
        rule.onNodeWithText("Intervals.icu").assertIsDisplayed()
        rule.onNodeWithText("Turn on sending").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Saved, queued, sent").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("How sharing works").performScrollTo().assertIsDisplayed()
    }

    // ── Packet boundary + localization guard ──────────────────────────────────

    @Test
    fun intervalsUiStaysInPhoneMainAndAllCopyIsLocalized() {
        val root = projectRoot()

        // Product UI + VM live in phoneMain only.
        assertTrue(
            File(root, "shared/src/phoneMain/kotlin/com/devil/phoenixproject/presentation/screen/IntervalsSettingsScreen.kt").exists(),
            "IntervalsSettingsScreen must live in phoneMain",
        )
        assertTrue(
            File(root, "shared/src/phoneMain/kotlin/com/devil/phoenixproject/presentation/viewmodel/IntervalsSettingsViewModel.kt").exists(),
            "IntervalsSettingsViewModel must live in phoneMain",
        )

        // No Intervals presentation code outside phoneMain: nothing in commonMain/TV can
        // auto-schedule exports on non-phone targets. Sources only — compiled classes in
        // build/ mirror the sources legitimately.
        val stray = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name.startsWith("Intervals") }
            .filter { it.path.contains("presentation") && !it.path.contains("/build/") }
            .filter { !it.path.contains("shared/src/phoneMain") && !it.path.contains("shared/src/androidHostTest") }
            .map { it.path }
            .toList()
        assertEquals(emptyList(), stray, "Intervals presentation code must stay in phoneMain")

        // The screen uses theme colors + resources, masks the key, and never shows a
        // blind re-upload action.
        val screen = File(
            root,
            "shared/src/phoneMain/kotlin/com/devil/phoenixproject/presentation/screen/IntervalsSettingsScreen.kt",
        ).readText()
        assertFalse(screen.contains("Color(0x"), "no hardcoded palette in IntervalsSettingsScreen")
        assertTrue(screen.contains("PasswordVisualTransformation"), "the API-key input must be masked")
        assertTrue(screen.contains("MaterialTheme.colorScheme"), "use the current theme colors")
        assertFalse(screen.contains("Upload again"), "the forbidden blind-retry copy must never appear")

        // Every new string exists in every locale, under intervals_* keys.
        val locales = listOf("values", "values-de", "values-es", "values-fr", "values-it", "values-nl")
        val requiredKeys = listOf(
            "intervals_screen_title",
            "intervals_send_workouts_title",
            "intervals_disclosure_backup_tradeoff",
            "intervals_three_way_saved",
            "intervals_message_uncertain",
            "intervals_action_check_upload",
            "intervals_disconnect_message",
        )
        for (locale in locales) {
            val strings = File(root, "shared/src/commonMain/composeResources/$locale/strings.xml").readText()
            for (key in requiredKeys) {
                assertTrue(strings.contains("name=\"$key\""), "$locale is missing $key")
            }
            assertFalse(strings.contains("Upload again"), "$locale must not carry the forbidden blind-retry copy")
        }

        // Navigation + Integrations wiring stay within the allowed edits.
        val routes = File(
            root,
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/navigation/NavigationRoutes.kt",
        ).readText()
        assertTrue(
            routes.contains("IntervalsSettings : NavigationRoutes(\"integrations/intervals\")"),
            "NavigationRoutes must carry the IntervalsSettings route",
        )
        val integrations = File(
            root,
            "shared/src/phoneMain/kotlin/com/devil/phoenixproject/presentation/screen/IntegrationsScreen.kt",
        ).readText()
        assertTrue(
            integrations.contains("Res.string.intervals_send_workouts_title"),
            "IntegrationsScreen must render the localized Send workouts section",
        )
    }

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "shared/src/commonMain").exists()) {
            dir = dir.parentFile ?: break
        }
        return dir
    }
}
