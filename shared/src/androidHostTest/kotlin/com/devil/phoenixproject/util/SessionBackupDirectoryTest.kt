package com.devil.phoenixproject.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionBackupDirectoryTest {

    private val cacheDir = File("/data/user/0/app/cache")
    private val filesDir = File("/data/user/0/app/files")
    private val externalDocs = File("/storage/emulated/0/Android/data/app/files/Documents")

    @Test
    fun api28_usesAppSpecificDocumentsDir_notPublicDownloads() {
        val dir = sessionBackupDirectory(28, cacheDir, filesDir) { externalDocs }
        assertEquals(File(externalDocs, "PhoenixBackups"), dir)
    }

    @Test
    fun api28_externalStorageUnavailable_fallsBackToInternalFilesDir() {
        val dir = sessionBackupDirectory(28, cacheDir, filesDir) { null }
        assertEquals(File(filesDir, "PhoenixBackups"), dir)
    }

    @Test
    fun api29and30_useCacheStagingDir_forMediaStoreWrite() {
        // 29 is the boundary: write/list/prune switch to MediaStore at >= Q.
        for (sdk in listOf(29, 30)) {
            var externalQueried = false
            val dir = sessionBackupDirectory(sdk, cacheDir, filesDir) {
                externalQueried = true
                externalDocs
            }
            assertEquals(File(cacheDir, "PhoenixBackups"), dir, "sdk $sdk")
            assertFalse(externalQueried, "sdk $sdk")
        }
    }

    @Test
    fun settingsCopy_api28_pointsAtAppStorage_andHidesOpenFolder() {
        assertTrue(autoBackupLocationNoteFor(28)!!.contains("Documents/PhoenixBackups"))
        assertTrue(defaultBackupLocationLabelFor(28).contains("Documents/PhoenixBackups"))
        assertFalse(canOpenBackupFolderFor(28))
    }

    @Test
    fun settingsCopy_api29_pointsAtDownloads_andShowsOpenFolder() {
        assertNull(autoBackupLocationNoteFor(29))
        assertEquals("Downloads/PhoenixBackups", defaultBackupLocationLabelFor(29))
        assertTrue(canOpenBackupFolderFor(29))
    }

    @Test
    fun preQFullExport_usesSameAppSpecificDocumentsDirAsSessionBackup() {
        val exportDir = preQFullExportDirectory(filesDir) { externalDocs }
        assertEquals(sessionBackupDirectory(28, cacheDir, filesDir) { externalDocs }, exportDir)
        assertEquals(File(externalDocs, "PhoenixBackups"), exportDir)
    }

    @Test
    fun preQFullExport_externalStorageUnavailable_fallsBackToInternalFilesDir() {
        val exportDir = preQFullExportDirectory(filesDir) { null }
        assertEquals(sessionBackupDirectory(28, cacheDir, filesDir) { null }, exportDir)
        assertEquals(File(filesDir, "PhoenixBackups"), exportDir)
    }

    @Test
    fun copyFullExport_writesIntoAppSpecificDocuments_notPublicDownloads() {
        val root = tempRoot()
        val internalFiles = File(root, "files")
        val documents = File(root, "Android/data/app/files/Documents")
        val source = File(root, "cache/phoenix_backup_test.json")
        source.parentFile!!.mkdirs()
        source.writeText("""{"version":1}""")
        try {
            val dest = copyFullExportToPreQDocuments(source, internalFiles) { documents }
            assertEquals(File(documents, "PhoenixBackups/phoenix_backup_test.json"), dest)
            assertEquals("""{"version":1}""", dest.readText())
            assertTrue(source.exists(), "caller deletes the cache file")
            assertFalse(dest.path.contains("${File.separator}Download${File.separator}"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun copyFullExport_externalStorageUnavailable_writesInternalFilesDir() {
        val root = tempRoot()
        val internalFiles = File(root, "files")
        val source = File(root, "cache/phoenix_backup_test.json")
        source.parentFile!!.mkdirs()
        source.writeText("payload")
        try {
            val dest = copyFullExportToPreQDocuments(source, internalFiles) { null }
            assertEquals(File(internalFiles, "PhoenixBackups/phoenix_backup_test.json"), dest)
            assertEquals("payload", dest.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun copyFullExport_overwritesExistingFile() {
        val root = tempRoot()
        val internalFiles = File(root, "files")
        val documents = File(root, "documents")
        val source = File(root, "cache/phoenix_backup_test.json")
        source.parentFile!!.mkdirs()
        source.writeText("new")
        val existing = File(documents, "PhoenixBackups/phoenix_backup_test.json")
        existing.parentFile!!.mkdirs()
        existing.writeText("old")
        try {
            val dest = copyFullExportToPreQDocuments(source, internalFiles) { documents }
            assertEquals(existing, dest)
            assertEquals("new", dest.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun tempRoot(): File =
        File(System.getProperty("java.io.tmpdir"), "phoenix-export-preq-${System.nanoTime()}")
}
