package com.devil.phoenixproject.presentation.util

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Structural guard: verifies WeightDisplayFormatter remains in the presentation package.
 *
 * NOTE: Source file scanning guards have been moved to androidHostTest where java.io.File
 * is available. See: androidHostTest/.../WeightDisplayGuardTest.kt
 * Those tests scan actual source files for forbidden imports of WeightDisplayFormatter
 * in data/ble, data/sync, and data/integration packages.
 *
 * This commonTest file retains only the one guard that works via reflection (no file I/O needed).
 */
class WeightDisplayGuardTest {

    @Test
    fun weightDisplayFormatter_isInPresentationPackage() {
        val formatterPackage = WeightDisplayFormatter::class.qualifiedName ?: ""
        assertTrue(
            formatterPackage.contains("presentation"),
            "WeightDisplayFormatter must remain in the presentation layer. " +
                "Current: '$formatterPackage'. " +
                "Cable multiplication for display is a presentation concern only.",
        )
    }
}
