package dev.timber.app.engine.midi

import dev.timber.app.domain.midi.MidiMessageType

/**
 * Universal MIDI Packet helpers (USB MIDI 2.0 / Android TRANSPORT_UNIVERSAL_MIDI_PACKETS).
 * Packets are always a multiple of 4 bytes.
 */
object UmpCodec {
    const val MT_UTILITY: Int = 0x0
    const val MT_SYSTEM: Int = 0x1
    const val MT_MIDI1_CHANNEL_VOICE: Int = 0x2
    const val MT_DATA64_SYSEX7: Int = 0x3
    const val MT_MIDI2_CHANNEL_VOICE: Int = 0x4
    const val MT_DATA128: Int = 0x5

    data class Decoded(
        val type: MidiMessageType,
        val channel: Int,
        val data1: Int,
        val data2: Int,
        val messageType: Int,
        val group: Int,
        val label: String,
    )

    fun messageType(firstByte: Int): Int = (firstByte shr 4) and 0x0F

    fun packetWordCount(messageType: Int): Int = when (messageType) {
        MT_UTILITY, MT_SYSTEM, MT_MIDI1_CHANNEL_VOICE -> 1
        MT_DATA64_SYSEX7, MT_MIDI2_CHANNEL_VOICE -> 2
        MT_DATA128 -> 4
        else -> 1
    }

    fun looksLikeUmp(data: ByteArray, offset: Int, count: Int): Boolean {
        if (count < 4 || count % 4 != 0) return false
        val mt = messageType(data[offset].toInt() and 0xFF)
        val words = packetWordCount(mt)
        return count >= words * 4
    }

    /** Decode one or more UMP packets starting at [offset]; returns channel-voice and notable events. */
    fun decodePackets(data: ByteArray, offset: Int, count: Int): List<Decoded> {
        val out = mutableListOf<Decoded>()
        var i = offset
        val end = offset + count
        while (i + 4 <= end) {
            val b0 = data[i].toInt() and 0xFF
            val mt = messageType(b0)
            val words = packetWordCount(mt)
            val bytes = words * 4
            if (i + bytes > end) break
            decodeOne(data, i, mt, b0 and 0x0F)?.let { out += it }
            i += bytes
        }
        return out
    }

    private fun decodeOne(data: ByteArray, offset: Int, mt: Int, group: Int): Decoded? {
        return when (mt) {
            MT_MIDI1_CHANNEL_VOICE -> {
                val status = data[offset + 1].toInt() and 0xFF
                val d1 = data[offset + 2].toInt() and 0xFF
                val d2 = data[offset + 3].toInt() and 0xFF
                val (type, channel) = MidiPlayback.parseStatus(status)
                val normalized = if (type == MidiMessageType.NoteOn && d2 == 0) MidiMessageType.NoteOff else type
                Decoded(
                    type = normalized,
                    channel = channel,
                    data1 = d1,
                    data2 = d2,
                    messageType = mt,
                    group = group,
                    label = "UMP1 ${MidiDebugFormat.describeType(normalized, d1, d2)}",
                )
            }
            MT_MIDI2_CHANNEL_VOICE -> {
                val statusByte = data[offset + 1].toInt() and 0xFF
                val opcode = statusByte and 0xF0
                val channel = (statusByte and 0x0F) + 1
                val note = data[offset + 2].toInt() and 0xFF
                val velocity16 =
                    ((data[offset + 4].toInt() and 0xFF) shl 8) or (data[offset + 5].toInt() and 0xFF)
                val velocity7 = midi2VelocityTo7Bit(velocity16)
                when (opcode) {
                    0x80 -> Decoded(
                        MidiMessageType.NoteOff, channel, note, velocity7, mt, group,
                        "UMP2 NoteOff note=$note vel16=$velocity16 vel7=$velocity7",
                    )
                    0x90 -> {
                        val type = if (velocity16 == 0) MidiMessageType.NoteOff else MidiMessageType.NoteOn
                        Decoded(
                            type, channel, note, velocity7, mt, group,
                            "UMP2 ${if (type == MidiMessageType.NoteOff) "NoteOff(vel0)" else "NoteOn"} note=$note vel16=$velocity16 vel7=$velocity7",
                        )
                    }
                    0xB0 -> {
                        val index = note
                        val value16 =
                            ((data[offset + 4].toInt() and 0xFF) shl 8) or (data[offset + 5].toInt() and 0xFF)
                        val value7 = (value16 shr 9).coerceIn(0, 127)
                        Decoded(
                            MidiMessageType.ControlChange, channel, index, value7, mt, group,
                            "UMP2 CC cc=$index val16=$value16 val7=$value7",
                        )
                    }
                    0xC0 -> Decoded(
                        MidiMessageType.ProgramChange, channel, note, 0, mt, group,
                        "UMP2 ProgramChange pc=$note",
                    )
                    0xD0 -> {
                        val pressure16 =
                            ((data[offset + 4].toInt() and 0xFF) shl 8) or (data[offset + 5].toInt() and 0xFF)
                        val pressure7 = (pressure16 shr 9).coerceIn(0, 127)
                        Decoded(
                            MidiMessageType.ChannelPressure, channel, pressure7, 0, mt, group,
                            "UMP2 ChannelPressure pressure7=$pressure7",
                        )
                    }
                    0xE0 -> {
                        val data14 =
                            ((data[offset + 4].toInt() and 0xFF) shl 8) or (data[offset + 5].toInt() and 0xFF)
                        // Map 16-bit to 14-bit pitch bend roughly.
                        val bend14 = (data14 shr 2).coerceIn(0, 16383)
                        Decoded(
                            MidiMessageType.PitchBend, channel, bend14 and 0x7F, (bend14 shr 7) and 0x7F,
                            mt, group, "UMP2 PitchBend raw16=$data14",
                        )
                    }
                    0xA0 -> Decoded(
                        MidiMessageType.Aftertouch, channel, note,
                        midi2VelocityTo7Bit(
                            ((data[offset + 4].toInt() and 0xFF) shl 8) or (data[offset + 5].toInt() and 0xFF),
                        ),
                        mt, group, "UMP2 Aftertouch note=$note",
                    )
                    else -> Decoded(
                        MidiMessageType.Other, channel, note, 0, mt, group,
                        "UMP2 Other opcode=0x${"%02X".format(opcode)}",
                    )
                }
            }
            MT_SYSTEM -> {
                val status = data[offset + 1].toInt() and 0xFF
                Decoded(
                    MidiMessageType.Other, 1, status, 0, mt, group,
                    "UMP System status=0x${"%02X".format(status)}",
                )
            }
            MT_UTILITY -> Decoded(
                MidiMessageType.Other, 1, 0, 0, mt, group,
                "UMP Utility",
            )
            MT_DATA64_SYSEX7, MT_DATA128 -> Decoded(
                MidiMessageType.SysEx, 1, 0, 0, mt, group,
                if (mt == MT_DATA64_SYSEX7) "UMP SysEx7" else "UMP Data128",
            )
            else -> Decoded(
                MidiMessageType.Other, 1, 0, 0, mt, group,
                "UMP mt=0x${"%X".format(mt)}",
            )
        }
    }

