package com.choplab.desktop.separation

import com.choplab.desktop.source.DesktopAudioDecoder
import com.choplab.desktop.prepareDesktopOnnxRuntime
import com.choplab.sampler.separation.DrumSeparationService
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * Manual verification entry point (not part of unit tests):
 * `./gradlew :desktop:separateDrums -PseparateInput=song.wav -PseparateOutput=drums.wav`.
 */
fun main(args: Array<String>) {
    prepareDesktopOnnxRuntime()
    val parsed = mutableMapOf<String, String>()
    var index = 0
    while (index < args.size - 1) {
        if (args[index].startsWith("--")) parsed[args[index].removePrefix("--")] = args[index + 1]
        index += 2
    }
    val input = File(parsed["input"] ?: error("Missing --input song.wav"))
    val output = File(parsed["output"] ?: "drums.wav")
    val models = parsed["models"]?.let(::File) ?: defaultSeparatorModelsDir()
    println("models: ${models.absolutePath}")
    val latch = CountDownLatch(1)
    val failure = AtomicReference<String?>(null)
    val store = com.choplab.sampler.separation.SeparatorModelStore(models)
    DrumSeparationService(models, DesktopAudioDecoder::decode,
        modelProvider = { progress, cancelled -> store.ensure(progress, cancelled) }).use { service ->
        val started = System.nanoTime()
        val accepted = service.separate(
            DrumSeparationService.Request(
                sourceFile = input,
                outputFile = output,
                onModelProgress = { println("model download (first use, 166 MB): ${(it * 100).toInt()}%") },
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
