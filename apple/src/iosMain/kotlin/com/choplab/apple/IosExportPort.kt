package com.choplab.apple

import com.choplab.core.*
import com.choplab.core.model.Project
import com.choplab.core.model.ProjectLimits
import com.choplab.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Offline export through the same EngineCore and compiled Program as playback, streamed in blocks to a 16/24-bit
 * WAV with the shared seeded TPDF quantizer. The finished file is synced and renamed into place; a cancelled or
 * failed export leaves no partial file at the destination.
 */
internal class IosExportPort(private val compiler: ProgramCompiler, private val files: IosLocations) : ExportPort {
    override suspend fun export(project: Project, patternId: String, request: ExportRequest): ExportReceipt {
        require(project.clips.isEmpty() && project.tracks.isEmpty() && project.takes.isEmpty()) {
            "Choose an explicit PlaybackTarget to export a document containing timeline material"
        }
        return export(project, PlaybackTarget.Pattern(patternId), request)
    }

    override suspend fun export(project: Project, target: PlaybackTarget, request: ExportRequest): ExportReceipt {
        val program = compiler.compile(project, target, 0)
        try {
            val tail = if (request.tailMode == ExportTailMode.INCLUDE_GRAPH_TAIL) maxOf(request.tailFrames, program.mixer.tailFrames) else request.tailFrames
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                val destination = files.resolve(request.location).path
                val pending = destination.substringBeforeLast('/') + "/." + uniqueName("export-")
                try {
                    FileWriter(pending).use { output ->
                        render(program, output, request.frames, tail, request.bits, request.seed) { !context.isActive }
                        output.syncAndClose()
                    }
                    require(context.isActive) { "Export cancelled" }
                    renameReplacing(pending, destination)
                } finally { deleteFile(pending) }
            }
            return ExportReceipt(request.frames.toLong() + tail, EngineFormat.SAMPLE_RATE, 2, request.bits)
        } finally { program.releasePreparation() }
    }

    /** Same block plan, latency skip and PCM-miss check as the JVM hosts' streaming renderer. */
    internal fun render(program: EngineProgram, output: FileWriter, frames: Int, tailFrames: Int, bits: Int, seed: Int,
                        blockFrames: Int = 480, cancelled: () -> Boolean) {
        require(frames.toLong() in 1..ProjectLimits.MAX_TIMELINE_FRAMES && tailFrames in 0..MixerProgram.MAX_TAIL_FRAMES)
        val engine = EngineCore(program, EngineConfig(controlCapacity = 4, eventCapacity = 8, outputMode = EngineOutputMode.EXPORT))
        try {
            require(engine.controls.offer(EngineCommand.StartSequence(0, 1)) == OfferResult.ACCEPTED)
            require(engine.controls.offer(EngineCommand.Stop(frames.toLong(), 2)) == OfferResult.ACCEPTED)
            val latency = engine.latencyFrames
            val outputFrames = frames.toLong() + tailFrames
            val writer = IosWav.PcmWriter(output.asOutput(), outputFrames, bits = bits, seed = seed, bufferFrames = blockFrames)
            val buffer = FloatArray(blockFrames * 2)
            val total = outputFrames + latency
            var rendered = 0L
            while (rendered < total) {
                require(!cancelled()) { "WAV export cancelled" }
                val plan = engine.prepareOfflineBlock(minOf(blockFrames.toLong(), 4096L, total - rendered).toInt())
                // This host keeps every asset resident; a paged window would mean PCM it cannot provide.
                require(plan.windows.isEmpty()) { "Paged PCM is not available on this host" }
                val count = plan.frames
                engine.render(buffer, frameCount = count)
                check(engine.pcmUnderrunFrames == 0L) { "PCM missing during export" }
                val skipped = minOf(count.toLong(), (latency - rendered).coerceAtLeast(0)).toInt()
                if (count > skipped) writer.write(buffer, skipped, count - skipped)
                rendered += count
            }
            require(!cancelled()) { "WAV export cancelled" }
            writer.finish()
        } finally { engine.close() }
    }
}
