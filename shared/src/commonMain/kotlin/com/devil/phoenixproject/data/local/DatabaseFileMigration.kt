package com.devil.phoenixproject.data.local

import com.devil.phoenixproject.StartupDiagnosticFailure

internal object DatabaseFileNames {
    const val LEGACY = "vitruvian.db"
    const val TARGET = "phoenix.db"
    const val STAGING = "phoenix.db.migrating"
    const val RECOVERY = "phoenix-recovery.db"
    const val LOCK = "phoenix-db-migration.lock"
}

internal enum class DatabaseArtifact {
    LEGACY,
    TARGET,
    RECOVERY,
    STAGING,
}

internal data class DatabaseFileLayout(
    val legacyExists: Boolean,
    val targetExists: Boolean,
    val recoveryExists: Boolean,
    val stagingExists: Boolean,
    val legacySidecarsExist: Boolean,
)

internal data class DatabaseFingerprint(
    val fileSize: Long,
    val userVersion: Long,
    val pageCount: Long,
    val freePageCount: Long,
)

internal enum class DatabaseMigrationFailureCode {
    DUAL_DATABASES,
    INTEGRITY_CHECK_FAILED,
    CHECKPOINT_FAILED,
    RECOVERY_COPY_FAILED,
    ATOMIC_MOVE_FAILED,
    LEGACY_CLEANUP_FAILED,
    TARGET_VALIDATION_FAILED,
}

internal class DatabaseFileMigrationException(
    val code: DatabaseMigrationFailureCode,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause),
    StartupDiagnosticFailure {
    override val startupDiagnosticCode: String = "DB_${code.name}"
    override val startupRetryAllowed: Boolean = code != DatabaseMigrationFailureCode.DUAL_DATABASES
}

internal data class DatabasePreparation(
    val migratedThisLaunch: Boolean,
    val recoveryCleanupDue: Boolean,
)

internal interface DatabaseFileOperations {
    fun inspect(): DatabaseFileLayout

    fun checkpointAndValidate(artifact: DatabaseArtifact): DatabaseFingerprint

    fun validate(artifact: DatabaseArtifact): DatabaseFingerprint

    fun copy(from: DatabaseArtifact, to: DatabaseArtifact)

    fun sync(artifact: DatabaseArtifact)

    fun atomicMove(from: DatabaseArtifact, to: DatabaseArtifact)

    fun delete(artifact: DatabaseArtifact)

    fun deleteLegacySidecars()

    fun <T> withExclusiveMigrationLock(block: () -> T): T
}

/**
 * Moves the released database filename to its neutral Phoenix name without
 * allowing SQLDelight to create or upgrade a database until a canonical file
 * has been selected safely.
 */
