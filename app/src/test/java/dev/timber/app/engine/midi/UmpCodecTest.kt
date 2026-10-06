package dev.timber.app.engine.midi

import dev.timber.app.domain.midi.MidiMessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UmpCodecTest {
    @Test
    fun looksLikeUmp_requiresMultipleOf4() {
        assertFalse(UmpCodec.looksLikeUmp(byteArrayOf(0x20, 0x90.toByte(), 60, 100, 0), 0, 5))
        assertTrue(UmpCodec.looksLikeUmp(byteArrayOf(0x20, 0x90.toByte(), 60, 100), 0, 4))
    }

    @Test
    fun decodeMidi1ChannelVoiceUmp_noteOn() {
        val packet = byteArrayOf(0x20, 0x90.toByte(), 60, 100)
        val decoded = UmpCodec.decodePackets(packet, 0, 4)
        assertEquals(1, decoded.size)
        assertEquals(MidiMessageType.NoteOn, decoded[0].type)
        assertEquals(1, decoded[0].channel)
        assertEquals(60, decoded[0].data1)
        assertEquals(100, decoded[0].data2)
        assertTrue(decoded[0].label.contains("UMP1"))
    }

    @Test
    fun decodeMidi2ChannelVoiceUmp_noteOn() {
        // mt=4 group=0 | status=0x90 | note=60 | attr=0 | vel=0x6400 (~100 in 7-bit)
        val packet = byteArrayOf(
            0x40, 0x90.toByte(), 60, 0,
            0x64, 0x00, 0, 0,
        )
        val decoded = UmpCodec.decodePackets(packet, 0, 8)
        assertEquals(1, decoded.size)
        assertEquals(MidiMessageType.NoteOn, decoded[0].type)
        assertEquals(1, decoded[0].channel)
        assertEquals(60, decoded[0].data1)
        assertTrue(decoded[0].data2 in 1..127)
        assertTrue(decoded[0].label.contains("UMP2"))
    }

    @Test
    fun encodeMidi1ChannelVoiceUmp_roundTrip() {
        val encoded = UmpCodec.encodeMidi1ChannelVoiceUmp(0x91, 64, 80)
        assertEquals(4, encoded.size)
        assertEquals(0x20, encoded[0].toInt() and 0xFF)
        val decoded = UmpCodec.decodePackets(encoded, 0, 4).single()
        assertEquals(MidiMessageType.NoteOn, decoded.type)
        assertEquals(2, decoded.channel)
        assertEquals(64, decoded.data1)
        assertEquals(80, decoded.data2)
    }

    @Test
    fun midi2VelocityTo7Bit_zeroAndPositive() {
        assertEquals(0, UmpCodec.midi2VelocityTo7Bit(0))
        assertTrue(UmpCodec.midi2VelocityTo7Bit(1) >= 1)
        assertEquals(127, UmpCodec.midi2VelocityTo7Bit(0xFFFF))
    }
}
