package com.devil.phoenixproject.presentation.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.devil.phoenixproject.data.repository.AuthRepository
import com.devil.phoenixproject.data.sync.AuthEvent
import com.devil.phoenixproject.data.sync.PortalUser
import com.devil.phoenixproject.data.sync.SyncManager
import com.devil.phoenixproject.data.sync.SyncState
import com.devil.phoenixproject.ui.sync.LinkAccountViewModel
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
class LinkAccountScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var isAuthenticatedFlow: MutableStateFlow<Boolean>
    private lateinit var currentUserFlow: MutableStateFlow<PortalUser?>
    private lateinit var syncStateFlow: MutableStateFlow<SyncState>
    private lateinit var lastSyncTimeFlow: MutableStateFlow<Long>
    private lateinit var openedUris: MutableList<String>

    @Before
    fun setUp() {
        isAuthenticatedFlow = MutableStateFlow(true)
        currentUserFlow = MutableStateFlow(
            PortalUser(
                id = "user-1",
                email = "lifter@phoenix.app",
                displayName = "Alex",
                isPremium = true,
            ),
        )
        syncStateFlow = MutableStateFlow(SyncState.Idle)
        lastSyncTimeFlow = MutableStateFlow(0L)
        openedUris = mutableListOf()

        val syncManager = mockk<SyncManager>(relaxed = true).also {
            every { it.isAuthenticated } returns isAuthenticatedFlow
            every { it.currentUser } returns currentUserFlow
            every { it.syncState } returns syncStateFlow
            every { it.lastSyncTime } returns lastSyncTimeFlow
            every { it.authEvents } returns MutableSharedFlow<AuthEvent>()
        }
        val authRepository = mockk<AuthRepository>(relaxed = true)

        stopKoin()
        startKoin {
            modules(
                module {
                    factory { LinkAccountViewModel(syncManager, authRepository) }
                },
            )
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun setScreenContent() {
        val fakeUriHandler = object : UriHandler {
            override fun openUri(uri: String) {
                openedUris.add(uri)
            }
        }
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalUriHandler provides fakeUriHandler) {
                    LinkAccountScreen()
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun premiumBadgeHasNoClickAction_andDeletionRowOpensPortalProfileInScrollableContainer() {
        setScreenContent()

        rule.onNodeWithText("Premium").assertIsDisplayed().assertHasNoClickAction()

        rule.onNodeWithText("Opens the portal. Workouts saved on this phone stay on this phone.")
            .performScrollTo()
            .assertIsDisplayed()
        rule.onNodeWithText("Delete portal account")
            .performScrollTo()
            .performClick()

        assertEquals(listOf(PORTAL_ACCOUNT_DELETION_URL), openedUris)
        assertEquals("https://phoenix-portal.com/profile", PORTAL_ACCOUNT_DELETION_URL)
    }

    @Test
    fun notPremiumSyncState_rendersPausedMessageAndGuardedLastSyncedLine() {
        syncStateFlow.value = SyncState.NotPremium
        lastSyncTimeFlow.value = 0L
        setScreenContent()

        rule.onNodeWithText("Sync paused — subscription required").assertIsDisplayed()
        rule.onNodeWithText("Last synced:", substring = true).assertDoesNotExist()

        lastSyncTimeFlow.value = 1_700_000_000_000L
        rule.waitForIdle()

        rule.onNodeWithText("Sync paused — subscription required").assertIsDisplayed()
        rule.onNodeWithText("Last synced:", substring = true).assertIsDisplayed()
    }
}
