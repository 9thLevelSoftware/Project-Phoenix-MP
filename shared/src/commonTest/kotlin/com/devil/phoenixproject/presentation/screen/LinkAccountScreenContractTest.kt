package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PR 12: paused-sync copy and the portal account-deletion link (F-074, F-080).
 * The shared module has no Compose UI test harness, so these pin the source contract;
 * each anchor is a single expression, not a window between two unrelated branches.
 */
class LinkAccountScreenContractTest {

    private val screen: String = assertNotNull(
        readProjectFile("src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/LinkAccountScreen.kt"),
    )

    @Test
    fun accountDeletionRowTargetsThePortalProfilePage() {
        // `/profile` hosts the portal's Danger Zone deletion flow.
        assertEquals("https://phoenix-portal.com/profile", PORTAL_ACCOUNT_DELETION_URL)
        assertContains(screen, "uriHandler.openUri(PORTAL_ACCOUNT_DELETION_URL)")
        assertContains(screen, "Res.string.delete_portal_account_caption")
    }

    @Test
    fun notPremiumRendersThePausedStatusWithTheLastSyncedTime() {
        // The NotPremium branch renders exactly the paused composable, fed the last-sync time.
        assertTrue(
            Regex("""is SyncState\.NotPremium -> SyncPausedStatus\(lastSyncTime\)""").containsMatchIn(screen),
            "NotPremium must render SyncPausedStatus(lastSyncTime)",
        )
        // That composable shows the paused copy, then the last-synced time when there is one.
        val body = Regex(
            """private fun SyncPausedStatus\(lastSyncTime: Long\) \{(.*?)\r?\n\}""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(screen)?.groupValues?.get(1)
        assertNotNull(body, "SyncPausedStatus composable not found")
        val paused = body.indexOf("Res.string.sync_paused_subscription_required")
        val guard = body.indexOf("if (lastSyncTime > 0)")
        val lastSynced = body.indexOf("Res.string.last_synced, formatSyncTimestamp(lastSyncTime)")
        assertTrue(paused >= 0 && guard > paused && lastSynced > guard, "paused copy, then guarded last-synced line")

        val strings = assertNotNull(readProjectFile("src/commonMain/composeResources/values/strings.xml"))
        assertContains(strings, "<string name=\"sync_paused_subscription_required\">Sync paused — subscription required</string>")
    }

    @Test
    fun theAuthenticatedScreenScrollsSoTheDeletionRowIsReachable() {
        assertContains(screen, ".verticalScroll(rememberScrollState())")
    }
}
