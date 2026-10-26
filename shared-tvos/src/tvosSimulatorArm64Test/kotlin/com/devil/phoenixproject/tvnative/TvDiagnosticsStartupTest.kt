package com.devil.phoenixproject.tvnative

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.devil.phoenixproject.di.doInitTvosKoin
import com.devil.phoenixproject.util.DeviceInfo
import com.devil.phoenixproject.util.shareCrashReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TvDiagnosticsStartupTest {
    @Test
    fun diagnosticsAreInstalledBeforeFirstStartupLogAndKoinOnEveryInitialization() {
        val previousSeverity = Logger.config.minSeverity
        val previousWriters = Logger.config.logWriterList
        val expected = if (DeviceInfo.isDebugBuild) Severity.Debug else Severity.Warn
        val observed = mutableListOf<Severity>()
        var initialized = 0
        Logger.setLogWriters(object : LogWriter() {
            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                if (message.contains("KOIN INITIALIZATION")) observed += Logger.config.minSeverity
            }
        })
        try {
            repeat(2) {
                Logger.mutableConfig.minSeverity = Severity.Verbose
                doInitTvosKoin {
                    assertEquals(expected, Logger.config.minSeverity, "Diagnostics must precede Koin")
                    initialized++
                }
            }
            assertEquals(2, initialized)
            assertTrue(observed.all { it == expected }, "Even the first startup log must use the installed floor")
            if (DeviceInfo.isDebugBuild) assertEquals(4, observed.size)
        } finally {
            Logger.mutableConfig.minSeverity = previousSeverity
            Logger.setLogWriters(previousWriters)
        }
    }

    @Test
    fun unavailableSharingDoesNotAcknowledgePresentation() {
        var shown = false
        shareCrashReport("test report") { shown = true }
        assertFalse(shown, "Unavailable tvOS sharing must not claim it displayed a sheet")
    }
}
