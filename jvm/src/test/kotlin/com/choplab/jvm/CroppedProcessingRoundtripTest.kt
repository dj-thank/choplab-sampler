package com.choplab.jvm

import com.choplab.core.DocumentState
import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.core.vocal.*
import com.choplab.engine.*
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*

class CroppedProcessingRoundtripTest {
    @Test fun savedOriginalAtNativeRatesSurvivesArchiveAndReeditWithoutRoundingTheCrop(): Unit = runBlocking {
        val root = Files.createTempDirectory("saved-native-pitch-")
        val memory = PcmMemoryBudget()
        try {
            val store = FileAssetStore(root.resolve("assets"))
            WavPcmPort(store, memory = memory).use { pcm ->
                val renderer = VocalPitchRenderer(store, pcm, root.resolve("scratch"))
                for (rate in listOf(8_000, 44_100, 96_000)) {
                    val source = put(store, rate, 215.0)
                    val nativeBytes = store.read(source)
                    val before = project(source, TrackKind.VOCAL)
                    val doc = DocumentState(before, 0)
                    val settings = PitchCorrectionSettings(retuneMs = 0f)
                    val draft = VocalPitchEdits.draft(doc, "clip", "pitch", settings)
                    val corrected = assertIs<VocalPitchRenderResult.Rendered>(renderer.render(before, draft, "Corrected")).asset
                    val applied = Reducer.reduce(before, VocalPitchEdits.apply(doc, draft, corrected)).project
                    for (crop in listOf(FrameRange(0, 1), FrameRange(corrected.frames - 1, corrected.frames), FrameRange(137, corrected.frames - 211))) {
                        val trimmed = applied.copy(clips = frozenListOf(applied.clips.single().copy(range = crop)))
                        val pending = VocalPitchEdits.draft(DocumentState(trimmed, 1), "clip", "unused", settings)
                        val original = renderer.original(trimmed, pending, "Original")
                        val selected = Reducer.reduce(trimmed, VocalPitchEdits.select(DocumentState(trimmed, 1), "pitch", true, original)).project
                        assertEquals(trimmed.clips.single().copy(assetHash = original.hash), selected.clips.single())
                        assertEquals(original.hash, selected.pitchCorrections.single().normalizedOriginalHash)
                        pcm.acquire(source).use { raw -> pcm.acquire(original).use { normalized ->
                            memory.reserve(2 * 4096 * 8L).use {
                                val first = pending.firstFrame48(source).toInt()
                                for (at in listOf(crop.start.toInt(), crop.end.toInt() - 1)) {
                                    assertContentEquals(pcm.readWindow(raw.pcm, first + at, first + at + 1), pcm.readWindow(normalized.pcm, at, at + 1))
                                }
                            }
                        } }
                        val bytes = ByteArrayOutputStream().also { ArchiveCodec().write(selected, store, it) }.toByteArray()
                        val restoredStore = FileAssetStore(root.resolve("restored-$rate-${crop.start}"))
                        val restored = ArchiveCodec().read(ByteArrayInputStream(bytes), restoredStore)
                        assertEquals(selected, restored)
                        assertContentEquals(nativeBytes, restoredStore.read(source))
                        assertContentEquals(store.read(original), restoredStore.read(original))
                        val switched = Reducer.reduce(restored, VocalPitchEdits.select(DocumentState(restored, 2), "pitch", false)).project
                        assertEquals(trimmed.clips, switched.clips)
                        val reedit = VocalPitchEdits.draft(DocumentState(restored, 2), "clip", "unused", settings)
                        assertEquals(draft.sourceRange, reedit.sourceRange)
                        assertEquals(crop, reedit.auditionRange(restored))
                        val edited = assertIs<VocalPitchRenderResult.Rendered>(renderer.render(restored, reedit, "Corrected")).asset
                        assertEquals(corrected.hash, edited.hash, "The same full native context is processed")
                        val reapplied = Reducer.reduce(restored, VocalPitchEdits.apply(DocumentState(restored, 2), reedit, edited)).project
                        assertEquals(trimmed.clips, reapplied.clips)
                        assertEquals(original.hash, reapplied.pitchCorrections.single().normalizedOriginalHash)
                        val storedBefore = store.storedBytes()
                        assertEquals(original, renderer.original(restored, reedit, "Cached"))
                        assertEquals(storedBefore, store.storedBytes())
                    }
                    assertContentEquals(nativeBytes, store.read(source))
                }
            }
            assertEquals(0L, memory.statistics().usedBytes)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun repeatedStretchRendersTheFullSourceAndReturnsToExactCropAfterArchiveReopen(): Unit = runBlocking {
        val root = Files.createTempDirectory("cropped-wsola-")
        val memory = PcmMemoryBudget()
        try {
            val store = FileAssetStore(root.resolve("assets"))
            WavPcmPort(store, memory = memory).use { pcm ->
                val renderer = BeatStretchRenderer(store, pcm, root.resolve("scratch"))
                val target = StretchTarget(StretchKind.CLIP, "clip")
                for (rate in listOf(8_000, 44_100, 48_000, 96_000)) {
                    val source = put(store, rate, 220.0)
                    val nativeBytes = store.read(source)
                    val before = project(source, TrackKind.BANK).copy(tempo = Tempo(137_000))
                    val firstDraft = BeatStretchEdits.draft(DocumentState(before, 0), target, 103_000)
                    val first = renderer.render(before, firstDraft, "Stretched")
                    val applied = Reducer.reduce(before, BeatStretchEdits.apply(DocumentState(before, 0), firstDraft, first)).project
                    val crop = FrameRange(137, first.frames - 211)
                    var current = applied.copy(clips = frozenListOf(applied.clips.single().copy(range = crop)))
                    for (bpm in listOf(239_000, 40_000, 103_000, 137_000)) {
                        current = current.copy(tempo = Tempo(bpm))
                        val doc = DocumentState(current, 5)
                        val draft = BeatStretchEdits.draft(doc, target, 103_000)
                        assertEquals(firstDraft.sourceRange, draft.sourceRange)
                        val rendered = renderer.render(current, draft, "Stretched")
                        val full = before.copy(tempo = Tempo(bpm))
                        val oracle = if (bpm == 103_000) renderer.original(full, BeatStretchEdits.draft(DocumentState(full, 0), target, 103_000), "Original")
                            else renderer.render(full, BeatStretchEdits.draft(DocumentState(full, 0), target, 103_000), "Stretched")
                        assertContentEquals(store.read(oracle), store.read(rendered), "No second processing of cropped audio at $rate Hz / $bpm")
                        current = Reducer.reduce(current, BeatStretchEdits.apply(doc, draft, rendered)).project
                        assertEquals(StretchCrop(crop, first.frames), current.beatStretches.single().crop)
                        assertEquals(current.beatStretches.single().crop!!.at(rendered.frames), current.clips.single().range)
                        assertEquals(before.clips.single().timelineStartFrame, current.clips.single().timelineStartFrame)
                        val bytes = ByteArrayOutputStream().also { ArchiveCodec().write(current, store, it) }.toByteArray()
                        current = ArchiveCodec().read(ByteArrayInputStream(bytes), store).also { assertEquals(current, it) }
                    }
                    assertEquals(crop, current.clips.single().range)
                    assertEquals(first.hash, current.clips.single().assetHash)
                    assertContentEquals(nativeBytes, store.read(source))
                }
            }
            assertEquals(0L, memory.statistics().usedBytes)
        } finally { root.toFile().deleteRecursively() }
    }

    private fun project(source: Asset, kind: TrackKind) = Project(assets = frozenListOf(source),
        tracks = frozenListOf(Track("track", "Track", kind)),
        clips = frozenListOf(Clip("clip", "track", source.hash, FrameRange(13, source.frames - 19), timelineStartFrame = 1237, gain = .7f, pan = -.3f)))

    private fun put(store: FileAssetStore, rate: Int, hz: Double): Asset {
        val samples = FloatArray(rate * 2) { (sin(2 * PI * hz * (it / 2) / rate) * if (it % 2 == 0) 1.2 else -.6).toFloat() }
        val bytes = ByteArrayOutputStream().also { WavCodec.writeFloat(it, samples, sampleRate = rate) }.toByteArray()
        return Asset(sha256(bytes), "wav", bytes.size.toLong(), rate, 2, rate.toLong(), "Original").also { store.publish(it, ByteArrayInputStream(bytes)) }
    }
}
