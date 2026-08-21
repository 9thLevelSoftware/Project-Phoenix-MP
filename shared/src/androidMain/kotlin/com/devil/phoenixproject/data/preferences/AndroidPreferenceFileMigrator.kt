package com.devil.phoenixproject.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.devil.phoenixproject.StartupDiagnosticFailure
import java.io.File

internal const val PREFERENCE_MIGRATION_MARKER = "__phoenix_preference_filename_migration_v1"

internal object AndroidPreferenceFileNames {
    const val LEGACY_PLAINTEXT = "vitruvian_preferences"
    const val TARGET_PLAINTEXT = "phoenix_preferences"
    const val RECOVERY_PLAINTEXT = "phoenix_preferences_recovery"
    const val LEGACY_ENCRYPTED = "vitruvian_secure_preferences"
    const val TARGET_ENCRYPTED = "phoenix_secure_preferences"
    const val RECOVERY_ENCRYPTED = "phoenix_secure_preferences_recovery"
}

internal enum class PreferenceArtifact {
    LEGACY_PLAINTEXT,
    TARGET_PLAINTEXT,
    RECOVERY_PLAINTEXT,
    LEGACY_ENCRYPTED,
    TARGET_ENCRYPTED,
    RECOVERY_ENCRYPTED,
}

internal enum class PreferenceMigrationFailureCode {
    STORE_INITIALIZATION_FAILED,
    RECOVERY_WRITE_FAILED,
    RECOVERY_VALIDATION_FAILED,
    TARGET_WRITE_FAILED,
    TARGET_VALIDATION_FAILED,
}

internal class PreferenceFileMigrationException(
    val code: PreferenceMigrationFailureCode,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause), StartupDiagnosticFailure {
    override val startupDiagnosticCode: String = "PREFERENCES_${code.name}"
    override val startupRetryAllowed: Boolean = true
}

internal interface PreferenceFileOperations {
    fun exists(artifact: PreferenceArtifact): Boolean

    fun read(artifact: PreferenceArtifact): Map<String, Any>

    fun replace(artifact: PreferenceArtifact, values: Map<String, Any>): Boolean

    fun writeMissing(artifact: PreferenceArtifact, values: Map<String, Any>): Boolean

    fun delete(artifact: PreferenceArtifact): Boolean
}

/**
 * Migrates both Android preference files as a single startup prerequisite.
 * Recovery stores are retained for the rest of the process that created them,
 * then removed by a new migrator instance on the next clean startup.
 */
