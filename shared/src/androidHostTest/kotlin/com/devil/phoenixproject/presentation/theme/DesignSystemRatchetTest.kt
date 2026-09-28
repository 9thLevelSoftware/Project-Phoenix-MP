package com.devil.phoenixproject.presentation.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Design-system ratchet: counts ad-hoc styling in the shared presentation
 * source tree and fails if a count rises above its ceiling.
 *
 * Placement: androidHostTest — uses java.io.File which is JVM-only, consistent with
 * ThemeModeUiContractGuardTest in the same package.
 */
class DesignSystemRatchetTest {

    /**
     * Resolve the repo root by walking up from user.dir until a directory containing
     * settings.gradle.kts (or .git) is found. Fails loudly if not found so a broken
     * path never silently counts 0 matches and passes.
     */
    private val presentationDir: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (
            !File(dir, "settings.gradle.kts").exists() &&
            !File(dir, ".git").exists() &&
            dir.parentFile != null
        ) {
            dir = dir.parentFile!!
        }
        File(
            dir,
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/presentation",
        )
    }

    private fun countMatches(regex: Regex): Int {
        require(presentationDir.isDirectory) {
            "Presentation directory not found at ${presentationDir.absolutePath}. " +
                "Path resolution is broken — ratchet counts cannot be trusted."
        }
        return presentationDir
            .walkTopDown()
            .filter { it.extension == "kt" }
            .sumOf { f -> regex.findAll(f.readText()).count() }
    }

    @Test
    fun presentationDir_exists() {
        assertTrue(
            presentationDir.exists(),
            "Presentation directory not found at ${presentationDir.absolutePath}. " +
                "Path resolution may be broken — check user.dir root-walking logic.",
        )
    }

    @Test
    fun rawRoundedCornerShapes_doNotIncrease() {
        // Ceiling stops new ad-hoc RoundedCornerShape(N.dp) values from creeping in.
        val count = countMatches(Regex("""RoundedCornerShape\(\d+\.dp"""))
        assertTrue(
            count <= 27,
            "RoundedCornerShape(N.dp) usages increased: found $count, baseline ≤ 27. " +
                "Use MaterialTheme.shapes or a named shape token instead.",
        )
    }

    @Test
    fun hardcodedBasicColors_doNotIncrease() {
        // Ceiling stops new ad-hoc Color.White/Black/Red/Green/Gray/LightGray literals from creeping in.
        val count = countMatches(Regex("""\bColor\.(White|Black|Red|Green|Gray|LightGray)\b"""))
        assertTrue(
            count <= 26,
            "Hardcoded Color.(White|Black|Red|Green|Gray|LightGray) usages increased: " +
                "found $count, baseline ≤ 26. Use MaterialTheme.colorScheme or Phoenix tokens instead.",
        )
    }
}
