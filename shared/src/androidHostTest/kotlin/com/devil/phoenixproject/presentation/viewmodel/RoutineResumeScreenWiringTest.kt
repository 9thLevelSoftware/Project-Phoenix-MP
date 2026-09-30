package com.devil.phoenixproject.presentation.viewmodel

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoutineResumeScreenWiringTest {
    @Test
    fun `all resume entry screens delegate to the shared dialog host without direct BLE or mutable-id loading`() {
        val screenRoot = findWorkspaceRoot().resolve(
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen",
        )
        val host = screenRoot.resolve("RoutineResumeDialogHost.kt").readText()
        assertTrue("runRoutineResumeUiOperation(" in host)
        assertTrue("RoutineResumeActionAuthority(" in host)
        assertTrue("isRoutineResumeProfileCurrent(" in host)
        assertTrue("activeProfileContext.collectAsState()" in host)
        assertTrue("LaunchedEffect(activeProfileContext)" in host)
        assertTrue("authority.tokenIsCurrent()" in host)
        assertTrue("authority.contextIsCurrent()" in host)
        assertTrue("classifyRoutineResumeCompletion(" in host)
        assertTrue("runFreshCycleUiOperation(" in host)
        assertTrue(
            "resumeOperationGate.currentToken == selectionToken" in host,
            "Daily discovery must stay on the token check",
        )
        assertTrue(
            "authority.isCurrent()" in host,
            "Cycle discovery must stay on the profile-aware authority check",
        )

        val forbidden = listOf(
            "viewModel.resumeRoutine(",
            "viewModel.discardRoutineResume(",
            "viewModel.ensureConnection(",
            "viewModel.loadRoutineFromCycleAsync(",
            "viewModel.loadRoutineAsync(",
            "routineResumeUiDecision(",
            "routineResumeDiscardUiDecision(",
        )
        forbidden.forEach { call ->
            assertFalse(call in host, "RoutineResumeDialogHost must not call $call directly")
        }

        val screens = listOf(
            Triple("DailyRoutinesScreen.kt", "DAILY_ROUTINES", "launchDailyRoutine("),
            Triple("HomeScreen.kt", "HOME_CYCLE", "launchCycleRoutine("),
            Triple("TrainingCyclesScreen.kt", "TRAINING_CYCLES", "launchCycleRoutine("),
        )
        screens.forEach { (fileName, expectedEntryPoint, launchCall) ->
            val source = screenRoot.resolve(fileName).readText()
            assertTrue("RoutineResumeDialogHost(" in source, fileName)
            assertTrue("rememberRoutineResumeLauncher()" in source, fileName)
            assertTrue(launchCall in source, "$fileName must start resume via $launchCall")
            assertTrue(
                "entryPoint = RoutineResumeEntryPoint.$expectedEntryPoint" in source,
                "$fileName must bind the shared host to $expectedEntryPoint",
            )
            assertFalse("runRoutineResumeUiOperation(" in source, fileName)
            assertFalse("pendingResumeHandle" in source, fileName)
            assertFalse("ResumeRoutineDialog(" in source, fileName)
            assertFalse("runFreshCycleUiOperation(" in source, fileName)
            forbidden.forEach { call ->
                assertFalse(call in source, "$fileName must not call $call directly")
            }
        }

        val daily = screenRoot.resolve("DailyRoutinesScreen.kt").readText()
        val home = screenRoot.resolve("HomeScreen.kt").readText()
        val cycles = screenRoot.resolve("TrainingCyclesScreen.kt").readText()
        assertTrue("onNavigateOverview" in daily)
        assertTrue("NavigationRoutes.RoutineOverview.route" in daily)
        assertFalse("launchCycleRoutine(" in daily)
        assertFalse("onNavigateOverview" in home)
        assertFalse("onConnectionFailed" in home)
        assertFalse("launchDailyRoutine(" in home)
        assertTrue("onConnectionFailed" in cycles)
        assertTrue("onWorkoutLoadFailed" in cycles)
        assertTrue("CONNECTION_FAILED_MESSAGE" in cycles)
        assertTrue("WORKOUT_LOAD_FAILED_MESSAGE" in cycles)
        assertFalse("launchDailyRoutine(" in cycles)
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
