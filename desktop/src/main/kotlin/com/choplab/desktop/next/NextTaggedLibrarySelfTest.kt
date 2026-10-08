package com.choplab.desktop.next

import com.choplab.jvm.WavCodec
import com.choplab.jvm.sha256
import com.choplab.sampler.source.LibraryBrowser
import com.choplab.sampler.source.LocalAudioLibrary
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Explicit synthetic codec fixture; never opens a provider, device, output endpoint, or existing profile. */
object NextTaggedLibrarySelfTest {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2) { "Use <new-temporary-directory> <ffmpeg>" }
        val directory = Path.of(args[0]); require(!Files.exists(directory)); Files.createDirectories(directory)
        val ffmpeg = Path.of(args[1]); require(Files.isExecutable(ffmpeg))
        val original = directory.resolve("synthetic.wav")
        Files.newOutputStream(original).use { WavCodec.writeFloat(it, FloatArray(9_600) { i -> if (i % 31 < 15) 0.05f else -0.05f }) }
        NextBackend.create(directory.resolve("profile"), sinkFactory = { error("No audio output in tagged-library fixture") }).use { backend ->
            val library = LocalAudioLibrary(directory.resolve("library").toFile(), backend::validateLibraryFile)
            for ((extension, codec) in listOf("flac" to "flac", "mp3" to "libmp3lame")) {
                val source = directory.resolve("001.$extension")
                val process = ProcessBuilder(ffmpeg.toString(), "-nostdin", "-v", "error", "-i", original.toString(), "-c:a", codec,
                    "-metadata", "title=観測した曲名", "-metadata", "artist=Synthetic artist", "-metadata", "album=Synthetic album",
                    "-metadata", "track=3/12", "-metadata", "disc=2/2", source.toString()).redirectError(ProcessBuilder.Redirect.INHERIT).start()
                try { check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) }
                finally { if (process.isAlive) process.destroyForcibly() }
                val bytes = Files.readAllBytes(source)
                val result = library.importFileResult(source.toFile())
                check(result.added && result.tagProblem == null)
                check(result.item.title == "観測した曲名" && result.item.artist == "Synthetic artist" && result.item.album == "Synthetic album")
                check(result.item.trackNumber == 3 && result.item.discNumber == 2)
                check(result.item.id == sha256(bytes) && library.resolve(result.item.id).readBytes().contentEquals(bytes))
                check(!library.importFileResult(source.toFile()).added)
                println("TAGGED_LIBRARY_PASS $extension title/artist/album/disc/track original-bytes duplicate")
            }
            val browser = LibraryBrowser()
            browser.open(browser.page(library.list()).groups.single())
            browser.open(browser.page(library.list()).groups.single())
            check(browser.page(library.list()).tracks.size == 2)
            check(backend.studio.document.value.project.assets.isEmpty())
        }
    }
}
