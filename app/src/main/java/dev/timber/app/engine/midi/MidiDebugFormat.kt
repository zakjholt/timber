package dev.timber.app.engine.midi

import dev.timber.app.domain.midi.MidiMessageType
import dev.timber.app.domain.midi.TransportState

/** Pure MIDI debug formatting (no Android dependency — unit-testable). */
object MidiDebugFormat {
    fun formatBytes(data: ByteArray, offset: Int, count: Int): String {
        if (count <= 0) return ""
        val end = (offset + count).coerceAtMost(data.size)
        if (offset >= end) return ""
        return buildString(capacity = (end - offset) * 3) {
            for (i in offset until end) {
                if (isNotEmpty()) append(' ')
                append("%02X".format(data[i].toInt() and 0xFF))
            }
        }
    }

    fun describeType(type: MidiMessageType, data1: Int, data2: Int): String = when (type) {
        MidiMessageType.NoteOn -> if (data2 == 0) "NoteOff(vel0)" else "NoteOn note=$data1 vel=$data2"
        MidiMessageType.NoteOff -> "NoteOff note=$data1 vel=$data2"
        MidiMessageType.ControlChange -> "CC cc=$data1 val=$data2"
        MidiMessageType.ProgramChange -> "ProgramChange pc=$data1"
        MidiMessageType.PitchBend -> "PitchBend lsb=$data1 msb=$data2"
        MidiMessageType.Aftertouch -> "Aftertouch note=$data1 pressure=$data2"
        MidiMessageType.ChannelPressure -> "ChannelPressure pressure=$data1"
        MidiMessageType.SysEx -> "SysEx"
        MidiMessageType.Other -> "Other"
    }

    fun formatIncoming(
        productName: String,
        deviceName: String,
        deviceId: Int,
        portIndex: Int,
        portName: String?,
        timestampNs: Long,
        status: Int,
        dataBytes: String,
        typeLabel: String,
        channel: Int,
        listenChannel: Int?,
        channelOk: Boolean,
        isSelectedRecordPort: Boolean,
        transport: TransportState,
        wouldRecord: Boolean,
    ): String {
        val listen = listenChannel?.toString() ?: "Omni"
        val portLabel = portName?.takeIf { it.isNotBlank() } ?: "port $portIndex"
        return buildString {
            append("IN product=").append(productName.ifBlank { "?" })
            append(" device=\"").append(deviceName).append("\"")
            append(" id=").append(deviceId)
            append(" port=").append(portIndex).append("(\"").append(portLabel).append("\")")
            append(" tNs=").append(timestampNs)
            append(" status=0x").append("%02X".format(status))
            append(" bytes=[").append(dataBytes).append(']')
            append(" type=").append(typeLabel)
            append(" ch=").append(channel)
            append(" listen=").append(listen)
            append(" channelOk=").append(channelOk)
            append(" selectedIn=").append(isSelectedRecordPort)
            append(" transport=").append(transport)
            append(" wouldRecord=").append(wouldRecord)
        }
    }
}
