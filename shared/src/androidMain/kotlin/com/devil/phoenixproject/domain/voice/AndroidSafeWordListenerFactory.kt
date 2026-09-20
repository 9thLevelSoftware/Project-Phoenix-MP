package com.devil.phoenixproject.domain.voice

import android.content.Context

/**
 * Android factory that provides the required [Context] to [AndroidSafeWordListener].
 */
class AndroidSafeWordListenerFactory(private val context: Context) : SafeWordListenerFactory {
    override fun create(safeWord: String): SafeWordListener = AndroidSafeWordListener(context, safeWord)
}
