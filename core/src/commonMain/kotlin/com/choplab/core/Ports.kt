package com.choplab.core

import com.choplab.core.model.Asset
import com.choplab.core.model.Project
import com.choplab.core.model.ProjectLimits
import com.choplab.engine.EngineCommand
import com.choplab.engine.EngineProgram
import com.choplab.engine.PcmAsset

/** Opaque host-owned locator. It is deliberately absent from Project and its JSON codec. */
data class Location(val handle: String) { init { require(handle.isNotBlank() && handle.length <= 4096) } }
interface AssetStore {
    suspend fun containsVerified(asset: Asset): Boolean
    suspend fun write(asset: Asset, bytes: ByteArray)
    suspend fun read(asset: Asset): ByteArray
}
interface ImportPort {
    /** Returns only after original bytes and their metadata are validated and published in AssetStore. */
    suspend fun import(location: Location): Asset
}
interface ProjectPort {
    suspend fun save(project: Project, revision: Long, location: Location)
    suspend fun open(location: Location): Project
}
data class ExportRequest(val location: Location, val frames: Int, val tailFrames: Int = 0, val bits: Int = 24, val seed: Int = 1) {
    init { require(frames.toLong() in 1..ProjectLimits.MAX_TIMELINE_FRAMES && tailFrames in 0..480_000 && bits in setOf(16, 24)) }
}
data class ExportReceipt(val frames: Long, val sampleRate: Int, val channels: Int, val bits: Int)
interface ExportPort {
    suspend fun export(project: Project, patternId: String, request: ExportRequest): ExportReceipt
    /** Legacy adapters remain usable for Pattern; arrangement support must be explicit. */
    suspend fun export(project: Project, target: PlaybackTarget, request: ExportRequest): ExportReceipt = when (target) {
        is PlaybackTarget.Pattern -> export(project, target.id, request)
        is PlaybackTarget.Arrangement -> throw UnsupportedOperationException("Arrangement export is not supported by this adapter")
    }
}
interface PcmPort {
    /** 48 kHz stereo, ceil(sourceFrames * 48000 / sourceRate); bounded resident copy. */
    suspend fun load(asset: Asset): PcmAsset
}

data class TransportState(
    val frame: Long = 0,
    val playing: Boolean = false,
    val programRevision: Long = 0,
    val activeVoices: Int = 0,
    val eventLosses: Long = 0,
    val outputAttached: Boolean = false,
    /** Next 48 kHz transport/source frame; distinct from the monotonically advancing render/DAC clock. */
    val sequenceFrame: Long = 0,
    val sequencePaused: Boolean = false,
)
interface EnginePort {
    suspend fun prepare(project: Project, patternId: String, revision: Long): EngineProgram
    suspend fun prepare(project: Project, target: PlaybackTarget, revision: Long): EngineProgram = when (target) {
        is PlaybackTarget.Pattern -> prepare(project, target.id, revision)
        is PlaybackTarget.Arrangement -> throw UnsupportedOperationException("Arrangement playback is not supported by this adapter")
    }
    /** True means a matching APPLIED or LATE acknowledgement, not queue acceptance. Driver handles timeout/loss. */
    suspend fun apply(command: EngineCommand): Boolean
    fun snapshot(): TransportState
}
data class Services(val assets: AssetStore, val importer: ImportPort, val projects: ProjectPort, val exporter: ExportPort, val engine: EnginePort)
