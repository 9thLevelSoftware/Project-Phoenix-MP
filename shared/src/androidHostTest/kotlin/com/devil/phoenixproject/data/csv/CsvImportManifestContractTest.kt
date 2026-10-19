package com.devil.phoenixproject.data.csv

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Manifest contract for routine CSV import (#1242). The three requested filters live on the
 * transparent `CsvImportActivity` trampoline, never on `MainActivity`: a share or open intent
 * arrives in the sending app's task, and on `MainActivity` it would start a second instance
 * there. Only `content://` is registered, because a `file://` URI is read with the app's own
 * file permissions.
 */
class CsvImportManifestContractTest {
    private val manifest = assertNotNull(
        readProjectFile("../androidApp/src/main/AndroidManifest.xml"),
        "androidApp/src/main/AndroidManifest.xml must be readable for this contract test",
    )

    private fun activityBlock(name: String): String {
        val nameIndex = manifest.indexOf("android:name=\"$name\"")
        assertTrue(nameIndex >= 0, "$name must be declared")
        return manifest.substring(manifest.lastIndexOf("<activity", nameIndex), manifest.indexOf("</activity>", nameIndex))
    }

    private val intake = activityBlock(".csv.CsvImportActivity")
    private val main = activityBlock(".MainActivity")

    private fun filters(block: String): List<String> =
        Regex("<intent-filter>(.*?)</intent-filter>", RegexOption.DOT_MATCHES_ALL).findAll(block).map { it.groupValues[1] }.toList()

    @Test
    fun theIntakeIsATransientTrampolineOutsideTheAppTask() {
        assertTrue("android:exported=\"true\"" in intake)
        assertTrue("android:taskAffinity=\"\"" in intake, "never lands inside the app's own task")
        assertTrue("android:excludeFromRecents=\"true\"" in intake)
        assertTrue("Theme.Translucent" in intake)
        assertFalse("android:launchMode" in intake, "a new transient instance per delivery")
    }

    @Test
    fun theSendFilterRegistersTheFourRequestedMimeTypes() {
        val send = filters(intake).single { "android.intent.action.SEND\"" in it }
        for (mime in listOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain")) {
            assertTrue("<data android:mimeType=\"$mime\" />" in send, "SEND must register $mime")
        }
    }

    @Test
    fun theOpenWithFilterRegistersContentOnly() {
        val open = filters(intake).single { "android.intent.action.VIEW\"" in it && "android:scheme=\"content\"" in it }
        assertTrue("android.intent.category.DEFAULT" in open && "android.intent.category.BROWSABLE" in open)
        assertTrue("<data android:mimeType=\"text/csv\" />" in open)
        assertTrue("<data android:mimeType=\"text/comma-separated-values\" />" in open)
        assertFalse("android:scheme=\"file\"" in intake, "file:// would read the app's own files")
    }

    @Test
    fun thePhoenixImportLinkPutsSchemeAndHostOnSeparateDataTags() {
        val link = filters(intake).single { "android:scheme=\"phoenix\"" in it }
        assertTrue("<data android:scheme=\"phoenix\" />" in link)
        assertTrue("<data android:host=\"import\" />" in link)
    }

    @Test
    fun theRegistrationsAvoidWhatLintRejectsAndWhatWidensTheShareSheet() {
        assertFalse("android:autoVerify" in intake, "no autoVerify on a custom scheme (AppLinkUrlError)")
        assertFalse("application/octet-stream" in intake)
        assertFalse("android:mimeType=\"*/*\"" in intake)
        assertFalse("android:pathPattern" in intake || "android:pathSuffix" in intake)
        assertFalse("READ_EXTERNAL_STORAGE" in manifest, "no storage permission")
    }

    @Test
    fun mainActivityKeepsItsLaunchModeAndTakesNoImportIntents() {
        assertFalse("android:launchMode" in main, "MainActivity's task behavior is unchanged")
        assertFalse("android.intent.action.SEND\"" in main)
        assertFalse("android:scheme=\"phoenix\"" in main)
        assertFalse("android:scheme=\"content\"" in main)
    }
}
