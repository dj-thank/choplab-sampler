package com.choplab.desktop.audio

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Stands in for the ScreenCaptureKit helper in tests on any OS: same header and PCM-16 stream,
 * written in odd-sized chunks so frames are split across pipe reads.
 */
object FakeSystemAudioHelper {
    @JvmStatic fun main(args: Array<String>) {
        val mode = args.firstOrNull() ?: "normal"
        val out = System.out
        if (mode == "refused") {
            out.write("CHOPLAB-ERROR denied\n".toByteArray()); out.flush()
            exitProcess(1)
        }
        if (mode == "late") Thread.sleep(args.getOrNull(1)?.toLong() ?: 1_500)
        val finished = AtomicBoolean(false)
        Thread { while (System.`in`.read() >= 0) Unit; finished.set(true) }.apply { isDaemon = true; start() }
        out.write("CHOPLAB-PCM 48000 2\n".toByteArray()); out.flush()
        var frame = 0
        val started = System.nanoTime()
        while (!finished.get()) {
            if (mode == "dies" && System.nanoTime() - started > 150_000_000L) {
                out.flush()
                exitProcess(2)
            }
            // 480 frames per 10 ms, sent as 7-byte pieces.
            val chunk = ByteArray(480 * 4)
            for (i in 0 until 480) {
                val value = pattern(frame++)
                chunk[i * 4] = value.toByte(); chunk[i * 4 + 1] = (value shr 8).toByte()
                chunk[i * 4 + 2] = (-value).toByte(); chunk[i * 4 + 3] = ((-value) shr 8).toByte()
            }
            var offset = 0
            while (offset < chunk.size) {
                val size = minOf(7, chunk.size - offset)
                out.write(chunk, offset, size)
                offset += size
            }
            out.flush()
            Thread.sleep(10)
        }
    }

    fun pattern(frame: Int): Int = frame % 1_000

    /** Java launcher for this helper; the recorder under test starts it like the real executable. */
    fun launcher(vararg args: String): (File) -> Process = {
        val java = File(System.getProperty("java.home"), "bin/java").path
        ProcessBuilder(listOf(java, "-cp", System.getProperty("java.class.path"), FakeSystemAudioHelper::class.java.name) + args).start()
    }
}
