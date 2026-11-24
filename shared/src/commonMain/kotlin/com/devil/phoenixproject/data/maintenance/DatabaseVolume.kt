package com.devil.phoenixproject.data.maintenance

/**
 * Free bytes on the volume holding the live database (issue #1425). Null means the platform
 * cannot report free space, which makes VACUUM a typed skip (tvOS: no Settings cleanup entry
 * and no backups, so no VACUUM either).
 */
expect fun availableDatabaseVolumeBytes(): Long?
