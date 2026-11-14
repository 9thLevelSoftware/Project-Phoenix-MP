package com.devil.phoenixproject.presentation

import com.devil.phoenixproject.testutil.readProjectFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Source-level drift guards for issue #1358 Phase 1 (custom-exercise photos).
 *
 * These are CONTRACT guards only (SD14): behavioral correctness is proven by
 * the real-file/real-DB tests in `ExerciseMediaStoreTest` and
 * `SqlDelightExerciseRepositoryTest`.
 */
class ExerciseMediaWiringTest {

    // T1/T2: ExerciseDemoImage stays byte-contract-identical — List<String> urls,
    // no scheme parsing, unchanged Coil .data(url) path.
    @Test
    fun demoImageKeepsStringUrlListWithoutSchemeParsing() {
        val src = readProjectFile(
            "src/phoneMain/kotlin/com/devil/phoenixproject/presentation/components/ExerciseDemoImage.kt",
        )
        assertTrue(src != null, "ExerciseDemoImage.kt must exist")
        assertTrue(
            src!!.contains("imageUrls: List<String>"),
            "ExerciseDemoImage must keep its List<String> url contract (SD9: renderer byte-unchanged).",
        )
        assertTrue(
            src.contains(".data(url)"),
            "ExerciseDemoImage must keep loading through Coil .data(url) so local file paths render unchanged.",
        )
        assertFalse(
            src.contains("Uri.parse") || src.contains("startsWith(\"http") || src.contains("java.net.URL"),
            "ExerciseDemoImage must not gain scheme parsing (T1).",
        )
    }

    // T3: widened onSave (Exercise, ExerciseMediaDelta) present at BOTH entry
    // points and routed through the shared save coordinator (SD5).
    @Test
    fun widenedOnSaveIsWiredAtBothDialogEntryPoints() {
        val picker = readProjectFile(
            "src/phoneMain/kotlin/com/devil/phoenixproject/presentation/components/ExercisePicker.kt",
        )
        val single = readProjectFile(
            "src/phoneMain/kotlin/com/devil/phoenixproject/presentation/screen/SingleExerciseScreen.kt",
        )
        assertTrue(picker != null && single != null, "Both entry-point sources must exist")
        for ((name, src) in listOf("ExercisePicker" to picker!!, "SingleExerciseScreen" to single!!)) {
            assertTrue(
                src.contains("onSave = { exercise, _delta ->"),
                "$name must use the widened (Exercise, ExerciseMediaDelta) onSave — cannot silently revert (T3).",
            )
            assertTrue(
                src.contains("ExerciseMediaSaveCoordinator("),
                "$name must construct the shared media save coordinator (SD5).",
            )
            assertTrue(
                src.contains("mediaCoordinator.discardAll()"),
                "$name must discard only session-owned drafts on dismiss (SD2).",
            )
        }
        // Shared tail lives in the coordinator, not duplicated at call sites.
        val coordinator = readProjectFile(
            "src/phoneMain/kotlin/com/devil/phoenixproject/presentation/components/ExerciseMediaSaveCoordinator.kt",
        )
        assertTrue(coordinator != null, "ExerciseMediaSaveCoordinator.kt must exist")
        assertTrue(
            coordinator!!.contains("suspend fun saveWithMedia(") &&
                coordinator.contains("suspend fun deleteExerciseWithMedia("),
            "One shared commit/rollback tail must own save AND delete for both entry points (SD5).",
        )
    }

    // T4: the media attach path emits no sync payload and writes no backup entry.
    @Test
    fun mediaPathReferencesNoSyncOrBackupBuilder() {
        val files = listOf(
            "src/phoneMain/kotlin/com/devil/phoenixproject/data/media/ExerciseMediaStore.kt",
            "src/phoneMain/kotlin/com/devil/phoenixproject/data/media/ExerciseMediaReconciler.kt",
            "src/phoneMain/kotlin/com/devil/phoenixproject/presentation/components/ExerciseMediaSaveCoordinator.kt",
            "src/phoneMain/kotlin/com/devil/phoenixproject/presentation/components/ExerciseMediaAttachments.kt",
        )
        for (path in files) {
            val src = readProjectFile(path)
            assertTrue(src != null, "$path must exist")
            assertFalse(
                src!!.contains("DataBackupManager") ||
                    src.contains("data.sync") ||
                    src.contains("SyncDto") ||
                    src.contains("BackupModels"),
                "$path must reference no sync/backup builder (T4/AC9): ExerciseImage is in no sync payload.",
            )
        }
    }

