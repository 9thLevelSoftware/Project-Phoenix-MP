package com.devil.phoenixproject.presentation.screen

import com.devil.phoenixproject.presentation.manager.GroupedRoutineHistoryItem
import com.devil.phoenixproject.presentation.manager.HistoryItem
import com.devil.phoenixproject.presentation.manager.SingleSessionHistoryItem
import com.devil.phoenixproject.presentation.manager.lazyColumnKey

/**
 * Home shows at most two activities. The input is already History-visible and grouped by workout
 * execution; sort and limit only after grouping so sets from one routine cannot fill both rows.
 */
internal fun projectHomeRecentActivities(history: List<HistoryItem>): List<HistoryItem> =
    history
        .sortedWith(compareByDescending<HistoryItem> { it.timestamp }.thenBy { it.lazyColumnKey })
        .take(2)

/** Hide cached history until its profile matches the Home screen's current profile. */
internal fun filterRecentActivityHistoryForProfile(
    history: List<HistoryItem>,
    profileId: String,
): List<HistoryItem> =
    history.filter { item ->
        when (item) {
            is GroupedRoutineHistoryItem -> item.sessions.isNotEmpty() &&
                item.sessions.all { it.profileId == profileId }
            is SingleSessionHistoryItem -> item.session.profileId == profileId
        }
    }
