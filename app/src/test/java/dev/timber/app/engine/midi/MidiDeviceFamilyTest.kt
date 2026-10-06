package dev.timber.app.engine.midi

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiDeviceFamilyTest {
    @Test
    fun key_groupsMidi1AndMidi2AlternateSettings() {
        val midi1 = MidiDeviceFamily.key(
            serialNumber = null,
            displayName = "Yamaha Corporation MODX M#577 MIDI 1.0",
            productName = "MODX M",
            deviceId = 16,
        )
        val midi2 = MidiDeviceFamily.key(
            serialNumber = null,
            displayName = "Yamaha Corporation MODX M#577 MIDI 2.0",
            productName = "MODX M",
            deviceId = 15,
        )
        assertEquals(midi1, midi2)
        assertEquals("name:yamaha corporation modx m", midi1)
    }

    @Test
    fun key_prefersUsbThenSerial() {
        assertEquals(
            "usb:1177:1:foo",
            MidiDeviceFamily.key("ABC", "MODX M MIDI 1.0", "MODX M", 1, usbKey = "usb:1177:1:foo"),
        )
        assertEquals(
            "serial:ABC",
            MidiDeviceFamily.key("ABC", "MODX M MIDI 1.0", "MODX M", 1),
        )
    }
}
