package dev.timber.app.engine.midi.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class UserspaceUsbMidiModeTest {
    @Test
    fun storageRoundTrip() {
        for (mode in UserspaceUsbMidiMode.entries) {
            assertEquals(mode, UserspaceUsbMidiMode.fromStorage(mode.toStorage()))
        }
    }

    @Test
    fun unknownDefaultsOff() {
        assertEquals(UserspaceUsbMidiMode.Off, UserspaceUsbMidiMode.fromStorage(null))
        assertEquals(UserspaceUsbMidiMode.Off, UserspaceUsbMidiMode.fromStorage("nope"))
    }

    @Test
    fun labels() {
        assertEquals("HAL", UserspaceUsbMidiMode.Off.label)
        assertEquals("Auto", UserspaceUsbMidiMode.Auto.label)
        assertEquals("Userspace", UserspaceUsbMidiMode.On.label)
    }
}
