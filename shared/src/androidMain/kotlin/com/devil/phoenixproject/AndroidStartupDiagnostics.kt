package com.devil.phoenixproject

import android.content.Context
import android.content.Intent
import android.os.Build

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
    chooserTitle: String,
): Boolean = try {
    val report = formatAndroidStartupDiagnostics(
        failure,
        appVersion = installedAppVersion(context),
        platform = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Project Phoenix startup diagnostics")
        putExtra(Intent.EXTRA_TEXT, report)
    }
    context.startActivity(
        Intent.createChooser(send, chooserTitle).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
    )
    true
} catch (_: Exception) {
    false
}

/** Package metadata is optional: support sharing must survive a failed package lookup. */
@Suppress("DEPRECATION")
private fun installedAppVersion(context: Context): String = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        info.versionCode.toLong()
    }
    "${info.versionName ?: "UNKNOWN"} (build $code)"
} catch (_: Exception) {
    "UNKNOWN (build UNKNOWN)"
}
