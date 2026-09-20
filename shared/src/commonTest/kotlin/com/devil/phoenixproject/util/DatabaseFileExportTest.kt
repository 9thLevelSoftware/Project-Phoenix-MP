package com.devil.phoenixproject.util

import com.devil.phoenixproject.data.local.DatabaseFileNames
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseFileExportTest {
    private val names = listOf(
        DatabaseFileNames.LEGACY,
        DatabaseFileNames.TARGET,
        DatabaseFileNames.RECOVERY,
        DatabaseFileNames.STAGING,
    )
    private val suffixes = listOf("", "-wal", "-shm", "-journal")

    @Test
    fun entriesIncludeEveryCandidateAndSidecarAndNothingElse() {
        val candidates = names.flatMap { name -> suffixes.map { "$name$it" } }
        val existing = candidates.map { "/db/$it" }.toSet() +
            setOf("/db/unrelated.db", "/db/${DatabaseFileNames.LOCK}")

        val entries = DatabaseFileExport.entries("sqliter", "/db") { it in existing }

        assertEquals(16, candidates.size)
        assertEquals(candidates.map { DatabaseExportEntry("sqliter/$it", "/db/$it") }, entries)
    }

    @Test
    fun missingFilesAreSkipped() {
        val existing = setOf("/db/vitruvian.db", "/db/vitruvian.db-wal", "/db/phoenix.db")

        val entries = DatabaseFileExport.entries("sqliter", "/db") { it in existing }

        assertEquals(listOf("sqliter/vitruvian.db", "sqliter/vitruvian.db-wal", "sqliter/phoenix.db"), entries.map { it.entryName })
    }

    @Test
    fun sameNamedFilesFromTwoLocationsGetDistinctEntries() {
        val entries = DatabaseFileExport.entries("library", "/lib") { it == "/lib/vitruvian.db" } +
            DatabaseFileExport.entries("sqliter", "/db") { it == "/db/vitruvian.db" }

        assertEquals(listOf("library/vitruvian.db", "sqliter/vitruvian.db"), entries.map { it.entryName })
    }
}
