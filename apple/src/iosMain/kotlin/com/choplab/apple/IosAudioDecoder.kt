package com.choplab.apple

import com.choplab.core.model.ProjectLimits
import com.choplab.engine.EngineFormat
import kotlinx.cinterop.*
import platform.AVFAudio.AVAudioFile
import platform.AVFAudio.AVAudioPCMBuffer
import platform.Foundation.NSError
import platform.Foundation.NSURL

internal data class AudioInfo(val sampleRate: Int, val channels: Int, val frames: Long)
internal class DecodedAudio(val info: AudioInfo, val samples: FloatArray)

/**
 * Reads an original's metadata and samples. WAV uses the shared RIFF rules so its metadata is the same on every host;
 * other containers use Apple's decoder (AVAudioFile). A format Apple cannot open is refused, never guessed.
 */
internal object IosAudioDecoder {
    fun inspect(path: String, extension: String): AudioInfo =
        if (extension == "wav") FileReader(path).use { reader -> IosWav.inspect(reader.asInput()).let { AudioInfo(it.sampleRate, it.channels, it.frames) } }
        else openApple(path).use { it.info }

    fun decode(path: String, extension: String, maxDecodedBytes: Long = EngineFormat.MAX_RESIDENT_BYTES,
               cancelled: () -> Boolean = { false }): DecodedAudio {
        if (extension == "wav") return FileReader(path).use { reader ->
            val audio = IosWav.read(reader.asInput(), maxDecodedBytes = maxDecodedBytes)
            DecodedAudio(AudioInfo(audio.info.sampleRate, audio.info.channels, audio.info.frames), audio.samples)
        }
        return openApple(path).use { file -> file.decode(maxDecodedBytes, cancelled) }
    }

    private fun openApple(path: String): AppleFile = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        // A failing Objective-C initializer surfaces as an exception or an error out-parameter.
        val file: AVAudioFile? = try { AVAudioFile(forReading = NSURL.fileURLWithPath(path), error = error.ptr) } catch (_: Throwable) { null }
        require(file != null && error.value == null) { "This audio format cannot be opened on iPadOS" }
        AppleFile(file)
    }

    private class AppleFile(private val file: AVAudioFile) : AutoCloseable {
        val info: AudioInfo
        init {
            val format = file.processingFormat
            val rate = format.sampleRate
            val channels = format.channelCount.toInt()
            require(rate == kotlin.math.floor(rate) && rate.toInt() in 8_000..192_000) { "Unsupported sample rate" }
            require(channels in 1..2) { "Only mono and stereo originals are supported" }
            require(file.length in 1..ProjectLimits.MAX_FRAMES) { "Audio length outside limits" }
            info = AudioInfo(rate.toInt(), channels, file.length)
        }

        fun decode(maxDecodedBytes: Long, cancelled: () -> Boolean): DecodedAudio {
            val total = info.frames * info.channels
            require(total * 4 <= maxDecodedBytes) { "Decoded audio exceeds residency limit" }
            val samples = FloatArray(total.toInt())
            val chunk = 32_768u
            val buffer = requireNotNull(AVAudioPCMBuffer(pCMFormat = file.processingFormat, frameCapacity = chunk)) { "No decode buffer" }
            var frame = 0L
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                while (frame < info.frames) {
                    require(!cancelled()) { "Decode cancelled" }
                    require(file.readIntoBuffer(buffer, error.ptr) && error.value == null) { "Audio decode failed" }
                    val count = buffer.frameLength.toInt()
                    if (count == 0) break
                    require(frame + count <= info.frames) { "Decoded more frames than declared" }
                    val channels = requireNotNull(buffer.floatChannelData)
                    for (c in 0 until info.channels) {
                        val source = requireNotNull(channels[c])
                        var at = (frame * info.channels).toInt() + c
                        for (i in 0 until count) {
                            val value = source[i]
                            require(value.isFinite()) { "Non-finite decoded sample" }
                            samples[at] = value
                            at += info.channels
                        }
                    }
                    frame += count
                }
            }
            require(frame == info.frames) { "Decoded length differs from the declared length" }
            return DecodedAudio(info, samples)
        }

        override fun close() { file.close() }
    }
}
