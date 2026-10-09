package com.devil.phoenixproject.domain.voice

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.IntentFilter
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

/**
 * Drives [AndroidSafeWordListener] through Robolectric's SpeechRecognizer shadow.
 *
 * The platform delivers the final hypothesis in onResults after onEndOfSpeech, and
 * cancel()/destroy() drop it. So end-of-speech must leave the recognizer alive until
 * onResults or onError arrives, and the session must still restart exactly once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidSafeWordListenerRecognitionTest {
    private lateinit var listener: AndroidSafeWordListener
    private val detections = mutableListOf<String>()
    private val collectScope = CoroutineScope(Dispatchers.Unconfined + Job())

    @Before
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val service = ComponentName("test.recognizer", "test.recognizer.Service")
        shadowOf(app.packageManager).apply {
            addServiceIfNotPresent(service)
            addIntentFilterForService(service, IntentFilter(RecognitionService.SERVICE_INTERFACE))
        }

        // The listener debounces against SystemClock.elapsedRealtime(); a device
        // clock is never this close to zero.
        idle(Duration.ofSeconds(2))

        listener = AndroidSafeWordListener(app, "stop")
        collectScope.launch { listener.detectedWord.collect { detections += it } }
        listener.startListening()
        idle()
    }

    @After
    fun tearDown() {
        collectScope.cancel()
    }

    @Test
    fun finalResultAfterEndOfSpeechStillStopsTheMachine() {
        val first = currentRecognizer()
        shadowOf(first).triggerOnReadyForSpeech(Bundle())
        assertEquals(SafeWordState.Armed, listener.state.value)

        shadowOf(first).triggerOnEndOfSpeech()
        idle(Duration.ofMillis(1_000))
        assertSame("End of speech must not replace the recognizer", first, currentRecognizer())
        assertFalse("End of speech must not destroy the recognizer", shadowOf(first).isDestroyed)

        shadowOf(first).triggerOnResults(results("please stop"))
        assertEquals(listOf("stop"), detections)

        idle(Duration.ofMillis(600))
        assertTrue(shadowOf(first).isDestroyed)
        assertNotSame("Final results restart listening", first, currentRecognizer())
    }

    @Test
    fun endOfSpeechWithoutAFinalCallbackStillRestarts() {
        val first = currentRecognizer()
        shadowOf(first).triggerOnEndOfSpeech()

        idle(Duration.ofSeconds(3))

        assertTrue(shadowOf(first).isDestroyed)
        val second = currentRecognizer()
        assertNotSame(first, second)
        assertFalse(shadowOf(second).isDestroyed)
    }

    @Test
    fun resultsFollowedByAnErrorRestartOnlyOnce() {
        val first = currentRecognizer()
        shadowOf(first).triggerOnEndOfSpeech()
        shadowOf(first).triggerOnResults(results("nothing here"))
        idle(Duration.ofMillis(100))
        shadowOf(first).triggerOnError(SpeechRecognizer.ERROR_CLIENT)

        idle(Duration.ofMillis(450))
        val second = currentRecognizer()
        assertNotSame(first, second)

        // A second restart would destroy (or leak) this recognizer.
        idle(Duration.ofSeconds(3))
        assertSame(second, currentRecognizer())
        assertFalse(shadowOf(second).isDestroyed)
    }

    @Test
    fun callbacksFromAReplacedRecognizerDoNotRestartTheNewOne() {
        val first = currentRecognizer()
        shadowOf(first).triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        idle(Duration.ofMillis(600))
        val second = currentRecognizer()
        assertNotSame(first, second)

        shadowOf(first).triggerOnEndOfSpeech()
        shadowOf(first).triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)
        idle(Duration.ofSeconds(3))

        assertSame(second, currentRecognizer())
        assertFalse(shadowOf(second).isDestroyed)
    }

    @Test
    fun stopListeningDisarmsTheEndOfSpeechFallback() {
        val first = currentRecognizer()
        shadowOf(first).triggerOnEndOfSpeech()

        listener.stopListening()
        idle(Duration.ofSeconds(3))

        assertTrue(shadowOf(first).isDestroyed)
        assertSame("No recognizer may start after stop", first, currentRecognizer())
        assertEquals(SafeWordState.Disabled, listener.state.value)
    }

    private fun currentRecognizer(): SpeechRecognizer =
        requireNotNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer()) { "no recognizer was created" }

    private fun results(text: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    private fun idle(duration: Duration = Duration.ZERO) {
        val looper = shadowOf(Looper.getMainLooper())
        if (duration.isZero) looper.idle() else looper.idleFor(duration)
    }
}
