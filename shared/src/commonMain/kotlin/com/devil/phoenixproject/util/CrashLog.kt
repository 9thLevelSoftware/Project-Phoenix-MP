package com.devil.phoenixproject.util

import kotlin.time.Instant

/**
 * Local-only crash capture (no third-party SDK). Platform hooks write the last uncaught
 * exception to `crash-last.txt`; on the next launch the app offers to share it once.
 */
object CrashLog {
    const val FILE_NAME = "crash-last.txt"

    /** Keeps the shared text well under platform share-intent size limits. */
    const val MAX_REPORT_CHARS = 100_000

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
    }.take(MAX_REPORT_CHARS)

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

    /** The report left by a previous run, or null. It stays until [discard] so it is offered until answered. */
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
}

/** Access to the single last-crash report file. */
interface CrashLogStore {
    fun read(): String?
    fun write(report: String)
    fun delete()
}

/** The platform crash file, or null before the platform hook is installed. */
internal expect fun platformCrashLogStore(): CrashLogStore?

/** Opens the platform share sheet with the report as plain text. */
expect fun shareCrashReport(report: String)
