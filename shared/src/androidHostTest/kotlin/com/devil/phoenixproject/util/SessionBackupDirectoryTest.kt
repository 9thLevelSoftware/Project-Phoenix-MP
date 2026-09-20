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
}
