package com.devil.phoenixproject.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.*
import platform.PhotosUI.*
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * iOS implementation of [MediaPicker] (issue #1358, SD1) using PHPickerViewController.
 *
 * Images filter, unlimited multi-select (`selectionLimit = 0`), `.current` asset
 * representation. Runs entirely out-of-process: no photo-library permission
 * prompt, no camera or video affordance (deployment target 15.0 — SD13).
 *
 * PHPicker's file representations only exist inside their load callback, so
 * each picked item is copied immediately into a picker-owned temp file (D4:
 * transient handles are consumed at pick time). The resulting handle is that
 * temp path; the media store stages it with the byte cap and deletes it.
 *
 * R3: every temp copy is OWNED by [PhotoPickerDelegate] from creation until it
 * is either deleted (batch error, cancel, disposal, late arrival) or its
 * ownership is transferred to the media store with a delivered `Picked`
 * result (consumed at stage time). A cancelled/disposed session keeps none.
 *
 * All delegate state is touched only on the main queue (load completions are
 * marshalled there), so no lock is needed.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class MediaPicker {

    @Composable
    actual fun LaunchPhotoPicker(onResult: (MediaPickResult) -> Unit) {
        // The delegate is remembered across recompositions; read the latest
        // callback so a late result never hits a stale dialog state.
        val currentOnResult by rememberUpdatedState(onResult)

        val delegate = remember {
            PhotoPickerDelegate(
                // R1/R3: deliver synchronously on the main queue — the delegate
                // only reports "finished" AFTER the consumer received the
                // result, so ownership handoff has no gap where a cancelled
                // coroutine could drop a batch with live temp copies.
                onFinished = { result -> currentOnResult(result) },
                log = log,
            )
        }

        LaunchedEffect(Unit) {
            dispatch_async(dispatch_get_main_queue()) {
                presentPhotoPicker(delegate)
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                // R3: leaving composition without a result (dialog dismissed
                // while provider loads run) ends the session — accumulated and
                // late-arriving copies are deleted, nothing is delivered.
                delegate.dispose()
            }
        }
    }
}

/**
 * Remember a [MediaPicker] instance for use in Compose.
 */
@Composable
actual fun rememberMediaPicker(): MediaPicker = remember { MediaPicker() }

/**
 * iOS picker temps are app-owned `NSTemporaryDirectory` copies — delete a copy
 * whose pick never reached staging (idempotent: a handle the store consumed at
 * stage time is already gone).
 */
@OptIn(ExperimentalForeignApi::class)
actual fun releasePickerOwnedHandle(photo: PickedPhoto) {
    if (photo.handle.startsWith("/")) {
        runCatching { NSFileManager.defaultManager.removeItemAtPath(photo.handle, null) }
    }
}

private val log = Logger.withTag("MediaPicker.iOS")

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun presentPhotoPicker(delegate: PhotoPickerDelegate) {
    var presenter: UIViewController = keyWindowRootViewController() ?: run {
        log.e { "Could not get root view controller" }
        delegate.finish(MediaPickResult.Error("No view controller to present the photo picker"))
        return
    }
    while (true) {
        presenter = presenter.presentedViewController ?: break
    }

    val configuration = PHPickerConfiguration()
    configuration.filter = PHPickerFilter.imagesFilter
    configuration.selectionLimit = 0
    configuration.preferredAssetRepresentationMode = PHPickerConfigurationAssetRepresentationModeCurrent

    val picker = PHPickerViewController(configuration = configuration)
    picker.delegate = delegate
    presenter.presentViewController(picker, animated = true, completion = null)
}

/**
 * One picking session's state machine (R3: explicit picker-temp ownership).
 *
 * `internal` so the iosTest failure-injection suite can drive the exact
 * accumulate/finish/dispose transitions with real temp files; the UIKit layer
 * above only marshals load completions onto the main queue and copies the
 * transient files.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class PhotoPickerDelegate(
    private val onFinished: (MediaPickResult) -> Unit,
    private val log: Logger,
    /** Injectable for failure-injection tests; deletes one owned temp copy. */
    private val deleteTemp: (String) -> Unit = { path ->
        NSFileManager.defaultManager.removeItemAtPath(path, null)
        Unit
    },
) : NSObject(), PHPickerViewControllerDelegateProtocol {

    // Touched only on the main queue.
    private var finished = false
    private var remaining = 0
    private val photos = mutableListOf<PickedPhoto>()

    /**
     * R3: every temp copy this session created. Entries leave this list only
     * by deletion (failure/cancel/dispose/late arrival) or by transfer to the
     * media store with a delivered `Picked` result.
     */
    private val ownedTemps = mutableListOf<String>()

    /** Test/diagnostic view of copies still owned by this session. */
    fun ownedTempPaths(): List<String> = ownedTemps.toList()

    /** Start one batch of [count] asynchronous provider loads. */
    fun beginBatch(count: Int) {
        remaining = count
    }

    fun finish(result: MediaPickResult) {
        // Exactly one result per picking session; late load callbacks after a
        // failure or dispose mutate nothing (T27).
        if (finished) return
        finished = true
        when (result) {
            is MediaPickResult.Picked -> {
                // Ownership transfers to the media store, which consumes the
                // handles at stage time (D4: real transient-file consumption).
                ownedTemps.clear()
                onFinished(result)
            }
            else -> {
                // R3: failure/cancel — delete every copy accumulated so far.
                discardOwnedTemps()
                onFinished(result)
            }
        }
    }

    /**
     * R3: session disposal (the picker composable left composition before a
     * terminal result). Nothing is delivered and no temp copy survives —
     * including copies whose provider loads arrive after this call.
     */
    fun dispose() {
        if (finished) return
        finished = true
        discardOwnedTemps()
    }

    /** R3: one provider load finished; [tempPath] is its transient copy, if any. */
    fun onItemLoaded(tempPath: String?, displayName: String?, loadFailed: Boolean) {
        if (finished) {
            // R3: a late-arriving copy after the session ended is deleted
            // immediately — never orphaned in the picker temp namespace.
            tempPath?.let { releaseTemp(it) }
            return
        }
        if (loadFailed || tempPath == null) {
            log.w { "PHPicker item load failed" }
            finish(MediaPickResult.Error("A picked photo could not be read"))
            return
        }
        ownedTemps += tempPath
        photos.add(
            PickedPhoto(
                handle = tempPath,
                displayName = displayName ?: "photo",
                byteSize = null, // revalidated at stage time (SD11)
            ),
        )
        remaining -= 1
        if (remaining == 0) {
            finish(MediaPickResult.Picked(photos.toList()))
        }
    }

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)

        val results = didFinishPicking.filterIsInstance<PHPickerResult>()
        if (results.isEmpty()) {
            // User dismissed the picker: clean no-op (SD1).
            finish(MediaPickResult.Cancelled)
            return
        }

        beginBatch(results.size)
        results.forEach { result ->
            val provider: NSItemProvider = result.itemProvider
            provider.loadFileRepresentationForTypeIdentifier("public.image") { url, error ->
                // Transient handle: copy it before the callback returns (D4).
                val tempPath = url?.let { copyToPickerTemp(it) }
                dispatch_async(dispatch_get_main_queue()) {
                    onItemLoaded(
                        tempPath = tempPath,
                        displayName = url?.lastPathComponent,
                        loadFailed = url == null || tempPath == null,
                    )
                }
            }
        }
    }

    private fun discardOwnedTemps() {
        ownedTemps.toList().forEach { releaseTemp(it) }
        ownedTemps.clear()
        photos.clear()
    }

    private fun releaseTemp(path: String) {
        runCatching { deleteTemp(path) }
            .onFailure { log.w(it) { "Failed to release picker temp $path" } }
    }
}

/**
 * Copy PHPicker's transient file representation into picker-owned temp storage
 * so the handle stays readable after the load callback returns (D4). The media
 * store deletes this copy after staging.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun copyToPickerTemp(url: NSURL): String? = try {
    val fileManager = NSFileManager.defaultManager
    val tempDir = NSTemporaryDirectory()
    val fileName = "picked_${NSUUID().UUIDString}_${url.lastPathComponent ?: "photo"}"
    val destPath = "$tempDir$fileName"
    if (fileManager.fileExistsAtPath(destPath)) {
        fileManager.removeItemAtPath(destPath, null)
    }
    val ok = fileManager.copyItemAtURL(url, NSURL.fileURLWithPath(destPath), null)
    if (ok) destPath else null
} catch (e: Exception) {
    log.w(e) { "Failed to copy PHPicker item to temp" }
    null
}
