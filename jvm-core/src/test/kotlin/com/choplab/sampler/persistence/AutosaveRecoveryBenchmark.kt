package com.choplab.sampler.persistence

import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerUiState
import java.io.File
import java.security.MessageDigest
import java.util.Random
import kotlin.system.measureNanoTime

/** Synthetic, opt-in host recovery timing; not Android/Windows cold-launch timing. */
fun main(args: Array<String>) {
    require(args.size in 2..3 && args[0] in listOf("seed", "measure")) {
        "Usage: seed <new-fixture-directory> | measure <fixture-directory> [label]"
    }
    val directory = File(args[1])
    if (args[0] == "seed") {
        check(directory.mkdirs()) { "Use a new empty fixture directory" }
        val random = Random(20260910L)
        val pcm = ShortArray(30 * 48_000 * 2) { random.nextInt(65_536).toShort() }
        val audio = PcmAudio(id = 10L, name = "synthetic-benchmark", samples = pcm, sampleRate = 48_000, channelCount = 2)
        val names = listOf("autosave.previous2", "autosave.previous", "autosave.pending", "autosave")
        names.forEachIndexed { index, name ->
            val archive = File(directory, "$name.choplab")
            archive.outputStream().buffered().use {
                ProjectArchiveCodec.write(SamplerUiState(currentAudio = audio, rangeEndFrame = audio.frameCount, bpm = 90f + index), it)
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(archive.readBytes()).joinToString("") { "%02x".format(it) }
            File(directory, "$name.revision").writeText("${index + 1}\t$digest\n")
        }
        println("Fixture: 4 archives, 30 seconds 48 kHz stereo PCM-16 per archive, 5760000 PCM bytes per generation")
        return
    }
    repeat(3) { check(AtomicProjectStore(directory).loadWithRevision()?.revision == 4L) }
    repeat(9) { index ->
        val nanos = measureNanoTime {
            val recovered = requireNotNull(AtomicProjectStore(directory).loadWithRevision())
            check(recovered.revision == 4L && recovered.state.bpm == 93f)
            check(recovered.state.currentAudio?.samples?.size == 2_880_000)
        }
        println("${args.getOrElse(2) { "candidate" }},$index,${nanos / 1_000_000.0}")
    }
}
