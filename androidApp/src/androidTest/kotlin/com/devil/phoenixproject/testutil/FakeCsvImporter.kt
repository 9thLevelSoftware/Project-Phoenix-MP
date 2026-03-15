package com.devil.phoenixproject.testutil

import com.devil.phoenixproject.util.CsvImporter
import com.devil.phoenixproject.util.CsvImportResult

/**
 * Fake CsvImporter for UI tests.
 * Returns a canned result without touching the filesystem or database.
 */
class FakeCsvImporter : CsvImporter {
    override suspend fun importFromCsv(uri: String): CsvImportResult {
        return CsvImportResult(
            imported = 0,
            skipped = 0,
            failed = 0,
            errors = listOf("Import not available in test mode")
        )
    }
}
