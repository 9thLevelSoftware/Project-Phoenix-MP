package com.devil.phoenixproject.presentation.viewmodel

import androidx.lifecycle.ViewModel
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.util.Constants
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock

/**
 * ViewModel for managing EULA/Terms of Service acceptance.
 * Uses multiplatform-settings for persistence across Android/iOS.
 *
 * Features:
 * - Version-controlled EULA acceptance
 * - Timestamp tracking for audit trail
 * - Re-prompts user when EULA version is incremented
 */
class EulaViewModel(
    private val settings: Settings
) : ViewModel() {

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
            log.i { "EULA version updated: user accepted v$acceptedVersion, current is v$currentVersion" }
        }

        return isAccepted
    }

    /**
     * Record user's acceptance of the EULA.
     * Stores the version accepted and timestamp for audit purposes.
     */
    fun acceptEula() {
        val timestamp = Clock.System.now().toEpochMilliseconds()

        settings[EULA_ACCEPTED_VERSION_KEY] = Constants.EULA_VERSION
        settings[EULA_ACCEPTED_TIMESTAMP_KEY] = timestamp

        _eulaAccepted.value = true

        log.i { "EULA v${Constants.EULA_VERSION} accepted at timestamp $timestamp" }
    }

    /**
     * Get the timestamp when EULA was accepted (for audit purposes).
     * Returns null if EULA was never accepted.
     */
    @Suppress("unused") // Reserved for future audit/settings UI
    fun getAcceptanceTimestamp(): Long? {
        return settings.getLongOrNull(EULA_ACCEPTED_TIMESTAMP_KEY)
    }

    /**
     * Get the version of EULA that was accepted.
     * Returns 0 if EULA was never accepted.
     */
    @Suppress("unused") // Reserved for future audit/settings UI
    fun getAcceptedVersion(): Int {
        return settings.getIntOrNull(EULA_ACCEPTED_VERSION_KEY) ?: 0
    }

    companion object {
        private const val EULA_ACCEPTED_VERSION_KEY = "eula_accepted_version"
        private const val EULA_ACCEPTED_TIMESTAMP_KEY = "eula_accepted_timestamp"
    }
}
