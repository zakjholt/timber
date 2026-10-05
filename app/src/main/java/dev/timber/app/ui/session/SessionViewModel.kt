package dev.timber.app.ui.session

import android.app.Application
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.midi.MidiManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.timber.app.device.UsbAudioProbe
import dev.timber.app.domain.audio.MeterPeak
import dev.timber.app.domain.midi.TransportState
import dev.timber.app.domain.session.SessionState
import dev.timber.app.engine.audio.AudioEngine
import dev.timber.app.engine.midi.MidiEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TimberTab {
    Sequencer,
    Mixer,
}

data class TimberUiState(
    val tab: TimberTab = TimberTab.Sequencer,
    val session: SessionState = SessionState.bootstrap(),
    val midiDevices: List<String> = emptyList(),
    val audioRunning: Boolean = false,
    val audioRecording: Boolean = false,
    val transport: TransportState = TransportState.Stopped,
    val positionTicks: Long = 0,
    val deviceNotes: String = SessionState.bootstrap().deviceNotes,
    val meters: List<MeterPeak> = emptyList(),
    val masterMeter: MeterPeak = MeterPeak(channelId = -1),
)

class SessionViewModel(application: Application) : AndroidViewModel(application) {
    private val midiManager = application.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val midiEngine = MidiEngine(midiManager)
    private val audioEngine = AudioEngine()

    private val _ui = MutableStateFlow(TimberUiState())
    val ui: StateFlow<TimberUiState> = _ui.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            renegotiateAudio()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            renegotiateAudio()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        renegotiateAudio()
        midiEngine.refreshDevices()

        viewModelScope.launch {
            midiEngine.state.collect { midi ->
                _ui.update {
                    it.copy(
                        midiDevices = midi.connectedDevices,
                        transport = midi.transport,
                        positionTicks = midi.positionTicks,
                        session = it.session.copy(song = midi.song, transport = it.session.transport.copy(
                            state = midi.transport,
                            songPositionTicks = midi.positionTicks,
                            tempoBpm = midi.song.tempoBpm,
                        )),
                    )
                }
            }
        }
        viewModelScope.launch {
            audioEngine.state.collect { audio ->
                _ui.update {
                    it.copy(
                        audioRunning = audio.isRunning,
                        audioRecording = audio.isRecording,
                        deviceNotes = audio.layout.notes,
                        meters = audio.meters,
                        masterMeter = audio.masterMeter,
                        session = it.session.copy(mixer = audio.mixer, deviceNotes = audio.layout.notes),
                    )
                }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                audioEngine.pollMeters()
                delay(50)
            }
        }
    }

    fun selectTab(tab: TimberTab) {
        _ui.update { it.copy(tab = tab) }
    }

    fun play() = midiEngine.play()
    fun stop() {
        midiEngine.stop()
        if (audioEngine.state.value.isRecording) {
            audioEngine.stopRecording()
        }
    }

    fun toggleMidiRecord() = midiEngine.toggleRecord()

    fun armMidiTrack(trackId: Int) = midiEngine.armTrack(trackId)
    fun toggleMidiMute(trackId: Int) = midiEngine.toggleMute(trackId)

    fun setChannelGain(channelId: Int, gainDb: Float) = audioEngine.setChannelGain(channelId, gainDb)
    fun toggleChannelMute(channelId: Int) = audioEngine.toggleMute(channelId)
    fun toggleChannelSolo(channelId: Int) = audioEngine.toggleSolo(channelId)
    fun toggleStemArm(channelId: Int) = audioEngine.toggleStemArm(channelId)
    fun toggleMasterArm() = audioEngine.toggleMasterArm()
    fun setMasterLevel(levelDb: Float) = audioEngine.setMasterLevel(levelDb)

    fun toggleAudioEngine() {
        if (audioEngine.state.value.isRunning) {
            audioEngine.stop()
        } else {
            audioEngine.start()
        }
    }

    fun toggleAudioRecord() {
        if (audioEngine.state.value.isRecording) {
            audioEngine.stopRecording()
            return
        }
        if (!audioEngine.state.value.isRunning) {
            audioEngine.start()
        }
        val takesDir = File(getApplication<Application>().filesDir, "takes")
        takesDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val takeDir = File(takesDir, "take_$stamp")
        takeDir.mkdirs()
        audioEngine.startRecording(takeDir.absolutePath)
    }

    private fun renegotiateAudio() {
        val layout = UsbAudioProbe.probe(audioManager)
        audioEngine.applyLayout(
            inputChannels = layout.inputChannelCount.coerceAtLeast(2),
            outputChannels = layout.outputChannelCount.coerceAtLeast(2),
            sampleRate = layout.sampleRate,
        )
        _ui.update { it.copy(deviceNotes = layout.notes) }
        midiEngine.refreshDevices()
    }

    override fun onCleared() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        audioEngine.stop()
        midiEngine.stop()
        super.onCleared()
    }
}
