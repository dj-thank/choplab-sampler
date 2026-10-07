@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.choplab.desktop.next

import com.choplab.desktop.provider.*
import com.choplab.sampler.source.SourceTrack
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.choplab.ui.resources.*
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.net.URI
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.coroutines.resume

/** Owns its window/timer; the host owns the memory-only account session. */
internal object NextSpotifyDialog {
    suspend fun show(parent: Window?, session: SpotifyDesktopSession, onRendered: () -> Unit = {}) {
        val labels = NextSpotifyMetadataController.resources.associateWith { getString(it) }
        fun text(key: StringResource, vararg values: Any) = labels.getValue(key).let {
            if (values.isEmpty()) it else String.format(it, *values)
        }
        suspendCancellableCoroutine<Unit> { answer -> SwingUtilities.invokeLater {
            if (!answer.isActive) return@invokeLater
            val controller = NextSpotifyMetadataController(session)
            val browser = SpotifyCatalogBrowser(session)
            val dialog = JDialog(parent, text(Res.string.ce_spotify_metadata), Dialog.ModalityType.DOCUMENT_MODAL)
            val client = JTextField().apply { name = "next-spotify-client"; accessibleContext.accessibleName = text(Res.string.ce_spotify_client) }
            fun button(key: StringResource, id: String) = JButton(text(key)).apply {
                name = "next-spotify-$id"; preferredSize = Dimension(preferredSize.width.coerceAtLeast(110), 48)
            }
            val configure = button(Res.string.ce_spotify_configure, "configure")
            val login = button(Res.string.ce_spotify_login, "login")
            val cancel = button(Res.string.ce_library_cancel, "cancel")
            val disconnect = button(Res.string.ce_spotify_disconnect, "disconnect")
            val close = button(Res.string.ce_library_close, "close")
            fun wrapped(value: String) = JTextArea(value).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false }
            val status = wrapped("").apply { rows = 2; name = "next-spotify-status" }
            val content = androidx.compose.ui.awt.ComposePanel().apply {
                name = "next-spotify-catalog"
                setContent {
                    NextDisplayEnvironment {
                    val state by session.state.collectAsState()
                    androidx.compose.runtime.SideEffect(onRendered)
                    com.choplab.sampler.ui.theme.ChopLabTheme {
                        com.choplab.desktop.SpotifyCatalogPanel(state, browser, onOpenSpotify = { track ->
                            controller.openCatalogTrack(track, browser.page?.entries.orEmpty().mapNotNull { it.track })
                        })
                    }
                    }
                }
            }
            var finished = false
            fun refresh() {
                if (finished) return
                val view = controller.view()
                configure.isEnabled = view.canConfigure; client.isEnabled = view.canConfigure
                login.isEnabled = view.canLogin; cancel.isEnabled = view.canCancel
                disconnect.isEnabled = view.canDisconnect
                status.text = text(view.status, *view.statusArguments.toTypedArray())
            }
            val timer = Timer(100) { refresh() }
            fun finish() {
                if (finished) return
                finished = true; timer.stop(); browser.close(); controller.close()
                client.text = ""; content.dispose(); dialog.dispose()
                if (answer.isActive) answer.resume(Unit)
            }
            configure.addActionListener { controller.configure(client.text); client.text = ""; browser.home(); refresh() }
            login.addActionListener { controller.login(); refresh() }
            cancel.addActionListener { controller.cancel(); refresh() }
            disconnect.addActionListener { controller.disconnect(); browser.home(); refresh() }
            close.addActionListener { finish() }
            dialog.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            dialog.addWindowListener(object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent) = finish()
                override fun windowClosed(e: WindowEvent) = finish()
            })
            answer.invokeOnCancellation { SwingUtilities.invokeLater { finish() } }
            fun row(vararg components: Component) = JPanel(GridLayout(1, components.size, 8, 8)).apply { components.forEach { add(it) } }
            dialog.contentPane = JPanel(BorderLayout(8, 8)).apply {
                border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
                add(JPanel(GridLayout(3, 1, 4, 4)).apply {
                    add(row(JLabel(text(Res.string.ce_spotify_client)), client, configure))
                    add(wrapped(text(Res.string.ce_spotify_redirect, "http://127.0.0.1/callback")))
                    add(row(login, cancel, disconnect))
                }, BorderLayout.NORTH)
                add(content, BorderLayout.CENTER)
                add(JPanel(BorderLayout(8, 8)).apply {
                    add(status, BorderLayout.NORTH)
                    add(wrapped(text(Res.string.ce_spotify_boundary)).apply { rows = 2 }, BorderLayout.CENTER)
                    add(row(close), BorderLayout.SOUTH)
                }, BorderLayout.SOUTH)
            }
            configureNextDialog(dialog, Dimension(960, 820), Dimension(760, 680)) { finish() }
            refresh(); timer.start()
            try { dialog.isVisible = true } finally { finish() }
        } }
    }
}

