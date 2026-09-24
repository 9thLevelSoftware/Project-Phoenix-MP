package com.devil.phoenixproject.data.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.DatabaseFileContext
import co.touchlab.sqliter.NO_VERSION_CHECK
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
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
internal class IosDatabaseFileOperations(
    private val excludeFromBackup: (String) -> Unit = ::excludePathFromBackup,
) : DatabaseFileOperations {
    private val fileManager = NSFileManager.defaultManager
    private var capturedPresenceSnapshot: DatabasePresenceSnapshot? = null

    override fun capturePresenceSnapshot(): DatabasePresenceSnapshot {
        fun presence(path: String): DatabasePresence = DatabasePresence(
            main = fileManager.fileExistsAtPath(path),
            wal = fileManager.fileExistsAtPath("$path-wal"),
            shm = fileManager.fileExistsAtPath("$path-shm"),
            journal = fileManager.fileExistsAtPath("$path-journal"),
        )
        fun artifactPresence(artifact: DatabaseArtifact): DatabasePresence =
            presence(DatabaseFileContext.databasePath(name(artifact), null))
        return DatabasePresenceSnapshot(
            libraryLegacy = presence(legacyLibraryRootPath()),
            sqliterLegacy = artifactPresence(DatabaseArtifact.LEGACY),
            target = artifactPresence(DatabaseArtifact.TARGET),
            recovery = artifactPresence(DatabaseArtifact.RECOVERY),
            staging = artifactPresence(DatabaseArtifact.STAGING),
        ).also { capturedPresenceSnapshot = it }
    }

    override fun inspect(): DatabaseFileLayout {
        migrateLegacyLibraryRootIfNeeded()
        return DatabaseFileLayout(
            legacyExists = exists(DatabaseArtifact.LEGACY),
            targetExists = exists(DatabaseArtifact.TARGET),
            recoveryExists = exists(DatabaseArtifact.RECOVERY),
            stagingExists = exists(DatabaseArtifact.STAGING),
            legacySidecarsExist = LEGACY_SIDECAR_SUFFIXES.any { suffix ->
                fileManager.fileExistsAtPath("${path(DatabaseArtifact.LEGACY)}$suffix")
            },
        )
    }

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
        check(fileManager.copyItemAtPath(path(from), toPath = path(to), error = null)) {
            "Could not copy $from to $to"
        }
        runBestEffortBackupExclusion(path(to)) { excludeFromBackup(path(to)) }
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
        // Apply the attribute before rename so a successfully promoted file is
        // never left eligible for backup if a later startup step fails.
        runBestEffortBackupExclusion(path(from)) { excludeFromBackup(path(from)) }
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

    override fun probeUserData(artifact: DatabaseArtifact): CandidateContent = probePath(path(artifact))

    override fun quarantine(artifact: DatabaseArtifact, reason: DatabaseDiagnosticReason) {
        quarantinePath(path(artifact), reason, sidecarsOnly = false)
    }

    override fun discardProbeScratch() {
        val directory = path(DatabaseFileNames.TARGET).substringBeforeLast('/')
        val names = fileManager.contentsOfDirectoryAtPath(directory, error = null).orEmpty()
        for (name in names) {
            val fileName = name as? String ?: continue
            if (fileName.startsWith(DATABASE_PROBE_SCRATCH_PREFIX)) {
                fileManager.removeItemAtPath("$directory/$fileName", error = null)
            }
        }
    }

    /**
     * Classifies the database at [sourcePath] from a scratch copy in the SQLiter directory (#764).
     * The original is only copied, never opened; the copy is deleted afterwards. Never throws.
     */
    private fun probePath(sourcePath: String): CandidateContent {
        return try {
            if (!fileManager.fileExistsAtPath(sourcePath)) return CandidateContent.UNINSPECTABLE
            val sidecars = DATABASE_PROBE_SIDECAR_SUFFIXES.filter { fileManager.fileExistsAtPath("$sourcePath$it") }
            val totalSize = fileSizeAt(sourcePath) + sidecars.sumOf { fileSizeAt("$sourcePath$it") }
            if (totalSize > DATABASE_PROBE_SIZE_LIMIT_BYTES) return CandidateContent.HAS_USER_DATA

            val scratchName = "$DATABASE_PROBE_SCRATCH_PREFIX${sourcePath.substringAfterLast('/')}"
            val scratchPath = path(scratchName)
            removeWithSidecars(scratchPath)
            try {
                check(fileManager.copyItemAtPath(sourcePath, toPath = scratchPath, error = null)) {
                    "Could not copy a database candidate for inspection"
                }
                runBestEffortBackupExclusion(scratchPath) { excludeFromBackup(scratchPath) }
                for (suffix in sidecars) {
                    check(fileManager.copyItemAtPath("$sourcePath$suffix", toPath = "$scratchPath$suffix", error = null)) {
                        "Could not copy a database candidate sidecar for inspection"
                    }
                }
                val driver = NativeSqliteDriver(
                    DatabaseConfiguration(
                        name = scratchName,
                        version = NO_VERSION_CHECK,
                        create = {},
                    ),
                )
                try {
                    DatabaseUserDataClassifier.classify(SqlDriverProbeQueries(driver))
                } finally {
                    driver.close()
                }
            } finally {
                removeWithSidecars(scratchPath)
            }
        } catch (_: Throwable) {
            CandidateContent.UNINSPECTABLE
        }
    }

    /**
     * Moves the sidecars of [sourcePath] and then (unless [sidecarsOnly]) the main file into a new
     * folder under the SQLiter directory's [DATABASE_QUARANTINE_DIRECTORY] (#764). Sidecars move
     * first so a foreign -wal can never be left beside a database that later takes this name.
     * Nothing is overwritten or deleted.
     */
    private fun quarantinePath(
        sourcePath: String,
        reason: DatabaseDiagnosticReason,
        sidecarsOnly: Boolean,
    ) {
        val root = path(DATABASE_QUARANTINE_DIRECTORY)
        val fileName = sourcePath.substringAfterLast('/')
        val timestampMs = (NSDate().timeIntervalSince1970 * 1000).toLong()
        var folder = "$root/${quarantineFolderName(timestampMs, reason, fileName)}"
        var attempt = 1
        while (fileManager.fileExistsAtPath(folder)) {
            folder = "$root/${quarantineFolderName(timestampMs, reason, fileName)}-${attempt++}"
        }
        check(
            fileManager.createDirectoryAtPath(
                folder,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            ),
        ) { "Could not create the quarantine folder" }
        runBestEffortBackupExclusion(folder) { excludeFromBackup(folder) }
        for (suffix in DATABASE_SIDECAR_SUFFIXES) {
            val sidecar = "$sourcePath$suffix"
            if (fileManager.fileExistsAtPath(sidecar)) {
                check(fileManager.moveItemAtPath(sidecar, toPath = "$folder/$fileName$suffix", error = null)) {
                    "Could not move a database sidecar into quarantine"
                }
            }
        }
        if (!sidecarsOnly && fileManager.fileExistsAtPath(sourcePath)) {
            check(fileManager.moveItemAtPath(sourcePath, toPath = "$folder/$fileName", error = null)) {
                "Could not move a database into quarantine"
            }
        }
    }

    private fun removeWithSidecars(databasePath: String) {
        fileManager.removeItemAtPath(databasePath, error = null)
        for (suffix in DATABASE_SIDECAR_SUFFIXES) {
            fileManager.removeItemAtPath("$databasePath$suffix", error = null)
        }
    }

    private fun fileSizeAt(filePath: String): Long {
        val attributes = fileManager.attributesOfItemAtPath(filePath, error = null)
        return (attributes?.get(NSFileSize) as? NSNumber)?.longValue
            ?: error("Could not read a database candidate's size")
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

    private fun migrateLegacyLibraryRootIfNeeded() {
        val legacyPath = path(DatabaseArtifact.LEGACY)
        val compatibilityPath = legacyLibraryRootPath()
        val compatibilityExists = fileManager.fileExistsAtPath(compatibilityPath)
        val compatibilitySidecars = existingSidecars(compatibilityPath)
        val legacySidecars = existingSidecars(legacyPath)

        val legacyExists = fileManager.fileExistsAtPath(legacyPath)

        if (!compatibilityExists) {
            if (compatibilitySidecars.isNotEmpty()) {
                NSLog("iOS DB: Ignoring orphaned Library/vitruvian.db sidecars without a main file")
            }
            if (!legacyExists && legacySidecars.isNotEmpty()) {
                NSLog("iOS DB: Ignoring orphaned SQLiter vitruvian.db sidecars without a main file")
            }
            return
        }
        if (legacyExists) {
            // Guard B (#764): both locations hold a vitruvian.db. Keep one only when that is lossless.
            if (resolveDuplicateLegacy(compatibilityPath, legacyPath) == DuplicateLegacyKept.SQLITER) return
        } else if (legacySidecars.isNotEmpty()) {
            // Guard C (#764): SQLiter-location sidecars with no main file cannot belong to any
            // database Phoenix will open, but a -wal left there would be replayed into the copy
            // made below. Move them aside (never delete them), then relocate as usual.
            try {
                quarantinePath(legacyPath, DatabaseDiagnosticReason.LIBRARY_MAIN_SQLITER_SIDECARS, sidecarsOnly = true)
            } catch (failure: Throwable) {
                throw DatabaseFileMigrationException(
                    DatabaseMigrationFailureCode.DUAL_DATABASES,
                    "Legacy database sidecars exist in the SQLiter location without its main file; automatic recovery is disabled.",
                    failure,
                    diagnosticReason = DatabaseDiagnosticReason.LIBRARY_MAIN_SQLITER_SIDECARS,
                    presenceSnapshot = capturedPresenceSnapshot,
                )
            }
        }

        val parentPath = legacyPath.substringBeforeLast('/')
        check(fileManager.createDirectoryAtPath(
            parentPath,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )) {
            "Could not create the SQLiter database directory for legacy migration"
        }

        var mainCopied = false
        val copiedSidecars = mutableListOf<String>()
        try {
            // Copy the main file first. Sidecars must never be copied into a
            // location unless their corresponding database was copied too.
            check(fileManager.copyItemAtPath(
                compatibilityPath,
                toPath = legacyPath,
                error = null,
            )) {
                "Could not copy the legacy database into the SQLiter directory"
            }
            mainCopied = true
            for (suffix in compatibilitySidecars) {
                check(fileManager.copyItemAtPath(
                    "$compatibilityPath$suffix",
                    toPath = "$legacyPath$suffix",
                    error = null,
                )) {
                    "Could not copy the legacy database sidecar into the SQLiter directory"
                }
                copiedSidecars += suffix
            }
        } catch (failure: Throwable) {
            for (suffix in copiedSidecars.asReversed()) {
                runCatching {
                    fileManager.removeItemAtPath("$legacyPath$suffix", error = null)
                }
            }
            if (mainCopied) {
                runCatching { fileManager.removeItemAtPath(legacyPath, error = null) }
            }
            throw DatabaseFileMigrationException(
                DatabaseMigrationFailureCode.RECOVERY_COPY_FAILED,
                "The legacy database could not be copied into the SQLiter directory.",
                failure,
            )
        }

        try {
            // The copy is complete, so remove every old-location file. Leaving
            // a sidecar behind would make a later launch see two legacy sources.
            for (suffix in compatibilitySidecars) {
                check(fileManager.removeItemAtPath("$compatibilityPath$suffix", error = null)) {
                    "Could not remove the old legacy database sidecar"
                }
            }
            check(fileManager.removeItemAtPath(compatibilityPath, error = null)) {
                "Could not remove the old legacy database"
            }
        } catch (failure: Throwable) {
            // Do not leave two possible legacy sources behind. Roll back the
            // copied destination so the next launch can retry cleanup and copy
            // from the still-authoritative old location.
            for (suffix in copiedSidecars.asReversed()) {
                runCatching {
                    fileManager.removeItemAtPath("$legacyPath$suffix", error = null)
                }
            }
            if (mainCopied) {
                runCatching { fileManager.removeItemAtPath(legacyPath, error = null) }
            }
            throw DatabaseFileMigrationException(
                DatabaseMigrationFailureCode.LEGACY_CLEANUP_FAILED,
                "The old legacy database files could not be removed after migration.",
                failure,
            )
        }
    }

    private enum class DuplicateLegacyKept { LIBRARY, SQLITER }

    /**
     * Guard B (#764): Library-root and SQLiter vitruvian.db both exist. Resolved when the two are
     * byte-identical (a relocation that copied but was interrupted before removing the original),
     * or when exactly one of them holds user data. The other one is moved into quarantine; every
     * other case throws the same fail-closed DUAL_DATABASES as before.
     */
    private fun resolveDuplicateLegacy(libraryPath: String, sqliterPath: String): DuplicateLegacyKept {
        fun conflict(cause: Throwable? = null) = DatabaseFileMigrationException(
            DatabaseMigrationFailureCode.DUAL_DATABASES,
            "Legacy database files exist in both the old and SQLiter locations; automatic recovery is disabled.",
            cause,
            diagnosticReason = DatabaseDiagnosticReason.LIBRARY_SQLITER_LEGACY,
            presenceSnapshot = capturedPresenceSnapshot,
        )

        val kept = if (sameDatabaseFiles(libraryPath, sqliterPath)) {
            DuplicateLegacyKept.SQLITER
        } else {
            val library = probePath(libraryPath)
            val sqliter = if (library == CandidateContent.UNINSPECTABLE) {
                CandidateContent.UNINSPECTABLE
            } else {
                probePath(sqliterPath)
            }
            when {
                library == CandidateContent.EMPTY && sqliter != CandidateContent.UNINSPECTABLE -> DuplicateLegacyKept.SQLITER
                library == CandidateContent.HAS_USER_DATA && sqliter == CandidateContent.EMPTY -> DuplicateLegacyKept.LIBRARY
                else -> throw conflict()
            }
        }

        try {
            val setAside = if (kept == DuplicateLegacyKept.SQLITER) libraryPath else sqliterPath
            quarantinePath(setAside, DatabaseDiagnosticReason.LIBRARY_SQLITER_LEGACY, sidecarsOnly = false)
        } catch (failure: Throwable) {
            throw conflict(failure)
        }
        return kept
    }

    private fun sameDatabaseFiles(first: String, second: String): Boolean {
        val sidecars = existingSidecars(first)
        if (sidecars != existingSidecars(second)) return false
        if (!fileManager.contentsEqualAtPath(first, andPath = second)) return false
        return sidecars.all { suffix -> fileManager.contentsEqualAtPath("$first$suffix", andPath = "$second$suffix") }
    }

    private fun existingSidecars(databasePath: String): List<String> = LEGACY_SIDECAR_SUFFIXES.filter { suffix ->
        fileManager.fileExistsAtPath("$databasePath$suffix")
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

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun runBestEffortBackupExclusion(path: String) {
    runBestEffortBackupExclusion(path) { excludePathFromBackup(path) }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun runBestEffortBackupExclusion(path: String, exclude: () -> Unit) {
    try {
        exclude()
    } catch (failure: Throwable) {
        NSLog("iOS DB: Warning -- could not exclude database artifact from backup at $path: ${failure.message?.take(120)}")
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun excludePathFromBackup(filePath: String) {
    val url = NSURL.fileURLWithPath(filePath)
    memScoped {
        val errorPtr = alloc<ObjCObjectVar<NSError?>>()
        errorPtr.value = null
        val excluded = url.setResourceValue(
            NSNumber(bool = true),
            forKey = NSURLIsExcludedFromBackupKey,
            error = errorPtr.ptr,
        )
        check(excluded) {
            "Could not exclude a database migration artifact from backup: " +
                (errorPtr.value?.localizedDescription ?: "unknown error")
        }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun legacyLibraryRootPath(): String {
    val fileManager = NSFileManager.defaultManager
    @Suppress("UNCHECKED_CAST")
    val libraryUrl = (fileManager.URLsForDirectory(NSLibraryDirectory, NSUserDomainMask) as List<NSURL>).firstOrNull()
        ?: error("Could not resolve the iOS Library directory")
    return "${libraryUrl.path}/${DatabaseFileNames.LEGACY}"
}
