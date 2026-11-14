package com.devil.phoenixproject.data.media

import co.touchlab.kermit.Logger
import com.devil.phoenixproject.domain.model.generateUUID
import com.devil.phoenixproject.util.PickedPhoto
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSInputStream
import platform.Foundation.NSOutputStream
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSURL
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

private val log = Logger.withTag("ExerciseMediaStore.iOS")

private class MediaStoreIOException(message: String) : Exception(message)

/**
 * iOS [ExerciseMediaStore] (issue #1358, P3).
 *
 * App-private root `NSApplicationSupportDirectory/exercise-media/` (per the
 * `DataBackupManager.ios.kt` app-private pattern). Binary streaming copies via
 * NSInputStream/NSOutputStream — image bytes never pass through a text reader
 * and large inputs are never whole-file buffered (T9).
 *
 * Picked handles are picker-owned temp files (see `MediaPicker.ios.kt`, D4);
 * they are consumed at stage time and deleted afterwards.
 *
 * NSFileManager mutating calls return BOOL rather than throwing — every call is
 * checked explicitly.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosExerciseMediaStore(
    private val mediaRootPath: String,
) : ExerciseMediaStore {

    constructor() : this(
        mediaRootPath = run {
            val base = NSSearchPathForDirectoriesInDomains(
                NSApplicationSupportDirectory,
                NSUserDomainMask,
                true,
            ).firstOrNull() as? String ?: NSTemporaryDirectory()
            "$base/exercise-media"
        },
    )

    private val fileManager = NSFileManager.defaultManager
    private val stagingRootPath: String get() = "$mediaRootPath/staging"
    private val cleanupDebtPath: String get() = "$mediaRootPath/.cleanup-debt"

    override fun exerciseDirPath(exerciseId: String): String = "$mediaRootPath/$exerciseId"

    override suspend fun stage(
        sessionId: String,
        picked: List<PickedPhoto>,
        existingCount: Int,
    ): Result<List<StagedMedia>> {
        // SD11: guardrails BEFORE any copy; whole-batch rejection on violation.
        val remaining = ExerciseMediaLimits.MAX_PHOTOS_PER_EXERCISE - existingCount
        if (picked.size > remaining) {
            consumeHandles(picked)
            return Result.failure(
                MediaGuardrailException(MediaGuardrailError.TooManyPhotos(picked.size, remaining)),
            )
        }
        for (photo in picked) {
            val known = photo.byteSize
            if (known != null && known > ExerciseMediaLimits.MAX_PHOTO_BYTES) {
                consumeHandles(picked)
                return Result.failure(
                    MediaGuardrailException(MediaGuardrailError.PhotoTooLarge(photo.displayName)),
                )
            }
        }

        val sessionDir = "$stagingRootPath/$sessionId"
        fileManager.createDirectoryAtPath(sessionDir, true, null, null)

        val batchFiles = mutableListOf<String>()
        val staged = mutableListOf<StagedMedia>()
        for (photo in picked) {
            val target = "$sessionDir/${generateUUID()}.${extensionFor(photo.displayName)}"
            try {
                batchFiles += target // tracked BEFORE copy so partials are always cleaned
                val input = NSInputStream(uRL = NSURL.fileURLWithPath(photo.handle))
                    ?: return rejectBatch(batchFiles, picked, MediaGuardrailError.Unreadable(photo.displayName))
                input.open()
                try {
                    val header = readHeader(input, 12)
                    if (!isSupportedImageMagic(header)) {
                        return rejectBatch(batchFiles, picked, MediaGuardrailError.UnsupportedType(photo.displayName))
                    }
                    // NSOutputStream has no file-backed constructor in the klib —
                    // posix stdio gives binary-exact writes with stable interop.
                    val output = fopen(target, "wb")
                        ?: return rejectBatch(batchFiles, picked, MediaGuardrailError.Unreadable(photo.displayName))
                    try {
                        var written = 0L
                        writeFully(output, header, header.size)
                        written += header.size
                        val buffer = ByteArray(8 * 1024)
                        while (true) {
                            val read = buffer.usePinned { pinned ->
                                input.read(pinned.addressOf(0).reinterpret(), buffer.size.convert())
                            }.toInt()
                            if (read <= 0) break
                            written += read
                            if (written > ExerciseMediaLimits.MAX_PHOTO_BYTES) {
                                return rejectBatch(batchFiles, picked, MediaGuardrailError.PhotoTooLarge(photo.displayName))
                            }
                            writeFully(output, buffer, read)
                        }
                        staged += StagedMedia(
                            stagedPath = target,
                            displayName = photo.displayName,
                            byteSize = written,
                        )
                    } finally {
                        fclose(output)
                    }
                } finally {
                    input.close()
                }
            } catch (e: MediaGuardrailException) {
                return rejectBatch(batchFiles, picked, e.guardrail)
            } catch (e: Exception) {
                log.w(e) { "Failed to stage ${photo.displayName}" }
                return rejectBatch(batchFiles, picked, MediaGuardrailError.Unreadable(photo.displayName))
            }
        }
        consumeHandles(picked)
        return Result.success(staged)
    }

    private fun rejectBatch(
        batchFiles: List<String>,
        picked: List<PickedPhoto>,
        error: MediaGuardrailError,
    ): Result<List<StagedMedia>> {
        batchFiles.forEach { runCatching { fileManager.removeItemAtPath(it, null) } }
        consumeHandles(picked)
        return Result.failure(MediaGuardrailException(error))
    }

    /** The picked handles are our own transient temp copies — consume them (D4). */
    private fun consumeHandles(picked: List<PickedPhoto>) {
        picked.forEach { photo ->
            if (photo.handle.startsWith("/")) {
                runCatching { fileManager.removeItemAtPath(photo.handle, null) }
            }
        }
    }

    override fun commitAll(
        sessionId: String,
        staged: List<StagedMedia>,
        exerciseId: String,
    ): Result<List<String>> {
        val exerciseDir = exerciseDirPath(exerciseId)
        fileManager.createDirectoryAtPath(exerciseDir, true, null, null)
        val moved = mutableListOf<String>()
        try {
            for (item in staged) {
                val target = "$exerciseDir/${item.stagedPath.substringAfterLast('/')}"
                val ok = fileManager.moveItemAtPath(item.stagedPath, target, null)
                if (!ok) {
                    throw MediaStoreIOException("Could not move ${item.stagedPath} into $exerciseDir")
                }
                moved += target
            }
            return Result.success(moved)
        } catch (e: Exception) {
            log.w(e) { "Commit failed for $exerciseId; restoring this attempt's moves to staging" }
            // SD6: only session-owned additions (files moved this attempt) are
            // touched — restored to their staged paths so the draft stays
            // retryable (T26/T28). Nothing pre-existing is ever mutated.
            moved.forEachIndexed { index, url ->
                runCatching { restoreOrDelete(url, staged[index].stagedPath) }
            }
            return Result.failure(e)
        }
    }

    /**
     * Restore one moved file to its staged path; delete it only when restore
     * fails (never leave an orphan in the exercise dir).
     */
    private fun restoreOrDelete(target: String, stagedPath: String) {
        if (!fileManager.fileExistsAtPath(stagedPath.substringBeforeLast('/'))) {
            fileManager.createDirectoryAtPath(stagedPath.substringBeforeLast('/'), true, null, null)
        }
        val restored = fileManager.moveItemAtPath(target, stagedPath, null)
        if (!restored) {
            runCatching { fileManager.removeItemAtPath(target, null) }
        }
    }

    override fun restoreToStaging(
        staged: List<StagedMedia>,
        committedUrls: List<String>,
    ): Boolean {
        var allRestored = true
        committedUrls.forEach { url ->
            if (!isInsideManagedRoot(url)) {
                log.w { "Refusing to restore outside managed root: $url" }
                allRestored = false
                return@forEach
            }
            val targetName = url.substringAfterLast('/')
            val stagedItem = staged.firstOrNull { it.stagedPath.substringAfterLast('/') == targetName }
            if (stagedItem == null) {
                log.w { "No staged source for $url; deleting session-owned copy" }
                if (!fileManager.removeItemAtPath(url, null)) allRestored = false
                return@forEach
            }
            runCatching { restoreOrDelete(url, stagedItem.stagedPath) }
            if (fileManager.fileExistsAtPath(url)) allRestored = false
        }
        return allRestored
    }

    override fun stagedCopyExists(stagedPath: String): Boolean = fileManager.fileExistsAtPath(stagedPath)

    override fun deleteForExercise(exerciseId: String): Boolean = try {
        val dir = exerciseDirPath(exerciseId)
        if (!fileManager.fileExistsAtPath(dir)) {
            true
        } else {
            fileManager.removeItemAtPath(dir, null)
        }
    } catch (e: Exception) {
        log.w(e) { "deleteForExercise($exerciseId) failed" }
        false
    }

    override fun deleteOne(localUrl: String): Boolean = try {
        if (!fileManager.fileExistsAtPath(localUrl)) {
            true
        } else if (!isInsideManagedRoot(localUrl)) {
            log.w { "Refusing to delete outside managed root: $localUrl" }
            false
        } else {
            fileManager.removeItemAtPath(localUrl, null)
        }
    } catch (e: Exception) {
        log.w(e) { "deleteOne($localUrl) failed" }
        false
    }

    override fun discardStaging(sessionId: String) {
        try {
            val dir = "$stagingRootPath/$sessionId"
            if (fileManager.fileExistsAtPath(dir)) {
                fileManager.removeItemAtPath(dir, null)
            }
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

        val referenced = referencedUrls.mapNotNull { canonicalPathOf(it) }.toSet()
        try {
            // (b) abandoned staging dirs.
            listDir(stagingRootPath).forEach { name ->
                if (name in activeSessions) return@forEach
                fileManager.removeItemAtPath("$stagingRootPath/$name", null)
            }

            // (a) media dirs with no Exercise row; (c) unreferenced files in live dirs.
            listDir(mediaRootPath).forEach { name ->
                if (name == "staging" || name == ".cleanup-debt") return@forEach
                if (name in activeSessions) return@forEach // in-flight commit
                val dirPath = "$mediaRootPath/$name"
                if (name !in liveExerciseIds) {
                    fileManager.removeItemAtPath(dirPath, null)
                    return@forEach
                }
                listDir(dirPath).forEach { fileName ->
                    val filePath = "$dirPath/$fileName"
                    val canonical = canonicalPathOf(filePath) ?: return@forEach
                    if (!canonical.startsWith("$mediaRootPath/")) return@forEach
                    if (canonical !in referenced) {
                        if (!fileManager.removeItemAtPath(filePath, null)) {
                            registerCleanupDebt(filePath)
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
            fileManager.createDirectoryAtPath(mediaRootPath, true, null, null)
            val existing = readAllText(cleanupDebtPath) ?: ""
            writeAllText(cleanupDebtPath, existing + localUrl + "\n")
        } catch (e: Exception) {
            log.w(e) { "Could not record cleanup debt for $localUrl" }
        }
    }

    override fun drainCleanupDebt(): Int {
        val pending = readAllText(cleanupDebtPath)?.lines()?.filter { it.isNotBlank() }
            ?: return 0
        val remaining = mutableListOf<String>()
        for (path in pending) {
            if (!deleteOne(path)) remaining += path
        }
        try {
            if (remaining.isEmpty()) {
                fileManager.removeItemAtPath(cleanupDebtPath, null)
            } else {
                writeAllText(cleanupDebtPath, remaining.joinToString("\n", postfix = "\n"))
            }
        } catch (e: Exception) {
            log.w(e) { "Could not rewrite cleanup debt" }
        }
        return remaining.size
    }

    // ---- helpers ----

    private fun listDir(path: String): List<String> = try {
        (fileManager.contentsOfDirectoryAtPath(path, null) as? List<*>)?.filterIsInstance<String>()
            ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun canonicalPathOf(path: String): String? = try {
        NSURL.fileURLWithPath(path).URLByResolvingSymlinksInPath?.path
    } catch (e: Exception) {
        null
    }

    private fun isInsideManagedRoot(path: String): Boolean {
        val canonical = canonicalPathOf(path) ?: return false
        val rootCanonical = canonicalPathOf(mediaRootPath) ?: return false
        return canonical == rootCanonical || canonical.startsWith("$rootCanonical/")
    }

    private fun readHeader(input: NSInputStream, n: Int): ByteArray {
        val header = ByteArray(n)
        var filled = 0
        while (filled < n) {
            val read = header.usePinned { pinned ->
                input.read(pinned.addressOf(filled).reinterpret(), (n - filled).convert())
            }.toInt()
            if (read <= 0) break
            filled += read
        }
        return if (filled == n) header else header.copyOf(filled)
    }

    private fun writeFully(output: CPointer<FILE>, bytes: ByteArray, count: Int) {
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < count) {
                val written = fwrite(pinned.addressOf(offset), 1.convert(), (count - offset).convert(), output)
                    .toInt()
                if (written <= 0) throw MediaStoreIOException("fwrite failed")
                offset += written
            }
        }
    }

    /** UTF-8 text through binary streams — no NSString bridging involved. */
    private fun readAllText(path: String): String? {
        val input = NSInputStream(uRL = NSURL.fileURLWithPath(path)) ?: return null
        input.open()
        try {
            val chunks = mutableListOf<Byte>()
            val buffer = ByteArray(4 * 1024)
            while (true) {
                val read = buffer.usePinned { pinned ->
                    input.read(pinned.addressOf(0).reinterpret(), buffer.size.convert())
                }.toInt()
                if (read <= 0) break
                for (i in 0 until read) chunks += buffer[i]
            }
            return chunks.toByteArray().decodeToString()
        } finally {
            input.close()
        }
    }

    private fun writeAllText(path: String, content: String) {
        val output = fopen(path, "wb")
            ?: throw MediaStoreIOException("Could not open $path for writing")
        try {
            val bytes = content.encodeToByteArray()
            writeFully(output, bytes, bytes.size)
        } finally {
            fclose(output)
        }
    }

    private fun extensionFor(displayName: String): String {
        val ext = displayName.substringAfterLast('.', "").lowercase()
        return if (ext.isNotEmpty() && ext.length <= 5 && ext.all { it in 'a'..'z' || it in '0'..'9' }) {
            ext
        } else {
            "img"
        }
    }
}
