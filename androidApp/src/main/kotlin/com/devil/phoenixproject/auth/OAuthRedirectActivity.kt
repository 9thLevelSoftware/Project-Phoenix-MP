package com.devil.phoenixproject.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import co.touchlab.kermit.Logger
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
 */
class OAuthRedirectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        finish()
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data
        if (data == null) {
            // No URI on the intent means the OS or another app launched us
            // without a redirect payload. The waiting suspend in
            // OAuthLauncher.launch() must still complete — surface this as
            // a cancellation so the caller doesn't hang.
            Logger.w("OAuthRedirectActivity") { "Received intent with no data; cancelling pending OAuth flow" }
            AndroidOAuthBridge.cancelFlow()
            return
        }
        AndroidOAuthBridge.deliverCallback(data.toString())
    }
}
