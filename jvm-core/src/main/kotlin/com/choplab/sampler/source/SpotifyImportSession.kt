package com.choplab.sampler.source

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.*

interface SpotifyImportHttp {
    fun token(form: String): String
    fun favorites(accessToken: String, offset: Int): String
}
class SpotifyImportHttpError(val status: Int): Exception("Spotify HTTP $status")
class UrlConnectionSpotifyImportHttp : SpotifyImportHttp {
    private fun request(url: String, form: String? = null, token: String? = null): String {
        val connection=URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout=15000;connection.readTimeout=20000
        connection.instanceFollowRedirects=false
        connection.setRequestProperty("Accept","application/json")
        if(token!=null) connection.setRequestProperty("Authorization","Bearer $token")
        try {
            if(form!=null) {
                connection.requestMethod="POST";connection.doOutput=true
                connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            }
            if(connection.responseCode !in 200..299) throw SpotifyImportHttpError(connection.responseCode)
            return connection.inputStream.use { input ->
                val output=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(8192)
                while(true) {
                    val n=input.read(buffer);if(n<0)break
                    require(output.size()+n<=2_000_000) { "Spotifyの応答が大きすぎます" }
                    output.write(buffer,0,n)
                }
                output.toByteArray().toString(Charsets.UTF_8)
            }
        } finally { connection.disconnect() }
    }
    override fun token(form:String)=request("https://accounts.spotify.com/api/token",form=form)
    override fun favorites(accessToken:String,offset:Int)=request("https://api.spotify.com/v1/me/tracks?limit=50&offset=$offset",token=accessToken)
}

