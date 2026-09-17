package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class IosComposeResourceContractTest {
    @Test
    fun xcodeStagesResourcesForTheSelectedKotlinTarget() {
        val project = requireNotNull(
            readProjectFile("../iosApp/PhoenixApp/PhoenixApp.xcodeproj/project.pbxproj")
                ?: readProjectFile("iosApp/PhoenixApp/PhoenixApp.xcodeproj/project.pbxproj"),
        )

        assertContains(project, "KOTLIN_TARGET=\"iosSimulatorArm64\"")
        assertContains(project, "KOTLIN_TARGET=\"iosArm64\"")
        assertContains(project, "processedResources/${'$'}{KOTLIN_TARGET}/main/composeResources")
        assertContains(project, "compose-resources/composeResources")
        assertContains(project, "Processed Compose resources not found")
        assertFalse(project.contains("$(SRCROOT)/compose-resources\\n"))
    }
}