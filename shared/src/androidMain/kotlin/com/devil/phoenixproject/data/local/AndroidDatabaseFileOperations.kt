package com.devil.phoenixproject.data.local

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class AndroidDatabaseFileOperations(
    private val context: Context,
) : DatabaseFileOperations {
    override fun inspect(): DatabaseFileLayout = DatabaseFileLayout(
        legacyExists = file(DatabaseArtifact.LEGACY).exists(),
        targetExists = file(DatabaseArtifact.TARGET).exists(),
        recoveryExists = file(DatabaseArtifact.RECOVERY).exists(),
        stagingExists = file(DatabaseArtifact.STAGING).exists(),
        legacySidecarsExist = LEGACY_SIDECAR_SUFFIXES.any { suffix ->
            context.getDatabasePath("${DatabaseFileNames.LEGACY}$suffix").exists()
        },
    )

    override fun checkpointAndValidate(artifact: DatabaseArtifact): DatabaseFingerprint {
        val databaseFile = file(artifact)
        try {
            val values = SQLiteDatabase.openDatabase(
                databaseFile.path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
                PRESERVING_ERROR_HANDLER,
            ).use { database ->
                database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                    if (!cursor.moveToFirst() || cursor.getInt(0) != 0) {
                        throw DatabaseFileMigrationException(
                            DatabaseMigrationFailureCode.CHECKPOINT_FAILED,
                            "The ${artifact.name.lowercase()} database WAL checkpoint was busy.",
                        )
                    }
                }
                database.validatedPragmas(artifact)
            }
            return values.toFingerprint(databaseFile.length())
        } catch (failure: DatabaseFileMigrationException) {
            throw failure
        } catch (failure: SQLiteDatabaseCorruptException) {
            throw integrityFailure(artifact, failure)
        } catch (failure: SQLiteException) {
            if (failure.indicatesCorruption()) {
                throw integrityFailure(artifact, failure)
            }
            throw DatabaseFileMigrationException(
                DatabaseMigrationFailureCode.CHECKPOINT_FAILED,
                "The ${artifact.name.lowercase()} database WAL checkpoint failed.",
                failure,
            )
        }
    }

    override fun validate(artifact: DatabaseArtifact): DatabaseFingerprint {
        val databaseFile = file(artifact)
        try {
            val values = SQLiteDatabase.openDatabase(
                databaseFile.path,
                null,
                SQLiteDatabase.OPEN_READONLY,
                PRESERVING_ERROR_HANDLER,
            ).use { database -> database.validatedPragmas(artifact) }
            return values.toFingerprint(databaseFile.length())
        } catch (failure: DatabaseFileMigrationException) {
            throw failure
        } catch (failure: Throwable) {
            throw integrityFailure(artifact, failure)
        }
    }

    override fun copy(from: DatabaseArtifact, to: DatabaseArtifact) {
        val source = file(from)
        val destination = file(to)
        destination.parentFile?.mkdirs()
        Files.copy(source.toPath(), destination.toPath())
    }

    override fun sync(artifact: DatabaseArtifact) {
        RandomAccessFile(file(artifact), "rw").use { randomAccessFile ->
            randomAccessFile.fd.sync()
        }
    }

    override fun atomicMove(from: DatabaseArtifact, to: DatabaseArtifact) {
        val source = file(from)
        val destination = file(to)
        destination.parentFile?.mkdirs()
        check(!destination.exists()) { "Refusing to replace existing $to database artifact" }
        deleteSidecars(from)
        Files.move(
            source.toPath(),
            destination.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
        )
    }

    override fun delete(artifact: DatabaseArtifact) {
        Files.deleteIfExists(file(artifact).toPath())
        deleteSidecars(artifact)
    }

    override fun deleteLegacySidecars() {
        for (suffix in LEGACY_SIDECAR_SUFFIXES) {
            Files.deleteIfExists(context.getDatabasePath("${DatabaseFileNames.LEGACY}$suffix").toPath())
        }
    }

    override fun <T> withExclusiveMigrationLock(block: () -> T): T = synchronized(PROCESS_LOCK) {
        val lockFile = context.getDatabasePath(DatabaseFileNames.LOCK)
        lockFile.parentFile?.mkdirs()
        RandomAccessFile(lockFile, "rw").use { randomAccessFile ->
            randomAccessFile.channel.use { channel ->
                channel.lock().use { block() }
            }
        }
    }

    private fun file(artifact: DatabaseArtifact): File = context.getDatabasePath(
        when (artifact) {
            DatabaseArtifact.LEGACY -> DatabaseFileNames.LEGACY
            DatabaseArtifact.TARGET -> DatabaseFileNames.TARGET
            DatabaseArtifact.RECOVERY -> DatabaseFileNames.RECOVERY
            DatabaseArtifact.STAGING -> DatabaseFileNames.STAGING
        },
    )

    private fun deleteSidecars(artifact: DatabaseArtifact) {
        val artifactName = file(artifact).name
        for (suffix in LEGACY_SIDECAR_SUFFIXES) {
            Files.deleteIfExists(context.getDatabasePath("$artifactName$suffix").toPath())
        }
    }

    private fun SQLiteDatabase.validatedPragmas(artifact: DatabaseArtifact): PragmaValues {
        var rows = 0
        var result: String? = null
        rawQuery("PRAGMA quick_check", null).use { cursor ->
            while (cursor.moveToNext()) {
                rows += 1
                result = cursor.getString(0)
            }
        }
        if (rows != 1 || result != "ok") {
            throw DatabaseFileMigrationException(
                DatabaseMigrationFailureCode.INTEGRITY_CHECK_FAILED,
                "The ${artifact.name.lowercase()} database failed SQLite quick_check.",
            )
        }

        return PragmaValues(
            userVersion = longPragma("PRAGMA user_version"),
            pageCount = longPragma("PRAGMA page_count"),
            freePageCount = longPragma("PRAGMA freelist_count"),
        )
    }

    private fun SQLiteDatabase.longPragma(sql: String): Long = rawQuery(sql, null).use { cursor ->
        check(cursor.moveToFirst()) { "$sql returned no value" }
        cursor.getLong(0)
    }

    private fun PragmaValues.toFingerprint(fileSize: Long): DatabaseFingerprint = DatabaseFingerprint(
        fileSize = fileSize,
        userVersion = userVersion,
        pageCount = pageCount,
        freePageCount = freePageCount,
    )

    private fun integrityFailure(artifact: DatabaseArtifact, cause: Throwable) = DatabaseFileMigrationException(
        DatabaseMigrationFailureCode.INTEGRITY_CHECK_FAILED,
        "The ${artifact.name.lowercase()} database could not be validated.",
        cause,
    )

    private fun SQLiteException.indicatesCorruption(): Boolean {
        val normalized = message.orEmpty().lowercase()
        return normalized.contains("not a database") ||
            normalized.contains("malformed") ||
            normalized.contains("corrupt")
    }

    private data class PragmaValues(
        val userVersion: Long,
        val pageCount: Long,
        val freePageCount: Long,
    )

    private companion object {
        val PROCESS_LOCK = Any()
        val LEGACY_SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")
        val PRESERVING_ERROR_HANDLER = DatabaseErrorHandler {
            // Validation must report corruption without Android's default
            // handler deleting the only source database.
        }
    }
}
