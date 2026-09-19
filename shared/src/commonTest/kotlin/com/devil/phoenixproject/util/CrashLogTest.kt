package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            appVersion = "1.2.3 (45)",
            platform = "TestOS 9",
            timestampMillis = 0L,
        )

        assertTrue(report.contains("App version: 1.2.3 (45)"), report)
        assertTrue(report.contains("Platform: TestOS 9"), report)
        assertTrue(report.contains("1970-01-01T00:00:00Z"), report)
        assertTrue(report.contains("boom"), report)
        assertTrue(report.contains("root cause"), report)
    }

    @Test
    fun oversizedReportKeepsHeadAndRootCauseTail() {
        val report = CrashLog.formatReport(
            throwable = RuntimeException("x".repeat(CrashLog.MAX_REPORT_CHARS * 2), IllegalStateException("the real root cause")),
            appVersion = "1",
            platform = "p",
            timestampMillis = 0L,
        )

        assertTrue(report.length <= CrashLog.MAX_REPORT_CHARS, "length ${report.length}")
        assertTrue(report.startsWith("Project Phoenix crash report"))
        assertTrue(report.contains("... truncated "), "missing truncation marker")
        assertTrue(report.contains("the real root cause"), "root cause must survive truncation")
    }

    @Test
    fun reportRedactsTokensAndEmails() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0In0.c2lnbmF0dXJl"
        val report = CrashLog.formatReport(
            throwable = RuntimeException("401 for user jane.doe@example.com, Authorization: Bearer abc123secret, refresh=$jwt"),
            appVersion = "1",
            platform = "p",
            timestampMillis = 0L,
        )

        assertFalse(report.contains("jane.doe@example.com"), report)
        assertFalse(report.contains("abc123secret"), report)
        assertFalse(report.contains(jwt), report)
        assertTrue(report.contains("[redacted]"), report)
        assertTrue(report.contains("401 for user"), report)
    }

    @Test
    fun recordedReportIsPresentedUntilAnsweredThenNeverAgain() {
        val store = InMemoryStore()
        CrashLog.record(store, RuntimeException("boom"), "1.2.3", "p", 0L)

        val first = CrashLog.pending(store)
        assertTrue(first!!.contains("boom"))
        // Not answered yet (e.g. app killed with the dialog open): offered again.
        assertEquals(first, CrashLog.pending(store))

        CrashLog.answer(CrashReportAnswer.NOT_NOW, first, store) { _, _ -> error("must not share") }

        assertEquals(1, store.deletes)
        assertNull(CrashLog.pending(store))
    }

    @Test
    fun shareSendsReportAndDeletesOnlyAfterTheSheetOpened() {
        val store = InMemoryStore(content = "trace")
        val shared = mutableListOf<String>()
        var onShown: (() -> Unit)? = null

        CrashLog.answer(CrashReportAnswer.SHARE, "trace", store) { report, shown ->
            shared += report
            onShown = shown
        }

        assertEquals(listOf("trace"), shared)
        assertEquals("trace", CrashLog.pending(store), "must not delete before the share sheet opened")

        onShown!!.invoke()

        assertNull(CrashLog.pending(store))
    }

    @Test
    fun shareThatNeverOpensKeepsTheReport() {
        val store = InMemoryStore(content = "trace")

        // e.g. no Activity to launch from, or no root view controller: onShown is never called.
        CrashLog.answer(CrashReportAnswer.SHARE, "trace", store) { _, _ -> }

        assertEquals(0, store.deletes)
        assertEquals("trace", CrashLog.pending(store))
    }

    @Test
    fun accidentalDismissKeepsTheReportForNextLaunch() {
        val store = InMemoryStore(content = "trace")

        CrashLog.answer(CrashReportAnswer.DISMISSED, "trace", store) { _, _ -> error("must not share") }

        assertEquals(0, store.deletes)
        assertEquals("trace", CrashLog.pending(store))
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
