package com.devil.phoenixproject.presentation.components

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Locks the shared iOS sound-pool pick. [playSound] used to index every pool
 * with Random.nextInt; one helper owns that pick. Empty-pool routing stays at
 * the call sites. The helper is Kotlin/Native and does not run on the host.
 *
 * Issue #1357: the pool picks live in selectPlayer() so a refused cue can
 * re-select from a rebuilt pool; playSound() selects through it.
 */
class IosHapticRandomPoolContractTest {

    @Test
    fun randomPoolEntryIsTheOnlyNextIntPick() {
        val source = requireSource(
            "src/iosMain/kotlin/com/devil/phoenixproject/presentation/components/HapticFeedbackEffect.ios.kt",
        )
        val helperAt = source.indexOf("private fun <T> List<T>.randomPoolEntry()")
        val playSoundAt = source.indexOf("fun playSound(event: HapticEvent)")
        assertTrue(helperAt >= 0 && playSoundAt > helperAt, "randomPoolEntry is defined before playSound")

        val helper = source.substring(helperAt, playSoundAt)
        assertTrue(
            helper.contains("this[kotlin.random.Random.nextInt(size)]"),
            "the helper is the uniform pool index",
        )
        assertEquals(1, source.split("Random.nextInt").size - 1, "Random.nextInt stays in the helper only")
    }

    @Test
    fun selectPlayerPicksEveryPoolThroughTheHelper() {
        val source = requireSource(
            "src/iosMain/kotlin/com/devil/phoenixproject/presentation/components/HapticFeedbackEffect.ios.kt",
        )
        val playSound = functionBody(source, "fun playSound(event: HapticEvent)")
        assertTrue(!playSound.contains("Random.nextInt"), "playSound no longer indexes pools itself")
        assertTrue(playSound.contains("selectPlayer(event)"), "playSound picks its player through selectPlayer")

        val picker = functionBody(source, "private fun selectPlayer(event: HapticEvent)")
        assertTrue(!picker.contains("Random.nextInt"), "selectPlayer does not index pools itself")

        val picks = listOf(
            "badgeSoundPlayers.randomPoolEntry()",
            "prSoundPlayers.randomPoolEntry()",
            "encouragementNeutralSoundPlayers.randomPoolEntry()",
            "encouragementDominatrixSoundPlayers.randomPoolEntry()",
            "encouragementMildSoundPlayers.randomPoolEntry()",
            "encouragementStrongSoundPlayers.randomPoolEntry()",
            "combined.randomPoolEntry()",
        )
        for (pick in picks) {
            assertTrue(picker.contains(pick), "selectPlayer picks through $pick")
        }
        assertEquals(
            2,
            picker.split("encouragementNeutralSoundPlayers.randomPoolEntry()").size - 1,
            "vulgar-off and fallback both pick the neutral pool",
        )
        assertEquals(
            8,
            picker.split("randomPoolEntry()").size - 1,
            "every former nextInt pool pick uses the helper",
        )

        assertTrue(picker.contains("event.dominatrixMode -> null"), "empty dominatrix pool stays silent")
        assertTrue(
            picker.contains(
                "event.vulgarTier == VulgarTier.MILD && encouragementMildSoundPlayers.isNotEmpty()",
            ),
            "empty mild pool still falls through",
        )
        assertTrue(
            picker.contains(
                "event.vulgarTier == VulgarTier.STRONG && encouragementStrongSoundPlayers.isNotEmpty()",
            ),
            "empty strong pool still falls through",
        )
        assertTrue(
            picker.contains("encouragementMildSoundPlayers + encouragementStrongSoundPlayers"),
            "mix still draws from both vulgar pools",
        )
        assertTrue(picker.contains("repCountSoundPlayers[index]"), "rep-count sounds stay numbered, not random")
    }

    private fun requireSource(relativePath: String): String =
        readProjectFile(relativePath) ?: fail("Missing $relativePath")

    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "Missing $signature")
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
        }
        fail("Unclosed $signature")
    }
}
