package com.devil.phoenixproject.util

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Locks the shared iOS security-scoped access helper and the four callers.
 * The helper itself is Kotlin/Native and does not run on the host.
 */
class IosSecurityScopedAccessContractTest {

    @Test
    fun helperStartsThenStopsOnlyWhenAccessWasGranted() {
        val source = requireSource("src/iosMain/kotlin/com/devil/phoenixproject/util/SecurityScopedAccess.ios.kt")
        val body = functionBody(source, "internal inline fun <T> NSURL.withSecurityScopedAccess")

        val startAt = body.indexOf("startAccessingSecurityScopedResource()")
        val blockAt = body.indexOf("block()")
        val stopAt = body.indexOf("stopAccessingSecurityScopedResource()")
        assertTrue(startAt >= 0 && blockAt > startAt, "access starts before the block")
        assertTrue(stopAt > blockAt, "access stops after the block")
        assertTrue(body.contains("if (accessing)"), "stop runs only when start returned true")
        assertTrue(body.contains("finally"), "stop runs on return and on throw")
        assertTrue(!body.contains("catch"), "the helper does not absorb caller error styles")
    }

    @Test
    fun resolverUsesTheHelperAtThreeSites() {
        val source = requireSource(
            "src/iosMain/kotlin/com/devil/phoenixproject/util/BackupDestinationResolver.ios.kt",
        )
        assertEquals(3, countCalls(source), "refresh, accessibility, and write share the helper")
        assertTrue(!source.contains("startAccessingSecurityScopedResource()"), "resolver no longer starts access itself")
        assertTrue(!source.contains("stopAccessingSecurityScopedResource()"), "resolver no longer stops access itself")

        val refresh = functionBody(source, "private suspend fun refreshStaleBookmark")
        val refreshCall = refresh.indexOf("url.withSecurityScopedAccess")
        val refreshReturn = refresh.indexOf("return")
        val refreshCatch = refresh.indexOf("catch (e: Exception)")
        assertTrue(refreshCall >= 0 && refreshReturn > refreshCall && refreshCatch > refreshReturn, "early return stays inside scoped access")
        assertTrue(refresh.contains("Failed to persist refreshed bookmark"), "refresh still logs its own failure")

        val accessible = functionBody(source, "override suspend fun isAccessible")
        assertTrue(accessible.contains("return@withContext false"), "inaccessible path still returns false")
        assertTrue(accessible.contains("log.e(e) { \"isAccessible failed"), "accessibility still logs its own failure")
        assertTrue(accessible.contains("false"), "accessibility still returns false on failure")

        val write = functionBody(source, "override suspend fun writeFile")
        assertTrue(write.contains("return@withContext Result.failure"), "write failures still leave the coroutine")
        assertTrue(write.contains("Result.failure(e)"), "write still wraps exceptions as Result.failure")
        assertTrue(write.contains("Result.success(destPath)"), "successful writes still return the path")
    }

    @Test
    fun directoryPickerUsesTheHelper() {
        val source = requireSource("src/iosMain/kotlin/com/devil/phoenixproject/util/BackupLocationPicker.ios.kt")
        val body = functionBody(source, "actual fun LaunchDirectoryPicker")

        assertEquals(1, countCalls(source), "the picker has one scoped-access site")
        assertTrue(!source.contains("startAccessingSecurityScopedResource()"), "picker no longer starts access itself")
        assertTrue(!source.contains("stopAccessingSecurityScopedResource()"), "picker no longer stops access itself")

        val callAt = body.indexOf("url.withSecurityScopedAccess")
        val bookmarkAt = body.indexOf("createBookmarkedDestination(url)")
        val pickedAt = body.indexOf("onDirectoryPicked(result)")
        assertTrue(callAt >= 0 && bookmarkAt > callAt && pickedAt > bookmarkAt, "bookmark creation stays inside scoped access")
    }

    private fun countCalls(source: String): Int = Regex("url\\.withSecurityScopedAccess").findAll(source).count()

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
