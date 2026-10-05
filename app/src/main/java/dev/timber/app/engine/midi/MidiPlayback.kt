package dev.timber.app.engine.midi

import dev.timber.app.domain.midi.MidiEvent
import dev.timber.app.domain.midi.MidiMessageType
import dev.timber.app.domain.midi.PPQN
import dev.timber.app.domain.midi.QuantizeGrid
import dev.timber.app.domain.midi.TrackModifiers

/**
 * Pure MIDI transform helpers used by [MidiEngine] on the clock / thru paths.
 */
object MidiPlayback {
    fun shouldEmit(event: MidiEvent, modifiers: TrackModifiers): Boolean {
        return when (event.type) {
            MidiMessageType.NoteOn, MidiMessageType.NoteOff -> !modifiers.filterNotes
            MidiMessageType.ControlChange -> !modifiers.filterCc
            MidiMessageType.ProgramChange -> !modifiers.filterProgramChange
            MidiMessageType.Aftertouch, MidiMessageType.ChannelPressure -> !modifiers.filterAftertouch
            MidiMessageType.PitchBend -> !modifiers.filterPitchBend
            MidiMessageType.SysEx, MidiMessageType.Other -> true
        }
    }

    /**
     * Apply non-destructive modifiers for playback / thru.
     * [trackChannel] is the track's base output channel (1–16); [forceChannel] wins when set.
     */
    fun applyModifiers(
        event: MidiEvent,
        modifiers: TrackModifiers,
        trackChannel: Int,
    ): MidiEvent? {
        if (!shouldEmit(event, modifiers)) return null

        val channel = (modifiers.forceChannel ?: trackChannel).coerceIn(1, 16)
        var data1 = event.data1
        var data2 = event.data2

        when (event.type) {
            MidiMessageType.NoteOn, MidiMessageType.NoteOff -> {
                if (modifiers.transpose != 0 && modifiers.transpose != TrackModifiers.PERFORMANCE_TRANSPOSE) {
                    data1 = (data1 + modifiers.transpose).coerceIn(0, 127)
                }
                if (event.type == MidiMessageType.NoteOn && event.data2 > 0 && modifiers.velocityScale != 1f) {
                    data2 = (data2 * modifiers.velocityScale).toInt().coerceIn(1, 127)
                }
            }
            else -> Unit
        }

        return event.copy(channel = channel, data1 = data1, data2 = data2)
    }

    /** Quantize a tick; swing nudges off-beats when grid is active. */
    fun quantizeTick(tick: Long, modifiers: TrackModifiers): Long {
        val gridTicks = gridToTicks(modifiers.quantize) ?: return tick
        if (gridTicks <= 0) return tick
        val quantized = ((tick + gridTicks / 2) / gridTicks) * gridTicks
        if (modifiers.swing <= 0f) return quantized
        val inPair = quantized % (gridTicks * 2)
        return if (inPair == gridTicks.toLong()) {
            quantized + (gridTicks * modifiers.swing * 0.5f).toLong()
        } else {
            quantized
        }
    }

    fun gridToTicks(grid: QuantizeGrid): Int? = when (grid) {
        QuantizeGrid.Off -> null
        QuantizeGrid.Quarter -> PPQN
        QuantizeGrid.Eighth -> PPQN / 2
        QuantizeGrid.Sixteenth -> PPQN / 4
        QuantizeGrid.ThirtySecond -> PPQN / 8
        QuantizeGrid.TripletEighth -> (PPQN * 2) / 3
        QuantizeGrid.TripletSixteenth -> PPQN / 6
    }

    fun eventToBytes(event: MidiEvent): ByteArray? {
        val ch = (event.channel - 1).coerceIn(0, 15)
        return when (event.type) {
            MidiMessageType.NoteOff -> byteArrayOf((0x80 or ch).toByte(), event.data1.toByte(), event.data2.toByte())
            MidiMessageType.NoteOn -> byteArrayOf((0x90 or ch).toByte(), event.data1.toByte(), event.data2.toByte())
            MidiMessageType.Aftertouch -> byteArrayOf((0xA0 or ch).toByte(), event.data1.toByte(), event.data2.toByte())
            MidiMessageType.ControlChange -> byteArrayOf((0xB0 or ch).toByte(), event.data1.toByte(), event.data2.toByte())
            MidiMessageType.ProgramChange -> byteArrayOf((0xC0 or ch).toByte(), event.data1.toByte())
            MidiMessageType.ChannelPressure -> byteArrayOf((0xD0 or ch).toByte(), event.data1.toByte())
            MidiMessageType.PitchBend -> byteArrayOf((0xE0 or ch).toByte(), event.data1.toByte(), event.data2.toByte())
            MidiMessageType.SysEx -> event.sysEx
            MidiMessageType.Other -> null
        }
    }

    fun parseStatus(status: Int): Pair<MidiMessageType, Int> {
        val type = when (status and 0xF0) {
            0x80 -> MidiMessageType.NoteOff
            0x90 -> MidiMessageType.NoteOn
            0xB0 -> MidiMessageType.ControlChange
            0xC0 -> MidiMessageType.ProgramChange
            0xD0 -> MidiMessageType.ChannelPressure
            0xE0 -> MidiMessageType.PitchBend
            0xA0 -> MidiMessageType.Aftertouch
            0xF0 -> MidiMessageType.SysEx
            else -> MidiMessageType.Other
        }
        return type to ((status and 0x0F) + 1)
    }
}
