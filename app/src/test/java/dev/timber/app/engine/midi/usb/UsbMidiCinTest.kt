package dev.timber.app.engine.midi.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbMidiCinTest {
    @Test
    fun decodeNoteOnPacket() {
        // cable 0, CIN 0x9 (Note On), ch1 note 60 vel 100
        val packet = byteArrayOf(0x09, 0x90.toByte(), 60, 100)
        val decoded = UsbMidiCin.decodePacket(packet)!!
        assertEquals(0, decoded.cable)
        assertEquals(0x9, decoded.cin)
        assertEquals(3, decoded.midi.size)
        assertEquals(0x90.toByte(), decoded.midi[0])
        assertEquals(60.toByte(), decoded.midi[1])
        assertEquals(100.toByte(), decoded.midi[2])
    }

    @Test
    fun decodeNoteOffOnCable2() {
        val packet = byteArrayOf(0x28, 0x82.toByte(), 64, 0) // cable 2, CIN 8
        val decoded = UsbMidiCin.decodePacket(packet)!!
        assertEquals(2, decoded.cable)
        assertEquals(0x8, decoded.cin)
        assertEquals(0x82.toByte(), decoded.midi[0])
    }

    @Test
    fun decodeProgramChangeTwoBytes() {
        val packet = byteArrayOf(0x0C, 0xC0.toByte(), 12, 0)
        val decoded = UsbMidiCin.decodePacket(packet)!!
        assertEquals(2, decoded.midi.size)
        assertEquals(0xC0.toByte(), decoded.midi[0])
        assertEquals(12.toByte(), decoded.midi[1])
    }

    @Test
    fun decodeRealtimeSingleByte() {
        val packet = byteArrayOf(0x0F, 0xF8.toByte(), 0, 0) // timing clock
        val decoded = UsbMidiCin.decodePacket(packet)!!
        assertEquals(1, decoded.midi.size)
        assertEquals(0xF8.toByte(), decoded.midi[0])
    }

    @Test
    fun reservedCinReturnsNull() {
        assertNull(UsbMidiCin.decodePacket(byteArrayOf(0x00, 0, 0, 0)))
        assertNull(UsbMidiCin.decodePacket(byteArrayOf(0x01, 0, 0, 0)))
    }

    @Test
    fun decodeBufferMultiplePackets() {
        val buf = byteArrayOf(
            0x09, 0x90.toByte(), 60, 100,
            0x08, 0x80.toByte(), 60, 0,
            0x00, 0, 0, 0, // ignored reserved
        )
        val packets = UsbMidiCin.decodeBuffer(buf)
        assertEquals(2, packets.size)
        assertEquals(0x9, packets[0].cin)
        assertEquals(0x8, packets[1].cin)
    }

    @Test
    fun decodeBufferTruncatedTrailingIgnored() {
        val buf = byteArrayOf(0x09, 0x90.toByte(), 60, 100, 0x08, 0x80.toByte())
        val packets = UsbMidiCin.decodeBuffer(buf, length = 6)
        assertEquals(1, packets.size)
    }

    @Test
    fun midiByteCountTable() {
        assertEquals(0, UsbMidiCin.midiByteCount(0x0))
        assertEquals(3, UsbMidiCin.midiByteCount(0x9))
        assertEquals(2, UsbMidiCin.midiByteCount(0xC))
        assertEquals(1, UsbMidiCin.midiByteCount(0xF))
    }

    @Test
    fun encodeRoundTripNoteOn() {
        val midi = byteArrayOf(0x91.toByte(), 61, 90)
        val packet = UsbMidiCin.encodeShortMessage(cable = 1, midi = midi)!!
        assertEquals(0x19.toByte(), packet[0]) // cable 1, CIN 9
        val decoded = UsbMidiCin.decodePacket(packet)!!
        assertEquals(1, decoded.cable)
        assertTrue(decoded.midi.contentEquals(midi))
    }

    @Test
    fun encodeControlChange() {
        val midi = byteArrayOf(0xB0.toByte(), 7, 127)
        val packet = UsbMidiCin.encodeShortMessage(0, midi)!!
        assertEquals(0x0B.toByte(), packet[0])
        assertEquals(3, UsbMidiCin.decodePacket(packet)!!.midi.size)
    }
}
