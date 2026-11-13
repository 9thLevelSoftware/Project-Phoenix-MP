package com.devil.phoenixproject.util

import com.devil.phoenixproject.data.local.DATABASE_QUARANTINE_DIRECTORY
import com.devil.phoenixproject.data.local.DatabaseFileNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Signoff decision 5 (issue #1384 P3, integration-model §7): the support export enumerates
 * candidate database artifacts as METADATA ONLY (names + byte sizes). Raw DB/WAL/SHM/
 * journal/quarantine bytes are never part of the surface.
 */
class DatabaseFileExportTest {
    private val names = listOf(
        DatabaseFileNames.LEGACY,
        DatabaseFileNames.TARGET,
        DatabaseFileNames.RECOVERY,
        DatabaseFileNames.STAGING,
    )
    private val suffixes = listOf("", "-wal", "-shm", "-journal")

    @Test
    fun diagnosticsCoverEveryCandidateAndSidecarAndNothingElse() {
        val candidates = names.flatMap { name -> suffixes.map { "$name$it" } }
        val existing = candidates.map { "/db/$it" }.toSet() +
            setOf("/db/unrelated.db", "/db/${DatabaseFileNames.LOCK}")

        val files = DatabaseFileExport.diagnosticFiles("sqliter", "/db", { it in existing }, { it.length.toLong() })

        assertEquals(16, candidates.size)
        assertEquals(candidates.map { "sqliter/$it" }, files.map { it.name })
    }

    @Test
    fun missingFilesAreSkipped() {
        val existing = setOf("/db/vitruvian.db", "/db/vitruvian.db-wal", "/db/phoenix.db")

        val files = DatabaseFileExport.diagnosticFiles("sqliter", "/db", { it in existing }, { 0L })

        assertEquals(listOf("sqliter/vitruvian.db", "sqliter/vitruvian.db-wal", "sqliter/phoenix.db"), files.map { it.name })
    }

    @Test
    fun sameNamedFilesFromTwoLocationsGetDistinctEntries() {
        val files = DatabaseFileExport.diagnosticFiles("library", "/lib", { it == "/lib/vitruvian.db" }, { 0L }) +
            DatabaseFileExport.diagnosticFiles("sqliter", "/db", { it == "/db/vitruvian.db" }, { 0L })

        assertEquals(listOf("library/vitruvian.db", "sqliter/vitruvian.db"), files.map { it.name })
    }

    @Test
    fun quarantinedCandidatesAreReportedUnderTheirOwnFolder() {
        val files = DatabaseFileExport.quarantineDiagnosticFiles(
            "/db",
            listOf("2-CANONICAL_LEGACY_TARGET-vitruvian.db/vitruvian.db", "1-X-phoenix.db/phoenix.db-wal"),
        ) { 42L }

        assertEquals(
            listOf(
                "quarantine/1-X-phoenix.db/phoenix.db-wal",
                "quarantine/2-CANONICAL_LEGACY_TARGET-vitruvian.db/vitruvian.db",
            ),
            files.map { it.name },
        )
        assertEquals(listOf(42L, 42L), files.map { it.sizeBytes })
    }

    @Test
    fun diagnosticsReportCarriesNamesAndSizesOnly() {
        val report = DatabaseFileExport.diagnosticsJson(
            listOf(DatabaseDiagnosticFile("databases/phoenix.db", 7L)),
        )
        assertEquals(true, "\"rawDatabaseBytesIncluded\":false" in report)
        assertEquals(true, "\"name\":\"databases/phoenix.db\"" in report)
        assertEquals(true, "\"sizeBytes\":7" in report)
        assertFalse(report.contains(ByteArray(7) { 1 }.decodeToString()))
    }
}
