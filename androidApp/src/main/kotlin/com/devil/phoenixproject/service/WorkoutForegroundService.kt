package com.devil.phoenixproject.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.MainActivity

/**
 * Foreground service to keep the app alive during workouts.
 * Prevents Android from killing the app and losing BLE connection.
 */
class WorkoutForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "vitruvian_workout_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START_WORKOUT = "com.devil.phoenixproject.START_WORKOUT"
        const val ACTION_STOP_WORKOUT = "com.devil.phoenixproject.STOP_WORKOUT"
        const val EXTRA_WORKOUT_MODE = "workout_mode"
        const val EXTRA_TARGET_REPS = "target_reps"

        private val log = Logger.withTag("WorkoutForegroundService")

        fun startWorkoutService(context: Context, workoutMode: String, targetReps: Int) {
            val intent = Intent(context, WorkoutForegroundService::class.java).apply {
                action = ACTION_START_WORKOUT
                putExtra(EXTRA_WORKOUT_MODE, workoutMode)
                putExtra(EXTRA_TARGET_REPS, targetReps)
            }
            // minSdk=26 (Android 8.0) requires startForegroundService
            context.startForegroundService(intent)
        }

        fun stopWorkoutService(context: Context) {
            val intent = Intent(context, WorkoutForegroundService::class.java).apply {
                action = ACTION_STOP_WORKOUT
            }
            context.startService(intent)
        }
    }

    private var workoutMode: String = "Old School"
    private var targetReps: Int = 10
    private var currentReps: Int = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        log.d { "Workout foreground service created" }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Android restarted the service after it was killed. BLE connection is lost and
            // the workout cannot meaningfully resume, so post the required foreground
            // notification (Android 8+ crashes without it) and shut down immediately.
            log.w { "WorkoutForegroundService restarted with null intent, stopping" }
            startForeground(NOTIFICATION_ID, createNotification())
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            ACTION_START_WORKOUT -> {
                workoutMode = intent.getStringExtra(EXTRA_WORKOUT_MODE) ?: "Old School"
                targetReps = intent.getIntExtra(EXTRA_TARGET_REPS, 10)
                currentReps = 0
                startForeground(NOTIFICATION_ID, createNotification())
                log.d { "Workout service started: $workoutMode, $targetReps reps" }
            }

            ACTION_STOP_WORKOUT -> {
                log.d { "Workout service stopping" }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        // A killed workout cannot resume the BLE connection, so do not request restart.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We don't need binding
    }

    private fun createNotificationChannel() {
        // minSdk=26 (Android 8.0) always has NotificationChannel
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Phoenix Workout",
            NotificationManager.IMPORTANCE_LOW, // Low importance = no sound/vibration
        ).apply {
            description = "Shows ongoing workout status"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(
            Context.NOTIFICATION_SERVICE,
        ) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Phoenix Workout Active")
        .setContentText("$workoutMode - $currentReps/$targetReps reps")
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setOngoing(true) // Cannot be dismissed
        .setCategory(NotificationCompat.CATEGORY_WORKOUT)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(createPendingIntent())
        .build()

    private fun createPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        // minSdk=26 (Android 8.0) is above API 23 (M), FLAG_IMMUTABLE always available
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    /**
     * Update the notification with current rep count.
     * Call this from WorkoutViewModel when rep count changes.
     */
    fun updateRepCount(reps: Int) {
        currentReps = reps
        val notificationManager = getSystemService(
            Context.NOTIFICATION_SERVICE,
        ) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        log.d { "Workout foreground service destroyed" }
    }
}
