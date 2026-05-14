package com.github.itskenny0.r1ha.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Tiny image loader for the now-playing album art and any other one-off
 * Compose-rendered bitmap. No Glide / Coil dependency — the R1 use case is
 * one or two album covers visible at a time, so a per-composable LaunchedEffect
 * that fetches the bytes and decodes via BitmapFactory is plenty.
 *
 * Handles three URL shapes coming from HA's `entity_picture` attribute:
 *  1. Absolute `http(s)://…` — used verbatim.
 *  2. Relative `/api/media_player_proxy/…` — prepended with the configured HA
 *     server URL.
 *  3. `data:image/…` data URIs — split and decoded as base64 in place.
 *
 * Failures (network down, decode error, 404 on the proxy) render a quiet
 * placeholder instead of throwing; the album cover is a nice-to-have, not a
 * must-have, and a broken image rotating in a corner of the card would be
 * worse than a clean fallback.
 */
@Composable
fun AsyncBitmap(
    url: String?,
    serverUrl: String?,
    bearerToken: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    var bitmap by remember(url, serverUrl) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url, serverUrl) { mutableStateOf(false) }

    LaunchedEffect(url, serverUrl, bearerToken) {
        bitmap = null
        failed = false
        val raw = url ?: return@LaunchedEffect
        val resolved = resolveUrl(raw, serverUrl) ?: run { failed = true; return@LaunchedEffect }
        val image = runCatching { fetchAndDecode(resolved, bearerToken) }
            .onFailure { R1Log.d("AsyncBitmap", "fetch failed $resolved: ${it.message}") }
            .getOrNull()
        if (image != null) bitmap = image else failed = true
    }

    Box(modifier = modifier.background(R1.SurfaceMuted)) {
        val img = bitmap
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (failed) {
            // Tiny "♪" placeholder so an unloadable cover still reads as 'this is
            // where the art would go' rather than an empty rectangle.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "♪", style = R1.numeralM, color = R1.InkMuted)
            }
        }
        // Loading state intentionally renders as the empty SurfaceMuted box —
        // album art typically loads in <300 ms over LAN, and a spinner during
        // that window would feel busier than the brief blank frame.
    }
}

/**
 * Resolve the entity_picture URL into something OkHttp can fetch. Returns null
 * for shapes we can't handle (e.g. malformed data URIs); the caller renders the
 * placeholder in that case.
 */
private fun resolveUrl(raw: String, serverUrl: String?): String? = when {
    raw.startsWith("http://") || raw.startsWith("https://") -> raw
    raw.startsWith("data:") -> raw  // handled specially in fetchAndDecode
    raw.startsWith("/") && !serverUrl.isNullOrBlank() -> serverUrl.trimEnd('/') + raw
    else -> null
}

private suspend fun fetchAndDecode(url: String, bearerToken: String?): ImageBitmap? =
    withContext(Dispatchers.IO) {
        if (url.startsWith("data:")) {
            // data:image/jpeg;base64,<...> — split off the base64 segment and
            // decode it. Inline data URIs are rare for media_player but some
            // integrations (Plex, Music Assistant) emit them.
            val commaIdx = url.indexOf(',')
            if (commaIdx < 0) return@withContext null
            val payload = url.substring(commaIdx + 1)
            val bytes = runCatching { android.util.Base64.decode(payload, android.util.Base64.DEFAULT) }
                .getOrNull() ?: return@withContext null
            return@withContext BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
        val client = SharedHttpClient.instance
        val builder = Request.Builder().url(url)
        if (!bearerToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $bearerToken")
        }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val bytes = resp.body?.bytes() ?: return@withContext null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }

/**
 * Process-scoped OkHttp instance for ad-hoc one-shot fetches that don't need to
 * share state with the main repository's client. Lazy so test contexts that
 * never load an album cover don't pay the construction cost.
 */
private object SharedHttpClient {
    val instance: OkHttpClient by lazy { OkHttpClient() }
}
