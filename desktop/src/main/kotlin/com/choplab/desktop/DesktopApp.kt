package com.choplab.desktop

import com.choplab.desktop.audio.JavaSoundWavPlayer
import com.choplab.desktop.spotify.JdkSpotifyTokenClient
import com.choplab.desktop.spotify.SpotifyApi
import com.choplab.desktop.spotify.SpotifyLoopbackCallbackServer
import com.choplab.desktop.spotify.SpotifyTokens
import com.choplab.desktop.spotify.newSpotifyAuthorizationAttempt
import com.choplab.desktop.ui.DesktopDeckPanel
import java.awt.Desktop
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

fun main() {
    SwingUtilities.invokeLater { DesktopApp().show() }
}

private class DesktopApp {
    private val frame = JFrame("ChopLab — おとひろい PC")
    private val player = JavaSoundWavPlayer()
    private val spotifyApi = SpotifyApi()
    private val tokenClient = JdkSpotifyTokenClient()
    private var spotifyTokens: SpotifyTokens? = null
    private lateinit var deck: DesktopDeckPanel

    fun show() {
        deck = DesktopDeckPanel(
            player = player,
            onLoadWav = ::chooseWav,
            onSpotifyLogin = ::loginToSpotify,
            onSpotifyCurrent = ::readCurrentSpotifyPlayback,
        )
        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        val scale = GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .defaultScreenDevice
            .defaultConfiguration
            .defaultTransform
            .scaleX
            .coerceAtLeast(1.0)
        frame.minimumSize = Dimension((1180 / scale).toInt(), (820 / scale).toInt())
        frame.contentPane = deck
        frame.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosed(event: java.awt.event.WindowEvent?) {
                deck.close()
                player.close()
            }
        })
        frame.size = Dimension((1440 / scale).toInt(), (1180 / scale).toInt())
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }

    private fun chooseWav() {
        val chooser = JFileChooser().apply {
            dialogTitle = "ChopLabで開くWAV"
            fileFilter = FileNameExtensionFilter("WAV audio (*.wav)", "wav")
        }
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            deck.loadLocalWav(chooser.selectedFile)
        }
    }

    private fun loginToSpotify() {
        val clientId = System.getenv("CHOPLAB_SPOTIFY_CLIENT_ID")
        if (clientId.isNullOrBlank()) {
            deck.showExternalStatus("Spotify接続失敗: CHOPLAB_SPOTIFY_CLIENT_ID が未設定です")
            return
        }

        Thread {
            var callback: SpotifyLoopbackCallbackServer? = null
            try {
                callback = SpotifyLoopbackCallbackServer()
                val attempt = newSpotifyAuthorizationAttempt(
                    clientId = clientId,
                    redirectUri = callback.redirectUri,
                    scopes = listOf(
                        "user-read-currently-playing",
                        "user-read-playback-state",
                        "playlist-read-private",
                        "user-modify-playback-state",
                    ),
                )
                check(Desktop.isDesktopSupported()) { "この環境では既定ブラウザを開けません" }
                Desktop.getDesktop().browse(attempt.request.toUri())
                val result = callback.await(attempt.state)
                spotifyTokens = tokenClient.exchangeCode(
                    clientId = clientId,
                    code = result.code,
                    redirectUri = callback.redirectUri,
                    verifier = attempt.verifier,
                )
                SwingUtilities.invokeLater {
                    deck.showExternalStatus("Spotify CONNECTED — tokenはこのセッションのメモリ内だけで保持")
                }
            } catch (error: Throwable) {
                SwingUtilities.invokeLater {
                    deck.showExternalStatus("Spotify接続失敗: ${error.message ?: error.javaClass.simpleName}")
                }
            } finally {
                callback?.close()
            }
        }.apply {
            name = "choplab-spotify-oauth"
            isDaemon = true
            start()
        }
    }

    private fun readCurrentSpotifyPlayback() {
        val tokens = spotifyTokens
        if (tokens == null) {
            deck.showExternalStatus("先に SPOTIFY LOGIN を実行してください")
            return
        }
        Thread {
            try {
                val response = spotifyApi.currentPlayback(tokens.accessToken)
                SwingUtilities.invokeLater {
                    deck.showExternalStatus("Spotify CURRENT — HTTP ${response.statusCode} / ${response.body.length} bytes")
                }
            } catch (error: Throwable) {
                SwingUtilities.invokeLater {
                    deck.showExternalStatus("Spotify現在再生情報の取得失敗: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }.apply {
            name = "choplab-spotify-current-playback"
            isDaemon = true
            start()
        }
    }
}
