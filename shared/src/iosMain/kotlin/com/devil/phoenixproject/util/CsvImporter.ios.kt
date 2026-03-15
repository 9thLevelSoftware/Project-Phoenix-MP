package com.devil.phoenixproject.util

/**
 * iOS stub implementation of [CsvImporter].
 * CSV import is not yet available on iOS -- returns an informative error.
 */
class IosCsvImporter : CsvImporter {

    override suspend fun importFromCsv(uri: String): CsvImportResult {
        return CsvImportResult(
            imported = 0,
            skipped = 0,
            failed = 0,
            errors = listOf("CSV import is not yet available on iOS")
        )
    }
}
