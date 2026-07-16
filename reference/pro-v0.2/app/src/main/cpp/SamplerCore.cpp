#include "SamplerCore.h"

#include <algorithm>
#include <cstddef>
#include <cmath>
#include <cstring>
#include <limits>
#include <utility>

namespace choplab {
namespace {
constexpr float kPi = 3.14159265358979323846f;
constexpr float kTwoPi = 6.28318530717958647692f;
constexpr float kEpsilon = 1.0e-8f;

template <typename T>
T clampValue(T value, T minimum, T maximum) {
    return std::min(maximum, std::max(minimum, value));
}

float dbToLinear(float db) {
    return std::pow(10.0f, db / 20.0f);
}

float semitoneRatio(float semitones) {
    return std::pow(2.0f, semitones / 12.0f);
}

float softClip(float value) {
    return value / (1.0f + std::abs(value));
}

uint32_t xorshift32(uint32_t& state) {
    if (state == 0) state = 0x6d2b79f5u;
    state ^= state << 13u;
    state ^= state >> 17u;
    state ^= state << 5u;
    return state;
}
} // namespace

struct SamplerCore::SampleBuffer {
    int sampleRate = 48'000;
    int frameCount = 0;
    std::vector<float> stereo;

    float sample(double frame, int channel) const {
        if (frameCount <= 0 || frame < 0.0 || frame >= static_cast<double>(frameCount)) return 0.0f;
        const int lower = clampValue(static_cast<int>(frame), 0, frameCount - 1);
        const int upper = std::min(lower + 1, frameCount - 1);
        const float fraction = static_cast<float>(frame - static_cast<double>(lower));
        const float first = stereo[static_cast<size_t>(lower) * 2u + static_cast<size_t>(channel)];
        const float second = stereo[static_cast<size_t>(upper) * 2u + static_cast<size_t>(channel)];
        return first + (second - first) * fraction;
    }
};

struct SamplerCore::AtomicPadState {
    std::atomic<int> sampleId{-1};
    std::atomic<int> startFrame{0};
    std::atomic<int> endFrame{0};
    std::atomic<float> pitchSemitones{0.0f};
    std::atomic<float> stretchRatio{1.0f};
    std::atomic<float> tone{1.0f};
    std::atomic<float> resonance{0.08f};
    std::atomic<float> gain{0.9f};
    std::atomic<float> pan{0.0f};
    std::atomic<int> reverse{0};
    std::atomic<int> playMode{0};
    std::atomic<int> chokeGroup{0};

    std::atomic<float> attackMs{2.0f};
    std::atomic<float> decayMs{60.0f};
    std::atomic<float> sustain{1.0f};
    std::atomic<float> releaseMs{120.0f};

    std::atomic<int> lfoEnabled{0};
    std::atomic<int> lfoWaveform{0};
    std::atomic<int> lfoTarget{3};
    std::atomic<float> lfoRateHz{2.0f};
    std::atomic<float> lfoDepth{0.0f};
    std::atomic<int> lfoTempoSync{0};
    std::atomic<float> lfoDivisionBeats{0.5f};

    std::atomic<float> drive{0.0f};
    std::atomic<int> bitDepth{16};
    std::atomic<int> sampleRateReduction{1};
    std::atomic<float> delaySend{0.0f};
    std::atomic<float> reverbSend{0.0f};
};

struct SamplerCore::PadSnapshot {
    SampleBuffer* sample = nullptr;
    int padIndex = -1;
    int startFrame = 0;
    int endFrame = 0;
    float pitchSemitones = 0.0f;
    float stretchRatio = 1.0f;
    float tone = 1.0f;
    float resonance = 0.08f;
    float gain = 0.9f;
    float pan = 0.0f;
    bool reverse = false;
    int playMode = 0;
    int chokeGroup = 0;

    float attackMs = 2.0f;
    float decayMs = 60.0f;
    float sustain = 1.0f;
    float releaseMs = 120.0f;

    bool lfoEnabled = false;
    int lfoWaveform = 0;
    int lfoTarget = 3;
    float lfoRateHz = 2.0f;
    float lfoDepth = 0.0f;
    bool lfoTempoSync = false;
    float lfoDivisionBeats = 0.5f;

    float drive = 0.0f;
    int bitDepth = 16;
    int sampleRateReduction = 1;
    float delaySend = 0.0f;
    float reverbSend = 0.0f;
};

struct SamplerCore::SequenceData {
    std::vector<uint64_t> masks;
    bool loop = true;
};

struct SamplerCore::Voice {
    enum class EnvelopeStage { Attack, Decay, Sustain, Release, Done };

    struct Grain {
        bool active = false;
        int age = 0;
        double readPosition = 0.0;
    };

    bool active = false;
    uint64_t serial = 0;
    int padIndex = -1;
    int playMode = 0;
    int chokeGroup = 0;
    SampleBuffer* sample = nullptr;
    int startFrame = 0;
    int endFrame = 0;
    bool reverse = false;
    int direction = 1;
    float velocity = 1.0f;
    float gain = 1.0f;
    float pan = 0.0f;
    float tone = 1.0f;
    float resonance = 0.0f;
    float pitchRatio = 1.0f;
    float stretchRatio = 1.0f;
    float sourceRateRatio = 1.0f;
    float drive = 0.0f;
    int bitDepth = 16;
    int reduction = 1;
    float delaySend = 0.0f;
    float reverbSend = 0.0f;

    bool lfoEnabled = false;
    int lfoWaveform = 0;
    int lfoTarget = 3;
    float lfoRateHz = 2.0f;
    float lfoDepth = 0.0f;
    bool lfoTempoSync = false;
    float lfoDivisionBeats = 0.5f;
    float lfoPhase = 0.0f;
    float sampleHold = 0.0f;
    uint32_t randomState = 1u;

    EnvelopeStage envelopeStage = EnvelopeStage::Done;
    float envelope = 0.0f;
    int attackFrames = 1;
    int decayFrames = 1;
    int releaseFrames = 1;
    float sustain = 1.0f;
    float releaseStep = 1.0f;

