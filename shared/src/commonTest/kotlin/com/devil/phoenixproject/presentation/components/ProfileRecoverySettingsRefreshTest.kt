package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ProfileRecoverySettingsRefreshTest {
    @Test
    fun refreshRunsOnlyWhileARecoveryIsShown() = runTest {
        assertEquals(3_000L, PROFILE_RECOVERY_SETTINGS_REFRESH_INTERVAL_MS)
        val shown = MutableStateFlow(false)
        var refreshes = 0
        val job = launch {
            refreshProfileRecoveryWhileShown(
                shown = shown,
                refresh = { refreshes += 1 },
                intervalMillis = PROFILE_RECOVERY_SETTINGS_REFRESH_INTERVAL_MS,
            )
        }
        runCurrent()
        advanceTimeBy(PROFILE_RECOVERY_SETTINGS_REFRESH_INTERVAL_MS * 3)
        runCurrent()
        assertEquals(0, refreshes)

        shown.value = true
        runCurrent()
        assertEquals(1, refreshes)

        advanceTimeBy(PROFILE_RECOVERY_SETTINGS_REFRESH_INTERVAL_MS - 1)
        runCurrent()
        assertEquals(1, refreshes)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, refreshes)

        shown.value = false
        advanceTimeBy(PROFILE_RECOVERY_SETTINGS_REFRESH_INTERVAL_MS * 3)
        runCurrent()
        assertEquals(2, refreshes)
        job.cancel()
    }

    @Test
    fun settingsSubscribesToProfilesAndRefreshOnlyWhileRecoveryIsVisible() {
        val section = source(
            "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileRecoverySettingsSection.kt",
        )
        val idle = functionBody(section, "ProfileRecoverySettingsSection")
        val visible = functionBody(section, "VisibleProfileRecoverySettings")
        val refresh = functionBody(
            source(
                "src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/ProfileRecoverySettingsRefresh.kt",
            ),
            "refreshProfileRecoveryWhileShown",
        )

        assertTrue(idle.indexOf("if (pending.isEmpty() && cloudPending.isEmpty()) return") < idle.indexOf("VisibleProfileRecoverySettings("))
        assertTrue(idle.contains("observeUnresolved()"))
        assertTrue(idle.contains("observePending()"))
        assertFalse(idle.contains("while (true)"))
        assertFalse(idle.contains("pendingAll()"))
        assertFalse(idle.contains("allProfiles"))
        assertFalse(idle.contains("authState"))
        assertFalse(idle.contains("delay("))

        assertTrue(visible.contains("allProfiles.collectAsState()"))
        assertTrue(visible.contains("refreshProfileRecoveryWhileShown("))
        assertFalse(visible.contains("authState"))
        assertFalse(visible.contains("pendingAll()"))
        assertTrue(refresh.indexOf("if (!isShown) return@collectLatest") < refresh.indexOf("while (true)"))
    }

    private fun source(path: String): String = requireNotNull(readProjectFile(path)) { path }

    private fun functionBody(source: String, name: String): String {
        val marker = "fun $name("
        val start = source.indexOf(marker)
        assertTrue(start >= 0, "missing $name")
        val open = source.indexOf('{', start)
        assertTrue(open >= 0, "missing body for $name")
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
        }
        error("missing closing brace for $name")
    }
}
