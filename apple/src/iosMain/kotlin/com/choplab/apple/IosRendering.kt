package com.choplab.apple

import com.choplab.core.ProgramCompiler
import com.choplab.core.chop.*
import com.choplab.core.kits.DrumKits
import com.choplab.core.model.*
import com.choplab.engine.NoteRepeatRender
import com.choplab.engine.PadPerformanceRender
import com.choplab.engine.PadRender
import com.choplab.engine.Tempo
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlin.math.abs
import kotlin.math.round

/**
 * App-rendered sounds, waveform peaks and automatic chop analysis on iPadOS, with the JVM hosts' rules: rendered
 * sounds are 48 kHz float WAVs published by content hash, so the same PAD renders to the same asset again.
 */
internal class IosRendering(private val assets: IosAssetStore, private val pcm: IosPcmPort) {
    /** A PAD as it sounds from its PAD (pitch, reverse, tone, own pan), for placing on the song. */
    suspend fun renderPad(pad: Pad, source: Asset): Asset = withContext(Dispatchers.Default) {
        pcm.acquire(source).use { lease ->
            val prepared = ProgramCompiler.enginePad(pad, source, lease.pcm)
            val samples = PadRender.render(prepared, bakePan = true)
            val marks = buildList {
                if (pad.pitchSemitones != 0.0) add((if (pad.pitchSemitones > 0) "+" else "") + round(pad.pitchSemitones).toInt())
                if (pad.reverse) add("rev")
                if (pad.tone < com.choplab.engine.Pad.TONE_BYPASS) add("tone ${round(pad.tone * 100).toInt()}%")
            }
            publishRendered(samples, (listOf(source.name.take(200)) + marks).joinToString(" "), source.hash)
        }
    }

    suspend fun renderPerformance(pad: Pad, source: Asset, releaseAt: Int?, limitFrames: Int, stopAt: Int?): Asset = withContext(Dispatchers.Default) {
        pcm.acquire(source).use { lease ->
            val prepared = ProgramCompiler.enginePad(pad, source, lease.pcm)
            publishRendered(PadPerformanceRender.render(prepared, releaseAt, limitFrames, stopAt), source.name.take(200) + " performance", source.hash)
        }
    }

    suspend fun renderNoteRepeat(pad: Pad, source: Asset, tempo: Tempo, ticks: Int, releaseAt: Int, limitFrames: Int, stopAt: Int?): Asset =
        withContext(Dispatchers.Default) {
            pcm.acquire(source).use { lease ->
                val prepared = ProgramCompiler.enginePad(pad, source, lease.pcm)
                publishRendered(NoteRepeatRender.render(prepared, tempo, ticks, releaseAt, limitFrames, stopAt), source.name.take(200) + " repeat", source.hash)
            }
        }

    /** A built-in kit's 16 sounds in slot order, as app-rendered mono float WAVs (the synthesis is 16-bit). */
    suspend fun drumKit(kitId: String): List<Asset> = withContext(Dispatchers.Default) {
        val kit = DrumKits.kit(kitId)
        (0 until DrumKits.SOUNDS).map { slot ->
            ensureActive()
            val values = DrumKits.render(kit, slot)
            val samples = FloatArray(values.size) { values[it] / 32768f }
            publishRendered(samples, DrumKits.soundName(kit, slot), null, DrumKits.SAMPLE_RATE, 1)
        }
    }

    /** Peak levels for the waveform, at most [maximumBuckets] buckets over the whole sound. */
    suspend fun peaks(asset: Asset, maximumBuckets: Int = 512): List<Float> = withContext(Dispatchers.Default) {
        pcm.acquire(asset).use { lease ->
            val audio = lease.pcm
            val bucketSize = ((audio.frameCount + maximumBuckets - 1) / maximumBuckets).coerceAtLeast(1)
            val peaks = FloatArray((audio.frameCount + bucketSize - 1) / bucketSize)
            for (frame in 0 until audio.frameCount) {
                if (frame % 65_536 == 0) ensureActive()
                val bucket = frame / bucketSize
                peaks[bucket] = maxOf(peaks[bucket], abs(audio.sample(frame, 0)), abs(audio.sample(frame, 1)))
            }
            peaks.toList()
        }
    }

