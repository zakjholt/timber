package dev.timber.app.engine.midi

import android.util.Log
import dev.timber.app.BuildConfig

/**
 * Debug-only MIDI diagnostics. Filter with: `adb logcat -s TimberMidi`
 */
object MidiDebugLog {
    const val TAG = "TimberMidi"

    val enabled: Boolean get() = BuildConfig.DEBUG

    fun i(message: String) {
        if (enabled) Log.i(TAG, message)
    }

    fun w(message: String, throwable: Throwable? = null) {
        if (!enabled) return
        if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (!enabled) return
        if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
    }
}