/** PKCE browser authentication, memory-only credentials, and official saved-track metadata. */
class SpotifyImportSession(
    private val redirectUri: String,
    private val openBrowser: (String)->Unit,
    private val http: SpotifyImportHttp = UrlConnectionSpotifyImportHttp(),
    defaultClientId: String = "",
) : AutoCloseable {
    private val mutable=MutableStateFlow(SpotifyImportState(configured=defaultClientId.isNotBlank()))
    val state=mutable.asStateFlow()
    private val executor=Executors.newSingleThreadExecutor { Thread(it,"ChopLab-Spotify-Import").apply { isDaemon=true } }
    private val generation=AtomicLong()
    private val lock=Any()
    private fun publish(lease:Long, action:()->Unit) = synchronized(lock) { if(generation.get()==lease) action() }
    private data class Attempt(val client:String,val verifier:String,val state:String)
    private data class Tokens(val access:String,val refresh:String?,val expires:Long)
    @Volatile private var attempt:Attempt?=null
    @Volatile private var tokens:Tokens?=null
    private var clientId=defaultClientId
    private var offset=0
    private fun encoded(value:String)=URLEncoder.encode(value,"UTF-8")
    private fun random():String=Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(48).also { SecureRandom().nextBytes(it) })
    fun login(client:String) {
        if(mutable.value.busy)return
        val id=client.trim().ifEmpty { clientId }
        if(!Regex("[A-Za-z0-9]{16,128}").matches(id)) { mutable.update{it.copy(message="Spotify Client IDを入力してください。Client Secretは不要です")};return }
        generation.incrementAndGet();tokens=null;clientId=id
        val request=Attempt(id,random(),random());attempt=request
        val challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(request.verifier.toByteArray(Charsets.US_ASCII)))
        mutable.value=SpotifyImportState(busy=true,configured=true,message="ブラウザでSpotifyにログインしてください")
        val parameters=linkedMapOf("client_id" to id,"response_type" to "code","redirect_uri" to redirectUri,
            "scope" to "user-library-read","state" to request.state,"code_challenge_method" to "S256","code_challenge" to challenge)
        try { openBrowser("https://accounts.spotify.com/authorize?"+parameters.entries.joinToString("&") { encoded(it.key)+"="+encoded(it.value) }) }
        catch(error:Exception) { attempt=null;mutable.update{it.copy(busy=false,message="ログイン用ブラウザを開けませんでした")} }
    }
    fun acceptCallback(url:String):Boolean {
        val pending=attempt?:return false
        if(url.length>8192)return false
        val uri=runCatching{URI(url)}.getOrNull()?:return false
        val expected=URI(redirectUri)
        if(uri.scheme!=expected.scheme || uri.host!=expected.host || uri.path!=expected.path)return false
        val query=runCatching {
            val pairs=uri.rawQuery.orEmpty().split('&').map { it.split('=',limit=2) }.filter{it.size==2}
                .map { URLDecoder.decode(it[0],"UTF-8") to URLDecoder.decode(it[1],"UTF-8") }
            require(pairs.map{it.first}.distinct().size==pairs.size)
            pairs.toMap()
        }.getOrNull()?:return false
        if(query["state"]!=pending.state)return false
        if(query["error"]!=null) { attempt=null;mutable.update{it.copy(busy=false,message="Spotifyへの連携をキャンセルしました")};return true }
        val code=query["code"]?.takeIf{it.length in 1..4096}?:return false
        attempt=null
        val lease=generation.get()
        executor.execute {
            try {
                val form=linkedMapOf("client_id" to pending.client,"grant_type" to "authorization_code","code" to code,
                    "redirect_uri" to redirectUri,"code_verifier" to pending.verifier).entries.joinToString("&") { encoded(it.key)+"="+encoded(it.value) }
                val received=parseTokens(http.token(form),null)
                if(generation.get()!=lease)return@execute
                publish(lease) { tokens=received;offset=0 }
                fetch(lease,append=false)
            } catch(error:Exception) { fail(lease,error) }
        }
        return true
    }
    private fun parseTokens(body:String, oldRefresh:String?):Tokens {
        require(body.length<100_000)
        val obj=Json.parseToJsonElement(body).jsonObject
        val access=obj["access_token"]?.jsonPrimitive?.contentOrNull?:error("Token missing")
        val expires=obj["expires_in"]?.jsonPrimitive?.longOrNull?:error("Expiry missing")
        require(expires in 1..86400 && access.length<=8192)
        return Tokens(access,obj["refresh_token"]?.jsonPrimitive?.contentOrNull?:oldRefresh,System.currentTimeMillis()+expires*1000-30000)
    }
    private fun accessToken(lease:Long):String {
        val current=tokens?:error("Login required")
        if(System.currentTimeMillis()<current.expires)return current.access
        val refresh=current.refresh?:throw SpotifyImportHttpError(401)
        val form="client_id=${encoded(clientId)}&grant_type=refresh_token&refresh_token=${encoded(refresh)}"
        val received=parseTokens(http.token(form),refresh)
        if(generation.get()!=lease)throw java.util.concurrent.CancellationException()
        publish(lease) { tokens=received }
        return received.access
    }
    fun loadMore() {
        if(mutable.value.busy || !mutable.value.connected || tokens==null)return
        val lease=generation.get();mutable.update{it.copy(busy=true)}
        executor.execute { try { fetch(lease,append=true) } catch(error:Exception){fail(lease,error)} }
    }
    private fun fetch(lease:Long,append:Boolean) {
        if(generation.get()!=lease)return
        val token=accessToken(lease)
        if(generation.get()!=lease)return
        val response=http.favorites(token,if(append)offset else 0)
        val tracks=SourceRecipes.parseSpotifyTracks(response)
        if(generation.get()!=lease)return
        publish(lease) {
            offset=if(append)offset+50 else 50
            mutable.update { it.copy(connected=true,busy=false,message="お気に入りをタップすると、対応するYouTube音源を取り込みます",
                tracks=if(append)(it.tracks+tracks).distinctBy(SourceTrack::spotifyUrl) else tracks,hasMore=SourceRecipes.spotifyHasNext(response)) }
        }
    }

    private fun fail(lease:Long,error:Exception) {
        if(generation.get()!=lease)return
        val message=when((error as? SpotifyImportHttpError)?.status) {
            401 -> "認証期限が切れました。もう一度ログインしてください"
            403 -> "Spotifyの開発モードの許可ユーザー・Premium・権限を確認してください"
            429 -> "Spotifyへのアクセスが集中しています。少し待って再試行してください"
            else -> "Spotifyに接続できませんでした。設定と接続状態を確認してください"
        }
        publish(lease) { tokens=null;mutable.update { it.copy(busy=false,connected=false,tracks=emptyList(),message=message) } }
    }
    fun cancelAuthentication() { generation.incrementAndGet();attempt=null;mutable.update{it.copy(busy=false,message="認証を中止しました")} }
    fun disconnect() = synchronized(lock) { generation.incrementAndGet();attempt=null;tokens=null;offset=0;mutable.value=SpotifyImportState(configured=clientId.isNotEmpty(),message="Spotifyの連携を解除しました") }
    override fun close() { disconnect();executor.shutdownNow() }
}
