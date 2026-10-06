package dev.timber.app.engine.midi

import android.util.Log
import dev.timber.app.BuildConfig

/**
 * MIDI diagnostics. Filter with: `adb logcat -s TimberMidi`
 * Enabled for debug builds; spike also logs when userspace USB-MIDI is active.
 */
object MidiDebugLog {
    const val TAG = "TimberMidi"

    @Volatile
    var forceEnable: Boolean = false

    val enabled: Boolean get() = BuildConfig.DEBUG || forceEnable

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
