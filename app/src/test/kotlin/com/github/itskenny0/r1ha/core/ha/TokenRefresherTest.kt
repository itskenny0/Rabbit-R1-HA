package com.github.itskenny0.r1ha.core.ha

import androidx.test.core.app.ApplicationProvider
import com.github.itskenny0.r1ha.core.prefs.ServerConfig
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.SoftwareKeyProvider
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.prefs.Tokens
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TokenRefresherTest {

    private lateinit var server: MockWebServer
    private lateinit var settings: SettingsRepository
    private lateinit var tokens: TokenStore

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        settings = SettingsRepository.forTesting(
            context = context,
            datastoreName = "tr_settings_${System.nanoTime()}",
            shadowName = "tr_shadow_${System.nanoTime()}",
        )
        tokens = TokenStore(
            context = context,
            datastoreName = "tr_tokens_${System.nanoTime()}",
            keyAlias = "tr_key_${System.nanoTime()}",
            keystoreProvider = SoftwareKeyProvider(),
        )
    }

    @After fun tearDown() { server.shutdown() }

    private fun newRefresher() = TokenRefresher(
        http = OkHttpClient(),
        settings = settings,
        tokens = tokens,
    )

    @Test fun ensureFreshIsNoopWhenTokenHasPlentyOfLifeLeft() = runTest {
        settings.update { it.copy(server = ServerConfig(url = server.url("").toString().trimEnd('/'))) }
        val notExpiring = System.currentTimeMillis() + 60 * 60 * 1_000 // 1h
        tokens.save(Tokens(accessToken = "A", refreshToken = "R", expiresAtMillis = notExpiring))

        val ok = newRefresher().ensureFresh()

        assertThat(ok).isTrue()
        assertThat(server.requestCount).isEqualTo(0) // no HTTP call when token is fresh
        // Stored access token unchanged.
        assertThat(tokens.load()!!.accessToken).isEqualTo("A")
    }

    @Test fun ensureFreshRefreshesNearExpiryAndPersistsNewAccessToken() = runTest {
        settings.update { it.copy(server = ServerConfig(url = server.url("").toString().trimEnd('/'))) }
        val almostExpired = System.currentTimeMillis() + 10_000 // 10s — inside the 60s skew
        tokens.save(Tokens(accessToken = "OLD", refreshToken = "R", expiresAtMillis = almostExpired))

        server.enqueue(
            MockResponse()
                .setBody("""{"access_token":"NEW","expires_in":1800,"token_type":"Bearer"}""")
                .setHeader("Content-Type", "application/json")
        )

        val ok = newRefresher().ensureFresh()
        assertThat(ok).isTrue()
        val reload = tokens.load()!!
        assertThat(reload.accessToken).isEqualTo("NEW")
        // Refresh token is preserved unchanged.
        assertThat(reload.refreshToken).isEqualTo("R")
        // The new expiry should be at least ~29 minutes in the future (we asked for 1800s).
        assertThat(reload.expiresAtMillis - System.currentTimeMillis()).isGreaterThan(29 * 60 * 1_000)

        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/auth/token")
        val body = req.body.readUtf8()
        assertThat(body).contains("grant_type=refresh_token")
        assertThat(body).contains("refresh_token=R")
    }

    @Test fun forceRefreshIgnoresRemainingLifetime() = runTest {
        settings.update { it.copy(server = ServerConfig(url = server.url("").toString().trimEnd('/'))) }
        val plentyOfLife = System.currentTimeMillis() + 60 * 60 * 1_000
        tokens.save(Tokens(accessToken = "OLD", refreshToken = "R", expiresAtMillis = plentyOfLife))

        server.enqueue(
            MockResponse()
                .setBody("""{"access_token":"NEW","expires_in":1800,"token_type":"Bearer"}""")
                .setHeader("Content-Type", "application/json")
        )

        val ok = newRefresher().forceRefresh()
        assertThat(ok).isTrue()
        assertThat(tokens.load()!!.accessToken).isEqualTo("NEW")
    }

    @Test fun refreshFailsCleanlyWhenServerReturns400() = runTest {
        settings.update { it.copy(server = ServerConfig(url = server.url("").toString().trimEnd('/'))) }
        tokens.save(Tokens("OLD", "R", System.currentTimeMillis() + 10_000))

        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))

        val ok = newRefresher().ensureFresh()
        assertThat(ok).isFalse()
        // On failure, the existing token is left untouched (still "OLD").
        assertThat(tokens.load()!!.accessToken).isEqualTo("OLD")
    }

    @Test fun ensureFreshReturnsFalseWhenNoTokensStored() = runTest {
        settings.update { it.copy(server = ServerConfig(url = server.url("").toString().trimEnd('/'))) }
        // No tokens.save()

        val ok = newRefresher().ensureFresh()
        assertThat(ok).isFalse()
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun ensureFreshReturnsFalseWhenNoServerConfigured() = runTest {
        tokens.save(Tokens("OLD", "R", System.currentTimeMillis() + 10_000))
        // settings.server stays null

        val ok = newRefresher().ensureFresh()
        assertThat(ok).isFalse()
    }
}
