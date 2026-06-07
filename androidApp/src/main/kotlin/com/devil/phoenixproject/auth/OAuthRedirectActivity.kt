package com.devil.phoenixproject.auth

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.MainActivity
import com.devil.phoenixproject.data.auth.AndroidOAuthBridge

/**
 * Transparent activity that catches the OAuth provider's final redirect
 * (e.g. `com.devil.phoenixproject://auth-callback?code=...`), hands the URL
 * off to [AndroidOAuthBridge], and finishes itself immediately — leaving the
 * original [MainActivity] in front of the user.
 *
 * This intentionally lives in the app module (not the shared module) because
 * it has to be declared in the host app's AndroidManifest to receive the
 * deep-link intent; the shared module has no manifest of its own.
 *
 * After delivering (or cancelling) the callback, this activity explicitly
 * routes the user back to [MainActivity]. Without this handoff, the user can
 * be left looking at the (now-finished) Custom Tab surface because the OAuth
 * launcher uses `FLAG_ACTIVITY_NEW_TASK` on the Custom Tabs intent, and this
 * redirect activity runs with `taskAffinity=""` / `singleTask` — meaning the
 * system delivers the deep link into a transient task, not the app's own
 * task. Foregrounding [MainActivity] by hand restores the expected "return
 * to the app" UX (fixes Project Phoenix issue #508).
 */
class OAuthRedirectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        routeBackToApp()
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        routeBackToApp()
        finish()
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        if (data == null) {
            // No URI on the intent means the OS or another app launched us
            // without a redirect payload. The waiting suspend in
            // OAuthLauncher.launch() must still complete — surface this as
            // a cancellation so the caller doesn't hang.
            Logger.w(tag = "OAuthRedirectActivity") { "Received intent with no data; cancelling pending OAuth flow" }
            AndroidOAuthBridge.cancelFlow()
            return
        }
        AndroidOAuthBridge.deliverCallback(data.toString())
    }

    /**
     * Bring the app's existing task (and its [MainActivity]) to the front so
     * the user isn't stranded on the (just-finished) browser/Custom Tab
     * surface after the OAuth callback completes.
     *
     * Flag choice rationale:
     *  - `FLAG_ACTIVITY_NEW_TASK`: required because this redirect activity
     *    has `taskAffinity=""` and therefore lives in its own transient
     *    task. Without this flag, the system would try to launch
     *    [MainActivity] into that same empty-affinity task, which is not
     *    what we want — we want the original app task.
     *  - `FLAG_ACTIVITY_REORDER_TO_FRONT`: if the existing [MainActivity]
     *    instance is still in the app task, bring it forward without clearing
     *    other app state. This preserves the user's link-account context while
     *    still replacing the browser/redirect surface.
     *  - `FLAG_ACTIVITY_SINGLE_TOP`: if [MainActivity] is already at the
     *    top of its task, reuse that instance instead of creating a new
     *    one. This avoids a duplicate stack and any side effects of a
     *    second `onCreate`.
     *
     * `ACTION_MAIN` + `CATEGORY_LAUNCHER` mirror the launcher intent the
     * system uses to start the app cold, which makes the routing behave
     * consistently regardless of how the user originally launched the app.
     */
    private fun routeBackToApp() {
        val launchIntent = buildReturnToAppIntent()
        try {
            startActivity(launchIntent)
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        } catch (e: Exception) {
            // Defensive: if for some reason the explicit intent can't be
            // resolved (e.g. MainActivity was renamed / removed in a future
            // build), don't crash the redirect handler — the callback has
            // already been delivered, so the user is technically signed in
            // even if they have to relaunch the app manually.
            Logger.w(tag = "OAuthRedirectActivity") {
                "Failed to route back to MainActivity after OAuth callback: ${e.message}"
            }
        }
    }

    /**
     * Build the [Intent] used by [routeBackToApp] to foreground the app's
     * existing task. Exposed at internal visibility so focused unit tests
     * can assert on the flag combination without needing to spin up an
     * actual [android.app.Activity].
     */
    internal fun buildReturnToAppIntent(): Intent = Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(this@OAuthRedirectActivity, MainActivity::class.java)
        addCategory(Intent.CATEGORY_LAUNCHER)
        // See kdoc on [routeBackToApp] for why each flag is set.
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_SINGLE_TOP,
        )
    }
}
