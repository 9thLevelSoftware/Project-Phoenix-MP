package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the two Echo-level editors that used to paint English [com.devil.phoenixproject.domain.model.EchoLevel.displayName]
 * values. The config modal delegates to [com.devil.phoenixproject.presentation.components.EchoLevelPillSelector];
 * the exercise editor keeps its segmented row but binds localized labels.
 */
class EchoLevelEditorLocalizationGuardTest {

    private fun read(relativePath: String): String =
        requireNotNull(readProjectFile(relativePath)) {
            "Source file not found at $relativePath. " +
                "This localization guard test requires project source files to be present on disk."
        }

    @Test
    fun configModalEchoPanelDelegatesToLocalizedPillSelector() {
        val source = read(
            "shared/src/phoneMain/kotlin/com/devil/phoenixproject/presentation/components/ExerciseConfigModal.kt",
        )

        assertTrue(
            source.contains("EchoLevelPillSelector("),
            "EchoConfigPanel must delegate to EchoLevelPillSelector.",
        )
        assertFalse(
            source.contains("level.displayName"),
            "ExerciseConfigModal must not paint EchoLevel.displayName.",
        )
        assertFalse(
            source.contains("text = \"Echo Level\""),
            "ExerciseConfigModal must not hard-code an Echo Level title.",
        )
    }

    @Test
    fun bottomSheetEchoSelectorUsesResourceTitleAndLocalizedChipText() {
        val source = read(
            "shared/src/phoneMain/kotlin/com/devil/phoenixproject/presentation/screen/ExerciseEditBottomSheet.kt",
        )
        val selector = source.substringAfter("fun EchoLevelSelector(")
            .substringBefore("\n/**")

        assertTrue(
            selector.contains("stringResource(Res.string.echo_level)"),
            "EchoLevelSelector title must use the echo_level string resource.",
        )
        assertTrue(
            selector.contains("echoLevelLabel("),
            "EchoLevelSelector chips must use localized EchoLevel labels.",
        )
        assertFalse(
            selector.contains("\"Echo Level\""),
            "EchoLevelSelector must not hard-code an English Echo Level title.",
        )
        assertFalse(
            selector.contains("displayName"),
            "EchoLevelSelector must not surface EchoLevel.displayName.",
        )
    }
}
