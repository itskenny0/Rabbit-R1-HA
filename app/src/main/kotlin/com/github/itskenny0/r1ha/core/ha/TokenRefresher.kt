package com.github.itskenny0.r1ha.core.ha

import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.prefs.Tokens
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Exchanges the stored refresh token for a fresh access token via HA's `/auth/token` endpoint.
 *
 * Home Assistant's access tokens expire after about 30 minutes by default. Without this, the WS
 * connection drops into [ConnectionState.AuthLost] the first time the access token expires —
 * which on the user's side looks like the app silently lost its login. With this, the repository
 * refreshes proactively before reconnect and reactively after an AuthLost transition.
 */
class TokenRefresher(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
    private val clientId: String = "https://itskenny0.github.io/Rabbit-R1-HA/",
) {
    @Serializable
    private data class RefreshResponse(
        @SerialName("access_token") val access_token: String,
        @SerialName("expires_in") val expires_in: Long,
        @SerialName("token_type") val token_type: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * If the stored access token is within [skewMillis] of expiry, exchange the refresh token
     * for a new access token and persist it. Returns true if the token is now valid (either
     * already-valid or freshly refreshed); false if there is no token, no server URL, or the
     * refresh attempt failed.
     */
    suspend fun ensureFresh(skewMillis: Long = 60_000L): Boolean {
        val current = tokens.load() ?: return false
        if (current.expiresAtMillis > System.currentTimeMillis() + skewMillis) return true
        return refresh(current)
    }

    /** Force a refresh regardless of remaining lifetime. Used after [ConnectionState.AuthLost]. */
    suspend fun forceRefresh(): Boolean {
        val current = tokens.load() ?: return false
        return refresh(current)
    }

    private suspend fun refresh(current: Tokens): Boolean = withContext(Dispatchers.IO) {
        val serverUrl = settings.settings.first().server?.url ?: return@withContext false
        try {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", current.refreshToken)
                .add("client_id", clientId)
                .build()
            val req = Request.Builder()
                .url("${serverUrl.trimEnd('/')}/auth/token")
                .post(body)
                .build()
            val resp = http.newCall(req).execute().use { r ->
                val bodyStr = r.body?.string() ?: error("Empty refresh response")
                if (!r.isSuccessful) error("HTTP ${r.code}: $bodyStr")
                json.decodeFromString<RefreshResponse>(bodyStr)
            }
            val expiresAt = System.currentTimeMillis() + resp.expires_in * 1_000L
            tokens.save(
                current.copy(
                    accessToken = resp.access_token,
                    expiresAtMillis = expiresAt,
                )
            )
            R1Log.i("TokenRefresher", "refreshed; new expiry in ${resp.expires_in}s")
            Toaster.show("Session refreshed")
            true
        } catch (t: Throwable) {
            R1Log.e("TokenRefresher", "refresh failed", t)
            Toaster.show("Couldn't refresh session: ${t.message ?: "error"} — sign out & reconnect", long = true)
            false
        }
    }
}
