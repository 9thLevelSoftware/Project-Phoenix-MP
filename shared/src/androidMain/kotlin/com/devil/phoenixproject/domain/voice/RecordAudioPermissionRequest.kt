package com.devil.phoenixproject.domain.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.devil.phoenixproject.util.ActivityHolder

/**
 * Outcome of asking for [Manifest.permission.RECORD_AUDIO] before safe-word listening.
 *
 * [Granted] and [Denied] are synchronous. [Requesting] means the system dialog is
 * up (or a permanent denial will deliver the result through the callback) and
 * [onResult] runs later.
 */
internal sealed interface RecordAudioPermissionStatus {
    data object Granted : RecordAudioPermissionStatus
    data object Denied : RecordAudioPermissionStatus
    data object Requesting : RecordAudioPermissionStatus
}

/**
 * Runtime microphone gate for [AndroidSafeWordListener].
 *
 * Same idea as the BLE permission gate ([androidx.activity.result.contract.ActivityResultContracts]):
 * ask through the resumed activity, and skip the dialog when the permission is
 * already granted so continuous recognition restarts do not prompt again.
 * A permanent denial cannot show a dialog; the caller reports
 * [SafeWordUnavailableReason.PERMISSION] and the calibration UI keeps its
 * Open Settings path.
 */
internal class RecordAudioPermissionRequest(
    private val context: Context,
    private val currentActivity: () -> ComponentActivity? = {
        ActivityHolder.getActivity() as? ComponentActivity
    },
) {
    fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * @param onResult invoked only for [RecordAudioPermissionStatus.Requesting],
     *   including when the platform delivers that result before [request] returns
     *   (already permanently denied). Not invoked for [Granted] or [Denied].
     */
    fun request(
        requestKey: String,
        onResult: (granted: Boolean) -> Unit,
    ): RecordAudioPermissionStatus {
        if (isGranted()) return RecordAudioPermissionStatus.Granted

        val activity = currentActivity()
        if (activity == null) {
            Log.w(TAG, "No resumed activity to request RECORD_AUDIO")
            return RecordAudioPermissionStatus.Denied
        }

        val launcherHolder = arrayOfNulls<ActivityResultLauncher<String>>(1)
        val launcher = try {
            activity.activityResultRegistry.register(
                requestKey,
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                launcherHolder[0]?.unregister()
                // Trust the contract result. A later ERROR_INSUFFICIENT_PERMISSIONS
                // still fails closed if the grant did not actually stick.
                onResult(granted)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register RECORD_AUDIO request", e)
            return RecordAudioPermissionStatus.Denied
        }
        launcherHolder[0] = launcher

        return try {
            launcher.launch(Manifest.permission.RECORD_AUDIO)
            RecordAudioPermissionStatus.Requesting
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch RECORD_AUDIO request", e)
            launcher.unregister()
            RecordAudioPermissionStatus.Denied
        }
    }

    private companion object {
        const val TAG = "SafeWordListener"
    }
}
