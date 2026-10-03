package com.devil.phoenixproject.presentation.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The four verbal-encouragement pools share one numbered loader.
 * The loader is Kotlin/Native and does not run on the host.
 */
class IosEncouragementSoundLoaderContractTest {
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
    fun fourEncouragementPoolsShareOneNumberedLoader() {
        val source = iosHapticSource.readText()
        val helper = functionBody(source, "private fun loadEncouragementSounds(")
        val parameters = helper.substringBefore('{').substringAfter('(')

        assertTrue(parameters.indexOf("prefix: String") < parameters.indexOf("count: Int"))
        assertTrue(parameters.indexOf("count: Int") < parameters.indexOf("destination: MutableList<AVAudioPlayer?>"))
        assertTrue(parameters.indexOf("destination: MutableList<AVAudioPlayer?>") < parameters.indexOf("label: String"))
        assertTrue(helper.contains("for (i in 1..count)"))
        assertTrue(helper.contains("loadSound(\"\${prefix}_\${i.toString().padStart(2, '0')}\")?.let { destination.add(it) }"))
        assertTrue(helper.contains("Loaded \${destination.size} \$label"))

        val init = functionBody(source, "init {")
        assertEquals(
            listOf(
                """loadEncouragementSounds("encouragement", 15, encouragementNeutralSoundPlayers, "encouragement neutral sounds")""",
                """loadEncouragementSounds("vulgar_mild", 12, encouragementMildSoundPlayers, "vulgar mild sounds")""",
                """loadEncouragementSounds("vulgar_strong", 12, encouragementStrongSoundPlayers, "vulgar strong sounds")""",
                """loadEncouragementSounds("dominatrix", 12, encouragementDominatrixSoundPlayers, "dominatrix sounds")""",
            ),
            Regex("""loadEncouragementSounds\("[^"]+", \d+, \w+, "[^"]+"\)""").findAll(init).map { it.value }.toList(),
        )
        assertEquals(
            5,
            Regex("""loadEncouragementSounds\(""").findAll(source).count(),
            "one definition plus the four encouragement pools",
        )

        assertTrue(!source.contains("fun loadEncouragementNeutralSounds"))
        assertTrue(!source.contains("fun loadEncouragementMildSounds"))
        assertTrue(!source.contains("fun loadEncouragementStrongSounds"))
        assertTrue(!source.contains("fun loadEncouragementDominatrixSounds"))

        val repCount = functionBody(source, "private fun loadRepCountSounds()")
        assertTrue(!repCount.contains("loadEncouragementSounds"))
    }

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
}
