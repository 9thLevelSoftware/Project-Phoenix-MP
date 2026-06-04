package com.devil.phoenixproject.util

import kotlinx.coroutines.CancellationException

/**
 * Coroutine cancellation must not be converted into ordinary recoverable errors.
 */
fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
