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
        val source = read("shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutineOverviewScreen.kt")

        assertTrue(
            source.contains("stringResource(Res.string.rest_eccentric_load)"),
            "Overview Eccentric Load label must use the Compose resource system.",
        )
        assertTrue(
            source.contains("percentLabel(percent)"),
            "Overview Eccentric Load value must use the locale-aware percent formatter.",
        )
        assertFalse(
            source.contains("text = \"ECCENTRIC LOAD\""),
            "Overview Eccentric Load label must not be hard-coded English.",
        )
        assertFalse(
            source.contains("text = \"\$percent%\""),
            "Overview Eccentric Load value must not use the raw <int>% rendering.",
        )
    }
}