    bool directMode = true;
    double directPosition = 0.0;
    double directStep = 1.0;
    int64_t outputAge = 0;
    int64_t maxOutputFrames = 1;
    int autoReleaseAt = -1;

    int grainLength = 1024;
    int grainHop = 256;
    int64_t nextGrainAt = 0;
    double nextGrainSource = 0.0;
    double grainSourceHop = 256.0;
    std::array<Grain, kMaxGrains> grains{};

    float filterIc1L = 0.0f;
    float filterIc2L = 0.0f;
    float filterIc1R = 0.0f;
    float filterIc2R = 0.0f;
    float filterA1 = 1.0f;
    float filterA2 = 0.0f;
    float filterA3 = 0.0f;
    float heldL = 0.0f;
    float heldR = 0.0f;
    int reductionCounter = 0;

    void start(
        const PadSnapshot& pad,
        int outputSampleRate,
        float velocityValue,
        uint64_t voiceSerial,
        int automaticReleaseFrames
    ) {
        active = pad.sample != nullptr && pad.endFrame > pad.startFrame;
        if (!active) return;

        serial = voiceSerial;
        padIndex = pad.padIndex;
        playMode = pad.playMode;
        chokeGroup = pad.chokeGroup;
        sample = pad.sample;
        startFrame = clampValue(pad.startFrame, 0, sample->frameCount - 1);
        endFrame = clampValue(pad.endFrame, startFrame + 1, sample->frameCount);
        reverse = pad.reverse;
        direction = reverse ? -1 : 1;
        velocity = clampValue(velocityValue, 0.0f, 1.0f);
        gain = pad.gain;
        pan = pad.pan;
        tone = pad.tone;
        resonance = pad.resonance;
        pitchRatio = semitoneRatio(pad.pitchSemitones);
        stretchRatio = pad.stretchRatio;
        sourceRateRatio = static_cast<float>(sample->sampleRate) /
            static_cast<float>(std::max(1, outputSampleRate));
        drive = pad.drive;
        bitDepth = pad.bitDepth;
        reduction = pad.sampleRateReduction;
        delaySend = pad.delaySend;
        reverbSend = pad.reverbSend;

        lfoEnabled = pad.lfoEnabled && pad.lfoDepth > 0.0001f;
        lfoWaveform = pad.lfoWaveform;
        lfoTarget = pad.lfoTarget;
        lfoRateHz = pad.lfoRateHz;
        lfoDepth = pad.lfoDepth;
        lfoTempoSync = pad.lfoTempoSync;
        lfoDivisionBeats = pad.lfoDivisionBeats;
        lfoPhase = 0.0f;
        randomState = static_cast<uint32_t>((padIndex + 1) * 0x9e3779b9u) ^
            static_cast<uint32_t>(voiceSerial);
        sampleHold = (static_cast<float>(xorshift32(randomState) & 0xFFFFu) / 32767.5f) - 1.0f;

        attackFrames = std::max(1, static_cast<int>(pad.attackMs * outputSampleRate / 1000.0f));
        decayFrames = std::max(1, static_cast<int>(pad.decayMs * outputSampleRate / 1000.0f));
        releaseFrames = std::max(1, static_cast<int>(pad.releaseMs * outputSampleRate / 1000.0f));
        sustain = pad.sustain;
        envelope = pad.attackMs <= 0.001f ? 1.0f : 0.0f;
        envelopeStage = pad.attackMs <= 0.001f ? EnvelopeStage::Decay : EnvelopeStage::Attack;
        releaseStep = 1.0f / static_cast<float>(releaseFrames);

        const int sourceFrames = std::max(1, endFrame - startFrame);
        const double naturalOutputFrames = static_cast<double>(sourceFrames) /
            std::max(1.0e-9, static_cast<double>(sourceRateRatio));
        maxOutputFrames = std::max<int64_t>(
            1,
            static_cast<int64_t>(std::llround(naturalOutputFrames * stretchRatio))
        );
        outputAge = 0;
        autoReleaseAt = automaticReleaseFrames;

        directMode = std::abs(pad.pitchSemitones) < 0.0001f &&
            std::abs(stretchRatio - 1.0f) < 0.0001f &&
            !(lfoEnabled && lfoTarget == 2);
        directPosition = reverse ? static_cast<double>(endFrame - 1) : static_cast<double>(startFrame);
        directStep = static_cast<double>(sourceRateRatio) * static_cast<double>(direction);

        grainLength = clampValue(
            static_cast<int>(std::min<int64_t>(1024, maxOutputFrames)),
            32,
            1024
        );
        grainHop = std::max(8, grainLength / 4);
        nextGrainAt = 0;
        nextGrainSource = reverse ? static_cast<double>(endFrame - 1) : static_cast<double>(startFrame);
        grainSourceHop = static_cast<double>(direction) *
            static_cast<double>(grainHop) * static_cast<double>(sourceRateRatio) /
            static_cast<double>(std::max(0.25f, stretchRatio));
        for (auto& grain : grains) grain = Grain{};

        filterIc1L = filterIc2L = filterIc1R = filterIc2R = 0.0f;
        heldL = heldR = 0.0f;
        reductionCounter = 0;
    }

    void noteOff(bool force) {
        if (!active || envelopeStage == EnvelopeStage::Release || envelopeStage == EnvelopeStage::Done) return;
        if (!force && playMode == 0) return;
        envelopeStage = EnvelopeStage::Release;
        releaseStep = envelope / static_cast<float>(std::max(1, releaseFrames));
    }

    float lfoValue(float bpm, int outputSampleRate) {
        if (!lfoEnabled) return 0.0f;
        float value = 0.0f;
        switch (clampValue(lfoWaveform, 0, 4)) {
            case 0: value = std::sin(kTwoPi * lfoPhase); break;
            case 1: value = 1.0f - 4.0f * std::abs(lfoPhase - 0.5f); break;
            case 2: value = lfoPhase < 0.5f ? 1.0f : -1.0f; break;
            case 3: value = 2.0f * lfoPhase - 1.0f; break;
            case 4: value = sampleHold; break;
            default: break;
        }

        const float rate = lfoTempoSync
            ? clampValue(bpm, 30.0f, 300.0f) /
                (60.0f * clampValue(lfoDivisionBeats, 0.0625f, 4.0f))
            : clampValue(lfoRateHz, 0.05f, 30.0f);
        lfoPhase += rate / static_cast<float>(std::max(1, outputSampleRate));
        if (lfoPhase >= 1.0f) {
            lfoPhase -= std::floor(lfoPhase);
            if (lfoWaveform == 4) {
                sampleHold = (static_cast<float>(xorshift32(randomState) & 0xFFFFu) / 32767.5f) - 1.0f;
            }
        }
        return value;
    }

