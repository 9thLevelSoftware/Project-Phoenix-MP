package com.devil.phoenixproject.qa

import android.content.Context

class ProfileQaFixtureGate(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun enable() {
        check(preferences.edit().putBoolean(KEY_ENABLED, true).commit()) {
            "Could not persist the profile QA fixture gate"
        }
    }

    private companion object {
        const val PREFERENCES_FILE = "profile_qa_fixture_gate"
        const val KEY_ENABLED = "enabled"
    }
}
