package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** PR 12: paused-sync copy and the portal account-deletion link (F-074, F-080). */
class LinkAccountScreenContractTest {

    @Test
    fun accountDeletionRowTargetsThePortalProfilePage() {
        // `/profile` hosts the portal's Danger Zone deletion flow.
        assertEquals("https://phoenix-portal.com/profile", PORTAL_ACCOUNT_DELETION_URL)

        val screen = assertNotNull(
            readProjectFile("src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/LinkAccountScreen.kt"),
        )
        assertContains(screen, "uriHandler.openUri(PORTAL_ACCOUNT_DELETION_URL)")
        assertContains(screen, "Res.string.delete_portal_account_caption")
    }

    @Test
    fun notPremiumRendersThePausedCopyAndKeepsTheLastSyncedTime() {
        val screen = assertNotNull(
            readProjectFile("src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/LinkAccountScreen.kt"),
        )
        val branch = screen.substringAfter("is SyncState.NotPremium ->").substringBefore("is SyncState.NotAuthenticated ->")
        assertContains(branch, "Res.string.sync_paused_subscription_required")
        assertContains(branch, "Res.string.last_synced")

        val strings = assertNotNull(readProjectFile("src/commonMain/composeResources/values/strings.xml"))
        assertContains(strings, "<string name=\"sync_paused_subscription_required\">Sync paused — subscription required</string>")
    }
}
