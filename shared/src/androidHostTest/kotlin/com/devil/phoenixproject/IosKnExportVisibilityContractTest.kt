package com.devil.phoenixproject

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Swift calls only [com.devil.phoenixproject.di.doInitKoin] and [MainViewController].
 * Every other top-level iosMain declaration is either an expect/actual (visibility is
 * fixed by the shared contract) or must be internal/private so it stays out of the
 * Kotlin/Native framework header. The sources are not compiled on the host.
 */
class IosKnExportVisibilityContractTest {
    @Test
    fun swiftEntryPointsStayPublicAndOtherIosDeclarationsAreHidden() {
        val iosMain = File(projectRoot(), "shared/src/iosMain")
        assertTrue(iosMain.isDirectory, "iosMain sources must be present")

        val declarations = iosMain.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> topLevelDeclarations(file.readText()) }
            .toList()

        val entries = declarations.filter { it.name in SWIFT_ENTRY_POINTS }
        assertEquals(
            SWIFT_ENTRY_POINTS,
            entries.map { it.name }.toSet(),
            "doInitKoin and MainViewController must both stay declared",
        )
        entries.forEach { declaration ->
            assertTrue(
                !declaration.hidden,
                "${declaration.name} is a Swift entry point and must stay public",
            )
        }

        val leaks = declarations.filter { declaration ->
            declaration.name !in SWIFT_ENTRY_POINTS && !declaration.hidden && !declaration.actual
        }
        if (leaks.isNotEmpty()) {
            fail(
                "iosMain declarations that Swift does not call must be internal or private:\n" +
                    leaks.joinToString("\n") { "  ${it.name}: ${it.head}" },
            )
        }
    }

    private fun projectRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        while (!File(dir, "shared/src/iosMain").exists()) {
            dir = dir.parentFile ?: break
        }
        return dir
    }

    private fun topLevelDeclarations(source: String): List<Declaration> {
        val lines = source.lineSequence().map { it.substringBefore("//") }.toList()
        val declarations = mutableListOf<Declaration>()
        var index = 0
        while (index < lines.size) {
            val raw = lines[index]
            if (raw.isEmpty() || raw[0] == ' ' || raw[0] == '\t') {
                index++
                continue
            }
            val stripped = raw.trim()
            if (
                stripped.isEmpty() ||
                stripped.startsWith("package ") ||
                stripped.startsWith("import ") ||
                stripped.startsWith("/*") ||
                stripped.startsWith("*")
            ) {
                index++
                continue
            }

            var buffer = stripped
            var paren = buffer.count { it == '(' } - buffer.count { it == ')' }
            while (paren > 0 && index + 1 < lines.size) {
                index++
                buffer += " " + lines[index].trim()
                paren = buffer.count { it == '(' } - buffer.count { it == ')' }
            }
            if (!containsDeclaration(buffer) && stripped.startsWith("@")) {
                var extra = buffer
                var cursor = index + 1
                while (cursor < lines.size) {
                    val next = lines[cursor]
                    if (next.isNotEmpty() && (next[0] == ' ' || next[0] == '\t')) break
                    val nextStripped = next.trim()
                    if (nextStripped.isEmpty()) {
                        cursor++
                        continue
                    }
                    extra += " " + nextStripped
                    if (containsDeclaration(extra) && extra.count { it == '(' } <= extra.count { it == ')' }) {
                        buffer = extra
                        index = cursor
                        break
                    }
                    if (!nextStripped.startsWith("@") && extra.count { it == '(' } <= extra.count { it == ')' }) {
                        break
                    }
                    cursor++
                }
            }
            declarationOrNull(buffer)?.let(declarations::add)
            index++
        }
        return declarations
    }

    private fun containsDeclaration(text: String): Boolean =
        DECLARATION.containsMatchIn(text.replace(ANNOTATION, " "))

    private fun declarationOrNull(buffer: String): Declaration? {
        val body = buffer.replace(ANNOTATION, " ").replace(Regex("\\s+"), " ").trim()
        val head = body.substringBefore('{').substringBefore('=')
        val match = DECLARATION.find(head) ?: return null
        val name = match.groupValues[2]
        if (name.isEmpty()) return null
        val flags = MODIFIER.findAll(head).map { it.value }.toSet()
        return Declaration(
            name = name,
            head = head.take(160),
            hidden = "internal" in flags || "private" in flags,
            actual = "actual" in flags,
        )
    }

    private data class Declaration(
        val name: String,
        val head: String,
        val hidden: Boolean,
        val actual: Boolean,
    )

    private companion object {
        val SWIFT_ENTRY_POINTS = setOf("doInitKoin", "MainViewController")
        val DECLARATION = Regex("""\b(class|interface|object|fun|typealias|val|var)\s+`?([A-Za-z_][A-Za-z0-9_]*)""")
        val ANNOTATION = Regex("""@[\w.]+(?:\([^()]*\))?""")
        val MODIFIER = Regex("""\b(internal|private|actual)\b""")
    }
}
