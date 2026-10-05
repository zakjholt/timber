package dev.timber.app.domain.session

import dev.timber.app.device.ModxMProfile
import dev.timber.app.domain.audio.ChannelStripState
import dev.timber.app.domain.audio.MasterState
import dev.timber.app.domain.audio.MixerState
import dev.timber.app.domain.midi.Song
import dev.timber.app.domain.midi.TransportSnapshot
import kotlinx.serialization.Serializable

@Serializable
data class SessionState(
    val song: Song = Song(),
    val transport: TransportSnapshot = TransportSnapshot(),
    val mixer: MixerState = MixerState(),
    val deviceNotes: String = "",
) {
    companion object {
        fun bootstrap(layout: ModxMProfile.Layout = ModxMProfile.resolveLayout(2, 2)): SessionState {
            val channels = layout.inputLabels.mapIndexed { index, label ->
                val linked = layout.stereoPairs.any { it.first == index }
                ChannelStripState(
                    id = index,
                    name = label,
                    inputChannelIndex = index,
                    stereoLink = linked,
                    recordArmed = index < 2,
                )
            }
            return SessionState(
                mixer = MixerState(
                    channels = channels,
                    buses = emptyList(),
                    master = MasterState(recordArmed = true),
                ),
                deviceNotes = layout.notes,
                transport = TransportSnapshot(tempoBpm = 120f),
            )
        }
    }
}
