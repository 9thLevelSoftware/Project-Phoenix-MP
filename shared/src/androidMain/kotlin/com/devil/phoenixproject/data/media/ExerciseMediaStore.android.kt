package com.devil.phoenixproject.data.media

import android.content.Context
import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.util.PickedPhoto
import java.io.File
import java.io.IOException
import java.io.InputStream

private val log = Logger.withTag("ExerciseMediaStore.Android")

/**
 * Android [ExerciseMediaStore] (issue #1358, P3).
 *
 * App-private root `context.filesDir/exercise-media/` (per the
 * `DataBackupManager.android.kt` app-private pattern). Binary streaming copies
 * only — image bytes never pass through a text-oriented reader (T9).
 *
 * The secondary constructor is the androidHostTest seam: real temp dirs plus an
 * injected handle→stream opener, so behavior tests run on real files without a
 * Context.
 */
class AndroidExerciseMediaStore(
    private val mediaRoot: File,
    private val openPickedStream: (String) -> InputStream?,
) : ExerciseMediaStore {

    constructor(context: Context) : this(
        mediaRoot = File(context.filesDir, "exercise-media"),
        openPickedStream = { handle ->
            try {
                android.net.Uri.parse(handle).let { uri ->
                    context.contentResolver.openInputStream(uri)
                }
            } catch (e: Exception) {
                log.w(e) { "Could not open picked handle $handle" }
                null
            }
        },
    )

    private val stagingRoot: File get() = File(mediaRoot, "staging")
    private val cleanupDebtFile: File get() = File(mediaRoot, ".cleanup-debt")

    override fun exerciseDirPath(exerciseId: String): String =
        File(mediaRoot, exerciseId).absolutePath

    override suspend fun stage(
        sessionId: String,
        picked: List<PickedPhoto>,
        existingCount: Int,
    ): Result<List<StagedMedia>> {
        // SD11: guardrails BEFORE any copy. Any violation rejects the whole new
        // batch; prior accepted media is untouched.
        val remaining = ExerciseMediaLimits.MAX_PHOTOS_PER_EXERCISE - existingCount
        if (picked.size > remaining) {
            return Result.failure(
                MediaGuardrailException(MediaGuardrailError.TooManyPhotos(picked.size, remaining)),
            )
        }
        for (photo in picked) {
            val known = photo.byteSize
            if (known != null && known > ExerciseMediaLimits.MAX_PHOTO_BYTES) {
                return Result.failure(
                    MediaGuardrailException(MediaGuardrailError.PhotoTooLarge(photo.displayName)),
                )
            }
        }

        val sessionDir = File(stagingRoot, sessionId)
        try {
            if (!sessionDir.isDirectory && !sessionDir.mkdirs()) {
                return Result.failure(IOException("Could not create staging dir $sessionDir"))
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val batchFiles = mutableListOf<File>()
        val staged = mutableListOf<StagedMedia>()
        for (photo in picked) {
            val target = File(sessionDir, "${generateUUID()}.${extensionFor(photo.displayName)}")
            try {
                batchFiles += target // tracked BEFORE copy so partials are always cleaned
                val stream = openPickedStream(photo.handle)
                    ?: return rejectBatch(batchFiles, MediaGuardrailError.Unreadable(photo.displayName))
                stream.use { input ->
                    val header = input.readHeader(12)
                    if (!isSupportedImageMagic(header)) {
                        return rejectBatch(
                            batchFiles,
                            MediaGuardrailError.UnsupportedType(photo.displayName),
                        )
                    }
                    // Binary streaming copy; the 10,000,000-byte cap is enforced
                    // DURING the copy for unknown sizes; a partial file is
                    // discarded on exceed (SD11).
                    target.outputStream().use { out ->
                        var written = 0L
                        out.write(header, 0, header.size)
                        written += header.size
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            if (written > ExerciseMediaLimits.MAX_PHOTO_BYTES) {
                                throw MediaGuardrailException(
                                    MediaGuardrailError.PhotoTooLarge(photo.displayName),
                                )
                            }
                            out.write(buffer, 0, read)
                        }
                        staged += StagedMedia(
                            stagedPath = target.absolutePath,
                            displayName = photo.displayName,
                            byteSize = written,
                        )
                    }
                }
            } catch (e: MediaGuardrailException) {
                return rejectBatch(batchFiles, e.guardrail)
            } catch (e: Exception) {
                log.w(e) { "Failed to stage ${photo.displayName}" }
                return rejectBatch(batchFiles, MediaGuardrailError.Unreadable(photo.displayName))
            }
        }
        return Result.success(staged)
    }

    private fun rejectBatch(
        batchFiles: List<File>,
        error: MediaGuardrailError,
    ): Result<List<StagedMedia>> {
        // Whole-batch rejection: every partial/complete copy of THIS batch goes.
        batchFiles.forEach { it.delete() }
        return Result.failure(MediaGuardrailException(error))
    }

    override fun commitAll(
        sessionId: String,
        staged: List<StagedMedia>,
        exerciseId: String,
    ): Result<List<String>> {
        val exerciseDir = File(mediaRoot, exerciseId)
        if (!exerciseDir.isDirectory && !exerciseDir.mkdirs()) {
            return Result.failure(IOException("Could not create media dir $exerciseDir"))
        }
        val moved = mutableListOf<String>()
        try {
            for (item in staged) {
                val source = File(item.stagedPath)
                val target = File(exerciseDir, File(item.stagedPath).name)
                if (!source.renameTo(target)) {
                    // Cross-device fallback: copy + delete, still binary-exact.
                    source.copyTo(target, overwrite = true)
                    source.delete()
                }
                moved += target.absolutePath
            }
            return Result.success(moved)
        } catch (e: Exception) {
            log.w(e) { "Commit failed for $exerciseId; rolling back this attempt's moves" }
            // SD6: remove only session-owned additions (files moved this attempt).
            moved.forEach { runCatching { File(it).delete() } }
            return Result.failure(e)
        }
    }

    override fun deleteForExercise(exerciseId: String): Boolean = try {
        val dir = File(mediaRoot, exerciseId)
        if (!dir.exists()) {
            true
        } else {
            deleteTreeInsideRoot(dir)
        }
    } catch (e: Exception) {
        log.w(e) { "deleteForExercise($exerciseId) failed" }
        false
    }

    override fun deleteOne(localUrl: String): Boolean = try {
        val file = File(localUrl)
        if (!file.exists()) {
            true
        } else if (!isInsideManagedRoot(file)) {
            log.w { "Refusing to delete outside managed root: $localUrl" }
            false
        } else {
            file.delete()
        }
    } catch (e: Exception) {
        log.w(e) { "deleteOne($localUrl) failed" }
        false
    }

    override fun discardStaging(sessionId: String) {
        try {
            val dir = File(stagingRoot, sessionId)
            if (dir.exists()) deleteTreeInsideRoot(dir)
        } catch (e: Exception) {
            log.w(e) { "discardStaging($sessionId) failed" }
        }
    }

    override fun reconcile(
        liveExerciseIds: Set<String>,
        referencedUrls: Set<String>,
        activeSessions: Set<String>,
    ) {
        // SD8: silent, never throws, never blocks startup. SD7: retry debt first.
        try {
            drainCleanupDebt()
        } catch (e: Exception) {
            log.w(e) { "Cleanup-debt drain failed during reconcile" }
        }

        val referencedCanonical = referencedUrls.mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }.toSet()

        try {
            val rootCanonical = mediaRoot.canonicalPath

            // (b) abandoned staging dirs.
            stagingRoot.listFiles()?.forEach { sessionDir ->
                if (!sessionDir.isDirectory) return@forEach
                if (sessionDir.name in activeSessions) return@forEach
                deleteTreeInsideRoot(sessionDir)
            }

            // (a) media dirs with no Exercise row; (c) unreferenced files in live dirs.
            mediaRoot.listFiles()?.forEach { entry ->
                if (!entry.isDirectory || entry.name == "staging") return@forEach
                if (entry.name in activeSessions) return@forEach // in-flight commit
                if (entry.name !in liveExerciseIds) {
                    deleteTreeInsideRoot(entry)
                    return@forEach
                }
                // Live dir: remove files no row references. Never follows
                // symlinks escaping the root (canonical containment per entry).
                entry.listFiles()?.forEach { file ->
                    if (!file.isFile) return@forEach
                    val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return@forEach
                    if (!canonical.startsWith(rootCanonical + File.separator)) return@forEach
                    if (canonical !in referencedCanonical) {
                        if (!file.delete()) {
                            registerCleanupDebt(file.absolutePath)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.w(e) { "reconcile failed" }
        }
    }

    override fun registerCleanupDebt(localUrl: String) {
        try {
            mediaRoot.mkdirs()
            cleanupDebtFile.appendText("$localUrl\n")
        } catch (e: Exception) {
            log.w(e) { "Could not record cleanup debt for $localUrl" }
        }
    }

    override fun drainCleanupDebt(): Int {
        val pending = try {
            if (!cleanupDebtFile.isFile) return 0
            cleanupDebtFile.readLines().filter { it.isNotBlank() }
        } catch (e: Exception) {
            log.w(e) { "Could not read cleanup debt" }
            return 0
        }
        val remaining = mutableListOf<String>()
        for (path in pending) {
            if (!deleteOne(path)) remaining += path
        }
        try {
            if (remaining.isEmpty()) {
                cleanupDebtFile.delete()
            } else {
                cleanupDebtFile.writeText(remaining.joinToString("\n", postfix = "\n"))
            }
        } catch (e: Exception) {
            log.w(e) { "Could not rewrite cleanup debt" }
        }
        return remaining.size
    }

    // ---- helpers ----

    private fun isInsideManagedRoot(file: File): Boolean = try {
        val rootCanonical = mediaRoot.canonicalPath
        val canonical = file.canonicalPath
        canonical == rootCanonical || canonical.startsWith(rootCanonical + File.separator)
    } catch (e: Exception) {
        false
    }

    /**
     * Recursively delete [dir] only while every entry stays inside the managed
     * root (symlink escapes are left untouched). Unlike `deleteRecursively` this
     * never follows a symlinked directory outside the root.
     */
    private fun deleteTreeInsideRoot(dir: File): Boolean {
        if (!isInsideManagedRoot(dir)) return false
        dir.listFiles()?.forEach { entry ->
            val canonical = runCatching { entry.canonicalPath }.getOrNull()
            if (canonical == null || !canonical.startsWith(mediaRoot.canonicalPath.let { "$it${File.separator}" })) {
                return@forEach
            }
            if (entry.isDirectory) {
                deleteTreeInsideRoot(entry)
            } else {
                entry.delete()
            }
        }
        return dir.delete()
    }

    private fun extensionFor(displayName: String): String {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return if (ext.isNotEmpty() && ext.length <= 5 && ext.all { it in 'a'..'z' || it in '0'..'9' }) {
            ext
        } else {
            "img"
        }
    }

    /** Read up to [n] header bytes; shorter input is returned as-is. */
    private fun InputStream.readHeader(n: Int): ByteArray {
        val header = ByteArray(n)
        var filled = 0
        while (filled < n) {
            val read = read(header, filled, n - filled)
            if (read < 0) break
            filled += read
        }
        return if (filled == n) header else header.copyOf(filled)
    }
}
