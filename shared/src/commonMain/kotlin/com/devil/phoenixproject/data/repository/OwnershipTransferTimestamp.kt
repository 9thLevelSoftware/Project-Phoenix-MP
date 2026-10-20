package com.devil.phoenixproject.data.repository

/** Keeps chained X -> source -> target transfers in a stable account-local order. */
internal fun nextOwnershipTransferCreatedAt(now: Long, latestCreatedAt: Long?): Long {
    val latest = latestCreatedAt ?: return now
    return maxOf(now, latest + 1L)
}
