package com.choplab.desktop.separation

import com.choplab.desktop.source.DesktopAudioDecoder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Manual verification entry point (not part of unit tests):
 * `./gradlew :desktop:separateDrums -PseparateInput=song.wav -PseparateOutput=drums.wav`.
 */
fun main(args: Array<String>) {
    val parsed = mutableMapOf<String, String>()
    var index = 0
    while (index < args.size - 1) {
        if (args[index].startsWith("--")) parsed[args[index].removePrefix("--")] = args[index + 1]
        index += 2
    }
    val input = File(parsed["input"] ?: error("Missing --input song.wav"))
    val output = File(parsed["output"] ?: "drums.wav")
    val models = File(parsed["models"] ?: "work/separator-models")
    val latch = CountDownLatch(1)
    val failure = AtomicReference<String?>(null)
    DrumSeparationService(models, DesktopAudioDecoder::decode).use { service ->
        if (!service.isModelAvailable()) {
            System.err.println("Model missing: ${service.modelFile().absolutePath}")
            System.err.println("Run: python scripts/prepare_separator_model.py --out work/separator-models")
            return
        }
        val started = System.nanoTime()
        val accepted = service.separate(
            DrumSeparationService.Request(
                sourceFile = input,
                outputFile = output,
                onProgress = { println("progress: ${(it * 100).toInt()}%") },
                onDone = {
                    println("wrote ${it.absolutePath} in ${(System.nanoTime() - started) / 1_000_000_000}s")
                    latch.countDown()
                },
                onError = {
                    failure.set(it)
                    latch.countDown()
                },
                onCancelled = {
                    failure.set("cancelled")
                    latch.countDown()
                },
            ),
        )
        if (!accepted) {
            System.err.println("Another separation job is already running")
            return
        }
        latch.await()
    }
    failure.get()?.let {
        System.err.println("FAILED: $it")
        kotlin.system.exitProcess(1)
    }
}
