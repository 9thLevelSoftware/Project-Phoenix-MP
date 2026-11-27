package com.devil.phoenixproject.data.maintenance

import android.os.Environment
import android.os.StatFs

/**
 * Free bytes on the internal data volume (the live database lives on the same volume as
 * [Environment.getDataDirectory], so a Context is not required).
 */
actual fun availableDatabaseVolumeBytes(): Long? = runCatching {
    val stat = StatFs(Environment.getDataDirectory().absolutePath)
    stat.availableBlocksLong * stat.blockSizeLong
}.getOrNull()
