package com.choplab.desktop.spotify

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotifyPkceTest {
    @Test
    fun authorizationRequestUsesS256AndLeastPrivilegeScopes() {
        val request = SpotifyAuthorizationRequest(
            clientId = "client-123",
            redirectUri = URI("http://127.0.0.1:41731/callback"),
            scopes = listOf("user-read-currently-playing", "playlist-read-private"),
            state = "state-123",
            codeChallenge = "challenge-123",
        )

        val uri = request.toUri().toString()

        assertTrue(uri.startsWith("https://accounts.spotify.com/authorize?"))
        assertTrue(uri.contains("response_type=code"))
        assertTrue(uri.contains("client_id=client-123"))
        assertTrue(uri.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A41731%2Fcallback"))
        assertTrue(uri.contains("scope=user-read-currently-playing%20playlist-read-private"))
        assertTrue(uri.contains("state=state-123"))
        assertTrue(uri.contains("code_challenge_method=S256"))
        assertTrue(uri.contains("code_challenge=challenge-123"))
        assertFalse(uri.contains("client_secret"))
    }

    @Test
    fun knownVerifierProducesTheIndependentS256Challenge() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.codeChallenge(verifier),
        )
    }

    @Test
    fun generatedAttemptUsesOneStateForTheAttemptAndAuthorizationUrl() {
        val attempt = newSpotifyAuthorizationAttempt(
            clientId = "client-123",
            redirectUri = URI("http://127.0.0.1:41731/callback"),
            scopes = listOf("user-read-currently-playing"),
        )

        assertTrue(attempt.request.toUri().toString().contains("state=${encode(attempt.state)}"))
        assertEquals(Pkce.codeChallenge(attempt.verifier), attempt.request.codeChallenge)
    }
}
