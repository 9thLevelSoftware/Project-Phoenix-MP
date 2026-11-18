package com.devil.phoenixproject.data.export

/**
 * Issue #1392 — frozen power-history export v1 facade (P4 implements, P5 UI calls).
 *
 * P4 owns `PowerHistoryExporterImpl` (SQLDelight reads + versioned codec + Koin
 * registration). P5 wires the `Export power data` UI entry to this interface only.
 *
 * Export v1 contract (implementation-spec.md P4): separately identified version,
 * stable IDs, attempt/completed identity, frozen config, metric version,
 * reference/trigger/winner facts, nullable metrics/quality, units, and explicit
 * null-vs-zero semantics. Missing data is null — never a fabricated zero.
 */
interface PowerHistoryExporter {
    /**
     * Export the profile's persisted power-loss evidence as the versioned
     * power-history document (v1). Returns the produced document's display name
     * and content; the caller owns share/save UX.
     */
    suspend fun exportPowerData(profileId: String): PowerHistoryExportResult
}

/** Result of a power-history export v1 run. [content] is the versioned document body. */
data class PowerHistoryExportResult(
    val displayName: String,
    val content: String,
    val version: Int,
)
