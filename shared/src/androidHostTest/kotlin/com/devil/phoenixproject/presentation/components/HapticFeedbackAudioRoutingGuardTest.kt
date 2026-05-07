package com.devil.phoenixproject.presentation.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Structural guards for Android workout cue playback.
 *
 * Workout cues must use USAGE_MEDIA so they route to STREAM_MUSIC, matching
 * MainActivity's volumeControlStream contract. Fire OS keeps the same usage
 * to work around its SoundPool volume bug via the MediaPlayer fallback.
 */
class HapticFeedbackAudioRoutingGuardTest {

    private val projectRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "shared/src/androidMain").exists()) {
            val parent = dir.parentFile ?: break
            dir = parent
        }
        check(File(dir, "shared/src/androidMain").exists()) {
            "Could not locate project root containing shared/src/androidMain " +
                "starting from ${System.getProperty("user.dir")}. " +
                "Verify the working directory used by the test runner."
        }
        dir
    }

    private val hapticSourceFile: File
        get() {
            val file = File(
                projectRoot,
                "shared/src/androidMain/kotlin/com/devil/phoenixproject/presentation/components/HapticFeedbackEffect.android.kt",
            )
            check(file.isFile) {
                "HapticFeedbackEffect.android.kt not found at ${file.absolutePath}. " +
                    "The audio routing guard cannot run without the source file."
            }
            return file
        }

    private val rawResourceDir: File
        get() {
            val dir = File(projectRoot, "androidApp/src/main/res/raw")
            check(dir.isDirectory) {
                "Expected raw resource directory at ${dir.absolutePath} but it is missing or not a directory."
            }
            return dir
        }

    @Test
    fun `standard Android cue playback uses media usage`() {
        val source = hapticSourceFile.readText()

        assertTrue(
            source.contains("STANDARD_ANDROID_CUE_USAGE = AudioAttributes.USAGE_MEDIA"),
            "Standard Android cues must use USAGE_MEDIA so they route to STREAM_MUSIC " +
                "(matching MainActivity.volumeControlStream).",
        )
        assertTrue(
            source.contains("FIRE_OS_CUE_USAGE = AudioAttributes.USAGE_MEDIA"),
            "Fire OS must keep USAGE_MEDIA to work around its SoundPool volume bug.",
        )
        assertTrue(
            source.contains("buildCueAudioAttributes(STANDARD_ANDROID_CUE_USAGE)"),
            "SoundPool must be built with the standard cue audio attributes.",
        )
        assertFalse(
            source.contains("AudioAttributes.USAGE_GAME"),
            "Workout cues must not be classified as game audio; that was the v0.9.0 sweep regression.",
        )
        assertFalse(
            source.contains("AudioAttributes.USAGE_ASSISTANCE_SONIFICATION"),
            "Workout cues must not use USAGE_ASSISTANCE_SONIFICATION; it routes to STREAM_SYSTEM " +
                "and breaks the volumeControlStream = STREAM_MUSIC contract.",
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
        val rawFiles = rawResourceDir.listFiles()
        check(rawFiles != null) {
            "Failed to list ${rawResourceDir.absolutePath}: listFiles() returned null."
        }
        val availableRawNames = rawFiles.map { it.nameWithoutExtension }.toSet()

        val loadSoundNames = Regex("""loadSoundByName\(context, soundPool, "([a-z0-9_]+)"\)""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toSet()

        val constantSoundNames = soundNamesInConstantList(source, "BADGE_SOUND_NAMES") +
            soundNamesInConstantList(source, "PR_SOUND_NAMES")

        val repCountNames = repCountSoundNames(source)
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

        // Sound names only contain [a-z0-9_], so the next `)` after `start`
        // is reliably the closing paren of `listOf(...)`, regardless of how
        // the list is formatted (single-line, multi-line, indented `)`, etc.).
        val end = source.indexOf(")", start)
        if (end < 0) return emptySet()

        return Regex("\"([a-z0-9_]+)\"")
            .findAll(source.substring(start, end))
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun repCountSoundNames(source: String): Set<String> {
        // Mirror the rep-count range in HapticFeedbackEffect.android.kt so the
        // guard stays in sync if the upper bound changes.
        val match = Regex("""\(1\.\.(\d+)\)\.mapNotNull""").find(source)
        check(match != null) {
            "Could not locate the rep-count range '(1..N).mapNotNull' in HapticFeedbackEffect.android.kt; " +
                "update repCountSoundNames if the loop shape has changed."
        }
        val upper = match.groupValues[1].toInt()
        return (1..upper).map { "rep_%02d".format(it) }.toSet()
    }
}
