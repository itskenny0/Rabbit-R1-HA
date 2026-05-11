package com.github.itskenny0.r1ha.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.prefs.ServerConfig
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.prefs.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

class OnboardingViewModel(
    private val http: OkHttpClient,
    private val settings: SettingsRepository,
    private val tokens: TokenStore,
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Probing : State
        /** Server responded with an authorize URL ready to open in the WebView. */
        data class ReadyToAuth(val authorizeUrl: String) : State
        data object Exchanging : State
        data object Done : State
        data class Error(val message: String) : State
    }

    private val _state: MutableStateFlow<State> = MutableStateFlow(State.Idle)
    val state: StateFlow<State> get() = _state

    @Serializable
    private data class ClientResponse(
        @SerialName("client_id") val clientId: String,
        @SerialName("authorize_url") val authorizeUrl: String,
    )

    @Serializable
    data class TokenResponse(
        @SerialName("access_token") val access_token: String,
        @SerialName("refresh_token") val refresh_token: String,
        @SerialName("expires_in") val expires_in: Long,
        @SerialName("token_type") val token_type: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Validates [rawUrl], saves it, then fetches the HA OAuth client registration. */
    fun probe(rawUrl: String) {
        val baseUrl = rawUrl.trimEnd('/')
        if (baseUrl.isBlank()) {
            _state.value = State.Error("Please enter your Home Assistant URL.")
            return
        }
        _state.value = State.Probing
        viewModelScope.launch {
            try {
                val authorizeUrl = withContext(Dispatchers.IO) {
                    val req = Request.Builder()
                        .url("$baseUrl/auth/authorize?response_type=code&client_id=https%3A%2F%2Fitskenny0.github.io%2FRabbit-R1-HA%2F&redirect_uri=r1ha://auth-callback")
                        .head()
                        .build()
                    // Simply confirm the server is reachable; construct the authorize URL directly.
                    http.newCall(req).execute().use { /* consume */ }
                    "$baseUrl/auth/authorize?response_type=code&client_id=https%3A%2F%2Fitskenny0.github.io%2FRabbit-R1-HA%2F&redirect_uri=r1ha%3A%2F%2Fauth-callback"
                }
                // Persist the URL so the rest of the app can use it.
                settings.update { it.copy(server = ServerConfig(url = baseUrl)) }
                _state.value = State.ReadyToAuth(authorizeUrl)
            } catch (e: Exception) {
                _state.value = State.Error("Cannot reach server: ${e.message}")
            }
        }
    }

    /** Called by the WebView once the r1ha://auth-callback?code=… redirect is intercepted. */
    fun exchangeCode(code: String, serverUrl: String) {
        _state.value = State.Exchanging
        viewModelScope.launch {
            try {
                val tokenResponse = withContext(Dispatchers.IO) {
                    val body = FormBody.Builder()
                        .add("grant_type", "authorization_code")
                        .add("code", code)
                        .add("client_id", "https://itskenny0.github.io/Rabbit-R1-HA/")
                        .add("redirect_uri", "r1ha://auth-callback")
                        .build()
                    val req = Request.Builder()
                        .url("$serverUrl/auth/token")
                        .post(body)
                        .build()
                    http.newCall(req).execute().use { resp ->
                        val bodyStr = resp.body?.string()
                            ?: throw IllegalStateException("Empty token response")
                        if (!resp.isSuccessful) {
                            throw IllegalStateException("Token exchange failed (${resp.code}): $bodyStr")
                        }
                        json.decodeFromString<TokenResponse>(bodyStr)
                    }
                }
                val expiresAtMillis = System.currentTimeMillis() + tokenResponse.expires_in * 1_000L
                tokens.save(
                    Tokens(
                        accessToken = tokenResponse.access_token,
                        refreshToken = tokenResponse.refresh_token,
                        expiresAtMillis = expiresAtMillis,
                    )
                )
                _state.value = State.Done
            } catch (e: Exception) {
                _state.value = State.Error("Token exchange failed: ${e.message}")
            }
        }
    }

    fun resetError() {
        _state.value = State.Idle
    }

    companion object {
        fun factory(http: OkHttpClient, settings: SettingsRepository, tokens: TokenStore) =
            viewModelFactory {
                initializer {
                    OnboardingViewModel(http = http, settings = settings, tokens = tokens)
                }
            }
    }
}
