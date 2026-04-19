package com.devil.phoenixproject.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.MainActivity
import com.devil.phoenixproject.presentation.manager.WorkoutServicePhase
import com.devil.phoenixproject.presentation.manager.WorkoutServiceProtocol

/**
 * Foreground service to keep the app alive during workouts.
 * Prevents Android from killing the app and losing BLE connection.
 */
class WorkoutForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "vitruvian_workout_channel"
        const val NOTIFICATION_ID = 1

        private val log = Logger.withTag("WorkoutForegroundService")
    }

    private data class NotificationState(
        val phase: WorkoutServicePhase = WorkoutServicePhase.INITIALIZING,
        val workoutMode: String = "Old School",
        val exerciseName: String? = null,
        val nextExerciseName: String? = null,
        val currentSet: Int? = null,
        val totalSets: Int? = null,
        val completedReps: Int? = null,
        val targetReps: Int? = null,
        val secondsRemaining: Int? = null,
    )

    private var notificationState = NotificationState()
    private var isForegroundActive = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        log.d { "Workout foreground service created" }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // Process death restart: BLE session is gone; do not call startForeground (avoids
            // wrong service-type on API 34+). Tear down immediately.
            log.w { "WorkoutForegroundService restarted with null intent, stopping" }
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent.action) {
            WorkoutServiceProtocol.ACTION_SYNC -> {
                notificationState = intent.toNotificationState(previous = notificationState)
                if (!isForegroundActive) {
                    startWorkoutForeground()
                    isForegroundActive = true
                } else {
                    updateNotification()
                }
                log.d {
                    "Workout service synced: phase=${notificationState.phase}, mode=${notificationState.workoutMode}, " +
                        "exercise=${notificationState.exerciseName}, seconds=${notificationState.secondsRemaining}"
                }
            }

            WorkoutServiceProtocol.ACTION_STOP -> {
                log.d { "Workout service stopping" }
                isForegroundActive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        // A killed workout cannot resume the BLE connection, so do not request restart.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWorkoutForeground() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Phoenix Workout",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows ongoing workout status"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(
            Context.NOTIFICATION_SERVICE,
        ) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(
            Context.NOTIFICATION_SERVICE,
        ) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun createNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(notificationTitle())
        .setContentText(notificationText())
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_WORKOUT)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(createPendingIntent())
        .build()

    private fun notificationTitle(): String = when (notificationState.phase) {
        WorkoutServicePhase.INITIALIZING -> "Phoenix Workout"
        WorkoutServicePhase.COUNTDOWN -> "Workout Starting"
        WorkoutServicePhase.ACTIVE -> notificationState.exerciseName ?: "Workout Active"
        WorkoutServicePhase.SET_SUMMARY -> "Set Complete"
        WorkoutServicePhase.RESTING -> "Rest Timer"
        WorkoutServicePhase.JUST_LIFT_REST -> "Just Lift Rest"
        WorkoutServicePhase.PAUSED -> "Workout Paused"
    }

    private fun notificationText(): String {
        val details = mutableListOf<String>()

        when (notificationState.phase) {
            WorkoutServicePhase.INITIALIZING -> details += "Preparing ${notificationState.workoutMode}"
            WorkoutServicePhase.COUNTDOWN -> {
                notificationState.exerciseName?.let(details::add)
                notificationState.secondsRemaining?.let { details += "Starts in ${it}s" }
            }

            WorkoutServicePhase.ACTIVE -> {
                notificationState.currentSetLabel()?.let(details::add)
                notificationState.repLabel()?.let(details::add)
                details += notificationState.workoutMode
            }

            WorkoutServicePhase.SET_SUMMARY -> {
                notificationState.exerciseName?.let(details::add)
                notificationState.repLabel()?.let(details::add)
                notificationState.currentSetLabel()?.let(details::add)
            }

            WorkoutServicePhase.RESTING -> {
                notificationState.nextExerciseName?.let { details += "Next: $it" }
                notificationState.currentSetLabel()?.let(details::add)
                notificationState.secondsRemaining?.let { details += "${it}s remaining" }
            }

            WorkoutServicePhase.JUST_LIFT_REST -> {
                notificationState.exerciseName?.let(details::add)
                notificationState.secondsRemaining?.let { details += "${it}s remaining" }
            }

            WorkoutServicePhase.PAUSED -> {
                notificationState.exerciseName?.let(details::add)
                notificationState.currentSetLabel()?.let(details::add)
            }
        }

        return details.filter { it.isNotBlank() }.joinToString(" | ").ifBlank {
            "Phoenix workout in progress"
        }
    }

    private fun NotificationState.currentSetLabel(): String? {
        val set = currentSet
        val total = totalSets
        if (set == null || total == null || set <= 0 || total <= 0) return null
        return "Set $set/$total"
    }

    private fun NotificationState.repLabel(): String? {
        val reps = completedReps
        val target = targetReps
        return when {
            reps == null || reps < 0 -> null
            target != null && target > 0 -> "Reps $reps/$target"
            else -> "Reps $reps"
        }
    }

    private fun createPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun Intent.toNotificationState(previous: NotificationState): NotificationState {
        val phaseName = getStringExtra(WorkoutServiceProtocol.EXTRA_PHASE)
        val phase = phaseName?.let {
            runCatching { WorkoutServicePhase.valueOf(it) }.getOrNull()
        } ?: previous.phase

        return NotificationState(
            phase = phase,
            workoutMode = getStringExtra(WorkoutServiceProtocol.EXTRA_WORKOUT_MODE) ?: previous.workoutMode,
            exerciseName = getNullableStringExtra(WorkoutServiceProtocol.EXTRA_EXERCISE_NAME),
            nextExerciseName = getNullableStringExtra(WorkoutServiceProtocol.EXTRA_NEXT_EXERCISE_NAME),
            currentSet = getNullableIntExtra(WorkoutServiceProtocol.EXTRA_CURRENT_SET),
            totalSets = getNullableIntExtra(WorkoutServiceProtocol.EXTRA_TOTAL_SETS),
            completedReps = getNullableIntExtra(WorkoutServiceProtocol.EXTRA_COMPLETED_REPS),
            targetReps = getNullableIntExtra(WorkoutServiceProtocol.EXTRA_TARGET_REPS),
            secondsRemaining = getNullableIntExtra(WorkoutServiceProtocol.EXTRA_SECONDS_REMAINING),
        )
    }

    private fun Intent.getNullableIntExtra(name: String): Int? {
        val value = getIntExtra(name, Int.MIN_VALUE)
        return if (value == Int.MIN_VALUE || value < 0) null else value
    }

    private fun Intent.getNullableStringExtra(name: String): String? =
        getStringExtra(name)?.takeIf { it.isNotBlank() }

    override fun onDestroy() {
        super.onDestroy()
        log.d { "Workout foreground service destroyed" }
    }
}
