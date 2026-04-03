package com.devil.phoenixproject.data.migration

/**
 * State for Issue #319 orphaned data repair flow.
 * Tracks PR records that exist for deleted profiles.
 */
sealed interface OrphanedDataRepairState {
    data object Idle : OrphanedDataRepairState
    data class Scanning(val message: String) : OrphanedDataRepairState
    data class NeedsRepair(
        val orphanedProfileIds: List<String>,
        val orphanedRecordCounts: Map<String, Int>,
        val targetProfileId: String,
    ) : OrphanedDataRepairState
    data class Repairing(val message: String) : OrphanedDataRepairState
    data class Completed(val message: String, val repairedRecords: Int) : OrphanedDataRepairState
    data class Failed(val error: String) : OrphanedDataRepairState
}
