package com.devil.phoenixproject.util

import co.touchlab.kermit.Logger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val log = Logger.withTag("BackupDestination")

/**
 * Represents the user's chosen backup destination.
 *
 * - [Default]: Platform default location (Android Downloads/Phoenix Backups, iOS Documents).
 * - [Custom]: A user-picked directory with a persistable URI/bookmark.
 *
 * On iOS, [Custom.bookmarkData] stores a Base64-encoded security-scoped bookmark
 * so the app can regain access across launches without re-prompting.
 */
@Serializable
sealed class BackupDestination {

    @Serializable
    @SerialName("default")
    data object Default : BackupDestination()

    @Serializable
    @SerialName("custom")
    data class Custom(
        /** Platform URI string (content:// on Android, file:// on iOS). */
        val uri: String,
        /** Human-readable folder name for display in settings. */
        val displayName: String,
        /**
         * Base64-encoded iOS security-scoped bookmark data.
         * Null on Android (URI permissions are persisted via ContentResolver).
         */
        val bookmarkData: String? = null,
    ) : BackupDestination()

    /** Convenience check for whether this is a user-chosen custom location. */
    val isCustom: Boolean get() = this is Custom

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * Serialize to JSON string for preferences storage.
         */
        fun BackupDestination.toJson(): String = json.encodeToString(serializer(), this)

        /**
         * Deserialize from JSON string stored in preferences.
         * Returns [Default] on any parse failure -- corrupt data must never crash the app.
         */
        fun fromJson(jsonString: String?): BackupDestination {
            if (jsonString.isNullOrBlank()) return Default
            return try {
                json.decodeFromString(serializer(), jsonString)
            } catch (e: Exception) {
                log.w { "Failed to parse BackupDestination, falling back to Default: ${e.message}" }
                Default
            }
        }
    }
}
