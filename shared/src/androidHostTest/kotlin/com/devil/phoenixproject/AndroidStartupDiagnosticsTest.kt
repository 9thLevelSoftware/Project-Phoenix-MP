package com.devil.phoenixproject

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
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

        assertTrue(shareAndroidStartupDiagnostics(context, failure(IllegalStateException("private")), "Diagnose teilen"))

        val chooser = launched.single()
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertEquals("Diagnose teilen", chooser.getCharSequenceExtra(Intent.EXTRA_TITLE))
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
        assertFalse(shareAndroidStartupDiagnostics(context, failure(IllegalStateException()), "Share"))
    }

    @Test
    fun sharedReportUsesInstalledVersionAndLongBuildCode() {
        assertInstalledVersion("2.4.0-store", 4_294_967_305L)
    }

    @Test
    @Config(sdk = [26])
    fun sharedReportUsesInstalledVersionOnApi26() {
        assertInstalledVersion("2.4.0-legacy", 123L)
    }

    @Test
    fun packageLookupFailureStillSharesWithExplicitlyUnknownBuild() {
        val launched = mutableListOf<Intent>()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getPackageName(): String = "missing.startup.diagnostics.package"
            override fun startActivity(intent: Intent) { launched += intent }
        }

        assertTrue(shareAndroidStartupDiagnostics(context, failure(IllegalStateException("private")), "Share"))

        val report = sharedReport(launched.single())
        assertTrue(report.contains("App version: UNKNOWN (build UNKNOWN)"), report)
        assertTrue(report.contains("Diagnostic code: STARTUP_INITIALIZATION_FAILED"), report)
        assertFalse(report.contains("private"), report)
    }

    @Suppress("DEPRECATION")
    private fun assertInstalledVersion(versionName: String, versionCode: Long) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val info = PackageInfo().apply {
            packageName = app.packageName
            this.versionName = versionName
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                longVersionCode = versionCode
            } else {
                this.versionCode = versionCode.toInt()
            }
        }
        shadowOf(app.packageManager).installPackage(info)
        val launched = mutableListOf<Intent>()
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { launched += intent }
        }

        assertTrue(shareAndroidStartupDiagnostics(context, failure(IllegalStateException()), "Share"))

        assertEquals(
            "App version: $versionName (build $versionCode)",
            sharedReport(launched.single()).lineSequence().first { it.startsWith("App version:") },
        )
    }

    @Suppress("DEPRECATION")
    private fun sharedReport(chooser: Intent): String =
        chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!.getStringExtra(Intent.EXTRA_TEXT)!!

    private fun failure(cause: Throwable) = StartupDependencyResolution.Failed(
        diagnosticCode = "STARTUP_INITIALIZATION_FAILED",
        retryAllowed = true,
        cause = cause,
        stage = StartupStage.STARTUP_DEPENDENCIES,
    )
}