    float advanceEnvelope() {
        switch (envelopeStage) {
            case EnvelopeStage::Attack:
                envelope += 1.0f / static_cast<float>(attackFrames);
                if (envelope >= 1.0f) {
                    envelope = 1.0f;
                    envelopeStage = EnvelopeStage::Decay;
                }
                break;
            case EnvelopeStage::Decay:
                envelope -= (1.0f - sustain) / static_cast<float>(decayFrames);
                if (envelope <= sustain) {
                    envelope = sustain;
                    envelopeStage = EnvelopeStage::Sustain;
                }
                break;
            case EnvelopeStage::Sustain:
                envelope = sustain;
                break;
            case EnvelopeStage::Release:
                envelope -= releaseStep;
                if (envelope <= 0.00001f) {
                    envelope = 0.0f;
                    envelopeStage = EnvelopeStage::Done;
                    active = false;
                }
                break;
            case EnvelopeStage::Done:
                envelope = 0.0f;
                active = false;
                break;
        }
        return envelope;
    }

    float windowForAge(int age) const {
        if (grainLength <= 1 || age < 0 || age >= grainLength) return 0.0f;
        const float phase = static_cast<float>(age) / static_cast<float>(grainLength - 1);
        return 0.5f - 0.5f * std::cos(kTwoPi * phase);
    }

    void scheduleGrains() {
        while (outputAge >= nextGrainAt && nextGrainAt < maxOutputFrames) {
            const bool sourceInside = nextGrainSource >= static_cast<double>(startFrame) &&
                nextGrainSource < static_cast<double>(endFrame);
            if (!sourceInside) {
                nextGrainAt = maxOutputFrames;
                break;
            }
            Grain* slot = nullptr;
            for (auto& grain : grains) {
                if (!grain.active) {
                    slot = &grain;
                    break;
                }
            }
            if (slot == nullptr) {
                slot = &*std::max_element(
                    grains.begin(),
                    grains.end(),
                    [](const Grain& left, const Grain& right) { return left.age < right.age; }
                );
            }
            slot->active = true;
            slot->age = 0;
            slot->readPosition = nextGrainSource;
            nextGrainAt += grainHop;
            nextGrainSource += grainSourceHop;
        }
    }

    void renderSource(float pitchModulation, float& left, float& right) {
        left = 0.0f;
        right = 0.0f;
        if (directMode) {
            left = sample->sample(directPosition, 0);
            right = sample->sample(directPosition, 1);
            directPosition += directStep;
            return;
        }

        for (auto& grain : grains) {
            if (grain.active && grain.age >= grainLength) grain.active = false;
        }
        scheduleGrains();

        float norm = 0.0f;
        const double readIncrement = static_cast<double>(direction) *
            static_cast<double>(sourceRateRatio) * static_cast<double>(pitchRatio * pitchModulation);
        for (auto& grain : grains) {
            if (!grain.active) continue;
            const float window = windowForAge(grain.age);
            if (grain.readPosition >= static_cast<double>(startFrame) &&
                grain.readPosition < static_cast<double>(endFrame)) {
                left += sample->sample(grain.readPosition, 0) * window;
                right += sample->sample(grain.readPosition, 1) * window;
                norm += window;
            }
            grain.readPosition += readIncrement;
            grain.age++;
            if (grain.age >= grainLength) grain.active = false;
        }
        if (norm > kEpsilon) {
            left /= norm;
            right /= norm;
        }
    }

    float processFilter(float input, float& ic1, float& ic2) const {
        const float v3 = input - ic2;
        const float v1 = filterA1 * ic1 + filterA2 * v3;
        const float v2 = ic2 + filterA2 * ic1 + filterA3 * v3;
        ic1 = 2.0f * v1 - ic1;
        ic2 = 2.0f * v2 - ic2;
        return v2;
    }

