package com.devil.phoenixproject.data.preferences

import com.russhwolf.settings.Settings

/**
 * Profiles the user permanently deleted while their data still has to reach the portal
 * (PR 20 / KD-12). The profile row stays in the database, hidden from the UI, until its
 * own push lands the tombstones; only then is the row removed and the id dropped here.
 *
 * Plain (non-secure) settings: the ids are not credentials. The flag is written after
 * the deletion transaction commits, so a crash in between leaves a visible, empty
 * profile the user can delete again, never a hidden profile that still holds live data.
 */
interface PendingProfileDeletionStore {
    fun read(): Set<String>
    fun add(profileId: String)
    fun remove(profileId: String)
}

class SettingsPendingProfileDeletionStore(
    private val settings: Settings,
) : PendingProfileDeletionStore {
    override fun read(): Set<String> = settings.getStringOrNull(KEY)
        ?.split(SEPARATOR)
        ?.filterTo(linkedSetOf()) { it.isNotBlank() }
        .orEmpty()

    override fun add(profileId: String) = write(read() + profileId)

    override fun remove(profileId: String) = write(read() - profileId)

    private fun write(ids: Set<String>) {
        if (ids.isEmpty()) {
            settings.remove(KEY)
        } else {
            settings.putString(KEY, ids.joinToString(SEPARATOR))
        }
    }

    private companion object {
        const val KEY = "pending_profile_deletion_ids"
        const val SEPARATOR = ","
    }
}

class InMemoryPendingProfileDeletionStore : PendingProfileDeletionStore {
    private val ids = linkedSetOf<String>()

    override fun read(): Set<String> = ids.toSet()
    override fun add(profileId: String) {
        ids += profileId
    }
    override fun remove(profileId: String) {
        ids -= profileId
    }
}
