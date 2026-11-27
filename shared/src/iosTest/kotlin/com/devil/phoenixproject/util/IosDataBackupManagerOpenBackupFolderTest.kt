@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.util

import com.devil.phoenixproject.data.preferences.InMemoryPendingProfileDeletionStore
import com.devil.phoenixproject.data.sync.PortalTokenStorage
import com.devil.phoenixproject.data.repository.SqlDelightProfilePreferencesRepository
import com.devil.phoenixproject.testutil.FakeBackupDestinationResolver
import com.devil.phoenixproject.testutil.FakePreferencesManager
import com.devil.phoenixproject.testutil.FakeUserProfileRepository
import com.devil.phoenixproject.testutil.createTestDatabase
import com.devil.phoenixproject.testutil.readProjectFile
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

/**
 * Issue #1423 regression tests for [IosDataBackupManager.openBackupFolder].
 *
 * Tapping "Open Backup Folder" used to present a share sheet over the backup
 * directory URL, which always fails for a folder. The fix hands the folder to
 * the Files app via a `shareddocuments://` URL and falls back to a folder
 * document-picker browse — never a share sheet.
 *
 * A recorder [IosFolderOpener] seam observes every system side effect; the
 * URL building stays production code ([filesHandoffUrl]) so assertions see the
 * same percent-encoded URLs the system would receive. The resolver is the
 * shared [FakeBackupDestinationResolver].
 */
class IosDataBackupManagerOpenBackupFolderTest {

    /** Records handoff/browse calls and replays queued handoff results. */
    private class RecordingFolderOpener : IosFolderOpener() {
        val handoffRequests = mutableListOf<NSURL>()
        val browseRequests = mutableListOf<NSURL>()
        val handoffResults = ArrayDeque<Boolean>()

        override fun openFolderInFiles(handoffUrl: NSURL, onResult: (Boolean) -> Unit) {
            handoffRequests.add(handoffUrl)
            onResult(handoffResults.removeFirstOrNull() ?: true)
        }

        override fun presentFolderBrowse(directoryUrl: NSURL) {
            browseRequests.add(directoryUrl)
        }
    }

    private val resolver = FakeBackupDestinationResolver()
    private val preferencesManager = FakePreferencesManager()

    /** Mirrors getSessionBackupDirectory(): <app Documents>/PhoenixBackups. */
    private val defaultBackupDir: String = run {
        val paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        "${paths.firstOrNull() as? String ?: ""}/PhoenixBackups"
    }

