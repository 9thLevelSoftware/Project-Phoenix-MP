package com.devil.phoenixproject.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Behavior contract guarding the rendered chrome luminance invariant under
 * Dark + Material You (issue #640).
 *
 * Production chrome call sites read their high-visibility container colors through
 * the helpers in `PhoenixChromeColorContracts.kt`. These tests assert those helpers
 * forward the runtime [ColorScheme] roles that `clampDynamicDarkScheme` rewrites,
 * without reading physical source files or matching fragile formatting-sensitive
 * strings in `.kt` files.
 */
class RoutinesChromeLuminanceContractTest {

    private fun chromeScheme(): ColorScheme = darkColorScheme(
        surface = Color(0xFF101820),
        surfaceContainerHigh = Color(0xFF1E293B),
        surfaceContainerHighest = Color(0xFF334155),
        primaryContainer = Color(0xFF7C2D12),
    )

    @Test
    fun topAppBar_containerColor_readsColorSchemeSurface() {
        val scheme = chromeScheme().copy(surface = Color(0xFF123456))

        assertEquals(
            scheme.surface,
            phoenixTopAppBarContainerColor(scheme),
            "Top app bar container color must read ColorScheme.surface so the " +
                "dark+Material You clamp propagates through.",
        )
    }

    @Test
    fun bottomNavigation_containerColor_readsColorSchemeSurfaceContainerHigh() {
        val scheme = chromeScheme().copy(surfaceContainerHigh = Color(0xFF234567))

        assertEquals(
            scheme.surfaceContainerHigh,
            phoenixBottomNavigationContainerColor(scheme),
            "Bottom navigation container color must read ColorScheme.surfaceContainerHigh " +
                "so the dark+Material You clamp propagates through.",
        )
    }

    @Test
    fun routineCard_unselectedContainerColor_readsColorSchemeSurfaceContainerHighest() {
        val scheme = chromeScheme().copy(surfaceContainerHighest = Color(0xFF345678))

        assertEquals(
            scheme.surfaceContainerHighest,
            routineCardContainerColor(scheme, isSelected = false),
            "Unselected RoutineCard container color must read " +
                "ColorScheme.surfaceContainerHighest so the dark+Material You clamp propagates through.",
        )
    }

    @Test
    fun routineCard_selectedContainerColor_preservesPrimaryContainerSelectionTint() {
        val scheme = chromeScheme().copy(primaryContainer = Color(0xFF456789))

        assertEquals(
            scheme.primaryContainer.copy(alpha = 0.4f),
            routineCardContainerColor(scheme, isSelected = true),
            "Selected RoutineCard container color must remain the primaryContainer " +
                "selection tint, not a hardcoded surface color.",
        )
    }

    @Test
    fun chromeHelpers_areDrivenByInputSchemeNotHardcodedLightColors() {
        val darkScheme = chromeScheme()
        val lightSentinel = Color.White

        val returnedColors = listOf(
            phoenixTopAppBarContainerColor(darkScheme),
            phoenixBottomNavigationContainerColor(darkScheme),
            routineCardContainerColor(darkScheme, isSelected = false),
        )

        returnedColors.forEach { color ->
            assertNotEquals(
                lightSentinel,
                color,
                "Chrome helper returned Color.White for a dark scheme; this would " +
                    "bypass the issue #640 dark-surface clamp.",
            )
            assertTrue(
                color != Color(0xFFFFFFFF),
                "Chrome helper returned a hardcoded white Color literal for a dark scheme; " +
                    "it must be driven by the runtime ColorScheme instead.",
            )
        }
    }
}
