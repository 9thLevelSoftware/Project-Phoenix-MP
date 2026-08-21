package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.DatabaseFileContext
import co.touchlab.sqliter.NO_VERSION_CHECK
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*
import platform.posix.LOCK_EX
import platform.posix.LOCK_UN
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close
import platform.posix.flock
import platform.posix.open

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosDatabaseFileOperations : DatabaseFileOperations {
    private val fileManager = NSFileManager.defaultManager

    override fun inspect(): DatabaseFileLayout = DatabaseFileLayout(
        legacyExists = exists(DatabaseArtifact.LEGACY),
        targetExists = exists(DatabaseArtifact.TARGET),
        recoveryExists = exists(DatabaseArtifact.RECOVERY),
        stagingExists = exists(DatabaseArtifact.STAGING),
        legacySidecarsExist = LEGACY_SIDECAR_SUFFIXES.any { suffix ->
            fileManager.fileExistsAtPath("${path(DatabaseArtifact.LEGACY)}$suffix")
        },
    )

    override fun checkpointAndValidate(artifact: DatabaseArtifact): DatabaseFingerprint {
        val driver = rawDriver(artifact)
        try {
            var busy: Long? = null
            driver.executeQuery(
                identifier = null,
                sql = "PRAGMA wal_checkpoint(TRUNCATE)",
                mapper = { cursor ->
                    if (cursor.next().value) busy = cursor.getLong(0)
                    QueryResult.Value(Unit)
                },
                parameters = 0,
            )
            if (busy != 0L) {
                throw DatabaseFileMigrationException(
                    DatabaseMigrationFailureCode.CHECKPOINT_FAILED,
                    "The ${artifact.name.lowercase()} database WAL checkpoint was busy.",
                )
            }
            return validatedFingerprint(driver, artifact)
        } catch (failure: DatabaseFileMigrationException) {
            throw failure
        } catch (failure: Throwable) {
            throw DatabaseFileMigrationException(
                DatabaseMigrationFailureCode.CHECKPOINT_FAILED,
                "The ${artifact.name.lowercase()} database WAL checkpoint failed.",
                failure,
            )
        } finally {
            driver.close()
        }
    }

    override fun validate(artifact: DatabaseArtifact): DatabaseFingerprint {
        val driver = rawDriver(artifact)
        return try {
            validatedFingerprint(driver, artifact)
        } catch (failure: DatabaseFileMigrationException) {
            throw failure
        } catch (failure: Throwable) {
            throw integrityFailure(artifact, failure)
        } finally {
            driver.close()
        }
    }

    override fun copy(from: DatabaseArtifact, to: DatabaseArtifact) {
        check(!exists(to)) { "Refusing to replace existing $to database artifact" }
        check(fileManager.copyItemAtPath(path(from), path(to), error = null)) {
            "Could not copy $from to $to"
        }
    }

    override fun sync(artifact: DatabaseArtifact) {
        val handle = checkNotNull(NSFileHandle.fileHandleForWritingAtPath(path(artifact))) {
            "Could not open $artifact for synchronous flush"
        }
        try {
            handle.synchronizeFile()
        } finally {
            handle.closeFile()
        }
    }

    override fun atomicMove(from: DatabaseArtifact, to: DatabaseArtifact) {
        check(!exists(to)) { "Refusing to replace existing $to database artifact" }
        deleteSidecars(from)
        check(fileManager.moveItemAtPath(path(from), toPath = path(to), error = null)) {
            "Could not atomically move $from to $to"
        }
    }

    override fun delete(artifact: DatabaseArtifact) {
        val artifactPath = path(artifact)
        if (fileManager.fileExistsAtPath(artifactPath)) {
            check(fileManager.removeItemAtPath(artifactPath, error = null)) {
                "Could not delete $artifact database artifact"
            }
        }
        deleteSidecars(artifact)
    }

    override fun deleteLegacySidecars() {
        for (suffix in LEGACY_SIDECAR_SUFFIXES) {
            val sidecarPath = "${path(DatabaseArtifact.LEGACY)}$suffix"
            if (fileManager.fileExistsAtPath(sidecarPath)) {
                check(fileManager.removeItemAtPath(sidecarPath, error = null)) {
                    "Could not delete a legacy database sidecar"
                }
            }
        }
    }

    override fun <T> withExclusiveMigrationLock(block: () -> T): T {
        val lockDescriptor = open(
            path(DatabaseFileNames.LOCK),
            O_CREAT or O_RDWR,
            S_IRUSR or S_IWUSR,
        )
        check(lockDescriptor >= 0) { "Could not open the database migration lock" }
        try {
            check(flock(lockDescriptor, LOCK_EX) == 0) { "Could not acquire the database migration lock" }
            return block()
        } finally {
            flock(lockDescriptor, LOCK_UN)
            close(lockDescriptor)
        }
    }

    private fun rawDriver(artifact: DatabaseArtifact): NativeSqliteDriver = NativeSqliteDriver(
        DatabaseConfiguration(
            name = name(artifact),
            version = NO_VERSION_CHECK,
            create = {},
        ),
    )

    private fun validatedFingerprint(
        driver: SqlDriver,
        artifact: DatabaseArtifact,
    ): DatabaseFingerprint {
        val quickCheckRows = mutableListOf<String?>()
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA quick_check",
            mapper = { cursor ->
                while (cursor.next().value) quickCheckRows += cursor.getString(0)
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        if (quickCheckRows != listOf("ok")) {
            throw integrityFailure(artifact)
        }

        return DatabaseFingerprint(
            fileSize = fileSize(artifact),
            userVersion = driver.queryLong("PRAGMA user_version"),
            pageCount = driver.queryLong("PRAGMA page_count"),
            freePageCount = driver.queryLong("PRAGMA freelist_count"),
        )
    }

    private fun SqlDriver.queryLong(sql: String): Long {
        var value: Long? = null
        executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                if (cursor.next().value) value = cursor.getLong(0)
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return checkNotNull(value) { "$sql returned no value" }
    }

    private fun fileSize(artifact: DatabaseArtifact): Long {
        val attributes = fileManager.attributesOfItemAtPath(path(artifact), error = null)
        return (attributes?.get(NSFileSize) as? NSNumber)?.longValue
            ?: error("Could not read the $artifact database size")
    }

    private fun exists(artifact: DatabaseArtifact): Boolean = fileManager.fileExistsAtPath(path(artifact))

    private fun deleteSidecars(artifact: DatabaseArtifact) {
        for (suffix in LEGACY_SIDECAR_SUFFIXES) {
            val sidecarPath = "${path(artifact)}$suffix"
            if (fileManager.fileExistsAtPath(sidecarPath)) {
                check(fileManager.removeItemAtPath(sidecarPath, error = null)) {
                    "Could not delete a database sidecar for $artifact"
                }
            }
        }
    }

    private fun path(artifact: DatabaseArtifact): String = path(name(artifact))

    private fun path(name: String): String = DatabaseFileContext.databasePath(name, null)

    private fun name(artifact: DatabaseArtifact): String = when (artifact) {
        DatabaseArtifact.LEGACY -> DatabaseFileNames.LEGACY
        DatabaseArtifact.TARGET -> DatabaseFileNames.TARGET
        DatabaseArtifact.RECOVERY -> DatabaseFileNames.RECOVERY
        DatabaseArtifact.STAGING -> DatabaseFileNames.STAGING
    }

    private fun integrityFailure(
        artifact: DatabaseArtifact,
        cause: Throwable? = null,
    ) = DatabaseFileMigrationException(
        DatabaseMigrationFailureCode.INTEGRITY_CHECK_FAILED,
        "The ${artifact.name.lowercase()} database failed SQLite quick_check.",
        cause,
    )

    private companion object {
        val LEGACY_SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")
    }
}
