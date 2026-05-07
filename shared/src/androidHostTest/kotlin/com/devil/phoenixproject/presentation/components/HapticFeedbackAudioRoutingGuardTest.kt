package com.devil.phoenixproject.presentation.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Structural guards for Android workout cue playback.
 *
 * These protect the v0.7.x known-good audio path: short workout cues should be
 * classified as assistance sonification, while Fire OS keeps the explicit media fallback.
 */
class HapticFeedbackAudioRoutingGuardTest {

    private val projectRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "shared/src/androidMain").exists()) {
            dir = dir.parentFile ?: break
        }
        dir
    }

    private val hapticSourceFile: File
        get() = File(
            projectRoot,
            "shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/components/HapticFeedbackEffect.android.kt",
        )

    private val rawResourceDir: File
        get() = File(projectRoot, "androidApp/src/main/res/raw")

    @Test
    fun `standard Android cue playback uses assistance sonification`() {
        val source = hapticSourceFile.readText()

        assertTrue(
            source.contains("STANDARD_ANDROID_CUE_USAGE = AudioAttributes.USAGE_ASSISTANCE_SONIFICATION"),
            "Standard Android cues must use assistance sonification, matching the v0.7.x playback path.",
        )
        assertTrue(
            source.contains("FIRE_OS_CUE_USAGE = AudioAttributes.USAGE_MEDIA"),
            "Fire OS must keep the media fallback for its SoundPool volume issue.",
        )
        assertTrue(
            source.contains("buildCueAudioAttributes(STANDARD_ANDROID_CUE_USAGE)"),
            "SoundPool must be built with the standard cue audio attributes.",
        )
        assertFalse(
            source.contains("AudioAttributes.USAGE_GAME"),
            "Workout cues must not be classified as game audio; that was the v0.9.0 sweep regression.",
        )
    }

    @Test
    fun `cue playback path does not request Android audio focus`() {
        val source = hapticSourceFile.readText()

        assertFalse(source.contains("AudioFocusRequest"))
        assertFalse(source.contains("requestAudioFocus"))
        assertFalse(source.contains("AUDIOFOCUS_GAIN_TRANSIENT"))
    }

    @Test
    fun `referenced raw cue resources exist`() {
        val source = hapticSourceFile.readText()
        val availableRawNames = rawResourceDir
            .listFiles()
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: emptySet()

        val loadSoundNames = Regex("""loadSoundByName\(context, soundPool, "([a-z0-9_]+)"\)""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet()

        val constantSoundNames = soundNamesInConstantList(source, "BADGE_SOUND_NAMES") +
            soundNamesInConstantList(source, "PR_SOUND_NAMES")

        val repCountNames = (1..25).map { "rep_%02d".format(it) }
        val expectedNames = loadSoundNames + constantSoundNames + repCountNames
        val missing = expectedNames.sorted().filterNot { it in availableRawNames }

        assertTrue(
            missing.isEmpty(),
            "Missing raw sound resources referenced by HapticFeedbackEffect: $missing",
        )
    }

    private fun soundNamesInConstantList(source: String, listName: String): Set<String> {
        val start = source.indexOf("private val $listName = listOf(")
        if (start < 0) return emptySet()

        val lineEnd = source.indexOf('\n', start)
        val singleLine = lineEnd > start && source.substring(start, lineEnd).contains(")")
        val end = if (singleLine) lineEnd else source.indexOf("\n)", start)
        if (end < 0) return emptySet()

        return Regex("\"([a-z0-9_]+)\"")
            .findAll(source.substring(start, end))
            .map { it.groupValues[1] }
            .toSet()
    }
}