internal class AndroidPreferenceFileMigrator(
    private val operations: PreferenceFileOperations,
    private val logCleanupFailure: (String, Throwable?) -> Unit = { _, _ -> },
) {
    private val processLock = Any()
    private var prepared = false

    fun prepare() = synchronized(processLock) {
        if (prepared) return@synchronized

        val states = PREFERENCE_PAIRS.associateWith { pair ->
            PreferencePairState(
                sourceExists = operations.exists(pair.source),
                targetExists = operations.exists(pair.target),
                recoveryExists = operations.exists(pair.recovery),
            )
        }
        val sourceSnapshots = states
            .filterValues { state -> state.sourceExists }
            .mapValues { (pair, _) -> readStore(pair.source) }
        val reconstructionNeeded = states.any { (_, state) ->
            !state.sourceExists && !state.targetExists && state.recoveryExists
        }
        val migrationThisLaunch = sourceSnapshots.isNotEmpty() || reconstructionNeeded

        for ((pair, state) in states) {
            when {
                state.sourceExists -> migrateSource(pair, sourceSnapshots.getValue(pair), state)
                !state.targetExists && state.recoveryExists -> reconstructTarget(pair)
            }
        }

        for (pair in sourceSnapshots.keys) {
            cleanupWithRetry(pair.source, "Legacy preference cleanup failed; it will retry next launch.")
        }

        if (!migrationThisLaunch) {
            for ((pair, state) in states) {
                if (state.targetExists && state.recoveryExists) {
                    readStore(pair.target)
                    cleanupWithRetry(pair.recovery, "Preference recovery cleanup failed; it will retry next launch.")
                }
            }
        }

        prepared = true
    }

    private fun migrateSource(
        pair: PreferencePair,
        rawSource: Map<String, Any>,
        state: PreferencePairState,
    ) {
        val source = rawSource - PREFERENCE_MIGRATION_MARKER
        val expectedRecovery = source + (PREFERENCE_MIGRATION_MARKER to true)

        val recoveryValid = if (state.recoveryExists) {
            runCatching { readStore(pair.recovery) }
                .getOrNull()
                ?.let { recovery -> runCatching { validateRecovery(recovery) }.getOrNull() == source }
                ?: false
        } else {
            false
        }

        if (!recoveryValid) {
            if (state.recoveryExists && !operations.delete(pair.recovery)) {
                throw PreferenceFileMigrationException(
                    PreferenceMigrationFailureCode.RECOVERY_VALIDATION_FAILED,
                    "An incomplete preference recovery store could not be replaced.",
                )
            }
            if (!operations.replace(pair.recovery, expectedRecovery)) {
                throw PreferenceFileMigrationException(
                    PreferenceMigrationFailureCode.RECOVERY_WRITE_FAILED,
                    "A preference recovery store could not be committed.",
                )
            }
            val writtenRecovery = readStore(pair.recovery)
            if (writtenRecovery != expectedRecovery) {
                throw PreferenceFileMigrationException(
                    PreferenceMigrationFailureCode.RECOVERY_VALIDATION_FAILED,
                    "A preference recovery store could not be verified.",
                )
            }
        }

        writeAndVerifyTarget(pair.target, source)
    }

    private fun reconstructTarget(pair: PreferencePair) {
        val source = validateRecovery(readStore(pair.recovery))
        writeAndVerifyTarget(pair.target, source)
    }

    private fun writeAndVerifyTarget(
        target: PreferenceArtifact,
        source: Map<String, Any>,
    ) {
        val targetBefore = readStore(target)
        val desired = source + (PREFERENCE_MIGRATION_MARKER to true)
        val expected = desired + targetBefore
        if (!operations.writeMissing(target, desired)) {
            throw PreferenceFileMigrationException(
                PreferenceMigrationFailureCode.TARGET_WRITE_FAILED,
                "Phoenix preferences could not be committed.",
            )
        }
        if (readStore(target) != expected) {
            throw PreferenceFileMigrationException(
                PreferenceMigrationFailureCode.TARGET_VALIDATION_FAILED,
                "Phoenix preferences could not be verified after migration.",
            )
        }
    }

    private fun validateRecovery(recovery: Map<String, Any>): Map<String, Any> {
        if (recovery[PREFERENCE_MIGRATION_MARKER] != true) {
            throw PreferenceFileMigrationException(
                PreferenceMigrationFailureCode.RECOVERY_VALIDATION_FAILED,
                "The preference recovery store is incomplete.",
            )
        }
        return recovery - PREFERENCE_MIGRATION_MARKER
    }

    private fun readStore(artifact: PreferenceArtifact): Map<String, Any> = try {
        operations.read(artifact)
    } catch (failure: PreferenceFileMigrationException) {
        throw failure
    } catch (failure: Throwable) {
        throw PreferenceFileMigrationException(
            PreferenceMigrationFailureCode.STORE_INITIALIZATION_FAILED,
            "A required preference store could not be opened safely.",
            failure,
        )
    }

    private fun cleanupWithRetry(artifact: PreferenceArtifact, message: String) {
        try {
            if (!operations.delete(artifact)) logCleanupFailure(message, null)
        } catch (failure: Throwable) {
            logCleanupFailure(message, failure)
        }
    }

    private data class PreferencePairState(
        val sourceExists: Boolean,
        val targetExists: Boolean,
        val recoveryExists: Boolean,
    )

    private data class PreferencePair(
        val source: PreferenceArtifact,
        val target: PreferenceArtifact,
        val recovery: PreferenceArtifact,
    )

    private companion object {
        val PREFERENCE_PAIRS = listOf(
            PreferencePair(
                PreferenceArtifact.LEGACY_PLAINTEXT,
                PreferenceArtifact.TARGET_PLAINTEXT,
                PreferenceArtifact.RECOVERY_PLAINTEXT,
            ),
            PreferencePair(
                PreferenceArtifact.LEGACY_ENCRYPTED,
                PreferenceArtifact.TARGET_ENCRYPTED,
                PreferenceArtifact.RECOVERY_ENCRYPTED,
            ),
        )
    }
}

