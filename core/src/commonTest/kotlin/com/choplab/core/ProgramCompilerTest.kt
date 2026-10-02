package com.choplab.core

import com.choplab.core.model.*
import com.choplab.engine.PcmAsset
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ProgramCompilerTest {
    @Test fun combinedResidencyIsRejectedBeforeAnyAssetIsLoaded() = runTest {
        val assets = listOf("a", "b").map { Asset(it.repeat(64), "wav", 72_000_044, 48_000, 2, 9_000_000, "$it.wav") }
        val project = Project(assets = assets.frozen(), pads = (0..127).map { index ->
            if (index < 2) Pad(index, assets[index].hash, FrameRange(0, 9_000_000)) else Pad(index)
        }.frozen())
        var loads = 0
        val compiler = ProgramCompiler(object : PcmPort {
            override suspend fun load(asset: Asset): PcmAsset { loads++; error("Preflight should reject before loading") }
        })
        assertFailsWith<IllegalArgumentException> { compiler.compile(project, "pattern-1", 0) }
        assertEquals(0, loads)
    }

    @Test fun oneAssetIsSharedAcrossOneHundredTwentyEightPads() = runTest {
        val asset = Asset("a".repeat(64), "wav", 100, 48_000, 2, 7, "a.wav")
        val project = Project(assets = frozenListOf(asset), pads = (0..127).map { Pad(it, asset.hash, FrameRange(0, 7)) }.frozen())
        var loads = 0
        val compiler = ProgramCompiler(object : PcmPort {
            override suspend fun load(asset: Asset): PcmAsset { loads++; return PcmAsset.fromInterleaved(FloatArray(14)) }
        })
        val program = compiler.compile(project, "pattern-1", 1)
        assertEquals(1, loads)
        assertEquals(56L, program.residentBytes)
        assertSame(program.pad(0)!!.asset, program.pad(127)!!.asset)
    }

    @Test fun decoderMetadataMismatchCannotEnterProgram() = runTest {
        val asset = Asset("a".repeat(64), "wav", 100, 48_000, 2, 7, "a.wav")
        val project = Project(assets = frozenListOf(asset), pads = (0..127).map { if (it == 0) Pad(it, asset.hash, FrameRange(0, 7)) else Pad(it) }.frozen())
        val compiler = ProgramCompiler(object : PcmPort { override suspend fun load(asset: Asset) = PcmAsset.fromInterleaved(FloatArray(12)) })
        assertFailsWith<IllegalArgumentException> { compiler.compile(project, "pattern-1", 0) }
    }
    @Test fun allOverdubRoutesAndSelectedTakesMustFitTheSameAudiblePlanAndMixerUnion() {
        val asset = Asset("a".repeat(64), "wav", 844, 48_000, 2, 100, "a.wav")
        val a = Track("a", "A", TrackKind.BANK)
        val b = Track("b", "B", TrackKind.BANK)
        val voice = Track("voice", "Voice", TrackKind.VOCAL)
        val project = Project(assets = frozenListOf(asset), tracks = frozenListOf(a, b, voice),
            clips = List(30) { Clip("clip-$it", a.id, asset.hash, FrameRange(0, 100), 0) }.frozen(),
            takes = frozenListOf(Take("take", voice.id, asset.hash, FrameRange(0, 100), 0)))
        assertTrue(ProgramCompiler.canAddAudioClips(project, PlaybackTarget.Arrangement(), 0, 100, listOf(a.id, b.id)))
        val selected = PlaybackTarget.Arrangement(takeIds = frozenListOf("take"))
        assertFalse(ProgramCompiler.canAddAudioClips(project, selected, 0, 100, listOf(a.id, b.id)))
        val muted = project.copy(tracks = frozenListOf(a, b.copy(mute = true), voice))
        assertTrue(ProgramCompiler.canAddAudioClips(muted, selected, 0, 100, listOf(a.id, b.id)))
        val tracks = List(16) { Track("track-$it", "Track $it", TrackKind.BANK) }
        val union = Project(assets = frozenListOf(asset), tracks = tracks.frozen(),
            clips = (8..15).map { Clip("clip-$it", tracks[it].id, asset.hash, FrameRange(0, 100), 0) }.frozen(),
            banks = Project().banks.map { it.copy(trackId = tracks[it.id].id) }.frozen(),
            pads = Project().pads.map { if (it.id % 16 == 0) it.copy(assetHash = asset.hash, range = FrameRange(0, 100)) else it }.frozen())
        assertFalse(ProgramCompiler.canAddAudioClips(union, PlaybackTarget.Arrangement(), 0, 100, listOf(null)))
        assertTrue(ProgramCompiler.canAddAudioClips(union, PlaybackTarget.Arrangement(), 0, 100, listOf(tracks[0].id)))
    }

}
