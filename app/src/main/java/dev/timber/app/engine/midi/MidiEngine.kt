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
 * Realtime MIDI scheduler / recorder. Clock thread owns musical time; UI observes [state].
 * Android: [MidiInputPort] = app writes (our out); [MidiOutputPort] = device writes (our in).
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
        val countInBeatsRemaining: Int = 0,
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
    private val outputPorts = ConcurrentHashMap<String, MidiInputPort>()
    private val inputPorts = ConcurrentHashMap<String, MidiOutputPort>()
    private val inputReceivers = ConcurrentHashMap<String, MidiReceiver>()

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = refreshDevices()

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
                    MidiDeviceInfo.PortInfo.TYPE_OUTPUT -> inputs += portInfo
                    MidiDeviceInfo.PortInfo.TYPE_INPUT -> outputs += portInfo
                }
            }
            openDeviceIfNeeded(info)
        }

        _state.update {
            it.copy(
                connectedDevices = names,
                availableInputs = inputs,
                availableOutputs = outputs,
                recordInput = preferDefaultInput(it.recordInput, inputs),
                song = ensureTrackOutputs(it.song, outputs),
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
        _state.update { it.copy(recordInput = it.recordInput.copy(endpoint = endpoint)) }
        reconnectRecordInput()
    }

    /** null = Omni */
    fun setRecordListenChannel(channel: Int?) {
        require(channel == null || channel in 1..16)
        _state.update { it.copy(recordInput = it.recordInput.copy(listenChannel = channel)) }
    }

    fun setTrackOutput(trackId: Int, endpoint: MidiEndpointRef, channel: Int) {
        val ch = channel.coerceIn(1, 16)
        updateActivePartTracks { track ->
            if (track.id == trackId) track.copy(output = endpoint, outputChannel = ch) else track
        }
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
            it.copy(transport = TransportState.Stopped, positionTicks = 0, countInBeatsRemaining = 0)
        }
        sendPanic()
    }

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
                val beats = _state.value.countInBeats.takeIf { it > 0 }
                    ?: (activePart()?.timeSignatureNumerator ?: 4)
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
        if (status >= 0xF8) return

        val (type, channel) = MidiPlayback.parseStatus(status)
        val d1 = if (count > 1) data[offset + 1].toInt() and 0xFF else 0
        val d2 = if (count > 2) data[offset + 2].toInt() and 0xFF else 0
        val normalizedType = if (type == MidiMessageType.NoteOn && d2 == 0) MidiMessageType.NoteOff else type

        val listen = snapshot.recordInput.listenChannel
        val channelOk = listen == null || channel == listen

        if (channelOk) {
            val part = activePart()
            val thruTrack = part?.tracks?.firstOrNull { it.armed } ?: part?.tracks?.firstOrNull()
            if (thruTrack != null && !thruTrack.muted) {
                val shaped = MidiPlayback.applyModifiers(
                    MidiEvent(0, normalizedType, channel, d1, d2),
                    thruTrack.modifiers,
                    thruTrack.outputChannel,
                )
                if (shaped != null) sendToTrackOutput(thruTrack, shaped, timestampNs)
            }
        }

        if (snapshot.transport != TransportState.Recording || !channelOk) return
        recordBuffer += MidiEvent(positionTicks.get(), normalizedType, channel, d1, d2)
    }

    fun activePart(): Part? {
        val song = _state.value.song
        return song.parts.find { it.id == _state.value.activePartId } ?: song.parts.firstOrNull()
    }

    fun armTrack(trackId: Int) {
        updateActivePartTracks { track -> track.copy(armed = track.id == trackId) }
    }

    fun toggleMute(trackId: Int) {
        updateActivePartTracks { track ->
            if (track.id == trackId) track.copy(muted = !track.muted) else track
        }
    }

    private fun updateActivePartTracks(transform: (MidiTrack) -> MidiTrack) {
        val song = _state.value.song
        val partId = _state.value.activePartId
        setSong(
            song.copy(
                parts = song.parts.map { part ->
                    if (part.id != partId) part
                    else part.copy(tracks = part.tracks.map(transform))
                },
            ),
        )
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
                val sleepNs = nextTickNs - System.nanoTime()
                if (sleepNs > 0) {
                    try {
                        Thread.sleep(sleepNs / 1_000_000, (sleepNs % 1_000_000).toInt())
                    } catch (_: InterruptedException) {
                        break
                    }
                }
                if (!running.get()) break

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
                            )
                        }
                    }
                    if (remaining <= 0) {
                        countInTicksLeft.set(0)
                        _state.update {
                            it.copy(transport = TransportState.Recording, countInBeatsRemaining = 0)
                        }
                    }
                }

                val tick = positionTicks.get()
                scheduleTick(tick)
                val partLen = part?.lengthTicks ?: Long.MAX_VALUE
                positionTicks.set(if (tick + 1 >= partLen) 0 else tick + 1)
                _state.update { it.copy(positionTicks = positionTicks.get()) }
            }
        }
    }

    private fun scheduleTick(tick: Long) {
        val part = activePart() ?: return
        for (track in part.tracks) {
            if (track.muted) continue
            for (event in track.events) {
                if (MidiPlayback.quantizeTick(event.tick, track.modifiers) != tick) continue
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
        }
    }

    private fun resolveOutputPort(endpoint: MidiEndpointRef): MidiInputPort? {
        if (endpoint.isSet) {
            outputPorts[portKey(endpoint.deviceId, endpoint.portIndex)]?.let { return it }
        }
        return outputPorts.values.firstOrNull()
    }

    private fun commitRecording() {
        val part = activePart() ?: return
        val armed = part.tracks.firstOrNull { it.armed } ?: part.tracks.firstOrNull() ?: return
        val merged = (armed.events + recordBuffer).sortedBy { it.tick }
        recordBuffer.clear()
        setSong(
            _state.value.song.copy(
                parts = _state.value.song.parts.map { p ->
                    if (p.id != part.id) p
                    else p.copy(
                        tracks = p.tracks.map { t ->
                            if (t.id == armed.id) t.copy(events = merged) else t
                        },
                    )
                },
            ),
        )
    }

    private fun sendPanic() {
        val message = ByteArray(3)
        for (port in outputPorts.values) {
            for (ch in 0..15) {
                message[0] = (0xB0 or ch).toByte()
                message[2] = 0
                for (cc in intArrayOf(123, 120)) {
                    message[1] = cc.toByte()
                    try {
                        port.send(message, 0, 3, 0)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private fun openDeviceIfNeeded(info: MidiDeviceInfo) {
        if (midiManager == null) return
        openDevices[info.id]?.let {
            ensurePortsOpen(info, it)
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
                            device.openOutputPort(port.portNumber)?.let { inputPorts[key] = it }
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
        outputPorts.keys.filter { it.startsWith("$deviceId:") }.forEach { key ->
            try {
                outputPorts.remove(key)?.close()
            } catch (_: Exception) {
            }
        }
        inputPorts.keys.filter { it.startsWith("$deviceId:") }.forEach { key ->
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
        openDevices.keys.toList().forEach { closeDevice(it) }
    }

    private fun preferDefaultInput(current: RecordInputConfig, inputs: List<PortInfo>): RecordInputConfig {
        if (inputs.isEmpty()) return current
        if (current.endpoint.isSet &&
            inputs.any { it.deviceId == current.endpoint.deviceId && it.portIndex == current.endpoint.portIndex }
        ) {
            return current
        }
        val preferred = inputs.firstOrNull { it.isModxFamily } ?: inputs.first()
        return current.copy(endpoint = preferred.toRef())
    }

    private fun ensureTrackOutputs(song: Song, outputs: List<PortInfo>): Song {
        if (outputs.isEmpty()) return song
        val defaultRef = (outputs.firstOrNull { it.isModxFamily } ?: outputs.first()).toRef()
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

    private fun deviceDisplayName(info: MidiDeviceInfo): String =
        info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "MIDI ${info.id}"

    private fun portKey(deviceId: Int, portIndex: Int): String = "$deviceId:$portIndex"
}
