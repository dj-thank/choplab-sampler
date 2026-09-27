package com.choplab.desktop.next

import com.choplab.desktop.provider.*
import com.choplab.sampler.source.SourceTrack
import com.choplab.ui.resources.*
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.net.URI
import javax.swing.*
import kotlin.coroutines.resume

/** Owns its window/timer; the host owns the memory-only account session. */
internal object NextSpotifyDialog {
    suspend fun show(parent: Window?, session: SpotifyDesktopSession) {
        val resources = listOf(Res.string.ce_spotify, Res.string.ce_spotify_client,
            Res.string.ce_spotify_configure, Res.string.ce_spotify_login, Res.string.ce_spotify_disconnect,
            Res.string.ce_spotify_favorites, Res.string.ce_spotify_more, Res.string.ce_spotify_search,
            Res.string.ce_spotify_open, Res.string.ce_spotify_unconfigured, Res.string.ce_spotify_ready,
            Res.string.ce_spotify_authenticating, Res.string.ce_spotify_connected, Res.string.ce_spotify_busy,
            Res.string.ce_spotify_boundary, Res.string.ce_spotify_invalid_client, Res.string.ce_spotify_retry,
            Res.string.ce_spotify_network, Res.string.ce_spotify_browser, Res.string.ce_spotify_port,
            Res.string.ce_spotify_denied, Res.string.ce_spotify_timeout, Res.string.ce_spotify_expired,
            Res.string.ce_spotify_forbidden, Res.string.ce_spotify_rate, Res.string.ce_spotify_api,
            Res.string.ce_library_cancel, Res.string.ce_library_close)
        val labels = resources.associateWith { getString(it) }
        fun text(key: StringResource) = labels.getValue(key)
        suspendCancellableCoroutine<Unit> { answer -> SwingUtilities.invokeLater {
            if (!answer.isActive) return@invokeLater
            val dialog = JDialog(parent, text(Res.string.ce_spotify), Dialog.ModalityType.DOCUMENT_MODAL)
            val client = JTextField().apply { name = "next-spotify-client"; accessibleContext.accessibleName = text(Res.string.ce_spotify_client) }
            val query = JTextField().apply { name = "next-spotify-query"; accessibleContext.accessibleName = text(Res.string.ce_spotify_search) }
            fun button(key: StringResource, id: String) = JButton(text(key)).apply {
                name = "next-spotify-$id"; preferredSize = Dimension(preferredSize.width.coerceAtLeast(110), 48)
            }
            val configure = button(Res.string.ce_spotify_configure, "configure")
            val login = button(Res.string.ce_spotify_login, "login")
            val cancel = button(Res.string.ce_library_cancel, "cancel")
            val disconnect = button(Res.string.ce_spotify_disconnect, "disconnect")
            val favorites = button(Res.string.ce_spotify_favorites, "favorites")
            val more = button(Res.string.ce_spotify_more, "more")
            val search = button(Res.string.ce_spotify_search, "search")
            val open = button(Res.string.ce_spotify_open, "open")
            val close = button(Res.string.ce_library_close, "close")
            val model = DefaultListModel<SourceTrack>()
            val list = JList(model).apply {
                name = "next-spotify-tracks"; fixedCellHeight = 48
                selectionMode = ListSelectionModel.SINGLE_SELECTION
                cellRenderer = object : DefaultListCellRenderer() {
                    override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component {
                        val track = value as SourceTrack
                        return super.getListCellRendererComponent(list, "${track.artist} — ${track.title}", index, selected, focus)
                    }
                }
            }
            val status = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true; rows = 3 }
            var showingSearch = false
            var invalidClient = false
            var shown = emptyList<SourceTrack>()
            var finished = false
            fun refresh() {
                val state = session.state.value
                val tracks = if (showingSearch) state.searchResults else state.sourceTracks
                if (tracks != shown) { shown = tracks; model.clear(); tracks.forEach(model::addElement) }
                configure.isEnabled = state.canConfigureClientId; client.isEnabled = state.canConfigureClientId
                login.isEnabled = state.canLogin; cancel.isEnabled = state.canCancelLogin
                disconnect.isEnabled = state.canDisconnect
                favorites.isEnabled = state.canUsePlaybackControls; search.isEnabled = state.canUsePlaybackControls
                more.isEnabled = !showingSearch && state.canUsePlaybackControls && state.sourceHasMore
                open.isEnabled = list.selectedValue != null
                val problem = state.problem
                val key = if (invalidClient) Res.string.ce_spotify_invalid_client else if (problem != null) when (problem.kind) {
                    SpotifyProblemKind.CANCELLED, SpotifyProblemKind.DENIED -> Res.string.ce_spotify_denied
                    SpotifyProblemKind.TIMEOUT -> Res.string.ce_spotify_timeout
                    SpotifyProblemKind.BROWSER_UNAVAILABLE -> Res.string.ce_spotify_browser
                    SpotifyProblemKind.CALLBACK_PORT_UNAVAILABLE -> Res.string.ce_spotify_port
                    SpotifyProblemKind.NETWORK -> Res.string.ce_spotify_network
                    SpotifyProblemKind.AUTH_EXPIRED -> Res.string.ce_spotify_expired
                    SpotifyProblemKind.API_FAILED -> when (problem.statusCode) {
                        403 -> Res.string.ce_spotify_forbidden
                        429 -> Res.string.ce_spotify_rate
                        else -> Res.string.ce_spotify_api
                    }
                    else -> Res.string.ce_spotify_retry
                } else when (state.phase) {
                    SpotifyConnectionPhase.UNCONFIGURED -> Res.string.ce_spotify_unconfigured
                    SpotifyConnectionPhase.READY -> Res.string.ce_spotify_ready
                    SpotifyConnectionPhase.AUTHENTICATING -> Res.string.ce_spotify_authenticating
                    SpotifyConnectionPhase.CONNECTED -> if (state.busy) Res.string.ce_spotify_busy else Res.string.ce_spotify_connected
                    SpotifyConnectionPhase.ERROR -> Res.string.ce_spotify_retry
                }
                status.text = text(key)
            }
            val timer = Timer(100) { refresh() }
            fun finish() {
                if (finished) return
                finished = true; timer.stop(); session.cancelLogin(); dialog.dispose()
                if (answer.isActive) answer.resume(Unit)
            }
            configure.addActionListener { invalidClient = !session.configureClientId(client.text); client.text = ""; refresh() }
            login.addActionListener { invalidClient = false; session.login(); refresh() }
            cancel.addActionListener { session.cancelLogin(); refresh() }
            disconnect.addActionListener { session.disconnect(); refresh() }
            favorites.addActionListener { showingSearch = false; session.showLibrary(); refresh() }
            more.addActionListener { session.showMoreLibrary(); refresh() }
            search.addActionListener { showingSearch = true; session.setSearchQuery(query.text); session.searchForImport(); refresh() }
            open.addActionListener {
                val uri = runCatching { URI(list.selectedValue?.spotifyUrl.orEmpty()) }.getOrNull()
                if (uri?.scheme == "https" && uri.host == "open.spotify.com" && uri.userInfo == null && uri.port == -1) {
                    runCatching { Desktop.getDesktop().browse(uri) }.onFailure { status.text = text(Res.string.ce_spotify_browser) }
                }
            }
            close.addActionListener { finish() }
            list.addListSelectionListener { refresh() }
            dialog.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            dialog.addWindowListener(object : WindowAdapter() { override fun windowClosing(e: WindowEvent) = finish() })
            answer.invokeOnCancellation { SwingUtilities.invokeLater { finish() } }
            fun row(vararg components: Component) = JPanel(GridLayout(1, components.size, 8, 8)).apply { components.forEach { add(it) } }
            dialog.contentPane = JPanel(BorderLayout(8, 8)).apply {
                border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
                add(JPanel(GridLayout(3, 1, 8, 8)).apply {
                    add(row(JLabel(text(Res.string.ce_spotify_client)), client, configure))
                    add(row(login, cancel, disconnect, favorites, more))
                    add(row(query, search))
                }, BorderLayout.NORTH)
                add(JScrollPane(list), BorderLayout.CENTER)
                add(JPanel(BorderLayout(8, 8)).apply {
                    add(status, BorderLayout.NORTH)
                    add(JLabel(text(Res.string.ce_spotify_boundary)), BorderLayout.CENTER)
                    add(row(open, close), BorderLayout.SOUTH)
                }, BorderLayout.SOUTH)
            }
            dialog.minimumSize = Dimension(820, 520); dialog.setSize(900, 600); dialog.setLocationRelativeTo(parent)
            refresh(); timer.start(); dialog.isVisible = true
        } }
    }
}
