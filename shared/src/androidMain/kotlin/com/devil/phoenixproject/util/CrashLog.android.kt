package com.devil.phoenixproject.util

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File

internal class FileCrashLogStore(private val file: File) : CrashLogStore {
    override fun read(): String? = if (file.exists()) file.readText() else null
    override fun write(report: String) = file.writeText(report)
    override fun delete() {
        file.delete()
    }
}

/** Android crash capture: call [install] first thing in Application.onCreate(). */
object AndroidCrashLog {
    @Volatile
    private var appContext: Context? = null

    /** [versionName]/[versionCode] come from the app's BuildConfig so the report pins the exact build. */
    fun install(context: Context, versionName: String, versionCode: Int) {
        val app = context.applicationContext ?: context
        appContext = app
        val appVersion = "$versionName ($versionCode)"
        installHandler(crashFile(app)) { throwable ->
            CrashLog.formatReport(
                throwable = throwable,
                appVersion = appVersion,
                platform = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}",
                timestampMillis = System.currentTimeMillis(),
            )
        }
    }

    /** Wraps the current default handler: write the report, then delegate so the process still dies normally. */
    internal fun installHandler(file: File, format: (Throwable) -> String) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is CrashFileHandler) return
        Thread.setDefaultUncaughtExceptionHandler(CrashFileHandler(file, previous, format))
    }

    /** The same file for the handler (write) and the prompt (read/delete). */
    internal fun crashFile(context: Context): File = File(context.filesDir, CrashLog.FILE_NAME)

    internal fun context(): Context? = appContext

    private class CrashFileHandler(
        private val file: File,
        private val previous: Thread.UncaughtExceptionHandler?,
        private val format: (Throwable) -> String,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            try {
                file.writeText(format(throwable))
            } catch (_: Throwable) {
                // Never block delegation to the platform handler.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}

internal actual fun platformCrashLogStore(): CrashLogStore? = AndroidCrashLog.context()?.let { FileCrashLogStore(AndroidCrashLog.crashFile(it)) }

actual fun shareCrashReport(report: String, onShown: () -> Unit) {
    val context = AndroidCrashLog.context() ?: return
    try {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Project Phoenix crash report")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        context.startActivity(
            Intent.createChooser(send, "Share crash report").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        onShown()
    } catch (e: Exception) {
        // Keep the report so it is offered again next launch.
        android.util.Log.e("CrashLog", "Failed to share crash report: ${e.message}", e)
    }
}
