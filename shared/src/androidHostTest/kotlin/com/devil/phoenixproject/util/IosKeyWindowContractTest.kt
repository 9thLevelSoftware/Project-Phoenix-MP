package com.devil.phoenixproject.util

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Locks the shared iOS key-window lookup and the two callers that used to walk scenes themselves.
 * The lookup is Kotlin/Native and does not run on the host.
 */
class IosKeyWindowContractTest {

    @Test
    fun keyWindowIsTheFirstConnectedWindowScene() {
        val source = requireSource(
            "src/iosMain/kotlin/com/devil/phoenixproject/util/KeyWindowRootViewController.ios.kt",
        )
        val body = functionBody(source, "internal fun keyWindow()")

        val scenesAt = body.indexOf("UIApplication.sharedApplication.connectedScenes")
        val firstSceneAt = body.indexOf("firstOrNull")
        assertTrue(scenesAt >= 0 && firstSceneAt > scenesAt, "the helper still takes the first connected scene")
        assertTrue(body.contains("it is UIWindowScene"), "only a window scene is a candidate")
        assertTrue(body.contains("return windowScene?.keyWindow"), "the helper returns that scene's key window")
        assertTrue(
            functionBody(source, "internal fun keyWindowRootViewController()").contains(
                "return keyWindow()?.rootViewController",
            ),
            "document pickers still present from the key window's root controller",
        )
    }

    @Test
    fun oauthAnchorUsesKeyWindowAndNamesTheHelperFile() {
        val source = requireSource("src/iosMain/kotlin/com/devil/phoenixproject/data/auth/OAuth.ios.kt")
        val body = functionBody(source, "override fun presentationAnchorForWebAuthenticationSession")

        assertTrue(body.contains("return keyWindow() ?: UIWindow()"), "missing key window still falls back to a new UIWindow")
        assertTrue(source.contains("KeyWindowRootViewController.ios.kt"), "the comment names the helper file")
        assertTrue(!source.contains("CsvExporter.ios.kt"), "the comment no longer names the CSV exporter")
        assertTrue(!body.contains("connectedScenes"), "OAuth no longer walks scenes itself")
    }

    @Test
    fun shareSheetPresentsFromKeyWindowRoot() {
        val source = requireSource("src/iosMain/kotlin/com/devil/phoenixproject/util/CrashLog.ios.kt")
        val body = functionBody(source, "internal fun presentShareSheet")

        assertTrue(
            body.contains("keyWindow()?.rootViewController"),
            "the share sheet still presents from the key window's root controller",
        )
        assertTrue(body.contains("onNotShown()"), "a missing window still skips the sheet")
        assertTrue(!body.contains("connectedScenes"), "the share sheet no longer walks scenes itself")
    }

    private fun requireSource(relativePath: String): String =
        readProjectFile(relativePath) ?: fail("Missing $relativePath")

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "Missing $signature")
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        fail("Unclosed $signature")
    }
}
