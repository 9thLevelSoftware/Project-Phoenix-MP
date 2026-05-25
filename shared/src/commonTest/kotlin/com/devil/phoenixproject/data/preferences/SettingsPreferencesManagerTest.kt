package com.devil.phoenixproject.data.preferences

import com.devil.phoenixproject.domain.model.EchoLevel
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsPreferencesManagerTest {

    @Test
    fun `loadPreferences removes legacy hud preset key`() {
        val settings = MapSettings().apply {
            putString("hud_preset", "biomechanics")
            putInt("summary_countdown_seconds", 15)
        }

        val manager = SettingsPreferencesManager(settings)

        assertNull(settings.getStringOrNull("hud_preset"))
        assertEquals(15, manager.preferencesFlow.value.summaryCountdownSeconds)
        assertTrue(manager.preferencesFlow.value.enableVideoPlayback)
    }

    @Test
    fun `just lift defaults use official Echo defaults`() {
        val defaults = JustLiftDefaults()

        assertEquals(0, defaults.echoLevelValue)
        assertEquals(EchoLevel.HARD, defaults.getEchoLevel())
    }
}
