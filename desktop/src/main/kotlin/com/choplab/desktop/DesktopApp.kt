package com.choplab.desktop

import com.choplab.desktop.audio.JavaSoundWavPlayer
import com.choplab.desktop.model.DesktopPadModel
import com.choplab.desktop.spotify.SpotifyApi
import com.choplab.desktop.spotify.SpotifyLoopbackCallbackServer
import com.choplab.desktop.spotify.JdkSpotifyTokenClient
import com.choplab.desktop.spotify.SpotifyTokens
import com.choplab.desktop.spotify.newSpotifyAuthorizationAttempt
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.io.File
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

private val BACKGROUND = Color(30, 30, 30)
private val SURFACE = Color(48, 48, 48)
private val PAD_COLORS = listOf(Color(226, 120, 65), Color(111, 164, 91), Color(76, 126, 171), Color(173, 105, 157))

fun main() {
    SwingUtilities.invokeLater { DesktopApp().show() }
}

private class DesktopApp {
    private val frame = JFrame("ChopLab — Windows Desktop")
    private val pads = DesktopPadModel()
    private val player = JavaSoundWavPlayer()
    private val spotifyApi = SpotifyApi()
    private val tokenClient = JdkSpotifyTokenClient()
    private var spotifyTokens: SpotifyTokens? = null
    private var selectedFile: File? = null
    private val status = JLabel("ローカルWAVを開いて、PADへ割り当ててください")
    private val padButtons = ArrayList<JButton>(pads.padCount)

    fun show() {
        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        frame.minimumSize = Dimension(860, 620)
        frame.contentPane = buildContent()
        frame.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosed(event: java.awt.event.WindowEvent?) {
                player.close()
            }
        })
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }

    private fun buildContent(): JPanel {
        val root = JPanel(BorderLayout(16, 16)).apply {
            background = BACKGROUND
            border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        }
        root.add(buildHeader(), BorderLayout.NORTH)
        root.add(buildPadSurface(), BorderLayout.CENTER)
        root.add(buildStatusBar(), BorderLayout.SOUTH)
        return root
    }

    private fun buildHeader(): JPanel = JPanel(BorderLayout(12, 12)).apply {
        background = BACKGROUND
        val title = JLabel("ChopLab  /  おとひろい PC")
        title.foreground = Color.WHITE
        title.font = title.font.deriveFont(22f)
        add(title, BorderLayout.WEST)

        val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { background = BACKGROUND }
        actions.add(button("OPEN WAV") { chooseWav() })
        actions.add(button("PLAY") { runAudioAction { player.play() } })
        actions.add(button("STOP") { player.stop() })
        actions.add(button("SPOTIFY LOGIN") { loginToSpotify() })
        actions.add(button("SPOTIFY CURRENT") { readCurrentSpotifyPlayback() })
        add(actions, BorderLayout.EAST)
    }

    private fun buildPadSurface(): JPanel = JPanel(GridLayout(4, 4, 12, 12)).apply {
        background = BACKGROUND
        border = BorderFactory.createEmptyBorder(24, 80, 24, 80)
        repeat(pads.padCount) { slot ->
            val button = JButton(padLabel(slot)).apply {
                foreground = Color.WHITE
                background = PAD_COLORS[slot / 4]
                font = font.deriveFont(16f)
                isFocusPainted = false
                addActionListener { pressPad(slot) }
            }
            padButtons += button
            add(button)
        }
    }

    private fun buildStatusBar(): JPanel = JPanel(BorderLayout(8, 8)).apply {
        background = SURFACE
        border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
        status.foreground = Color.WHITE
        status.horizontalAlignment = SwingConstants.LEFT
        add(status, BorderLayout.CENTER)
        add(JTextField("Spotify連携はセッション内のみ", 24).apply { isEditable = false }, BorderLayout.EAST)
    }

    private fun chooseWav() {
        val chooser = JFileChooser().apply {
            dialogTitle = "ChopLabで開くWAV"
            fileFilter = FileNameExtensionFilter("WAV audio (*.wav)", "wav")
        }
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            val file = chooser.selectedFile
            runAudioAction {
                player.load(file)
                selectedFile = file
                status.text = "読み込み済み: ${file.name} — 空のPADを押すと割り当てます"
            }
        }
    }

    private fun pressPad(slot: Int) {
        val assigned = pads.fileFor(slot)
        if (assigned == null) {
            val file = selectedFile
            if (file == null) {
                status.text = "先に OPEN WAV でローカル音源を選択してください"
                return
            }
            pads.assign(slot, file.path)
            refreshPad(slot)
            status.text = "PAD ${slot + 1} に ${file.name} を割り当てました"
        }
        val fileToPlay = pads.fileFor(slot)?.let(::File)
        if (fileToPlay != null) {
            runAudioAction {
                if (player.loadedFile?.canonicalFile != fileToPlay.canonicalFile) player.load(fileToPlay)
                player.play()
                status.text = "PAD ${slot + 1} を再生中: ${fileToPlay.name}"
            }
        }
    }

    private fun refreshPad(slot: Int) {
        padButtons[slot].text = padLabel(slot)
    }

    private fun padLabel(slot: Int): String =
        "PAD ${String.format("%02d", slot + 1)}\n${pads.fileFor(slot)?.let(::File)?.name ?: "EMPTY"}"

    private fun loginToSpotify() {
        val clientId = System.getenv("CHOPLAB_SPOTIFY_CLIENT_ID")
        if (clientId.isNullOrBlank()) {
            showMessage("開発用のSpotify Client IDがありません。\nCHOPLAB_SPOTIFY_CLIENT_IDに設定してから再試行してください。")
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
                val tokens = tokenClient.exchangeCode(clientId, result.code, callback.redirectUri, attempt.verifier)
                spotifyTokens = tokens
                SwingUtilities.invokeLater {
                    status.text = "Spotifyに接続しました（この試作ではtokenはメモリ内のみ）"
                }
            } catch (error: Throwable) {
                SwingUtilities.invokeLater { status.text = "Spotify接続失敗: ${error.message ?: error.javaClass.simpleName}" }
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
            status.text = "先に SPOTIFY LOGIN を実行してください"
            return
        }
        Thread {
            try {
                val response = spotifyApi.currentPlayback(tokens.accessToken)
                SwingUtilities.invokeLater {
                    status.text = "Spotify現在再生情報: HTTP ${response.statusCode} / ${response.body.length} bytes"
                }
            } catch (error: Throwable) {
                SwingUtilities.invokeLater {
                    status.text = "Spotify現在再生情報の取得失敗: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }.apply {
            name = "choplab-spotify-current-playback"
            isDaemon = true
            start()
        }
    }

    private fun runAudioAction(action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            status.text = "音声操作失敗: ${error.message ?: error.javaClass.simpleName}"
        }
    }

    private fun button(text: String, action: () -> Unit): JButton = JButton(text).apply {
        addActionListener { action() }
        isFocusable = false
    }

    private fun showMessage(message: String) {
        JOptionPane.showMessageDialog(frame, message, "ChopLab", JOptionPane.INFORMATION_MESSAGE)
    }
}
