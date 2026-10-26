package com.devil.phoenixproject.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TvosPhoneSourceSetIsolationTest {
    @Test
    fun `phone shell and reorderable screens do not compile into the tvOS source set`() {
        val phoneOnlySources = listOf(
            "App.kt",
            "presentation/navigation/NavGraph.kt",
            "presentation/screen/CycleEditorScreen.kt",
            "presentation/screen/RoutineEditorScreen.kt",
        )

        val project = projectRoot()
        for (relative in phoneOnlySources) {
            val commonPath = "shared/src/commonMain/kotlin/com/devil/phoenixproject/$relative"
            val phonePath = "shared/src/phoneMain/kotlin/com/devil/phoenixproject/$relative"
            assertNull(project.resolve(commonPath).takeIf(File::isFile), "$relative must not remain in commonMain")
            assertNotNull(project.resolve(phonePath).takeIf(File::isFile), "$relative must be available in phoneMain")
        }
    }

    @Test
    fun `phone-only animation and reorderable libraries are scoped to phoneMain`() {
        val gradle = projectRoot().resolve("shared/build.gradle.kts").readText()
        val commonMain = blockFrom(gradle, "val commonMain by getting")
        val phoneMain = blockFrom(gradle, "val phoneMain by creating")

        for (dependency in listOf("libs.reorderable", "libs.compottie")) {
            assertNull(dependency.takeIf(commonMain::contains), "$dependency must not be in commonMain")
            assertNotNull(dependency.takeIf(phoneMain::contains), "$dependency must remain in phoneMain")
        }
    }

    @Test
    fun `phone compose resources stay in the resource source set`() {
        val resources = projectRoot().resolve("shared/src/commonMain/composeResources")
        for (relative in listOf(
            "values/strings.xml",
            "files/exercises.json",
            "drawable/phoenix_logo.png",
        )) {
            assertNotNull(resources.resolve(relative).takeIf(File::isFile), "$relative must remain in commonMain resources")
        }
    }

    private fun blockFrom(source: String, marker: String): String {
        val start = source.indexOf(marker)
        check(start >= 0) { "Missing $marker" }
        val open = source.indexOf('{', start)
        check(open >= 0) { "Missing block body for $marker" }
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(start, index + 1)
            }
        }
        error("Unterminated block $marker")
    }

    private fun projectRoot(): File {
        var directory: File? = File(".").absoluteFile
        repeat(8) {
            directory?.let { current ->
                if (File(current, "settings.gradle.kts").isFile) return current
                directory = current.parentFile
            }
        }
        error("Could not locate the project root from ${File(".").absolutePath}")
    }
}
