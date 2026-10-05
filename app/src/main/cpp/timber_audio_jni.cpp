#include <jni.h>

#include <memory>
#include <string>
#include <vector>

#include "audio/AudioGraph.h"

namespace {
std::unique_ptr<AudioGraph> gGraph;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_timber_app_engine_audio_TimberAudioNative_nativeStart(
        JNIEnv*, jobject, jint sampleRate, jint inputChannels, jint outputChannels) {
    if (!gGraph) gGraph = std::make_unique<AudioGraph>();
    return gGraph->start(sampleRate, inputChannels, outputChannels) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_timber_app_engine_audio_TimberAudioNative_nativeStop(JNIEnv*, jobject) {
    if (gGraph) gGraph->stop();
}

JNIEXPORT void JNICALL
Java_dev_timber_app_engine_audio_TimberAudioNative_nativeSetChannelGain(
        JNIEnv*, jobject, jint channelId, jfloat gainDb) {
    if (gGraph) gGraph->setChannelGain(channelId, gainDb);
}

JNIEXPORT void JNICALL
Java_dev_timber_app_engine_audio_TimberAudioNative_nativeSetChannelPan(
        JNIEnv*, jobject, jint channelId, jfloat pan) {
    if (gGraph) gGraph->setChannelPan(channelId, pan);
}

JNIEXPORT void JNICALL
Java_dev_timber_app_engine_audio_TimberAudioNative_nativeSetChannelMute(
        JNIEnv*, jobject, jint channelId, jboolean muted) {
    if (gGraph) gGraph->setChannelMute(channelId, muted == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_dev_timber_app_engine_audio_TimberAudioNative_nativeSetChannelSolo(
        JNIEnv*, jobject, jint channelId, jboolean solo) {
    if (gGraph) gGraph->setChannelSolo(channelId, solo == JNI_TRUE);
}

JNIEXPORT void JNICALL
Java_dev_timber_app_engine_audio_TimberAudioNative_nativeSetMasterLevel(
        JNIEnv*, jobject, jfloat levelDb) {
    if (gGraph) gGraph->setMasterLevel(levelDb);
}

JNIEXPORT jboolean JNICALL
Java_dev_timber_app_engine_audio_TimberAudioNative_nativeStartRecording(
        JNIEnv* env, jobject, jstring directory, jintArray stemChannelIds, jboolean recordMaster) {
    if (!gGraph) return JNI_FALSE;
    const char* dirChars = env->GetStringUTFChars(directory, nullptr);
    std::string dir = dirChars ? dirChars : "";
    if (dirChars) env->ReleaseStringUTFChars(directory, dirChars);

    std::vector<int> stems;
    if (stemChannelIds != nullptr) {
        const jsize len = env->GetArrayLength(stemChannelIds);
        stems.resize(static_cast<size_t>(len));
        env->GetIntArrayRegion(stemChannelIds, 0, len, stems.data());
    }
    return gGraph->startRecording(dir, stems, recordMaster == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_dev_timber_app_engine_audio_TimberAudioNative_nativeStopRecording(JNIEnv*, jobject) {
    if (gGraph) gGraph->stopRecording();
}

JNIEXPORT jfloatArray JNICALL
Java_dev_timber_app_engine_audio_TimberAudioNative_nativeReadMeters(
        JNIEnv* env, jobject, jint channelCount) {
    std::vector<float> peaks;
    if (gGraph) {
        peaks = gGraph->readMeters(channelCount);
    } else {
        peaks.assign(static_cast<size_t>(std::max(0, channelCount)) + 1, 0.f);
    }
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(peaks.size()));
    if (out != nullptr && !peaks.empty()) {
        env->SetFloatArrayRegion(out, 0, static_cast<jsize>(peaks.size()), peaks.data());
    }
    return out;
}

}  // extern "C"
