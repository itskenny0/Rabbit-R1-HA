package com.github.itskenny0.r1ha.feature.onboarding

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Renders [authorizeUrl] in an embedded [WebView] and intercepts the
 * `r1ha://auth-callback?code=…` redirect, extracting the authorization code
 * and forwarding it to [onCodeCaptured].
 */
@Composable
fun OAuthWebView(
    authorizeUrl: String,
    onCodeCaptured: (code: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Keep the WebView stable across recompositions so we don't reload the page.
    val webView = remember(context) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val uri = request.url
                    if (uri.scheme == "r1ha" && uri.host == "auth-callback") {
                        val code = uri.getQueryParameter("code")
                        if (!code.isNullOrBlank()) {
                            onCodeCaptured(code)
                        }
                        return true
                    }
                    return false
                }
            }
            loadUrl(authorizeUrl)
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier,
    )
}
