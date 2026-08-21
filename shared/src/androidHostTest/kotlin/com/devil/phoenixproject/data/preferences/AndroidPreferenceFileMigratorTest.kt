package com.devil.phoenixproject.data.preferences

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class AndroidPreferenceFileMigratorTest {
    @Test
    fun migratesEverySupportedValueTypeThroughBothPreferenceStores() {
        val operations = FakePreferenceFileOperations().apply {
            seed(
                PreferenceArtifact.LEGACY_PLAINTEXT,
                mapOf(
                    "string" to "value",
                    "boolean" to true,
                    "int" to 7,
                    "long" to 9L,
                    "float" to 1.5f,
                    "strings" to setOf("one", "two"),
                ),
            )
            seed(PreferenceArtifact.LEGACY_ENCRYPTED, mapOf("token" to "secret"))
        }

        AndroidPreferenceFileMigrator(operations).prepare()

        assertEquals(
            mapOf(
                "string" to "value",
                "boolean" to true,
                "int" to 7,
                "long" to 9L,
                "float" to 1.5f,
                "strings" to setOf("one", "two"),
                PREFERENCE_MIGRATION_MARKER to true,
            ),
            operations.values(PreferenceArtifact.TARGET_PLAINTEXT),
        )
        assertEquals(
            mapOf("token" to "secret", PREFERENCE_MIGRATION_MARKER to true),
            operations.values(PreferenceArtifact.TARGET_ENCRYPTED),
        )
        assertTrue(operations.exists(PreferenceArtifact.RECOVERY_PLAINTEXT))
        assertTrue(operations.exists(PreferenceArtifact.RECOVERY_ENCRYPTED))
        assertFalse(operations.exists(PreferenceArtifact.LEGACY_PLAINTEXT))
        assertFalse(operations.exists(PreferenceArtifact.LEGACY_ENCRYPTED))
    }

    @Test
    fun migratesEmptyStoresWithoutLosingTheRestartCheckpoint() {
        val operations = FakePreferenceFileOperations().apply {
            seed(PreferenceArtifact.LEGACY_PLAINTEXT, emptyMap())
            seed(PreferenceArtifact.LEGACY_ENCRYPTED, emptyMap())
        }

        AndroidPreferenceFileMigrator(operations).prepare()

        assertEquals(mapOf(PREFERENCE_MIGRATION_MARKER to true), operations.values(PreferenceArtifact.TARGET_PLAINTEXT))
        assertEquals(mapOf(PREFERENCE_MIGRATION_MARKER to true), operations.values(PreferenceArtifact.TARGET_ENCRYPTED))
        assertTrue(operations.exists(PreferenceArtifact.RECOVERY_PLAINTEXT))
        assertTrue(operations.exists(PreferenceArtifact.RECOVERY_ENCRYPTED))
    }

    @Test
    fun targetOnlyStartupDoesNotWriteOrDeleteAnything() {
        val operations = FakePreferenceFileOperations().apply {
            seed(PreferenceArtifact.TARGET_PLAINTEXT, mapOf("theme" to "dark"))
            seed(PreferenceArtifact.TARGET_ENCRYPTED, mapOf("token" to "new"))
        }

        AndroidPreferenceFileMigrator(operations).prepare()

        assertEquals(emptyList(), operations.mutations)
    }

    @Test
    fun sourceOnlyMigrationCreatesVerifiedRecoveryBeforeTargetAndCleanup() {
        val operations = FakePreferenceFileOperations().apply {
            seed(PreferenceArtifact.LEGACY_PLAINTEXT, mapOf("units" to "kg"))
        }

        AndroidPreferenceFileMigrator(operations).prepare()

        assertEquals(
            listOf(
                "replace:RECOVERY_PLAINTEXT",
                "missing:TARGET_PLAINTEXT",
                "delete:LEGACY_PLAINTEXT",
            ),
            operations.mutations,
        )
    }

    @Test
    fun existingPhoenixValuesWinKeyConflicts() {
        val operations = FakePreferenceFileOperations().apply {
            seed(
                PreferenceArtifact.LEGACY_PLAINTEXT,
                mapOf("conflict" to "legacy", "legacy-only" to 4),
            )
            seed(
                PreferenceArtifact.TARGET_PLAINTEXT,
                mapOf("conflict" to "phoenix", "phoenix-only" to false),
            )
        }

        AndroidPreferenceFileMigrator(operations).prepare()

        assertEquals("phoenix", operations.values(PreferenceArtifact.TARGET_PLAINTEXT)["conflict"])
        assertEquals(4, operations.values(PreferenceArtifact.TARGET_PLAINTEXT)["legacy-only"])
        assertEquals(false, operations.values(PreferenceArtifact.TARGET_PLAINTEXT)["phoenix-only"])
    }

    @Test
    fun failedRecoveryCommitPreservesSourceAndDoesNotTouchTarget() {
        val operations = FakePreferenceFileOperations().apply {
            seed(PreferenceArtifact.LEGACY_PLAINTEXT, mapOf("units" to "kg"))
            failedReplacement = PreferenceArtifact.RECOVERY_PLAINTEXT
        }

        val failure = assertFailsWith<PreferenceFileMigrationException> {
            AndroidPreferenceFileMigrator(operations).prepare()
        }

        assertEquals(PreferenceMigrationFailureCode.RECOVERY_WRITE_FAILED, failure.code)
        assertTrue(operations.exists(PreferenceArtifact.LEGACY_PLAINTEXT))
        assertFalse(operations.exists(PreferenceArtifact.TARGET_PLAINTEXT))
        assertEquals(listOf("replace:RECOVERY_PLAINTEXT"), operations.mutations)
    }

    @Test
    fun failedTargetCommitPreservesSourceAndVerifiedRecovery() {
        val operations = FakePreferenceFileOperations().apply {
            seed(PreferenceArtifact.LEGACY_PLAINTEXT, mapOf("units" to "kg"))
            failedMissingWrite = PreferenceArtifact.TARGET_PLAINTEXT
        }

        val failure = assertFailsWith<PreferenceFileMigrationException> {
            AndroidPreferenceFileMigrator(operations).prepare()
        }

        assertEquals(PreferenceMigrationFailureCode.TARGET_WRITE_FAILED, failure.code)
        assertTrue(operations.exists(PreferenceArtifact.LEGACY_PLAINTEXT))
        assertTrue(operations.exists(PreferenceArtifact.RECOVERY_PLAINTEXT))
        assertFalse(operations.mutations.contains("delete:LEGACY_PLAINTEXT"))
    }

    @Test
    fun encryptedStoreInitializationFailureBlocksBeforeAnyLegacyCleanup() {
        val operations = FakePreferenceFileOperations().apply {
            seed(PreferenceArtifact.LEGACY_PLAINTEXT, mapOf("units" to "kg"))
            seed(PreferenceArtifact.LEGACY_ENCRYPTED, mapOf("token" to "secret"))
            failedRead = PreferenceArtifact.LEGACY_ENCRYPTED
        }

        val failure = assertFailsWith<PreferenceFileMigrationException> {
            AndroidPreferenceFileMigrator(operations).prepare()
        }

        assertEquals(PreferenceMigrationFailureCode.STORE_INITIALIZATION_FAILED, failure.code)
        assertTrue(operations.exists(PreferenceArtifact.LEGACY_PLAINTEXT))
        assertTrue(operations.exists(PreferenceArtifact.LEGACY_ENCRYPTED))
        assertFalse(operations.mutations.any { it.startsWith("delete:LEGACY") })
    }

    @Test
    fun interruptedMigrationReusesVerifiedRecoveryAndFillsOnlyMissingTargetKeys() {
        val operations = FakePreferenceFileOperations().apply {
            seed(
                PreferenceArtifact.LEGACY_PLAINTEXT,
                mapOf("first" to "source", "second" to 2),
            )
            seed(
                PreferenceArtifact.RECOVERY_PLAINTEXT,
                mapOf(
                    "first" to "source",
                    "second" to 2,
                    PREFERENCE_MIGRATION_MARKER to true,
                ),
            )
            seed(PreferenceArtifact.TARGET_PLAINTEXT, mapOf("first" to "newer"))
        }

        AndroidPreferenceFileMigrator(operations).prepare()

        assertFalse(operations.mutations.contains("replace:RECOVERY_PLAINTEXT"))
        assertEquals("newer", operations.values(PreferenceArtifact.TARGET_PLAINTEXT)["first"])
        assertEquals(2, operations.values(PreferenceArtifact.TARGET_PLAINTEXT)["second"])
        assertFalse(operations.exists(PreferenceArtifact.LEGACY_PLAINTEXT))
    }

    @Test
    fun invalidInterruptedRecoveryIsRebuiltOnlyFromTheStillCanonicalSource() {
        val operations = FakePreferenceFileOperations().apply {
            seed(PreferenceArtifact.LEGACY_PLAINTEXT, mapOf("units" to "kg"))
            seed(
                PreferenceArtifact.RECOVERY_PLAINTEXT,
                mapOf("units" to "lb", PREFERENCE_MIGRATION_MARKER to true),
            )
        }

        AndroidPreferenceFileMigrator(operations).prepare()

        assertEquals(
            listOf(
                "delete:RECOVERY_PLAINTEXT",
                "replace:RECOVERY_PLAINTEXT",
                "missing:TARGET_PLAINTEXT",
                "delete:LEGACY_PLAINTEXT",
            ),
            operations.mutations,
        )
        assertEquals("kg", operations.values(PreferenceArtifact.RECOVERY_PLAINTEXT)["units"])
    }

    @Test
    fun recoveryOnlyReconstructsTargetAndRetainsRecoveryForNextLaunch() {
        val operations = FakePreferenceFileOperations().apply {
            seed(
                PreferenceArtifact.RECOVERY_ENCRYPTED,
                mapOf("token" to "secret", PREFERENCE_MIGRATION_MARKER to true),
            )
        }

        AndroidPreferenceFileMigrator(operations).prepare()

        assertEquals("secret", operations.values(PreferenceArtifact.TARGET_ENCRYPTED)["token"])
        assertTrue(operations.exists(PreferenceArtifact.RECOVERY_ENCRYPTED))
    }

    @Test
    fun secondLaunchDeletesRecoveryButRepeatedPreparationInOneProcessDoesNot() {
        val operations = FakePreferenceFileOperations().apply {
            seed(PreferenceArtifact.LEGACY_PLAINTEXT, mapOf("units" to "kg"))
        }
        val firstLaunch = AndroidPreferenceFileMigrator(operations)
        firstLaunch.prepare()
        firstLaunch.prepare()
        assertTrue(operations.exists(PreferenceArtifact.RECOVERY_PLAINTEXT))

        AndroidPreferenceFileMigrator(operations).prepare()

        assertFalse(operations.exists(PreferenceArtifact.RECOVERY_PLAINTEXT))
    }

    @Test
    fun cleanupFailureIsLoggedAndRetriedWithoutRestoringStaleValues() {
        val logs = mutableListOf<String>()
        val operations = FakePreferenceFileOperations().apply {
            seed(
                PreferenceArtifact.TARGET_PLAINTEXT,
                mapOf("units" to "lb", PREFERENCE_MIGRATION_MARKER to true),
            )
            seed(
                PreferenceArtifact.RECOVERY_PLAINTEXT,
                mapOf("units" to "kg", PREFERENCE_MIGRATION_MARKER to true),
            )
            failedDelete = PreferenceArtifact.RECOVERY_PLAINTEXT
        }

        AndroidPreferenceFileMigrator(operations) { message, _ -> logs += message }.prepare()

        assertEquals("lb", operations.values(PreferenceArtifact.TARGET_PLAINTEXT)["units"])
        assertTrue(operations.exists(PreferenceArtifact.RECOVERY_PLAINTEXT))
        assertTrue(logs.single().contains("retry"))

        operations.failedDelete = null
        AndroidPreferenceFileMigrator(operations).prepare()
        assertFalse(operations.exists(PreferenceArtifact.RECOVERY_PLAINTEXT))
        assertEquals("lb", operations.values(PreferenceArtifact.TARGET_PLAINTEXT)["units"])
    }

    @Test
    fun cleanRestartCanDeleteRecoveryWithoutReopeningEncryptedRecoveryData() {
        val operations = FakePreferenceFileOperations().apply {
            seed(
                PreferenceArtifact.TARGET_ENCRYPTED,
                mapOf("token" to "new", PREFERENCE_MIGRATION_MARKER to true),
            )
            seed(
                PreferenceArtifact.RECOVERY_ENCRYPTED,
                mapOf("token" to "old", PREFERENCE_MIGRATION_MARKER to true),
            )
            failedRead = PreferenceArtifact.RECOVERY_ENCRYPTED
        }

        AndroidPreferenceFileMigrator(operations).prepare()

        assertFalse(operations.exists(PreferenceArtifact.RECOVERY_ENCRYPTED))
        assertEquals("new", operations.values(PreferenceArtifact.TARGET_ENCRYPTED)["token"])
    }

    private class FakePreferenceFileOperations : PreferenceFileOperations {
        private val files = mutableMapOf<PreferenceArtifact, MutableMap<String, Any>>()
        val mutations = mutableListOf<String>()
        var failedReplacement: PreferenceArtifact? = null
        var failedMissingWrite: PreferenceArtifact? = null
        var failedRead: PreferenceArtifact? = null
        var failedDelete: PreferenceArtifact? = null

        fun seed(artifact: PreferenceArtifact, values: Map<String, Any>) {
            files[artifact] = values.toMutableMap()
        }

        fun values(artifact: PreferenceArtifact): Map<String, Any> = files[artifact].orEmpty()

        override fun exists(artifact: PreferenceArtifact): Boolean = files.containsKey(artifact)

        override fun read(artifact: PreferenceArtifact): Map<String, Any> {
            if (failedRead == artifact) throw SecurityException("store unavailable")
            return files[artifact]?.toMap().orEmpty()
        }

        override fun replace(artifact: PreferenceArtifact, values: Map<String, Any>): Boolean {
            mutations += "replace:$artifact"
            if (failedReplacement == artifact) return false
            files[artifact] = values.toMutableMap()
            return true
        }

        override fun writeMissing(artifact: PreferenceArtifact, values: Map<String, Any>): Boolean {
            mutations += "missing:$artifact"
            if (failedMissingWrite == artifact) return false
            val target = files.getOrPut(artifact) { mutableMapOf() }
            for ((key, value) in values) target.putIfAbsent(key, value)
            return true
        }

        override fun delete(artifact: PreferenceArtifact): Boolean {
            mutations += "delete:$artifact"
            if (failedDelete == artifact) return false
            files.remove(artifact)
            return true
        }
    }
}
