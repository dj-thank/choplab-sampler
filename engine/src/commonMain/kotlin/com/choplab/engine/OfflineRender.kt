package com.choplab.engine

import kotlin.math.floor

/** Worker-only export seam; adapters own files/headers. Uses exactly the playback engine. */
object OfflineRender {
    fun render(program: EngineProgram, commands: List<EngineCommand>, frameCount: Int,
               tailFrames: Int = 0, blockFrames: Int = 480): FloatArray {
        require(frameCount >= 0 && tailFrames >= 0 && blockFrames in 1..65_536)
        val total = frameCount.toLong() + tailFrames + MasterLimiter.LOOKAHEAD_FRAMES
        require(total * 8 <= EngineFormat.MAX_RESIDENT_BYTES && commands.size <= 8192)
        var capacity = 2
        while (capacity < commands.size) capacity *= 2
        val engine = EngineCore(program, EngineConfig(controlCapacity = capacity, outputMode = EngineOutputMode.EXPORT))
        for (command in commands) {
            if (command is EngineCommand.OriginalSourceCommand || command is EngineCommand.SetSongMonitorGain) continue
            require(engine.controls.offer(command) == OfferResult.ACCEPTED)
        }
        val output = FloatArray(total.toInt() * 2)
        var offset = 0
        while (offset < total) {
            val count = minOf(blockFrames, total.toInt() - offset)
            engine.render(output, offset, count)
            offset += count
        }
        return output.copyOfRange(MasterLimiter.LOOKAHEAD_FRAMES * 2, output.size)
    }
}

/** Seeded TPDF is applied only at final integer quantization. Output is signed little-endian PCM. */
class PcmQuantizer(seed: Int, val bits: Int = 24, val dither: Boolean = true) {
    init { require(bits == 16 || bits == 24) }
    private var randomState = if (seed == 0) 0x6D2B79F5 else seed
    private val scale = if (bits == 16) 32768 else 8388608
    fun encode(input: FloatArray, output: ByteArray, sampleOffset: Int = 0, sampleCount: Int = input.size - sampleOffset,
               outputByteOffset: Int = 0) {
        require(sampleOffset >= 0 && sampleCount >= 0 && sampleOffset.toLong() + sampleCount <= input.size)
        require(outputByteOffset >= 0 && outputByteOffset.toLong() + sampleCount.toLong() * (bits / 8) <= output.size)
        var destination = outputByteOffset
        for (i in sampleOffset until sampleOffset + sampleCount) {
            val value = input[i]
            require(value.isFinite())
            val noise = if (dither) uniform() - uniform() else 0.0
            val quantized = floor(value.toDouble().coerceIn(-1.0, 1.0) * scale + noise + 0.5)
                .coerceIn(-scale.toDouble(), scale - 1.0).toInt()
            output[destination++] = quantized.toByte()
            output[destination++] = (quantized shr 8).toByte()
            if (bits == 24) output[destination++] = (quantized shr 16).toByte()
        }
    }
    private fun uniform(): Double {
        var x = randomState
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        randomState = x
        return (x.toLong() and 0xFFFFFFFFL) / 4294967296.0
    }
}
