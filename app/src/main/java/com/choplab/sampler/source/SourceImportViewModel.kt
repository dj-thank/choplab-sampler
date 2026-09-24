package com.choplab.sampler.source

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.ffmpeg.FFmpeg
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidYoutubeBackend(private val application:Application):YoutubeSourceBackend {
    @Volatile private var initialized=false
    @Synchronized private fun ready() {
        if(initialized)return
        YoutubeDL.getInstance().init(application)
        FFmpeg.getInstance().init(application)
        if(Thread.currentThread().isInterrupted) throw InterruptedException()
        initialized=true
    }
    private fun execute(args:List<String>,id:String,progress:((Float)->Unit)?=null):String {
        ready()
        if(Thread.currentThread().isInterrupted) throw InterruptedException()
        val request=YoutubeDLRequest(args.last()).addCommands(args.dropLast(1))
        return YoutubeDL.getInstance().execute(request,processId=id,callback={p,_,_->progress?.invoke(p)}).out
    }
    override fun search(query:String,jobId:String)=SourceRecipes.parseCandidates(execute(SourceRecipes.searchArguments(query),jobId))
    override fun info(url:String,jobId:String)=SourceRecipes.parseInfo(execute(SourceRecipes.infoArguments(url),jobId))
    override fun download(source:YoutubeSource,folder:File,jobId:String,progress:(Float)->Unit):File {
        execute(SourceRecipes.downloadArguments(source.url,File(folder,"audio.%(ext)s").absolutePath),jobId,progress)
        return File(folder,"audio.wav").also { require(it.isFile && it.length()>44) }
    }
    override fun cancel(jobId:String) { if(initialized) YoutubeDL.getInstance().destroyProcessById(jobId) }
}

class SourceImportViewModel(application:Application):AndroidViewModel(application) {
    val hub=AudioSourceController(LocalAudioLibrary(File(application.filesDir,"audio-library")) { file -> kotlinx.coroutines.runBlocking { com.choplab.sampler.audio.AudioDecoder(application).decode(Uri.fromFile(file)) };Unit },AndroidYoutubeBackend(application))
    val spotify=SpotifyImportSession(REDIRECT_URI, { link ->
        application.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }, defaultClientId=com.choplab.sampler.BuildConfig.SPOTIFY_CLIENT_ID)
    /** Liked tracks import automatically after login; search additions queue behind that import. */
    val spotifySync=SpotifyFavoritesAutoImport(spotify.state,hub,spotify::loadAllFavorites,
        isConnected={it.connected},isBusy={it.busy},libraryRevision={it.libraryRevision},libraryTracks={it.tracks})
    private val visibleState=MutableStateFlow(false)
    val visible=visibleState.asStateFlow()
    fun show() { hub.refresh();visibleState.value=true }
    fun hide() {
        // Closing the panel keeps an owned Spotify queue running, as on Windows.
        hub.dismiss()
        if(spotify.state.value.busy && !spotify.state.value.connected) spotify.cancelAuthentication()
        visibleState.value=false
    }
    fun used() { visibleState.value=false }
    fun disconnectSpotify() { spotifySync.cancel();spotify.disconnect() }
    fun cancelImport() { spotifySync.cancel();hub.cancel() }
    fun importUris(uris:List<Uri>) {
        val resolver=getApplication<Application>().contentResolver
        hub.importInputs(uris.filter { it.scheme=="content" }.map { uri ->
            LibrarySourceInput({
                val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { cursor ->
                    if(cursor.moveToFirst()) cursor.getString(0) else null
                }
                name ?: "音源." + when(resolver.getType(uri)) {
                    "audio/mpeg"->"mp3";"audio/mp4","audio/x-m4a"->"m4a";"video/mp4"->"mp4";"video/webm"->"webm";"application/zip"->"zip";"audio/wav","audio/x-wav"->"wav";else->error("音源のファイル名と形式を確認できません")
                }
            }, { requireNotNull(resolver.openInputStream(uri)) { "音源を開けませんでした" } })
        })
    }
    fun handleIntent(intent:Intent?) {
        if(intent==null)return
        val data=intent.data
        if(data?.scheme==spotifyCallbackScheme(com.choplab.sampler.BuildConfig.APPLICATION_ID) && data.host=="spotify") {
            spotify.acceptCallback(data.toString());show();hub.section(SourceSection.SPOTIFY);return
        }
        if(intent.action==Intent.ACTION_SEND) {
            val text=intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val link=Regex("""https://[^\s]+""").findAll(text).mapNotNull { runCatching { SourceRecipes.youtubeUrl(it.value) }.getOrNull() }.firstOrNull()
            if(link!=null) { show();hub.section(SourceSection.YOUTUBE);hub.query(link);hub.search();return }
            @Suppress("DEPRECATION") val file=intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if(file!=null) { show();importUris(listOf(file)) }
        }
    }
    override fun onCleared() { spotifySync.close();hub.close();spotify.close() }
    companion object {
        val REDIRECT_URI = spotifyCallbackScheme(com.choplab.sampler.BuildConfig.APPLICATION_ID) + "://spotify/callback"
    }
}
