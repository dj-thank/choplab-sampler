package com.choplab.apple

import kotlinx.serialization.json.*
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioSession
import platform.Foundation.NSError
import platform.Foundation.NSThread
import platform.Foundation.NSBundle

internal enum class AudioOutputStage { SESSION_CATEGORY, SESSION_ACTIVATE, OUTPUT_FORMAT, ENGINE_START, READY, UNEXPECTED_FAILURE, DISABLED }

/** Capability evidence only: no device names, identifiers, file paths or localized error text. */
internal data class AudioOutputCapability(
    val stage: AudioOutputStage,
    val errorDomain: String? = null,
    val errorCode: Long? = null,
    val sessionSampleRate: Double = 0.0,
    val sessionOutputChannels: Long = 0,
    val outputCount: Int = 0,
    val hardwareSampleRate: Double? = null,
    val hardwareChannels: Long? = null,
    val hardwareCommonFormat: Long? = null,
    val mainThread: Boolean = false,
    val hasBundleIdentifier: Boolean = false,
) {
    fun json(): JsonObject = buildJsonObject {
        put("stage", stage.name)
        put("availableAtOpen", stage == AudioOutputStage.READY)
        errorDomain?.let { put("errorDomain", it) }
        errorCode?.let { put("errorCode", it) }
        put("sessionSampleRate", sessionSampleRate)
        put("sessionOutputChannels", sessionOutputChannels)
        put("outputCount", outputCount)
        hardwareSampleRate?.let { put("hardwareSampleRate", it) }
        hardwareChannels?.let { put("hardwareChannels", it) }
        hardwareCommonFormat?.let { put("hardwareCommonFormat", it) }
        put("mainThread", mainThread)
        put("hasBundleIdentifier", hasBundleIdentifier)
        put("engineSampleRate", 48_000); put("engineChannels", 2)
    }

    companion object {
        fun capture(stage: AudioOutputStage, error: NSError? = null, hardware: AVAudioFormat? = null): AudioOutputCapability {
            val session = AVAudioSession.sharedInstance()
            return AudioOutputCapability(stage,
                error?.domain?.filter { it.isLetterOrDigit() || it in "._-" }?.take(80), error?.code,
                session.sampleRate.takeIf { it.isFinite() } ?: 0.0, session.outputNumberOfChannels.toLong(),
                session.currentRoute.outputs.size, hardware?.sampleRate?.takeIf { it.isFinite() },
                hardware?.channelCount?.toLong(), hardware?.commonFormat?.toLong(), NSThread.isMainThread,
                NSBundle.mainBundle.bundleIdentifier != null)
        }
    }
}

internal data class AudioOutputOpen(val output: IosAudioOutput?, val capability: AudioOutputCapability) {
    companion object {
        fun disabled() = AudioOutputOpen(null, AudioOutputCapability(AudioOutputStage.DISABLED))
    }
}
