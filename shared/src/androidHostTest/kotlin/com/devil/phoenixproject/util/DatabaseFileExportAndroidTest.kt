package com.devil.phoenixproject.util

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class DatabaseFileExportAndroidTest {
    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("dbexport").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun archiveContainsExactBytesAndLeavesSourcesUntouched() {
        val databases = File(dir, "databases").apply { mkdirs() }
        val sources = mapOf(
            "vitruvian.db" to byteArrayOf(1, 2, 3),
            "vitruvian.db-wal" to byteArrayOf(4, 5),
            "phoenix.db" to ByteArray(70_000) { (it % 251).toByte() },
        ).onEach { (name, bytes) -> File(databases, name).writeBytes(bytes) }
        val modified = sources.keys.associateWith { File(databases, it).lastModified() }
        val entries = DatabaseFileExport.entries("databases", databases.path) { File(it).isFile }
        val archive = File(dir, "cache/exports/${DatabaseFileExport.ARCHIVE_NAME}")

        writeDatabaseArchive(entries, archive)

        ZipFile(archive).use { zip ->
            assertEquals(
                sources.keys.map { "databases/$it" }.toSet(),
                zip.entries().asSequence().map { it.name }.toSet(),
            )
            for ((name, bytes) in sources) {
                assertContentEquals(bytes, zip.getInputStream(zip.getEntry("databases/$name")).readBytes())
            }
        }
        for ((name, bytes) in sources) {
            val source = File(databases, name)
            assertContentEquals(bytes, source.readBytes())
            assertEquals(modified.getValue(name), source.lastModified())
        }
        assertEquals(sources.keys, databases.list()!!.toSet())
    }
}