    bool render(
        int outputSampleRate,
        float bpm,
        float& dryLeft,
        float& dryRight,
        float& delayLeft,
        float& delayRight,
        float& reverbLeft,
        float& reverbRight
    ) {
        dryLeft = dryRight = delayLeft = delayRight = reverbLeft = reverbRight = 0.0f;
        if (!active || sample == nullptr) return false;
        if (autoReleaseAt >= 0 && outputAge >= autoReleaseAt && playMode == 1) noteOff(false);
        if (!active || outputAge >= maxOutputFrames) {
            active = false;
            return false;
        }

        const float lfo = lfoValue(bpm, outputSampleRate);
        float pitchMod = 1.0f;
        if (lfoEnabled && lfoTarget == 2) {
            pitchMod = semitoneRatio(lfo * lfoDepth * 12.0f);
        }

        float left = 0.0f;
        float right = 0.0f;
        renderSource(pitchMod, left, right);

        float cutoffTone = tone;
        if (lfoEnabled && lfoTarget == 3) {
            cutoffTone = clampValue(tone + lfo * lfoDepth * 0.45f, 0.0f, 1.0f);
        }
        const float cutoff = clampValue(
            40.0f * std::pow(500.0f, cutoffTone),
            25.0f,
            static_cast<float>(outputSampleRate) * 0.45f
        );
        const float g = std::tan(kPi * cutoff / static_cast<float>(outputSampleRate));
        const float k = 2.0f - 1.92f * resonance;
        filterA1 = 1.0f / (1.0f + g * (g + k));
        filterA2 = g * filterA1;
        filterA3 = g * filterA2;
        if (tone < 0.999f || resonance > 0.001f || (lfoEnabled && lfoTarget == 3)) {
            left = processFilter(left, filterIc1L, filterIc2L);
            right = processFilter(right, filterIc1R, filterIc2R);
        }

        if (drive > 0.0001f) {
            const float amount = 1.0f + drive * 18.0f;
            const float normalizer = 1.0f / std::max(0.001f, std::tanh(amount));
            left = std::tanh(left * amount) * normalizer;
            right = std::tanh(right * amount) * normalizer;
        }

        if (reductionCounter <= 0) {
            heldL = left;
            heldR = right;
            reductionCounter = reduction;
        }
        reductionCounter--;
        if (bitDepth < 16) {
            const float levels = static_cast<float>((1 << (bitDepth - 1)) - 1);
            heldL = std::round(heldL * levels) / levels;
            heldR = std::round(heldR * levels) / levels;
        }
        left = heldL;
        right = heldR;

        float dynamicPan = pan;
        float ampMod = 1.0f;
        if (lfoEnabled && lfoTarget == 0) {
            ampMod = 1.0f - lfoDepth + lfoDepth * (lfo + 1.0f) * 0.5f;
        } else if (lfoEnabled && lfoTarget == 1) {
            dynamicPan = clampValue(pan + lfo * lfoDepth, -1.0f, 1.0f);
        }
        const float leftPan = std::sqrt(0.5f * (1.0f - dynamicPan));
        const float rightPan = std::sqrt(0.5f * (1.0f + dynamicPan));
        const float env = advanceEnvelope();

        const int64_t fadeFrames = std::min<int64_t>(64, std::max<int64_t>(1, maxOutputFrames / 4));
        const float startFade = clampValue(static_cast<float>(outputAge) / static_cast<float>(fadeFrames), 0.0f, 1.0f);
        const float endFade = clampValue(
            static_cast<float>(maxOutputFrames - outputAge) / static_cast<float>(fadeFrames),
            0.0f,
            1.0f
        );
        const float amplitude = gain * velocity * ampMod * env * std::min(startFade, endFade);
        dryLeft = left * amplitude * leftPan;
        dryRight = right * amplitude * rightPan;
        delayLeft = dryLeft * delaySend;
        delayRight = dryRight * delaySend;
        reverbLeft = dryLeft * reverbSend;
        reverbRight = dryRight * reverbSend;

        outputAge++;
        if (outputAge >= maxOutputFrames || envelopeStage == EnvelopeStage::Done) active = false;
        return active;
    }
};

class SamplerCore::DelayBus {
public:
    explicit DelayBus(int sampleRate)
        : sampleRate_(sampleRate),
          left_(static_cast<size_t>(std::max(1024, sampleRate * 4)), 0.0f),
          right_(left_.size(), 0.0f) {}

    void process(
        float inputLeft,
        float inputRight,
        const MasterParameters& parameters,
        float bpm,
        float& wetLeft,
        float& wetRight
    ) {
        if (!parameters.delayEnabled || parameters.delayMix <= 0.0001f) {
            // Keep clearing the current write point so stale echoes do not return later.
            left_[writeIndex_] = 0.0f;
            right_[writeIndex_] = 0.0f;
            advance();
            wetLeft = wetRight = 0.0f;
            return;
        }
        const float seconds = parameters.delayBeats * 60.0f / clampValue(bpm, 30.0f, 300.0f);
        const int delayFrames = clampValue(
            static_cast<int>(seconds * sampleRate_),
            1,
            static_cast<int>(left_.size()) - 1
        );
        const size_t readIndex = (writeIndex_ + left_.size() - static_cast<size_t>(delayFrames)) % left_.size();
        const float delayedLeft = left_[readIndex];
        const float delayedRight = right_[readIndex];
        const float feedback = parameters.delayFeedback;
        left_[writeIndex_] = inputLeft + (parameters.pingPong ? delayedRight : delayedLeft) * feedback;
        right_[writeIndex_] = inputRight + (parameters.pingPong ? delayedLeft : delayedRight) * feedback;
        wetLeft = delayedLeft * parameters.delayMix;
        wetRight = delayedRight * parameters.delayMix;
        advance();
    }

private:
    void advance() { writeIndex_ = (writeIndex_ + 1u) % left_.size(); }

    int sampleRate_;
    std::vector<float> left_;
    std::vector<float> right_;
    size_t writeIndex_ = 0;
};

class SamplerCore::ReverbBus {
    struct Comb {
        explicit Comb(int length) : buffer(static_cast<size_t>(std::max(8, length)), 0.0f) {}
        float process(float input, float feedback, float damping) {
            const float output = buffer[index];
            filterStore = output * (1.0f - damping) + filterStore * damping;
            buffer[index] = input + filterStore * feedback;
            index = (index + 1u) % buffer.size();
            return output;
        }
        std::vector<float> buffer;
        size_t index = 0;
        float filterStore = 0.0f;
    };

    struct Allpass {
        explicit Allpass(int length) : buffer(static_cast<size_t>(std::max(8, length)), 0.0f) {}
        float process(float input) {
            const float buffered = buffer[index];
            const float output = -input + buffered;
            buffer[index] = input + buffered * 0.5f;
            index = (index + 1u) % buffer.size();
            return output;
        }
        std::vector<float> buffer;
        size_t index = 0;
    };

public:
    explicit ReverbBus(int sampleRate) {
        const float scale = static_cast<float>(sampleRate) / 44'100.0f;
        const int combTimes[] = {1116, 1188, 1277, 1356};
        for (int delay : combTimes) {
            combLeft_.emplace_back(static_cast<int>(delay * scale));
            combRight_.emplace_back(static_cast<int>((delay + 23) * scale));
        }
        const int allpassTimes[] = {556, 441};
        for (int delay : allpassTimes) {
            allpassLeft_.emplace_back(static_cast<int>(delay * scale));
            allpassRight_.emplace_back(static_cast<int>((delay + 23) * scale));
        }
    }

