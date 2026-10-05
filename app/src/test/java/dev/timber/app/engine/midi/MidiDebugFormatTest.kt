package dev.timber.app.engine.midi

import dev.timber.app.domain.midi.MidiMessageType
import dev.timber.app.domain.midi.TransportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiDebugFormatTest {
    @Test
    fun formatBytes_hexSpaceSeparated() {
        val data = byteArrayOf(0x90.toByte(), 60, 100)
        assertEquals("90 3C 64", MidiDebugFormat.formatBytes(data, 0, 3))
    }

    @Test
    fun describeType_noteOnAndCc() {
        assertEquals("NoteOn note=60 vel=100", MidiDebugFormat.describeType(MidiMessageType.NoteOn, 60, 100))
        assertEquals("NoteOff(vel0)", MidiDebugFormat.describeType(MidiMessageType.NoteOn, 60, 0))
        assertEquals("CC cc=1 val=64", MidiDebugFormat.describeType(MidiMessageType.ControlChange, 1, 64))
    }

    @Test
    fun formatIncoming_includesFilterAndRecordDecision() {
        val line = MidiDebugFormat.formatIncoming(
            productName = "MODX M",
            deviceName = "Yamaha MODX M",
            deviceId = 12,
            portIndex = 0,
            portName = "MIDI",
            timestampNs = 123L,
            status = 0x90,
            dataBytes = "90 3C 64",
            typeLabel = "NoteOn note=60 vel=100",
            channel = 1,
            listenChannel = null,
            channelOk = true,
            isSelectedRecordPort = true,
            transport = TransportState.Recording,
            wouldRecord = true,
        )
        assertTrue(line.contains("product=MODX M"))
        assertTrue(line.contains("id=12"))
        assertTrue(line.contains("port=0(\"MIDI\")"))
        assertTrue(line.contains("listen=Omni"))
        assertTrue(line.contains("channelOk=true"))
        assertTrue(line.contains("selectedIn=true"))
        assertTrue(line.contains("wouldRecord=true"))
        assertTrue(line.contains("transport=Recording"))
    }
}
