package com.devil.phoenixproject.presentation.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReduceMotionRefreshContractTest {

    private val projectRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "shared/src/commonMain").exists()) {
            dir = dir.parentFile ?: break
        }
        dir
    }

    private fun androidSettings(): String = File(
        projectRoot,
        "shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/util/PlatformAccessibilitySettings.android.kt",
    ).readText()

    @Test
    fun animatorDurationScale_isRereadOnResumeAndSettingsObserver() {
        val source = androidSettings()
        assertTrue(
            source.contains("Settings.Global.ANIMATOR_DURATION_SCALE"),
            "Reduce motion must still come from ANIMATOR_DURATION_SCALE.",
        )
        assertTrue(
            source.contains("Lifecycle.Event.ON_RESUME"),
            "ANIMATOR_DURATION_SCALE must be re-read on resume.",
        )
        assertTrue(
            source.contains("registerContentObserver"),
            "ANIMATOR_DURATION_SCALE must be re-read from a settings ContentObserver.",
        )
        assertTrue(
            source.contains("Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE)"),
            "The observer must watch the animator duration scale URI.",
        )
        assertTrue(
            source.contains("Looper.getMainLooper()"),
            "Settings observer callbacks must land on the main looper before Compose state is updated.",
        )
        assertTrue(
            source.contains("LaunchedEffect(config)"),
            "Configuration changes must still re-read ANIMATOR_DURATION_SCALE.",
        )
        assertFalse(
            source.contains("return remember(config)"),
            "Reduce motion must not be cached only across configuration changes.",
        )
        val reads = Regex("""readReduceMotion\(""").findAll(source).count()
        assertEquals(
            4,
            reads,
            "Expected the initial read plus configuration, resume, and observer re-reads.",
        )
    }

    @Test
    fun boldText_staysTiedToFontWeightAdjustment() {
        val source = androidSettings()
        assertTrue(
            source.contains("fontWeightAdjustment"),
            "Bold text must still follow configuration font weight.",
        )
    }

    @Test
    fun reduceMotion_isAnimatorDurationScaleZero() {
        assertTrue(reduceMotionFromAnimatorDurationScale(0f))
        assertFalse(reduceMotionFromAnimatorDurationScale(1f))
        assertFalse(reduceMotionFromAnimatorDurationScale(0.5f))
        assertFalse(reduceMotionFromAnimatorDurationScale(2f))
    }
}
