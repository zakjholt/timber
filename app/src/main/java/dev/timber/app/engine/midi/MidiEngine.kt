package dev.timber.app.engine.midi

import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import dev.timber.app.device.ModxMProfile
import dev.timber.app.domain.midi.MIDI_DEVICE_UNSET
import dev.timber.app.domain.midi.MidiEndpointRef
import dev.timber.app.domain.midi.MidiEvent
import dev.timber.app.domain.midi.MidiMessageType
import dev.timber.app.domain.midi.MidiTrack
import dev.timber.app.domain.midi.PPQN
import dev.timber.app.domain.midi.Part
import dev.timber.app.domain.midi.RecordInputConfig
import dev.timber.app.domain.midi.Song
import dev.timber.app.domain.midi.TransportState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Realtime MIDI scheduler / recorder.
 * Timing runs on a dedicated high-priority thread; UI observes [state].
 *
 * Android naming: [MidiInputPort] is where the *app writes* (our out);
 * [MidiOutputPort] is where the *device writes* (our in).
 */
class MidiEngine(
    private val midiManager: MidiManager?,
) {
    data class PortInfo(
        val deviceId: Int,
        val portIndex: Int,
        val deviceName: String,
        val portName: String?,
        val isModxFamily: Boolean,
    ) {
        val label: String
            get() = buildString {
                append(deviceName)
                if (!portName.isNullOrBlank()) append(" · ").append(portName)
                else append(" · port ").append(portIndex)
            }

        fun toRef(): MidiEndpointRef = MidiEndpointRef(deviceId, portIndex, label)
    }

    data class State(
        val song: Song = Song(),
        val transport: TransportState = TransportState.Stopped,
        val positionTicks: Long = 0,
        val activePartId: Int = 1,
        val connectedDevices: List<String> = emptyList(),
        val availableInputs: List<PortInfo> = emptyList(),
        val availableOutputs: List<PortInfo> = emptyList(),
        val recordInput: RecordInputConfig = RecordInputConfig(),
        /** Beats remaining in count-in (0 when not counting in). */
        val countInBeatsRemaining: Int = 0,
        /** Increments each count-in beat for UI pulse. */
        val countInPulse: Int = 0,
        val countInBeats: Int = 4,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val running = AtomicBoolean(false)
    private val positionTicks = AtomicLong(0)
    private val recordBuffer = CopyOnWriteArrayList<MidiEvent>()
    private val countInTicksLeft = AtomicLong(0)
    private val countInPulseCounter = AtomicInteger(0)
    private var clockThread: Thread? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val openDevices = ConcurrentHashMap<Int, MidiDevice>()
    /** App→device writers keyed by "deviceId:portIndex". */
    private val outputPorts = ConcurrentHashMap<String, MidiInputPort>()
    /** Device→app readers keyed by "deviceId:portIndex". */
    private val inputPorts = ConcurrentHashMap<String, MidiOutputPort>()
    private val inputReceivers = ConcurrentHashMap<String, MidiReceiver>()

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            refreshDevices()
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            closeDevice(device.id)
            refreshDevices()
        }
    }

    init {
        midiManager?.registerDeviceCallback(deviceCallback, mainHandler)
        refreshDevices()
    }

    fun release() {
        midiManager?.unregisterDeviceCallback(deviceCallback)
        stop()
        closeAllDevices()
    }

    fun refreshDevices() {
        val devices = midiManager?.devices?.toList().orEmpty()
        val names = devices.map { deviceDisplayName(it) }
        val inputs = mutableListOf<PortInfo>()
        val outputs = mutableListOf<PortInfo>()

        for (info in devices) {
            val name = deviceDisplayName(info)
            val isModx = ModxMProfile.looksLikeModxFamily(name, null)
            for (port in info.ports) {
                val portInfo = PortInfo(
                    deviceId = info.id,
                    portIndex = port.portNumber,
                    deviceName = name,
                    portName = port.name,
                    isModxFamily = isModx,
                )
                when (port.type) {
                    MidiDeviceInfo.PortInfo.TYPE_OUTPUT -> inputs += portInfo // device out = our in
                    MidiDeviceInfo.PortInfo.TYPE_INPUT -> outputs += portInfo // device in = our out
                }
            }
            openDeviceIfNeeded(info)
        }

        _state.update {
            val record = preferDefaultInput(it.recordInput, inputs)
            val song = ensureTrackOutputs(it.song, outputs)
            it.copy(
                connectedDevices = names,
                availableInputs = inputs,
                availableOutputs = outputs,
                recordInput = record,
                song = song,
            )
        }
        reconnectRecordInput()
    }

    fun setSong(song: Song) {
        val outputs = _state.value.availableOutputs
        _state.update {
            val keepPart = song.parts.any { part -> part.id == it.activePartId }
            it.copy(
                song = ensureTrackOutputs(song, outputs),
                activePartId = if (keepPart) it.activePartId else (song.parts.firstOrNull()?.id ?: 1),
            )
        }
    }

    fun setRecordInput(endpoint: MidiEndpointRef) {
        _state.update {
            it.copy(recordInput = it.recordInput.copy(endpoint = endpoint))
        }
        reconnectRecordInput()
    }

    /** null = Omni */
    fun setRecordListenChannel(channel: Int?) {
        require(channel == null || channel in 1..16) { "listen channel must be Omni or 1..16" }
        _state.update {
            it.copy(recordInput = it.recordInput.copy(listenChannel = channel))
        }
    }

    fun setTrackOutput(trackId: Int, endpoint: MidiEndpointRef, channel: Int) {
        val ch = channel.coerceIn(1, 16)
        updateActivePartTracks { track ->
            if (track.id == trackId) track.copy(output = endpoint, outputChannel = ch) else track
        }
    }

    fun setCountInBeats(beats: Int) {
        _state.update { it.copy(countInBeats = beats.coerceIn(0, 16)) }
    }

    fun play() {
        if (running.getAndSet(true)) {
            if (_state.value.transport == TransportState.Stopped) {
                _state.update { it.copy(transport = TransportState.Playing) }
            }
            return
        }
        _state.update { it.copy(transport = TransportState.Playing, countInBeatsRemaining = 0) }
        startClock()
    }

    fun stop() {
        running.set(false)
        clockThread?.interrupt()
        clockThread = null
        positionTicks.set(0)
        countInTicksLeft.set(0)
        recordBuffer.clear()
        _state.update {
            it.copy(
                transport = TransportState.Stopped,
                positionTicks = 0,
                countInBeatsRemaining = 0,
            )
        }
        sendPanic()
    }

    /**
     * Record arming with count-in:
     * - From Stopped/Playing → CountIn (then Recording)
     * - From CountIn → cancel into Playing (or Stopped if clock wasn't wanted)
     * - From Recording → commit overdub and keep Playing
     */
    fun toggleRecord() {
        when (_state.value.transport) {
            TransportState.Recording -> {
                commitRecording()
                _state.update { it.copy(transport = TransportState.Playing, countInBeatsRemaining = 0) }
            }
            TransportState.CountIn -> {
                countInTicksLeft.set(0)
                _state.update { it.copy(transport = TransportState.Playing, countInBeatsRemaining = 0) }
            }
            TransportState.Playing, TransportState.Stopped -> {
                recordBuffer.clear()
                val part = activePart()
                val beats = _state.value.countInBeats
                    .takeIf { it > 0 }
                    ?: (part?.timeSignatureNumerator ?: 4)
                if (beats <= 0) {
                    if (!running.get()) play()
                    _state.update { it.copy(transport = TransportState.Recording, countInBeatsRemaining = 0) }
                    return
                }
                countInTicksLeft.set(beats.toLong() * PPQN)
                countInPulseCounter.set(0)
                _state.update {
                    it.copy(
                        transport = TransportState.CountIn,
                        countInBeatsRemaining = beats,
                        countInPulse = 0,
                        countInBeats = beats,
                    )
                }
                if (!running.get()) {
                    running.set(true)
                    startClock()
                }
            }
        }
    }

    fun onIncomingMidi(data: ByteArray, offset: Int, count: Int, timestampNs: Long) {
        if (count <= 0) return
        val snapshot = _state.value
        val status = data[offset].toInt() and 0xFF
        if (status >= 0xF8) return // realtime clock/sense — ignore for record/thru notes

        val (type, channel) = MidiPlayback.parseStatus(status)
        val d1 = if (count > 1) data[offset + 1].toInt() and 0xFF else 0
        val d2 = if (count > 2) data[offset + 2].toInt() and 0xFF else 0

        // Note-on velocity 0 → treat as note-off for recording cleanliness
        val normalizedType = if (type == MidiMessageType.NoteOn && d2 == 0) MidiMessageType.NoteOff else type

        val listen = snapshot.recordInput.listenChannel
        val channelOk = listen == null || channel == listen

        // Always-on thru while ports are open → armed track output (or first track).
        if (channelOk) {
            val part = activePart()
            val thruTrack = part?.tracks?.firstOrNull { it.armed } ?: part?.tracks?.firstOrNull()
            if (thruTrack != null && !thruTrack.muted) {
                val raw = MidiEvent(
                    tick = 0,
                    type = normalizedType,
                    channel = channel,
                    data1 = d1,
                    data2 = d2,
                )
                val shaped = MidiPlayback.applyModifiers(raw, thruTrack.modifiers, thruTrack.outputChannel)
                if (shaped != null) {
                    sendToTrackOutput(thruTrack, shaped, timestampNs)
                }
            }
        }

        if (snapshot.transport != TransportState.Recording || !channelOk) return

        recordBuffer += MidiEvent(
            tick = positionTicks.get(),
            type = normalizedType,
            channel = channel,
            data1 = d1,
            data2 = d2,
        )
    }

    fun activePart(): Part? {
        val song = _state.value.song
        return song.parts.find { it.id == _state.value.activePartId } ?: song.parts.firstOrNull()
    }

    fun armTrack(trackId: Int) {
        updateActivePartTracks { track ->
            track.copy(armed = track.id == trackId)
        }
    }

    fun toggleMute(trackId: Int) {
        updateActivePartTracks { track ->
            if (track.id == trackId) track.copy(muted = !track.muted) else track
        }
    }

    private fun updateActivePartTracks(transform: (MidiTrack) -> MidiTrack) {
        val song = _state.value.song
        val partId = _state.value.activePartId
        val updated = song.copy(
            parts = song.parts.map { part ->
                if (part.id != partId) part
                else part.copy(tracks = part.tracks.map(transform))
            },
        )
        setSong(updated)
    }

    private fun startClock() {
        clockThread = thread(name = "timber-midi-clock", isDaemon = true, priority = Thread.MAX_PRIORITY) {
            var nextTickNs = System.nanoTime()
            while (running.get()) {
                val song = _state.value.song
                val part = activePart()
                val bpm = part?.tempoBpm ?: song.tempoBpm
                val tickDurationNs = (60_000_000_000.0 / (bpm * PPQN)).toLong().coerceAtLeast(1L)
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
                if (!running.get()) break

                // Count-in countdown before entering Recording
                val left = countInTicksLeft.get()
                if (left > 0) {
                    val remaining = countInTicksLeft.decrementAndGet()
                    val beatsLeft = ((remaining + PPQN - 1) / PPQN).toInt().coerceAtLeast(0)
                    if (remaining % PPQN == 0L || remaining == left - 1L) {
                        val pulse = countInPulseCounter.incrementAndGet()
                        _state.update {
                            it.copy(
                                transport = TransportState.CountIn,
                                countInBeatsRemaining = beatsLeft.coerceAtLeast(1),
                                countInPulse = pulse,
                                positionTicks = positionTicks.get(),
                            )
                        }
                    }
                    if (remaining <= 0) {
                        countInTicksLeft.set(0)
                        _state.update {
                            it.copy(
                                transport = TransportState.Recording,
                                countInBeatsRemaining = 0,
                            )
                        }
                    }
                    // Still play existing material during count-in
                    val tick = positionTicks.get()
                    scheduleTick(tick)
                    val partLen = part?.lengthTicks ?: Long.MAX_VALUE
                    val next = tick + 1
                    positionTicks.set(if (next >= partLen) 0 else next)
                    _state.update { it.copy(positionTicks = positionTicks.get()) }
                    continue
                }

                val tick = positionTicks.get()
                scheduleTick(tick)
                val partLen = part?.lengthTicks ?: Long.MAX_VALUE
                val next = tick + 1
                positionTicks.set(if (next >= partLen) 0 else next)
                val transport = _state.value.transport
                _state.update {
                    it.copy(
                        positionTicks = positionTicks.get(),
                        transport = if (transport == TransportState.CountIn) TransportState.Recording else transport,
                    )
                }
            }
        }
    }

    private fun scheduleTick(tick: Long) {
        val part = activePart() ?: return
        for (track in part.tracks) {
            if (track.muted) continue
            for (event in track.events) {
                val playTick = MidiPlayback.quantizeTick(event.tick, track.modifiers)
                if (playTick != tick) continue
                val shaped = MidiPlayback.applyModifiers(event, track.modifiers, track.outputChannel) ?: continue
                sendToTrackOutput(track, shaped, 0L)
            }
        }
    }

    private fun sendToTrackOutput(track: MidiTrack, event: MidiEvent, timestampNs: Long) {
        val bytes = MidiPlayback.eventToBytes(event) ?: return
        val port = resolveOutputPort(track.output) ?: return
        try {
            port.send(bytes, 0, bytes.size, timestampNs)
        } catch (_: Exception) {
            // Device may have disconnected mid-send
        }
    }

    private fun resolveOutputPort(endpoint: MidiEndpointRef): MidiInputPort? {
        if (endpoint.isSet) {
            outputPorts[portKey(endpoint.deviceId, endpoint.portIndex)]?.let { return it }
        }
        // Fallback: first open output
        return outputPorts.values.firstOrNull()
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
        for (port in outputPorts.values) {
            for (ch in 0..15) {
                message[0] = (0xB0 or ch).toByte()
                message[1] = 123 // CC All Notes Off
                message[2] = 0
                try {
                    port.send(message, 0, 3, 0)
                } catch (_: Exception) {
                }
                message[1] = 120 // All Sound Off
                try {
                    port.send(message, 0, 3, 0)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun openDeviceIfNeeded(info: MidiDeviceInfo) {
        if (midiManager == null) return
        if (openDevices.containsKey(info.id)) {
            ensurePortsOpen(info, openDevices[info.id] ?: return)
            return
        }
        midiManager.openDevice(info, { device ->
            if (device == null) return@openDevice
            openDevices[info.id] = device
            ensurePortsOpen(info, device)
            reconnectRecordInput()
        }, mainHandler)
    }

    private fun ensurePortsOpen(info: MidiDeviceInfo, device: MidiDevice) {
        for (port in info.ports) {
            val key = portKey(info.id, port.portNumber)
            when (port.type) {
                MidiDeviceInfo.PortInfo.TYPE_INPUT -> {
                    if (!outputPorts.containsKey(key)) {
                        try {
                            device.openInputPort(port.portNumber)?.let { outputPorts[key] = it }
                        } catch (_: Exception) {
                        }
                    }
                }
                MidiDeviceInfo.PortInfo.TYPE_OUTPUT -> {
                    if (!inputPorts.containsKey(key)) {
                        try {
                            device.openOutputPort(port.portNumber)?.let { outPort ->
                                inputPorts[key] = outPort
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }

    private fun reconnectRecordInput() {
        val endpoint = _state.value.recordInput.endpoint
        val targetKey = if (endpoint.isSet) {
            portKey(endpoint.deviceId, endpoint.portIndex)
        } else {
            _state.value.availableInputs.firstOrNull()?.let { portKey(it.deviceId, it.portIndex) }
        } ?: return

        for ((key, port) in inputPorts) {
            val existing = inputReceivers[key]
            if (key == targetKey) {
                if (existing == null) {
                    val receiver = object : MidiReceiver() {
                        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                            onIncomingMidi(msg, offset, count, timestamp)
                        }
                    }
                    try {
                        port.connect(receiver)
                        inputReceivers[key] = receiver
                    } catch (_: Exception) {
                    }
                }
            } else if (existing != null) {
                try {
                    port.disconnect(existing)
                } catch (_: Exception) {
                }
                inputReceivers.remove(key)
            }
        }
    }

    private fun closeDevice(deviceId: Int) {
        val keys = outputPorts.keys.filter { it.startsWith("$deviceId:") }
        for (key in keys) {
            try {
                outputPorts.remove(key)?.close()
            } catch (_: Exception) {
            }
        }
        val inKeys = inputPorts.keys.filter { it.startsWith("$deviceId:") }
        for (key in inKeys) {
            val receiver = inputReceivers.remove(key)
            val port = inputPorts.remove(key)
            try {
                if (receiver != null) port?.disconnect(receiver)
                port?.close()
            } catch (_: Exception) {
            }
        }
        try {
            openDevices.remove(deviceId)?.close()
        } catch (_: Exception) {
        }
    }

    private fun closeAllDevices() {
        val ids = openDevices.keys.toList()
        for (id in ids) closeDevice(id)
    }

    private fun preferDefaultInput(current: RecordInputConfig, inputs: List<PortInfo>): RecordInputConfig {
        if (inputs.isEmpty()) return current
        if (current.endpoint.isSet && inputs.any { it.deviceId == current.endpoint.deviceId && it.portIndex == current.endpoint.portIndex }) {
            return current
        }
        val preferred = inputs.firstOrNull { it.isModxFamily } ?: inputs.first()
        return current.copy(endpoint = preferred.toRef())
    }

    private fun ensureTrackOutputs(song: Song, outputs: List<PortInfo>): Song {
        if (outputs.isEmpty()) return song
        val preferred = outputs.firstOrNull { it.isModxFamily } ?: outputs.first()
        val defaultRef = preferred.toRef()
        return song.copy(
            parts = song.parts.map { part ->
                part.copy(
                    tracks = part.tracks.map { track ->
                        val out = if (track.output.isSet &&
                            outputs.any { it.deviceId == track.output.deviceId && it.portIndex == track.output.portIndex }
                        ) {
                            track.output
                        } else {
                            defaultRef
                        }
                        track.copy(output = out)
                    },
                )
            },
        )
    }

    private fun deviceDisplayName(info: MidiDeviceInfo): String {
        return info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "MIDI ${info.id}"
    }

    private fun portKey(deviceId: Int, portIndex: Int): String = "$deviceId:$portIndex"
}
