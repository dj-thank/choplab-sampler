package com.choplab.desktop.next

import com.choplab.jvm.WavAudio
import com.choplab.jvm.WavCodec
import com.choplab.jvm.WavInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** Explicit real-model smoke test; all generated audio is temporary. It does not assess separation quality. */
object NextDrumSeparationSelfTest {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "Pass the pinned ONNX model path" }
        val root = Files.createTempDirectory("choplab-next-real-separator-")
        try {
            val frames = 22_050
            val samples = FloatArray(frames * 2) { index ->
                val frame = index / 2
                val time = frame / 44_100.0
                val envelope = exp(-((frame % 5_512) / 44_100.0) * 35)
                (sin(time * Math.PI * 2 * if (index % 2 == 0) 110.0 else 173.0) * envelope * .35).toFloat()
            }
            val original = samples.copyOf()
            val output = root.resolve("drums.wav")
            val start = System.nanoTime()
            NextDrumSeparation.renderWithModel(WavAudio(WavInfo(44_100, 2, frames.toLong(), 32, true), samples), output, Path.of(args[0]))
            val result = Files.newInputStream(output).use { WavCodec.read(it) }
            check(result.info == WavInfo(44_100, 2, frames.toLong(), 32, true))
            check(result.samples.all { it.isFinite() } && result.samples.any { abs(it) > 0.000001f })
            check(samples.contentEquals(original))
            println("{\"status\":\"LOCAL_PASS\",\"scope\":\"real-model-float-drums\",\"frames\":$frames,\"bits\":32,\"channels\":2,\"milliseconds\":${(System.nanoTime()-start)/1_000_000},\"sourceUnchanged\":true,\"humanAcceptance\":false}")
        } finally { root.toFile().deleteRecursively() }
    }
}
