package dev.timber.app.domain.audio

import kotlinx.serialization.Serializable

/**
 * Plugin contract for insert / send / master effects.
 * V1 ships with an empty chain (bypass). Concrete FX implement this later.
 */
interface AudioEffect {
    val id: String
    val displayName: String
    fun prepare(sampleRate: Int, maxBlockFrames: Int)
    fun process(buffer: FloatArray, frames: Int, channels: Int)
    fun setParam(key: String, value: Float)
    fun getParam(key: String): Float
    fun reset()
}

object BypassEffect : AudioEffect {
    override val id: String = "bypass"
    override val displayName: String = "Bypass"
    override fun prepare(sampleRate: Int, maxBlockFrames: Int) = Unit
    override fun process(buffer: FloatArray, frames: Int, channels: Int) = Unit
    override fun setParam(key: String, value: Float) = Unit
    override fun getParam(key: String): Float = 0f
    override fun reset() = Unit
}

@Serializable
data class EffectSlotState(
    val effectId: String = BypassEffect.id,
    val bypassed: Boolean = true,
    val params: Map<String, Float> = emptyMap(),
)

@Serializable
data class EffectChainState(
    val inserts: List<EffectSlotState> = emptyList(),
)

@Serializable
enum class SendPoint {
    PreFader,
    PostFader,
}

@Serializable
data class SendState(
    val busId: Int,
    val levelDb: Float = -Float.MAX_VALUE,
    val muted: Boolean = true,
    val point: SendPoint = SendPoint.PostFader,
)

@Serializable
data class ChannelStripState(
    val id: Int,
    val name: String,
    val inputChannelIndex: Int,
    /** Pair with next channel when true (stereo linked strip). */
    val stereoLink: Boolean = false,
    val gainDb: Float = 0f,
    val pan: Float = 0f,
    val muted: Boolean = false,
    val solo: Boolean = false,
    val recordArmed: Boolean = false,
    val inserts: EffectChainState = EffectChainState(),
    /** Reserved for future mix buses; empty in v1. */
    val sends: List<SendState> = emptyList(),
)

@Serializable
data class BusState(
    val id: Int,
    val name: String,
    val levelDb: Float = 0f,
    val muted: Boolean = false,
    val inserts: EffectChainState = EffectChainState(),
)

@Serializable
data class MasterState(
    val levelDb: Float = 0f,
    val muted: Boolean = false,
    val recordArmed: Boolean = true,
    val inserts: EffectChainState = EffectChainState(),
)

@Serializable
data class MixerState(
    val channels: List<ChannelStripState> = emptyList(),
    /** Future mix buses; kept in the model so persistence stays stable. */
    val buses: List<BusState> = emptyList(),
    val master: MasterState = MasterState(),
)

@Serializable
data class MeterPeak(
    val channelId: Int,
    val peakL: Float = 0f,
    val peakR: Float = 0f,
)

@Serializable
enum class RecordTarget {
    Stem,
    Master,
}

@Serializable
data class TakeFile(
    val target: RecordTarget,
    val channelId: Int?,
    val fileName: String,
)

@Serializable
data class TakeManifest(
    val takeId: String,
    val songId: Int,
    val partId: Int,
    val tempoBpm: Float,
    val sampleRate: Int,
    val createdAtEpochMs: Long,
    val files: List<TakeFile>,
)
