package dev.timber.app.engine.audio

import dev.timber.app.device.ModxMProfile
import dev.timber.app.domain.audio.ChannelStripState
import dev.timber.app.domain.audio.MasterState
import dev.timber.app.domain.audio.MeterPeak
import dev.timber.app.domain.audio.MixerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Kotlin façade over the native Oboe graph.
 * V1: negotiating device layout, strip state, meters, and record arming.
 * Buses / insert FX are modeled but not processed yet.
 */
class AudioEngine {
    data class State(
        val layout: ModxMProfile.Layout = ModxMProfile.resolveLayout(2, 2),
        val mixer: MixerState = MixerState(),
        val meters: List<MeterPeak> = emptyList(),
        val masterMeter: MeterPeak = MeterPeak(channelId = -1),
        val isRunning: Boolean = false,
        val isRecording: Boolean = false,
        val lastError: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val native = TimberAudioNative()

    fun applyLayout(inputChannels: Int, outputChannels: Int, sampleRate: Int = ModxMProfile.PREFERRED_SAMPLE_RATE) {
        val layout = ModxMProfile.resolveLayout(inputChannels, outputChannels, sampleRate)
        val channels = layout.inputLabels.mapIndexed { index, label ->
            ChannelStripState(
                id = index,
                name = label,
                inputChannelIndex = index,
                stereoLink = layout.stereoPairs.any { it.first == index },
                recordArmed = index < 2,
            )
        }
        _state.update {
            it.copy(
                layout = layout,
                mixer = MixerState(
                    channels = channels,
                    buses = emptyList(),
                    master = MasterState(recordArmed = true),
                ),
                meters = channels.map { ch -> MeterPeak(channelId = ch.id) },
            )
        }
    }

    fun start(): Boolean {
        val layout = _state.value.layout
        val ok = native.start(
            sampleRate = layout.sampleRate,
            inputChannels = layout.inputChannelCount.coerceAtLeast(1),
            outputChannels = layout.outputChannelCount.coerceAtLeast(1),
        )
        _state.update {
            it.copy(
                isRunning = ok,
                lastError = if (ok) null else "Failed to start audio engine",
            )
        }
        return ok
    }

    fun stop() {
        native.stop()
        _state.update { it.copy(isRunning = false, isRecording = false) }
    }

    fun setChannelGain(channelId: Int, gainDb: Float) {
        updateChannel(channelId) { it.copy(gainDb = gainDb) }
        native.setChannelGain(channelId, gainDb)
    }

    fun setChannelPan(channelId: Int, pan: Float) {
        updateChannel(channelId) { it.copy(pan = pan.coerceIn(-1f, 1f)) }
        native.setChannelPan(channelId, pan)
    }

    fun toggleMute(channelId: Int) {
        val channel = _state.value.mixer.channels.find { it.id == channelId } ?: return
        val muted = !channel.muted
        updateChannel(channelId) { it.copy(muted = muted) }
        native.setChannelMute(channelId, muted)
    }

    fun toggleSolo(channelId: Int) {
        val channel = _state.value.mixer.channels.find { it.id == channelId } ?: return
        val solo = !channel.solo
        updateChannel(channelId) { it.copy(solo = solo) }
        native.setChannelSolo(channelId, solo)
    }

    fun toggleStemArm(channelId: Int) {
        updateChannel(channelId) { it.copy(recordArmed = !it.recordArmed) }
    }

    fun toggleMasterArm() {
        _state.update {
            it.copy(mixer = it.mixer.copy(master = it.mixer.master.copy(recordArmed = !it.mixer.master.recordArmed)))
        }
    }

    fun setMasterLevel(levelDb: Float) {
        _state.update { it.copy(mixer = it.mixer.copy(master = it.mixer.master.copy(levelDb = levelDb))) }
        native.setMasterLevel(levelDb)
    }

    fun startRecording(takeDirectory: String): Boolean {
        val mixer = _state.value.mixer
        val stemIds = mixer.channels.filter { it.recordArmed }.map { it.id }.toIntArray()
        val ok = native.startRecording(takeDirectory, stemIds, mixer.master.recordArmed)
        _state.update { it.copy(isRecording = ok) }
        return ok
    }

    fun stopRecording() {
        native.stopRecording()
        _state.update { it.copy(isRecording = false) }
    }

    fun pollMeters() {
        val peaks = native.readMeters(_state.value.mixer.channels.size)
        if (peaks.isEmpty()) return
        val meters = peaks.dropLast(1).mapIndexed { index, peak ->
            MeterPeak(channelId = index, peakL = peak, peakR = peak)
        }
        val master = peaks.lastOrNull() ?: 0f
        _state.update {
            it.copy(
                meters = meters,
                masterMeter = MeterPeak(channelId = -1, peakL = master, peakR = master),
            )
        }
    }

    private fun updateChannel(channelId: Int, transform: (ChannelStripState) -> ChannelStripState) {
        _state.update { state ->
            state.copy(
                mixer = state.mixer.copy(
                    channels = state.mixer.channels.map { ch ->
                        if (ch.id == channelId) transform(ch) else ch
                    },
                ),
            )
        }
    }
}
