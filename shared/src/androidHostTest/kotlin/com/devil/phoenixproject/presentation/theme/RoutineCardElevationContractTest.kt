package com.devil.phoenixproject.presentation.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Contract test for the `RoutineCard` 2.dp / 8.dp elevation flicker under
 * Dark + Material You (issue #640).
 *
 * The RCA traces the per-card "flicker" the reporter sees on expand/collapse to
 * `Card(defaultElevation = if (expanded) 8.dp else 2.dp)` in `RoutinesTab.kt`. With
 * the unclamped dynamic dark scheme, the elevation tint (`surfaceColorAtElevation`)
 * shifts the rendered color from the dynamic `surfaceContainerHighest` toward the
 * light end of the dynamic tonal range, producing an order-of-magnitude luminance
 * change between the 2.dp collapsed and 8.dp expanded states.
 *
 * Once `clampDynamicDarkScheme` rewrites `surfaceContainerHighest` to the static
 * Slate700 ramp, Compose's elevation tint blends a small amount of `primary` into
 * a brand-consistent dark surface — the 2.dp / 8.dp shift is bounded by the
 * elevation step alone, not by the wallpaper palette. The clamp itself is covered
 * by `ClampedDynamicDarkSchemeTest.card surfaceContainerHighest differs from
 * surfaceContainerHigh by less than 6 percent` (which asserts the structural
 * precondition for the ≤ 6% shift acceptance criterion).
 *
 * This test guards the *card call site*: the elevation must remain 2.dp / 8.dp and
 * the elevation logic must remain on `expanded`, so the elevation tint that creates
 * the flicker is well-defined and boundable.
 */
class RoutineCardElevationContractTest {

    private val projectRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "shared/src/commonMain").exists()) {
            dir = dir.parentFile ?: break
        }
        dir
    }

    private fun read(relativePath: String): String =
        File(projectRoot, relativePath).readText()

    private val routinesSource: String by lazy {
        read("shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation/screen/RoutinesTab.kt")
    }

    @Test
    fun routineCard_elevationIsTwoOrEightDp() {
        // The collapsed state must be 2.dp and the expanded state must be 8.dp.
        // These exact values are the precondition for the ≤ 6% luminance shift
        // acceptance criterion (see RCA for issue #640, section 5/6).
        assertTrue(
            routinesSource.contains("defaultElevation = if (expanded) 8.dp else 2.dp"),
            "RoutineCard in RoutinesTab.kt must set defaultElevation = " +
                "if (expanded) 8.dp else 2.dp so the elevation tint shift is " +
                "bounded by a 6-dp step (acceptance criterion). If changed, " +
                "the per-card flicker invariant no longer holds even with the clamp.",
        )
    }

    @Test
    fun routineCard_usesCardDefaultsCardElevation() {
        // The elevation must flow through `CardDefaults.cardElevation(...)`, which is
        // the documented entry point for the 2.dp/8.dp lint baseline. Hardcoded
        // `Card(...)` constructors or `Modifier.shadow(...)` would route around the
        // `surfaceColorAtElevation` tint that the clamp composes against.
        assertTrue(
            routinesSource.contains("CardDefaults.cardElevation"),
            "RoutineCard elevation in RoutinesTab.kt must use CardDefaults.cardElevation " +
                "so the elevation tint flows through ColorScheme.surfaceColorAtElevation " +
                "— the same entry point the clamp composes against.",
        )
    }
}