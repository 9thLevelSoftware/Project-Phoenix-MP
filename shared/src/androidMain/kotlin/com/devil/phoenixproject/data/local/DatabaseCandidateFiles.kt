package com.devil.phoenixproject.data.local

import java.io.File
import java.nio.file.Files

/**
 * Copies [source] and the sidecars that carry committed data (-wal, hot -journal) to scratch
 * files in [databasesDir], runs [inspect] on the copy and deletes the copy (#764). The original
 * is only read. A candidate larger than [DATABASE_PROBE_SIZE_LIMIT_BYTES] is reported as holding
 * user data without being copied.
 */
internal fun probeDatabaseCopy(
    source: File,
    databasesDir: File,
    inspect: (scratch: File) -> CandidateContent,
): CandidateContent {
    if (!source.isFile) return CandidateContent.UNINSPECTABLE
    val sidecars = DATABASE_PROBE_SIDECAR_SUFFIXES.map { File("${source.path}$it") }.filter(File::isFile)
    if (source.length() + sidecars.sumOf(File::length) > DATABASE_PROBE_SIZE_LIMIT_BYTES) {
        return CandidateContent.HAS_USER_DATA
    }
    val scratch = File(databasesDir, "$DATABASE_PROBE_SCRATCH_PREFIX${source.name}")
    deleteWithSidecars(scratch)
    return try {
        Files.copy(source.toPath(), scratch.toPath())
        for (sidecar in sidecars) {
            val suffix = sidecar.name.removePrefix(source.name)
            Files.copy(sidecar.toPath(), File("${scratch.path}$suffix").toPath())
        }
        inspect(scratch)
    } finally {
        deleteWithSidecars(scratch)
    }
}

/**
 * Moves [source] and every sidecar into a new folder under [databasesDir]/[DATABASE_QUARANTINE_DIRECTORY]
 * (#764). Sidecars move first so a crash can never leave a foreign -wal beside a database that is
 * later promoted into this name; the main file moves last. Nothing is overwritten or deleted.
 */
internal fun quarantineDatabaseFiles(
    source: File,
    databasesDir: File,
    reason: DatabaseDiagnosticReason,
    timestampMs: Long,
): File {
    val root = File(databasesDir, DATABASE_QUARANTINE_DIRECTORY)
    var folder = File(root, quarantineFolderName(timestampMs, reason, source.name))
    var attempt = 1
    while (folder.exists()) {
        folder = File(root, quarantineFolderName(timestampMs, reason, source.name) + "-" + attempt++)
    }
    check(folder.mkdirs()) { "Could not create the quarantine folder for ${source.name}" }
    for (suffix in DATABASE_SIDECAR_SUFFIXES) {
        val sidecar = File("${source.path}$suffix")
        if (sidecar.exists()) Files.move(sidecar.toPath(), File(folder, sidecar.name).toPath())
    }
    if (source.exists()) Files.move(source.toPath(), File(folder, source.name).toPath())
    return folder
}

/** Deletes leftover probe scratch copies in [databasesDir]; never touches anything else. */
internal fun deleteProbeScratch(databasesDir: File) {
    databasesDir.listFiles { file -> file.isFile && file.name.startsWith(DATABASE_PROBE_SCRATCH_PREFIX) }
        ?.forEach { it.delete() }
}

private fun deleteWithSidecars(database: File) {
    database.delete()
    for (suffix in DATABASE_SIDECAR_SUFFIXES) File("${database.path}$suffix").delete()
}
