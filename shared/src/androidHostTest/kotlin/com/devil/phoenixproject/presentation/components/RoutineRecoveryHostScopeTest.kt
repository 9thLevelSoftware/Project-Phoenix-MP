package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.data.repository.RoutineRecoveryItem
import com.devil.phoenixproject.data.sync.GoTrueAuthResponse
import com.devil.phoenixproject.data.sync.GoTrueUser
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.FakeWorkoutRepository
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Issue #1162 final audit R4-UI/R5 regressions — recovery surface scope
 * lifecycle on a MOUNTED screen (Robolectric + real Compose runtime).
 *
 * Required behaviors under test:
 * - new retained snapshots surface on the mounted screen without a remount (R5),
 * - logout, an account switch with an unchanged stored owner, and a profile
 *   change clear/hide the scope-keyed items immediately (R4 UI),
 * - an in-flight load for the old scope never publishes into the changed scope
 *   (R4 UI),
 * - explicit entry revalidates the list (R5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoutineRecoveryHostScopeTest {

    @get:Rule
    val rule = createComposeRule()

    private fun recoveryItem(name: String = "Push Day") = RoutineRecoveryItem(
        recoveryId = "rec-1",
        graphIndex = 0,
        reason = "alias_coalesce",
        routineName = name,
        exercises = emptyList(),
    )

    private class Harness {
        val profiles = FakeUserProfileRepository()
        val tokenStorage = PortalTokenStorage(MapSettings())
        val workoutRepository = FakeWorkoutRepository()
        val scopeAItems = MutableStateFlow<List<RoutineRecoveryItem>>(emptyList())

        init {
            profiles.setActiveProfileForTest(id = "profile-a", supabaseUserId = "owner-a")
            signIn(tokenStorage, "owner-a")
            workoutRepository.routineRecoveryObserver = { _, _ -> scopeAItems }
        }
    }

    private fun mount(harness: Harness) {
        rule.setContent {
            RoutineRecoveryHost(
                workoutRepository = harness.workoutRepository,
                profileRepository = harness.profiles,
                portalTokenStorage = harness.tokenStorage,
            )
        }
    }

    @Test
    fun newRecoverySurfacesOnMountedScreenWithoutRemount() {
        val harness = Harness()
        mount(harness)
        rule.waitForIdle()
        rule.onNodeWithText("Recover deleted routines (1)").assertDoesNotExist()

        // A snapshot is retained AFTER the first (empty) load, screen stays mounted.
        harness.scopeAItems.value = listOf(recoveryItem())
        rule.waitForIdle()
        rule.onNodeWithText("Recover deleted routines (1)").assertIsDisplayed()
    }

    @Test
    fun logoutImmediatelyHidesRecoverySurface() {
        val harness = Harness()
        harness.scopeAItems.value = listOf(recoveryItem())
        mount(harness)
        rule.waitForIdle()
        rule.onNodeWithText("Recover deleted routines (1)").assertIsDisplayed()

        harness.tokenStorage.clearAuth()
        rule.waitForIdle()
        rule.onNodeWithText("Recover deleted routines (1)").assertDoesNotExist()
    }

    @Test
    fun accountSwitchWithUnchangedStoredOwnerHidesImmediately() {
        val harness = Harness()
        harness.scopeAItems.value = listOf(recoveryItem())
        mount(harness)
        rule.waitForIdle()
        rule.onNodeWithText("Recover deleted routines (1)").assertIsDisplayed()

        // A different account signs in; the profile row still names "owner-a".
        signIn(harness.tokenStorage, "other-user")
        rule.waitForIdle()
        rule.onNodeWithText("Recover deleted routines (1)").assertDoesNotExist()
    }

    @Test
    fun profileSwitchClearsImmediatelyAndStaleInFlightLoadNeverPublishes() {
        val harness = Harness()
        val staleGate = CompletableDeferred<Unit>()
        harness.workoutRepository.routineRecoveryObserver = { profileId, _ ->
            when (profileId) {
                "profile-a" -> flow {
                    emit(emptyList())
                    staleGate.await()
                    // In-flight load for the OLD scope completing late.
                    emit(listOf(recoveryItem("Stale Owner Programming")))
                }
                else -> emptyFlow()
            }
        }
        mount(harness)
        rule.waitForIdle()
        rule.onNodeWithText("Recover deleted routines (1)").assertDoesNotExist()

        // Profile switch (same owner): the old scope's state is dropped now.
        harness.profiles.setActiveProfileForTest(id = "profile-b", supabaseUserId = "owner-a")
        rule.waitForIdle()
        rule.onNodeWithText("Recover deleted routines (1)").assertDoesNotExist()

        // The old in-flight load completes after the scope changed: it must
        // never publish into the new scope.
        staleGate.complete(Unit)
        rule.waitForIdle()
        rule.onNodeWithText("Recover deleted routines (1)").assertDoesNotExist()
    }

    @Test
    fun explicitEntryRevalidatesTheList() {
        val harness = Harness()
        var subscriptions = 0
        harness.workoutRepository.routineRecoveryObserver = { _, _ ->
            subscriptions++
            harness.scopeAItems
        }
        harness.scopeAItems.value = listOf(recoveryItem())
        mount(harness)
        rule.waitForIdle()
        rule.onNodeWithText("Recover deleted routines (1)").performClick()
        rule.waitForIdle()
        assertTrue(
            "explicit entry must revalidate the list (resubscribe), was $subscriptions",
            subscriptions >= 2,
        )
    }
}

/** Signs [storage] in as [userId] through the production auth path. */
private fun signIn(storage: PortalTokenStorage, userId: String) {
    storage.saveGoTrueAuth(
        GoTrueAuthResponse(
            accessToken = "test-access-token",
            expiresIn = 3600,
            refreshToken = "test-refresh-token",
            user = GoTrueUser(id = userId, email = "$userId@example.com"),
        ),
    )
}
