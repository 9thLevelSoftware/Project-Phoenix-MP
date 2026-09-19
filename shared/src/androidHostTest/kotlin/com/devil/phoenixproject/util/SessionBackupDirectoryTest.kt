package com.devil.phoenixproject.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun api30_usesCacheStagingDir_forMediaStoreWrite() {
        var externalQueried = false
        val dir = sessionBackupDirectory(30, cacheDir, filesDir) {
            externalQueried = true
            externalDocs
        }
        assertEquals(File(cacheDir, "PhoenixBackups"), dir)
        assertEquals(false, externalQueried)
    }
}
