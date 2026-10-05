package dev.timber.app.engine.midi

import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import dev.timber.app.domain.midi.MidiEvent
import dev.timber.app.domain.midi.MidiMessageType
import dev.timber.app.domain.midi.PPQN
import dev.timber.app.domain.midi.Part
import dev.timber.app.domain.midi.Song
import dev.timber.app.domain.midi.TransportState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Realtime MIDI scheduler / recorder skeleton.
 * Timing runs on a dedicated high-priority thread; UI observes [state].
 */
class MidiEngine(
    private val midiManager: MidiManager?,
) {
    data class State(
        val song: Song = Song(),
        val transport: TransportState = TransportState.Stopped,
        val positionTicks: Long = 0,
        val activePartId: Int = 1,
        val connectedDevices: List<String> = emptyList(),
        val thruEnabled: Boolean = true,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val running = AtomicBoolean(false)
    private val positionTicks = AtomicLong(0)
    private val recordBuffer = CopyOnWriteArrayList<MidiEvent>()
    private var clockThread: Thread? = null
    private var outputReceiver: MidiReceiver? = null

    fun refreshDevices() {
        val names = midiManager?.devices
            ?.map { it.properties.getString(MidiDeviceInfo.PROPERTY_NAME) ?: "MIDI device" }
            ?: emptyList()
        _state.update { it.copy(connectedDevices = names) }
    }

    fun setSong(song: Song) {
        _state.update { it.copy(song = song, activePartId = song.parts.firstOrNull()?.id ?: 1) }
    }

    fun setThru(enabled: Boolean) {
        _state.update { it.copy(thruEnabled = enabled) }
    }

    fun play() {
        if (running.getAndSet(true)) return
        _state.update { it.copy(transport = TransportState.Playing) }
        startClock()
    }

    fun stop() {
        running.set(false)
        clockThread = null
        positionTicks.set(0)
        recordBuffer.clear()
        _state.update {
            it.copy(transport = TransportState.Stopped, positionTicks = 0)
        }
        sendPanic()
    }

    fun toggleRecord() {
        val current = _state.value.transport
        when (current) {
            TransportState.Recording -> {
                commitRecording()
                _state.update { it.copy(transport = TransportState.Playing) }
            }
            TransportState.Playing, TransportState.Stopped, TransportState.CountIn -> {
                recordBuffer.clear()
                if (!running.get()) play()
                _state.update { it.copy(transport = TransportState.Recording) }
            }
        }
    }

    fun onIncomingMidi(data: ByteArray, offset: Int, count: Int, timestampNs: Long) {
        val snapshot = _state.value
        if (snapshot.thruEnabled) {
            outputReceiver?.send(data, offset, count, timestampNs)
        }
        if (snapshot.transport != TransportState.Recording) return

        val status = data[offset].toInt() and 0xFF
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
        val channel = status and 0x0F
        val d1 = if (count > 1) data[offset + 1].toInt() and 0xFF else 0
        val d2 = if (count > 2) data[offset + 2].toInt() and 0xFF else 0
        recordBuffer += MidiEvent(
            tick = positionTicks.get(),
            type = type,
            channel = channel + 1,
            data1 = d1,
            data2 = d2,
        )
    }

    fun activePart(): Part? {
        val song = _state.value.song
        return song.parts.find { it.id == _state.value.activePartId } ?: song.parts.firstOrNull()
    }

    fun armTrack(trackId: Int) {
        val song = _state.value.song
        val partId = _state.value.activePartId
        val updated = song.copy(
            parts = song.parts.map { part ->
                if (part.id != partId) part
                else part.copy(
                    tracks = part.tracks.map { track ->
                        track.copy(armed = track.id == trackId)
                    },
                )
            },
        )
        setSong(updated)
    }

    fun toggleMute(trackId: Int) {
        val song = _state.value.song
        val partId = _state.value.activePartId
        val updated = song.copy(
            parts = song.parts.map { part ->
                if (part.id != partId) part
                else part.copy(
                    tracks = part.tracks.map { track ->
                        if (track.id == trackId) track.copy(muted = !track.muted) else track
                    },
                )
            },
        )
        setSong(updated)
    }

    private fun startClock() {
        clockThread = thread(name = "timber-midi-clock", isDaemon = true, priority = Thread.MAX_PRIORITY) {
            var nextTickNs = System.nanoTime()
            while (running.get()) {
                val bpm = _state.value.song.tempoBpm
                val tickDurationNs = (60_000_000_000.0 / (bpm * PPQN)).toLong()
                nextTickNs += tickDurationNs
                val now = System.nanoTime()
                val sleepNs = nextTickNs - now
                if (sleepNs > 0) {
                    val ms = sleepNs / 1_000_000
                    val ns = (sleepNs % 1_000_000).toInt()
                    try {
                        Thread.sleep(ms, ns)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                val tick = positionTicks.incrementAndGet()
                val part = activePart()
                if (part != null && tick >= part.lengthTicks) {
                    positionTicks.set(0)
                }
                scheduleTick(positionTicks.get())
                _state.update { it.copy(positionTicks = positionTicks.get()) }
            }
        }
    }

    private fun scheduleTick(tick: Long) {
        val part = activePart() ?: return
        for (track in part.tracks) {
            if (track.muted) continue
            // Playback of stored events will land here (native-priority path later).
            track.events.filter { it.tick == tick }.forEach { /* emit */ }
        }
    }

    private fun commitRecording() {
        val part = activePart() ?: return
        val armed = part.tracks.firstOrNull { it.armed } ?: part.tracks.firstOrNull() ?: return
        val merged = (armed.events + recordBuffer).sortedBy { it.tick }
        val song = _state.value.song
        val updated = song.copy(
            parts = song.parts.map { p ->
                if (p.id != part.id) p
                else p.copy(
                    tracks = p.tracks.map { t ->
                        if (t.id == armed.id) t.copy(events = merged) else t
                    },
                )
            },
        )
        recordBuffer.clear()
        setSong(updated)
    }

    private fun sendPanic() {
        val message = ByteArray(3)
        for (ch in 0..15) {
            message[0] = (0xB0 or ch).toByte()
            message[1] = 123 // all notes off
            message[2] = 0
            outputReceiver?.send(message, 0, 3, 0)
        }
    }
}
