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
        if (mode == "float" || mode == "float-dies" || mode == "float-permission" || mode == "float-error") {
            out.write("CHOPLAB-FLOAT32 48000 2\n".toByteArray()); out.flush()
            val bytes = java.nio.ByteBuffer.allocate(48_000 * 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            repeat(48_000) { frame -> bytes.putFloat(.1234567f + frame / 1_000_000f); bytes.putFloat(-.2345678f - frame / 1_000_000f) }
            for (at in bytes.array().indices step 7) { out.write(bytes.array(), at, minOf(7, bytes.capacity() - at)); out.flush() }
            if (mode == "float-permission" || mode == "float-error") {
                System.err.println("CHOPLAB-END ${if (mode == "float-permission") "PERMISSION" else "READ_FAILED"}")
                System.err.flush(); exitProcess(2)
            }
            if (mode == "float-dies") exitProcess(2)
            while (System.`in`.read() >= 0) Unit
            return
        }
        if (mode == "mic-late" || mode == "mic-denied") {
            if (mode == "mic-late") Thread.sleep(10_000)
            out.write("CHOPLAB-MIC ${if (mode == "mic-denied") "DENIED" else "AUTHORIZED"}\n".toByteArray()); out.flush()
            return
        }
        if (mode == "no-display") {
            out.write("CHOPLAB-ERROR NO_DISPLAY No visible display\n".toByteArray()); out.flush()
            exitProcess(1)
        }
        if (mode == "unavailable") {
            out.write("CHOPLAB-ERROR UNAVAILABLE Start failed\n".toByteArray()); out.flush()
            exitProcess(1)
        }
        if (mode == "refused") {
            out.write("CHOPLAB-ERROR DENIED User declined\n".toByteArray()); out.flush()
            exitProcess(1)
        }
        if (mode == "late") Thread.sleep(args.getOrNull(1)?.toLong() ?: 1_500)
        val finished = AtomicBoolean(false)
        Thread { while (System.`in`.read() >= 0) Unit; finished.set(true) }.apply { isDaemon = true; start() }
        out.write("CHOPLAB-PCM 48000 2\n".toByteArray()); out.flush()
        // Optional cold-worker delay after the header: the consumer must await data, not assume pipe throughput.
        if (mode == "normal") args.getOrNull(1)?.toLong()?.let(Thread::sleep)
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