internal class AndroidPreferenceFileOperations(
    private val context: Context,
    private val encryptedPreferences: (String) -> SharedPreferences,
) : PreferenceFileOperations {
    override fun exists(artifact: PreferenceArtifact): Boolean {
        val file = preferenceFile(artifact)
        return file.exists() || File("${file.path}.bak").exists()
    }

    override fun read(artifact: PreferenceArtifact): Map<String, Any> = preferences(artifact).all.mapValues { (_, value) ->
        normalizeValue(value)
    }

    override fun replace(artifact: PreferenceArtifact, values: Map<String, Any>): Boolean {
        val editor = preferences(artifact).edit().clear()
        values.forEach { (key, value) -> editor.putSupportedValue(key, value) }
        return editor.commit()
    }

    override fun writeMissing(artifact: PreferenceArtifact, values: Map<String, Any>): Boolean {
        val preferences = preferences(artifact)
        val missing = values.filterKeys { key -> !preferences.contains(key) }
        if (missing.isEmpty()) return true
        val editor = preferences.edit()
        missing.forEach { (key, value) -> editor.putSupportedValue(key, value) }
        return editor.commit()
    }

    override fun delete(artifact: PreferenceArtifact): Boolean {
        context.deleteSharedPreferences(name(artifact))
        return !exists(artifact)
    }

    fun targetPlaintext(): SharedPreferences = preferences(PreferenceArtifact.TARGET_PLAINTEXT)

    fun targetEncrypted(): SharedPreferences = preferences(PreferenceArtifact.TARGET_ENCRYPTED)

    private fun preferences(artifact: PreferenceArtifact): SharedPreferences = if (artifact.isEncrypted) {
        encryptedPreferences(name(artifact))
    } else {
        context.getSharedPreferences(name(artifact), Context.MODE_PRIVATE)
    }

    private fun preferenceFile(artifact: PreferenceArtifact): File = File(
        File(context.applicationInfo.dataDir, "shared_prefs"),
        "${name(artifact)}.xml",
    )

    private fun normalizeValue(value: Any?): Any = when (value) {
        is String -> value
        is Boolean -> value
        is Int -> value
        is Long -> value
        is Float -> value
        is Set<*> -> {
            check(value.all { item -> item is String }) { "Preference string set contains a non-string value" }
            @Suppress("UNCHECKED_CAST")
            (value as Set<String>).toSet()
        }
        else -> error("Unsupported preference value type: ${value?.let { it::class.simpleName } ?: "null"}")
    }

    private fun SharedPreferences.Editor.putSupportedValue(key: String, value: Any) {
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> {
                check(value.all { item -> item is String }) { "Preference string set contains a non-string value" }
                @Suppress("UNCHECKED_CAST")
                putStringSet(key, (value as Set<String>).toSet())
            }
            else -> error("Unsupported preference value type: ${value::class.simpleName}")
        }
    }

    private fun name(artifact: PreferenceArtifact): String = when (artifact) {
        PreferenceArtifact.LEGACY_PLAINTEXT -> AndroidPreferenceFileNames.LEGACY_PLAINTEXT
        PreferenceArtifact.TARGET_PLAINTEXT -> AndroidPreferenceFileNames.TARGET_PLAINTEXT
        PreferenceArtifact.RECOVERY_PLAINTEXT -> AndroidPreferenceFileNames.RECOVERY_PLAINTEXT
        PreferenceArtifact.LEGACY_ENCRYPTED -> AndroidPreferenceFileNames.LEGACY_ENCRYPTED
        PreferenceArtifact.TARGET_ENCRYPTED -> AndroidPreferenceFileNames.TARGET_ENCRYPTED
        PreferenceArtifact.RECOVERY_ENCRYPTED -> AndroidPreferenceFileNames.RECOVERY_ENCRYPTED
    }

    private val PreferenceArtifact.isEncrypted: Boolean
        get() = this == PreferenceArtifact.LEGACY_ENCRYPTED ||
            this == PreferenceArtifact.TARGET_ENCRYPTED ||
            this == PreferenceArtifact.RECOVERY_ENCRYPTED
}
