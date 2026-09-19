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

    fun install(context: Context) {
        val app = context.applicationContext ?: context
        appContext = app
        installHandler(File(app.filesDir, CrashLog.FILE_NAME)) { throwable ->
            CrashLog.formatReport(
                throwable = throwable,
                appVersion = DeviceInfo.appVersionName,
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

internal actual fun platformCrashLogStore(): CrashLogStore? = AndroidCrashLog.context()?.let { FileCrashLogStore(File(it.filesDir, CrashLog.FILE_NAME)) }

actual fun shareCrashReport(report: String) {
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
    } catch (e: Exception) {
        android.util.Log.e("CrashLog", "Failed to share crash report: ${e.message}", e)
    }
}
