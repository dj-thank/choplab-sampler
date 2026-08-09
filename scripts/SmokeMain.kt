import com.choplab.sampler.audio.PatternRenderer
import com.choplab.sampler.audio.TransientDetector
import com.choplab.sampler.audio.WavFileWriter
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.PcmBuffer
import com.choplab.sampler.model.AudioAssetId
import com.choplab.sampler.model.AudioAssetMetadata
import com.choplab.sampler.model.LegacyProjectAdapter
import com.choplab.sampler.model.ProjectPad
import com.choplab.sampler.model.ProjectSnapshot
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.SliceRange
import com.choplab.sampler.model.assignRangesToPads
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

    val sourceStereo = shortArrayOf(100, -100, 200, -200)
    val stereo = PcmBuffer.fromInterleaved(
        samples = sourceStereo,
        sampleRate = sampleRate,
        channelCount = 2,
    )
    sourceStereo[0] = 9_999
    check(stereo.frameCount == 2)
    check(stereo.sampleAt(frame = 0, channel = 0) == 100.toShort())
    check(stereo.sampleAt(frame = 0, channel = 1) == (-100).toShort())
    expectInvalid {
        PcmBuffer.fromInterleaved(shortArrayOf(1, 2, 3), sampleRate, channelCount = 2)
    }

    val boundedAsset = AudioAssetMetadata(
        id = AudioAssetId("bounded-audio"),
        name = "bounded",
        sampleRate = sampleRate,
        channelCount = 2,
        frameCount = 2,
    )
    expectInvalid {
        ProjectSnapshot(
            projectId = "invalid-range",
            name = "Invalid range",
            audioAssets = listOf(boundedAsset),
            pads = List(SamplerConfig.PAD_COUNT) { index ->
                if (index == 0) ProjectPad(index, boundedAsset.id, 0, 3) else ProjectPad(index)
            },
        )
    }

    val assignmentState = SamplerUiState(
        currentAudio = audio,
        rangeStartFrame = 1_000,
        rangeEndFrame = 90_000,
        sliceMarkers = listOf(24_000, 60_000),
        activeSliceIndex = 0,
        selectedBank = 1,
        selectedPad = 31,
        autoNextPad = true,
    )
    val assignment = assignRangesToPads(
        state = assignmentState,
        ranges = listOf(SliceRange(1_000, 24_000)),
        statusMessage = "assigned",
    )
    check(assignment.state.pads[31].startFrame == 1_000)
    check(assignment.state.selectedPad == 16)
    check(assignment.state.activeSliceIndex == 1)

    val project = LegacyProjectAdapter.toSnapshot(assignment.state, projectName = "Smoke")
    check(project.schemaVersion == 1)
    check(project.audioAssets.single().channelCount == 1)
    check(project.pads[31].assetId == project.audioAssets.single().id)

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
            "stereo=${stereo.frameCount} frames, project=v${project.schemaVersion}, " +
            "WAV header valid, pattern=${summary.frameCount} frames",
    )
}

private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
    ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .int

private inline fun expectInvalid(block: () -> Unit) {
    try {
        block()
        error("Expected IllegalArgumentException")
    } catch (_: IllegalArgumentException) {
        // Expected.
    }
}