    private val autoChopSlot = Mutex()
    /** Equal or attack-detected cuts within the selected range; refusing rather than guessing when it cannot analyse. */
    val autoChop = AutoChopPort { asset, range, settings ->
        withContext(Dispatchers.Default) {
            if (!autoChopSlot.tryLock()) return@withContext AutoChopResult.Refused(AutoChopProblem.BUSY)
            try {
                require(range.end <= asset.frames)
                if (settings.mode == AutoChopMode.EQUAL) return@withContext AutoChop.equal(range, settings.slices)
                val first = (range.start * 48_000 + asset.sampleRate - 1) / asset.sampleRate
                val end = range.end * 48_000 / asset.sampleRate
                if (end - first < settings.minimumGapMs * 96L) return@withContext AutoChopResult.Refused(AutoChopProblem.TOO_SHORT)
                try {
                    pcm.acquire(asset).use { lease ->
                        val detector = AttackChopDetector(first, end, settings)
                        require(end <= lease.pcm.frameCount)
                        val window = FloatArray(4_096 * 2)
                        var from = first.toInt()
                        while (from < end) {
                            ensureActive()
                            val until = minOf(end.toInt(), from + 4_096)
                            val chunk = if (until - from == 4_096) window else FloatArray((until - from) * 2)
                            for (frame in from until until) {
                                chunk[(frame - from) * 2] = lease.pcm.sample(frame, 0)
                                chunk[(frame - from) * 2 + 1] = lease.pcm.sample(frame, 1)
                            }
                            detector.accept(chunk) { ensureActive() }
                            from = until
                        }
                        when (val result = detector.finish()) {
                            is AutoChopResult.Ready -> AutoChopResult.Ready(result.markers.map {
                                (it * asset.sampleRate / 48_000).coerceIn(range.start + 1, range.end - 1)
                            }.distinct().sorted().frozen())
                            is AutoChopResult.Refused -> result
                        }
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: PcmBudgetExceeded) { AutoChopResult.Refused(AutoChopProblem.NO_MEMORY) }
                catch (_: Exception) { AutoChopResult.Refused(AutoChopProblem.INVALID_AUDIO) }
            } finally { autoChopSlot.unlock() }
        }
    }

    /** Streams float samples to a synced pending file, hashing as it writes, then publishes it by content hash. */
    private suspend fun publishRendered(samples: FloatArray, name: String, sourceHash: String?, rate: Int = 48_000, channels: Int = 2): Asset =
        withContext(Dispatchers.Default) {
            val pending = assets.pendingPath()
            try {
                val hash = Sha256()
                val frames = samples.size / channels.toLong()
                FileWriter(pending).use { file ->
                    val hashing = object : ByteOutput {
                        override fun write(buffer: ByteArray, offset: Int, length: Int) { hash.update(buffer, offset, length); file.write(buffer, offset, length) }
                    }
                    val writer = IosWav.FloatWriter(hashing, frames, rate, channels)
                    var first = 0
                    while (first < frames) {
                        ensureActive()
                        val count = minOf(4096, (frames - first).toInt())
                        writer.write(samples, first, count)
                        first += count
                    }
                    writer.finish()
                    file.syncAndClose()
                }
                val size = requireNotNull(regularFileSize(pending))
                val asset = Asset(hash.hex(), "wav", size, rate, channels, frames, name.ifBlank { "Rendered" }, AssetRole.RENDERED, derivedFrom = sourceHash)
                ensureActive()
                assets.adopt(asset, pending)
                asset
            } finally { deleteFile(pending) }
        }
}
