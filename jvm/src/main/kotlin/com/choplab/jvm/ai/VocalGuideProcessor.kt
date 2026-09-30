package com.choplab.jvm.ai

import com.choplab.core.ProgramCompiler
import com.choplab.core.ai.*
import com.choplab.core.model.*
import com.choplab.engine.*
import com.choplab.jvm.PcmMemoryBudget
import kotlin.math.*

internal data class CachedSpeech(val audio: TtsAudio, val speed: Double = 1.0, val trimmedFrames: Long = 0, val contentHash: String? = null)

/** Native synthesis is normalized once; only conservative edge silence is discarded. */
internal object VocalGuideProcessor {
    const val VERSION = "vocal-fit-1-" + OfflineWsola.VERSION
    suspend fun fit(audio: TtsAudio, request: TtsRequest, frames: Int, memory: PcmMemoryBudget = PcmMemoryBudget.shared, checkCancelled: () -> Unit): TtsResult<CachedSpeech> {
        checkCancelled()
        val normalizedFrames = (audio.frames.toLong() * 48_000 + audio.sampleRate - 1) / audio.sampleRate
        val normalizedBytes = normalizedFrames * 8
        val stereoBytes = audio.frames * 8L
        // Native copy, mono expansion, the resampler's defensive PCM copy, coefficients and output coexist.
        val normalizePeak = audio.bytes + (if (audio.channels == 1) stereoBytes else 0) + normalizedBytes +
            (if (audio.sampleRate != 48_000) stereoBytes + 512L * 4097 * 4 else 0)
        val normalization = memory.reserve(normalizePeak)
        try {
        val normalized = normalize(audio, checkCancelled)
        normalization.shrinkTo(normalizedBytes)
        val length = normalized.size / 2
        var first = 0; var end = length
        // -100 dBFS peak gate plus a 5 ms guard at each edge. No interior pause or voiced tail is cut.
        while (first < length && abs(normalized[first * 2]) <= .00001f && abs(normalized[first * 2 + 1]) <= .00001f) {
            if (first % 4096 == 0) checkCancelled(); first++
        }
        if (first == length) return ttsFailure(TtsProblem.SILENT_AUDIO)
        while (end > first && abs(normalized[(end - 1) * 2]) <= .00001f && abs(normalized[(end - 1) * 2 + 1]) <= .00001f) {
            if (end % 4096 == 0) checkCancelled(); end--
        }
        first = maxOf(0, first - 240); end = minOf(length, end + 240)
        val speed = (end - first).toDouble() / frames
        if (speed !in OfflineWsola.MIN_SPEED..OfflineWsola.MAX_SPEED) return ttsFailure(TtsProblem.CANNOT_FIT, speed)
        if (end - first < 128) return ttsFailure(TtsProblem.TOO_SHORT)
        val fittedCharge = memory.reserve((end - first) * 8L + frames * 8L)
        try {
        val output = stretch(normalized, first, end, frames, checkCancelled)
        fittedCharge.shrinkTo(frames * 8L)
        val supplied = audio.words.takeIf { words -> words.isNotEmpty() && words.joinToString("") { it.text } == request.text }
        val words = supplied?.mapNotNull { word ->
            val rawFrom = word.startFrame * 48_000.0 / audio.sampleRate - first
            val rawTo = word.endFrame * 48_000.0 / audio.sampleRate - first
            val from48 = rawFrom.coerceIn(0.0, (end - first).toDouble())
            val to48 = rawTo.coerceIn(0.0, (end - first).toDouble())
            val from = (from48 / speed).roundToLong().coerceIn(0, frames.toLong())
            val to = (to48 / speed).roundToLong().coerceIn(0, frames.toLong())
            if (to <= from) null else TtsWord(word.text, from, to,
                if (speed == 1.0 && rawFrom >= 0 && rawTo <= end - first) word.origin else WordTimingOrigin.ESTIMATED)
        }?.takeIf { it.size == supplied.size }
            ?: estimateWords(request.text, frames)
        checkCancelled()
        return TtsResult.Success(CachedSpeech(TtsAudio.takeOwnership(output, 48_000, 2, words, memory, fittedCharge::close),
            speed, (length - (end - first)).toLong()))
        } catch (failure: Throwable) { fittedCharge.close(); throw failure }
        } finally { normalization.close() }
    }

    /** Helpers return only their output, so temporary PCM is no longer referenced before shrinking its charge. */
    private fun normalize(audio: TtsAudio, check: () -> Unit): FloatArray {
        val native = audio.copySamples()
        val stereo = if (audio.channels == 2) native else FloatArray(native.size * 2) { native[it / 2] }
        return OfflineResampler.resample(stereo, audio.sampleRate, checkCancelled = check)
    }
    private fun stretch(normalized: FloatArray, first: Int, end: Int, frames: Int, check: () -> Unit): FloatArray =
        OfflineWsola.stretch(normalized.copyOfRange(first * 2, end * 2), frames, check)


    /** No forced alignment claim: character/space groups divide the available duration proportionally. */
    private fun estimateWords(text: String, frames: Int): List<TtsWord> {
        val codepoints = text.codePoints().toArray()
        val strings = if (text.any { it.isWhitespace() }) Regex("\\s*\\S+\\s*").findAll(text).map { it.value }.toList()
            else codepoints.map { String(Character.toChars(it)) }
        val grouped = ArrayList<String>()
        var pending = ""
        val maximumGroups = minOf(256, frames)
        val groupSize = maxOf(1, (strings.size + maximumGroups - 1) / maximumGroups)
        strings.chunked(groupSize).forEach { parts ->
            val group = parts.joinToString("")
            if (group.length <= 256) grouped += group else {
                for (codepoint in group.codePoints().toArray()) {
                    val next = String(Character.toChars(codepoint))
                    if (pending.length + next.length > 256) { grouped += pending; pending = "" }
                    pending += next
                }
                if (pending.isNotEmpty()) { grouped += pending; pending = "" }
            }
        }
        val total = grouped.sumOf { it.length }
        var cursor = 0
        return grouped.map { group ->
            val start = frames.toLong() * cursor / total
            cursor += group.length
            TtsWord(group, start, frames.toLong() * cursor / total, WordTimingOrigin.ESTIMATED)
        }
    }

    fun lyricWords(row: FlowRow, audio: TtsAudio): FrozenList<LyricWord> {
        val span = row.line.endTick - row.line.startTick
        return audio.words.map { word ->
            val start = row.line.startTick + span * word.startFrame / audio.frames
            val end = row.line.startTick + span * word.endFrame / audio.frames
            LyricWord(word.text, start, maxOf(start + 1, end).coerceAtMost(row.line.endTick), word.origin)
        }.frozen()
    }

    fun targetFrames(row: FlowRow, tempo: Tempo): Int =
        (ProgramCompiler.clipTickToFrame(row.line.endTick, tempo) - ProgramCompiler.clipTickToFrame(row.line.startTick, tempo)).toInt().also {
            require(it in 128..OfflineWsola.MAX_FRAMES)
        }
}
