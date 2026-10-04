package com.devil.phoenixproject.util

import platform.Foundation.NSURL

/**
 * Grants security-scoped access for the duration of [block].
 *
 * [startAccessingSecurityScopedResource] is paired with
 * [stopAccessingSecurityScopedResource] only when it returns true.
 * [block] always runs. Inline so a return from [block] still stops access.
 * Does not catch exceptions; callers keep their own error style.
 */
internal inline fun <T> NSURL.withSecurityScopedAccess(block: () -> T): T {
    val accessing = startAccessingSecurityScopedResource()
    try {
        return block()
    } finally {
        if (accessing) {
            stopAccessingSecurityScopedResource()
        }
    }
}
