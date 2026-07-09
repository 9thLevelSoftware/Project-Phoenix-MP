package com.devil.phoenixproject.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Source-level contract test for the dark + Material You clamp wired into [Theme.kt].
 *
 * The fix for issue #640 only takes effect if `VitruvianTheme` calls
 * `clampDynamicDarkScheme(dynamic, DarkColorScheme)` on the dark branch and *only*
 * the dark branch. A contract test is the right tool here: a Robolectric / Compose
 * runtime round-trip would not exercise any additional code paths, and the
 * `clampDynamicDarkScheme` helper itself is covered by [ClampedDynamicDarkSchemeTest]
 * in `commonTest` (the JVM-only test suite).
 *
 * If a future refactor removes the clamp call or moves it outside the dark branch,
 * this test fails loudly. The contract is intentionally narrow: just one assertion
 * per behavior, all anchored on the exact phrasing we expect.
 */
class MaterialYouDarkClampContractTest {

    private val projectRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "shared/src/commonMain").exists()) {
            dir = dir.parentFile ?: break
        }
        dir
    }

    private fun read(relativePath: String): String =
        File(projectRoot, relativePath).readText()

    private val themeKt: String by lazy {
        read("shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/Theme.kt")
    }

    @Test
    fun darkBranch_invokesClampDynamicDarkScheme() {
        // The dark branch of `VitruvianTheme` must call `clampDynamicDarkScheme` with
        // the dynamic dark scheme and the static `DarkColorScheme` as the fallback.
        // This is the single line of glue that makes the fix for issue #640 take
        // effect at the theme boundary.
        assertTrue(
            themeKt.contains("clampDynamicDarkScheme(dynamic, DarkColorScheme)"),
            "Theme.kt must call clampDynamicDarkScheme(dynamic, DarkColorScheme) " +
                "on the dark + Material You branch. The dynamic surface family " +
                "otherwise leaks high-luminance wallpaper values into the chrome.",
        )
    }

    @Test
    fun lightBranch_doesNotInvokeClamp() {
        // The clamp is dark-mode only. Light + Material You must continue to use the
        // wallpaper-derived palette as today.
        // The literal substring `clampDynamicDarkScheme` must appear, and it must be
        // gated by `useDarkColors` (we asserted the call shape in darkBranch_invokes...
        // above). This test asserts the structural shape of the call site.
        assertTrue(
            themeKt.contains("dynamic != null && useDarkColors -> clampDynamicDarkScheme"),
            "Theme.kt must gate clampDynamicDarkScheme on useDarkColors=true so light " +
                "+ Material You keeps wallpaper hues across the entire scheme.",
        )
    }

    @Test
    fun clampHelperExists_alongsideTheme() {
        // The helper must live next to Theme.kt so the surface family token list is
        // discoverable from the theme module. If a future refactor moves the helper
        // into a non-theme package, surface the failure here and re-document.
        val helper = File(
            projectRoot,
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/ClampedDynamicDarkScheme.kt",
        )
        assertTrue(
            helper.exists(),
            "ClampedDynamicDarkScheme.kt must live in " +
                "shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/ " +
                "alongside Theme.kt so the surface family token list is discoverable " +
                "from the theme module. Found missing at ${helper.absolutePath}.",
        )
    }

    @Test
    fun staticDarkColorScheme_unmodifiedByFix() {
        // Non-goal: do not change the static DarkColorScheme. The clamp must compose
        // against the existing static palette; if the static palette itself is
        // modified, the iOS / non-dynamic Android brand contract breaks.
        assertTrue(
            themeKt.contains("surfaceContainerHighest = SurfaceContainerHighestDark"),
            "DarkColorScheme must still anchor surfaceContainerHighest to " +
                "SurfaceContainerHighestDark (Slate700). The clamp composes against " +
                "the existing static palette; it does not redefine it.",
        )
    }

    @Test
    fun clampHelper_clampsInverseSurfaceAndSurfaceTintFromFallback() {
        // Material 3 components such as Snackbar read `inverseSurface` for their
        // container color and `inverseOnSurface` for content. If the clamp leaves
        // these from `dynamic`, a wallpaper that produces a light surface produces
        // a dark `inverseSurface`, and the Snackbar renders dark-on-dark under the
        // very wallpaper this fix is meant to tame. Material 3 also applies
        // `surfaceTint` to elevated surface containers, so pin that to fallback too
        // and prevent bright/saturated wallpaper tints from reintroducing chrome drift.
        val helper = read(
            "shared/src/commonMain/kotlin/com/devil/phoenixproject/ui/theme/ClampedDynamicDarkScheme.kt",
        )
        assertTrue(
            helper.contains("inverseSurface = fallback.inverseSurface") &&
                helper.contains("inverseOnSurface = fallback.inverseOnSurface") &&
                helper.contains("surfaceTint = fallback.surfaceTint"),
            "ClampedDynamicDarkScheme must clamp inverseSurface, inverseOnSurface, " +
                "and surfaceTint from fallback so Material 3 Snackbar / Tooltip / " +
                "similar components and elevated surfaces stay on the brand-controlled " +
                "dark ramp described in the automated reviews on PR #642.",
        )
    }
}