package com.devil.phoenixproject.data.preferences

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
}
