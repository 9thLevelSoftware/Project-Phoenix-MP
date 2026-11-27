package com.devil.phoenixproject.data.maintenance

import com.devil.phoenixproject.database.MetricSample
import com.devil.phoenixproject.util.DataBackupManager

/**
 * JSON backup byte estimator (issue #1425). Samples up to 200 real rows through the backup
 * manager's own `mapMetricToBackup` + export `Json` (I11), so the estimate is exactly the
 * bytes the export would write. Returns null when `includeRawTelemetryInBackups` is off —
 * then backups never contained these samples and are unaffected.
 */
class TelemetryBackupBytesEstimator(
    private val backupManager: DataBackupManager,
) {
    /** Estimated backup bytes reclaimable by deleting [rowsReclaimable] raw sample rows. */
    fun estimateBackupBytesReclaimable(
        sampleRows: List<MetricSample>,
        rowsReclaimable: Int,
    ): Long? {
        val averageRowBytes = backupManager.averageMetricSampleBackupBytes(
            sampleRows.take(MAX_ESTIMATOR_ROWS),
        ) ?: return null
        return (averageRowBytes * rowsReclaimable).toLong()
    }

    companion object {
        const val MAX_ESTIMATOR_ROWS = 200
    }
}
