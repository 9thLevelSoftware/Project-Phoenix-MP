package com.devil.phoenixproject.data.sync

data class SupabaseConfig(
    val url: String,
    val anonKey: String
) {
    /** GoTrue auth base URL */
    val authUrl: String get() = "$url/auth/v1"
}
