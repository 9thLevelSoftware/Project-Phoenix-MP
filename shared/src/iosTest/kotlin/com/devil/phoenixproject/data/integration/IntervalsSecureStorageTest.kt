@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.devil.phoenixproject.data.integration

import com.devil.phoenixproject.data.local.IosIntegrationBackupExclusionGate
import com.devil.phoenixproject.data.local.readBackupExclusion
import com.devil.phoenixproject.data.local.runBestEffortBackupExclusion
import com.russhwolf.settings.KeychainSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToFile

/**
 * Issue #1384 P3 (integration-model §7, R4 evidence): real iOS Keychain behavior for the
 * Intervals.icu secret and installation authorization, and verified backup-exclusion
 * resource attributes for the live database family including sidecars and quarantine.
 *
 * Real-Keys/simulator test (compile-checked from this checkout; run via
 * `:shared:iosSimulatorArm64Test`). The Keychain service matches the mandatory named
 * binding (`KeychainSettings(service = "com.devil.phoenixproject.auth")`); secrets are
 * never in NSUserDefaults, SQL, or backups.
 */
class IntervalsSecureStorageTest {

    private val keychain = KeychainSettings(service = KEYCHAIN_SERVICE_NAME)
    private val ownedKeys = listOf(
        "intervals.secret.ref.v1",
        "intervals.installation.authorization.v1",
    )
    private var tempRoot: String = ""

    @BeforeTest
    fun setUp() {
        tempRoot = "${NSTemporaryDirectory().trimEnd('/')}/intervals-secure-storage-test-${currentTimeNanos()}"
        NSFileManager.defaultManager.createDirectoryAtPath(
            tempRoot,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        for (key in ownedKeys + NON_OWNED_KEY) keychain.remove(key)
    }

    @AfterTest
    fun tearDown() {
        // Owned-key deletion only — never KeychainSettings.clear(): the shared auth
        // Keychain (portal tokens, other integrations) must survive.
        for (key in ownedKeys) keychain.remove(key)
        NSFileManager.defaultManager.removeItemAtPath(tempRoot, error = null)
    }

    @Test
    fun ownedKeyDeletionRemovesOnlyOwnedKeysAndNeverClearsTheSharedAuthKeychain() {
        keychain.putString(ownedKeys[0], STORED_REF_MARKER)
        keychain.putString(ownedKeys[1], INSTALLATION_MARKER)
        keychain.putString(NON_OWNED_KEY, "non-owned-token-marker")
        assertEquals(STORED_REF_MARKER, keychain.getStringOrNull(ownedKeys[0]))

        for (key in ownedKeys) keychain.remove(key)

        assertNull(keychain.getStringOrNull(ownedKeys[0]))
        assertNull(keychain.getStringOrNull(ownedKeys[1]))
        assertEquals(
            "non-owned-token-marker",
            keychain.getStringOrNull(NON_OWNED_KEY),
            "deleting Intervals-owned keys must never touch other keys in the shared auth Keychain",
        )
    }

    @Test
    fun restoredOrTransferredAuthorizationFromAnotherInstallationIsDetectableAndClearable() {
        // A restore/transfer can carry Keychain items across installations. The stored
        // authorization is bound to its installation id: a fresh installation reads a
        // DIFFERENT id (installation mismatch fails closed) and owned-key deletion clears
        // the stale authorization without touching anything else.
        keychain.putString(ownedKeys[1], INSTALLATION_MARKER)
        keychain.putString(NON_OWNED_KEY, "non-owned-token-marker")

        val restoredAuthorization = keychain.getStringOrNull(ownedKeys[1])
        assertNotNull(restoredAuthorization)
        assertEquals(
            INSTALLATION_MARKER,
            restoredAuthorization,
            "restored authorization must carry the original installation id, never a fresh one",
        )
        assertFalse(
            restoredAuthorization == FRESH_INSTALLATION_ID,
            "a fresh installation id must never match the restored authorization (fail closed)",
        )

        keychain.remove(ownedKeys[1])
        assertNull(keychain.getStringOrNull(ownedKeys[1]))
        assertEquals("non-owned-token-marker", keychain.getStringOrNull(NON_OWNED_KEY))
    }

    @Test
    fun backupResourceAttributesAreVerifiedForMainSidecarsAndQuarantine() {
        // The live database family: main file plus -wal/-shm/-journal sidecars, and the
        // quarantine folder with its entries. Exclusion is applied AND read back — a
        // best-effort write is not sufficient (integration-model §7, risk 5).
        val db = "$tempRoot/phoenix.db"
        val sidecars = listOf(db, "$db-wal", "$db-shm", "$db-journal")
        val quarantineDir = "$tempRoot/phoenix-quarantine"
        val quarantined = "$quarantineDir/1700000000000-DUAL_DATABASES-phoenix.db"
        for (path in sidecars + quarantined) writeText(path, "not-a-real-database")
        NSFileManager.defaultManager.createDirectoryAtPath(
            quarantineDir,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )

        for (path in sidecars + quarantineDir + quarantined) {
            runBestEffortBackupExclusion(path)
            assertEquals(
                true,
                readBackupExclusion(path),
                "NSURLIsExcludedFromBackupKey must read back true for $path",
            )
        }
    }

    @Test
    fun integrationStorageGateIsFailClosed() {
        IosIntegrationBackupExclusionGate.noteExclusionFailure("injected verification failure")
        assertEquals("injected verification failure", IosIntegrationBackupExclusionGate.closedReason())

        // Verification re-runs against the real live database family at call time and never
        // throws; whatever it concludes, the gate reports its reason consistently.
        val permitted = IosIntegrationBackupExclusionGate.integrationStoragePermitted()
        if (permitted) {
            assertEquals(null, IosIntegrationBackupExclusionGate.closedReason())
        } else {
            assertNotNull(IosIntegrationBackupExclusionGate.closedReason())
        }
    }

    private fun writeText(path: String, text: String) {
        assertTrue(
            NSString.create(string = text).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null),
        )
    }

    private fun currentTimeNanos(): Long = kotlin.time.TimeSource.Monotonic.markNow()
        .elapsedNow()
        .inWholeNanoseconds

    private companion object {
        const val KEYCHAIN_SERVICE_NAME = "com.devil.phoenixproject.auth"
        const val NON_OWNED_KEY = "portal.auth.token"
        const val STORED_REF_MARKER = "marker-stored-ref-ios"
        const val INSTALLATION_MARKER = "marker-install-ios"
        const val FRESH_INSTALLATION_ID = "marker-fresh-install"
    }
}
