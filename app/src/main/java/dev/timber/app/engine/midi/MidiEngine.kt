package dev.timber.app.engine.midi

import android.content.SharedPreferences
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
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
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Realtime MIDI scheduler / recorder. Clock thread owns musical time; UI observes [state].
 * Android: [MidiInputPort] = app writes (our out); [MidiOutputPort] = device writes (our in).
 *
 * Enumerates both MIDI 1.0 byte-stream and Universal MIDI Packet (UMP / MIDI 2.0) transports.
 *
 * Important: a USB MIDI 2.0 gadget may only be opened as **either** MIDI 1.0 **or** UMP at once
 * (Android alternate-setting exclusivity). Opening both for the same physical device crashes
 * on some SDK 37 stacks — see [UmpOpenGuard] + exclusive family open.
 */
class MidiEngine(
    private val midiManager: MidiManager?,
    umpPrefs: SharedPreferences? = null,
) {
    private val umpGuard = UmpOpenGuard(umpPrefs)
    data class PortInfo(
        val deviceId: Int,
        val portIndex: Int,
        val deviceName: String,
        val productName: String,
        val portName: String?,
        val isModxFamily: Boolean,
        val isUmp: Boolean = false,
        val protocol: Int = MidiDeviceInfoProtocol.PROTOCOL_UNKNOWN,
        val isProbeApp: Boolean = false,
    ) {
        val label: String
            get() = buildString {
                append(deviceName)
                if (isUmp) append(" · UMP")
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
    private val mainExecutor = Executor { mainHandler.post(it) }

    private val openDevices = ConcurrentHashMap<Int, MidiDevice>()
    private val pendingOpenDeviceIds = ConcurrentHashMap.newKeySet<Int>()
    private val openGeneration = ConcurrentHashMap<Int, Int>()
    private val outputPorts = ConcurrentHashMap<String, MidiInputPort>()
    private val inputPorts = ConcurrentHashMap<String, MidiOutputPort>()
    private val inputReceivers = ConcurrentHashMap<String, MidiReceiver>()
    private val inputPortInfo = ConcurrentHashMap<String, PortInfo>()
    private val umpDeviceIds = ConcurrentHashMap.newKeySet<Int>()
    private val nullOpenLogged = ConcurrentHashMap.newKeySet<String>()
    private val lastClockLogMs = ConcurrentHashMap<String, Long>()
    /** deviceId → (MidiDeviceInfo, isUmp, familyKey) from last refresh. */
    private val listedDevices = ConcurrentHashMap<Int, ListedDevice>()
    /** familyKey → want UMP open (exclusive vs MIDI1). */
    private val familyWantUmp = ConcurrentHashMap<String, Boolean>()

    private data class ListedDevice(
        val info: MidiDeviceInfo,
        val isUmp: Boolean,
        val familyKey: String,
        val isModxFamily: Boolean,
    )

    private fun deviceCallback(expectedUmp: Boolean?) = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            val ump = expectedUmp ?: isUmpDevice(device)
            MidiDebugLog.i(
                "deviceAdded id=${device.id} name=\"${deviceDisplayName(device)}\" " +
                    "product=\"${deviceProductName(device)}\" transport=${transportLabel(ump)} " +
                    "protocol=${UmpCodec.protocolLabel(safeProtocol(device))} " +
                    "inPorts=${device.outputPortCount} outPorts=${device.inputPortCount}",
            )
            refreshDevices()
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            val ump = expectedUmp ?: isUmpDevice(device)
            MidiDebugLog.i(
                "deviceRemoved id=${device.id} name=\"${deviceDisplayName(device)}\" " +
                    "transport=${transportLabel(ump)}",
            )
            closeDevice(device.id)
            refreshDevices()
        }
    }

    private val midi1DeviceCallback = deviceCallback(expectedUmp = false)
    private val umpDeviceCallback = deviceCallback(expectedUmp = true)
    private val legacyDeviceCallback = deviceCallback(expectedUmp = null)

    init {
        MidiDebugLog.i(
            "MidiEngine init debugLogging=${MidiDebugLog.enabled} sdk=${Build.VERSION.SDK_INT}",
        )
        umpGuard.consumePreviousCrash()?.let { family ->
            MidiDebugLog.e(
                "UMP open previously crashed for family=\"$family\" — " +
                    "blocking UMP auto-open; using MIDI1 exclusive for that family. " +
                    "Clear app data to retry UMP.",
            )
        }
        registerCallbacks()
        refreshDevices()
    }

    fun release() {
        unregisterCallbacks()
        stop()
        closeAllDevices()
    }

    fun refreshDevices() {
        val devices = listAllDevices()
        val names = linkedSetOf<String>()
        val inputs = mutableListOf<PortInfo>()
        val outputs = mutableListOf<PortInfo>()
        val seenIds = HashSet<Int>()
        listedDevices.clear()

        for ((info, isUmp) in devices) {
            seenIds += info.id
            val name = deviceDisplayName(info)
            val product = deviceProductName(info)
            names += if (isUmp) "$name (UMP)" else name
            val isModx = ModxMProfile.looksLikeModxFamily(name, null) ||
                ModxMProfile.looksLikeModxFamily(product, null)
            val probe = isProbeAppName(name) || isProbeAppName(product)
            val protocol = safeProtocol(info)
            val family = deviceFamilyKey(info, name)
            if (isUmp) umpDeviceIds.add(info.id) else umpDeviceIds.remove(info.id)
            listedDevices[info.id] = ListedDevice(info, isUmp, family, isModx)

            for (port in info.ports) {
                val portInfo = PortInfo(
                    deviceId = info.id,
                    portIndex = port.portNumber,
                    deviceName = name,
                    productName = product,
                    portName = port.name,
                    isModxFamily = isModx,
                    isUmp = isUmp,
                    protocol = protocol,
                    isProbeApp = probe,
                )
                when (port.type) {
                    MidiDeviceInfo.PortInfo.TYPE_OUTPUT -> {
                        inputs += portInfo
                        inputPortInfo[portKey(info.id, port.portNumber)] = portInfo
                    }
                    MidiDeviceInfo.PortInfo.TYPE_INPUT -> outputs += portInfo
                }
            }
        }

        // Resolve exclusive open mode per physical device family, then open only that transport.
        val families = listedDevices.values.map { it.familyKey }.toSet()
        for (family in families) {
            val wantUmp = resolveWantUmp(family)
            familyWantUmp[family] = wantUmp
            enforceExclusiveFamilyOpen(family, wantUmp)
        }

        // Drop stale open devices that disappeared from both transports.
        openDevices.keys.filter { it !in seenIds }.forEach { closeDevice(it) }

        val umpInputs = inputs.filter { it.isUmp }
        val modxUmpInputs = umpInputs.filter { it.isModxFamily }
        val modxMidi1Inputs = inputs.filter { it.isModxFamily && !it.isUmp }
        MidiDebugLog.i(
            "refreshDevices listed=${devices.size} inputs=${inputs.size} outputs=${outputs.size} " +
                "names=$names observeAllInputs=${MidiDebugLog.enabled} " +
                "umpDevices=${umpDeviceIds.sorted()}",
        )
        if (modxUmpInputs.isEmpty()) {
            MidiDebugLog.w(
                "MODX UMP: NONE enumerated " +
                    "(umpInputPorts=${umpInputs.size} modxMidi1Ports=${modxMidi1Inputs.size}). " +
                    "If notes stay silent on MIDI1, keyboard events may be on a missing UMP device.",
            )
        } else {
            val family = listedDevices[modxUmpInputs.first().deviceId]?.familyKey
            val mode = if (family != null && familyWantUmp[family] == true) "OPEN_UMP" else "MIDI1_EXCLUSIVE"
            val blocked = family != null && umpGuard.isBlocked(family)
            MidiDebugLog.i(
                "MODX UMP: YES count=${modxUmpInputs.size} openMode=$mode blocked=$blocked " +
                    modxUmpInputs.joinToString { "id=${it.deviceId}:port=${it.portIndex}(${it.portName})" },
            )
        }
        for (port in inputs) {
            MidiDebugLog.i(
                "availableIn product=\"${port.productName}\" device=\"${port.deviceName}\" " +
                    "id=${port.deviceId} port=${port.portIndex} name=\"${port.portName}\" " +
                    "modx=${port.isModxFamily} ump=${port.isUmp} " +
                    "protocol=${UmpCodec.protocolLabel(port.protocol)} probe=${port.isProbeApp}",
            )
        }
        for (port in outputs) {
            MidiDebugLog.i(
                "availableOut product=\"${port.productName}\" device=\"${port.deviceName}\" " +
                    "id=${port.deviceId} port=${port.portIndex} name=\"${port.portName}\" " +
                    "modx=${port.isModxFamily} ump=${port.isUmp} " +
                    "protocol=${UmpCodec.protocolLabel(port.protocol)} probe=${port.isProbeApp}",
            )
        }

        _state.update {
            it.copy(
                connectedDevices = names.toList(),
                availableInputs = inputs,
                availableOutputs = outputs,
                recordInput = preferDefaultInput(it.recordInput, inputs),
                song = ensureTrackOutputs(it.song, outputs),
            )
        }
        reconnectInputReceivers()
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
        val listed = listedDevices[endpoint.deviceId]
        MidiDebugLog.i(
            "setRecordInput ${endpoint.displayName} id=${endpoint.deviceId} port=${endpoint.portIndex} " +
                "ump=${listed?.isUmp == true} family=${listed?.familyKey}",
        )
        if (listed != null) {
            familyWantUmp[listed.familyKey] = listed.isUmp
            enforceExclusiveFamilyOpen(listed.familyKey, wantUmp = listed.isUmp)
        }
        reconnectInputReceivers()
    }

    /** null = Omni */
    fun setRecordListenChannel(channel: Int?) {
        require(channel == null || channel in 1..16)
        _state.update { it.copy(recordInput = it.recordInput.copy(listenChannel = channel)) }
        MidiDebugLog.i("setRecordListenChannel ${channel ?: "Omni"}")
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
                MidiDebugLog.i("transport=Playing (resume)")
            }
            return
        }
        _state.update { it.copy(transport = TransportState.Playing, countInBeatsRemaining = 0) }
        MidiDebugLog.i("transport=Playing")
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
        MidiDebugLog.i("transport=Stopped")
        sendPanic()
    }

    fun toggleRecord() {
        when (_state.value.transport) {
            TransportState.Recording -> {
                commitRecording()
                _state.update { it.copy(transport = TransportState.Playing, countInBeatsRemaining = 0) }
                MidiDebugLog.i("transport=Playing (punch out) recordedEvents committed")
            }
            TransportState.CountIn -> {
                countInTicksLeft.set(0)
                _state.update { it.copy(transport = TransportState.Playing, countInBeatsRemaining = 0) }
                MidiDebugLog.i("transport=Playing (count-in cancelled)")
            }
            TransportState.Playing, TransportState.Stopped -> {
                recordBuffer.clear()
                val beats = _state.value.countInBeats.takeIf { it > 0 }
                    ?: (activePart()?.timeSignatureNumerator ?: 4)
                if (beats <= 0) {
                    if (!running.get()) play()
                    _state.update { it.copy(transport = TransportState.Recording, countInBeatsRemaining = 0) }
                    MidiDebugLog.i("transport=Recording (no count-in)")
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
                MidiDebugLog.i("transport=CountIn beats=$beats")
                if (!running.get()) {
                    running.set(true)
                    startClock()
                }
            }
        }
    }

    fun onIncomingMidi(
        data: ByteArray,
        offset: Int,
        count: Int,
        timestampNs: Long,
        portKey: String,
        isSelectedRecordPort: Boolean,
    ) {
        val info = inputPortInfo[portKey]
        val deviceIdFromKey = portKey.substringBefore(':').toIntOrNull()
        val isUmpPort = info?.isUmp == true ||
            (deviceIdFromKey != null && umpDeviceIds.contains(deviceIdFromKey))

        if (count <= 0) {
            MidiDebugLog.i("IN empty count=$count port=$portKey ump=$isUmpPort tNs=$timestampNs selectedIn=$isSelectedRecordPort")
            return
        }

        // Copy immediately — MidiReceiver msg buffer is only valid during onSend.
        val copy = data.copyOfRange(offset, offset + count)

        if (isUmpPort || UmpCodec.looksLikeUmp(copy, 0, copy.size)) {
            handleIncomingUmp(copy, timestampNs, portKey, isSelectedRecordPort, info)
            return
        }

        handleIncomingMidi1(copy, timestampNs, portKey, isSelectedRecordPort, info)
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

    private fun handleIncomingMidi1(
        data: ByteArray,
        timestampNs: Long,
        portKey: String,
        isSelectedRecordPort: Boolean,
        info: PortInfo?,
    ) {
        val status = data[0].toInt() and 0xFF
        if (status >= 0xF8) {
            logRealtimeOrClock(portKey, info, status, data, timestampNs, isSelectedRecordPort, ump = false)
            return
        }

        val (type, channel) = MidiPlayback.parseStatus(status)
        val d1 = if (data.size > 1) data[1].toInt() and 0xFF else 0
        val d2 = if (data.size > 2) data[2].toInt() and 0xFF else 0
        val normalizedType = if (type == MidiMessageType.NoteOn && d2 == 0) MidiMessageType.NoteOff else type
        processChannelVoice(
            normalizedType, channel, d1, d2,
            MidiDebugFormat.describeType(normalizedType, d1, d2),
            status, data, timestampNs, portKey, isSelectedRecordPort, info, ump = false,
        )
    }

    private fun handleIncomingUmp(
        data: ByteArray,
        timestampNs: Long,
        portKey: String,
        isSelectedRecordPort: Boolean,
        info: PortInfo?,
    ) {
        val decoded = UmpCodec.decodePackets(data, 0, data.size)
        if (decoded.isEmpty()) {
            MidiDebugLog.i(
                "IN UMP undecoded port=$portKey bytes=[${MidiDebugFormat.formatBytes(data, 0, data.size)}] " +
                    "tNs=$timestampNs selectedIn=$isSelectedRecordPort",
            )
            return
        }
        for (packet in decoded) {
            // Utility JR clock etc. — log throttled like MIDI 1 clock.
            if (packet.messageType == UmpCodec.MT_UTILITY) {
                logThrottled(
                    portKey,
                    "IN UMP Utility port=$portKey bytes=[${MidiDebugFormat.formatBytes(data, 0, minOf(4, data.size))}] " +
                        "tNs=$timestampNs selectedIn=$isSelectedRecordPort",
                )
                continue
            }
            processChannelVoice(
                packet.type, packet.channel, packet.data1, packet.data2,
                packet.label,
                data[0].toInt() and 0xFF, data, timestampNs, portKey, isSelectedRecordPort, info, ump = true,
            )
        }
    }

    private fun processChannelVoice(
        type: MidiMessageType,
        channel: Int,
        d1: Int,
        d2: Int,
        typeLabel: String,
        status: Int,
        data: ByteArray,
        timestampNs: Long,
        portKey: String,
        isSelectedRecordPort: Boolean,
        info: PortInfo?,
        ump: Boolean,
    ) {
        val snapshot = _state.value
        val listen = snapshot.recordInput.listenChannel
        val channelOk = listen == null || channel == listen
        val wouldRecord =
            isSelectedRecordPort &&
                channelOk &&
                snapshot.transport == TransportState.Recording &&
                type != MidiMessageType.Other &&
                type != MidiMessageType.SysEx

        if (MidiDebugLog.enabled) {
            MidiDebugLog.i(
                MidiDebugFormat.formatIncoming(
                    productName = info?.productName.orEmpty(),
                    deviceName = info?.deviceName ?: "?",
                    deviceId = info?.deviceId ?: portKey.substringBefore(':').toIntOrNull() ?: -1,
                    portIndex = info?.portIndex ?: portKey.substringAfter(':').toIntOrNull() ?: -1,
                    portName = buildString {
                        if (ump) append("UMP/")
                        append(info?.portName ?: "port")
                    },
                    timestampNs = timestampNs,
                    status = status,
                    dataBytes = MidiDebugFormat.formatBytes(data, 0, data.size),
                    typeLabel = typeLabel,
                    channel = channel,
                    listenChannel = listen,
                    channelOk = channelOk,
                    isSelectedRecordPort = isSelectedRecordPort,
                    transport = snapshot.transport,
                    wouldRecord = wouldRecord,
                ),
            )
        }

        if (!isSelectedRecordPort) return
        if (type == MidiMessageType.Other || type == MidiMessageType.SysEx) return

        if (channelOk) {
            val part = activePart()
            val thruTrack = part?.tracks?.firstOrNull { it.armed } ?: part?.tracks?.firstOrNull()
            if (thruTrack != null && !thruTrack.muted) {
                val shaped = MidiPlayback.applyModifiers(
                    MidiEvent(0, type, channel, d1, d2),
                    thruTrack.modifiers,
                    thruTrack.outputChannel,
                )
                if (shaped != null) sendToTrackOutput(thruTrack, shaped, timestampNs)
            }
        }

        if (snapshot.transport != TransportState.Recording || !channelOk) return
        recordBuffer += MidiEvent(positionTicks.get(), type, channel, d1, d2)
    }

    private fun logRealtimeOrClock(
        portKey: String,
        info: PortInfo?,
        status: Int,
        data: ByteArray,
        timestampNs: Long,
        isSelectedRecordPort: Boolean,
        ump: Boolean,
    ) {
        if (!MidiDebugLog.enabled) return
        val label = when (status) {
            0xF8 -> "Clock"
            0xFA -> "Start"
            0xFB -> "Continue"
            0xFC -> "Stop"
            0xFE -> "ActiveSense"
            0xFF -> "Reset"
            else -> "Realtime"
        }
        val line =
            "IN $label status=0x${"%02X".format(status)} port=$portKey " +
                "device=\"${info?.deviceName}\" ump=$ump bytes=[${MidiDebugFormat.formatBytes(data, 0, data.size)}] " +
                "tNs=$timestampNs selectedIn=$isSelectedRecordPort"
        if (status == 0xF8 || status == 0xFE) {
            logThrottled(portKey, line)
        } else {
            MidiDebugLog.i(line)
        }
    }

    private fun logThrottled(portKey: String, line: String) {
        val now = System.currentTimeMillis()
        val last = lastClockLogMs[portKey] ?: 0L
        if (now - last < 1000L) return
        lastClockLogMs[portKey] = now
        MidiDebugLog.i("$line (throttled 1/s)")
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
                        MidiDebugLog.i("transport=Recording (count-in done)")
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
        val midi1 = MidiPlayback.eventToBytes(event) ?: return
        val port = resolveOutputPort(track.output) ?: return
        val key = if (track.output.isSet) {
            portKey(track.output.deviceId, track.output.portIndex)
        } else {
            outputPorts.entries.firstOrNull { it.value === port }?.key
        }
        val deviceId = key?.substringBefore(':')?.toIntOrNull()
        val bytes = if (deviceId != null && umpDeviceIds.contains(deviceId)) {
            val status = midi1[0].toInt() and 0xFF
            val d1 = if (midi1.size > 1) midi1[1].toInt() and 0xFF else 0
            val d2 = if (midi1.size > 2) midi1[2].toInt() and 0xFF else 0
            UmpCodec.encodeMidi1ChannelVoiceUmp(status, d1, d2)
        } else {
            midi1
        }
        try {
            port.send(bytes, 0, bytes.size, timestampNs)
        } catch (t: Exception) {
            MidiDebugLog.w("send failed key=$key", t)
        }
    }

    private fun resolveOutputPort(endpoint: MidiEndpointRef): MidiInputPort? {
        if (endpoint.isSet) {
            outputPorts[portKey(endpoint.deviceId, endpoint.portIndex)]?.let { return it }
        }
        // Prefer MODX non-probe outs (UMP first).
        val preferred = _state.value.availableOutputs
            .filter { !it.isProbeApp }
            .sortedWith(compareByDescending<PortInfo> { it.isModxFamily }.thenByDescending { it.isUmp })
            .firstOrNull()
        if (preferred != null) {
            outputPorts[portKey(preferred.deviceId, preferred.portIndex)]?.let { return it }
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
        for ((key, port) in outputPorts) {
            val deviceId = key.substringBefore(':').toIntOrNull()
            val ump = deviceId != null && umpDeviceIds.contains(deviceId)
            for (ch in 0..15) {
                for (cc in intArrayOf(123, 120)) {
                    val status = 0xB0 or ch
                    val bytes = if (ump) {
                        UmpCodec.encodeMidi1ChannelVoiceUmp(status, cc, 0)
                    } else {
                        byteArrayOf(status.toByte(), cc.toByte(), 0)
                    }
                    try {
                        port.send(bytes, 0, bytes.size, 0)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    /**
     * USB MIDI 2.0 devices expose MIDI1 + UMP alternate settings; only one may be open.
     * Opening both for the same physical MODX was crashing Timber on SDK 37 before openDevice OK.
     */
    private fun enforceExclusiveFamilyOpen(familyKey: String, wantUmp: Boolean) {
        val members = listedDevices.values.filter { it.familyKey == familyKey }
        if (members.isEmpty()) return

        // Close the transport we do not want (and any pending opens).
        for (member in members) {
            if (member.isUmp == wantUmp) continue
            if (openDevices.containsKey(member.info.id) || pendingOpenDeviceIds.contains(member.info.id)) {
                MidiDebugLog.i(
                    "exclusiveClose id=${member.info.id} transport=${transportLabel(member.isUmp)} " +
                        "family=\"$familyKey\" wantUmp=$wantUmp",
                )
                closeDevice(member.info.id)
            }
        }

        val targets = members.filter { it.isUmp == wantUmp }
        if (targets.isEmpty()) {
            MidiDebugLog.w("exclusiveOpen no ${transportLabel(wantUmp)} member for family=\"$familyKey\"")
            return
        }
        for (target in targets) {
            openDeviceIfNeeded(target.info, target.isUmp, familyKey)
        }
    }

    private fun resolveWantUmp(familyKey: String): Boolean {
        val members = listedDevices.values.filter { it.familyKey == familyKey }
        val hasUmp = members.any { it.isUmp }
        val hasMidi1 = members.any { !it.isUmp }
        val isModx = members.any { it.isModxFamily }
        val blocked = umpGuard.isBlocked(familyKey)
        val selected = _state.value.recordInput.endpoint
        val selectedListed = listedDevices[selected.deviceId]

        // Explicit user selection wins (unless UMP blocked — then force MIDI1).
        if (selected.isSet && selectedListed?.familyKey == familyKey) {
            if (selectedListed.isUmp && blocked) {
                MidiDebugLog.w(
                    "record-in is UMP but family=\"$familyKey\" blocked after crash — falling back to MIDI1",
                )
                return false
            }
            return selectedListed.isUmp
        }

        // Sticky choice from prior selection / refresh.
        familyWantUmp[familyKey]?.let { previous ->
            if (previous && (!hasUmp || blocked)) return false
            if (!previous && !hasMidi1 && hasUmp && !blocked) return true
            return previous
        }

        // Default: MODX prefers UMP (notes were silent on MIDI1) unless crash-blocked.
        if (isModx && hasUmp && !blocked) return true
        return false
    }

    private fun openDeviceIfNeeded(info: MidiDeviceInfo, isUmp: Boolean, familyKey: String) {
        if (midiManager == null) return
        openDevices[info.id]?.let { device ->
            // Already open — only ensure missing ports; never thrash reopen.
            ensurePortsOpen(info, device, isUmp)
            return
        }
        if (!pendingOpenDeviceIds.add(info.id)) {
            MidiDebugLog.i("openDevice already pending id=${info.id} transport=${transportLabel(isUmp)}")
            return
        }
        val generation = (openGeneration[info.id] ?: 0) + 1
        openGeneration[info.id] = generation
        if (isUmp) {
            umpGuard.beginAttempt(familyKey)
        }
        MidiDebugLog.i(
            "openDevice request id=${info.id} name=\"${deviceDisplayName(info)}\" " +
                "transport=${transportLabel(isUmp)} protocol=${UmpCodec.protocolLabel(safeProtocol(info))} " +
                "family=\"$familyKey\" exclusive=${transportLabel(isUmp)} gen=$generation",
        )
        try {
            midiManager.openDevice(info, { device ->
                try {
                    pendingOpenDeviceIds.remove(info.id)
                    if (openGeneration[info.id] != generation) {
                        MidiDebugLog.w("openDevice stale callback ignored id=${info.id} gen=$generation")
                        try {
                            device?.close()
                        } catch (_: Exception) {
                        }
                        if (isUmp) umpGuard.clearAttempt()
                        return@openDevice
                    }
                    if (device == null) {
                        MidiDebugLog.e(
                            "openDevice FAILED (null) id=${info.id} name=\"${deviceDisplayName(info)}\" " +
                                "transport=${transportLabel(isUmp)} family=\"$familyKey\" " +
                                "— USB permission, exclusive lock, or sibling still open?",
                        )
                        if (isUmp) {
                            umpGuard.clearAttempt()
                            // Fall back to MIDI1 for this family.
                            familyWantUmp[familyKey] = false
                            mainHandler.post {
                                enforceExclusiveFamilyOpen(familyKey, wantUmp = false)
                            }
                        }
                        return@openDevice
                    }
                    MidiDebugLog.i(
                        "openDevice OK id=${info.id} name=\"${deviceDisplayName(info)}\" " +
                            "transport=${transportLabel(isUmp)} family=\"$familyKey\"",
                    )
                    if (isUmp) umpGuard.clearAttempt()
                    openDevices[info.id] = device
                    ensurePortsOpen(info, device, isUmp)
                    reconnectInputReceivers()
                } catch (t: Exception) {
                    if (isUmp) umpGuard.clearAttempt()
                    MidiDebugLog.e("openDevice callback crashed id=${info.id} ump=$isUmp", t)
                    if (isUmp) {
                        familyWantUmp[familyKey] = false
                        mainHandler.post {
                            closeDevice(info.id)
                            enforceExclusiveFamilyOpen(familyKey, wantUmp = false)
                        }
                    }
                }
            }, mainHandler)
        } catch (t: Exception) {
            pendingOpenDeviceIds.remove(info.id)
            if (isUmp) umpGuard.clearAttempt()
            MidiDebugLog.e("openDevice threw id=${info.id} ump=$isUmp", t)
            if (isUmp) {
                familyWantUmp[familyKey] = false
                enforceExclusiveFamilyOpen(familyKey, wantUmp = false)
            }
        }
    }

    private fun ensurePortsOpen(info: MidiDeviceInfo, device: MidiDevice, isUmp: Boolean) {
        for (port in info.ports) {
            val key = portKey(info.id, port.portNumber)
            when (port.type) {
                MidiDeviceInfo.PortInfo.TYPE_INPUT -> {
                    if (outputPorts.containsKey(key)) continue
                    try {
                        val opened = device.openInputPort(port.portNumber)
                        if (opened != null) {
                            outputPorts[key] = opened
                            nullOpenLogged.remove("out:$key")
                            MidiDebugLog.i(
                                "openedOutPort $key name=\"${port.name}\" transport=${transportLabel(isUmp)}",
                            )
                        } else if (nullOpenLogged.add("out:$key")) {
                            MidiDebugLog.w(
                                "openInputPort returned null $key name=\"${port.name}\" " +
                                    "transport=${transportLabel(isUmp)} " +
                                    "(port busy — another app or already open elsewhere; not retry-spamming)",
                            )
                        }
                    } catch (t: Exception) {
                        MidiDebugLog.e("openInputPort failed $key", t)
                    }
                }
                MidiDeviceInfo.PortInfo.TYPE_OUTPUT -> {
                    if (inputPorts.containsKey(key)) continue
                    try {
                        val opened = device.openOutputPort(port.portNumber)
                        if (opened != null) {
                            inputPorts[key] = opened
                            nullOpenLogged.remove("in:$key")
                            MidiDebugLog.i(
                                "openedInPort $key name=\"${port.name}\" transport=${transportLabel(isUmp)}",
                            )
                        } else if (nullOpenLogged.add("in:$key")) {
                            MidiDebugLog.w(
                                "openOutputPort returned null $key name=\"${port.name}\" " +
                                    "transport=${transportLabel(isUmp)} " +
                                    "(port busy — another app or already open elsewhere; not retry-spamming)",
                            )
                        }
                    } catch (t: Exception) {
                        MidiDebugLog.e("openOutputPort failed $key", t)
                    }
                }
            }
        }
    }

    /**
     * Attach receivers. Release: selected record-in only.
     * Debug: every open input port so logcat shows which device/port actually emits.
     */
    private fun reconnectInputReceivers() {
        val targetKey = selectedRecordPortKey()
        val observeAll = MidiDebugLog.enabled
        MidiDebugLog.i(
            "reconnectInputReceivers target=${targetKey ?: "none"} " +
                "openInputs=${inputPorts.keys.sorted()} observeAll=$observeAll " +
                "listen=${_state.value.recordInput.listenChannel ?: "Omni"} " +
                "umpDevices=${umpDeviceIds.sorted()}",
        )

        if (targetKey == null && !observeAll) return

        for ((key, port) in inputPorts) {
            val existing = inputReceivers[key]
            val shouldListen = observeAll || key == targetKey
            if (shouldListen) {
                if (existing == null) {
                    val selectedAtConnect = key == targetKey
                    val receiver = object : MidiReceiver() {
                        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                            try {
                                val selectedNow = key == selectedRecordPortKey()
                                onIncomingMidi(msg, offset, count, timestamp, key, selectedNow)
                            } catch (t: Exception) {
                                MidiDebugLog.e("onSend handler crashed $key", t)
                            }
                        }
                    }
                    try {
                        port.connect(receiver)
                        inputReceivers[key] = receiver
                        val meta = inputPortInfo[key]
                        MidiDebugLog.i(
                            "connectedReceiver $key selected=$selectedAtConnect " +
                                "ump=${meta?.isUmp == true} info=${meta?.label}",
                        )
                    } catch (t: Exception) {
                        MidiDebugLog.e("connectReceiver failed $key", t)
                    }
                }
            } else if (existing != null) {
                try {
                    port.disconnect(existing)
                    MidiDebugLog.i("disconnectedReceiver $key")
                } catch (t: Exception) {
                    MidiDebugLog.w("disconnectReceiver failed $key", t)
                }
                inputReceivers.remove(key)
            }
        }

        if (targetKey != null && !inputPorts.containsKey(targetKey)) {
            MidiDebugLog.w(
                "selected record-in $targetKey not open yet " +
                    "(openInputs=${inputPorts.keys.sorted()}) — waiting for openDevice",
            )
        }
    }

    private fun selectedRecordPortKey(): String? {
        val endpoint = _state.value.recordInput.endpoint
        return if (endpoint.isSet) {
            portKey(endpoint.deviceId, endpoint.portIndex)
        } else {
            _state.value.availableInputs
                .filter { !it.isProbeApp }
                .let { clean ->
                    clean.firstOrNull { it.isModxFamily && it.isUmp }
                        ?: clean.firstOrNull { it.isModxFamily }
                        ?: clean.firstOrNull()
                }
                ?.let { portKey(it.deviceId, it.portIndex) }
        }
    }

    private fun closeDevice(deviceId: Int) {
        openGeneration[deviceId] = (openGeneration[deviceId] ?: 0) + 1
        pendingOpenDeviceIds.remove(deviceId)
        outputPorts.keys.filter { it.startsWith("$deviceId:") }.forEach { key ->
            try {
                outputPorts.remove(key)?.close()
            } catch (_: Exception) {
            }
            nullOpenLogged.remove("out:$key")
        }
        inputPorts.keys.filter { it.startsWith("$deviceId:") }.forEach { key ->
            val receiver = inputReceivers.remove(key)
            val port = inputPorts.remove(key)
            inputPortInfo.remove(key)
            nullOpenLogged.remove("in:$key")
            lastClockLogMs.remove(key)
            try {
                if (receiver != null) port?.disconnect(receiver)
                port?.close()
            } catch (_: Exception) {
            }
        }
        umpDeviceIds.remove(deviceId)
        try {
            openDevices.remove(deviceId)?.close()
        } catch (_: Exception) {
        }
        MidiDebugLog.i("closeDevice id=$deviceId")
    }

    private fun closeAllDevices() {
        openDevices.keys.toList().forEach { closeDevice(it) }
    }

    private fun preferDefaultInput(current: RecordInputConfig, inputs: List<PortInfo>): RecordInputConfig {
        if (inputs.isEmpty()) return current
        val currentPort = inputs.firstOrNull {
            it.deviceId == current.endpoint.deviceId && it.portIndex == current.endpoint.portIndex
        }
        val currentFamily = listedDevices[current.endpoint.deviceId]?.familyKey
        val currentUmpBlocked = currentPort?.isUmp == true &&
            currentFamily != null &&
            umpGuard.isBlocked(currentFamily)

        fun umpAllowed(port: PortInfo): Boolean {
            val family = listedDevices[port.deviceId]?.familyKey ?: return !port.isUmp
            return !port.isUmp || !umpGuard.isBlocked(family)
        }

        val modxUmpExists = inputs.any { it.isModxFamily && it.isUmp && !it.isProbeApp && umpAllowed(it) }
        val modxExists = inputs.any { it.isModxFamily && !it.isProbeApp }
        val keepCurrent = current.endpoint.isSet &&
            currentPort != null &&
            !currentPort.isProbeApp &&
            !currentUmpBlocked &&
            !(currentPort.let { !it.isModxFamily && modxExists }) &&
            // Prefer MODX UMP over MODX MIDI 1.0 when UMP is allowed.
            !(currentPort.isModxFamily && !currentPort.isUmp && modxUmpExists)
        if (keepCurrent) return current

        val preferred = inputs
            .filter { !it.isProbeApp && umpAllowed(it) }
            .let { clean ->
                clean.firstOrNull { it.isModxFamily && it.isUmp }
                    ?: clean.firstOrNull { it.isModxFamily }
                    ?: clean.firstOrNull()
            }
            ?: inputs.first()
        MidiDebugLog.i(
            "preferDefaultInput → ${preferred.label} (id=${preferred.deviceId} port=${preferred.portIndex} " +
                "ump=${preferred.isUmp} modx=${preferred.isModxFamily})",
        )
        return current.copy(endpoint = preferred.toRef())
    }

    private fun ensureTrackOutputs(song: Song, outputs: List<PortInfo>): Song {
        if (outputs.isEmpty()) return song
        // Outputs must match the open transport for each family (exclusive MIDI1 vs UMP).
        val defaultRef = (
            outputs.filter { !it.isProbeApp }.let { clean ->
                val openIds = openDevices.keys + pendingOpenDeviceIds
                clean.firstOrNull { it.isModxFamily && it.deviceId in openIds }
                    ?: clean.firstOrNull { it.isModxFamily && !it.isUmp }
                    ?: clean.firstOrNull { it.isModxFamily }
                    ?: clean.firstOrNull { it.deviceId in openIds }
                    ?: clean.firstOrNull()
            } ?: outputs.first()
            ).toRef()
        return song.copy(
            parts = song.parts.map { part ->
                part.copy(
                    tracks = part.tracks.map { track ->
                        val matched = outputs.firstOrNull {
                            it.deviceId == track.output.deviceId && it.portIndex == track.output.portIndex
                        }
                        val out = if (track.output.isSet && matched != null && !matched.isProbeApp) {
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

    private fun listAllDevices(): List<Pair<MidiDeviceInfo, Boolean>> {
        val manager = midiManager ?: return emptyList()
        val out = ArrayList<Pair<MidiDeviceInfo, Boolean>>()
        if (Build.VERSION.SDK_INT >= 33) {
            for (info in manager.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM)) {
                out += info to false
            }
            for (info in manager.getDevicesForTransport(MidiManager.TRANSPORT_UNIVERSAL_MIDI_PACKETS)) {
                out += info to true
            }
        } else {
            for (info in manager.devices.orEmpty()) {
                out += info to false
            }
        }
        return out
    }

    private fun registerCallbacks() {
        val manager = midiManager
        if (manager == null) {
            MidiDebugLog.w("MidiManager is null — no USB MIDI")
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            manager.registerDeviceCallback(
                MidiManager.TRANSPORT_MIDI_BYTE_STREAM,
                mainExecutor,
                midi1DeviceCallback,
            )
            manager.registerDeviceCallback(
                MidiManager.TRANSPORT_UNIVERSAL_MIDI_PACKETS,
                mainExecutor,
                umpDeviceCallback,
            )
            MidiDebugLog.i("registered device callbacks for MIDI_BYTE_STREAM + UNIVERSAL_MIDI_PACKETS")
        } else {
            @Suppress("DEPRECATION")
            manager.registerDeviceCallback(legacyDeviceCallback, mainHandler)
            MidiDebugLog.i("registered legacy device callback (MIDI 1.0 only, SDK<33)")
        }
    }

    private fun unregisterCallbacks() {
        midiManager?.unregisterDeviceCallback(midi1DeviceCallback)
        midiManager?.unregisterDeviceCallback(umpDeviceCallback)
        midiManager?.unregisterDeviceCallback(legacyDeviceCallback)
    }

    private fun isUmpDevice(info: MidiDeviceInfo): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false
        val manager = midiManager ?: return umpDeviceIds.contains(info.id)
        return manager.getDevicesForTransport(MidiManager.TRANSPORT_UNIVERSAL_MIDI_PACKETS)
            .any { it.id == info.id }
    }

    private fun safeProtocol(info: MidiDeviceInfo): Int =
        if (Build.VERSION.SDK_INT >= 33) info.defaultProtocol else MidiDeviceInfoProtocol.PROTOCOL_UNKNOWN

    private fun transportLabel(isUmp: Boolean): String = if (isUmp) "UMP" else "MIDI1"

    private fun deviceFamilyKey(info: MidiDeviceInfo, displayName: String): String =
        MidiDeviceFamily.key(
            serialNumber = info.properties.getString(MidiDeviceInfo.PROPERTY_SERIAL_NUMBER),
            displayName = displayName,
            productName = deviceProductName(info),
            deviceId = info.id,
        )

    private fun isProbeAppName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.lowercase()
        return n.contains("miditap") ||
            n.contains("latencytester") ||
            n.contains("latency tester") ||
            n.contains("midi tap")
    }

    private fun deviceDisplayName(info: MidiDeviceInfo): String =
        info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "MIDI ${info.id}"

    private fun deviceProductName(info: MidiDeviceInfo): String =
        info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: info.properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: ""

    private fun portKey(deviceId: Int, portIndex: Int): String = "$deviceId:$portIndex"
}
