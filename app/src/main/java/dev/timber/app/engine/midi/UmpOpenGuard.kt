package dev.timber.app.engine.midi

import android.content.SharedPreferences

/**
 * Survives native crashes during UMP [MidiManager.openDevice].
 * Before attempting UMP open we set a pending flag; if the process dies, the next launch
 * sees the flag and blocks further auto-opens for that device family (MIDI1 fallback).
 */
class UmpOpenGuard(
    private val prefs: SharedPreferences?,
) {
    fun consumePreviousCrash(): String? {
        val pending = prefs?.getString(KEY_PENDING, null) ?: return null
        prefs.edit()
            .putBoolean(blockKey(pending), true)
            .remove(KEY_PENDING)
            .apply()
        return pending
    }

    fun isBlocked(familyKey: String): Boolean =
        prefs?.getBoolean(blockKey(familyKey), false) == true

    fun beginAttempt(familyKey: String) {
        prefs?.edit()?.putString(KEY_PENDING, familyKey)?.apply()
    }

    fun clearAttempt() {
        prefs?.edit()?.remove(KEY_PENDING)?.apply()
    }

    fun clearBlock(familyKey: String) {
        prefs?.edit()?.remove(blockKey(familyKey))?.apply()
    }

    companion object {
        private const val KEY_PENDING = "ump_open_pending_family"
        private fun blockKey(familyKey: String) = "ump_open_blocked:$familyKey"

        const val PREFS_NAME = "timber_midi_ump_guard"
    }
}
