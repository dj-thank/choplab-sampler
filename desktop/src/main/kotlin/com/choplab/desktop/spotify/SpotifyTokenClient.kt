package com.choplab.desktop.spotify

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

data class SpotifyTokens(
    val accessToken: String,
    val tokenType: String,
    val expiresInSeconds: Long,
    val refreshToken: String?,
    val scope: String,
)

class SpotifyTokenRequestException(
    val statusCode: Int,
) : IllegalStateException("Spotify token request failed with HTTP $statusCode")

object SpotifyTokenForm {
    fun authorizationCode(clientId: String, code: String, redirectUri: URI, verifier: String): String = form(
        linkedMapOf(
            "client_id" to clientId,
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri.toString(),
            "code_verifier" to verifier,
        ),
    )

    fun refresh(clientId: String, refreshToken: String): String = form(
        linkedMapOf(
            "client_id" to clientId,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
        ),
    )

    private fun form(values: Map<String, String>): String = values.entries.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
    }
}

interface SpotifyTokenClient {
    fun exchangeCode(clientId: String, code: String, redirectUri: URI, verifier: String): SpotifyTokens
    fun refresh(clientId: String, refreshToken: String): SpotifyTokens
}

class JdkSpotifyTokenClient(
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build(),
) : SpotifyTokenClient {
    override fun exchangeCode(clientId: String, code: String, redirectUri: URI, verifier: String): SpotifyTokens =
        post(SpotifyTokenForm.authorizationCode(clientId, code, redirectUri, verifier))

    override fun refresh(clientId: String, refreshToken: String): SpotifyTokens =
        post(SpotifyTokenForm.refresh(clientId, refreshToken))

    private fun post(form: String): SpotifyTokens {
        val request = HttpRequest.newBuilder(URI("https://accounts.spotify.com/api/token"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) throw SpotifyTokenRequestException(response.statusCode())
        return SpotifyTokensJson.parse(response.body())
    }
}

internal object SpotifyTokensJson {
    fun parse(body: String): SpotifyTokens = SpotifyTokens(
        accessToken = requiredString(body, "access_token"),
        tokenType = requiredString(body, "token_type"),
        expiresInSeconds = requiredLong(body, "expires_in"),
        refreshToken = optionalString(body, "refresh_token"),
        scope = optionalString(body, "scope").orEmpty(),
    )

    private fun requiredString(body: String, key: String): String =
        optionalString(body, key) ?: error("Spotify token response did not contain $key")

    private fun optionalString(body: String, key: String): String? {
        val pattern = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"")
        val encoded = pattern.find(body)?.groupValues?.get(1) ?: return null
        return encoded.replace("\\\\\"", "\\\"")
            .replace("\\\\\\\\", "\\\\")
            .replace("\\\\/", "/")
            .replace("\\\\n", "\n")
            .replace("\\\\r", "\r")
            .replace("\\\\t", "\t")
    }

    private fun requiredLong(body: String, key: String): Long {
        val pattern = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(\\d+)")
        return pattern.find(body)?.groupValues?.get(1)?.toLongOrNull()
            ?: error("Spotify token response did not contain numeric $key")
    }
}
