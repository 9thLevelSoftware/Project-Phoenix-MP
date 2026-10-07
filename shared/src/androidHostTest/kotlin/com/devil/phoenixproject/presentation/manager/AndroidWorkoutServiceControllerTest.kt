package com.devil.phoenixproject.presentation.manager

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test

class AndroidWorkoutServiceControllerTest {

    private val appContext = mockk<Context>()
    private val context = mockk<Context>()
    private lateinit var controller: AndroidWorkoutServiceController

    private val snapshot = WorkoutServiceSnapshot(
        phase = WorkoutServicePhase.ACTIVE,
        workoutModeName = "Old School",
    )

    @Before
    fun setUp() {
        mockkStatic(ContextCompat::class)
        mockkStatic(Log::class)
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
        every { Log.println(any(), any(), any()) } returns 0
        every { context.applicationContext } returns appContext
        every { appContext.packageName } returns "com.devil.phoenixproject"
        every { ContextCompat.startForegroundService(any(), any()) } just runs
        controller = AndroidWorkoutServiceController(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun deadServiceStart_nextUpdateUsesStartForegroundService() {
        every { appContext.startService(any()) } throws IllegalStateException("dead")

        controller.showOrUpdate(snapshot)
        controller.showOrUpdate(snapshot)
        controller.showOrUpdate(snapshot)

        verify(exactly = 2) { ContextCompat.startForegroundService(appContext, any()) }
        verify(exactly = 1) { appContext.startService(any()) }
    }

    @Test
    fun startForegroundServiceFailure_retriesWithStartForegroundService() {
        every { ContextCompat.startForegroundService(any(), any()) } throws SecurityException("blocked")
        every { appContext.startService(any()) } returns null

        controller.showOrUpdate(snapshot)

        every { ContextCompat.startForegroundService(any(), any()) } just runs
        controller.showOrUpdate(snapshot)

        verify(exactly = 2) { ContextCompat.startForegroundService(appContext, any()) }
        verify(exactly = 0) { appContext.startService(any()) }
    }

    @Test
    fun runningService_updatesWithStartService() {
        every { appContext.startService(any()) } returns null

        controller.showOrUpdate(snapshot)
        controller.showOrUpdate(snapshot)
        controller.showOrUpdate(snapshot)

        verify(exactly = 1) { ContextCompat.startForegroundService(appContext, any()) }
        verify(exactly = 2) { appContext.startService(any()) }
    }
}
