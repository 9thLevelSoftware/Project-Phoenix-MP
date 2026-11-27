package com.devil.phoenixproject.data.maintenance

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * On-device space reclamation for the issue #1425 telemetry compaction (I5): a commonMain
 * class over the **live** [SqlDriver]. `PRAGMA page_size/page_count/freelist_count`,
 * `PRAGMA wal_checkpoint(TRUNCATE)` and `VACUUM` are plain SQL and behave the same on the
 * Android SupportSQLite driver and the iOS/tvOS NativeSqliteDriver (WAL).
 *
 * `*DatabaseFileOperations` are deliberately not used: they implement the startup
 * file-migration contract against artifacts they open themselves and are the wrong tool for
 * the live, open database. Logical DB size is `page_count × page_size`, which equals the
 * main-file size after a successful TRUNCATE checkpoint.
 */
class DatabaseMaintenance(
    private val driver: SqlDriver,
    /** Fakeable in tests; the platform actual is [availableDatabaseVolumeBytes]. */
    private val availableVolumeBytes: () -> Long? = ::availableDatabaseVolumeBytes,
    /** G0 session guard (I3). True means a workout may be active: never VACUUM. */
    private val workoutSessionGuard: () -> Boolean = { false },
) {

    fun measure(): DatabaseSizeSnapshot = DatabaseSizeSnapshot(
        pageSize = pragmaLong("PRAGMA page_size"),
        pageCount = pragmaLong("PRAGMA page_count"),
        freelistCount = pragmaLong("PRAGMA freelist_count"),
    )

    /**
     * Guarded precheck: no active workout session (G0), at least [MIN_RECLAIMABLE_BYTES] of
     * freelist pages to reclaim, and free volume of at least 2× the logical DB size so
     * VACUUM can build its copy. Anything else is a typed skip, never an error.
     */
    fun canVacuum(): VacuumPrecheck {
        if (workoutSessionGuard()) return VacuumPrecheck.Blocked(VacuumSkipReason.ACTIVE_WORKOUT)
        val snapshot = measure()
        if (snapshot.freelistCount * snapshot.pageSize < MIN_RECLAIMABLE_BYTES) {
            return VacuumPrecheck.Blocked(VacuumSkipReason.INSUFFICIENT_FREELIST)
        }
        val freeBytes = availableVolumeBytes() ?: return VacuumPrecheck.Blocked(VacuumSkipReason.UNSUPPORTED_PLATFORM)
        if (freeBytes < 2L * snapshot.logicalBytes) {
            return VacuumPrecheck.Blocked(VacuumSkipReason.INSUFFICIENT_FREE_VOLUME)
        }
        return VacuumPrecheck.Ready
    }

    /**
     * `wal_checkpoint(TRUNCATE)` → `VACUUM` → `wal_checkpoint(TRUNCATE)` → `measure()`.
     * Never called inside `db.transaction`, never while the G0 reservation is unheld. A busy
     * checkpoint (readers open) is reported in the result and the result is kept; freed pages
     * are then released when the app restarts. Failures become a typed [VacuumOutcome.Skipped]
     * — this never throws.
     */
    fun vacuum(): VacuumOutcome {
        val before = measure()
        return try {
            var checkpointBusy = walCheckpointTruncate()
            driver.execute(null, "VACUUM", 0)
            checkpointBusy = walCheckpointTruncate() || checkpointBusy
            val after = measure()
            VacuumOutcome.Ran(
                bytesBefore = before.logicalBytes,
                bytesAfter = after.logicalBytes,
                checkpointBusy = checkpointBusy,
            )
        } catch (e: Throwable) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            VacuumOutcome.Skipped(VacuumSkipReason.VACUUM_FAILED)
        }
    }

    /** Returns true when the checkpoint reported busy (a reader held the WAL). */
    private fun walCheckpointTruncate(): Boolean {
        val busy = driver.executeQuery(
            null,
            "PRAGMA wal_checkpoint(TRUNCATE)",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
            0,
        ).value
        return (busy ?: 0L) != 0L
    }

    private fun pragmaLong(pragma: String): Long = driver.executeQuery(
        null,
        pragma,
        { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
        0,
    ).value ?: 0L

    companion object {
        /** Below this much freelist space a VACUUM is not worth its copy cost. */
        const val MIN_RECLAIMABLE_BYTES: Long = 1L * 1024L * 1024L
    }
}
