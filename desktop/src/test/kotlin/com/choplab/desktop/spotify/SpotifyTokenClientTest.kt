package com.choplab.desktop.spotify

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpotifyTokenClientTest {
    @Test
    fun authorizationCodeFormUsesPkceAndNeverUsesAClientSecret() {
        val form = SpotifyTokenForm.authorizationCode(
            clientId = "client-123",
            code = "code-123",
            redirectUri = URI("http://127.0.0.1:41731/callback"),
            verifier = "verifier-123",
        )

        assertTrue(form.contains("client_id=client-123"))
        assertTrue(form.contains("grant_type=authorization_code"))
        assertTrue(form.contains("code=code-123"))
        assertTrue(form.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A41731%2Fcallback"))
        assertTrue(form.contains("code_verifier=verifier-123"))
        assertFalse(form.contains("client_secret"))
    }

    @Test
    fun tokenResponseParserKeepsTheRefreshTokenOptional() {
        val tokens = SpotifyTokensJson.parse(
            """
            {
              "access_token": "access-123",
              "token_type": "Bearer",
              "expires_in": 3600,
              "scope": "user-read-currently-playing"
            }
            """.trimIndent(),
        )

        assertEquals("access-123", tokens.accessToken)
        assertEquals("Bearer", tokens.tokenType)
        assertEquals(3600L, tokens.expiresInSeconds)
        assertEquals(null, tokens.refreshToken)
        assertEquals("user-read-currently-playing", tokens.scope)
    }
}
