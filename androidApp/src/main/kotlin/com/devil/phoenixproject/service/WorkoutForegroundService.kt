package com.devil.phoenixproject.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import android.os.LocaleList
import androidx.core.app.NotificationCompat
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.MainActivity
import com.devil.phoenixproject.R
import com.devil.phoenixproject.presentation.manager.WorkoutServicePhase
import com.devil.phoenixproject.presentation.manager.WorkoutServiceProtocol
import com.devil.phoenixproject.presentation.viewmodel.ThemeViewModel
import java.util.Locale

/**
 * Foreground service to keep the app alive during workouts.
 * Prevents Android from killing the app and losing BLE connection.
 */
class WorkoutForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "phoenix_workout_channel"
        const val NOTIFICATION_ID = 1

        /** Same key [MainActivity] reads from [ThemeViewModel.THEME_PREFS_FILE]. */
        private const val PERSISTED_LANGUAGE_KEY = "language"

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
    private var postedChannelName: String? = null
    private var postedChannelDescription: String? = null

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
                    isForegroundActive = startWorkoutForeground()
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

            else -> {
                // Unknown/missing action: never entered foreground mode. If we were
                // launched via startForegroundService(), Android requires a timely
                // startForeground() call; since we cannot satisfy that for an
                // unrecognized action, stop immediately to avoid an ANR/crash.
                log.w { "Unexpected action ${intent.action}; stopping" }
                stopSelf(startId)
                return START_NOT_STICKY
            }
        }

        // A killed workout cannot resume the BLE connection, so do not request restart.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Promote the service to the foreground. Returns true on success.
     *
     * F056: startForeground() can throw at runtime (SecurityException,
     * ForegroundServiceStartNotAllowedException, or a service-type mismatch when
     * POST_NOTIFICATIONS / connected-device prerequisites aren't satisfied). The
     * throw happens inside this service process, so the controller's try/catch
     * around startForegroundService() cannot catch it and the app would crash
     * mid-workout. Catch it here, log, and stop the service so we degrade
     * gracefully instead.
     */
    private fun startWorkoutForeground(): Boolean {
        val notification = createNotification()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            log.e(e) { "Failed to start workout foreground service; stopping" }
            stopSelf()
            false
        }
    }

    /**
     * Context whose string resources follow the in-app locale.
     *
     * API 33+ per-app locales set by [MainActivity] already apply to this service.
     * API 26-32 only update the activity configuration, so resolve copy against the
     * same persisted language before reading notification strings.
     */
    private fun notificationStringsContext(): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return this
        val langCode = runCatching {
            getSharedPreferences(ThemeViewModel.THEME_PREFS_FILE, Context.MODE_PRIVATE)
                .getString(PERSISTED_LANGUAGE_KEY, null)
        }.getOrNull()
        if (langCode.isNullOrBlank()) return this
        val locale = Locale.forLanguageTag(langCode)
        val config = Configuration(resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return createConfigurationContext(config)
    }

    private fun createNotificationChannel() {
        val strings = notificationStringsContext()
        val name = strings.getString(R.string.workout_notification_channel_name)
        val description = strings.getString(R.string.workout_notification_channel_description)
        if (name == postedChannelName && description == postedChannelDescription) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            name,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            this.description = description
            setShowBadge(false)
        }

        val notificationManager = getSystemService(
            Context.NOTIFICATION_SERVICE,
        ) as NotificationManager
        notificationManager.createNotificationChannel(channel)
        postedChannelName = name
        postedChannelDescription = description
    }

    private fun updateNotification() {
        // Channel name is fixed at creation unless we post it again. Refresh only when
        // the resolved copy changed (language switch); steady-state updates skip this.
        createNotificationChannel()
        val notificationManager = getSystemService(
            Context.NOTIFICATION_SERVICE,
        ) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, createNotification())
    }

    private fun createNotification() = notificationStringsContext().let { strings ->
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(notificationTitle(strings))
            .setContentText(notificationText(strings))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(createPendingIntent())
            .build()
    }

    private fun notificationTitle(strings: Context): String = when (notificationState.phase) {
        WorkoutServicePhase.INITIALIZING -> strings.getString(R.string.workout_notification_title_initializing)
        WorkoutServicePhase.COUNTDOWN -> strings.getString(R.string.workout_notification_title_countdown)
        WorkoutServicePhase.ACTIVE -> notificationState.exerciseName
            ?: strings.getString(R.string.workout_notification_title_active)
        WorkoutServicePhase.SET_SUMMARY -> strings.getString(R.string.workout_notification_title_set_complete)
        WorkoutServicePhase.RESTING -> strings.getString(R.string.workout_notification_title_rest)
        WorkoutServicePhase.JUST_LIFT_REST -> strings.getString(R.string.workout_notification_title_just_lift_rest)
        WorkoutServicePhase.PAUSED -> strings.getString(R.string.workout_notification_title_paused)
    }

    private fun notificationText(strings: Context): String {
        val details = mutableListOf<String>()

        when (notificationState.phase) {
            WorkoutServicePhase.INITIALIZING ->
                details += strings.getString(
                    R.string.workout_notification_preparing,
                    notificationState.workoutMode,
                )

            WorkoutServicePhase.COUNTDOWN -> {
                notificationState.exerciseName?.let(details::add)
                notificationState.secondsRemaining?.let { seconds ->
                    details += strings.getString(R.string.workout_notification_starts_in, seconds)
                }
            }

            WorkoutServicePhase.ACTIVE -> {
                notificationState.currentSetLabel(strings)?.let(details::add)
                notificationState.repLabel(strings)?.let(details::add)
                details += notificationState.workoutMode
            }

            WorkoutServicePhase.SET_SUMMARY -> {
                notificationState.exerciseName?.let(details::add)
                notificationState.repLabel(strings)?.let(details::add)
                notificationState.currentSetLabel(strings)?.let(details::add)
            }

            WorkoutServicePhase.RESTING -> {
                notificationState.nextExerciseName?.let { name ->
                    details += strings.getString(R.string.workout_notification_next, name)
                }
                notificationState.currentSetLabel(strings)?.let(details::add)
                notificationState.secondsRemaining?.let { seconds ->
                    details += strings.getString(R.string.workout_notification_seconds_remaining, seconds)
                }
            }

            WorkoutServicePhase.JUST_LIFT_REST -> {
                notificationState.exerciseName?.let(details::add)
                notificationState.secondsRemaining?.let { seconds ->
                    details += strings.getString(R.string.workout_notification_seconds_remaining, seconds)
                }
            }

            WorkoutServicePhase.PAUSED -> {
                notificationState.exerciseName?.let(details::add)
                notificationState.currentSetLabel(strings)?.let(details::add)
            }
        }

        return details.filter { it.isNotBlank() }.joinToString(" | ").ifBlank {
            strings.getString(R.string.workout_notification_in_progress)
        }
    }

    private fun NotificationState.currentSetLabel(strings: Context): String? {
        val set = currentSet
        val total = totalSets
        if (set == null || total == null || set <= 0 || total <= 0) return null
        return strings.getString(R.string.workout_notification_set_progress, set, total)
    }

    private fun NotificationState.repLabel(strings: Context): String? {
        val reps = completedReps
        val target = targetReps
        return when {
            reps == null || reps < 0 -> null
            target != null && target > 0 ->
                strings.getString(R.string.workout_notification_reps_progress, reps, target)
            else -> strings.getString(R.string.workout_notification_reps, reps)
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
            workoutMode = getStringExtra(WorkoutServiceProtocol.EXTRA_WORKOUT_MODE)
                ?.let(::localizedWorkoutMode)
                ?: previous.workoutMode,
            exerciseName = getNullableStringExtra(WorkoutServiceProtocol.EXTRA_EXERCISE_NAME),
            nextExerciseName = getNullableStringExtra(WorkoutServiceProtocol.EXTRA_NEXT_EXERCISE_NAME),
            currentSet = getNullableIntExtra(WorkoutServiceProtocol.EXTRA_CURRENT_SET),
            totalSets = getNullableIntExtra(WorkoutServiceProtocol.EXTRA_TOTAL_SETS),
            completedReps = getNullableIntExtra(WorkoutServiceProtocol.EXTRA_COMPLETED_REPS),
            targetReps = getNullableIntExtra(WorkoutServiceProtocol.EXTRA_TARGET_REPS),
            secondsRemaining = getNullableIntExtra(WorkoutServiceProtocol.EXTRA_SECONDS_REMAINING),
        )
    }

    private fun localizedWorkoutMode(mode: String): String = when (mode) {
        WorkoutServiceProtocol.WORKOUT_MODE_BODYWEIGHT ->
            notificationStringsContext().getString(R.string.workout_mode_bodyweight)
        else -> mode
    }

    private fun Intent.getNullableIntExtra(name: String): Int? {
        val value = getIntExtra(name, Int.MIN_VALUE)
        return if (value == Int.MIN_VALUE || value < 0) null else value
    }

    private fun Intent.getNullableStringExtra(name: String): String? = getStringExtra(name)?.takeIf { it.isNotBlank() }

    override fun onDestroy() {
        super.onDestroy()
        log.d { "Workout foreground service destroyed" }
    }
}
