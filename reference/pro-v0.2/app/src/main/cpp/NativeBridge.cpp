#include <jni.h>
#include <android/log.h>
#include <oboe/Oboe.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>

#include "SamplerCore.h"

namespace {
constexpr const char* kTag = "ChopLabOboe";

void logError(const char* message) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message);
}

void throwIllegalState(JNIEnv* env, const std::string& message) {
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

class EngineHost final : public oboe::AudioStreamDataCallback,
                         public oboe::AudioStreamErrorCallback {
public:
    EngineHost(int preferredSampleRate, int preferredFramesPerBurst) {
        open(preferredSampleRate, preferredFramesPerBurst);
    }

    ~EngineHost() override {
        std::lock_guard<std::mutex> lock(streamMutex_);
        shuttingDown_.store(true, std::memory_order_release);
        if (stream_) {
            stream_->requestStop();
            stream_->close();
            stream_.reset();
        }
        core_.reset();
    }

    bool valid() const { return valid_.load(std::memory_order_acquire); }
    const std::string& errorMessage() const { return errorMessage_; }
    choplab::SamplerCore* core() const { return core_.get(); }

    int sampleRate() const {
        return stream_ ? stream_->getSampleRate() : 0;
    }

    int framesPerBurst() const {
        return stream_ ? stream_->getFramesPerBurst() : 0;
    }

    int xRunCount() const {
        if (!stream_) return 0;
        const auto result = stream_->getXRunCount();
        return result ? result.value() : 0;
    }

    int backend() const {
        if (!stream_) return 0;
        switch (stream_->getAudioApi()) {
            case oboe::AudioApi::AAudio: return 1;
            case oboe::AudioApi::OpenSLES: return 2;
            default: return 0;
        }
    }

    int errorCode() const { return lastError_.load(std::memory_order_relaxed); }

    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* audioStream,
        void* audioData,
        int32_t numFrames
    ) override {
        auto* output = static_cast<float*>(audioData);
        if (output == nullptr || numFrames <= 0) return oboe::DataCallbackResult::Stop;
        if (shuttingDown_.load(std::memory_order_acquire) || !core_ ||
            audioStream->getChannelCount() != 2 || audioStream->getFormat() != oboe::AudioFormat::Float) {
            std::memset(output, 0, static_cast<size_t>(numFrames) * 2u * sizeof(float));
            return shuttingDown_.load(std::memory_order_relaxed)
                ? oboe::DataCallbackResult::Stop
                : oboe::DataCallbackResult::Continue;
        }
        core_->process(output, numFrames);
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorBeforeClose(oboe::AudioStream*, oboe::Result error) override {
        lastError_.store(static_cast<int>(error), std::memory_order_relaxed);
        valid_.store(false, std::memory_order_release);
    }

    void onErrorAfterClose(oboe::AudioStream*, oboe::Result error) override {
        lastError_.store(static_cast<int>(error), std::memory_order_relaxed);
        valid_.store(false, std::memory_order_release);
    }

private:
    oboe::Result openAttempt(
        oboe::SharingMode sharingMode,
        int preferredSampleRate,
        std::shared_ptr<oboe::AudioStream>& destination
    ) {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(sharingMode)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setUsage(oboe::Usage::Game)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this)
            ->setFormatConversionAllowed(true)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium);
        if (preferredSampleRate >= 8'000 && preferredSampleRate <= 192'000) {
            builder.setSampleRate(preferredSampleRate);
        }
        return builder.openStream(destination);
    }

    void open(int preferredSampleRate, int preferredFramesPerBurst) {
        oboe::Result result = openAttempt(
            oboe::SharingMode::Exclusive,
            preferredSampleRate,
            stream_
        );
        if (result != oboe::Result::OK || !stream_) {
            stream_.reset();
            result = openAttempt(oboe::SharingMode::Shared, preferredSampleRate, stream_);
        }
        if (result != oboe::Result::OK || !stream_) {
            errorMessage_ = std::string("Oboe output open failed: ") + oboe::convertToText(result);
            logError(errorMessage_.c_str());
            return;
        }

        const int actualRate = stream_->getSampleRate();
        if (actualRate < 8'000) {
            errorMessage_ = "Oboe returned an invalid sample rate";
            stream_->close();
            stream_.reset();
            return;
        }
        core_ = std::make_unique<choplab::SamplerCore>(actualRate);

        const int actualBurst = std::max(1, stream_->getFramesPerBurst());
        const int requestedBurst = preferredFramesPerBurst > 0
            ? preferredFramesPerBurst
            : actualBurst;
        const int targetFrames = std::max(actualBurst * 2, requestedBurst * 2);
        const int capacityFrames = std::max(actualBurst, stream_->getBufferCapacityInFrames());
        stream_->setBufferSizeInFrames(std::min(targetFrames, capacityFrames));

        result = stream_->requestStart();
        if (result != oboe::Result::OK) {
            errorMessage_ = std::string("Oboe output start failed: ") + oboe::convertToText(result);
            logError(errorMessage_.c_str());
            stream_->close();
            stream_.reset();
            core_.reset();
            return;
        }
        valid_.store(true, std::memory_order_release);
    }

    mutable std::mutex streamMutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::unique_ptr<choplab::SamplerCore> core_;
    std::atomic<bool> valid_{false};
    std::atomic<bool> shuttingDown_{false};
    std::atomic<int> lastError_{0};
    std::string errorMessage_;
};

