package com.juul.kable

import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.android.asCoroutineDispatcher

public sealed class Threading {

    internal abstract val dispatcher: CoroutineDispatcher
    internal abstract val strategy: ThreadingStrategy

    /** Used on Android O (API 26) and above. */
    internal data class Handler(
        val thread: HandlerThread,
        val handler: android.os.Handler,
        override val dispatcher: CoroutineDispatcher,
        override val strategy: ThreadingStrategy,
    ) : Threading()
}

internal fun Threading.release() {
    strategy.release(this)
}

public val Threading.name: String
    get() = when (this) {
        is Threading.Handler -> thread.name
    }

public fun Threading.shutdown() {
    when (this) {
        is Threading.Handler -> thread.quit()
    }
}

/**
 * Creates [Threading] that can be used for Bluetooth communication. The returned [Threading] is
 * returned in a started state and must be [shutdown] when no longer needed.
 */
public fun ThreadingStrategy.Threading(name: String): Threading {
    val thread = HandlerThread(name).apply { start() }
    val handler = Handler(thread.looper)
    val dispatcher = handler.asCoroutineDispatcher()
    return Threading.Handler(thread, handler, dispatcher, this)
}
