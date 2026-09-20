package com.devil.phoenixproject

import com.devil.phoenixproject.data.local.DatabasePresenceSnapshot
import kotlin.coroutines.cancellation.CancellationException

/** A startup failure that is safe to classify without exposing its message. */
internal interface StartupDiagnosticFailure {
    val startupDiagnosticCode: String
    val startupRetryAllowed: Boolean
    val startupDiagnosticReason: String? get() = null
    val startupPresenceSnapshot: DatabasePresenceSnapshot? get() = null
}

internal sealed interface StartupDependencyResolution<out T> {
    data class Ready<T>(val dependencies: T) : StartupDependencyResolution<T>

    data class Failed(
        val diagnosticCode: String,
        val retryAllowed: Boolean,
        val supportCode: String? = null,
        internal val presenceSnapshot: DatabasePresenceSnapshot? = null,
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
            supportCode = diagnostic?.startupDiagnosticReason,
            presenceSnapshot = diagnostic?.startupPresenceSnapshot,
            cause = failure,
        )
    },
)

/**
 * Suspended startup boundary used by platform hosts. The initial implementation
 * intentionally keeps behavior minimal while ordering is specified by tests.
 */
internal suspend fun <S, T> prepareStartupDependencies(
    resolveStartupOnly: () -> S,
    prepareRequired: suspend (S) -> Unit,
    resolveFeatures: (S) -> T,
): StartupDependencyResolution<T> {
    val startup = resolveStartupDependencies(resolveStartupOnly)
    if (startup is StartupDependencyResolution.Failed) return startup
    startup as StartupDependencyResolution.Ready
    return try {
        prepareRequired(startup.dependencies)
        resolveStartupDependencies { resolveFeatures(startup.dependencies) }
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Throwable) {
        resolveStartupDependencies<T> { throw failure }
    }
}

/** Shared Android/iOS host gate; feature resolution is impossible before required startup succeeds. */
internal suspend fun <S, T> prepareAppHostDependencies(
    resolveStartupOnly: () -> S,
    prepareRequired: suspend (S) -> Unit,
    resolveFeatures: (S) -> T,
): StartupDependencyResolution<T> = prepareStartupDependencies(
    resolveStartupOnly = resolveStartupOnly,
    prepareRequired = prepareRequired,
    resolveFeatures = resolveFeatures,
)

internal fun DatabasePresenceSnapshot.safeSummary(): String = listOf(
    "schemaVersion=$schemaVersion",
    "libraryLegacy=${libraryLegacy.toSafeSummary()}",
    "sqliterLegacy=${sqliterLegacy.toSafeSummary()}",
    "target=${target.toSafeSummary()}",
    "recovery=${recovery.toSafeSummary()}",
    "staging=${staging.toSafeSummary()}",
).joinToString(",")

private fun com.devil.phoenixproject.data.local.DatabasePresence.toSafeSummary(): String =
    "main=$main,wal=$wal,shm=$shm,journal=$journal"

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
