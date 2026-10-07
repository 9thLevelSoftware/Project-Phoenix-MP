package com.devil.phoenixproject.presentation.manager

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidWorkoutServiceControllerTest {

    private lateinit var context: RecordingContext
    private lateinit var controller: AndroidWorkoutServiceController

    private val snapshot = WorkoutServiceSnapshot(
        phase = WorkoutServicePhase.ACTIVE,
        workoutModeName = "Old School",
    )

    @Before
    fun setUp() {
        context = RecordingContext(ApplicationProvider.getApplicationContext())
        controller = AndroidWorkoutServiceController(context)
    }

    @Test
    fun deadServiceStart_nextUpdateUsesStartForegroundService() {
        context.startServiceError = IllegalStateException("dead")

        controller.showOrUpdate(snapshot)
        controller.showOrUpdate(snapshot)
        controller.showOrUpdate(snapshot)

        assertEquals(2, context.foregroundStarts.size)
        assertEquals(1, context.serviceStarts.size)
    }

    @Test
    fun startForegroundServiceFailure_retriesWithStartForegroundService() {
        context.foregroundError = SecurityException("blocked")

        controller.showOrUpdate(snapshot)

        context.foregroundError = null
        controller.showOrUpdate(snapshot)

        assertEquals(2, context.foregroundStarts.size)
        assertEquals(0, context.serviceStarts.size)
    }

    @Test
    fun runningService_updatesWithStartService() {
        controller.showOrUpdate(snapshot)
        controller.showOrUpdate(snapshot)
        controller.showOrUpdate(snapshot)

        assertEquals(1, context.foregroundStarts.size)
        assertEquals(2, context.serviceStarts.size)
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        val foregroundStarts = mutableListOf<Intent>()
        val serviceStarts = mutableListOf<Intent>()
        var startServiceError: Exception? = null
        var foregroundError: Exception? = null

        override fun getApplicationContext(): Context = this

        override fun startService(service: Intent): ComponentName? {
            serviceStarts += service
            startServiceError?.let { throw it }
            return ComponentName(packageName, "service")
        }

        override fun startForegroundService(service: Intent): ComponentName? {
            foregroundStarts += service
            foregroundError?.let { throw it }
            return ComponentName(packageName, "service")
        }
    }
}
