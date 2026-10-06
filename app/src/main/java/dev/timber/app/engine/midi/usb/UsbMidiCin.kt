package dev.timber.app.engine.midi.usb

/**
 * USB MIDI 1.0 Class-Compliant Event Packet helpers (CIN + 3 MIDI bytes).
 *
 * Spec: USB Device Class Definition for MIDI Devices 1.0 — each packet is 4 bytes:
 *   [0] = (cableNumber << 4) | CIN
 *   [1..3] = MIDI data (unused bytes zero-padded)
 *
 * This is **not** UMP-over-USB (MIDI 2.0). UMP uses a different USB alternate
 * / protocol; this spike targets classic USB-MIDI bulk/interrupt packets.
 */
object UsbMidiCin {
    const val PACKET_SIZE = 4

    /** Code Index Number → MIDI byte count in bytes 1..3 (0 = ignore / reserved). */
    fun midiByteCount(cin: Int): Int = when (cin and 0x0F) {
        0x0, 0x1 -> 0 // misc / cable events — reserved for this spike
        0x2 -> 2 // two-byte System Common (MTC, Song Select, …)
        0x3 -> 3 // three-byte System Common (SPP)
        0x4 -> 3 // SysEx starts or continues
        0x5 -> 1 // single-byte System Common or SysEx ends (1 byte)
        0x6 -> 2 // SysEx ends with 2 bytes
        0x7 -> 3 // SysEx ends with 3 bytes
        0x8, 0x9, 0xA, 0xB, 0xE -> 3 // NoteOff/On, PolyAT, CC, PitchBend
        0xC, 0xD -> 2 // Program Change, Channel Pressure
        0xF -> 1 // Single Byte (incl. realtime)
        else -> 0
    }

    fun cableNumber(header: Int): Int = (header shr 4) and 0x0F

    fun cin(header: Int): Int = header and 0x0F

    fun isChannelVoice(cin: Int): Boolean = (cin and 0x0F) in 0x8..0xE

    fun isRealtimeOrSingle(cin: Int): Boolean = (cin and 0x0F) == 0xF

    /**
     * Decode one 4-byte USB-MIDI packet starting at [offset].
     * Returns null for reserved/empty packets or incomplete buffers.
     */
    fun decodePacket(data: ByteArray, offset: Int = 0): DecodedPacket? {
        if (offset < 0 || offset + PACKET_SIZE > data.size) return null
        val header = data[offset].toInt() and 0xFF
        val cin = cin(header)
        val count = midiByteCount(cin)
        if (count <= 0) return null
        val midi = ByteArray(count)
        for (i in 0 until count) {
            midi[i] = data[offset + 1 + i]
        }
        return DecodedPacket(
            cable = cableNumber(header),
            cin = cin,
            midi = midi,
        )
    }

    /**
     * Walk a bulk/interrupt transfer buffer (multiple 4-byte packets).
     * Pads implicitly: length need not be a multiple of 4 (trailing bytes ignored).
     */
    fun decodeBuffer(data: ByteArray, length: Int = data.size): List<DecodedPacket> {
        val end = length.coerceIn(0, data.size)
        if (end < PACKET_SIZE) return emptyList()
        val out = ArrayList<DecodedPacket>(end / PACKET_SIZE)
        var i = 0
        while (i + PACKET_SIZE <= end) {
            decodePacket(data, i)?.let { out += it }
            i += PACKET_SIZE
        }
        return out
    }

    /** Encode channel-voice / short MIDI into one USB-MIDI packet. */
    fun encodeShortMessage(cable: Int, midi: ByteArray, midiOffset: Int = 0, midiCount: Int = midi.size): ByteArray? {
        if (midiCount <= 0 || midiOffset < 0 || midiOffset + midiCount > midi.size) return null
        val status = midi[midiOffset].toInt() and 0xFF
        val cin = cinForStatus(status, midiCount) ?: return null
        val packet = ByteArray(PACKET_SIZE)
        packet[0] = (((cable and 0x0F) shl 4) or (cin and 0x0F)).toByte()
        val copy = midiCount.coerceAtMost(3)
        for (i in 0 until copy) {
            packet[1 + i] = midi[midiOffset + i]
        }
        return packet
    }

    fun cinForStatus(status: Int, byteCount: Int): Int? {
        val s = status and 0xFF
        return when {
            s >= 0xF8 -> 0xF // realtime single byte
            s == 0xF6 || s == 0xF8 || s == 0xFA || s == 0xFB || s == 0xFC || s == 0xFE || s == 0xFF -> 0xF
            s in 0x80..0x8F -> 0x8
            s in 0x90..0x9F -> 0x9
            s in 0xA0..0xAF -> 0xA
            s in 0xB0..0xBF -> 0xB
            s in 0xC0..0xCF -> 0xC
            s in 0xD0..0xDF -> 0xD
            s in 0xE0..0xEF -> 0xE
            s == 0xF1 || s == 0xF3 -> 0x2 // two-byte system common
            s == 0xF2 -> 0x3 // song position
            s == 0xF6 -> 0x5
            byteCount == 1 && s >= 0xF0 -> 0x5
            else -> null
        }
    }

    data class DecodedPacket(
        val cable: Int,
        val cin: Int,
        val midi: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DecodedPacket) return false
            return cable == other.cable && cin == other.cin && midi.contentEquals(other.midi)
        }

        override fun hashCode(): Int {
            var result = cable
            result = 31 * result + cin
            result = 31 * result + midi.contentHashCode()
            return result
        }
    }
}
