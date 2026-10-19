package com.devil.phoenixproject

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import co.touchlab.kermit.Logger

/**
 * Brings the app's own task, and its existing [MainActivity], to the front from a transient
 * activity that the system started in another task (an OAuth redirect, a shared CSV file).
 *
 * Flag choice rationale:
 *  - `FLAG_ACTIVITY_NEW_TASK`: the caller runs outside the app's task, and the app's task is
 *    the one to show.
 *  - `FLAG_ACTIVITY_REORDER_TO_FRONT`: an existing [MainActivity] comes forward without
 *    clearing the app's other state.
 *  - `FLAG_ACTIVITY_SINGLE_TOP`: a [MainActivity] already on top is reused, never duplicated.
 *
 * `ACTION_MAIN` + `CATEGORY_LAUNCHER` mirror the launcher intent, so the routing behaves the
 * same however the app was originally started. A failure is logged, never thrown: the caller
 * has already done its work, and at worst the user reopens the app by hand.
 */
internal fun Activity.returnToApp(tag: String) {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(this@returnToApp, MainActivity::class.java)
        addCategory(Intent.CATEGORY_LAUNCHER)
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
    }
    try {
        startActivity(intent)
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    } catch (e: Exception) {
        Logger.w(tag = tag) { "Failed to route back to MainActivity: ${e.message}" }
    }
}
