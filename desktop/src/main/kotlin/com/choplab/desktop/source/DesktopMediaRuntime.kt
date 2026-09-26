package com.choplab.desktop.source

import com.choplab.sampler.source.*
import com.choplab.sampler.audio.AudioResourceLimits
import com.choplab.desktop.audio.DesktopWavDecoder
import com.choplab.sampler.model.PcmAudio
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object DesktopMediaRuntime {
    internal fun tools(
        windows: Boolean = System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true),
        path: String = System.getenv("PATH").orEmpty(),
    ): DesktopMediaTools {
        val explicit = listOfNotNull(
            System.getProperty("choplab.mediaTools")?.let(::File),
            System.getenv("CHOPLAB_MEDIA_TOOLS")?.let(::File),
            File(System.getProperty("java.home")).parentFile?.resolve("tools"),
            File("work/media-tools"),
            File("../work/media-tools"),
        )
        return locateDesktopMediaTools(explicit, defaultMediaSearchDirectories(path), windows)
            ?: throw SourceImportUserError("取り込み用ツールがありません。ffmpeg、ffprobe、yt-dlp、node を用意するか、ChopLabのアプリ一式を使用してください")
    }
}

class DesktopYoutubeBackend : YoutubeSourceBackend {
    private val processes=ConcurrentHashMap<String,Process>()
    internal fun run(executable: File, arguments: List<String>, id: String, progress: (Float)->Unit = {}): String {
        require(executable.isFile)
        val process=ProcessBuilder(listOf(executable.absolutePath)+arguments).redirectErrorStream(true).start()
        processes[id]=process
        val bytes=ByteArrayOutputStream(); val failure=AtomicReference<Throwable?>()
        val reader=Thread({
            try {
                process.inputStream.use { input ->
                    val buffer=ByteArray(8192)
                    while(true) {
                        val size=input.read(buffer); if(size<0)break
                        if(bytes.size()+size>2_000_000) error("取込ツールの応答が大きすぎます")
                        bytes.write(buffer,0,size)
                        Regex("CHOPLAB: *([0-9.]+)%").findAll(String(buffer,0,size,Charsets.UTF_8)).forEach {
                            it.groupValues[1].toFloatOrNull()?.let(progress)
                        }
                    }
                }
            } catch(error:Throwable) { failure.set(error);process.destroyForcibly() }
        },"ChopLab-Import-Output").apply { isDaemon=true;start() }
        try {
            check(process.waitFor(12,TimeUnit.MINUTES)) { "取得がタイムアウトしました" }
            reader.join(5000)
            failure.get()?.let { throw IllegalStateException("取得応答を読み取れません",it) }
            check(process.exitValue()==0) { "音源を取得できませんでした" }
            return bytes.toString("UTF-8")
        } finally {
            processes.remove(id,process)
            if(process.isAlive) terminate(process)
            reader.join(1000)
        }
    }
    private fun terminate(process:Process) {
        process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
        process.destroyForcibly()
    }
    override fun cancel(jobId:String) { processes[jobId]?.let(::terminate) }
    private fun yt(arguments:List<String>,id:String,progress:(Float)->Unit = {}):String {
        val tools=DesktopMediaRuntime.tools()
        return run(tools.ytDlp, listOf("--ffmpeg-location",tools.ffmpeg.parentFile.absolutePath,"--js-runtimes","node:${tools.node.absolutePath}")+arguments,id,progress)
    }
    override fun search(query:String,jobId:String)=SourceRecipes.parseCandidates(yt(SourceRecipes.searchArguments(query),jobId))
    override fun info(url:String,jobId:String)=SourceRecipes.parseInfo(yt(SourceRecipes.infoArguments(url),jobId))
    override fun download(source:YoutubeSource,folder:File,jobId:String,progress:(Float)->Unit):File {
        progress(0f)
        yt(listOf("--newline","--progress-template","download:CHOPLAB:%(progress._percent_str)s")+
            SourceRecipes.downloadArguments(source.url,File(folder,"audio.%(ext)s").absolutePath),jobId,progress)
        return File(folder,"audio.wav").also { check(it.isFile && it.length()>44);progress(100f) }
    }
}

object DesktopAudioDecoder {
    fun decode(file:File):PcmAudio {
        if(file.extension.equals("wav",true)) return DesktopWavDecoder.decode(file)
        require(file.extension.lowercase() in LocalAudioLibrary.extensions) { "未対応の音声形式です" }
        AudioResourceLimits.requireImportFileSize(file.length())
        val tools=DesktopMediaRuntime.tools(); val runner=DesktopYoutubeBackend();val id=UUID.randomUUID().toString()
        val duration=runner.run(tools.ffprobe,listOf("-v","error","-protocol_whitelist","file,pipe","-show_entries","format=duration","-of","default=noprint_wrappers=1:nokey=1",file.absolutePath),id).trim().toDoubleOrNull()
        require(duration!=null && duration.isFinite() && duration in 0.001..600.0) { "10分以内の音声ファイルを使用してください" }
        val temporary=File.createTempFile("choplab-decode-",".wav")
        try {
            runner.run(tools.ffmpeg,listOf("-nostdin","-hide_banner","-loglevel","error","-protocol_whitelist","file,pipe","-i",file.absolutePath,"-map","0:a:0","-vn","-c:a","pcm_s16le","-ar","48000","-ac","2","-y",temporary.absolutePath),id)
            return DesktopWavDecoder.decode(temporary).copy(name=file.name.take(240))
        } finally { temporary.delete() }
    }
}
