package com.devil.phoenixproject.qa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

interface ProfileQaSeedPendingResult {
    fun finish()
}

class ProfileQaSeedReceiver(
    private val enableGate: (Context) -> Unit = { ProfileQaFixtureGate(it).enable() },
    private val seed: suspend () -> ProfileQaSeedResult = {
        GlobalContext.get().get<ProfileQaSeeder>().seed()
    },
    private val beginAsync: ProfileQaSeedReceiver.() -> ProfileQaSeedPendingResult = {
        AndroidPendingResult(goAsync())
    },
    private val launch: ((suspend () -> Unit) -> Unit) = { work ->
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { work() }
    },
    private val log: (String) -> Unit = { Log.i(LOG_TAG, it) },
) : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SEED_PROFILE) return

        try {
            enableGate(context)
        } catch (failure: Throwable) {
            log(failureMarker(failure))
            return
        }

        val pending = try {
            beginAsync()
        } catch (failure: Throwable) {
            log(failureMarker(failure))
            return
        }

        try {
            launch {
                try {
                    seed()
                    log(ProfileQaSeeder.RESULT_OK)
                } catch (failure: Throwable) {
                    log(failureMarker(failure))
                } finally {
                    pending.finish()
                }
            }
        } catch (failure: Throwable) {
            log(failureMarker(failure))
            pending.finish()
        }
    }

    private fun failureMarker(failure: Throwable): String =
        "PROFILE_QA_SEED_FAILED:${failure::class.simpleName ?: "Unknown"}"

    private class AndroidPendingResult(
        private val pendingResult: PendingResult,
    ) : ProfileQaSeedPendingResult {
        override fun finish() {
            pendingResult.finish()
        }
    }

    companion object {
        const val ACTION_SEED_PROFILE = "com.devil.phoenixproject.QA_SEED_PROFILE"
        private const val LOG_TAG = "ProfileQaSeed"
    }
}
