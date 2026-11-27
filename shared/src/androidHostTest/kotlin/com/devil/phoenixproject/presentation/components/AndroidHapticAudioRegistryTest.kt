package com.devil.phoenixproject.presentation.components

import android.media.AudioAttributes
import com.devil.phoenixproject.domain.model.HapticEvent
import com.devil.phoenixproject.domain.model.VulgarTier
import com.devil.phoenixproject.shared.R
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies Android haptic/cue audio routing and static `R.raw` cue registration:
 * - Cues route through [AudioAttributes.USAGE_MEDIA] (not sonification) so hardware
 *   volume keys and media mixing work consistently.
 * - Every `.ogg` cue in `shared/src/androidMain/res/raw` is statically registered in
 *   [AndroidCueResources] with a matching compile-time `R.raw` ID so release resource
 *   shrinking retains every cue asset.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidHapticAudioRegistryTest {

    @Test
    fun cueAudioAttributesRouteThroughMediaUsageOnly() {
        val attributes = buildCueAudioAttributes()
        assertEquals(
            AudioAttributes.USAGE_MEDIA,
            attributes.usage,
            "Workout cues must route through USAGE_MEDIA (STREAM_MUSIC).",
        )
        assertNotEquals(
            AudioAttributes.USAGE_ASSISTANCE_SONIFICATION,
            attributes.usage,
            "Workout cues must not revert to USAGE_ASSISTANCE_SONIFICATION.",
        )
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, attributes.contentType)
    }

    @Test
    fun everyRawOggCueFileIsStaticallyRegisteredWithCompileTimeRawId() {
        val rawDir = locateRawCueDir()
        val rawCueNames = rawDir.listFiles { file -> file.isFile && file.extension == "ogg" }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: emptySet()
        assertTrue(rawCueNames.isNotEmpty(), "Expected shared/src/androidMain/res/raw/*.ogg cues to exist")

        val registeredCues = (
            AndroidCueResources.eventCues.values +
                AndroidCueResources.badgeCues +
                AndroidCueResources.prCues +
                AndroidCueResources.repCountCues +
                AndroidCueResources.encouragementCues +
                AndroidCueResources.vulgarMildCues +
                AndroidCueResources.vulgarStrongCues +
                AndroidCueResources.dominatrixCues +
                listOf(AndroidCueResources.countdownTickCue)
            ).distinctBy { it.name }

        assertEquals(
            rawCueNames,
            registeredCues.map { it.name }.toSet(),
            "Every shared raw .ogg cue must be statically registered in AndroidCueResources.",
        )

        for (cue in registeredCues) {
            val expectedRawId = R.raw::class.java.getField(cue.name).getInt(null)
            assertEquals(
                expectedRawId,
                cue.rawResId,
                "Cue '${cue.name}' must reference compile-time R.raw.${cue.name}.",
            )
        }
    }

    @Test
    fun cueForEventRoutesAutoStartReadyRepCountsCountdownAndVerbalPools() {
        val autoStartReady = AndroidCueResources.cueForEvent(HapticEvent.AUTO_START_READY)
        assertEquals("auto_start_ready", autoStartReady?.name)
        assertEquals(R.raw.auto_start_ready, autoStartReady?.rawResId)

        assertEquals("dominatrix_unlock", AndroidCueResources.cueForEvent(HapticEvent.DOMINATRIX_MODE_UNLOCKED)?.name)
        assertEquals("beep", AndroidCueResources.cueForEvent(HapticEvent.COUNTDOWN_TICK(3))?.name)
        assertEquals("rep_01", AndroidCueResources.cueForEvent(HapticEvent.REP_COUNT_ANNOUNCED(1))?.name)
        assertEquals("rep_05", AndroidCueResources.cueForEvent(HapticEvent.REP_COUNT_ANNOUNCED(5))?.name)
        assertEquals("rep_25", AndroidCueResources.cueForEvent(HapticEvent.REP_COUNT_ANNOUNCED(25))?.name)
        assertFailsWith<IllegalArgumentException> { HapticEvent.REP_COUNT_ANNOUNCED(0) }
        assertFailsWith<IllegalArgumentException> { HapticEvent.REP_COUNT_ANNOUNCED(26) }
        assertNull(AndroidCueResources.cueForEvent(HapticEvent.ERROR))

        assertTrue(
            AndroidCueResources.cueForEvent(
                HapticEvent.VERBAL_ENCOURAGEMENT(vulgarTier = VulgarTier.STRONG, dominatrixMode = true, vulgarMode = false),
            ) in AndroidCueResources.encouragementCues,
        )
        assertTrue(
            AndroidCueResources.cueForEvent(
                HapticEvent.VERBAL_ENCOURAGEMENT(vulgarTier = VulgarTier.MILD, dominatrixMode = true, vulgarMode = true),
            ) in AndroidCueResources.dominatrixCues,
        )
        assertTrue(
            AndroidCueResources.cueForEvent(
                HapticEvent.VERBAL_ENCOURAGEMENT(vulgarTier = VulgarTier.MILD, dominatrixMode = false, vulgarMode = true),
            ) in AndroidCueResources.vulgarMildCues,
        )
        assertTrue(
            AndroidCueResources.cueForEvent(
                HapticEvent.VERBAL_ENCOURAGEMENT(vulgarTier = VulgarTier.STRONG, dominatrixMode = false, vulgarMode = true),
            ) in AndroidCueResources.vulgarStrongCues,
        )
        assertTrue(
            AndroidCueResources.cueForEvent(
                HapticEvent.VERBAL_ENCOURAGEMENT(vulgarTier = VulgarTier.MIX, dominatrixMode = false, vulgarMode = true),
            ) in (AndroidCueResources.vulgarMildCues + AndroidCueResources.vulgarStrongCues),
        )
    }

    private fun locateRawCueDir(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            for (candidate in listOf(
                File(dir, "shared/src/androidMain/res/raw"),
                File(dir, "src/androidMain/res/raw"),
            )) {
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        error("Could not locate shared/src/androidMain/res/raw from ${System.getProperty("user.dir")}")
    }
}