internal class DatabaseFileMigrationCoordinator(
    private val operations: DatabaseFileOperations,
) {
    private var recoveryCreatedThisProcess = false
    private var targetValidationPending = false

    fun prepareTarget(): DatabasePreparation = operations.withExclusiveMigrationLock {
        val layout = operations.inspect()

        if (layout.legacyExists && layout.targetExists) {
            throw DatabaseFileMigrationException(
                DatabaseMigrationFailureCode.DUAL_DATABASES,
                "Both legacy and Phoenix database files exist; automatic recovery is disabled.",
            )
        }

        val preparation = when {
            targetValidationPending && layout.targetExists && layout.recoveryExists -> {
                // The previous open reached cutover but never completed post-open
                // validation. Restore the known-good snapshot before retrying rather
                // than reopening a target that may have a partially-healed schema.
                reconstructFromRecovery(layout)
            }

            layout.targetExists -> prepareExistingTarget(layout)

            layout.legacyExists -> migrateLegacy(layout)

            layout.recoveryExists -> reconstructFromRecovery(layout)

            layout.stagingExists -> throw DatabaseFileMigrationException(
                DatabaseMigrationFailureCode.RECOVERY_COPY_FAILED,
                "A migration staging file exists without a verified database source.",
            )

            else -> DatabasePreparation(
                migratedThisLaunch = false,
                recoveryCleanupDue = false,
            )
        }

        targetValidationPending = preparation.migratedThisLaunch || preparation.recoveryCleanupDue
        if (preparation.migratedThisLaunch) {
            recoveryCreatedThisProcess = true
        }

        if (preparation.recoveryCleanupDue && recoveryCreatedThisProcess) {
            preparation.copy(recoveryCleanupDue = false)
        } else {
            preparation
        }
    }

    /**
     * Called only after SQLDelight schema migration and reconciliation succeed.
     * A previous-launch recovery is deleted after, never before, target
     * validation.
     */
    fun targetValidated(preparation: DatabasePreparation) {
        operations.withExclusiveMigrationLock {
            try {
                operations.validate(DatabaseArtifact.TARGET)
            } catch (failure: Throwable) {
                // Keep the recovery snapshot armed. A retry must restore it before
                // reopening this target, even when the target is still quick_check-clean.
                targetValidationPending = true
                throw DatabaseFileMigrationException(
                    DatabaseMigrationFailureCode.TARGET_VALIDATION_FAILED,
                    "The Phoenix database failed post-migration validation.",
                    failure,
                )
            }

            if (preparation.recoveryCleanupDue) {
                try {
                    operations.delete(DatabaseArtifact.RECOVERY)
                } catch (failure: Throwable) {
                    targetValidationPending = true
                    throw DatabaseFileMigrationException(
                        DatabaseMigrationFailureCode.LEGACY_CLEANUP_FAILED,
                        "The validated database opened, but its prior recovery file could not be removed.",
                        failure,
                    )
                }
            }
            targetValidationPending = false
        }
    }

    private fun prepareExistingTarget(layout: DatabaseFileLayout): DatabasePreparation {
        if (layout.stagingExists) {
            deleteIncompleteStaging()
        }
        if (!layout.recoveryExists) {
            return DatabasePreparation(
                migratedThisLaunch = false,
                recoveryCleanupDue = false,
            )
        }

        return try {
            operations.validate(DatabaseArtifact.TARGET)
            DatabasePreparation(
                migratedThisLaunch = false,
                recoveryCleanupDue = true,
            )
        } catch (_: Throwable) {
            // A failed launch can leave a corrupt target next to its verified
            // recovery copy. Validate the recovery first, then replace only the
            // unusable target through the same staging/cutover path.
            reconstructFromRecovery(layout)
        }
    }

    private fun migrateLegacy(layout: DatabaseFileLayout): DatabasePreparation {
        if (layout.stagingExists) {
            deleteIncompleteStaging()
        }

        val sourceFingerprint = try {
            operations.checkpointAndValidate(DatabaseArtifact.LEGACY)
        } catch (failure: DatabaseFileMigrationException) {
            throw failure
        } catch (failure: Throwable) {
            throw DatabaseFileMigrationException(
                DatabaseMigrationFailureCode.CHECKPOINT_FAILED,
                "The legacy database could not be checkpointed safely.",
                failure,
            )
        }

        if (layout.recoveryExists) {
            val recoveryFingerprint = validateSource(DatabaseArtifact.RECOVERY)
            requireMatchingFingerprint(sourceFingerprint, recoveryFingerprint)
        } else {
            createVerifiedCopy(
                source = DatabaseArtifact.LEGACY,
                destination = DatabaseArtifact.RECOVERY,
                sourceFingerprint = sourceFingerprint,
            )
        }

        try {
            operations.deleteLegacySidecars()
        } catch (failure: Throwable) {
            throw DatabaseFileMigrationException(
                DatabaseMigrationFailureCode.LEGACY_CLEANUP_FAILED,
                "Legacy database sidecars could not be removed before cutover.",
                failure,
            )
        }

        atomicMove(DatabaseArtifact.LEGACY, DatabaseArtifact.TARGET)
        return DatabasePreparation(
            migratedThisLaunch = true,
            recoveryCleanupDue = false,
        )
    }

    private fun reconstructFromRecovery(layout: DatabaseFileLayout): DatabasePreparation {
        if (layout.stagingExists) {
            deleteIncompleteStaging()
        }

        val recoveryFingerprint = validateSource(DatabaseArtifact.RECOVERY)
        if (layout.targetExists) {
            deleteTarget()
        }
        createVerifiedCopy(
            source = DatabaseArtifact.RECOVERY,
            destination = DatabaseArtifact.TARGET,
            sourceFingerprint = recoveryFingerprint,
        )
        return DatabasePreparation(
            migratedThisLaunch = true,
            recoveryCleanupDue = false,
        )
    }

    private fun deleteTarget() {
        try {
            operations.delete(DatabaseArtifact.TARGET)
        } catch (failure: Throwable) {
            throw DatabaseFileMigrationException(
                DatabaseMigrationFailureCode.RECOVERY_COPY_FAILED,
                "The failed Phoenix database could not be replaced from recovery.",
                failure,
            )
        }
    }

    private fun createVerifiedCopy(
        source: DatabaseArtifact,
        destination: DatabaseArtifact,
        sourceFingerprint: DatabaseFingerprint,
    ) {
        try {
            operations.copy(source, DatabaseArtifact.STAGING)
            operations.sync(DatabaseArtifact.STAGING)
            val stagingFingerprint = operations.validate(DatabaseArtifact.STAGING)
            requireMatchingFingerprint(sourceFingerprint, stagingFingerprint)
        } catch (failure: DatabaseFileMigrationException) {
            throw failure
        } catch (failure: Throwable) {
            throw DatabaseFileMigrationException(
                DatabaseMigrationFailureCode.RECOVERY_COPY_FAILED,
                "A verified database recovery copy could not be created.",
                failure,
            )
        }

        atomicMove(DatabaseArtifact.STAGING, destination)
    }

    private fun validateSource(artifact: DatabaseArtifact): DatabaseFingerprint = try {
        operations.validate(artifact)
    } catch (failure: DatabaseFileMigrationException) {
        throw failure
    } catch (failure: Throwable) {
        throw DatabaseFileMigrationException(
            DatabaseMigrationFailureCode.INTEGRITY_CHECK_FAILED,
            "The ${artifact.name.lowercase()} database failed integrity validation.",
            failure,
        )
    }

    private fun requireMatchingFingerprint(
        source: DatabaseFingerprint,
        candidate: DatabaseFingerprint,
    ) {
        if (candidate != source) {
            throw DatabaseFileMigrationException(
                DatabaseMigrationFailureCode.RECOVERY_COPY_FAILED,
                "The recovery database fingerprint does not match its verified source.",
            )
        }
    }

    private fun deleteIncompleteStaging() {
        try {
            operations.delete(DatabaseArtifact.STAGING)
        } catch (failure: Throwable) {
            throw DatabaseFileMigrationException(
                DatabaseMigrationFailureCode.RECOVERY_COPY_FAILED,
                "An incomplete migration staging file could not be removed.",
                failure,
            )
        }
    }

    private fun atomicMove(from: DatabaseArtifact, to: DatabaseArtifact) {
        try {
            operations.atomicMove(from, to)
        } catch (failure: Throwable) {
            throw DatabaseFileMigrationException(
                DatabaseMigrationFailureCode.ATOMIC_MOVE_FAILED,
                "The database file could not be atomically promoted from ${from.name.lowercase()} to ${to.name.lowercase()}.",
                failure,
            )
        }
    }
}
