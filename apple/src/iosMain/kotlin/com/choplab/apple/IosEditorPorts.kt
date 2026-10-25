package com.choplab.apple

import com.choplab.core.*
import com.choplab.core.model.*
import com.choplab.engine.MixerSnapshot
import com.choplab.engine.Tempo
import com.choplab.ui.*
import platform.Foundation.NSURL
import platform.UIKit.UIPasteboard
import kotlinx.coroutines.CoroutineScope

/** The existing four-stage editor talks only to these production Apple adapters. */
internal class IosEditorPorts(private val backend: IosBackend, private val picker: IosFilePicker, scope: CoroutineScope) : ContinuousEditorPorts {
    override val sourcePreview = IosVocalPreview(backend.studio, backend.engine, backend.audition, scope)
    override val autosaveFailure get() = backend.persistenceFailure
    override val autoChop get() = backend.rendering.autoChop
    override val originalAvailable get() = backend.engine.status.value.phase == DriverPhase.ATTACHED
    override val padRenderAvailable = true
    override val drumKitsAvailable = true
    override val stepPatternsAvailable = true
    override val noteRepeatAvailable = true
    override val sourceAnalysisAvailable = true
    override suspend fun analyseSource(asset: Asset, range: FrameRange) = backend.rendering.analyseSource(asset, range)
    // Ports not implemented by iPad remain NOT_CONNECTED in the shared editor, never a silent success.
    override val outputRevealAvailable = true
    override suspend fun chooseAudio(): Location? = picker.openAudio()?.let(backend.files::register)
    override suspend fun chooseOpen(): Location? = picker.openProject()?.let(backend.files::register)
    override suspend fun chooseSave(): Location = destination("choplab")
    override suspend fun chooseExport(frames: Long): ExportRequest = ExportRequest(destination("wav"), frames.toInt(), bits = 24)
    private fun destination(extension: String): Location {
        val name = uniqueName("Earth-Song-") + "." + extension
        return backend.files.register(HostFile(IosPaths.documents() + "/" + name, name))
    }
    override fun outputDisplayName(location: Location) = backend.files.resolve(location).name
    override suspend fun revealOutput(location: Location): Boolean = picker.share(NSURL.fileURLWithPath(backend.files.resolve(location).path))
    override suspend fun copyText(text: String): Boolean { UIPasteboard.generalPasteboard.string = text; return true }
    override suspend fun peaks(asset: Asset) = backend.rendering.peaks(asset)
    override suspend fun drumKit(kitId: String) = backend.rendering.drumKit(kitId)
    override suspend fun renderPad(pad: Pad, source: Asset) = backend.rendering.renderPad(pad, source)
    override suspend fun renderPerformance(pad: Pad, source: Asset, releaseAt: Int?, limitFrames: Int, stopAt: Int?) =
        backend.rendering.renderPerformance(pad, source, releaseAt, limitFrames, stopAt)
    override suspend fun renderNoteRepeat(pad: Pad, source: Asset, tempo: Tempo, ticks: Int, releaseAt: Int, limitFrames: Int, stopAt: Int?) =
        backend.rendering.renderNoteRepeat(pad, source, tempo, ticks, releaseAt, limitFrames, stopAt)
    override fun originalPlaying(): Boolean? = when (val result = backend.engine.originalPlaybackProbe()) {
        is OriginalPlaybackProbe.Ready -> result.playback.playing
        OriginalPlaybackProbe.Contended -> null
        OriginalPlaybackProbe.Unavailable -> false
    }
    override fun playingPads() = backend.engine.playingPads()
    override fun cancelOriginalPreparation() = backend.audition.cancelPreparation()
    override suspend fun playOriginal(asset: Asset) = backend.audition.play(asset)
    override suspend fun stopOriginal() = backend.audition.pause()
    override suspend fun resetOriginal() = backend.audition.clear()
    override suspend fun seekOriginal(frame: Long): Boolean {
        val project = backend.studio.document.value.project
        val asset = project.source?.assetHash?.let(project::asset) ?: return false
        return backend.audition.seek(asset, frame)
    }
    override suspend fun setSongMonitorGain(gain: Float) = backend.audition.songGain(gain)
    override suspend fun setOriginalMonitorGain(gain: Float) = backend.audition.originalGain(gain)
    override suspend fun setHandMonitorGain(gain: Float) = backend.audition.handGain(gain)
    override suspend fun scratchOriginalStart(asset: Asset, from: Long, start: Long, end: Long) = backend.audition.scratchStart(asset, from, start, end)
    override suspend fun scratchOriginalTo(position: Double, durationFrames: Int) = backend.audition.scratchTo(position, durationFrames)
    override suspend fun scratchOriginalCut(gain: Float) = backend.audition.scratchCut(gain)
    override suspend fun scratchOriginalEnd() = backend.audition.scratchEnd()
    override fun readout() = ContinuousEditorReadout(backend.audition.nativeFrame(), backend.engine.snapshot().sequenceFrame,
        handSourceFrame = backend.audition.nativeHandFrame(), countInBeatsRemaining = backend.engine.snapshot().countInBeatsRemaining)
    override fun readMixer(target: MixerSnapshot) = backend.engine.copyMixer(target)
    override fun diagnostics() = backend.engine.health().let { health ->
        ContinuousDiagnostics(health.attached, floatOutput = if (health.attached) true else null,
            blockFrames = health.blockFrames, bufferFrames = health.bufferFrames, pendingFrames = health.pendingFrames,
            underruns = health.underruns, outputLosses = health.outputLosses, measuredBlocks = health.measuredBlocks,
            renderP99 = health.renderP99, renderMax = health.renderMax)
    }
}
