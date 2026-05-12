package com.github.itskenny0.r1ha.feature.about

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.itskenny0.r1ha.BuildConfig
import com.github.itskenny0.r1ha.core.ha.ConnectionState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable

@Composable
fun AboutScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val connection by haRepository.connection.collectAsStateWithLifecycle()
    val appSettings by settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "ABOUT", onBack = onBack)

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {

            // ── App ────────────────────────────────────────────────────────────────
            item { Section("APP") }
            item { InfoRow("Version", BuildConfig.VERSION_NAME, mono = true) }
            item { InfoRow("Build", BuildConfig.GIT_SHA, mono = true) }
            item {
                LinkRow(
                    label = "Source code",
                    url = BuildConfig.SOURCE_URL,
                    onOpen = {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.SOURCE_URL))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                )
            }

            item { SectionDivider() }

            // ── Connection ─────────────────────────────────────────────────────────
            item { Section("CONNECTION") }
            item { InfoRow("Server", appSettings.server?.url ?: "(not connected)", mono = true) }
            item {
                InfoRow(
                    label = "WebSocket",
                    value = describeConnection(connection),
                )
            }
            item { InfoRow("Favourites", appSettings.favorites.size.toString(), mono = true) }

            item { SectionDivider() }

            // ── Device ─────────────────────────────────────────────────────────────
            item { Section("DEVICE") }
            item { InfoRow("Manufacturer", Build.MANUFACTURER) }
            item { InfoRow("Model", Build.MODEL) }
            item { InfoRow("Android", "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})") }

            item { SectionDivider() }

            // ── License ────────────────────────────────────────────────────────────
            item { Section("LICENSE") }
            item {
                Text(
                    text = "Released into the public domain via The Unlicense. " +
                        "Copy, modify, redistribute — commercial or not, by any means.",
                    style = R1.body,
                    color = R1.InkSoft,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
                )
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

@Composable
private fun Section(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = R1.sectionHeader, color = R1.AccentWarm)
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .height(1.dp)
                .weight(1f)
                .background(R1.Hairline),
        )
    }
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = R1.bodyEmph, color = R1.Ink)
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = if (mono) R1.body.copy(fontFamily = FontFamily.Monospace) else R1.body,
            color = R1.InkSoft,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun LinkRow(label: String, url: String, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(onOpen)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(label, style = R1.bodyEmph, color = R1.Ink)
        Spacer(Modifier.height(2.dp))
        Text(
            text = url,
            // Underline so the URL reads as interactive even without a chevron.
            style = R1.body.copy(
                fontFamily = FontFamily.Monospace,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
            ),
            color = R1.AccentWarm,
        )
    }
}

private fun describeConnection(state: ConnectionState): String = when (state) {
    ConnectionState.Idle -> "Idle"
    ConnectionState.Connecting -> "Connecting…"
    ConnectionState.Authenticating -> "Authenticating…"
    is ConnectionState.Connected ->
        "Connected${state.haVersion?.let { " · HA $it" } ?: ""}"
    is ConnectionState.Disconnected -> when (val c = state.cause) {
        ConnectionState.Cause.Network -> "Disconnected · network"
        ConnectionState.Cause.ServerClosed -> "Disconnected · server closed"
        is ConnectionState.Cause.Error -> "Disconnected · ${c.throwable.message ?: "error"}"
    }
    is ConnectionState.AuthLost -> "Auth lost · ${state.reason ?: "tokens invalid"}"
}