EngineHost* fromHandle(jlong handle) {
    return reinterpret_cast<EngineHost*>(static_cast<intptr_t>(handle));
}

choplab::SamplerCore* coreFromHandle(jlong handle) {
    EngineHost* host = fromHandle(handle);
    return host == nullptr ? nullptr : host->core();
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_choplab_sampler_audio_NativeSamplerEngine_nativeCreate(
    JNIEnv* env,
    jobject,
    jint preferredSampleRate,
    jint preferredFramesPerBurst
) {
    auto host = std::make_unique<EngineHost>(preferredSampleRate, preferredFramesPerBurst);
    if (!host->valid()) {
        throwIllegalState(env, host->errorMessage().empty()
            ? "Oboe engine could not be initialized"
            : host->errorMessage());
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(host.release()));
}

extern "C" JNIEXPORT void JNICALL
Java_com_choplab_sampler_audio_NativeSamplerEngine_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete fromHandle(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_choplab_sampler_audio_NativeSamplerEngine_nativeLoadSample(
    JNIEnv* env,
    jobject,
    jlong handle,
    jshortArray pcm,
    jint channelCount,
    jint sampleRate
) {
    auto* core = coreFromHandle(handle);
    if (core == nullptr || pcm == nullptr) return -1;
    const jsize count = env->GetArrayLength(pcm);
    if (count <= 0) return -1;
    jboolean copied = JNI_FALSE;
    jshort* values = env->GetShortArrayElements(pcm, &copied);
    if (values == nullptr) return -1;
    const int result = core->loadPcm16(
        reinterpret_cast<const int16_t*>(values),
        static_cast<int>(count),
        channelCount,
        sampleRate
    );
    env->ReleaseShortArrayElements(pcm, values, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_choplab_sampler_audio_NativeSamplerEngine_nativeConfigurePad(
    JNIEnv*, jobject, jlong handle,
    jint padIndex, jint sampleId, jint startFrame, jint endFrame,
    jfloat pitch, jfloat stretch, jfloat tone, jfloat resonance,
    jfloat gain, jfloat pan, jboolean reverse, jint playMode, jint chokeGroup,
    jfloat attackMs, jfloat decayMs, jfloat sustain, jfloat releaseMs,
    jboolean lfoEnabled, jint lfoWaveform, jint lfoTarget, jfloat lfoRateHz,
    jfloat lfoDepth, jboolean lfoTempoSync, jfloat lfoDivisionBeats,
    jfloat drive, jint bitDepth, jint sampleRateReduction,
    jfloat delaySend, jfloat reverbSend
) {
    auto* core = coreFromHandle(handle);
    if (core == nullptr) return;
    choplab::PadParameters p;
    p.sampleId = sampleId;
    p.startFrame = startFrame;
    p.endFrame = endFrame;
    p.pitchSemitones = pitch;
    p.stretchRatio = stretch;
    p.tone = tone;
    p.resonance = resonance;
    p.gain = gain;
    p.pan = pan;
    p.reverse = reverse == JNI_TRUE;
    p.playMode = playMode;
    p.chokeGroup = chokeGroup;
    p.attackMs = attackMs;
    p.decayMs = decayMs;
    p.sustain = sustain;
    p.releaseMs = releaseMs;
    p.lfoEnabled = lfoEnabled == JNI_TRUE;
    p.lfoWaveform = lfoWaveform;
    p.lfoTarget = lfoTarget;
    p.lfoRateHz = lfoRateHz;
    p.lfoDepth = lfoDepth;
    p.lfoTempoSync = lfoTempoSync == JNI_TRUE;
    p.lfoDivisionBeats = lfoDivisionBeats;
    p.drive = drive;
    p.bitDepth = bitDepth;
    p.sampleRateReduction = sampleRateReduction;
    p.delaySend = delaySend;
    p.reverbSend = reverbSend;
    core->configurePad(padIndex, p);
}

extern "C" JNIEXPORT void JNICALL
Java_com_choplab_sampler_audio_NativeSamplerEngine_nativeClearPad(
    JNIEnv*, jobject, jlong handle, jint padIndex
) {
    if (auto* core = coreFromHandle(handle)) core->clearPad(padIndex);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_choplab_sampler_audio_NativeSamplerEngine_nativeTriggerPad(
    JNIEnv*, jobject, jlong handle, jint padIndex, jfloat velocity
) {
    auto* core = coreFromHandle(handle);
    return core != nullptr && core->triggerPad(padIndex, velocity) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_choplab_sampler_audio_NativeSamplerEngine_nativeReleasePad(
    JNIEnv*, jobject, jlong handle, jint padIndex
) {
    auto* core = coreFromHandle(handle);
    return core != nullptr && core->releasePad(padIndex) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_choplab_sampler_audio_NativeSamplerEngine_nativeStopAll(
    JNIEnv*, jobject, jlong handle
) {
    if (auto* core = coreFromHandle(handle)) core->stopAllVoices();
}

extern "C" JNIEXPORT void JNICALL
Java_com_choplab_sampler_audio_NativeSamplerEngine_nativeSetSequence(
    JNIEnv* env, jobject, jlong handle, jlongArray masks, jboolean loop
) {
    auto* core = coreFromHandle(handle);
    if (core == nullptr) return;
    if (masks == nullptr) {
        core->setSequence(nullptr, 0, loop == JNI_TRUE);
        return;
    }
    const jsize count = env->GetArrayLength(masks);
    jlong* values = env->GetLongArrayElements(masks, nullptr);
    if (values == nullptr) return;
    core->setSequence(
        reinterpret_cast<const uint64_t*>(values),
        static_cast<int>(count),
        loop == JNI_TRUE
    );
    env->ReleaseLongArrayElements(masks, values, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_choplab_sampler_audio_NativeSamplerEngine_nativeSetTempo(
    JNIEnv*, jobject, jlong handle, jfloat bpm, jfloat swing
) {
    if (auto* core = coreFromHandle(handle)) core->setTempo(bpm, swing);
}

extern "C" JNIEXPORT void JNICALL
Java_com_choplab_sampler_audio_NativeSamplerEngine_nativeStartTransport(
    JNIEnv*, jobject, jlong handle
) {
    if (auto* core = coreFromHandle(handle)) core->startTransport();
}

extern "C" JNIEXPORT void JNICALL
Java_com_choplab_sampler_audio_NativeSamplerEngine_nativeStopTransport(
    JNIEnv*, jobject, jlong handle
) {
    if (auto* core = coreFromHandle(handle)) core->stopTransport();
}

extern "C" JNIEXPORT void JNICALL
Java_com_choplab_sampler_audio_NativeSamplerEngine_nativeSetMaster(
    JNIEnv*, jobject, jlong handle,
    jboolean delayEnabled, jfloat delayMix, jfloat delayFeedback, jfloat delayBeats,
    jboolean pingPong, jboolean reverbEnabled, jfloat reverbMix, jfloat reverbSize,
    jfloat reverbDamping, jboolean compressorEnabled, jfloat compressorThresholdDb,
    jfloat compressorRatio, jfloat masterDrive, jfloat masterGain
) {
    auto* core = coreFromHandle(handle);
    if (core == nullptr) return;
    choplab::MasterParameters p;
    p.delayEnabled = delayEnabled == JNI_TRUE;
    p.delayMix = delayMix;
    p.delayFeedback = delayFeedback;
    p.delayBeats = delayBeats;
    p.pingPong = pingPong == JNI_TRUE;
    p.reverbEnabled = reverbEnabled == JNI_TRUE;
    p.reverbMix = reverbMix;
    p.reverbSize = reverbSize;
    p.reverbDamping = reverbDamping;
    p.compressorEnabled = compressorEnabled == JNI_TRUE;
    p.compressorThresholdDb = compressorThresholdDb;
    p.compressorRatio = compressorRatio;
    p.masterDrive = masterDrive;
    p.masterGain = masterGain;
    core->setMasterParameters(p);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_choplab_sampler_audio_NativeSamplerEngine_nativeGetCurrentStep(
    JNIEnv*, jobject, jlong handle
) {
    auto* core = coreFromHandle(handle);
    return core == nullptr ? -1 : core->currentStep();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_choplab_sampler_audio_NativeSamplerEngine_nativeGetActiveVoiceCount(
    JNIEnv*, jobject, jlong handle
) {
    auto* core = coreFromHandle(handle);
    return core == nullptr ? 0 : core->activeVoiceCount();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_choplab_sampler_audio_NativeSamplerEngine_nativeGetDroppedCommandCount(
    JNIEnv*, jobject, jlong handle
) {
    auto* core = coreFromHandle(handle);
    return core == nullptr ? 0 : static_cast<jlong>(core->droppedCommandCount());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_choplab_sampler_audio_NativeSamplerEngine_nativeGetSampleRate(
    JNIEnv*, jobject, jlong handle
) {
    auto* host = fromHandle(handle);
    return host == nullptr ? 0 : host->sampleRate();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_choplab_sampler_audio_NativeSamplerEngine_nativeGetFramesPerBurst(
    JNIEnv*, jobject, jlong handle
) {
    auto* host = fromHandle(handle);
    return host == nullptr ? 0 : host->framesPerBurst();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_choplab_sampler_audio_NativeSamplerEngine_nativeGetXRunCount(
    JNIEnv*, jobject, jlong handle
) {
    auto* host = fromHandle(handle);
    return host == nullptr ? 0 : host->xRunCount();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_choplab_sampler_audio_NativeSamplerEngine_nativeGetBackend(
    JNIEnv*, jobject, jlong handle
) {
    auto* host = fromHandle(handle);
    return host == nullptr ? 0 : host->backend();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_choplab_sampler_audio_NativeSamplerEngine_nativeGetErrorCode(
    JNIEnv*, jobject, jlong handle
) {
    auto* host = fromHandle(handle);
    return host == nullptr ? 0 : host->errorCode();
}
