@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.devil.phoenixproject.data.maintenance

import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSHomeDirectory

/** Free bytes on the device volume holding the app home directory (the DB lives there). */
actual fun availableDatabaseVolumeBytes(): Long? = runCatching {
    val attributes = NSFileManager.defaultManager.attributesOfFileSystemForPath(
        path = NSHomeDirectory(),
        error = null,
    )
    (attributes?.get(NSFileSystemFreeSize) as? Number)?.toLong()
}.getOrNull()
