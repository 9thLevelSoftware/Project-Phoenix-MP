package com.devil.phoenixproject

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidStartupDiagnosticsTest {
    @Test
    fun reportKeepsNestedCauseTypesAndCodeLocationsButOmitsMessagesAndSuppressedErrors() {
        val cause = IllegalArgumentException("Bearer secret; workout=private; person@example.com")
        cause.stackTrace = arrayOf(StackTraceElement("storage.Reader", "open", "Reader.kt", 42))
        cause.addSuppressed(IllegalStateException("suppressed private data"))
        val failure = failure(IllegalStateException("Koin private settings", cause))

        val report = formatAndroidStartupDiagnostics(failure, "1.0.9", "Android test")

        assertTrue(report.contains("App version: 1.0.9"))
        assertTrue(report.contains("Stage: STARTUP_DEPENDENCIES"))
        assertTrue(report.contains("Cause 2: java.lang.IllegalArgumentException"))
        assertTrue(report.contains("storage.Reader.open(Reader.kt:42)"))
        for (secret in listOf("Bearer", "secret", "workout=", "person@example.com", "suppressed private data", "Koin private settings")) {
            assertFalse(report.contains(secret), report)
        }
    }

    @Test
    fun cyclicAndLongCauseChainsAreBounded() {
        val first = IllegalStateException()
        val second = IllegalArgumentException(first)
        first.initCause(second)
        val cyclic = formatAndroidStartupDiagnostics(failure(first), "1", "test")
        assertTrue(cyclic.contains("Cause 2:"))
        assertFalse(cyclic.contains("Cause 3:"))
        assertTrue(cyclic.contains("Cause chain truncated."))

        val longChain = (1..40).fold< Int, Throwable>(IllegalStateException()) { nested, _ -> IllegalStateException(nested) }
        val bounded = formatAndroidStartupDiagnostics(failure(longChain), "1", "test")
        assertTrue(bounded.contains("Cause 32:"))
        assertFalse(bounded.contains("Cause 33:"))
    }

    @Test
    fun sharePresentsOnlyTextToTheSystemChooser() {
        val launched = mutableListOf<Intent>()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun startActivity(intent: Intent) { launched += intent }
        }

        assertTrue(shareAndroidStartupDiagnostics(context, failure(IllegalStateException("private"))))

        val chooser = launched.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertTrue(send.getStringExtra(Intent.EXTRA_TEXT)!!.contains("Project Phoenix startup diagnostics"))
        assertFalse(send.hasExtra(Intent.EXTRA_STREAM))
        assertEquals(null, send.data)
    }

    @Test
    fun missingShareHandlerReturnsFailureWithoutThrowing() {
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        assertFalse(shareAndroidStartupDiagnostics(context, failure(IllegalStateException())))
    }

    private fun failure(cause: Throwable) = StartupDependencyResolution.Failed(
        diagnosticCode = "STARTUP_INITIALIZATION_FAILED",
        retryAllowed = true,
        cause = cause,
        stage = StartupStage.STARTUP_DEPENDENCIES,
    )
}
