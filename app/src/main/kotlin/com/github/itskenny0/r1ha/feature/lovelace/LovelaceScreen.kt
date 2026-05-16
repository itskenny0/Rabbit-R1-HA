package com.github.itskenny0.r1ha.feature.lovelace

import android.annotation.SuppressLint
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.coroutines.flow.first

/**
 * Lovelace WebView — the escape-hatch surface that hosts HA's own
 * frontend inside our app for everything we don't render natively
 * (custom HACS cards, the automation visual editor, the configuration
 * panel, the full Energy dashboard's bar charts, etc.).
 *
 * The HA Companion app is fundamentally a WebView wrapper around the
 * Lovelace frontend; we're the inverse — native first, WebView as a
 * fallback. This screen makes the WebView fallback an explicit,
 * navigable surface rather than relying on the user to launch a
 * separate browser via 'Open HA web UI' in Settings.
 *
 * Auth handoff: the page is loaded with the user's access token
 * pre-pasted into HA's `hassConnection` initial-state via a brief
 * JavaScript shim, mirroring the official Companion's auth handoff
 * pattern. Without this, the WebView would land on HA's login screen
 * and the user would have to OAuth a second time. Falls back to
 * loading the bare URL when the token isn't yet provisioned (e.g.
 * the user hasn't completed onboarding).
 */
@Composable
fun LovelaceScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // Resolve the server URL + access token once; both come from
    // settings/tokens via produceState so the WebView only loads
    // once we have something to point it at.
    val serverUrl by produceState<String?>(null, settings) {
        value = runCatching { settings.settings.first().server?.url }.getOrNull()
    }
    val accessToken by produceState<String?>(null, tokens) {
        value = runCatching { tokens.load()?.accessToken }.getOrNull()
    }
    var loading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val onBackState = rememberUpdatedState(onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "LOVELACE", onBack = onBack)
        val url = serverUrl
        if (url == null) {
            // Not signed in — same friendly empty-state as other
            // surfaces when the server isn't configured.
            Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No HA server configured — sign in via Settings → SERVER first.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            return@Column
        }
        Box(modifier = Modifier.fillMaxSize()) {
            LovelaceWebView(
                serverUrl = url,
                accessToken = accessToken,
                onLoadingChange = { loading = it },
                onError = { errorMessage = it },
                onBackHandled = { onBackState.value() },
                modifier = Modifier.fillMaxSize(),
            )
            if (loading) {
                // Spinner overlay during main-frame loads — same idiom
                // as OAuthWebView.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(R1.Bg.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = R1.AccentWarm,
                    )
                }
            }
            errorMessage?.let { msg ->
                Box(
                    modifier = Modifier
                        .padding(12.dp)
                        .clip(R1.ShapeS)
                        .background(R1.StatusRed.copy(alpha = 0.12f))
                        .border(1.dp, R1.StatusRed.copy(alpha = 0.4f), R1.ShapeS)
                        .r1Pressable(onClick = { errorMessage = null })
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(text = msg, style = R1.labelMicro, color = R1.StatusRed)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LovelaceWebView(
    serverUrl: String,
    accessToken: String?,
    onLoadingChange: (Boolean) -> Unit,
    onError: (String) -> Unit,
    onBackHandled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webView = remember(context, serverUrl) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Allow HA's Material Web Components + service worker to
            // initialise — they need DOM storage + cookies + mixed
            // content (the WS upgrade) to function.
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    onLoadingChange(true)
                    // Pre-paste the access token into localStorage so
                    // the frontend's hassConnection picks it up without
                    // a second OAuth round-trip. HA stores tokens
                    // under the 'hassTokens' key as a JSON envelope
                    // (access_token + token_type + expires_in +
                    // refresh_token); we synthesise a minimal one with
                    // a far-future expiry so the frontend doesn't try
                    // to refresh.
                    if (!accessToken.isNullOrBlank()) {
                        val script = "javascript:localStorage.setItem('hassTokens', " +
                            "JSON.stringify({" +
                            "access_token: '$accessToken'," +
                            "token_type: 'Bearer'," +
                            "expires_in: 1800," +
                            "refresh_token: ''," +
                            "hassUrl: '${serverUrl.trimEnd('/')}'," +
                            "clientId: null," +
                            "expires: 99999999999999" +
                            "}))"
                        view.evaluateJavascript(script.removePrefix("javascript:")) { }
                    }
                }
                override fun onPageFinished(view: WebView, url: String) {
                    onLoadingChange(false)
                }
                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (!request.isForMainFrame) return
                    val desc = runCatching { error.description?.toString() }.getOrNull() ?: "error"
                    R1Log.w("Lovelace", "WebView error: $desc (${request.url})")
                    onError("WebView: $desc")
                    onLoadingChange(false)
                }
            }
            // Load the dashboard root. HA's frontend redirects to the
            // default Lovelace view at /lovelace; we just point at /
            // and let HA's own routing decide.
            loadUrl("${serverUrl.trimEnd('/')}/")
        }
    }

    // Tear down on disposal to avoid leaks. The WebView is a heavy
    // native peer — leaving it alive after the screen pops would
    // continue running the HA frontend in the background.
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    // System back navigates the WebView's history first; falls
    // through to the screen's onBack when there's no history left.
    BackHandler(enabled = true) {
        if (webView.canGoBack()) webView.goBack() else onBackHandled()
    }

    AndroidView(factory = { webView }, modifier = modifier)
}
