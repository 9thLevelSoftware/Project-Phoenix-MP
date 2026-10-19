package com.devil.phoenixproject.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.MainActivity
import com.devil.phoenixproject.data.auth.AndroidOAuthBridge
import com.devil.phoenixproject.returnToApp

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
     * surface after the OAuth callback completes. This redirect activity has
     * `taskAffinity=""` and therefore lives in its own transient task; see
     * [returnToApp] for the flags.
     */
    private fun routeBackToApp() = returnToApp(tag = "OAuthRedirectActivity")
}
