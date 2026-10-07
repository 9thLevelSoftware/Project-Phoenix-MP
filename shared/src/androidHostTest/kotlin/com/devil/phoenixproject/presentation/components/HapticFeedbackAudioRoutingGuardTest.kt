package com.devil.phoenixproject.presentation.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HapticFeedbackAudioRoutingGuardTest {
    private val projectRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "shared/src/androidMain").exists()) {
            dir = dir.parentFile ?: break
        }
        dir
    }

    private val hapticFeedbackSource: File
        get() = File(
            projectRoot,
            "shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/components/HapticFeedbackEffect.android.kt",
        )

    private val rawCueDir: File
        get() = File(projectRoot, "shared/src/androidMain/res/raw")

    private fun registeredCueNames(source: String): Set<String> = Regex("""AndroidCueResource\("([^"]+)",\s*R\.raw\.\w+\)""")
        .findAll(source)
        .map { it.groupValues[1] }
        .toSet()

    @Test
    fun hapticFeedbackCueAudio_routesThroughMediaUsageOnly() {
        val source = hapticFeedbackSource.readText()

        assertTrue(
            source.contains(".setUsage(AudioAttributes.USAGE_MEDIA)"),
            "HapticFeedbackEffect must route cue playback through USAGE_MEDIA so cues use STREAM_MUSIC.",
        )
        assertFalse(
            source.contains("USAGE_ASSISTANCE_SONIFICATION"),
            "HapticFeedbackEffect must not revert workout cue audio to the sonification stream.",
        )
        assertFalse(
            source.contains("getIdentifier("),
            "HapticFeedbackEffect must not use dynamic raw resource lookup; release shrinking can strip those assets.",
        )
    }

    @Test
    fun cueResourceRegistry_matchesSharedRawCueFiles() {
        val source = hapticFeedbackSource.readText()
        val rawCueNames = rawCueDir.listFiles { file -> file.isFile && file.extension == "ogg" }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: emptySet()

        val registeredCueNames = registeredCueNames(source)

        assertTrue(rawCueNames.isNotEmpty(), "Expected shared Android raw cue resources to exist.")
        assertEquals(
            rawCueNames,
            registeredCueNames,
            "Every shared raw cue file must be statically registered so release resource shrinking keeps it.",
        )
    }

    @Test
    fun cueResourceRegistry_preservesWorkoutEventMappings() {
        val source = hapticFeedbackSource.readText()

        assertTrue(source.contains("HapticEvent.REP_COMPLETED to repCompleteStrong"))
        assertTrue(source.contains("HapticEvent.FINAL_REP to boopBeepBeep"))
        assertTrue(source.contains("HapticEvent.WARMUP_COMPLETE to beepBoop"))
        assertTrue(source.contains("HapticEvent.WORKOUT_COMPLETE to boopBeepBeep"))
        assertTrue(source.contains("HapticEvent.WORKOUT_START to chirpChirp"))
        assertTrue(source.contains("HapticEvent.WORKOUT_END to chirpChirp"))
        assertTrue(source.contains("HapticEvent.REST_ENDING to restOver"))
        assertTrue(source.contains("HapticEvent.DISCO_MODE_UNLOCKED to discoMode"))
        assertTrue(source.contains("HapticEvent.WARMUP_TO_WORKING to beepBoop"))
        assertTrue(source.contains("HapticEvent.VELOCITY_THRESHOLD_REACHED to boopBeepBeep"))
        assertTrue(source.contains("is HapticEvent.REP_COUNT_ANNOUNCED -> repCountCues.getOrNull(event.repNumber - 1)"))
        assertTrue(source.contains("is HapticEvent.COUNTDOWN_TICK -> countdownTickCue"))
        assertTrue(source.contains("val countdownTickCue: AndroidCueResource = beep"))
        assertTrue(source.contains("""AndroidCueResource("rep_complete_strong", R.raw.rep_complete_strong)"""))
    }

    @Test
    fun mediaPlayerFallback_appliesCountdownPlaybackRate() {
        val source = hapticFeedbackSource.readText()
        val fallbackSource = source
            .substringAfter("private fun playWithMediaPlayer")
            .substringBefore("private fun buildCueAudioAttributes")

        assertTrue(fallbackSource.contains("event is HapticEvent.COUNTDOWN_TICK"))
        assertTrue(fallbackSource.contains("ExerciseCountdownCuePolicy.playbackRate(event.secondsRemaining)"))
        assertTrue(fallbackSource.contains("mediaPlayer.setPlaybackParams"))
    }

    @Test
    fun releaseVerificationSentinelCues_areRegistered() {
        val registeredCueNames = registeredCueNames(hapticFeedbackSource.readText())

        assertTrue("beep" in registeredCueNames)
        assertTrue("chirpchirp" in registeredCueNames)
        assertTrue("rep_complete_strong" in registeredCueNames)
        assertTrue("rep_05" in registeredCueNames)
        assertTrue("boopbeepbeep" in registeredCueNames)
    }

    /**
     * Issue #611: Verbal encouragement cue reachability check. Every new pool
     * asset (15 neutral + 12 mild + 12 strong + 12 dominatrix + 1 unlock) must be
     * statically registered so release resource shrinking keeps it, and the
     * cueForEvent routing must reference each pool.
     */
    @Test
    fun verbalEncouragementCues_areReachableFromEventRouting() {
        val source = hapticFeedbackSource.readText()

        // Pool names: encouragement (15), vulgar_mild (12), vulgar_strong (12), dominatrix (12), dominatrix_unlock (1)
        val registeredCueNames = registeredCueNames(source)
        assertTrue("encouragement_01" in registeredCueNames, "encouragement_01 must be registered")
        assertTrue("encouragement_15" in registeredCueNames, "encouragement_15 must be registered")
        assertTrue("vulgar_mild_01" in registeredCueNames, "vulgar_mild_01 must be registered")
        assertTrue("vulgar_mild_12" in registeredCueNames, "vulgar_mild_12 must be registered")
        assertTrue("vulgar_strong_01" in registeredCueNames, "vulgar_strong_01 must be registered")
        assertTrue("vulgar_strong_12" in registeredCueNames, "vulgar_strong_12 must be registered")
        assertTrue("dominatrix_01" in registeredCueNames, "dominatrix_01 must be registered")
        assertTrue("dominatrix_12" in registeredCueNames, "dominatrix_12 must be registered")
        assertTrue("dominatrix_unlock" in registeredCueNames, "dominatrix_unlock must be registered")

        // Pool list declarations must exist
        assertTrue(source.contains("val encouragementCues: List<AndroidCueResource>"))
        assertTrue(source.contains("val vulgarMildCues: List<AndroidCueResource>"))
        assertTrue(source.contains("val vulgarStrongCues: List<AndroidCueResource>"))
        assertTrue(source.contains("val dominatrixCues: List<AndroidCueResource>"))

        // cueForEvent must route VERBAL_ENCOURAGEMENT through the shared pool pick
        assertTrue(source.contains("is HapticEvent.VERBAL_ENCOURAGEMENT ->"))
        assertTrue(source.contains("dominatrixCues.randomPoolEntry()"))
        assertTrue(source.contains("vulgarMildCues.randomPoolEntry()"))
        assertTrue(source.contains("vulgarStrongCues.randomPoolEntry()"))
        assertTrue(source.contains("encouragementCues.randomPoolEntry()"))

        // Event mapping for dominatrix unlock SFX
        assertTrue(source.contains("HapticEvent.DOMINATRIX_MODE_UNLOCKED to dominatrixUnlock"))
    }

    /**
     * Every former `list[Random.nextInt(list.size)]` cue pick goes through one
     * helper. Empty-pool routing stays at the call sites.
     */
    @Test
    fun randomCuePick_usesOneSharedHelper() {
        val source = hapticFeedbackSource.readText()
        val helperAt = source.indexOf("private fun <T> List<T>.randomPoolEntry(): T")
        assertTrue(helperAt >= 0, "randomPoolEntry owns the uniform cue pick")

        val helper = source.substring(helperAt, source.indexOf("private fun playSound", helperAt))
        assertTrue(
            helper.contains("this[Random.nextInt(size)]"),
            "the helper is the uniform pool index",
        )
        assertEquals(1, source.split("Random.nextInt").size - 1, "Random.nextInt stays in the helper only")

        val picks = listOf(
            "badgeSoundIds.randomPoolEntry()",
            "prSoundIds.randomPoolEntry()",
            "badgeCues.randomPoolEntry()",
            "prCues.randomPoolEntry()",
            "encouragementCues.randomPoolEntry()",
            "dominatrixCues.randomPoolEntry()",
            "vulgarMildCues.randomPoolEntry()",
            "vulgarStrongCues.randomPoolEntry()",
            "combined.randomPoolEntry()",
        )
        for (pick in picks) {
            assertTrue(source.contains(pick), "cue pick goes through $pick")
        }
        assertEquals(
            2,
            source.split("encouragementCues.randomPoolEntry()").size - 1,
            "vulgar-off and fallback both pick the encouragement pool",
        )
        assertEquals(
            10,
            Regex("""[A-Za-z0-9]+\.randomPoolEntry\(\)""").findAll(source).count(),
            "every former nextInt pool pick uses the helper",
        )
    }
}
