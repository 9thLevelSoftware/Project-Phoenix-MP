package com.devil.phoenixproject.qa

import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileQaSeedReceiverTest {
    @Test
    fun `receiver enables gate before async seed and always finishes success`() = runTest {
        val events = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val pending = RecordingPendingResult(events)
        var launched: (suspend () -> Unit)? = null
        val receiver = ProfileQaSeedReceiver(
            enableGate = { events += "gate" },
            seed = {
                events += "seed"
                ProfileQaSeedResult("PROFILE_QA_SEED_OK", "a", "b", 10)
            },
            beginAsync = {
                events += "goAsync"
                pending
            },
            launch = { launched = it },
            log = logs::add,
        )

        receiver.onReceive(mockk(), intent(ProfileQaSeedReceiver.ACTION_SEED_PROFILE))
        assertEquals(listOf("gate", "goAsync"), events)
        launched?.invoke() ?: error("Seed coroutine was not launched")

        assertEquals(listOf("gate", "goAsync", "seed", "finish"), events)
        assertEquals(listOf("PROFILE_QA_SEED_OK"), logs)
    }

    @Test
    fun `receiver finishes and logs stable category when seed fails`() = runTest {
        val events = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val pending = RecordingPendingResult(events)
        var launched: (suspend () -> Unit)? = null
        val receiver = ProfileQaSeedReceiver(
            enableGate = { events += "gate" },
            seed = {
                events += "seed"
                throw IllegalStateException("Phoenix Alpha must not appear in logs")
            },
            beginAsync = {
                events += "goAsync"
                pending
            },
            launch = { launched = it },
            log = logs::add,
        )

        receiver.onReceive(mockk(), intent(ProfileQaSeedReceiver.ACTION_SEED_PROFILE))
        launched?.invoke() ?: error("Seed coroutine was not launched")

        assertEquals(listOf("gate", "goAsync", "seed", "finish"), events)
        assertEquals(listOf("PROFILE_QA_SEED_FAILED:IllegalStateException"), logs)
        assertFalse(logs.single().contains("Phoenix Alpha"))
    }

    @Test
    fun `synchronous gate failure aborts before goAsync or first seed write`() {
        val events = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val receiver = ProfileQaSeedReceiver(
            enableGate = {
                events += "gate"
                throw IllegalStateException("commit failed")
            },
            seed = {
                events += "seed"
                error("must not seed")
            },
            beginAsync = {
                events += "goAsync"
                RecordingPendingResult(events)
            },
            launch = { error("must not launch") },
            log = logs::add,
        )

        receiver.onReceive(mockk(), intent(ProfileQaSeedReceiver.ACTION_SEED_PROFILE))

        assertEquals(listOf("gate"), events)
        assertEquals(listOf("PROFILE_QA_SEED_FAILED:IllegalStateException"), logs)
    }

    @Test
    fun `debug manifest exports only the explicit QA seed action`() {
        val manifest = locateDebugManifest().readText()

        assertTrue(manifest.contains(".qa.ProfileQaSeedReceiver"))
        assertTrue(manifest.contains("android:exported=\"true\""))
        assertTrue(manifest.contains("com.devil.phoenixproject.QA_SEED_PROFILE"))
    }

    private fun intent(action: String): Intent = mockk<Intent>().also {
        every { it.action } returns action
    }

    private fun locateDebugManifest(): java.io.File {
        val start = java.io.File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        return generateSequence(start) { it.parentFile }
            .flatMap { parent ->
                sequenceOf(
                    java.io.File(parent, "androidApp/src/debug/AndroidManifest.xml"),
                    java.io.File(parent, "src/debug/AndroidManifest.xml"),
                )
            }
            .firstOrNull { it.isFile }
            ?: error("Could not locate androidApp/src/debug/AndroidManifest.xml from $start")
    }

    private class RecordingPendingResult(
        private val events: MutableList<String>,
    ) : ProfileQaSeedPendingResult {
        override fun finish() {
            events += "finish"
        }
    }
}