    // T5: ExerciseImage table definition unchanged (row id + url + cascade only).
    @Test
    fun exerciseImageTableDefinitionUnchanged() {
        val src = readProjectFile("src/commonMain/sqldelight/com/devil/phoenixproject/database/PhoenixDatabase.sq")
        assertTrue(src != null, "PhoenixDatabase.sq must exist")
        val table = src!!.substringAfter("CREATE TABLE ExerciseImage").substringBefore(";")
        assertTrue(table.contains("url TEXT NOT NULL"), "ExerciseImage.url must stay TEXT NOT NULL (T5).")
        assertTrue(table.contains("ON DELETE CASCADE"), "ExerciseImage FK must keep ON DELETE CASCADE (T5).")
        assertTrue(table.contains("sortOrder INTEGER NOT NULL"), "ExerciseImage.sortOrder must be unchanged (T5).")
    }

    // T6: no schema migration for Phase 1 — no .sqm mentions ExerciseImage at all.
    @Test
    fun noMigrationTouchesExerciseImage() {
        // The largest known migration at the implementation base; Phase 1 must
        // not add a migration on top (compare-by-content, not a frozen number — SD14).
        for (n in 1..70) {
            val src = readProjectFile(
                "src/commonMain/sqldelight/com/devil/phoenixproject/database/migrations/$n.sqm",
            ) ?: continue
            assertFalse(
                src.contains("ExerciseImage"),
                "Migration $n.sqm must not touch ExerciseImage (Phase 1 adds NO migration; T6/AC13).",
            )
        }
    }

    // T7: tvOS stays media-free — no picker, store, or sweep stub (SD8).
    @Test
    fun tvosMainReferencesNoMediaTypes() {
        val src = readProjectFile("src/tvosMain/kotlin/com/devil/phoenixproject/di/PlatformModule.tvos.kt")
        assertTrue(src != null, "PlatformModule.tvos.kt must exist")
        assertFalse(
            src!!.contains("ExerciseMedia") || src.contains("MediaPicker") || src.contains("MediaStore"),
            "tvosMain must reference no media type at all — no expect sweep, no tvOS stub (T7/SD8).",
        )
    }

    // T8: ExerciseRepository stays row-only and file-free (F1/SD4).
    @Test
    fun exerciseRepositoryStaysFileFree() {
        val src = readProjectFile("src/commonMain/kotlin/com/devil/phoenixproject/data/repository/ExerciseRepository.kt")
        assertTrue(src != null, "ExerciseRepository.kt must exist")
        assertFalse(
            src!!.contains("java.io") ||
                src.contains("InputStream") ||
                src.contains("platform.Foundation") ||
                src.contains("File(") ||
                src.contains("filePath"),
            "ExerciseRepository must stay file-free: row-level methods only (T8/F1).",
        )
        assertTrue(
            src.contains("applyMediaDelta(") && src.contains("removeImage(imageId: Long)"),
            "ExerciseRepository must expose the SD3/SD4 row API.",
        )
    }

    // SD1: the concrete multi-select picker contracts cannot silently regress.
    @Test
    fun pickerContractsMatchBindingDecisionSd1() {
        val android = readProjectFile("src/androidMain/kotlin/com/devil/phoenixproject/util/MediaPicker.android.kt")
        assertTrue(android != null, "MediaPicker.android.kt must exist")
        assertTrue(
            android!!.contains("ActivityResultContracts.OpenMultipleDocuments()"),
            "Android picker must stay on OpenMultipleDocuments (SD1).",
        )
        assertFalse(
            android.contains("PickVisualMedia") || android.contains("OpenDocument()"),
            "Android picker must not fall back to single-select contracts (SD1).",
        )

        val ios = readProjectFile("src/iosMain/kotlin/com/devil/phoenixproject/util/MediaPicker.ios.kt")
        assertTrue(ios != null, "MediaPicker.ios.kt must exist")
        assertTrue(
            ios!!.contains("PHPickerViewController"),
            "iOS picker must stay on PHPickerViewController (SD1).",
        )
        assertTrue(
            ios.contains("selectionLimit = 0"),
            "iOS picker must keep unlimited multi-select (SD1).",
        )
    }

    // SD11: the exact 10,000,000-byte decimal cap cannot silently drift.
    @Test
    fun byteCapIsExactlyTenMillionBytes() {
        val src = readProjectFile("src/phoneMain/kotlin/com/devil/phoenixproject/data/media/ExerciseMediaStore.kt")
        assertTrue(src != null, "ExerciseMediaStore.kt must exist")
        assertTrue(
            src!!.contains("MAX_PHOTO_BYTES: Long = 10_000_000L"),
            "The photo byte cap must stay exactly 10,000,000 bytes (SD11 — decimal MB, not 10 MiB).",
        )
        assertTrue(
            src.contains("MAX_PHOTOS_PER_EXERCISE = 6"),
            "The photo count cap must stay 6 (SD11).",
        )
    }
}
