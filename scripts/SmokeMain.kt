import com.choplab.sampler.audio.PatternRenderer
import com.choplab.sampler.audio.TransientDetector
import com.choplab.sampler.audio.WavFileWriter
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.SliceRange
import com.choplab.sampler.model.sliceRanges
import com.choplab.sampler.model.stepKey
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

fun main() {
    val sampleRate = 48_000
    val samples = ShortArray(sampleRate * 2)
    val expectedFrames = listOf(12_000, 36_000, 60_000, 84_000)
    expectedFrames.forEach { onset ->
        repeat(1_000) { offset ->
            val envelope = 1f - offset / 1_000f
            samples[onset + offset] = (
                sin(2.0 * PI * 900.0 * offset / sampleRate) * envelope * Short.MAX_VALUE
            ).toInt().toShort()
        }
    }

    val transients = TransientDetector.detect(
        samples = samples,
        startFrame = 0,
        endFrame = samples.size,
        sampleRate = sampleRate,
        maxSlices = 8,
    )
    check(transients.size >= 3) { "Transient detector returned too few markers: $transients" }

    val audio = PcmAudio(name = "smoke", samples = samples, sampleRate = sampleRate)
    val slices = SamplerUiState(
        currentAudio = audio,
        rangeStartFrame = 1_000,
        rangeEndFrame = 90_000,
        sliceMarkers = listOf(60_000, 24_000, 24_000, 200, 95_000),
    ).sliceRanges()
    check(
        slices == listOf(
            SliceRange(1_000, 24_000),
            SliceRange(24_000, 60_000),
            SliceRange(60_000, 90_000),
        ),
    ) { "Unexpected slices: $slices" }

    val wavTest = File.createTempFile("choplab-writer", ".wav")
    WavFileWriter(wavTest, sampleRate, 1).use { writer ->
        writer.writePcm16(shortArrayOf(0, 1_000, -1_000, Short.MAX_VALUE))
    }
    val wavBytes = wavTest.readBytes()
    check(String(wavBytes.copyOfRange(0, 4)) == "RIFF")
    check(String(wavBytes.copyOfRange(8, 12)) == "WAVE")
    check(littleEndianInt(wavBytes, 40) == 8)
    wavTest.delete()

    val pads = List(SamplerConfig.PAD_COUNT) { index ->
        if (index == 0) {
            PadModel(
                globalIndex = index,
                audio = audio,
                startFrame = 11_500,
                endFrame = 13_500,
                pitchSemitones = 3f,
                tone = 0.8f,
                gain = 0.9f,
                reverse = true,
                chokeGroup = 1,
            )
        } else {
            PadModel(index)
        }
    }
    val renderFile = File.createTempFile("choplab-render", ".wav")
    val summary = PatternRenderer.renderToWav(
        outputFile = renderFile,
        pads = pads,
        activeSteps = setOf(stepKey(0, 0), stepKey(0, 4), stepKey(0, 8), stepKey(0, 12)),
        bpm = 96f,
        swing = 58f,
        bars = 1,
    )
    check(summary.frameCount > 0)
    val expectedDataBytes = summary.frameCount * Short.SIZE_BYTES
    val renderBytes = renderFile.readBytes()
    check(littleEndianInt(renderBytes, 40) == expectedDataBytes)
    renderFile.delete()

    println(
        "PASS: transients=$transients, slices=${slices.size}, " +
            "WAV header valid, pattern=${summary.frameCount} frames",
    )
}

private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
    ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .int