    void process(
        float inputLeft,
        float inputRight,
        const MasterParameters& parameters,
        float& wetLeft,
        float& wetRight
    ) {
        if (!parameters.reverbEnabled || parameters.reverbMix <= 0.0001f) {
            wetLeft = wetRight = 0.0f;
            return;
        }
        const float feedback = 0.70f + parameters.reverbSize * 0.26f;
        const float damping = 0.05f + parameters.reverbDamping * 0.85f;
        const float monoInput = (inputLeft + inputRight) * 0.18f;
        float left = 0.0f;
        float right = 0.0f;
        for (size_t index = 0; index < combLeft_.size(); ++index) {
            left += combLeft_[index].process(monoInput + inputLeft * 0.04f, feedback, damping);
            right += combRight_[index].process(monoInput + inputRight * 0.04f, feedback, damping);
        }
        left *= 0.25f;
        right *= 0.25f;
        for (auto& allpass : allpassLeft_) left = allpass.process(left);
        for (auto& allpass : allpassRight_) right = allpass.process(right);
        wetLeft = left * parameters.reverbMix;
        wetRight = right * parameters.reverbMix;
    }

private:
    std::vector<Comb> combLeft_;
    std::vector<Comb> combRight_;
    std::vector<Allpass> allpassLeft_;
    std::vector<Allpass> allpassRight_;
};

class SamplerCore::MasterProcessor {
public:
    explicit MasterProcessor(int sampleRate)
        : attackCoeff_(std::exp(-1.0f / (0.005f * sampleRate))),
          releaseCoeff_(std::exp(-1.0f / (0.080f * sampleRate))) {}

