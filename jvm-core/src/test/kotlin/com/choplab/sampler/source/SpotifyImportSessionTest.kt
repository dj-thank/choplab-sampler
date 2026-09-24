package com.choplab.sampler.source

import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.Assert.*

class SpotifyImportSessionTest {
    private val client="0123456789abcdef0123456789abcdef"
    private val body="""{"items":[{"track":{"id":"0123456789012345678901","name":"Song","artists":[{"name":"Artist"}],"duration_ms":123000}}],"next":null}"""
    private fun state(url:String)=URI(url).rawQuery.split('&').map{it.split('=',limit=2)}.first{it[0]=="state"}[1].let{URLDecoder.decode(it,"UTF-8")}
    private fun await(condition:()->Boolean) { val limit=System.currentTimeMillis()+5000;while(!condition()&&System.currentTimeMillis()<limit)Thread.sleep(10);assertTrue(condition()) }
    @Test fun callbackStateIsRequiredAndTokensNeverBecomeUiState() {
        var authorization="";var form=""
        val http=object:SpotifyImportHttp {
            override fun token(value:String):String { form=value;return """{"access_token":"test-access","expires_in":3600}""" }
            override fun favorites(accessToken:String,offset:Int):String { assertEquals("test-access",accessToken);return body }
        }
        SpotifyImportSession("choplab://spotify/callback",{authorization=it},http).use { session ->
            session.login(client)
            assertTrue(authorization.contains("code_challenge_method=S256"))
            assertFalse(session.acceptCallback("choplab://spotify/callback?state=wrong&code=x"))
            assertFalse(session.acceptCallback("choplab://spotify/callback?state=%GG&code=x"))
            assertTrue(session.acceptCallback("choplab://spotify/callback?state=${state(authorization)}&code=accepted"))
            await{session.state.value.connected}
            assertTrue(form.contains("code_verifier="))
            assertEquals(1,session.state.value.tracks.size)
            assertFalse(session.state.value.toString().contains("test-access"))
            session.disconnect()
            assertFalse(session.state.value.connected);assertTrue(session.state.value.tracks.isEmpty())
        }
    }
    @Test fun disconnectDuringExchangeDoesNotFetchOrRestoreTheAccount() {
        var authorization="";var favoriteCalls=0
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val http=object:SpotifyImportHttp {
            override fun token(form:String):String { entered.countDown();check(release.await(3,TimeUnit.SECONDS));return """{"access_token":"test-access","expires_in":3600}""" }
            override fun favorites(accessToken:String,offset:Int):String { favoriteCalls++;return body }
        }
        SpotifyImportSession("choplab://spotify/callback",{authorization=it},http).use { session ->
            session.login(client)
            session.acceptCallback("choplab://spotify/callback?state=${state(authorization)}&code=accepted")
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            session.disconnect();release.countDown();Thread.sleep(100)
            session.loadMore();Thread.sleep(100)
            assertFalse(session.state.value.connected);assertTrue(session.state.value.tracks.isEmpty());assertEquals(0,favoriteCalls)
        }
    }
}
