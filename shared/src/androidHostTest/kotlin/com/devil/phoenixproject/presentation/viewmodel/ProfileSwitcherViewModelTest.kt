package com.devil.phoenixproject.presentation.viewmodel

import com.devil.phoenixproject.data.repository.ProfileContextRecoveryException
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.TestCoroutineRule
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ProfileSwitcherViewModelTest {
    @get:Rule
    val coroutineRule = TestCoroutineRule()

    private lateinit var profiles: FakeUserProfileRepository

    @Before
    fun setUp() {
        profiles = FakeUserProfileRepository()
        profiles.seedReadyProfileForTest("a", name = "A")
        profiles.seedReadyProfileForTest("b", name = "B")
        profiles.seedReadyProfileForTest("c", name = "C")
        profiles.emitReadyForTest("a")
    }

    @Test
    fun `sheet opens during Switching null but cannot dismiss or start an operation`() = runTest {
        profiles.emitSwitchingForTest(null)
        val viewModel = createViewModel()

        viewModel.openSwitcher()
        assertEquals(ProfileSwitcherUiState(showSwitcher = true), viewModel.uiState.value)

        viewModel.dismissSwitcher()
        viewModel.switchProfile("b")
        advanceUntilIdle()

        assertEquals(ProfileSwitcherUiState(showSwitcher = true), viewModel.uiState.value)
        assertTrue(profiles.setActiveProfileRequests.isEmpty())
        assertTrue(profiles.createAndActivateRequests.isEmpty())
        assertEquals(0, profiles.reconcileActiveProfileContextRequests)
    }

    @Test
    fun `successful switch calls the repository once and closes the sheet`() = runTest {
        val viewModel = createViewModel()
        viewModel.openSwitcher()

        viewModel.switchProfile("b")
        assertEquals(
            RootProfileOperation(1L, RootProfileOperationKind.SWITCH, "b"),
            viewModel.uiState.value.operation,
        )
        assertTrue(viewModel.uiState.value.showSwitcher)

        advanceUntilIdle()

        assertEquals(listOf("b"), profiles.setActiveProfileRequests)
        assertEquals(ProfileSwitcherUiState(), viewModel.uiState.value)
    }

    @Test
    fun `ordinary switch failure keeps the sheet open with only switch error`() = runTest {
        profiles.setActiveProfileFailure = IllegalStateException("switch")
        val viewModel = createViewModel()
        viewModel.openSwitcher()

        viewModel.switchProfile("b")
        assertEquals(
            RootProfileOperation(1L, RootProfileOperationKind.SWITCH, "b"),
            viewModel.uiState.value.operation,
        )
        advanceUntilIdle()

        assertEquals(listOf("b"), profiles.setActiveProfileRequests)
        assertEquals(
            ProfileSwitcherUiState(
                showSwitcher = true,
                error = ProfileOverlayError.SWITCH_FAILED,
            ),
            viewModel.uiState.value,
        )
    }

    @Test
    fun `switch recovery failure closes ordinary overlays and opens blocking recovery`() = runTest {
        profiles.setActiveProfileFailure = recoveryFailure()
        val viewModel = createViewModel()
        viewModel.openSwitcher()

        viewModel.switchProfile("b")
        assertEquals(
            RootProfileOperation(1L, RootProfileOperationKind.SWITCH, "b"),
            viewModel.uiState.value.operation,
        )
        advanceUntilIdle()

        assertEquals(listOf("b"), profiles.setActiveProfileRequests)
        assertEquals(
            ProfileSwitcherUiState(recoveryRequired = true),
            viewModel.uiState.value,
        )
    }

    @Test
    fun `switch cancellation clears only its token and emits no error`() = runTest {
        profiles.setActiveProfileFailure = CancellationException("cancel switch")
        val viewModel = createViewModel()
        viewModel.openSwitcher()

        viewModel.switchProfile("b")
        assertEquals(
            RootProfileOperation(1L, RootProfileOperationKind.SWITCH, "b"),
            viewModel.uiState.value.operation,
        )
        advanceUntilIdle()

        assertEquals(listOf("b"), profiles.setActiveProfileRequests)
        assertEquals(ProfileSwitcherUiState(showSwitcher = true), viewModel.uiState.value)
    }

    @Test
    fun `two same-frame switch requests launch only the first`() = runTest {
        val release = CompletableDeferred<Unit>()
        profiles.beforeSetActiveProfile = { release.await() }
        val viewModel = createViewModel()
        viewModel.openSwitcher()

        viewModel.switchProfile("b")
        viewModel.switchProfile("c")
        assertEquals(
            RootProfileOperation(1L, RootProfileOperationKind.SWITCH, "b"),
            viewModel.uiState.value.operation,
        )
        runCurrent()

        assertEquals(listOf("b"), profiles.setActiveProfileRequests)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(ProfileSwitcherUiState(), viewModel.uiState.value)
    }

    @Test
    fun `noncooperative stale switch completion cannot close a newer recovery state`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        profiles.beforeSetActiveProfile = {
            entered.complete(Unit)
            try {
                release.await()
            } catch (_: CancellationException) {
                // Deliberately non-cooperative: return so the stale repository call completes.
            }
        }
        val viewModel = createViewModel()
        viewModel.openSwitcher()
        viewModel.switchProfile("b")
        runCurrent()
        assertTrue(entered.isCompleted)
        assertEquals(listOf("b"), profiles.setActiveProfileRequests)

        viewModel.requireRecovery(recoveryFailure())
        assertEquals(
            ProfileSwitcherUiState(recoveryRequired = true),
            viewModel.uiState.value,
        )
        release.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("b"), profiles.setActiveProfileRequests)
        assertEquals(
            ProfileSwitcherUiState(recoveryRequired = true),
            viewModel.uiState.value,
        )
    }

    @Test
    fun `add opens from the sheet and dismiss returns to that sheet`() = runTest {
        val viewModel = createViewModel()
        viewModel.openSwitcher()

        viewModel.openAddDialog()
        assertEquals(
            ProfileSwitcherUiState(showSwitcher = true, showAddDialog = true),
            viewModel.uiState.value,
        )

        profiles.emitSwitchingForTest(null)
        viewModel.dismissAddDialog()
        assertEquals(
            ProfileSwitcherUiState(showSwitcher = true, showAddDialog = true),
            viewModel.uiState.value,
        )

        profiles.emitReadyForTest("a")
        viewModel.dismissAddDialog()
        assertEquals(ProfileSwitcherUiState(showSwitcher = true), viewModel.uiState.value)
        assertTrue(profiles.createAndActivateRequests.isEmpty())
    }

    @Test
    fun `successful create and activate calls once and closes both overlays`() = runTest {
        val viewModel = createViewModel()
        openAddDialog(viewModel)

        viewModel.createAndActivateProfile("  New  ", 3)
        assertEquals(
            RootProfileOperation(1L, RootProfileOperationKind.CREATE),
            viewModel.uiState.value.operation,
        )
        assertTrue(viewModel.uiState.value.showSwitcher)
        assertTrue(viewModel.uiState.value.showAddDialog)
        advanceUntilIdle()

        assertEquals(
            listOf(FakeUserProfileRepository.CreateAndActivateRequest("New", 3)),
            profiles.createAndActivateRequests,
        )
        assertEquals(ProfileSwitcherUiState(), viewModel.uiState.value)
    }

    @Test
    fun `ordinary create failure keeps both overlays with only create error`() = runTest {
        profiles.createAndActivateProfileFailure = IllegalStateException("create")
        val viewModel = createViewModel()
        openAddDialog(viewModel)

        viewModel.createAndActivateProfile("New", 3)
        assertEquals(
            RootProfileOperation(1L, RootProfileOperationKind.CREATE),
            viewModel.uiState.value.operation,
        )
        advanceUntilIdle()

        assertEquals(
            listOf(FakeUserProfileRepository.CreateAndActivateRequest("New", 3)),
            profiles.createAndActivateRequests,
        )
        assertEquals(
            ProfileSwitcherUiState(
                showSwitcher = true,
                showAddDialog = true,
                error = ProfileOverlayError.CREATE_FAILED,
            ),
            viewModel.uiState.value,
        )
    }

    @Test
    fun `create recovery failure closes both overlays and opens blocking recovery`() = runTest {
        profiles.createAndActivateProfileFailure = recoveryFailure()
        val viewModel = createViewModel()
        openAddDialog(viewModel)

        viewModel.createAndActivateProfile("New", 3)
        assertEquals(
            RootProfileOperation(1L, RootProfileOperationKind.CREATE),
            viewModel.uiState.value.operation,
        )
        advanceUntilIdle()

        assertEquals(
            listOf(FakeUserProfileRepository.CreateAndActivateRequest("New", 3)),
            profiles.createAndActivateRequests,
        )
        assertEquals(
            ProfileSwitcherUiState(recoveryRequired = true),
            viewModel.uiState.value,
        )
    }

    @Test
    fun `two same-frame create confirmations launch only the first`() = runTest {
        val release = CompletableDeferred<Unit>()
        profiles.beforeCreateAndActivateProfile = { _, _ -> release.await() }
        val viewModel = createViewModel()
        openAddDialog(viewModel)

        viewModel.createAndActivateProfile("First", 2)
        viewModel.createAndActivateProfile("Second", 6)
        assertEquals(
            RootProfileOperation(1L, RootProfileOperationKind.CREATE),
            viewModel.uiState.value.operation,
        )
        runCurrent()

        assertEquals(
            listOf(FakeUserProfileRepository.CreateAndActivateRequest("First", 2)),
            profiles.createAndActivateRequests,
        )
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(ProfileSwitcherUiState(), viewModel.uiState.value)
    }

    @Test
    fun `Task 6 recovery handoff cancels ownership and opens the same recovery state`() = runTest {
        profiles.beforeCreateAndActivateProfile = { _, _ -> awaitCancellation() }
        val viewModel = createViewModel()
        openAddDialog(viewModel)
        viewModel.createAndActivateProfile("New", 3)
        runCurrent()
        assertEquals(
            listOf(FakeUserProfileRepository.CreateAndActivateRequest("New", 3)),
            profiles.createAndActivateRequests,
        )
        assertEquals(
            RootProfileOperation(1L, RootProfileOperationKind.CREATE),
            viewModel.uiState.value.operation,
        )

        viewModel.requireRecovery(recoveryFailure())
        advanceUntilIdle()

        assertEquals(
            ProfileSwitcherUiState(recoveryRequired = true),
            viewModel.uiState.value,
        )
    }

    @Test
    fun `successful recovery retry clears the blocking modal`() = runTest {
        val viewModel = createViewModel()
        viewModel.requireRecovery(recoveryFailure())

        viewModel.retryRecovery()
        assertEquals(
            RootProfileOperation(1L, RootProfileOperationKind.RECOVERY),
            viewModel.uiState.value.operation,
        )
        assertTrue(viewModel.uiState.value.recoveryRequired)
        advanceUntilIdle()

        assertEquals(1, profiles.reconcileActiveProfileContextRequests)
        assertEquals(ProfileSwitcherUiState(), viewModel.uiState.value)
    }

    @Test
    fun `failed recovery retry keeps the modal with only retry error`() = runTest {
        profiles.reconcileActiveProfileContextFailure = IllegalStateException("retry")
        val viewModel = createViewModel()
        viewModel.requireRecovery(recoveryFailure())

        viewModel.retryRecovery()
        assertEquals(
            RootProfileOperation(1L, RootProfileOperationKind.RECOVERY),
            viewModel.uiState.value.operation,
        )
        advanceUntilIdle()

        assertEquals(1, profiles.reconcileActiveProfileContextRequests)
        assertEquals(
            ProfileSwitcherUiState(
                recoveryRequired = true,
                error = ProfileOverlayError.RECOVERY_RETRY_FAILED,
            ),
            viewModel.uiState.value,
        )
    }

    @Test
    fun `two same-frame recovery retries launch only the first`() = runTest {
        val release = CompletableDeferred<Unit>()
        profiles.beforeReconcileActiveProfileContext = { release.await() }
        val viewModel = createViewModel()
        viewModel.requireRecovery(recoveryFailure())

        viewModel.retryRecovery()
        viewModel.retryRecovery()
        assertEquals(
            RootProfileOperation(1L, RootProfileOperationKind.RECOVERY),
            viewModel.uiState.value.operation,
        )
        runCurrent()

        assertEquals(1, profiles.reconcileActiveProfileContextRequests)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(ProfileSwitcherUiState(), viewModel.uiState.value)
    }

    @Test
    fun `recovery cancellation clears its token but keeps recovery blocking without an error`() =
        runTest {
            profiles.reconcileActiveProfileContextFailure = CancellationException("cancel retry")
            val viewModel = createViewModel()
            viewModel.requireRecovery(recoveryFailure())

            viewModel.retryRecovery()
            assertEquals(
                RootProfileOperation(1L, RootProfileOperationKind.RECOVERY),
                viewModel.uiState.value.operation,
            )
            advanceUntilIdle()

            assertEquals(1, profiles.reconcileActiveProfileContextRequests)
            val state = viewModel.uiState.value
            assertTrue(state.recoveryRequired)
            assertNull(state.operation)
            assertNull(state.error)
            assertFalse(state.showSwitcher)
            assertFalse(state.showAddDialog)
        }

    private fun createViewModel() = ProfileSwitcherViewModel(profiles)

    private fun openAddDialog(viewModel: ProfileSwitcherViewModel) {
        viewModel.openSwitcher()
        viewModel.openAddDialog()
        assertEquals(
            ProfileSwitcherUiState(showSwitcher = true, showAddDialog = true),
            viewModel.uiState.value,
        )
    }

    private fun recoveryFailure() =
        ProfileContextRecoveryException(IllegalStateException("recovery"))
}
