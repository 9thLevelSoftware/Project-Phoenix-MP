package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashLogTest {

    private class InMemoryStore(var content: String? = null) : CrashLogStore {
        var deletes = 0
        override fun read(): String? = content
        override fun write(report: String) {
            content = report
        }
        override fun delete() {
            deletes++
            content = null
        }
    }

    @Test
    fun formatReportIncludesVersionPlatformTimestampAndStackTrace() {
        val cause = IllegalStateException("root cause")
        val report = CrashLog.formatReport(
            throwable = RuntimeException("boom", cause),
            appVersion = "1.2.3",
            platform = "TestOS 9",
            timestampMillis = 0L,
        )

        assertTrue(report.contains("App version: 1.2.3"), report)
        assertTrue(report.contains("Platform: TestOS 9"), report)
        assertTrue(report.contains("1970-01-01T00:00:00Z"), report)
        assertTrue(report.contains("boom"), report)
        assertTrue(report.contains("root cause"), report)
    }

    @Test
    fun formatReportIsCappedForSharing() {
        val report = CrashLog.formatReport(
            throwable = RuntimeException("x".repeat(CrashLog.MAX_REPORT_CHARS * 2)),
            appVersion = "1",
            platform = "p",
            timestampMillis = 0L,
        )

        assertEquals(CrashLog.MAX_REPORT_CHARS, report.length)
    }

    @Test
    fun recordedReportIsPresentedUntilDiscardedThenNeverAgain() {
        val store = InMemoryStore()
        CrashLog.record(store, RuntimeException("boom"), "1.2.3", "p", 0L)

        val first = CrashLog.pending(store)
        assertTrue(first!!.contains("boom"))
        // Not answered yet (e.g. app killed with the dialog open): offered again.
        assertEquals(first, CrashLog.pending(store))

        CrashLog.discard(store)

        assertEquals(1, store.deletes)
        assertNull(CrashLog.pending(store))
    }

    @Test
    fun blankOrMissingFileIsNotPresented() {
        assertNull(CrashLog.pending(InMemoryStore(content = "  \n")))
        assertNull(CrashLog.pending(InMemoryStore(content = null)))
        assertNull(CrashLog.pending(null))
    }

    @Test
    fun recordNeverThrowsWhenTheStoreFails() {
        val failing = object : CrashLogStore {
            override fun read(): String? = error("read failed")
            override fun write(report: String) = error("disk full")
            override fun delete() = error("delete failed")
        }

        CrashLog.record(failing, RuntimeException("boom"), "1", "p", 0L)
        assertNull(CrashLog.pending(failing))
        CrashLog.discard(failing)
    }
}
