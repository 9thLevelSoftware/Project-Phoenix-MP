package com.devil.phoenixproject.data.auth

import com.devil.phoenixproject.testutil.findProjectRoot
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scans the iOS Info.plist for the mobile OAuth callback URL scheme.
 * Lives in androidHostTest because the scan uses java.io.File.
 */
class OAuthIosUrlSchemeConfigTest {

    @Test
    fun iosInfoPlistRegistersOAuthCallbackScheme() {
        val infoPlist = File(
            findProjectRoot(),
            "iosApp/PhoenixApp/PhoenixApp/Info.plist",
        )

        assertTrue("Expected iOS Info.plist to exist at ${infoPlist.path}", infoPlist.isFile)

        val content = infoPlist.readText()
        assertTrue(
            "Expected iOS Info.plist to declare CFBundleURLTypes for OAuth callbacks",
            content.contains("<key>CFBundleURLTypes</key>"),
        )
        assertTrue(
            "Expected iOS Info.plist to register the com.devil.phoenixproject URL scheme used by mobile OAuth",
            content.contains("<string>com.devil.phoenixproject</string>"),
        )
    }
}
