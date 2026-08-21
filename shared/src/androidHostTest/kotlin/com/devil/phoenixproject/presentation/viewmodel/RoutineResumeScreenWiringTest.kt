package com.devil.phoenixproject.presentation.viewmodel

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutineResumeScreenWiringTest {
    @Test
    fun `all resume entry screens use the shared authority runner without direct BLE or mutable-id loading`() {
        val screenRoot = findWorkspaceRoot().resolve(
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen",
        )
        val screens = listOf(
            Triple("DailyRoutinesScreen.kt", "DAILY_ROUTINES", false),
            Triple("HomeScreen.kt", "HOME_CYCLE", true),
            Triple("TrainingCyclesScreen.kt", "TRAINING_CYCLES", true),
        )

        screens.forEach { (fileName, expectedEntryPoint, isCycleEntry) ->
            val source = screenRoot.resolve(fileName).readText()
            assertTrue("runRoutineResumeUiOperation(" in source, fileName)
            assertTrue("RoutineResumeActionAuthority(" in source, fileName)
            assertTrue("isRoutineResumeProfileCurrent(" in source, fileName)
            assertTrue("activeProfileContext.collectAsState()" in source, fileName)
            assertTrue("LaunchedEffect(activeProfileContext)" in source, fileName)
            assertTrue("authority.tokenIsCurrent()" in source, fileName)
            assertTrue("authority.contextIsCurrent()" in source, fileName)
            assertTrue("classifyRoutineResumeCompletion(" in source, fileName)
            assertTrue(
                "entryPoint = RoutineResumeEntryPoint.$expectedEntryPoint" in source,
                "$fileName must bind the shared runner to $expectedEntryPoint",
            )
            if (isCycleEntry) {
                assertTrue("runFreshCycleUiOperation(" in source, fileName)
            }
            listOf(
                "viewModel.resumeRoutine(",
                "viewModel.discardRoutineResume(",
                "viewModel.ensureConnection(",
                "viewModel.loadRoutineFromCycleAsync(",
                "viewModel.loadRoutineAsync(",
                "routineResumeUiDecision(",
                "routineResumeDiscardUiDecision(",
            ).forEach { forbidden ->
                assertFalse(forbidden in source, "$fileName must not call $forbidden directly")
            }
        }
    }

    private fun findWorkspaceRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(8) {
            if (Files.isDirectory(current.resolve("shared/src/commonMain"))) return current
            current = current.parent ?: return@repeat
        }
        error("Could not locate workspace root from ${System.getProperty("user.dir")}")
    }
}
