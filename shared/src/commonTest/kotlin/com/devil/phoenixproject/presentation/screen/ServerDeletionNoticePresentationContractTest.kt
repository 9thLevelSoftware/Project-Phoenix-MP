package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ServerDeletionNoticePresentationContractTest {
    private val source = requireNotNull(
        readProjectFile(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt",
        ),
    )

    @Test
    fun serverDeletionNoticesAreShownFromTheGlobalScaffold() {
        assertContains(source, "val serverDeletionNotice by syncManager.serverDeletionNotice.collectAsState()")
        assertContains(source, "val serverDeletionNoticeSnackbarHostState = remember { SnackbarHostState() }")
        assertContains(source, "SnackbarHost(hostState = serverDeletionNoticeSnackbarHostState)")
    }

    @Test
    fun noticeIsAcknowledgedOnlyAfterTheSnackbarFinishes() {
        val effect = source.substring(
            source.indexOf("LaunchedEffect(serverDeletionNotice)"),
            source.indexOf("var currentRoute by remember"),
        )

        assertContains(effect, "message = notice.message")
        assertContains(effect, "duration = SnackbarDuration.Long")
        assertContains(effect, "syncManager.clearServerDeletionNotice(notice)")
        assertTrue(
            effect.indexOf("serverDeletionNoticeSnackbarHostState.showSnackbar(") <
                effect.indexOf("syncManager.clearServerDeletionNotice(notice)"),
            "The notice must remain pending until showSnackbar returns after dismissal.",
        )
    }
}
