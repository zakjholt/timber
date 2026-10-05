#include "AudioGraph.h"

#include <algorithm>
#include <cmath>

bool AudioGraph::start(int sampleRate, int inputChannels, int outputChannels) {
    sampleRate_ = sampleRate;
    inputChannels_ = std::max(1, inputChannels);
    outputChannels_ = std::max(1, outputChannels);
    gainsDb_.assign(static_cast<size_t>(inputChannels_), 0.f);
    pans_.assign(static_cast<size_t>(inputChannels_), 0.f);
    mutes_.assign(static_cast<size_t>(inputChannels_), 0);
    solos_.assign(static_cast<size_t>(inputChannels_), 0);
    running_.store(true);
    return true;
}

void AudioGraph::stop() {
    recording_.store(false);
    running_.store(false);
}

void AudioGraph::setChannelGain(int channelId, float gainDb) {
    if (channelId < 0 || channelId >= static_cast<int>(gainsDb_.size())) return;
    gainsDb_[static_cast<size_t>(channelId)] = gainDb;
}

void AudioGraph::setChannelPan(int channelId, float pan) {
    if (channelId < 0 || channelId >= static_cast<int>(pans_.size())) return;
    pans_[static_cast<size_t>(channelId)] = std::clamp(pan, -1.f, 1.f);
}

void AudioGraph::setChannelMute(int channelId, bool muted) {
    if (channelId < 0 || channelId >= static_cast<int>(mutes_.size())) return;
    mutes_[static_cast<size_t>(channelId)] = muted ? 1 : 0;
}

void AudioGraph::setChannelSolo(int channelId, bool solo) {
    if (channelId < 0 || channelId >= static_cast<int>(solos_.size())) return;
    solos_[static_cast<size_t>(channelId)] = solo ? 1 : 0;
}

void AudioGraph::setMasterLevel(float levelDb) {
    masterLevelDb_ = levelDb;
}

bool AudioGraph::startRecording(const std::string& /*directory*/,
                                const std::vector<int>& /*stemChannelIds*/,
                                bool /*recordMaster*/) {
    if (!running_.load()) return false;
    recording_.store(true);
    return true;
}

void AudioGraph::stopRecording() {
    recording_.store(false);
}

std::vector<float> AudioGraph::readMeters(int channelCount) const {
    std::vector<float> peaks(static_cast<size_t>(std::max(0, channelCount)) + 1, 0.f);
    return peaks;
}