    /** Encode a MIDI 1.0 channel message as a 4-byte UMP MIDI 1.0 Channel Voice packet. */
    fun encodeMidi1ChannelVoiceUmp(status: Int, data1: Int, data2: Int, group: Int = 0): ByteArray =
        byteArrayOf(
            ((MT_MIDI1_CHANNEL_VOICE shl 4) or (group and 0x0F)).toByte(),
            (status and 0xFF).toByte(),
            (data1 and 0x7F).toByte(),
            (data2 and 0x7F).toByte(),
        )

    fun midi2VelocityTo7Bit(velocity16: Int): Int {
        if (velocity16 <= 0) return 0
        // Map 1..65535 → 1..127
        return ((velocity16 + 0x80) shr 9).coerceIn(1, 127)
    }

    fun protocolLabel(protocol: Int): String = when (protocol) {
        MidiDeviceInfoProtocol.PROTOCOL_UMP_MIDI_2_0 -> "UMP_MIDI_2_0"
        MidiDeviceInfoProtocol.PROTOCOL_UMP_MIDI_2_0_AND_JRTS -> "UMP_MIDI_2_0_JRTS"
        MidiDeviceInfoProtocol.PROTOCOL_UMP_MIDI_1_0_UP_TO_64_BITS -> "UMP_MIDI_1_0_64"
        MidiDeviceInfoProtocol.PROTOCOL_UMP_MIDI_1_0_UP_TO_64_BITS_AND_JRTS -> "UMP_MIDI_1_0_64_JRTS"
        MidiDeviceInfoProtocol.PROTOCOL_UMP_MIDI_1_0_UP_TO_128_BITS -> "UMP_MIDI_1_0_128"
        MidiDeviceInfoProtocol.PROTOCOL_UMP_MIDI_1_0_UP_TO_128_BITS_AND_JRTS -> "UMP_MIDI_1_0_128_JRTS"
        MidiDeviceInfoProtocol.PROTOCOL_UMP_USE_MIDI_CI -> "UMP_MIDI_CI"
        MidiDeviceInfoProtocol.PROTOCOL_UNKNOWN -> "UNKNOWN"
        else -> "protocol=$protocol"
    }
}

/**
 * Local aliases so unit tests / JVM code can reference protocol constants without linking
 * android.media.midi.MidiDeviceInfo in pure-format tests that don't need the class.
 * Values match API 33+ MidiDeviceInfo.
 */
object MidiDeviceInfoProtocol {
    const val PROTOCOL_UMP_MIDI_1_0_UP_TO_128_BITS: Int = 3
    const val PROTOCOL_UMP_MIDI_1_0_UP_TO_128_BITS_AND_JRTS: Int = 4
    const val PROTOCOL_UMP_MIDI_1_0_UP_TO_64_BITS: Int = 1
    const val PROTOCOL_UMP_MIDI_1_0_UP_TO_64_BITS_AND_JRTS: Int = 2
    const val PROTOCOL_UMP_MIDI_2_0: Int = 17
    const val PROTOCOL_UMP_MIDI_2_0_AND_JRTS: Int = 18
    const val PROTOCOL_UMP_USE_MIDI_CI: Int = 0
    const val PROTOCOL_UNKNOWN: Int = -1
}