    void process(float& left, float& right, const MasterParameters& parameters) {
        if (parameters.compressorEnabled) {
            const float detector = std::max(std::abs(left), std::abs(right));
            const float coefficient = detector > envelope_ ? attackCoeff_ : releaseCoeff_;
            envelope_ = coefficient * envelope_ + (1.0f - coefficient) * detector;
            const float threshold = dbToLinear(parameters.compressorThresholdDb);
            if (envelope_ > threshold && envelope_ > kEpsilon) {
                const float inputDb = 20.0f * std::log10(envelope_);
                const float outputDb = parameters.compressorThresholdDb +
                    (inputDb - parameters.compressorThresholdDb) / parameters.compressorRatio;
                const float gainReduction = dbToLinear(outputDb - inputDb);
                left *= gainReduction;
                right *= gainReduction;
            }
        }

        if (parameters.masterDrive > 0.0001f) {
            const float amount = 1.0f + parameters.masterDrive * 12.0f;
            const float normalizer = 1.0f / std::max(0.001f, std::tanh(amount));
            left = std::tanh(left * amount) * normalizer;
            right = std::tanh(right * amount) * normalizer;
        }
        left = softClip(left * parameters.masterGain);
        right = softClip(right * parameters.masterGain);
    }

private:
    float attackCoeff_;
    float releaseCoeff_;
    float envelope_ = 0.0f;
};

SamplerCore::SamplerCore(int outputSampleRate)
    : outputSampleRate_(clampValue(outputSampleRate, 8'000, 192'000)),
      delayBus_(std::make_unique<DelayBus>(outputSampleRate_)),
      reverbBus_(std::make_unique<ReverbBus>(outputSampleRate_)),
      masterProcessor_(std::make_unique<MasterProcessor>(outputSampleRate_)) {
    for (auto& slot : sampleTable_) slot.store(nullptr, std::memory_order_relaxed);
    for (auto& pad : pads_) pad = std::make_unique<AtomicPadState>();
    for (auto& voice : voices_) voice = std::make_unique<Voice>();
    setMasterParameters(MasterParameters{});
}

SamplerCore::~SamplerCore() = default;

int SamplerCore::loadPcm16(
    const int16_t* samples,
    int sampleCount,
    int channelCount,
    int sampleRate
) {
    if (samples == nullptr || sampleCount <= 0 || channelCount < 1 || channelCount > 2 ||
        sampleRate < 8'000 || sampleRate > 384'000 || sampleCount % channelCount != 0) {
        return -1;
    }
    const int id = nextSampleId_.fetch_add(1, std::memory_order_relaxed);
    if (id < 0 || id >= kMaxSamples) return -1;

    auto buffer = std::make_unique<SampleBuffer>();
    buffer->sampleRate = sampleRate;
    buffer->frameCount = sampleCount / channelCount;
    buffer->stereo.resize(static_cast<size_t>(buffer->frameCount) * 2u);
    for (int frame = 0; frame < buffer->frameCount; ++frame) {
        const float left = static_cast<float>(samples[frame * channelCount]) / 32768.0f;
        const float right = channelCount == 2
            ? static_cast<float>(samples[frame * channelCount + 1]) / 32768.0f
            : left;
        buffer->stereo[static_cast<size_t>(frame) * 2u] = left;
        buffer->stereo[static_cast<size_t>(frame) * 2u + 1u] = right;
    }

    SampleBuffer* raw = buffer.get();
    {
        std::lock_guard<std::mutex> lock(sampleControlMutex_);
        ownedSamples_.push_back(std::move(buffer));
    }
    sampleTable_[id].store(raw, std::memory_order_release);
    return id;
}

void SamplerCore::configurePad(int padIndex, const PadParameters& parameters) {
    if (padIndex < 0 || padIndex >= kPadStateCount) return;
    AtomicPadState& pad = *pads_[padIndex];
    // Temporarily make the pad unavailable, publish every field, then publish sampleId last.
    // The acquire load in snapshotPad therefore never observes a half-updated parameter set.
    pad.sampleId.store(-1, std::memory_order_release);
    pad.startFrame.store(std::max(0, parameters.startFrame), std::memory_order_relaxed);
    pad.endFrame.store(std::max(0, parameters.endFrame), std::memory_order_relaxed);
    pad.pitchSemitones.store(clampValue(parameters.pitchSemitones, -24.0f, 24.0f), std::memory_order_relaxed);
    pad.stretchRatio.store(clampValue(parameters.stretchRatio, 0.25f, 4.0f), std::memory_order_relaxed);
    pad.tone.store(clampValue(parameters.tone, 0.0f, 1.0f), std::memory_order_relaxed);
    pad.resonance.store(clampValue(parameters.resonance, 0.0f, 0.95f), std::memory_order_relaxed);
    pad.gain.store(clampValue(parameters.gain, 0.0f, 1.5f), std::memory_order_relaxed);
    pad.pan.store(clampValue(parameters.pan, -1.0f, 1.0f), std::memory_order_relaxed);
    pad.reverse.store(parameters.reverse ? 1 : 0, std::memory_order_relaxed);
    pad.playMode.store(clampValue(parameters.playMode, 0, 1), std::memory_order_relaxed);
    pad.chokeGroup.store(clampValue(parameters.chokeGroup, 0, 8), std::memory_order_relaxed);

    pad.attackMs.store(clampValue(parameters.attackMs, 0.0f, 5'000.0f), std::memory_order_relaxed);
    pad.decayMs.store(clampValue(parameters.decayMs, 0.0f, 5'000.0f), std::memory_order_relaxed);
    pad.sustain.store(clampValue(parameters.sustain, 0.0f, 1.0f), std::memory_order_relaxed);
    pad.releaseMs.store(clampValue(parameters.releaseMs, 1.0f, 10'000.0f), std::memory_order_relaxed);

    pad.lfoEnabled.store(parameters.lfoEnabled ? 1 : 0, std::memory_order_relaxed);
    pad.lfoWaveform.store(clampValue(parameters.lfoWaveform, 0, 4), std::memory_order_relaxed);
    pad.lfoTarget.store(clampValue(parameters.lfoTarget, 0, 3), std::memory_order_relaxed);
    pad.lfoRateHz.store(clampValue(parameters.lfoRateHz, 0.05f, 30.0f), std::memory_order_relaxed);
    pad.lfoDepth.store(clampValue(parameters.lfoDepth, 0.0f, 1.0f), std::memory_order_relaxed);
    pad.lfoTempoSync.store(parameters.lfoTempoSync ? 1 : 0, std::memory_order_relaxed);
    pad.lfoDivisionBeats.store(clampValue(parameters.lfoDivisionBeats, 0.0625f, 4.0f), std::memory_order_relaxed);

    pad.drive.store(clampValue(parameters.drive, 0.0f, 1.0f), std::memory_order_relaxed);
    pad.bitDepth.store(clampValue(parameters.bitDepth, 4, 16), std::memory_order_relaxed);
    pad.sampleRateReduction.store(clampValue(parameters.sampleRateReduction, 1, 32), std::memory_order_relaxed);
    pad.delaySend.store(clampValue(parameters.delaySend, 0.0f, 1.0f), std::memory_order_relaxed);
    pad.reverbSend.store(clampValue(parameters.reverbSend, 0.0f, 1.0f), std::memory_order_relaxed);
    pad.sampleId.store(parameters.sampleId, std::memory_order_release);
}

void SamplerCore::clearPad(int padIndex) {
    if (padIndex < 0 || padIndex >= kPadStateCount) return;
    pads_[padIndex]->sampleId.store(-1, std::memory_order_release);
    releasePad(padIndex);
}

bool SamplerCore::triggerPad(int padIndex, float velocity) {
    return enqueue(Command{CommandType::Trigger, padIndex, clampValue(velocity, 0.0f, 1.0f)});
}

bool SamplerCore::releasePad(int padIndex) {
    return enqueue(Command{CommandType::Release, padIndex, 0.0f});
}

bool SamplerCore::stopAllVoices() {
    return enqueue(Command{CommandType::StopAll, -1, 0.0f});
}

void SamplerCore::setSequence(const uint64_t* padMasks, int stepCount, bool loop) {
    auto sequence = std::make_unique<SequenceData>();
    sequence->loop = loop;
    if (padMasks == nullptr || stepCount <= 0) {
        sequence->masks.push_back(0u);
    } else {
        const int bounded = clampValue(stepCount, 1, 16'384);
        sequence->masks.assign(padMasks, padMasks + bounded);
    }

    std::lock_guard<std::mutex> lock(sequenceControlMutex_);
    const uint64_t epoch = processEpoch_.load(std::memory_order_acquire);

    // Reclaim only on the control thread, several callbacks after retirement. The audio callback
    // can therefore dereference a previously active sequence without locks or shared_ptr traffic.
    for (size_t index = retiredSequences_.size(); index-- > 0;) {
        if (retiredSequenceEpochs_[index] <= epoch) {
            retiredSequences_.erase(retiredSequences_.begin() + static_cast<std::ptrdiff_t>(index));
            retiredSequenceEpochs_.erase(retiredSequenceEpochs_.begin() + static_cast<std::ptrdiff_t>(index));
        }
    }

    auto previous = std::move(activeSequenceOwner_);
    activeSequenceOwner_ = std::move(sequence);
    activeSequence_.store(activeSequenceOwner_.get(), std::memory_order_release);
    if (previous) {
        retiredSequences_.push_back(std::move(previous));
        retiredSequenceEpochs_.push_back(epoch + 3u);
    }
}

void SamplerCore::setTempo(float bpm, float swing) {
    bpm_.store(clampValue(bpm, 30.0f, 300.0f), std::memory_order_relaxed);
    swing_.store(clampValue(swing, 50.0f, 75.0f), std::memory_order_relaxed);
}

void SamplerCore::startTransport() {
    enqueue(Command{CommandType::StartTransport, -1, 0.0f});
}

void SamplerCore::stopTransport() {
    enqueue(Command{CommandType::StopTransport, -1, 0.0f});
}

void SamplerCore::setMasterParameters(const MasterParameters& p) {
    masterInts_[0].store(p.delayEnabled ? 1 : 0, std::memory_order_relaxed);
    masterInts_[1].store(p.pingPong ? 1 : 0, std::memory_order_relaxed);
    masterInts_[2].store(p.reverbEnabled ? 1 : 0, std::memory_order_relaxed);
    masterInts_[3].store(p.compressorEnabled ? 1 : 0, std::memory_order_relaxed);
    masterFloats_[0].store(clampValue(p.delayMix, 0.0f, 1.0f), std::memory_order_relaxed);
    masterFloats_[1].store(clampValue(p.delayFeedback, 0.0f, 0.94f), std::memory_order_relaxed);
    masterFloats_[2].store(clampValue(p.delayBeats, 0.0625f, 4.0f), std::memory_order_relaxed);
    masterFloats_[3].store(clampValue(p.reverbMix, 0.0f, 1.0f), std::memory_order_relaxed);
    masterFloats_[4].store(clampValue(p.reverbSize, 0.0f, 1.0f), std::memory_order_relaxed);
    masterFloats_[5].store(clampValue(p.reverbDamping, 0.0f, 1.0f), std::memory_order_relaxed);
    masterFloats_[6].store(clampValue(p.compressorThresholdDb, -36.0f, 0.0f), std::memory_order_relaxed);
    masterFloats_[7].store(clampValue(p.compressorRatio, 1.0f, 20.0f), std::memory_order_relaxed);
    masterFloats_[8].store(clampValue(p.masterDrive, 0.0f, 1.0f), std::memory_order_relaxed);
    masterFloats_[9].store(clampValue(p.masterGain, 0.0f, 1.5f), std::memory_order_relaxed);
}

bool SamplerCore::enqueue(const Command& command) {
    std::lock_guard<std::mutex> lock(commandProducerMutex_);
    const uint64_t write = commandWrite_.load(std::memory_order_relaxed);
    const uint64_t read = commandRead_.load(std::memory_order_acquire);
    if (write - read >= kCommandCapacity) {
        droppedCommands_.fetch_add(1, std::memory_order_relaxed);
        return false;
    }
    commandBuffer_[write % kCommandCapacity] = command;
    commandWrite_.store(write + 1, std::memory_order_release);
    return true;
}

void SamplerCore::drainCommands() {
    uint64_t read = commandRead_.load(std::memory_order_relaxed);
    const uint64_t write = commandWrite_.load(std::memory_order_acquire);
    while (read < write) {
        const Command command = commandBuffer_[read % kCommandCapacity];
        switch (command.type) {
            case CommandType::Trigger:
                beginVoice(command.padIndex, command.velocity, -1);
                break;
            case CommandType::Release:
                releaseVoicesForPad(command.padIndex);
                break;
            case CommandType::StopAll:
                for (auto& voice : voices_) voice->noteOff(true);
                break;
            case CommandType::StartTransport:
                transportRunning_.store(true, std::memory_order_relaxed);
                nextStep_ = 0;
                framesUntilNextStep_ = 0.0;
                break;
            case CommandType::StopTransport:
                transportRunning_.store(false, std::memory_order_relaxed);
                currentStep_.store(-1, std::memory_order_relaxed);
                break;
        }
        ++read;
    }
    commandRead_.store(read, std::memory_order_release);
}

SamplerCore::PadSnapshot SamplerCore::snapshotPad(int padIndex) const {
    PadSnapshot result;
    result.padIndex = padIndex;
    if (padIndex < 0 || padIndex >= kPadStateCount) return result;
    const AtomicPadState& pad = *pads_[padIndex];
    const int sampleId = pad.sampleId.load(std::memory_order_acquire);
    if (sampleId < 0 || sampleId >= kMaxSamples) return result;
    result.sample = sampleTable_[sampleId].load(std::memory_order_acquire);
    if (result.sample == nullptr) return result;

    result.startFrame = pad.startFrame.load(std::memory_order_relaxed);
    result.endFrame = pad.endFrame.load(std::memory_order_relaxed);
    result.pitchSemitones = pad.pitchSemitones.load(std::memory_order_relaxed);
    result.stretchRatio = pad.stretchRatio.load(std::memory_order_relaxed);
    result.tone = pad.tone.load(std::memory_order_relaxed);
    result.resonance = pad.resonance.load(std::memory_order_relaxed);
    result.gain = pad.gain.load(std::memory_order_relaxed);
    result.pan = pad.pan.load(std::memory_order_relaxed);
    result.reverse = pad.reverse.load(std::memory_order_relaxed) != 0;
    result.playMode = pad.playMode.load(std::memory_order_relaxed);
    result.chokeGroup = pad.chokeGroup.load(std::memory_order_relaxed);
    result.attackMs = pad.attackMs.load(std::memory_order_relaxed);
    result.decayMs = pad.decayMs.load(std::memory_order_relaxed);
    result.sustain = pad.sustain.load(std::memory_order_relaxed);
    result.releaseMs = pad.releaseMs.load(std::memory_order_relaxed);
    result.lfoEnabled = pad.lfoEnabled.load(std::memory_order_relaxed) != 0;
    result.lfoWaveform = pad.lfoWaveform.load(std::memory_order_relaxed);
    result.lfoTarget = pad.lfoTarget.load(std::memory_order_relaxed);
    result.lfoRateHz = pad.lfoRateHz.load(std::memory_order_relaxed);
    result.lfoDepth = pad.lfoDepth.load(std::memory_order_relaxed);
    result.lfoTempoSync = pad.lfoTempoSync.load(std::memory_order_relaxed) != 0;
    result.lfoDivisionBeats = pad.lfoDivisionBeats.load(std::memory_order_relaxed);
    result.drive = pad.drive.load(std::memory_order_relaxed);
    result.bitDepth = pad.bitDepth.load(std::memory_order_relaxed);
    result.sampleRateReduction = pad.sampleRateReduction.load(std::memory_order_relaxed);
    result.delaySend = pad.delaySend.load(std::memory_order_relaxed);
    result.reverbSend = pad.reverbSend.load(std::memory_order_relaxed);
    return result;
}

MasterParameters SamplerCore::snapshotMaster() const {
    MasterParameters p;
    p.delayEnabled = masterInts_[0].load(std::memory_order_relaxed) != 0;
    p.pingPong = masterInts_[1].load(std::memory_order_relaxed) != 0;
    p.reverbEnabled = masterInts_[2].load(std::memory_order_relaxed) != 0;
    p.compressorEnabled = masterInts_[3].load(std::memory_order_relaxed) != 0;
    p.delayMix = masterFloats_[0].load(std::memory_order_relaxed);
    p.delayFeedback = masterFloats_[1].load(std::memory_order_relaxed);
    p.delayBeats = masterFloats_[2].load(std::memory_order_relaxed);
    p.reverbMix = masterFloats_[3].load(std::memory_order_relaxed);
    p.reverbSize = masterFloats_[4].load(std::memory_order_relaxed);
    p.reverbDamping = masterFloats_[5].load(std::memory_order_relaxed);
    p.compressorThresholdDb = masterFloats_[6].load(std::memory_order_relaxed);
    p.compressorRatio = masterFloats_[7].load(std::memory_order_relaxed);
    p.masterDrive = masterFloats_[8].load(std::memory_order_relaxed);
    p.masterGain = masterFloats_[9].load(std::memory_order_relaxed);
    return p;
}

void SamplerCore::beginVoice(int padIndex, float velocity, int autoReleaseFrames) {
    const PadSnapshot pad = snapshotPad(padIndex);
    if (pad.sample == nullptr || pad.endFrame <= pad.startFrame) return;
    if (pad.chokeGroup > 0) releaseChokeGroup(pad.chokeGroup);

    Voice* target = nullptr;
    for (auto& voice : voices_) {
        if (!voice->active) {
            target = voice.get();
            break;
        }
    }
    if (target == nullptr) {
        target = std::min_element(
            voices_.begin(),
            voices_.end(),
            [](const std::unique_ptr<Voice>& left, const std::unique_ptr<Voice>& right) {
                return left->serial < right->serial;
            }
        )->get();
    }
    target->start(pad, outputSampleRate_, velocity, ++voiceSerial_, autoReleaseFrames);
}

void SamplerCore::releaseVoicesForPad(int padIndex) {
    for (auto& voice : voices_) {
        if (voice->active && voice->padIndex == padIndex) voice->noteOff(false);
    }
}

void SamplerCore::releaseChokeGroup(int chokeGroup) {
    for (auto& voice : voices_) {
        if (voice->active && voice->chokeGroup == chokeGroup) voice->noteOff(true);
    }
}

double SamplerCore::stepLengthFrames(int step) const {
    const double bpm = static_cast<double>(clampValue(bpm_.load(std::memory_order_relaxed), 30.0f, 300.0f));
    const double swing = static_cast<double>(clampValue(swing_.load(std::memory_order_relaxed), 50.0f, 75.0f));
    const double straight = static_cast<double>(outputSampleRate_) * 60.0 / bpm / 4.0;
    const double longRatio = swing / 50.0;
    return step % 2 == 0 ? straight * longRatio : straight * (2.0 - longRatio);
}

void SamplerCore::processTransportFrame() {
    if (!transportRunning_.load(std::memory_order_relaxed)) return;
    if (framesUntilNextStep_ <= 0.0) {
        SequenceData* sequence = activeSequence_.load(std::memory_order_acquire);
        if (sequence == nullptr || sequence->masks.empty()) {
            transportRunning_.store(false, std::memory_order_relaxed);
            currentStep_.store(-1, std::memory_order_relaxed);
            return;
        }
        if (nextStep_ >= static_cast<int>(sequence->masks.size())) {
            if (sequence->loop) {
                nextStep_ = 0;
            } else {
                transportRunning_.store(false, std::memory_order_relaxed);
                currentStep_.store(-1, std::memory_order_relaxed);
                return;
            }
        }

        const int step = nextStep_;
        currentStep_.store(step, std::memory_order_relaxed);
        const double length = stepLengthFrames(step);
        const int gateFrames = std::max(1, static_cast<int>(std::llround(length * 0.92)));
        const uint64_t mask = sequence->masks[static_cast<size_t>(step)];
        for (int pad = 0; pad < kPadCount; ++pad) {
            if ((mask & (uint64_t{1} << pad)) != 0u) beginVoice(pad, 1.0f, gateFrames);
        }
        framesUntilNextStep_ += length;
        ++nextStep_;
    }
    framesUntilNextStep_ -= 1.0;
}

void SamplerCore::process(float* output, int frameCount) {
    if (output == nullptr || frameCount <= 0) return;
    processEpoch_.fetch_add(1u, std::memory_order_release);
    drainCommands();
    const MasterParameters master = snapshotMaster();
    const float bpm = bpm_.load(std::memory_order_relaxed);
    int activeCount = 0;

    for (int frame = 0; frame < frameCount; ++frame) {
        processTransportFrame();

        float dryLeft = 0.0f;
        float dryRight = 0.0f;
        float delaySendLeft = 0.0f;
        float delaySendRight = 0.0f;
        float reverbSendLeft = 0.0f;
        float reverbSendRight = 0.0f;

        for (auto& voice : voices_) {
            if (!voice->active) continue;
            float voiceLeft = 0.0f;
            float voiceRight = 0.0f;
            float voiceDelayLeft = 0.0f;
            float voiceDelayRight = 0.0f;
            float voiceReverbLeft = 0.0f;
            float voiceReverbRight = 0.0f;
            voice->render(
                outputSampleRate_,
                bpm,
                voiceLeft,
                voiceRight,
                voiceDelayLeft,
                voiceDelayRight,
                voiceReverbLeft,
                voiceReverbRight
            );
            dryLeft += voiceLeft;
            dryRight += voiceRight;
            delaySendLeft += voiceDelayLeft;
            delaySendRight += voiceDelayRight;
            reverbSendLeft += voiceReverbLeft;
            reverbSendRight += voiceReverbRight;
        }

        float delayLeft = 0.0f;
        float delayRight = 0.0f;
        delayBus_->process(
            delaySendLeft,
            delaySendRight,
            master,
            bpm,
            delayLeft,
            delayRight
        );
        float reverbLeft = 0.0f;
        float reverbRight = 0.0f;
        reverbBus_->process(
            reverbSendLeft + delayLeft * 0.15f,
            reverbSendRight + delayRight * 0.15f,
            master,
            reverbLeft,
            reverbRight
        );

        float mixedLeft = dryLeft + delayLeft + reverbLeft;
        float mixedRight = dryRight + delayRight + reverbRight;
        masterProcessor_->process(mixedLeft, mixedRight, master);
        output[frame * 2] = mixedLeft;
        output[frame * 2 + 1] = mixedRight;
    }

    for (const auto& voice : voices_) if (voice->active) ++activeCount;
    activeVoiceCount_.store(activeCount, std::memory_order_relaxed);
}

} // namespace choplab
