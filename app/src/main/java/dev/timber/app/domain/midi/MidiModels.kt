package dev.timber.app.domain.midi

import kotlinx.serialization.Serializable

const val PPQN: Int = 96
const val MIDI_DEVICE_UNSET: Int = -1

@Serializable
data class MidiEndpointRef(
    val deviceId: Int = MIDI_DEVICE_UNSET,
    val portIndex: Int = 0,
    val displayName: String = "",
) {
    val isSet: Boolean get() = deviceId != MIDI_DEVICE_UNSET
}

/** [listenChannel] null = Omni. */
@Serializable
data class RecordInputConfig(
    val endpoint: MidiEndpointRef = MidiEndpointRef(),
    val listenChannel: Int? = null,
)

@Serializable
enum class MidiMessageType {
    NoteOn,
    NoteOff,
    ControlChange,
    ProgramChange,
    PitchBend,
    Aftertouch,
    ChannelPressure,
    SysEx,
    Other,
}

@Serializable
data class MidiEvent(
    val tick: Long,
    val type: MidiMessageType,
    val channel: Int,
    val data1: Int = 0,
    val data2: Int = 0,
    val sysEx: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MidiEvent) return false
        return tick == other.tick &&
            type == other.type &&
            channel == other.channel &&
            data1 == other.data1 &&
            data2 == other.data2 &&
            sysEx.contentEquals(other.sysEx)
    }

    override fun hashCode(): Int {
        var result = tick.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + channel
        result = 31 * result + data1
        result = 31 * result + data2
        result = 31 * result + (sysEx?.contentHashCode() ?: 0)
        return result
    }
}

@Serializable
enum class QuantizeGrid {
    Off,
    Quarter,
    Eighth,
    Sixteenth,
    ThirtySecond,
    TripletEighth,
    TripletSixteenth,
}

@Serializable
data class TrackModifiers(
    val quantize: QuantizeGrid = QuantizeGrid.Off,
    val swing: Float = 0f,
    val transpose: Int = 0,
    val velocityScale: Float = 1f,
    val filterNotes: Boolean = false,
    val filterCc: Boolean = false,
    val filterProgramChange: Boolean = false,
    val filterAftertouch: Boolean = false,
    val filterPitchBend: Boolean = false,
    /** null = track outputChannel; 1..16 forces channel. */
    val forceChannel: Int? = null,
) {
    companion object {
        const val PERFORMANCE_TRANSPOSE: Int = 99
    }
}

@Serializable
enum class TrackPlayMode {
    Loop,
    OneShot,
}

@Serializable
data class MidiTrack(
    val id: Int,
    val name: String = "Track $id",
    val events: List<MidiEvent> = emptyList(),
    val modifiers: TrackModifiers = TrackModifiers(),
    val muted: Boolean = false,
    val lengthTicks: Long? = null,
    val playMode: TrackPlayMode = TrackPlayMode.Loop,
    val armed: Boolean = false,
    val output: MidiEndpointRef = MidiEndpointRef(),
    val outputChannel: Int = 1,
)

@Serializable
data class Part(
    val id: Int,
    val name: String = "Part $id",
    val lengthBeats: Int = 4,
    val timeSignatureNumerator: Int = 4,
    val timeSignatureDenominator: Int = 4,
    val tempoBpm: Float? = null,
    val tracks: List<MidiTrack> = (1..8).map {
        MidiTrack(id = it, outputChannel = it.coerceIn(1, 16), armed = it == 1)
    },
) {
    val lengthTicks: Long
        get() = lengthBeats.toLong() * PPQN
}

@Serializable
data class SongStep(
    val partId: Int,
    val trackMutes: List<Boolean> = List(8) { false },
    val trackTransposes: List<Int> = List(8) { 0 },
)

@Serializable
data class Song(
    val id: Int = 1,
    val name: String = "Song 1",
    val tempoBpm: Float = 120f,
    val parts: List<Part> = listOf(Part(id = 1)),
    val arrangement: List<SongStep> = listOf(SongStep(partId = 1)),
)

@Serializable
enum class TransportState {
    Stopped,
    Playing,
    Recording,
    CountIn,
}

@Serializable
data class TransportSnapshot(
    val state: TransportState = TransportState.Stopped,
    val songPositionTicks: Long = 0,
    val currentPartId: Int = 1,
    val loopPart: Boolean = true,
    val tempoBpm: Float = 120f,
    val countInBeats: Int = 4,
)
