package com.devil.phoenixproject.util

import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseFileExportTest {
    @Test
    fun entriesIncludeEveryExistingCandidateAndSidecarUnderItsFolder() {
        val existing = setOf(
            "/db/vitruvian.db",
            "/db/vitruvian.db-wal",
            "/db/phoenix.db",
            "/db/phoenix.db-shm",
            "/db/unrelated.db",
            "/db/phoenix-db-migration.lock",
        )

        val entries = DatabaseFileExport.entries("sqliter", "/db") { it in existing }

        assertEquals(
            listOf(
                DatabaseExportEntry("sqliter/vitruvian.db", "/db/vitruvian.db"),
                DatabaseExportEntry("sqliter/vitruvian.db-wal", "/db/vitruvian.db-wal"),
                DatabaseExportEntry("sqliter/phoenix.db", "/db/phoenix.db"),
                DatabaseExportEntry("sqliter/phoenix.db-shm", "/db/phoenix.db-shm"),
            ),
            entries,
        )
    }

    @Test
    fun sameNamedFilesFromTwoLocationsGetDistinctEntries() {
        val entries = DatabaseFileExport.entries("library", "/lib") { it == "/lib/vitruvian.db" } +
            DatabaseFileExport.entries("sqliter", "/db") { it == "/db/vitruvian.db" }

        assertEquals(listOf("library/vitruvian.db", "sqliter/vitruvian.db"), entries.map { it.entryName })
    }
}
