package com.devil.phoenixproject

/** A startup failure that is safe to classify without exposing its message. */
internal interface StartupDiagnosticFailure {
    val startupDiagnosticCode: String
    val startupRetryAllowed: Boolean
}

internal sealed interface StartupDependencyResolution<out T> {
    data class Ready<T>(val dependencies: T) : StartupDependencyResolution<T>

    data class Failed(
        val diagnosticCode: String,
        val retryAllowed: Boolean,
        internal val cause: Throwable,
    ) : StartupDependencyResolution<Nothing>
}

/**
 * Resolves startup dependencies behind a boundary that can be run again after
 * Koin discards a failed singleton creation. Only stable, non-sensitive codes
 * cross into the user-visible state.
 */
internal inline fun <T> resolveStartupDependencies(
    resolve: () -> T,
): StartupDependencyResolution<T> = runCatching(resolve).fold(
    onSuccess = StartupDependencyResolution<T>::Ready,
    onFailure = { failure ->
        val diagnostic = failure.findStartupDiagnosticFailure()
        StartupDependencyResolution.Failed(
            diagnosticCode = diagnostic?.startupDiagnosticCode ?: "STARTUP_INITIALIZATION_FAILED",
            retryAllowed = diagnostic?.startupRetryAllowed ?: true,
            cause = failure,
        )
    },
)

private fun Throwable.findStartupDiagnosticFailure(): StartupDiagnosticFailure? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 16) {
        if (current is StartupDiagnosticFailure) return current
        current = current.cause
        depth++
    }
    return null
}
