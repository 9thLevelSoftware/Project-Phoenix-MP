package com.devil.phoenixproject.util

import com.devil.phoenixproject.testutil.FakeBackupDestinationResolver
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.util.BackupDestination.Companion.fromJson
import com.devil.phoenixproject.util.BackupDestination.Companion.toJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests for backup destination routing logic using [FakeBackupDestinationResolver].
 *
 * Verifies that:
 * - Default/Custom destination properties behave correctly
 * - Preference round-trip preserves Custom URIs
 * - Resolver correctly reports accessibility
 * - Write calls are captured with correct arguments
 * - Error results propagate properly
 */
class BackupRoutingTest {

    // Helper: call the companion extension function toJson()
    private fun BackupDestination.serialize(): String = with(BackupDestination) { toJson() }

    private val resolver = FakeBackupDestinationResolver()

    // ===== Destination property tests =====

    @Test
    fun default_destinationIsNotCustom() {
        assertFalse(BackupDestination.Default.isCustom)
    }

    @Test
    fun custom_destinationPreservesUriThroughPreferenceRoundTrip() {
        val original = BackupDestination.Custom(
            uri = "content://com.android.externalstorage/tree/primary%3APhoenixBackups",
            displayName = "Phoenix Backups",
        )
        val json = original.serialize()
        val restored = fromJson(json)
        assertTrue(restored is BackupDestination.Custom)
        assertEquals(original.uri, restored.uri)
        assertEquals(original.displayName, restored.displayName)
    }

    // ===== Resolver accessibility tests =====

    @Test
    fun inaccessible_destinationReturnsFalseFromResolver() = runTest {
        resolver.isAccessibleResult = false
        val destination = BackupDestination.Custom(
            uri = "content://revoked/tree/old",
            displayName = "Revoked Folder",
        )
        assertFalse(resolver.isAccessible(destination))
    }

    @Test
    fun accessible_destinationReceivesWriteCallAndCapturesArguments() = runTest {
        resolver.isAccessibleResult = true
        resolver.writeFileResult = Result.success("content://written/backup.json")

        val destination = BackupDestination.Custom(
            uri = "content://test/tree/backups",
            displayName = "My Backups",
        )

        // Verify accessible first
        assertTrue(resolver.isAccessible(destination))

        // Then write
        val result = resolver.writeFile(destination, "phoenix-backup-2024.json", "/tmp/backup.json")
        assertTrue(result.isSuccess)
        assertEquals("content://written/backup.json", result.getOrThrow())

        // Verify captured arguments
        assertEquals(1, resolver.writtenFiles.size)
        val (capturedDest, capturedName, capturedPath) = resolver.writtenFiles[0]
        assertEquals(destination, capturedDest)
        assertEquals("phoenix-backup-2024.json", capturedName)
        assertEquals("/tmp/backup.json", capturedPath)
    }

    @Test
    fun write_failureReturnsErrorResult() = runTest {
        val error = IllegalStateException("Permission denied")
        resolver.writeFileResult = Result.failure(error)

        val destination = BackupDestination.Custom(
            uri = "content://test/tree/readonly",
            displayName = "Read Only",
        )

        val result = resolver.writeFile(destination, "backup.json", "/tmp/backup.json")
        assertTrue(result.isFailure)
        assertEquals("Permission denied", result.exceptionOrNull()?.message)
    }

    // ===== Edge cases =====

    @Test
    fun custom_emptyUriEdgeCase() {
        val destination = BackupDestination.Custom(uri = "", displayName = "Empty")
        assertTrue(destination.isCustom, "Even with empty URI, Custom is still Custom")
        assertEquals("", destination.uri)
    }

    @Test
    fun reset_fromCustomToDefault() {
        // Simulate user changing from Custom back to Default
        val custom = BackupDestination.Custom(
            uri = "content://test/tree/old",
            displayName = "Old Location",
        )
        assertTrue(custom.isCustom)

        // Serialize Default as replacement
        val defaultJson = BackupDestination.Default.serialize()
        val restored = fromJson(defaultJson)
        assertEquals(BackupDestination.Default, restored)
        assertFalse(restored.isCustom)
    }

    @Test
    fun listFiles_returnsConfiguredResult() = runTest {
        resolver.listFilesResult = listOf("backup-2024-01.json", "backup-2024-02.json")
        val destination = BackupDestination.Custom(uri = "content://test", displayName = "Test")
        val files = resolver.listFiles(destination)
        assertEquals(2, files.size)
        assertEquals("backup-2024-01.json", files[0])
        assertEquals("backup-2024-02.json", files[1])
    }

    @Test
    fun multiple_writes_accumulateInCapture() = runTest {
        val destination = BackupDestination.Custom(uri = "content://test", displayName = "Test")
        resolver.writeFile(destination, "file1.json", "/tmp/1.json")
        resolver.writeFile(destination, "file2.json", "/tmp/2.json")
        resolver.writeFile(destination, "file3.json", "/tmp/3.json")

        assertEquals(3, resolver.writtenFiles.size)
        assertEquals("file1.json", resolver.writtenFiles[0].second)
        assertEquals("file2.json", resolver.writtenFiles[1].second)
        assertEquals("file3.json", resolver.writtenFiles[2].second)
    }

    // ===== PreferencesManager backup destination tests =====

    @Test
    fun preferencesManager_setBackupDestination_updatesFlow() = runTest {
        val prefs = FakePreferencesManager()

        // Verify default is Default
        assertEquals(
            BackupDestination.Default,
            prefs.preferencesFlow.value.backupDestination,
            "Initial backup destination should be Default",
        )

        // Set to custom
        val custom = BackupDestination.Custom(
            uri = "content://com.android.externalstorage/tree/primary%3APhoenixBackups",
            displayName = "Phoenix Backups",
        )
        prefs.setBackupDestination(custom)

        // Verify flow updated — smart-cast after assertTrue(is Custom) allows direct field access
        val updated = prefs.preferencesFlow.value.backupDestination
        assertTrue(updated is BackupDestination.Custom, "Destination should be Custom after set")
        assertEquals(custom.uri, updated.uri)
        assertEquals(custom.displayName, updated.displayName)
    }

    // ===== listFiles edge cases =====

    @Test
    fun listFiles_returnsEmptyByDefault() = runTest {
        // FakeBackupDestinationResolver defaults to emptyList for listFilesResult
        val destination = BackupDestination.Custom(uri = "content://inaccessible", displayName = "Gone")
        val files = resolver.listFiles(destination)
        assertTrue(files.isEmpty(), "Default listFiles result should be empty")
    }
}
