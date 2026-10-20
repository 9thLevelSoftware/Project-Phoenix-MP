package com.devil.phoenixproject.data.repository

import com.devil.phoenixproject.database.PhoenixDatabaseQueries

/** Keeps chained X -> source -> target transfers in a stable account-local order. */
internal fun nextOwnershipTransferCreatedAt(now: Long, latestCreatedAt: Long?): Long {
    val latest = latestCreatedAt ?: return now
    return maxOf(now, latest + 1L)
}

/** [nextOwnershipTransferCreatedAt] against the owner's latest recorded transfer. */
internal fun PhoenixDatabaseQueries.nextOwnershipTransferCreatedAtFor(ownerUserId: String, now: Long): Long =
    nextOwnershipTransferCreatedAt(
        now = now,
        latestCreatedAt = selectAllOwnershipTransfers().executeAsList()
            .asSequence()
            .filter { it.owner_user_id == ownerUserId }
            .maxOfOrNull { it.created_at },
    )
