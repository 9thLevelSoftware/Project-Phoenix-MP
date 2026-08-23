package com.devil.phoenixproject.di

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IosSecureTokenSourceContractTest {

    @Test
    fun platformModulePassesKeychainSettingsForSecureQualifier() {
        val source = readProjectFile(
            "src/iosMain/kotlin/com/devil/phoenixproject/di/PlatformModule.ios.kt",
        )
        assertNotNull(source, "Could not read PlatformModule.ios.kt")
        assertContains(source, "private const val KEYCHAIN_SERVICE_NAME = \"com.devil.phoenixproject.auth\"")

        val start = source.indexOf("single<Settings>(SecureSettingsQualifier)")
        assertTrue(start >= 0, "SecureSettingsQualifier binding is missing")
        val fromBinding = source.substring(start)
        val nextSingle = Regex("\\n    single(?:<|\\s|\\{)").find(fromBinding, startIndex = 1)
        val block = if (nextSingle != null) fromBinding.substring(0, nextSingle.range.first) else fromBinding
        assertContains(block, "KeychainSettings(service = KEYCHAIN_SERVICE_NAME)")
        assertFalse(
            block.contains("NSUserDefaultsSettings("),
            "SecureSettingsQualifier must not construct NSUserDefaultsSettings",
        )
        val lastStatement = block.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "}" && !it.startsWith("//") }
            .last()
        assertTrue(lastStatement == "keychainSettings", lastStatement)
    }
}
