package com.devil.phoenixproject.presentation.screen

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutineOverviewLocalizationGuardTest {

    private val projectRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "shared/src/commonMain").exists()) {
            dir = dir.parentFile ?: break
        }
        dir
    }

    private fun read(relativePath: String): String {
        val file = File(projectRoot, relativePath)
        if (!file.exists()) {
            throw IllegalStateException(
                "Source file not found at ${file.absolutePath}. " +
                    "This localization guard test requires project source files to be present on disk.",
            )
        }
        return file.readText()
    }

    @Test
    fun overviewEchoLevelSelectorUsesResourceLabelAndLocalizedChipText() {
        // The three verbatim copies were unified into EchoLevelPillSelector (task 3.3).
        // Localization assertions now target the shared component rather than RoutineOverviewScreen.
        val componentSource = read(
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/EchoLevelPillSelector.kt",
        )
        val overviewSource = read(
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineOverviewScreen.kt",
        )

        assertTrue(
            componentSource.contains("stringResource(Res.string.rest_echo_level)"),
            "EchoLevelPillSelector label must use the Compose resource system.",
        )
        assertTrue(
            componentSource.contains("echoLevelLabel("),
            "EchoLevelPillSelector chips must use localized EchoLevel labels instead of enum displayName.",
        )
        assertFalse(
            componentSource.contains("text = \"ECHO LEVEL\""),
            "EchoLevelPillSelector label must not be hard-coded English.",
        )
        assertFalse(
            componentSource.contains("text = level.displayName"),
            "EchoLevelPillSelector chips must not surface hard-coded enum displayName values.",
        )
        // Confirm RoutineOverviewScreen delegates to EchoLevelPillSelector rather than inlining.
        assertTrue(
            overviewSource.contains("EchoLevelPillSelector("),
            "RoutineOverviewScreen must delegate to EchoLevelPillSelector.",
        )
        assertFalse(
            overviewSource.contains("text = \"ECHO LEVEL\""),
            "RoutineOverviewScreen must not contain a hard-coded Echo Level label.",
        )
    }

    @Test
    fun overviewEccentricLoadSliderUsesResourceLabelAndLocalizedPercentText() {
        // The three verbatim copies were unified into EccentricLoadSlider.
        // Localization assertions target the shared component; each caller only delegates.
        val componentSource = read(
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/components/EccentricLoadSlider.kt",
        )
        val callers = listOf(
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineOverviewScreen.kt",
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RestTimerCard.kt",
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/SetReadyScreen.kt",
        ).map { read(it) }

        assertTrue(
            componentSource.contains("stringResource(Res.string.rest_eccentric_load)"),
            "EccentricLoadSlider label must use the Compose resource system.",
        )
        assertTrue(
            componentSource.contains("percentLabel(percent)"),
            "EccentricLoadSlider value must use the locale-aware percent formatter.",
        )
        assertTrue(
            componentSource.contains("valueRange = 0f..150f"),
            "EccentricLoadSlider must keep the 0–150% range.",
        )
        assertTrue(
            componentSource.contains("steps = 29"),
            "EccentricLoadSlider must keep 5% steps.",
        )
        assertFalse(
            componentSource.contains("text = \"ECCENTRIC LOAD\""),
            "EccentricLoadSlider label must not be hard-coded English.",
        )
        assertFalse(
            componentSource.contains("text = \"\$percent%\""),
            "EccentricLoadSlider value must not use the raw <int>% rendering.",
        )
        callers.forEach { source ->
            assertTrue(
                source.contains("EccentricLoadSlider("),
                "Rest, Overview, and Set Ready must delegate to EccentricLoadSlider.",
            )
            assertFalse(
                source.contains("text = \"ECCENTRIC LOAD\""),
                "Call sites must not keep a hard-coded Eccentric Load label.",
            )
            assertFalse(
                source.contains("text = \"\$percent%\""),
                "Call sites must not keep the raw <int>% eccentric value.",
            )
        }
    }
}
