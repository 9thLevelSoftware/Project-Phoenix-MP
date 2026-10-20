package com.devil.phoenixproject.presentation

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PolarH10WiringTest {

    @Test
    fun settingsTabIncludesPolarH10SettingsCard() {
        val src = readProjectFile("src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SettingsTab.kt")
        assertNotNull(src, "SettingsTab.kt must be readable")
        assertTrue(
            src.contains("import com.devil.phoenixproject.presentation.components.PolarH10SettingsCard"),
            "SettingsTab.kt must import PolarH10SettingsCard",
        )
        assertTrue(
            src.contains("PolarH10SettingsCard()"),
            "SettingsTab.kt must render PolarH10SettingsCard",
        )
    }

    @Test
    fun activeWorkoutScreenIncludesPolarH10WorkoutStrip() {
        val src = readProjectFile("src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/ActiveWorkoutScreen.kt")
        assertNotNull(src, "ActiveWorkoutScreen.kt must be readable")
        assertTrue(
            src.contains("import com.devil.phoenixproject.presentation.components.PolarH10WorkoutStrip"),
            "ActiveWorkoutScreen.kt must import PolarH10WorkoutStrip",
        )
        assertTrue(
            src.contains("PolarH10WorkoutStrip()"),
            "ActiveWorkoutScreen.kt must render PolarH10WorkoutStrip",
        )
    }
}
