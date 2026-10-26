@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.data.local

import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

/** The app target bundles the existing catalogue as exercises.json. */
internal actual suspend fun readBundledExerciseCatalogue(): ByteArray {
    val resourcePath = NSBundle.mainBundle.resourcePath
        ?: error("The bundled exercise catalogue is missing from the tvOS app.")
    val path = "$resourcePath/exercises.json"
    val contents = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) as? String
        ?: error("Could not read the bundled tvOS exercise catalogue.")
    return contents.encodeToByteArray()
}
