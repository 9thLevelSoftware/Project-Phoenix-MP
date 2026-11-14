package com.devil.phoenixproject.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
 * All delegate state is touched only on the main queue (load completions are
 * marshalled there), so no lock is needed.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class MediaPicker {

    @Composable
    actual fun LaunchPhotoPicker(onResult: (MediaPickResult) -> Unit) {
        val scope = rememberCoroutineScope()

        val delegate = remember {
            PhotoPickerDelegate(
                onFinished = { result ->
                    scope.launch(Dispatchers.Main) { onResult(result) }
                },
                log = log,
            )
        }

        LaunchedEffect(Unit) {
            dispatch_async(dispatch_get_main_queue()) {
                presentPhotoPicker(delegate)
            }
        }
    }
}

/**
 * Remember a [MediaPicker] instance for use in Compose.
 */
@Composable
actual fun rememberMediaPicker(): MediaPicker = remember { MediaPicker() }

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

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class PhotoPickerDelegate(
    private val onFinished: (MediaPickResult) -> Unit,
    private val log: Logger,
) : NSObject(), PHPickerViewControllerDelegateProtocol {

    // Touched only on the main queue.
    private var finished = false
    private var remaining = 0
    private val photos = mutableListOf<PickedPhoto>()

    fun finish(result: MediaPickResult) {
        // Exactly one result per picking session; late load callbacks after a
        // failure or dispose mutate nothing (T27).
        if (finished) return
        finished = true
        onFinished(result)
    }

    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)

        val results = didFinishPicking.filterIsInstance<PHPickerResult>()
        if (results.isEmpty()) {
            // User dismissed the picker: clean no-op (SD1).
            finish(MediaPickResult.Cancelled)
            return
        }

        remaining = results.size
        results.forEach { result ->
            val provider: NSItemProvider = result.itemProvider
            provider.loadFileRepresentationForTypeIdentifier("public.image") { url, error ->
                // Transient handle: copy it before the callback returns (D4).
                val tempPath = url?.let { copyToPickerTemp(it) }
                dispatch_async(dispatch_get_main_queue()) {
                    if (finished) return@dispatch_async
                    if (url == null || tempPath == null) {
                        log.w { "PHPicker item load failed: ${error?.localizedDescription}" }
                        finish(MediaPickResult.Error("A picked photo could not be read"))
                        return@dispatch_async
                    }
                    photos.add(
                        PickedPhoto(
                            handle = tempPath,
                            displayName = url.lastPathComponent ?: "photo",
                            byteSize = null, // revalidated at stage time (SD11)
                        ),
                    )
                    remaining -= 1
                    if (remaining == 0) {
                        finish(MediaPickResult.Picked(photos.toList()))
                    }
                }
            }
        }
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
