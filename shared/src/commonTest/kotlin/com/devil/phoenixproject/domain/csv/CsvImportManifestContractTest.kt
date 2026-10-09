package com.devil.phoenixproject.domain.csv

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Manifest source contract for the CSV import registration (#1242): three intent filters and
 * `singleTop` on `.MainActivity`, scheme and host on separate `<data>` tags, and none of the
 * registrations lint rejects (autoVerify on a custom scheme, wildcard/extension matching).
 */
class CsvImportManifestContractTest {
    private val manifest = assertNotNull(
        readProjectFile("../androidApp/src/main/AndroidManifest.xml"),
        "androidApp/src/main/AndroidManifest.xml must be readable for this contract test",
    )

    /** The `.MainActivity` activity block. */
    private val mainActivity = manifest.substring(
        manifest.indexOf("<activity").let { start ->
            manifest.indexOf("android:name=\".MainActivity\"").let { name -> manifest.lastIndexOf("<activity", name) }
        },
        manifest.indexOf("</activity>", manifest.indexOf("android:name=\".MainActivity\"")),
    )

    @Test
    fun mainActivityUsesSingleTopForGracefulReEntry() {
        assertTrue("android:launchMode=\"singleTop\"" in mainActivity, "MainActivity must be singleTop")
        assertTrue("android:launchMode=\"singleTask\"" !in mainActivity, "singleTask was rejected in signoff")
    }

    @Test
    fun theSendFilterRegistersTheFourRequestedMimeTypes() {
        val sendFilter = filterForAction("android.intent.action.SEND")
        for (mime in listOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain")) {
            assertTrue("<data android:mimeType=\"$mime\" />" in sendFilter, "SEND must register $mime")
        }
    }

    @Test
    fun theViewFilterRegistersContentAndFileForCsvMimes() {
        val viewFilter = mainActivity.substring(
            mainActivity.indexOf("android.intent.action.VIEW\""),
            mainActivity.indexOf("android.intent.action.VIEW\"").let { mainActivity.indexOf("</intent-filter>", it) },
        )
        assertTrue("<data android:scheme=\"content\" />" in viewFilter)
        assertTrue("<data android:scheme=\"file\" />" in viewFilter)
        assertTrue("<data android:mimeType=\"text/csv\" />" in viewFilter)
        assertTrue("<data android:mimeType=\"text/comma-separated-values\" />" in viewFilter)
    }

    @Test
    fun thePhoenixDeepLinkPutsSchemeAndHostOnSeparateDataTags() {
        assertTrue("<data android:scheme=\"phoenix\" />" in mainActivity)
        assertTrue("<data android:host=\"import\" />" in mainActivity)
        assertTrue("android:scheme=\"phoenix\"" in mainActivity && "android:host=\"import\"" in mainActivity)
    }

    @Test
    fun theRegistrationsAvoidWhatLintRejects() {
        assertTrue("android:autoVerify" !in mainActivity, "no autoVerify on a custom scheme (AppLinkUrlError)")
        assertTrue("application/octet-stream" !in mainActivity, "no octet-stream widening")
        assertTrue("android:mimeType=\"*/*\"" !in mainActivity, "no wildcard mime")
        assertTrue("android:pathPattern" !in mainActivity && "android:pathSuffix" !in mainActivity, "no extension-only matching")
        assertTrue("READ_EXTERNAL_STORAGE" !in manifest, "no storage permission (scoped storage only)")
    }

    private fun filterForAction(action: String): String {
        val actionIndex = mainActivity.indexOf("<action android:name=\"$action\" />")
        assertTrue(actionIndex >= 0, "MainActivity must register $action")
        val start = mainActivity.lastIndexOf("<intent-filter>", actionIndex)
        val end = mainActivity.indexOf("</intent-filter>", actionIndex)
        return mainActivity.substring(start, end)
    }
}
