package com.devil.phoenixproject.data.migration

sealed interface ProfileScopeRepairState {
    data object Idle : ProfileScopeRepairState

    data class Applying(val message: String) : ProfileScopeRepairState

    data class NeedsChoice(
        val activeProfileId: String,
        val activeProfileName: String,
        val defaultRowCount: Long,
        val activeRowCount: Long,
    ) : ProfileScopeRepairState

    data class Completed(val message: String? = null) : ProfileScopeRepairState

    data class Failed(val message: String) : ProfileScopeRepairState
}
