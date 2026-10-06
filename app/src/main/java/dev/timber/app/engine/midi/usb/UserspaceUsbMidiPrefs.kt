package dev.timber.app.engine.midi.usb

import android.content.Context

object UserspaceUsbMidiPrefs {
    private const val PREFS = "timber_midi"
    private const val KEY_MODE = "userspace_usb_midi_mode"

    /** Spike default: Auto so MODX silence can trip Path B without a settings hunt. */
    fun mode(context: Context): UserspaceUsbMidiMode {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_MODE, UserspaceUsbMidiMode.Auto.toStorage())
        return UserspaceUsbMidiMode.fromStorage(raw)
    }

    fun setMode(context: Context, mode: UserspaceUsbMidiMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.toStorage())
            .apply()
    }

    fun cycle(context: Context): UserspaceUsbMidiMode {
        val next = when (mode(context)) {
            UserspaceUsbMidiMode.Off -> UserspaceUsbMidiMode.Auto
            UserspaceUsbMidiMode.Auto -> UserspaceUsbMidiMode.On
            UserspaceUsbMidiMode.On -> UserspaceUsbMidiMode.Off
        }
        setMode(context, next)
        return next
    }
}
