package dev.timber.app.engine.audio

/**
 * JNI bridge with a safe Kotlin fallback when `libtimber_audio` is unavailable
 * (e.g. desktop unit tests or first sync before NDK build).
 */
class TimberAudioNative {
    private val useNative = Companion.libraryLoaded

    fun start(sampleRate: Int, inputChannels: Int, outputChannels: Int): Boolean {
        return if (useNative) nativeStart(sampleRate, inputChannels, outputChannels) else true
    }

    fun stop() {
        if (useNative) nativeStop()
    }

    fun setChannelGain(channelId: Int, gainDb: Float) {
        if (useNative) nativeSetChannelGain(channelId, gainDb)
    }

    fun setChannelPan(channelId: Int, pan: Float) {
        if (useNative) nativeSetChannelPan(channelId, pan)
    }

    fun setChannelMute(channelId: Int, muted: Boolean) {
        if (useNative) nativeSetChannelMute(channelId, muted)
    }

    fun setChannelSolo(channelId: Int, solo: Boolean) {
        if (useNative) nativeSetChannelSolo(channelId, solo)
    }

    fun setMasterLevel(levelDb: Float) {
        if (useNative) nativeSetMasterLevel(levelDb)
    }

    fun startRecording(directory: String, stemChannelIds: IntArray, recordMaster: Boolean): Boolean {
        return if (useNative) {
            nativeStartRecording(directory, stemChannelIds, recordMaster)
        } else {
            false
        }
    }

    fun stopRecording() {
        if (useNative) nativeStopRecording()
    }

    fun readMeters(channelCount: Int): FloatArray {
        return if (useNative) {
            nativeReadMeters(channelCount)
        } else {
            FloatArray(channelCount + 1)
        }
    }

    private external fun nativeStart(sampleRate: Int, inputChannels: Int, outputChannels: Int): Boolean
    private external fun nativeStop()
    private external fun nativeSetChannelGain(channelId: Int, gainDb: Float)
    private external fun nativeSetChannelPan(channelId: Int, pan: Float)
    private external fun nativeSetChannelMute(channelId: Int, muted: Boolean)
    private external fun nativeSetChannelSolo(channelId: Int, solo: Boolean)
    private external fun nativeSetMasterLevel(levelDb: Float)
    private external fun nativeStartRecording(
        directory: String,
        stemChannelIds: IntArray,
        recordMaster: Boolean,
    ): Boolean
    private external fun nativeStopRecording()
    private external fun nativeReadMeters(channelCount: Int): FloatArray

    companion object {
        val libraryLoaded: Boolean

        init {
            libraryLoaded = try {
                System.loadLibrary("timber_audio")
                true
            } catch (_: UnsatisfiedLinkError) {
                false
            }
        }
    }
}
