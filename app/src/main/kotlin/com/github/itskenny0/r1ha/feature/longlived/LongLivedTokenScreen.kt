package com.github.itskenny0.r1ha.feature.longlived

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.prefs.ServerConfig
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.prefs.Tokens
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Long-lived access token setup — alternative to the OAuth flow for
 * users who would rather paste a token from HA's `My profile` page
 * than authenticate in the WebView. Common with kiosk-style HA setups
 * (R1s mounted in cars, on walls, etc.) where the LLAT lives forever
 * and OAuth's refresh dance is just paperwork.
 *
 * Storage shape: `Tokens(accessToken = LLAT, refreshToken = "",
 * expiresAtMillis = Long.MAX_VALUE)`. The empty-refresh sentinel is
 * the contract [com.github.itskenny0.r1ha.core.ha.TokenRefresher]
 * checks before attempting a refresh — for LLATs it skips the refresh
 * call entirely.
 */
@Composable
fun LongLivedTokenScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    haRepository: HaRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val current by settings.settings.collectAsState(initial = null)
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Pre-fill the URL field with the currently-configured server (if any)
    // so users editing their token don't have to retype the URL.
    LaunchedEffect(current) {
        if (url.isBlank()) {
            url = current?.server?.url.orEmpty()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        R1TopBar(title = "LONG-LIVED TOKEN", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Skip OAuth: paste an HA long-lived access token. Generate one " +
                    "from HA Profile → Long-Lived Access Tokens. Stored encrypted at " +
                    "rest (AndroidKeystore-wrapped AES-256-GCM) just like the OAuth path.",
                style = R1.body,
                color = R1.InkMuted,
            )
            Spacer(Modifier.heightIn(min = 16.dp).fillMaxWidth())
            Text(text = "HA URL", style = R1.labelMicro, color = R1.InkSoft)
            Spacer(Modifier.heightIn(min = 4.dp).fillMaxWidth())
            R1TextField(
                value = url,
                onValueChange = { url = it },
                placeholder = "https://homeassistant.local:8123",
                monospace = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.heightIn(min = 12.dp).fillMaxWidth())
            Text(text = "ACCESS TOKEN", style = R1.labelMicro, color = R1.InkSoft)
            Spacer(Modifier.heightIn(min = 4.dp).fillMaxWidth())
            R1TextField(
                value = token,
                onValueChange = { token = it },
                placeholder = "eyJhbGciOiJIUzI1NiIs…",
                monospace = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 80.dp),
            )
            Spacer(Modifier.heightIn(min = 16.dp).fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                R1Button(
                    text = if (saving) "SAVING…" else "SAVE & CONNECT",
                    enabled = !saving && url.isNotBlank() && token.isNotBlank() && url.startsWith("http"),
                    onClick = {
                        saving = true
                        error = null
                        scope.launch {
                            try {
                                val normalisedUrl = url.trim().trimEnd('/')
                                require(normalisedUrl.startsWith("http://") ||
                                    normalisedUrl.startsWith("https://")) {
                                    "URL must start with http:// or https://"
                                }
                                val newServer = ServerConfig(url = normalisedUrl, haVersion = null)
                                settings.update { it.copy(server = newServer) }
                                tokens.save(
                                    Tokens(
                                        accessToken = token.trim(),
                                        refreshToken = "",
                                        // Far-future expiry so ensureFresh's
                                        // skew check is always satisfied — the
                                        // empty refreshToken is the real
                                        // 'don't refresh' signal but a Long.MAX
                                        // expiry is good documentation too.
                                        expiresAtMillis = Long.MAX_VALUE,
                                    ),
                                )
                                R1Log.i("LLAT", "saved long-lived token for $normalisedUrl")
                                // Kick off the connection — the repository
                                // will pick up the new server + token on the
                                // next WS attempt. reconnectNow makes that
                                // attempt happen immediately rather than on
                                // the next backoff fire.
                                haRepository.reconnectNow()
                                Toaster.show("Long-lived token saved · connecting…")
                                onBack()
                            } catch (t: Throwable) {
                                R1Log.w("LLAT", "save failed: ${t.message}")
                                error = t.message ?: "Save failed"
                            } finally {
                                saving = false
                            }
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "no refresh; revoke from HA to invalidate",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
            val e = error
            if (e != null) {
                Spacer(Modifier.heightIn(min = 12.dp).fillMaxWidth())
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(R1.StatusRed.copy(alpha = 0.18f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(text = e, style = R1.body, color = R1.StatusRed)
                }
            }
        }
    }
}
