package com.github.itskenny0.r1ha.feature.onboarding

import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster

/**
 * Renders [authorizeUrl] in an embedded [WebView] and intercepts the
 * `r1ha://auth-callback?code=…` redirect, extracting the authorization code
 * and forwarding it to [onCodeCaptured].
 */
@Composable
fun OAuthWebView(
    authorizeUrl: String,
    onCodeCaptured: (code: String) -> Unit,
    /** Called when the redirect arrives without a `code` query parameter — passes through
     *  the `error` param from HA (e.g. "access_denied" when the user tapped Deny) so the
     *  caller can surface it in the UI instead of just resetting silently. */
    onMissingCode: (errorMessage: String?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Keep the latest callbacks visible to the long-lived WebViewClient closure.
    val currentOnCode = rememberUpdatedState(onCodeCaptured)
    val currentOnMissing = rememberUpdatedState(onMissingCode)

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
                    R1Log.d("OAuthWebView", "shouldOverride scheme=${uri.scheme} host=${uri.host}")
                    if (uri.scheme == "r1ha" && uri.host == "auth-callback") {
                        val code = uri.getQueryParameter("code")
                        val error = uri.getQueryParameter("error")
                        R1Log.i("OAuthWebView", "redirect captured code?=${!code.isNullOrBlank()} error=$error")
                        if (!code.isNullOrBlank()) {
                            Toaster.show("Captured auth code")
                            currentOnCode.value.invoke(code)
                        } else {
                            val msg = error?.let { "Redirect had error=$it" } ?: "Redirect had no code"
                            R1Log.w("OAuthWebView", msg)
                            Toaster.show(msg, long = true)
                            currentOnMissing.value.invoke(error)
                        }
                        return true
                    }
                    return false
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    // Only surface errors for the top-level (main-frame) navigation. Sub-resource
                    // failures (favicon, analytics, etc.) are noise — HA's login page still works
                    // even when those 404. We also tolerate the error firing for the very last
                    // hop into r1ha://auth-callback, which the WebView reports as "scheme not
                    // supported" — that's expected and is handled by shouldOverrideUrlLoading.
                    if (!request.isForMainFrame) return
                    val url = request.url
                    if (url.scheme == "r1ha") return
                    val desc = runCatching { error.description?.toString() }.getOrNull() ?: "error"
                    R1Log.w("OAuthWebView", "main-frame load error: $desc ($url)")
                    Toaster.show("WebView: $desc", long = true)
                }
            }
            loadUrl(authorizeUrl)
        }
    }

    // If the screen leaves composition, drop the WebView so it doesn't leak.
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier,
    )
}
