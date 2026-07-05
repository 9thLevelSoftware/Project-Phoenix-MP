package com.devil.phoenixproject.presentation.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Design-system ratchet: counts banned styling patterns in the shared presentation
 * source tree and asserts they do NOT increase above the recorded baseline.
 *
 * Baselines measured 2026-07-04 on branch ux-audit/phase-0-tokens.
 * Phase 2 sweep tasks will lower these numbers; each merge that lowers a baseline
 * should also update the number and the comment below.
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
        // Baseline 2026-07-05: 37 (down from 375 after Phase 2 task-2.1 shape-token sweep).
        // All 37 remaining are exempt: ≤6dp decor, intentional 0dp flat edges,
        // unmapped mid-values (SetReadyScreen 14dp, SettingsTab 40dp, SmartInsightsTab 7dp).
        val count = countMatches(Regex("""RoundedCornerShape\(\d+\.dp"""))
        assertTrue(
            count <= 37,
            "RoundedCornerShape(N.dp) usages increased: found $count, baseline ≤ 37. " +
                "Use MaterialTheme.shapes or a named shape token instead.",
        )
    }

    @Test
    fun hardcodedBasicColors_doNotIncrease() {
        // Baseline 2026-07-05: 38 (down from 66 after Phase 1 semantic-color sweep).
        // Phase 3 task-3.1: deleted 5 dead component files — those files contained 0 occurrences,
        // so count is unchanged at 38. Phase 2 will sweep semantic replacements to near 0.
        val count = countMatches(Regex("""\bColor\.(White|Black|Red|Green|Gray|LightGray)\b"""))
        assertTrue(
            count <= 38,
            "Hardcoded Color.(White|Black|Red|Green|Gray|LightGray) usages increased: " +
                "found $count, baseline ≤ 38. Use MaterialTheme.colorScheme or Phoenix tokens instead.",
        )
    }
}
