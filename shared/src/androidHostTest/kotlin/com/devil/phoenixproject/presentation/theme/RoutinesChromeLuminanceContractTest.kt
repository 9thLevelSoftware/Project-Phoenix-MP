package com.devil.phoenixproject.presentation.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract test guarding the rendered chrome luminance invariant under
 * Dark + Material You (issue #640).
 *
 * `EnhancedMainScreen` paints:
 *   - the TopAppBar with `MaterialTheme.colorScheme.surface` (line ~335)
 *   - `PhoenixBottomNavigationBar` with `MaterialTheme.colorScheme.surfaceContainerHigh` (line ~500)
 *
 * `RoutinesTab` paints `RoutineCard` with `MaterialTheme.colorScheme.surfaceContainerHighest`
 * (line ~1083). All three read from `MaterialTheme.colorScheme.*`, which is exactly the
 * place the `clampDynamicDarkScheme` helper rewrites under dark + dynamic.
 *
 * This test asserts the *read* sites still go through `MaterialTheme.colorScheme.*`
 * (no hardcoded surface-color overrides crept in). The actual luminance invariant
 * under dark + Material You is proven by `ClampedDynamicDarkSchemeTest`
 * (`clamped chrome surface luminance is below the dark threshold`) plus this
 * source-level contract — together they close the loop without needing a Compose
 * UI-test runtime, which the project does not configure today.
 */
class RoutinesChromeLuminanceContractTest {

    private val projectRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "shared/src/commonMain").exists()) {
            dir = dir.parentFile ?: break
        }
        dir
    }

    private fun read(relativePath: String): String =
        File(projectRoot, relativePath).readText()

    @Test
    fun topAppBar_containerColor_readsFromMaterialTheme() {
        val source = read(
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt",
        )
        // The chrome must read `MaterialTheme.colorScheme.surface`. If someone
        // hardcodes `Color.White` / `Color(0xFF...)` here, the clamp cannot fix the
        // bug and the test fails loudly.
        assertTrue(
            source.contains("containerColor = MaterialTheme.colorScheme.surface") ||
                source.contains("containerColor = MaterialTheme.colorScheme.surface,"),
            "TopAppBar containerColor in EnhancedMainScreen.kt must read from " +
                "MaterialTheme.colorScheme.surface so the dark+Material You clamp " +
                "propagates through. If hardcoded, the surface-luminance invariant " +
                "breaks and the issue #640 bug returns on any wallpaper.",
        )
    }

    @Test
    fun bottomNavigation_containerColor_readsFromMaterialTheme() {
        val source = read(
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt",
        )
        assertTrue(
            source.contains("containerColor = MaterialTheme.colorScheme.surfaceContainerHigh"),
            "PhoenixBottomNavigationBar containerColor in EnhancedMainScreen.kt " +
                "must read from MaterialTheme.colorScheme.surfaceContainerHigh so the " +
                "clamp propagates through. Hardcoded values here reintroduce the " +
                "light bottom-nav symptom of issue #640.",
        )
    }

    @Test
    fun routineCard_containerColor_readsFromMaterialTheme() {
        val source = read(
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutinesTab.kt",
        )
        assertTrue(
            source.contains("MaterialTheme.colorScheme.surfaceContainerHighest"),
            "RoutineCard containerColor in RoutinesTab.kt must read from " +
                "MaterialTheme.colorScheme.surfaceContainerHighest so the clamp " +
                "propagates through. Hardcoded values here reintroduce the light " +
                "expanded-card symptom of issue #640.",
        )
    }

    @Test
    fun noHardcodedSurfaceColorInChrome() {
        // Belt-and-braces: assert that no chrome touchpoint hardcodes a near-white
        // Color literal that would override the MaterialTheme. We allow `Color.White`
        // for `onPrimary` (text/icons on the brand orange), but not for any
        // `containerColor` / `background(...)` modifier on the chrome.
        val source = read(
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/EnhancedMainScreen.kt",
        )
        val routinesSource = read(
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutinesTab.kt",
        )
        // These checks deliberately look only at the file-level strings we already
        // inspected during RCA. They are conservative — a future change that adds
        // a new hardcoded chrome background will need to update this test too,
        // which is the point.
        assertFalse(
            source.contains("containerColor = Color.White"),
            "EnhancedMainScreen.kt must not hardcode containerColor = Color.White " +
                "on the chrome — that defeats the dark+Material You clamp.",
        )
        assertFalse(
            routinesSource.contains("containerColor = Color.White"),
            "RoutinesTab.kt must not hardcode containerColor = Color.White on " +
                "RoutineCard — that defeats the dark+Material You clamp.",
        )
    }
}