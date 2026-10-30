package com.devil.phoenixproject

import android.content.Context
import android.content.Intent
import android.os.Build
import com.devil.phoenixproject.util.Constants

/** In-memory support report. Never read the database, preferences, or exception messages. */
internal fun formatAndroidStartupDiagnostics(
    failure: StartupDependencyResolution.Failed,
    appVersion: String,
    platform: String,
): String = buildString {
    appendLine("Project Phoenix startup diagnostics")
    appendLine("App version: $appVersion")
    appendLine("Platform: $platform")
    appendLine("Diagnostic code: ${failure.diagnosticCode}")
    appendLine("Support code: ${failure.supportCode ?: "NONE"}")
    appendLine("Stage: ${failure.stage}")
    appendLine("Presence: ${failure.presenceSnapshot?.safeSummary() ?: "UNAVAILABLE"}")
    appendLine("Exception messages and user data are omitted.")
    // Koin can wrap the original error repeatedly. Keep bounded, structured frames for each
    // cause rather than stackTraceToString(), which includes messages and suppressed errors.
    val seen = mutableListOf<Throwable>()
    var current: Throwable? = failure.cause
    while (current != null && seen.size < 32 && seen.none { it === current }) {
        val error = current
        seen += error
        appendLine("Cause ${seen.size}: ${error.javaClass.name}")
        error.stackTrace.take(12).forEach { frame ->
            appendLine("  at ${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})")
        }
        current = error.cause
    }
    if (current != null) appendLine("Cause chain truncated.")
}

/** Only presents the system chooser; sharing is explicitly completed by the user. */
internal fun shareAndroidStartupDiagnostics(
    context: Context,
    failure: StartupDependencyResolution.Failed,
): Boolean = try {
    val report = formatAndroidStartupDiagnostics(
        failure,
        appVersion = Constants.APP_VERSION,
        platform = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Project Phoenix startup diagnostics")
        putExtra(Intent.EXTRA_TEXT, report)
    }
    context.startActivity(
        Intent.createChooser(send, null).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
    )
    true
} catch (_: Exception) {
    false
}
