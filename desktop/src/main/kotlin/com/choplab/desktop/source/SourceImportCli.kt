package com.choplab.desktop.source

import com.choplab.sampler.source.*
import com.choplab.desktop.DesktopProfile
import java.io.File

/** Local acceptance helper. Inputs are user-owned files; no Spotify credentials are accepted. */
fun main(args:Array<String>) {
    val options=args.toList().chunked(2).associate { it[0] to it.getOrElse(1){""} }
    val library=LocalAudioLibrary(options["--library"]?.let(::File) ?: File(DesktopProfile.dataDirectory(),"audio-library")) { DesktopAudioDecoder.validate(it) }
    options["--spotify-check"]?.let {
        com.choplab.desktop.provider.SpotifyDesktopSession(onStatus={}).use { session ->
            session.login()
            val deadline=System.currentTimeMillis()+300000
            while(session.state.value.busy && System.currentTimeMillis()<deadline) Thread.sleep(100)
            check(session.connected) { "SPOTIFY_LOGIN_FAILED: ${session.state.value.message}" }
            session.showLibrary()
            while(session.state.value.busy && System.currentTimeMillis()<deadline) Thread.sleep(100)
            check(session.connected && session.state.value.librarySummary!="ライブラリは未取得です") { "SPOTIFY_LIBRARY_FAILED" }
            println("SPOTIFY_FAVORITES_RECEIVED ${session.state.value.sourceTracks.size}")
        }
    }
    options["--list"]?.let { list ->
        File(list).readLines(Charsets.UTF_8).filter { it.isNotBlank() }.forEach { row ->
            val fields=row.split('\t',limit=2)
            val input=File(fields[0])
            val item=library.importFile(input,fields.getOrNull(1)?:input.nameWithoutExtension,"既存の音源")
            val decoded=DesktopAudioDecoder.decode(library.resolve(item.id))
            println("IMPORTED ${item.id} ${item.title} frames=${decoded.frameCount} rate=${decoded.sampleRate}")
        }
    }
    options["--youtube"]?.let { url ->
        AudioSourceController(library,DesktopYoutubeBackend()).use { hub ->
            hub.query(url);hub.search()
            val deadline=System.currentTimeMillis()+12*60*1000
            while(hub.state.value.busy && System.currentTimeMillis()<deadline)Thread.sleep(50)
            check(!hub.state.value.busy && hub.state.value.pendingUseId!=null) { hub.state.value.message }
            val item=library.list().first { it.id==hub.state.value.pendingUseId }
            val decoded=DesktopAudioDecoder.decode(library.resolve(item.id))
            println("DOWNLOADED ${item.id} ${item.title} frames=${decoded.frameCount}")
        }
    }
    options["--export"]?.let { library.exportBundle(File(it));println("LIBRARY_BUNDLE_CREATED") }
    println("LIBRARY_ITEMS ${library.list().size}")
}
