package com.devil.phoenixproject.domain.voice

/**
 * Platform-specific factory for creating [SafeWordListener] instances.
 *
 * Android requires a Context parameter; iOS does not.
 * Each platform's actual implementation is registered in the Koin platform module.
 */
interface SafeWordListenerFactory {
    fun create(safeWord: String): SafeWordListener
}