internal data class NextSpotifyMetadataView(
    val tracks: List<SourceTrack>, val status: StringResource, val statusArguments: List<Any>,
    val canConfigure: Boolean, val canLogin: Boolean, val canCancel: Boolean,
    val canDisconnect: Boolean, val canFetch: Boolean, val canSearch: Boolean, val canMore: Boolean,
)

/** The native window and tests share this state/action path; it has no editor or audio port. */
internal class NextSpotifyMetadataController(
    private val session: SpotifyDesktopSession,
    private val browser: SpotifyBrowser = DesktopSpotifyBrowser,
) : AutoCloseable {
    private var showingSearch = false
    private var invalidClient = false
    private var browserFailed = false
    private var closed = false

    init { require(session.purpose == SpotifySessionPurpose.METADATA_ONLY) }

    fun configure(value: String) { if (!closed) { browserFailed = false; invalidClient = !session.configureClientId(value) } }
    fun login() { if (!closed) { invalidClient = false; browserFailed = false; session.login() } }
    fun cancel() { if (!closed) { browserFailed = false; session.cancelPendingOperations() } }
    fun disconnect() { if (!closed) { browserFailed = false; invalidClient = false; session.disconnect() } }
    fun setQuery(value: String) { if (!closed) { browserFailed = false; session.setSearchQuery(value) } }
    fun favorites() { if (!closed) { showingSearch = false; browserFailed = false; session.showLibrary() } }
    fun more() { if (!closed) { browserFailed = false; session.showMoreLibrary() } }
    fun search() { if (!closed) { showingSearch = true; browserFailed = false; session.searchForImport() } }

    fun open(track: SourceTrack?) {
        if (closed || track == null || track !in view().tracks) return
        val uri = runCatching { URI(track.spotifyUrl) }.getOrNull() ?: return
        if (uri.scheme != "https" || uri.host != "open.spotify.com" || uri.userInfo != null || uri.port != -1 ||
            uri.query != null || uri.fragment != null || !Regex("/track/[A-Za-z0-9]{22}").matches(uri.path)) return
        browserFailed = runCatching { browser.open(uri) }.isFailure
    }

    fun openCatalogTrack(track: SourceTrack, allowed: List<SourceTrack>) {
        if (closed || !session.connected || track !in allowed) return
        val uri = runCatching { URI(track.spotifyUrl) }.getOrNull() ?: return
        if (uri.scheme != "https" || uri.host != "open.spotify.com" || uri.userInfo != null || uri.port != -1 ||
            uri.query != null || uri.fragment != null || !Regex("/track/[A-Za-z0-9]{22}").matches(uri.path)) return
        browserFailed = runCatching { browser.open(uri) }.isFailure
    }

    fun view(): NextSpotifyMetadataView {
        val state = session.state.value
        val tracks = if (closed) emptyList() else if (showingSearch) state.searchResults else state.sourceTracks
        val wait = session.retryWaitSeconds()
        val problem = state.problem
        val args = mutableListOf<Any>()
        val key = when {
            invalidClient -> Res.string.ce_spotify_invalid_client
            browserFailed -> Res.string.ce_spotify_browser
            state.busy -> if (state.phase == SpotifyConnectionPhase.AUTHENTICATING) Res.string.ce_spotify_authenticating else Res.string.ce_spotify_busy
            wait > 0 -> Res.string.ce_spotify_retry_after.also { args.add(wait) }
            problem != null -> when (problem.kind) {
                SpotifyProblemKind.CANCELLED -> Res.string.ce_cancelled
                SpotifyProblemKind.DENIED -> Res.string.ce_spotify_denied
                SpotifyProblemKind.TIMEOUT -> Res.string.ce_spotify_timeout
                SpotifyProblemKind.BROWSER_UNAVAILABLE -> Res.string.ce_spotify_browser
                SpotifyProblemKind.CALLBACK_PORT_UNAVAILABLE -> Res.string.ce_spotify_port
                SpotifyProblemKind.NETWORK -> Res.string.ce_spotify_network
                SpotifyProblemKind.AUTH_EXPIRED -> Res.string.ce_spotify_expired
                SpotifyProblemKind.INVALID_RESPONSE -> Res.string.ce_spotify_api
                SpotifyProblemKind.API_FAILED -> when (problem.statusCode) {
                    403 -> Res.string.ce_spotify_forbidden
                    429 -> Res.string.ce_spotify_rate
                    else -> Res.string.ce_spotify_api
                }
                else -> Res.string.ce_spotify_retry
            }
            state.phase == SpotifyConnectionPhase.CONNECTED && tracks.isEmpty() &&
                (if (showingSearch) state.searchCompletedQuery == state.searchQuery.trim() else state.libraryLoaded) -> Res.string.ce_spotify_empty
            else -> when (state.phase) {
                SpotifyConnectionPhase.UNCONFIGURED -> Res.string.ce_spotify_unconfigured
                SpotifyConnectionPhase.READY -> Res.string.ce_spotify_ready
                SpotifyConnectionPhase.AUTHENTICATING -> Res.string.ce_spotify_authenticating
                SpotifyConnectionPhase.CONNECTED -> Res.string.ce_spotify_connected
                SpotifyConnectionPhase.ERROR -> Res.string.ce_spotify_retry
            }
        }
        val fetch = !closed && !state.busy && state.phase == SpotifyConnectionPhase.CONNECTED && wait == 0L
        return NextSpotifyMetadataView(tracks, key, args,
            !closed && state.canConfigureClientId, !closed && state.canLogin,
            !closed && state.busy, !closed && state.canDisconnect, fetch,
            fetch && state.searchQuery.isNotBlank(), fetch && !showingSearch && state.sourceHasMore)
    }

    override fun close() { if (!closed) { closed = true; session.cancelPendingOperations() } }

    companion object {
        val resources = listOf(Res.string.ce_spotify_metadata, Res.string.ce_spotify_client,
            Res.string.ce_spotify_configure, Res.string.ce_spotify_login, Res.string.ce_spotify_disconnect,
            Res.string.ce_spotify_favorites, Res.string.ce_spotify_more, Res.string.ce_spotify_search,
            Res.string.ce_spotify_open, Res.string.ce_spotify_unconfigured, Res.string.ce_spotify_ready,
            Res.string.ce_spotify_authenticating, Res.string.ce_spotify_connected, Res.string.ce_spotify_busy,
            Res.string.ce_spotify_boundary, Res.string.ce_spotify_invalid_client, Res.string.ce_spotify_retry,
            Res.string.ce_spotify_network, Res.string.ce_spotify_browser, Res.string.ce_spotify_port,
            Res.string.ce_spotify_denied, Res.string.ce_spotify_timeout, Res.string.ce_spotify_expired,
            Res.string.ce_spotify_forbidden, Res.string.ce_spotify_rate, Res.string.ce_spotify_api,
            Res.string.ce_spotify_retry_after, Res.string.ce_spotify_empty, Res.string.ce_spotify_redirect,
            Res.string.ce_cancelled, Res.string.ce_library_cancel, Res.string.ce_library_close)
    }
}
