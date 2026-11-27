@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import platform.Foundation.NSFileManager
import platform.Foundation.NSUUID

@OptIn(ExperimentalTestApi::class)
class FilePickerCleanupContractTest {

    private val fileManager = NSFileManager.defaultManager

    @Test
    fun exportTempFileHolderDeletesRealTempFilesOnCleanupAndReplacement() {
        val firstPath = assertNotNull(
            writeUtf8TempFile("export_cleanup_1_${NSUUID().UUIDString}.json", "{\"v\":1}"),
        )
        val secondPath = assertNotNull(
            writeUtf8TempFile("export_cleanup_2_${NSUUID().UUIDString}.csv", "a,b\n1,2\n"),
        )
        val holder = ExportTempFileHolder()
        try {
            holder.replaceWith(firstPath)
            assertTrue(fileManager.fileExistsAtPath(firstPath))

            // Replacing with a second export file must delete the first temp file immediately.
            holder.replaceWith(secondPath)
            assertFalse(fileManager.fileExistsAtPath(firstPath), "Superseded temp file must be deleted")
            assertTrue(fileManager.fileExistsAtPath(secondPath))
            assertEquals(secondPath, holder.activeTempPath)

            // Cleanup (completion, cancellation, or disposal) deletes the active temp file.
            holder.cleanup()
            assertFalse(fileManager.fileExistsAtPath(secondPath), "Active temp file must be deleted on cleanup")
            assertNull(holder.activeTempPath)
        } finally {
            fileManager.removeItemAtPath(firstPath, null)
            fileManager.removeItemAtPath(secondPath, null)
        }
    }

    @Test
    fun rememberedPickerCallbackTracksLatestRecomposedClosure() = runComposeUiTest {
        var firstCalls = 0
        var secondCalls = 0
        var callback by mutableStateOf<(String?) -> Unit>({ firstCalls++ })
        lateinit var capturedInvoke: (String?) -> Unit

        setContent {
            val latestCallback by rememberUpdatedState(callback)
            capturedInvoke = remember {
                { value: String? -> latestCallback(value) }
            }
        }
        waitForIdle()

        callback = { secondCalls++ }
        waitForIdle()

        capturedInvoke("/tmp/picked.csv")
        assertEquals(0, firstCalls, "Stale closure must not be invoked after recomposition")
        assertEquals(1, secondCalls, "Latest recomposed closure must be invoked")
    }
}
