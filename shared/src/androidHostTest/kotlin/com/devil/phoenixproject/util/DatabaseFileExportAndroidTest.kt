package com.devil.phoenixproject.util

import com.devil.phoenixproject.data.local.DATABASE_QUARANTINE_DIRECTORY
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Signoff decision 5 (issue #1384 P3, integration-model §7): the support export carries
 * only verified non-sensitive diagnostics — candidate names and byte sizes. Raw DB/WAL/
 * SHM/journal/quarantine bytes are never archived, including for unsafe-to-open dual
 * databases, because DELETE cannot scrub SQLite pages/WAL.
 */
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
    fun archiveCarriesOnlyDiagnosticsAndLeavesSourcesUntouched() {
        val databases = File(dir, "databases").apply { mkdirs() }
        val sources = mapOf(
            "vitruvian.db" to byteArrayOf(1, 2, 3),
            "vitruvian.db-wal" to byteArrayOf(4, 5),
            "phoenix.db" to ByteArray(70_000) { (it % 251).toByte() },
        ).onEach { (name, bytes) -> File(databases, name).writeBytes(bytes) }
        val modified = sources.keys.associateWith { File(databases, it).lastModified() }

        val archive = File(dir, "cache/exports/${DatabaseFileExport.ARCHIVE_NAME}")
        val built = buildDatabaseArchive(databases, File(dir, "cache"))

        assertEquals(archive, built)
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toList()
            assertEquals(listOf(DatabaseFileExport.DIAGNOSTICS_ENTRY_NAME), entries)
            val report = zip.getInputStream(zip.getEntry(DatabaseFileExport.DIAGNOSTICS_ENTRY_NAME)).readBytes().decodeToString()
            // Names and sizes are verified non-sensitive metadata; contents never appear.
            assertTrue("databases/vitruvian.db" in report)
            assertTrue("databases/vitruvian.db-wal" in report)
            assertTrue("databases/phoenix.db" in report)
            assertTrue("\"rawDatabaseBytesIncluded\":false" in report)
            for ((_, bytes) in sources) {
                assertFalse(report.contains(bytes.decodeToString()), "no source byte may leak")
            }
        }
        for ((name, bytes) in sources) {
            val source = File(databases, name)
            assertContentEquals(bytes, source.readBytes())
            assertEquals(modified.getValue(name), source.lastModified())
        }
        assertEquals(sources.keys, databases.list()!!.toSet())
    }

    @Test
    fun quarantinedBytesAreReportedAsMetadataButNeverArchived() {
        val databases = File(dir, "databases").apply { mkdirs() }
        File(databases, "vitruvian.db").writeBytes(byteArrayOf(1, 2, 3))
        val quarantine = File(databases, DATABASE_QUARANTINE_DIRECTORY).apply { mkdirs() }
        File(quarantine, "1700000000000-DUAL_DATABASES-vitruvian.db").writeBytes(byteArrayOf(9, 9, 9))
        File(quarantine, "1700000000000-DUAL_DATABASES-vitruvian.db-wal").writeBytes(byteArrayOf(8))

        val archive = buildDatabaseArchive(databases, File(dir, "cache"))!!

        ZipFile(archive).use { zip ->
            assertEquals(listOf(DatabaseFileExport.DIAGNOSTICS_ENTRY_NAME), zip.entries().asSequence().map { it.name }.toList())
            val report = zip.getInputStream(zip.getEntry(DatabaseFileExport.DIAGNOSTICS_ENTRY_NAME)).readBytes().decodeToString()
            assertTrue("quarantine/1700000000000-DUAL_DATABASES-vitruvian.db" in report)
            assertTrue("quarantine/1700000000000-DUAL_DATABASES-vitruvian.db-wal" in report)
            assertFalse(report.contains(byteArrayOf(9, 9, 9).decodeToString()))
        }
    }

    @Test
    fun emptyDatabasesDirectoryYieldsNoArchiveAndRemovesAStaleOne() {
        val databases = File(dir, "databases").apply { mkdirs() }
        File(databases, "unrelated.db").writeBytes(byteArrayOf(9))
        val cache = File(dir, "cache")
        val stale = databaseExportArchive(cache).apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(7))
        }

        assertNull(buildDatabaseArchive(databases, cache))
        assertFalse(stale.exists())
    }

    @Test
    fun buildReplacesAPreviousArchive() {
        val databases = File(dir, "databases").apply { mkdirs() }
        File(databases, "phoenix.db").writeBytes(byteArrayOf(1, 2))
        val cache = File(dir, "cache")
        databaseExportArchive(cache).apply {
            parentFile!!.mkdirs()
            writeBytes(ByteArray(10_000))
        }

        val archive = buildDatabaseArchive(databases, cache)!!
        ZipFile(archive).use { zip ->
            assertEquals(listOf(DatabaseFileExport.DIAGNOSTICS_ENTRY_NAME), zip.entries().asSequence().map { it.name }.toList())
        }
    }
}
