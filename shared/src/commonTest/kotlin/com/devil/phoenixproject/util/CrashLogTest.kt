package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

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
    fun redactionStaysFastOnPathologicalUnbrokenRuns() {
        // Long hex/base64-like runs made the unbounded email pattern backtrack quadratically (minutes at 200k).
        val inputs = listOf(
            "x".repeat(200_000),
            "a@" + "b".repeat(200_000),
            "eyJ" + "a".repeat(200_000),
            "Bearer " + "c".repeat(200_000),
        )
        for (input in inputs) {
            val elapsed = measureTime { CrashLog.redact(input) }
            assertTrue(elapsed < 1.seconds, "redact took $elapsed for ${input.take(8)}...")
        }

        val elapsed = measureTime {
            CrashLog.formatReport(RuntimeException("x".repeat(1_000_000)), "1", "p", 0L)
        }
        assertTrue(elapsed < 1.seconds, "formatReport took $elapsed on a 1M-char message")
    }

    @Test
    fun redactionStaysFastOnRepeatedAnchorTokens() {
        val inputs = listOf(
            "@".repeat(200_000),
            "eyJ".repeat(66_000),
            ("eyJ".repeat(1366) + ".a").repeat(50),
            ("a@b." + "1".repeat(250) + "@").repeat(800),
        )
        for (input in inputs) {
            val elapsed = measureTime { CrashLog.redact(input) }
            assertTrue(elapsed < 1.seconds, "redact took $elapsed for repeated anchor ${input.take(8)}...")
        }
    }

    @Test
    fun redactionPreservesEdgeCaseAndPrecedenceSemantics() {
        // >64 local-part chars before '@': only the trailing 64 chars + domain are redacted.
        assertEquals(
            "prefix " + "a".repeat(6) + "[redacted]",
            CrashLog.redact("prefix " + "a".repeat(70) + "@example.com"),
        )
        // Multi-dot domains and trailing non-TLD segments.
        assertEquals("user=[redacted]", CrashLog.redact("user=a@b.c.com"))
        assertEquals("user=[redacted].123", CrashLog.redact("user=a@b.com.123"))
        // Invalid TLDs / missing domain prefix are not redacted.
        assertEquals("a@b.123 a@b.c a@.com", CrashLog.redact("a@b.123 a@b.c a@.com"))
        // Adjacent/nested eyJ tokens: partial prefix before a valid eyJ still leaves the valid JWT redacted.
        assertEquals("[redacted]", CrashLog.redact("eyJfooeyJbar.baz.qux"))
        assertEquals(
            "eyJ" + "a".repeat(4097) + "[redacted]",
            CrashLog.redact("eyJ" + "a".repeat(4097) + "eyJabc.def.ghi"),
        )
        // Bearer runs before JWT and Email so a token containing either becomes a single [redacted].
        assertEquals("auth=[redacted]", CrashLog.redact("auth=Bearer eyJabc.def.ghi"))
        assertEquals("auth=[redacted]", CrashLog.redact("auth=bearer jane@example.com"))
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
