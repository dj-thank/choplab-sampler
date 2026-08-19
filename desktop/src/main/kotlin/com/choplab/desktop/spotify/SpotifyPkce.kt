package com.choplab.desktop.spotify

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

data class SpotifyAuthorizationRequest(
    val clientId: String,
    val redirectUri: URI,
    val scopes: List<String>,
    val state: String,
    val codeChallenge: String,
) {
    init {
        require(clientId.isNotBlank()) { "Spotify client id must not be blank" }
        require(redirectUri.scheme == "http" && redirectUri.host == "127.0.0.1") {
            "Desktop Spotify callbacks must use an explicit 127.0.0.1 loopback URI"
        }
        require(state.isNotBlank()) { "OAuth state must not be blank" }
        require(codeChallenge.isNotBlank()) { "PKCE code challenge must not be blank" }
    }

    fun toUri(): URI {
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri.toString(),
            "scope" to scopes.joinToString(" "),
            "state" to state,
            "code_challenge_method" to "S256",
            "code_challenge" to codeChallenge,
        )
        val query = params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return URI("https://accounts.spotify.com/authorize?$query")
    }
}

data class SpotifyAuthorizationAttempt(
    val verifier: String,
    val state: String,
    val request: SpotifyAuthorizationRequest,
)

object Pkce {
    private const val VERIFIER_LENGTH = 64
    private val random = SecureRandom()
    private val verifierAlphabet =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~".toCharArray()

    fun newVerifier(length: Int = VERIFIER_LENGTH): String {
        require(length in 43..128) { "PKCE verifier length must be between 43 and 128" }
        return buildString(length) {
            repeat(length) { append(verifierAlphabet[random.nextInt(verifierAlphabet.size)]) }
        }
    }

    fun newState(): String = newVerifier(48)

    fun codeChallenge(verifier: String): String {
        require(verifier.length in 43..128) { "PKCE verifier length must be between 43 and 128" }
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}

fun newSpotifyAuthorizationAttempt(
    clientId: String,
    redirectUri: URI,
    scopes: List<String>,
): SpotifyAuthorizationAttempt {
    val verifier = Pkce.newVerifier()
    val state = Pkce.newState()
    return SpotifyAuthorizationAttempt(
        verifier = verifier,
        state = state,
        request = SpotifyAuthorizationRequest(
            clientId = clientId,
            redirectUri = redirectUri,
            scopes = scopes,
            state = state,
            codeChallenge = Pkce.codeChallenge(verifier),
        ),
    )
}

internal fun encode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
