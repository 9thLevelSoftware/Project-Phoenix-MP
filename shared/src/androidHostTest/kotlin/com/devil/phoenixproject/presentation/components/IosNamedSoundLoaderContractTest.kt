package com.devil.phoenixproject.presentation.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Badge and PR celebration clips share one named-file loader, and release
 * stops those pools with the verbal-encouragement pools.
 * The loader is Kotlin/Native and does not run on the host.
 * Rep-count sounds stay on their own index-aligned loader.
 */
class IosNamedSoundLoaderContractTest {
    private val projectRoot: File by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "shared/src/iosMain").exists()) {
            dir = dir.parentFile ?: break
        }
        dir
    }

    private val iosHapticSource: File
        get() = File(
            projectRoot,
            "shared/src/iosMain/kotlin/com/devil/phoenixproject/presentation/components/HapticFeedbackEffect.ios.kt",
        )

    @Test
    fun badgeAndPrSoundsUseNamedLoader() {
        val source = iosHapticSource.readText()
        val helper = functionBody(source, "private fun loadNamedSounds(")
        val parameters = helper.substringBefore('{').substringAfter('(')

        assertTrue(parameters.indexOf("fileNames: List<String>") < parameters.indexOf("destination: MutableList<AVAudioPlayer?>"))
        assertTrue(parameters.indexOf("destination: MutableList<AVAudioPlayer?>") < parameters.indexOf("label: String"))
        assertTrue(helper.contains("fileNames.forEach { fileName ->"))
        assertTrue(helper.contains("loadSound(fileName)?.let { destination.add(it) }"))
        assertTrue(helper.contains("Loaded \${destination.size} \$label"))
        assertTrue(!helper.contains("padStart"), "named clips are not a numbered sequence")

        val badge = functionBody(source, "private fun loadBadgeSounds()")
        assertTrue(badge.contains("""loadNamedSounds(badgeSoundFiles, badgeSoundPlayers, "badge celebration sounds")"""))
        assertEquals(BADGE_SOUND_FILES, quotedNames(badge))
        assertTrue(!badge.contains("badgeSoundFiles.forEach"))

        val pr = functionBody(source, "private fun loadPRSounds()")
        assertTrue(pr.contains("""loadNamedSounds(prSoundFiles, prSoundPlayers, "PR celebration sounds")"""))
        assertEquals(PR_SOUND_FILES, quotedNames(pr))
        assertTrue(!pr.contains("prSoundFiles.forEach"))

        assertEquals(
            3,
            Regex("""loadNamedSounds\(""").findAll(source).count(),
            "one definition plus the badge and PR pools",
        )

        val repCount = functionBody(source, "private fun loadRepCountSounds()")
        assertTrue(!repCount.contains("loadNamedSounds"))
        assertTrue(repCount.contains("for (i in 1..25)"))
        assertTrue(repCount.contains("repCountSoundPlayers.add(player)"))
    }

    @Test
    fun releaseStopsBadgeAndPrWithEncouragementPools() {
        val source = iosHapticSource.readText()
        val release = functionBody(source, "fun release()")
        val listStart = release.indexOf("listOf(")
        val listEnd = release.indexOf(").forEach { pool ->", listStart)
        assertTrue(listStart >= 0 && listEnd > listStart, "release shares one pool stop-and-clear")
        val listed = release.substring(listStart, listEnd)

        assertEquals(
            listOf(
                "badgeSoundPlayers",
                "prSoundPlayers",
                "encouragementNeutralSoundPlayers",
                "encouragementMildSoundPlayers",
                "encouragementStrongSoundPlayers",
                "encouragementDominatrixSoundPlayers",
            ),
            Regex("""\w+SoundPlayers""").findAll(listed).map { it.value }.toList(),
        )
        assertTrue(release.contains("pool.forEach { player ->"))
        assertTrue(release.contains("player?.stop()"))
        assertEquals(1, Regex("""pool\.clear\(\)""").findAll(release).count())
        assertTrue(!release.contains("badgeSoundPlayers.forEach"))
        assertTrue(!release.contains("prSoundPlayers.forEach"))
        assertTrue(!release.contains("badgeSoundPlayers.clear()"))
        assertTrue(!release.contains("prSoundPlayers.clear()"))

        assertTrue(release.contains("repCountSoundPlayers.forEach { player ->"))
        assertTrue(release.contains("repCountSoundPlayers.clear()"))
        assertTrue(release.contains("countdownTickPlayer?.stop()"))
        assertTrue(release.contains("countdownTickPlayer = null"))
        assertTrue(release.contains("players.clear()"))
    }

    private fun quotedNames(body: String): List<String> =
        Regex(""""([^"]+)"""").findAll(body)
            .map { it.groupValues[1] }
            .filter { it != "badge celebration sounds" && it != "PR celebration sounds" }
            .toList()

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "Missing $signature")
        if (signature.endsWith("{")) return source.substring(start, matchingClose(source, start))
        val open = source.indexOf('{', start)
        return source.substring(start, matchingClose(source, open))
    }

    private fun matchingClose(source: String, open: Int): Int {
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
        }
        error("Unclosed block at $open")
    }

    private companion object {
        val BADGE_SOUND_FILES = listOf(
            "absolute_domination",
            "absolute_unit",
            "another_milestone_crushed",
            "beast_mode",
            "insane_performance",
            "maxed_out",
            "new_peak_achieved",
            "new_record_secured",
            "no_ones_stopping_you_now",
            "power",
            "pr",
            "pressure_create_greatness",
            "record",
            "shattered",
            "strenght_unlocked",
            "that_bar_never_stood_a_chance",
            "that_was_a_demolition",
            "that_was_god_mode",
            "that_was_monster_level",
            "that_was_next_tier_strenght",
            "that_was_pure_savagery",
            "the_grind_continues",
            "the_grind_is_real",
            "this_is_what_champions_are_made",
            "unchained_power",
            "unstoppable",
            "victory",
            "you_crushed_that",
            "you_dominated_that_set",
            "you_just_broke_your_limits",
            "you_just_destroyed_that_weight",
            "you_just_levelled_up",
            "you_went_full_throttle",
        )
        val PR_SOUND_FILES = listOf(
            "new_personal_record",
            "new_personal_record_2",
        )
    }
}
