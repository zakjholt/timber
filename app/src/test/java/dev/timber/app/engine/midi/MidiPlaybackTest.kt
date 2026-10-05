package dev.timber.app.engine.midi

import dev.timber.app.domain.midi.MidiEvent
import dev.timber.app.domain.midi.MidiMessageType
import dev.timber.app.domain.midi.PPQN
import dev.timber.app.domain.midi.QuantizeGrid
import dev.timber.app.domain.midi.TrackModifiers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiPlaybackTest {
    @Test
    fun forceChannelOverridesTrackChannel() {
        val event = MidiEvent(tick = 0, type = MidiMessageType.NoteOn, channel = 3, data1 = 60, data2 = 100)
        val shaped = MidiPlayback.applyModifiers(
            event,
            TrackModifiers(forceChannel = 12),
            trackChannel = 1,
        )
        assertNotNull(shaped)
        assertEquals(12, shaped!!.channel)
    }

    @Test
    fun transposeAndVelocityScale() {
        val event = MidiEvent(tick = 0, type = MidiMessageType.NoteOn, channel = 1, data1 = 60, data2 = 64)
        val shaped = MidiPlayback.applyModifiers(
            event,
            TrackModifiers(transpose = 2, velocityScale = 2f),
            trackChannel = 1,
        )
        assertEquals(62, shaped!!.data1)
        assertEquals(127, shaped.data2)
    }

    @Test
    fun filterNotesDropsNoteOn() {
        val event = MidiEvent(tick = 0, type = MidiMessageType.NoteOn, channel = 1, data1 = 60, data2 = 100)
        val shaped = MidiPlayback.applyModifiers(
            event,
            TrackModifiers(filterNotes = true),
            trackChannel = 1,
        )
        assertNull(shaped)
    }

    @Test
    fun eventToBytesNoteOn() {
        val bytes = MidiPlayback.eventToBytes(
            MidiEvent(tick = 0, type = MidiMessageType.NoteOn, channel = 2, data1 = 60, data2 = 80),
        )
        assertNotNull(bytes)
        assertEquals(0x91.toByte(), bytes!![0])
        assertEquals(60.toByte(), bytes[1])
        assertEquals(80.toByte(), bytes[2])
    }

    @Test
    fun quantizeSixteenth() {
        val modifiers = TrackModifiers(quantize = QuantizeGrid.Sixteenth)
        val grid = PPQN / 4
        val tick = (grid * 3 + 2).toLong()
        assertEquals((grid * 3).toLong(), MidiPlayback.quantizeTick(tick, modifiers))
    }

    @Test
    fun parseStatusChannelIsOneBased() {
        val (type, channel) = MidiPlayback.parseStatus(0x95)
        assertEquals(MidiMessageType.NoteOn, type)
        assertEquals(6, channel)
        assertTrue(channel in 1..16)
    }
}
