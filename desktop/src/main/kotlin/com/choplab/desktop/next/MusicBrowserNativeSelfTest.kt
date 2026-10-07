package com.choplab.desktop.next

import com.choplab.desktop.provider.*
import com.choplab.desktop.spotify.*
import java.awt.Window
import java.awt.event.WindowEvent
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JDialog
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlinx.coroutines.*

/** Only this test JVM's owned windows, synthetic account and isolated files; no desktop automation or audio. */
object MusicBrowserNativeSelfTest {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 1)
        val directory = Path.of(args.single())
        val composed = AtomicInteger()
        suspend fun window(show: suspend () -> Unit) {
            val before = composed.get()
            val deadline = System.nanoTime() + 15_000_000_000L
            val timedOut = java.util.concurrent.atomic.AtomicBoolean()
            val timer = Timer(50) {
                val owned = Window.getWindows().filterIsInstance<JDialog>().filter { it.isShowing }
                if (composed.get() > before || System.nanoTime() > deadline) {
                    if (composed.get() <= before) timedOut.set(true)
                    owned.forEach { it.dispatchEvent(WindowEvent(it, WindowEvent.WINDOW_CLOSING)) }
                }
            }
            SwingUtilities.invokeAndWait { timer.start() }
            try { withTimeout(20_000) { show() } }
            finally { SwingUtilities.invokeAndWait { timer.stop() } }
            check(!timedOut.get() && composed.get() > before)
            check(Window.getWindows().none { it.isShowing })
        }
        window { check(NextLibraryDialog.choose(null, directory.resolve("library"), {}, composed::incrementAndGet) == null) }
        val calls = AtomicInteger()
        val callback = object : SpotifyAuthorizationCallback {
            override val redirectUri = URI("http://127.0.0.1:8877/callback")
            var state = ""
            override fun expectState(state: String) { this.state = state }
            override fun await(timeout: Duration) = SpotifyCallbackResult("synthetic-code", state)
            override fun cancel() = Unit
            override fun close() = Unit
        }
        val api = SpotifyApi(SpotifyApiTransport { calls.incrementAndGet(); error("Opening the window must not fetch anything") })
        SpotifyDesktopSession({}, "0123456789abcdef0123456789abcdef", api = api,
            callbackFactory = SpotifyAuthorizationCallbackFactory { callback }, browser = SpotifyBrowser {},
            purpose = SpotifySessionPurpose.METADATA_ONLY, tokenClient = object : SpotifyTokenClient {
                override fun exchangeCode(clientId: String, code: String, redirectUri: URI, verifier: String) =
                    SpotifyTokens("synthetic-token", "Bearer", 3600, null, "user-library-read")
                override fun refresh(clientId: String, refreshToken: String): SpotifyTokens = error("Unused")
            }).use { session ->
            session.login()
            withTimeout(5_000) { while (session.state.value.busy) delay(5) }
            check(session.connected)
            window { NextSpotifyDialog.show(null, session, composed::incrementAndGet) }
            check(session.connected && !session.state.value.busy && calls.get() == 0)
        }
        println("{\"status\":\"LOCAL_PASS\",\"scope\":\"owned-native-music-browsers\",\"windows\":2,\"initialRequests\":0,\"audioStarted\":false}")
    }
}
