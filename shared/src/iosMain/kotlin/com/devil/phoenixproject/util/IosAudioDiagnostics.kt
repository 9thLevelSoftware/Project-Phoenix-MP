@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.devil.phoenixproject.util

import com.devil.phoenixproject.data.repository.ConnectionLogRepository
import com.devil.phoenixproject.data.repository.LogEventType
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.currentRoute
import platform.AVFAudio.outputVolume
import platform.Foundation.NSError

/** Bounded, exportable observations only; native success does not establish audible output. */
internal object IosAudioDiagnostics {
    fun record(owner: String, event: String, warning: Boolean = false, details: () -> String = { "" }) {
        try {
            val repository = ConnectionLogRepository.instance
            if (!repository.isEnabled.value) return
            val detail = details()
            if (warning) {
                repository.warning(LogEventType.WORKOUT_AUDIO, event, details = detail, tag = owner)
            } else {
                repository.debug(LogEventType.WORKOUT_AUDIO, event, details = detail, tag = owner)
            }
        } catch (_: Exception) {
            // Diagnostics must not interrupt playback, recovery, or cancellation.
        }
    }

    fun sessionDetails(): String {
        val session = AVAudioSession.sharedInstance()
        val outputTypes = session.currentRoute.outputs.mapNotNull {
            (it as? AVAudioSessionPortDescription)?.portType
        }.joinToString(",")
        // Port names/UIDs and NSError descriptions/userInfo can contain personal data.
        return "category=${session.category} mode=${session.mode} options=${session.categoryOptions} " +
            "outputVolume=${session.outputVolume} outputTypes=[$outputTypes]"
    }

    /** Execute the original operation once, allowing its exception to follow the original path. */
    fun nativeResult(
        owner: String,
        operation: String,
        call: (CPointer<ObjCObjectVar<NSError?>>) -> Boolean,
    ): Boolean = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        error.value = null
        val result = call(error.ptr)
        record(owner, operation, warning = !result) {
            "result=$result errorDomain=${error.value?.domain} errorCode=${error.value?.code} ${sessionDetails()}"
        }
        result
    }
}
