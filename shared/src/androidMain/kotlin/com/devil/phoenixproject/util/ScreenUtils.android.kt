package com.devil.phoenixproject.util

import android.app.Activity
import android.view.WindowManager
import java.lang.ref.WeakReference

/**
 * Holder for the current Activity reference.
 * Managed automatically via ActivityLifecycleCallbacks registered in VitruvianApp.
 */
object ActivityHolder {
    private var activityRef: WeakReference<Activity>? = null

    fun registerActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun getActivity(): Activity? = activityRef?.get()

    /**
     * Clear the held activity reference.
     * Called from ActivityLifecycleCallbacks.onActivityDestroyed to prevent
     * stale references across configuration changes.
     */
    fun clear() {
        activityRef = null
    }
}

actual fun setKeepScreenOn(enabled: Boolean) {
    val activity = ActivityHolder.getActivity() ?: return

    if (enabled) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