    private fun buildManager(opener: RecordingFolderOpener): IosDataBackupManager {
        val database = createTestDatabase()
        return IosDataBackupManager(
            database = database,
            preferencesManager = preferencesManager,
            destinationResolver = resolver,
            profilePreferencesRepository = SqlDelightProfilePreferencesRepository(database),
            userProfileRepository = FakeUserProfileRepository(),
            portalTokenStorage = PortalTokenStorage(MapSettings()),
            pendingProfileDeletionStore = InMemoryPendingProfileDeletionStore(),
            folderOpener = opener,
            // Unconfined runs the bookmark resolution inline so each test's
            // assertions observe the completed fallback chain deterministically.
            uiScope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private fun setCustomDestination() {
        preferencesManager.setPreferences(
            preferencesManager.preferencesFlow.value.copy(
                backupDestination = BackupDestination.Custom(
                    uri = "file:///backups/My%20Backups",
                    displayName = "My Backups",
                    bookmarkData = "ZmFrZS1ib29rbWFyaw==",
                ),
            ),
        )
    }

    // ---- AC1: default destination -> one shareddocuments handoff, no share sheet ----

    @Test
    fun defaultDestinationOpensDefaultFolderHandoffWithoutShareSheet() {
        val opener = RecordingFolderOpener()
        val manager = buildManager(opener)

        manager.openBackupFolder()

        val expectedDir = defaultBackupDir
        assertEquals(1, opener.handoffRequests.size, "exactly one Files handoff")
        val url = opener.handoffRequests.single()
        assertEquals("shareddocuments", url.scheme, "the handoff targets the Files app")
        assertEquals(expectedDir, url.path, "the handoff targets the default backup folder")
        assertTrue(opener.browseRequests.isEmpty(), "no browse fallback when the handoff is accepted")
        assertTrue(resolver.resolvedFolderPaths.isEmpty(), "the default destination needs no bookmark")
    }

    // ---- AC2: accessible custom destination -> resolved path, percent-encoded ----

    @Test
    fun customDestinationHandsOffTheResolvedFolderPathWithSpacesEncoded() {
        val opener = RecordingFolderOpener()
        val manager = buildManager(opener)
        setCustomDestination()
        resolver.resolveFolderPathResult = "/Users/tester/Library/Mobile Documents/My Backups"

        manager.openBackupFolder()

        assertEquals(1, opener.handoffRequests.size, "exactly one Files handoff")
        val url = opener.handoffRequests.single()
        assertEquals("shareddocuments", url.scheme)
        val absolute = assertNotNull(url.absoluteString)
        assertTrue(absolute.contains("My%20Backups"), "the space is percent-encoded: $absolute")
        assertTrue(absolute.contains("Mobile%20Documents"), "every space is percent-encoded: $absolute")
        assertFalse(absolute.contains(" "), "the handoff URL has no raw spaces: $absolute")
        assertEquals("/Users/tester/Library/Mobile Documents/My Backups", url.path, "the path decodes back")
        assertTrue(opener.browseRequests.isEmpty())
    }

    @Test
    fun filesHandoffUrlPercentEncodesFolderPathSegments() {
        val url = assertNotNull(filesHandoffUrl("/tmp/My Backups/Sub Folder"))
        assertEquals("shareddocuments", url.scheme)
        val absolute = assertNotNull(url.absoluteString)
        assertTrue(absolute.contains("My%20Backups"), "space in a segment is encoded: $absolute")
        assertTrue(absolute.contains("Sub%20Folder"), "space in a later segment is encoded: $absolute")
        assertFalse(absolute.contains(" "), "no raw spaces survive NSURLComponents: $absolute")
        assertEquals("/tmp/My Backups/Sub Folder", url.path, "path round-trips through the URL")
    }

    // ---- AC3: unresolvable custom bookmark or failed custom handoff -> default handoff ----

    @Test
    fun unresolvableCustomBookmarkFallsBackToDefaultFolderHandoff() {
        val opener = RecordingFolderOpener()
        val manager = buildManager(opener)
        setCustomDestination()
        resolver.resolveFolderPathResult = null

        manager.openBackupFolder()

        val expectedDir = defaultBackupDir
        assertEquals(1, opener.handoffRequests.size, "the default folder is handed off once")
        assertEquals(expectedDir, opener.handoffRequests.single().path)
        assertTrue(opener.browseRequests.isEmpty())
        assertEquals(1, resolver.resolvedFolderPaths.size, "the bookmark was consulted")
    }

    @Test
    fun rejectedCustomHandoffFallsBackToDefaultFolderHandoff() {
        val opener = RecordingFolderOpener()
        val manager = buildManager(opener)
        setCustomDestination()
        resolver.resolveFolderPathResult = "/tmp/My Backups"
        opener.handoffResults.add(false)
        opener.handoffResults.add(true)

        manager.openBackupFolder()

        assertEquals(2, opener.handoffRequests.size, "custom handoff then default handoff")
        assertEquals("/tmp/My Backups", opener.handoffRequests[0].path, "the custom folder is tried first")
        val expectedDir = defaultBackupDir
        assertEquals(expectedDir, opener.handoffRequests[1].path, "the default folder is the fallback")
        assertTrue(opener.browseRequests.isEmpty(), "no browse when the default handoff is accepted")
    }

    // ---- AC4: all handoffs fail -> document-picker browse of the target, never a share sheet ----

    @Test
    fun rejectedDefaultHandoffPresentsFolderBrowseOfDefaultFolder() {
        val opener = RecordingFolderOpener()
        val manager = buildManager(opener)
        opener.handoffResults.add(false)

        manager.openBackupFolder()

        val expectedDir = defaultBackupDir
        assertEquals(1, opener.handoffRequests.size)
        assertEquals(1, opener.browseRequests.size, "the browse fallback is presented")
        assertEquals(expectedDir, opener.browseRequests.single().path, "browse opens the target folder")
        assertTrue(opener.browseRequests.single().fileURL, "browse opens a filesystem folder")
    }

    @Test
    fun allHandoffsFailedForCustomDestinationBrowseTheResolvedTarget() {
        val opener = RecordingFolderOpener()
        val manager = buildManager(opener)
        setCustomDestination()
        resolver.resolveFolderPathResult = "/tmp/My Backups"
        opener.handoffResults.add(false)
        opener.handoffResults.add(false)

        manager.openBackupFolder()

        assertEquals(2, opener.handoffRequests.size, "custom then default handoff were both attempted")
        assertEquals(1, opener.browseRequests.size, "the browse fallback is presented")
        assertEquals("/tmp/My Backups", opener.browseRequests.single().path, "browse opens the active target")
    }

    // ---- Regression guard: presentShareSheet(dirURL) must never come back ----

    @Test
    fun openBackupFolderSourceNeverUsesAShareSheet() {
        val source = requireNotNull(
            readProjectFile("src/iosMain/kotlin/com/devil/phoenixproject/util/DataBackupManager.ios.kt"),
        ) { "DataBackupManager.ios.kt not found" }
        val body = functionBody(source, "override fun openBackupFolder")
        assertFalse(body.contains("presentShareSheet"), "the share sheet is never the folder opener (issue #1423)")
        assertFalse(body.contains("UIActivityViewController"), "no activity view controller for folders")
        assertTrue(body.contains("handoffOrBrowse"), "the fixed handoff fallback chain drives the button")
    }

    @Test
    fun folderOpenerSourceUsesFilesHandoffAndPickerBrowseOnly() {
        val source = requireNotNull(
            readProjectFile("src/iosMain/kotlin/com/devil/phoenixproject/util/IosFolderOpener.kt"),
        ) { "IosFolderOpener.kt not found" }
        assertTrue(source.contains("NSURLComponents"), "the handoff URL is built via NSURLComponents")
        assertTrue(source.contains("\"shareddocuments\""), "the handoff targets the Files app")
        assertFalse(source.contains("presentShareSheet"), "the opener never presents a share sheet")
        assertTrue(source.contains("UTTypeFolder"), "the browse fallback opens folders")
        assertTrue(source.contains("presentDocumentPicker"), "the browse fallback uses the shared presenter")
        assertTrue(source.contains("directoryURL"), "the browse fallback starts at the target folder")
    }

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
