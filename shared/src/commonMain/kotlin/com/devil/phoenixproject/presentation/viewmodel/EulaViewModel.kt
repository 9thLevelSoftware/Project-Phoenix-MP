package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.util.Constants
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import kotlin.time.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel for managing EULA/Terms of Service acceptance.
 * Uses multiplatform-settings for persistence across Android/iOS.
 *
 * Features:
 * - Version-controlled EULA acceptance
 * - Timestamp tracking for audit trail
 * - Re-prompts user when EULA version is incremented
 */
class EulaViewModel(private val settings: Settings) : ViewModel() {

    private val log = Logger.withTag("EulaViewModel")

    private val _eulaAccepted = MutableStateFlow(checkEulaAccepted())
    val eulaAccepted: StateFlow<Boolean> = _eulaAccepted.asStateFlow()

    /**
     * Check if the current EULA version has been accepted.
     */
    private fun checkEulaAccepted(): Boolean {
        val acceptedVersion = settings.getIntOrNull(EULA_ACCEPTED_VERSION_KEY) ?: 0
        val currentVersion = Constants.EULA_VERSION

        val isAccepted = acceptedVersion >= currentVersion

        if (!isAccepted && acceptedVersion > 0) {
            log.i {
                "EULA version updated: user accepted v$acceptedVersion, current is v$currentVersion"
            }
        }

        return isAccepted
    }

    /**
     * Record user's acceptance of the EULA.
     * Stores the version accepted and timestamp for audit purposes.
     */
    fun acceptEula() {
        val timestamp = Clock.System.now().toEpochMilliseconds()

        val persisted = runCatching {
            settings[EULA_ACCEPTED_VERSION_KEY] = Constants.EULA_VERSION
            settings[EULA_ACCEPTED_TIMESTAMP_KEY] = timestamp
        }.isSuccess

        if (persisted) {
            _eulaAccepted.value = true
            log.i { "EULA v${Constants.EULA_VERSION} accepted at timestamp $timestamp" }
        } else {
            log.e { "Failed to persist EULA acceptance for v${Constants.EULA_VERSION}" }
        }
    }

    companion object {
        private const val EULA_ACCEPTED_VERSION_KEY = "eula_accepted_version"
        private const val EULA_ACCEPTED_TIMESTAMP_KEY = "eula_accepted_timestamp"
    }
}
