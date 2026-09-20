package com.devil.phoenixproject.util

import kotlin.time.Instant

/**
 * Local-only crash capture (no third-party SDK). Platform hooks write the last uncaught
 * exception to `crash-last.txt`; on the next launch the app offers to share it.
 */
object CrashLog {
    const val FILE_NAME = "crash-last.txt"

    /** Keeps the shared text well under platform share-intent size limits. */
    const val MAX_REPORT_CHARS = 100_000

    private const val REDACTED = "[redacted]"
    // Every quantifier is bounded so matching stays linear in the report length. An unbounded
    // `[...]+@` backtracks quadratically over long unbroken runs (hex dumps, base64), which would
    // stall the dying process inside the crash handler.
    private val secretPatterns = listOf(
        Regex("Bearer\\s{1,8}\\S{1,4096}", RegexOption.IGNORE_CASE),
        Regex("eyJ[A-Za-z0-9_-]{1,4096}\\.[A-Za-z0-9_-]{1,4096}\\.[A-Za-z0-9_-]{1,4096}"),
        Regex("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,255}\\.[A-Za-z]{2,24}"),
    )

    fun formatReport(
        throwable: Throwable,
        appVersion: String,
        platform: String,
        timestampMillis: Long,
    ): String = buildString {
        appendLine("Project Phoenix crash report")
        appendLine("App version: $appVersion")
        appendLine("Platform: $platform")
        appendLine("Time (UTC): ${Instant.fromEpochMilliseconds(timestampMillis)}")
        appendLine()
        append(throwable.stackTraceToString())
    }.let { cap(redact(cap(it))) } // Cap first so redaction input is bounded; re-cap since a short match can grow.

    /** Strips bearer tokens, JWTs and email addresses, since reports are often posted publicly. */
    internal fun redact(text: String): String = secretPatterns.fold(text) { acc, regex -> regex.replace(acc, REDACTED) }

    /** Keeps the head and the tail (where the root "Caused by" usually is) of an oversized report. */
    private fun cap(report: String): String {
        if (report.length <= MAX_REPORT_CHARS) return report
        val marker = "\n... truncated ${report.length - MAX_REPORT_CHARS} chars ...\n"
        val budget = MAX_REPORT_CHARS - marker.length
        val head = budget * 6 / 10
        return report.take(head) + marker + report.takeLast(budget - head)
    }

    /** Called from a dying process: must never throw. */
    fun record(
        store: CrashLogStore,
        throwable: Throwable,
        appVersion: String,
        platform: String,
        timestampMillis: Long,
    ) {
        try {
            store.write(formatReport(throwable, appVersion, platform, timestampMillis))
        } catch (_: Throwable) {
            // Nothing useful can be done while the process is going down.
        }
    }

    /** The report left by a previous run, or null. It stays until the user explicitly answers. */
    fun pending(store: CrashLogStore? = platformCrashLogStore()): String? = try {
        store?.read()?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    /** Delete the report once the user has shared or declined it. */
    fun discard(store: CrashLogStore? = platformCrashLogStore()) {
        try {
            store?.delete()
        } catch (_: Throwable) {
            // A stale report is only re-offered; never fail the UI for it.
        }
    }

    /**
     * Applies the user's answer to the crash prompt. The report is deleted only after an
     * explicit "Not now" or once the share sheet actually opened. A dismissal (tap outside,
     * Back) or a share that never presented keeps it, so it is offered again next launch.
     */
    fun answer(
        answer: CrashReportAnswer,
        report: String,
        store: CrashLogStore? = platformCrashLogStore(),
        share: (report: String, onShown: () -> Unit) -> Unit = ::shareCrashReport,
    ) {
        when (answer) {
            CrashReportAnswer.SHARE -> share(report) { discard(store) }
            CrashReportAnswer.NOT_NOW -> discard(store)
            CrashReportAnswer.DISMISSED -> Unit
        }
    }
}

enum class CrashReportAnswer { SHARE, NOT_NOW, DISMISSED }

/** Access to the single last-crash report file. */
interface CrashLogStore {
    fun read(): String?
    fun write(report: String)
    fun delete()
}

/** The platform crash file, or null before the platform hook is installed. */
internal expect fun platformCrashLogStore(): CrashLogStore?

/** Opens the platform share sheet with the report as plain text; calls [onShown] only if it opened. */
expect fun shareCrashReport(report: String, onShown: () -> Unit)
