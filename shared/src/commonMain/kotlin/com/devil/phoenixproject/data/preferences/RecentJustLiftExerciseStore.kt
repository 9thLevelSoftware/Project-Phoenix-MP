package com.devil.phoenixproject.data.preferences

import com.devil.phoenixproject.util.withPlatformLock
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Exercises most recently used to tag Just Lift sets, per profile and local to this device
 * (#850). Newest first, unique, at most [MAX_ENTRIES]. Backs the Recent chip on the Just Lift
 * tagging picker; it is never synced.
 */
interface RecentJustLiftExerciseStore {
    /** Newest first. */
    fun read(profileId: String): List<String>

    /** [read], re-emitted after every change to [profileId]'s list. */
    fun observe(profileId: String): Flow<List<String>>

    /** True once a list exists for [profileId], even an empty one. */
    fun hasEntry(profileId: String): Boolean

    /** Moves [exerciseId] to the front of [profileId]'s list. */
    fun record(profileId: String, exerciseId: String)

    /** Stores [exerciseIds] only when [profileId] has no list yet; never overwrites a recorded one. */
    fun initializeIfAbsent(profileId: String, exerciseIds: List<String>)

    fun delete(profileId: String)

    companion object {
        const val MAX_ENTRIES = 30

        /** Newest-first, unique, trimmed, capped. */
        fun normalize(exerciseIds: List<String>): List<String> = exerciseIds
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .take(MAX_ENTRIES)
    }
}

class SettingsRecentJustLiftExerciseStore(
    private val settings: Settings,
) : RecentJustLiftExerciseStore {
    private val lock = Any()
    private val revision = MutableStateFlow(0L)

    override fun read(profileId: String): List<String> = withPlatformLock(lock) { readLocked(profileId) }

    override fun observe(profileId: String): Flow<List<String>> = revision
        .map { read(profileId) }
        .distinctUntilChanged()

    override fun hasEntry(profileId: String): Boolean = withPlatformLock(lock) { settings.hasKey(key(profileId)) }

    override fun record(profileId: String, exerciseId: String) {
        withPlatformLock(lock) {
            writeLocked(profileId, RecentJustLiftExerciseStore.normalize(listOf(exerciseId) + readLocked(profileId)))
        }
        revision.value += 1
    }

    override fun initializeIfAbsent(profileId: String, exerciseIds: List<String>) {
        val written = withPlatformLock(lock) {
            if (settings.hasKey(key(profileId))) return@withPlatformLock false
            writeLocked(profileId, RecentJustLiftExerciseStore.normalize(exerciseIds))
            true
        }
        if (written) revision.value += 1
    }

    override fun delete(profileId: String) {
        withPlatformLock(lock) { settings.remove(key(profileId)) }
        revision.value += 1
    }

    private fun readLocked(profileId: String): List<String> {
        val stored = settings.getStringOrNull(key(profileId)) ?: return emptyList()
        return runCatching { json.decodeFromString<List<String>>(stored) }
            .map(RecentJustLiftExerciseStore::normalize)
            .getOrDefault(emptyList())
    }

    private fun writeLocked(profileId: String, exerciseIds: List<String>) {
        settings.putString(key(profileId), json.encodeToString(exerciseIds))
    }

    private fun key(profileId: String) = "profile_${profileId}_recent_just_lift_exercise_ids"

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** In-memory [RecentJustLiftExerciseStore] for tests and previews. */
class InMemoryRecentJustLiftExerciseStore : RecentJustLiftExerciseStore {
    private val lists = MutableStateFlow<Map<String, List<String>>>(emptyMap())

    override fun read(profileId: String): List<String> = lists.value[profileId].orEmpty()

    override fun observe(profileId: String): Flow<List<String>> = lists
        .map { it[profileId].orEmpty() }
        .distinctUntilChanged()

    override fun hasEntry(profileId: String): Boolean = profileId in lists.value

    override fun record(profileId: String, exerciseId: String) {
        lists.value = lists.value + (profileId to RecentJustLiftExerciseStore.normalize(listOf(exerciseId) + read(profileId)))
    }

    override fun initializeIfAbsent(profileId: String, exerciseIds: List<String>) {
        if (hasEntry(profileId)) return
        lists.value = lists.value + (profileId to RecentJustLiftExerciseStore.normalize(exerciseIds))
    }

    override fun delete(profileId: String) {
        lists.value = lists.value - profileId
    }
}
