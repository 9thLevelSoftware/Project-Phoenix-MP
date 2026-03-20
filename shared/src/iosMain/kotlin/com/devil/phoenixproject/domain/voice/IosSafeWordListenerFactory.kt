package com.devil.phoenixproject.domain.voice

/**
 * iOS factory — no extra dependencies needed beyond the safe word itself.
 */
class IosSafeWordListenerFactory : SafeWordListenerFactory {
    override fun create(safeWord: String): SafeWordListener =
        SafeWordListener(safeWord)
}
