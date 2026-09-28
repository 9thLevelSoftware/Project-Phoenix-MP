package com.devil.phoenixproject.presentation.components

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

internal const val PROFILE_RECOVERY_SETTINGS_REFRESH_INTERVAL_MS = 3_000L

/**
 * Reloads the recovery snapshot only while [shown] is true.
 * Cloud acknowledgements are observed from their own flow and are not polled here.
 */
internal suspend fun refreshProfileRecoveryWhileShown(
    shown: StateFlow<Boolean>,
    refresh: suspend () -> Unit,
    intervalMillis: Long = PROFILE_RECOVERY_SETTINGS_REFRESH_INTERVAL_MS,
) {
    shown.collectLatest { isShown ->
        if (!isShown) return@collectLatest
        while (true) {
            refresh()
            delay(intervalMillis)
        }
    }
}
