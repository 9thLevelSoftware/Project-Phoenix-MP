package com.devil.phoenixproject.data.export

import com.devil.phoenixproject.data.repository.PowerLossCompanionStore
import com.devil.phoenixproject.database.PhoenixDatabase
import com.devil.phoenixproject.domain.model.currentTimeMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Issue #1392 (packet P4) — power-history export v1.
 *
 * Exports the profile's persisted power-loss evidence
 * (`PowerLossSetCompanion` + `PowerLossAttemptCompanion`, tier 1) as the versioned
 * power-history document via [PowerHistoryExportCodec]. Contract:
 *
 *  - separately identified document (`documentType`, `version: 1`) with stable
 *    session/set IDs and per-attempt identity (attempt-only rows included),
 *  - frozen target config + `metricVersion`, reference/trigger/winner facts and
 *    BOTH qualification facts,
 *  - explicit units and null-vs-zero semantics: missing data is null/empty,
 *    never a fabricated 0,
 *  - profile-scoped: only sessions owned by [PowerHistoryExporter.exportPowerData]'s
 *    profileId are exported; sessions without evidence are omitted (empty, not zeroed).
 *
 * No wire semantics: this document is a local share/save artifact only
 * (legacy portal payloads stay unchanged).
 */
class PowerHistoryExporterImpl(
    private val database: PhoenixDatabase,
) : PowerHistoryExporter {

    override suspend fun exportPowerData(profileId: String): PowerHistoryExportResult =
        withContext(Dispatchers.IO) {
            val queries = database.phoenixDatabaseQueries
            val sessions = queries.selectAllSessions(profileId).executeAsList().mapNotNull { row ->
                val evidence = PowerLossCompanionStore.readEvidence(queries, row.id)
                if (evidence.isEmpty()) {
                    null
                } else {
                    PowerHistoryExportCodec.SessionExport(sessionId = row.id, sets = evidence)
                }
            }
            val content = PowerHistoryExportCodec.encode(
                profileId = profileId,
                exportedAtMs = currentTimeMillis(),
                sessions = sessions,
            )
            PowerHistoryExportResult(
                displayName = "power-history-v1-$profileId.json",
                content = content,
                version = PowerHistoryExportCodec.VERSION,
            )
        }
}
