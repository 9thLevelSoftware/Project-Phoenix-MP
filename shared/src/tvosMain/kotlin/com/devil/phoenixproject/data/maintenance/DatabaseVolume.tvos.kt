package com.devil.phoenixproject.data.maintenance

/**
 * tvOS reports no free-volume figure: VACUUM becomes a typed `UNSUPPORTED_PLATFORM` skip.
 * There is no Settings cleanup entry and no backup surface on tvOS anyway.
 */
actual fun availableDatabaseVolumeBytes(): Long? = null
