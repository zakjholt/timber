#pragma once

#include <atomic>
#include <string>
#include <vector>

/**
 * Placeholder realtime graph.
 * Next step: wire Oboe (or AAudio) duplex streams, per-channel processing,
 * insert FX chain hooks, and non-blocking WAV writers for stems + master.
 */
class AudioGraph {
public:
    bool start(int sampleRate, int inputChannels, int outputChannels);
    void stop();

    void setChannelGain(int channelId, float gainDb);
    void setChannelPan(int channelId, float pan);
    void setChannelMute(int channelId, bool muted);
    void setChannelSolo(int channelId, bool solo);
    void setMasterLevel(float levelDb);

    bool startRecording(const std::string& directory,
                        const std::vector<int>& stemChannelIds,
                        bool recordMaster);
    void stopRecording();

    /** Returns channelCount peaks + 1 master peak. */
    std::vector<float> readMeters(int channelCount) const;

private:
    std::atomic<bool> running_{false};
    std::atomic<bool> recording_{false};
    int sampleRate_ = 44100;
    int inputChannels_ = 2;
    int outputChannels_ = 2;
    std::vector<float> gainsDb_;
    std::vector<float> pans_;
    std::vector<int> mutes_;
    std::vector<int> solos_;
    float masterLevelDb_ = 0.f;
};
