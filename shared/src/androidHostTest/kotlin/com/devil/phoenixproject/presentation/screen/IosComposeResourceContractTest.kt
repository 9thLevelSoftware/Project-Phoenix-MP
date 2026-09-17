package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class IosComposeResourceContractTest {
    @Test
    fun xcodeStagesResourcesForTheSelectedKotlinTarget() {
        val project = requireNotNull(
            readProjectFile("../iosApp/PhoenixApp/PhoenixApp.xcodeproj/project.pbxproj")
                ?: readProjectFile("iosApp/PhoenixApp/PhoenixApp.xcodeproj/project.pbxproj"),
        )

        val shellScript = assertNotNull(
            Regex(
                """shellScript = \"(.*?)\";""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            ).find(project),
        ).groupValues[1]
            .replace("\\n", "\n")
            .replace("\\\"", "\"")

        assertContains(shellScript, "KOTLIN_TARGET=\"iosSimulatorArm64\"")
        assertContains(shellScript, "KOTLIN_TARGET=\"iosArm64\"")
        assertContains(shellScript, "processedResources/${'$'}{KOTLIN_TARGET}/main/composeResources")
        assertContains(shellScript, "compose-resources/composeResources")
        assertContains(shellScript, "Processed Compose resources not found")
        assertContains(shellScript, "if [ -d \"${'$'}SOURCE\" ]; then")
        assertContains(shellScript, "cp -R \"${'$'}SOURCE/\" \"${'$'}DEST/\"")
        assertFalse(project.contains("$(SRCROOT)/compose-resources\\n"))
    }
}