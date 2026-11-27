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
    private const val BEARER_MAX_WS = 8
    private const val TOKEN_MAX_LEN = 4096
    private const val EMAIL_LOCAL_MAX_LEN = 64
    private const val EMAIL_DOMAIN_MAX_LEN = 255
    private const val EMAIL_TLD_MIN_LEN = 2
    private const val EMAIL_TLD_MAX_LEN = 24

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

    /**
     * Strips bearer tokens, JWTs and email addresses, since reports are often posted publicly.
     * Uses linear anchor-indexed scanning (`bearer`, `eyJ`, `@`) rather than whole-string regex
     * so long unbroken runs never trigger per-character regex backtracking.
     */
    internal fun redact(text: String): String = redactEmail(redactJwt(redactBearer(text)))

    private fun redactBearer(text: String): String {
        var sb: StringBuilder? = null
        var lastEmitted = 0
        var i = 0
        while (i < text.length) {
            val idx = text.indexOf("bearer", startIndex = i, ignoreCase = true)
            if (idx == -1) break
            val wsStart = idx + 6
            var wsEnd = wsStart
            val wsLimit = minOf(text.length, wsStart + BEARER_MAX_WS)
            while (wsEnd < wsLimit && text[wsEnd].isWhitespace()) wsEnd++
            if (wsEnd > wsStart) {
                var tokEnd = wsEnd
                val tokLimit = minOf(text.length, wsEnd + TOKEN_MAX_LEN)
                while (tokEnd < tokLimit && !text[tokEnd].isWhitespace()) tokEnd++
                if (tokEnd > wsEnd) {
                    val out = sb ?: StringBuilder(text.length).also { sb = it }
                    out.appendRange(text, lastEmitted, idx)
                    out.append(REDACTED)
                    lastEmitted = tokEnd
                    i = tokEnd
                    continue
                }
            }
            i = wsEnd
        }
        val out = sb ?: return text
        out.appendRange(text, lastEmitted, text.length)
        return out.toString()
    }

    private fun redactJwt(text: String): String {
        var sb: StringBuilder? = null
        var lastEmitted = 0
        var i = 0
        while (i < text.length) {
            val idx = text.indexOf("eyJ", startIndex = i)
            if (idx == -1) break
            val p1Start = idx + 3
            var p1End = p1Start
            while (p1End < text.length && isJwtBodyChar(text[p1End])) p1End++
            if (p1End >= text.length || text[p1End] != '.') {
                i = p1End + 1
                continue
            }
            val p2Start = p1End + 1
            var p2End = p2Start
            val p2Limit = minOf(text.length, p2Start + TOKEN_MAX_LEN + 1)
            while (p2End < p2Limit && isJwtBodyChar(text[p2End])) p2End++
            val p2Len = p2End - p2Start
            if (p2Len !in 1..TOKEN_MAX_LEN || p2End >= text.length || text[p2End] != '.') {
                i = p1End + 1
                continue
            }
            val p3Start = p2End + 1
            var p3End = p3Start
            val p3Limit = minOf(text.length, p3Start + TOKEN_MAX_LEN)
            while (p3End < p3Limit && isJwtBodyChar(text[p3End])) p3End++
            if (p3End == p3Start) {
                i = p1End + 1
                continue
            }
            val p1Len = p1End - p1Start
            val matchStart = when {
                p1Len in 1..TOKEN_MAX_LEN -> idx
                p1Len > TOKEN_MAX_LEN -> {
                    val earliestAllowed = maxOf(p1Start, p1End - TOKEN_MAX_LEN - 3)
                    val innerIdx = text.indexOf("eyJ", startIndex = earliestAllowed)
                    if (innerIdx != -1 && innerIdx <= p1End - 4) innerIdx else -1
                }
                else -> -1
            }
            if (matchStart == -1) {
                i = p1End + 1
                continue
            }
            val out = sb ?: StringBuilder(text.length).also { sb = it }
            out.appendRange(text, lastEmitted, matchStart)
            out.append(REDACTED)
            lastEmitted = p3End
            i = p3End
        }
        val out = sb ?: return text
        out.appendRange(text, lastEmitted, text.length)
        return out.toString()
    }

    private fun redactEmail(text: String): String {
        var sb: StringBuilder? = null
        var lastEmitted = 0
        var i = 0
        while (i < text.length) {
            val atIdx = text.indexOf('@', startIndex = i)
            if (atIdx == -1) break
            var localStart = atIdx
            val localMin = maxOf(lastEmitted, atIdx - EMAIL_LOCAL_MAX_LEN)
            while (localStart > localMin && isEmailLocalChar(text[localStart - 1])) {
                localStart--
            }
            if (localStart == atIdx) {
                i = atIdx + 1
                continue
            }
            val domainStart = atIdx + 1
            val maxAfterAt = minOf(text.length, domainStart + EMAIL_DOMAIN_MAX_LEN + 1 + EMAIL_TLD_MAX_LEN)
            var runEnd = domainStart
            while (runEnd < maxAfterAt && isEmailDomainChar(text[runEnd])) {
                runEnd++
            }
            var matchEnd = -1
            val maxDotIdx = minOf(runEnd - 1, domainStart + EMAIL_DOMAIN_MAX_LEN)
            for (dotIdx in maxDotIdx downTo domainStart + 1) {
                if (text[dotIdx] != '.') continue
                var tldEnd = dotIdx + 1
                val tldLimit = minOf(text.length, dotIdx + 1 + EMAIL_TLD_MAX_LEN)
                while (tldEnd < tldLimit && isAsciiLetter(text[tldEnd])) {
                    tldEnd++
                }
                if (tldEnd - (dotIdx + 1) >= EMAIL_TLD_MIN_LEN) {
                    matchEnd = tldEnd
                    break
                }
            }
            if (matchEnd == -1) {
                i = atIdx + 1
                continue
            }
            val out = sb ?: StringBuilder(text.length).also { sb = it }
            out.appendRange(text, lastEmitted, localStart)
            out.append(REDACTED)
            lastEmitted = matchEnd
            i = matchEnd
        }
        val out = sb ?: return text
        out.appendRange(text, lastEmitted, text.length)
        return out.toString()
    }

    private fun isJwtBodyChar(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '_' || c == '-'

    private fun isEmailLocalChar(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
            c == '.' || c == '_' || c == '%' || c == '+' || c == '-'

    private fun isEmailDomainChar(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '-'

    private fun isAsciiLetter(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z'

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
