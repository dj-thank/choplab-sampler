package com.choplab.sampler.source

import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class LibrarySourceInput(val name: ()->String, val open: ()->java.io.InputStream)

interface YoutubeSourceBackend {
    fun search(query: String, jobId: String): List<YoutubeSource>
    fun info(url: String, jobId: String): YoutubeSource
    fun download(source: YoutubeSource, folder: File, jobId: String, progress: (Float) -> Unit): File
    fun cancel(jobId: String)
}

/** One owned import job; stale completions cannot auto-select audio. Completed library files are retained. */
class AudioSourceController(val library: LocalAudioLibrary, private val backend: YoutubeSourceBackend) : AutoCloseable {
    private val mutable = MutableStateFlow(AudioSourceState())
    val state = mutable.asStateFlow()
    private val executor = Executors.newSingleThreadExecutor { Thread(it,"ChopLab-Source-Import").apply { isDaemon=true } }
    private val epoch = AtomicLong()
    private var future: Future<*>? = null
    private var jobId: String? = null
    init { refresh() }
    fun section(value: SourceSection) = mutable.update { it.copy(section=value) }
    fun query(value: String) = mutable.update { if(it.query==value.take(240))it else it.copy(query=value.take(240),candidates=emptyList()) }
    fun refresh() { executor.execute { mutable.update { it.copy(library=library.list()) } } }
    fun file(id: String): File = library.resolve(id)
    fun consumed(id: String) = mutable.update { if(it.pendingUseId==id) it.copy(pendingUseId=null) else it }

    private fun job(message: String, action: (Long,String)->Unit) {
        if (mutable.value.busy) return
        val lease=epoch.incrementAndGet(); val id=UUID.randomUUID().toString(); jobId=id
        mutable.update { it.copy(busy=true,message=message,pendingUseId=null) }
        future=executor.submit {
            try { action(lease,id) } catch (error: Exception) {
                publish(lease) { it.copy(message=when(error) {
                    is InterruptedException -> "取り込みを中止しました"
                    is IllegalArgumentException -> error.message ?: "入力を確認してください"
                    else -> "取り込みに失敗しました。URL・接続状態・音源形式を確認してください"
                }) }
            } finally { publish(lease) { it.copy(busy=false,library=library.list()) } }
        }
    }
    private fun publish(lease:Long,transform:(AudioSourceState)->AudioSourceState) {
        mutable.update { if(epoch.get()==lease)transform(it) else it }
    }
    private fun current(lease: Long) { if(epoch.get()!=lease || Thread.currentThread().isInterrupted) throw InterruptedException() }
    fun importFiles(files: List<File>) = importInputs(files.map { file -> LibrarySourceInput({file.name},{file.inputStream()}) })
    fun importInputs(inputs: List<LibrarySourceInput>) = job("音源をライブラリに追加しています") { lease,_ ->
        val added=mutableListOf<AudioLibraryItem>()
        inputs.forEach { incoming ->
            current(lease)
            val name=incoming.name().take(300)
            val extension=name.substringAfterLast('.',"").lowercase()
            incoming.open().use { input ->
                added += if(extension in setOf("zip","choplib")) library.importBundle(input)
                else listOf(library.importStream(input,extension,name.substringBeforeLast('.'),"ファイル"))
            }
        }
        current(lease)
        publish(lease) { it.copy(library=library.list(),section=SourceSection.LIBRARY,message="${added.size}件を追加しました",pendingUseId=added.singleOrNull()?.id) }
    }
    fun search() = job("YouTubeで音源を探しています") { lease,id ->
        val query=mutable.value.query.trim()
        if(query.startsWith("https://")) {
            val source=backend.info(SourceRecipes.youtubeUrl(query),id); current(lease); download(lease,id,source)
        } else {
            val candidates=backend.search(query,id); current(lease)
            publish(lease) { it.copy(candidates=candidates,section=SourceSection.YOUTUBE,message="取り込む音源を選んでください") }
        }
    }
    fun importFavorite(track: SourceTrack) {
        if(mutable.value.busy)return
        mutable.update { it.copy(query=track.query.take(240),section=SourceSection.YOUTUBE,candidates=emptyList()) }
        job("${track.title} をYouTubeから追加しています") { lease,id ->
            val candidates=backend.search(track.query.take(240),id);current(lease)
            val selected=SourceRecipes.matchingFavorite(track,candidates)
            if(selected==null) publish(lease) { it.copy(candidates=candidates,message="同名曲の取り違えを避けるため、今回の候補を選んでください") }
            else {
                val checked=backend.info(selected.url,id);current(lease)
                if(SourceRecipes.matchingFavorite(track,listOf(checked))==null) publish(lease) {
                    it.copy(candidates=candidates,message="曲の長さや版が異なるため、候補を確認してください")
                } else download(lease,id,checked)
            }
        }
    }
    fun download(source: YoutubeSource) = job("${source.title} を追加しています") { lease,id ->
        download(lease,id,backend.info(source.url,id))
    }
    private fun download(lease: Long,id: String,source: YoutubeSource) {
        current(lease)
        val stagingRoot=File(library.directory,".staging").apply { mkdirs() }.canonicalFile
        val folder=File(stagingRoot,id).apply { mkdir() }.canonicalFile
        check(folder.parentFile==stagingRoot)
        try {
            val output=backend.download(source,folder,id) { percent ->
                if(percent.isFinite()) publish(lease) { it.copy(message="${source.title} を取得中 ${percent.toInt().coerceIn(0,100)}%") }
            }
            current(lease)
            require(output.canonicalFile.parentFile==folder && output.isFile)
            val item=library.importFile(output,source.title,source.url)
            current(lease)
            publish(lease) { it.copy(library=library.list(),section=SourceSection.LIBRARY,message="${item.title} を追加しました",pendingUseId=item.id) }
        } finally {
            check(folder.canonicalFile.parentFile==stagingRoot)
            folder.deleteRecursively()
        }
    }
    fun cancel() {
        epoch.incrementAndGet(); jobId?.let(backend::cancel); future?.cancel(true)
        mutable.update { it.copy(busy=false,pendingUseId=null,candidates=emptyList(),message=if(it.busy) "取り込みを中止しました" else it.message) }
    }
    override fun close() { cancel();executor.shutdownNow() }
}
