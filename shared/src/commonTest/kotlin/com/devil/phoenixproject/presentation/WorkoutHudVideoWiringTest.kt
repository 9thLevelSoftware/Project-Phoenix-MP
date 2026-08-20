package com.devil.phoenixproject.presentation

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Source-level guard for issue #643: active-workout HUD keeps its demo page. */
class WorkoutHudVideoWiringTest {

    @Test
    fun activeHudKeepsVideoPageBetweenMetricsAndStats() {
        val src = readWorkoutHudSource()

        assertTrue(
            src.contains("exerciseRepository: ExerciseRepository") &&
                src.contains("enableVideoPlayback: Boolean"),
            "WorkoutHud must accept demo dependencies instead of dropping the preference/repository.",
        )
        assertTrue(
            src.contains("rememberPagerState(pageCount = { 3 })"),
            "WorkoutHud must expose Metrics, Demo, and Stats pages.",
        )
        assertTrue(
            src.contains("1 -> InstructionPage(") && src.contains("2 -> StatsPage("),
            "WorkoutHud must keep the demo page between metrics and stats.",
        )
        assertTrue(
            !src.contains("Video Playback Disabled") && src.contains("ExerciseDemoImage("),
            "InstructionPage must always render ExerciseDemoImage instead of the disabled-video UI.",
        )
        assertTrue(
            src.contains("selectedExerciseId = workoutParameters.selectedExerciseId") &&
                src.contains("currentExercise?.exercise?.id ?: selectedExerciseId"),
            "InstructionPage must load a selected exercise image when an active workout has no routine.",
        )
        assertTrue(
            src.contains("var images by remember(currentExerciseIndex, exerciseId)"),
            "InstructionPage state must reset when either the routine position or exercise changes.",
        )
    }

    @Test
    fun workoutTabForwardsVideoSettingsIntoActiveHud() {
        val src = readWorkoutTabSource()
        val hudCall = src.substringAfter("WorkoutHud(", missingDelimiterValue = "")
            .substringBefore("RepQualityIndicator", missingDelimiterValue = "")
        assertTrue(hudCall.isNotEmpty(), "WorkoutTab must call WorkoutHud for connected active workouts.")

        assertTrue(
            hudCall.contains("exerciseRepository = exerciseRepository,") &&
                hudCall.contains("enableVideoPlayback = enableVideoPlayback,"),
            "WorkoutTab must forward ExerciseRepository and enableVideoPlayback into WorkoutHud.",
        )
    }

    private fun readWorkoutHudSource(): String {
        val src = readProjectFile("src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutHud.kt")
        assertNotNull(src, "Could not locate WorkoutHud.kt")
        return src
    }

    private fun readWorkoutTabSource(): String {
        val src = readProjectFile("src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/WorkoutTab.kt")
        assertNotNull(src, "Could not locate WorkoutTab.kt")
        return src
    }
}
