package com.choplab.desktop.persistence

import com.choplab.sampler.audio.PatternRenderSummary
import com.choplab.sampler.audio.PatternRenderer
import com.choplab.sampler.model.PadModel
import java.io.File
import java.io.RandomAccessFile

internal fun interface DesktopPatternSequenceRenderer {
    fun render(
        outputFile: File,
        pads: List<PadModel>,
        patternSequence: List<Set<Int>>,
        bpm: Float,
        swing: Float,
    ): PatternRenderSummary
}

private val productionPatternSequenceRenderer =
    DesktopPatternSequenceRenderer { output, pads, sequence, bpm, swing ->
        PatternRenderer.renderSequenceToWav(
            outputFile = output,
            pads = pads,
            patternSequence = sequence,
            bpm = bpm,
            swing = swing,
        )
    }

internal object DesktopBeatFiles {
    fun export(
        target: File,
        pads: List<PadModel>,
        patternSequence: List<Set<Int>>,
        bpm: Float,
        swing: Float,
        renderer: DesktopPatternSequenceRenderer = productionPatternSequenceRenderer,
    ): PatternRenderSummary = replaceWithAtomicSibling(target) { temporary ->
        val summary = renderer.render(
            outputFile = temporary,
            pads = pads,
            patternSequence = patternSequence,
            bpm = bpm,
            swing = swing,
        )
        validateCanonicalPcm16Wav(temporary, summary)
        summary
    }
}

private fun validateCanonicalPcm16Wav(file: File, summary: PatternRenderSummary) {
    require(summary.frameCount >= 0) { "WAVのframe数が不正です" }
    require(summary.sampleRate > 0) { "WAVのsample rateが不正です" }
    require(summary.channelCount in 1..2) { "WAVのchannel数が不正です" }
    val blockAlign = summary.channelCount * Short.SIZE_BYTES
    val expectedDataBytes = Math.multiplyExact(summary.frameCount.toLong(), blockAlign.toLong())
    val expectedFileBytes = Math.addExact(CANONICAL_WAV_HEADER_BYTES.toLong(), expectedDataBytes)
    require(file.length() == expectedFileBytes) { "WAVのfile sizeがrender結果と一致しません" }

    val header = ByteArray(CANONICAL_WAV_HEADER_BYTES)
    RandomAccessFile(file, "r").use { input -> input.readFully(header) }
    require(header.ascii(0, 4) == "RIFF" && header.ascii(8, 4) == "WAVE") { "WAV headerが不正です" }
    require(header.ascii(12, 4) == "fmt " && header.unsignedInt(16) == 16L) { "WAV fmt chunkが不正です" }
    require(header.unsignedShort(20) == 1) { "WAVはPCM形式ではありません" }
    require(header.unsignedShort(22) == summary.channelCount) { "WAV channel数が一致しません" }
    require(header.unsignedInt(24) == summary.sampleRate.toLong()) { "WAV sample rateが一致しません" }
    require(header.unsignedInt(28) == summary.sampleRate.toLong() * blockAlign) { "WAV byte rateが不正です" }
    require(header.unsignedShort(32) == blockAlign) { "WAV block alignが不正です" }
    require(header.unsignedShort(34) == Short.SIZE_BITS) { "WAVはPCM 16-bitではありません" }
    require(header.ascii(36, 4) == "data") { "WAV data chunkがありません" }
    require(header.unsignedInt(4) == expectedFileBytes - 8L) { "WAV RIFF sizeが一致しません" }
    require(header.unsignedInt(40) == expectedDataBytes) { "WAV data sizeが一致しません" }
}

private fun ByteArray.ascii(offset: Int, length: Int): String =
    copyOfRange(offset, offset + length).toString(Charsets.US_ASCII)

private fun ByteArray.unsignedShort(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.unsignedInt(offset: Int): Long =
    (this[offset].toLong() and 0xFFL) or
        ((this[offset + 1].toLong() and 0xFFL) shl 8) or
        ((this[offset + 2].toLong() and 0xFFL) shl 16) or
        ((this[offset + 3].toLong() and 0xFFL) shl 24)

private const val CANONICAL_WAV_HEADER_BYTES = 44
