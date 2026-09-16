package com.choplab.sampler.source

import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import org.junit.Assert.*
import org.junit.Test

/** Android automatic import and search on top of the PKCE session. */
class SpotifyImportSessionLibraryTest {
    private val client="0123456789abcdef0123456789abcdef"
    private fun state(url:String)=URI(url).rawQuery.split('&').map{it.split('=',limit=2)}.first{it[0]=="state"}[1].let{URLDecoder.decode(it,"UTF-8")}
    private fun await(condition:()->Boolean) { val limit=System.currentTimeMillis()+5000;while(!condition()&&System.currentTimeMillis()<limit)Thread.sleep(10);assertTrue(condition()) }
    private fun trackObject(index:Int)="""{"id":"${"%022d".format(index)}","name":"Song $index","artists":[{"name":"Artist"}],"duration_ms":123000}"""
    private fun page(from:Int,count:Int,next:Boolean)=
        """{"items":[${(from until from+count).joinToString(","){"""{"track":${trackObject(it)}}"""}}],"next":${if(next)"\"https://api.spotify.com/v1/me/tracks?offset=${from+count}\"" else "null"}}"""

    private fun connected(http:SpotifyImportHttp, block:(SpotifyImportSession)->Unit) {
        var authorization=""
        SpotifyImportSession("choplab://spotify/callback",{authorization=it},http).use { session ->
            session.login(client)
            assertTrue(session.acceptCallback("choplab://spotify/callback?state=${state(authorization)}&code=accepted"))
            await { session.state.value.connected && !session.state.value.busy }
            block(session)
        }
    }

    @Test fun automaticImportReadsEveryLikedTrackPageAndPublishesOneRevision() {
        val offsets=Collections.synchronizedList(mutableListOf<Int>())
        val http=object:SpotifyImportHttp {
            override fun token(form:String)="""{"access_token":"a","expires_in":3600}"""
            override fun favorites(accessToken:String,offset:Int):String {
                offsets+=offset
                return when(offset) { 0->page(0,50,true); 50->page(50,50,true); else->page(100,10,false) }
            }
        }
        connected(http) { session ->
            offsets.clear()
            val before=session.state.value.libraryRevision
            session.loadAllFavorites()
            await { session.state.value.libraryRevision==before+1 && !session.state.value.busy }
            assertEquals(listOf(0,50,100),offsets.toList())
            assertEquals(110,session.state.value.tracks.size)
            assertFalse(session.state.value.hasMore)
            assertTrue(session.state.value.connected)
        }
    }

    @Test fun transientLibraryFailureKeepsTheSessionButExpiredAuthorizationDisconnects() {
        val status=java.util.concurrent.atomic.AtomicInteger(0)
        val http=object:SpotifyImportHttp {
            override fun token(form:String)="""{"access_token":"a","expires_in":3600}"""
            override fun favorites(accessToken:String,offset:Int):String {
                status.get().let { if(it!=0) throw SpotifyImportHttpError(it) }
                return page(0,1,false)
            }
        }
        connected(http) { session ->
            status.set(429)
            session.loadAllFavorites()
            await { !session.state.value.busy }
            assertTrue(session.state.value.connected)
            assertEquals(0L,session.state.value.libraryRevision)
            assertTrue(session.state.value.message.contains("集中"))
            status.set(401)
            session.loadAllFavorites()
            await { !session.state.value.connected }
            assertTrue(session.state.value.tracks.isEmpty())
        }
    }

    @Test fun searchPublishesResultsForTheCurrentQueryOnly() {
        val queries=Collections.synchronizedList(mutableListOf<String>())
        val http=object:SpotifyImportHttp {
            override fun token(form:String)="""{"access_token":"a","expires_in":3600}"""
            override fun favorites(accessToken:String,offset:Int)=page(0,1,false)
            override fun search(accessToken:String,query:String):String {
                queries+=query
                return """{"tracks":{"items":[${trackObject(7)}]}}"""
            }
        }
        connected(http) { session ->
            session.setSearchQuery("  Song 7 ")
            session.search()
            await { !session.state.value.busy && session.state.value.searchResults.isNotEmpty() }
            assertEquals(listOf("Song 7"),queries.toList())
            assertEquals("Song 7",session.state.value.searchResults.single().title)
            assertEquals("1曲見つかりました",session.state.value.searchMessage)
            session.setSearchQuery("other")
            assertTrue(session.state.value.searchResults.isEmpty())
            assertEquals("",session.state.value.searchMessage)
        }
    }
}
