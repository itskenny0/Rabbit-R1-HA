package com.github.itskenny0.r1ha.feature.about

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.itskenny0.r1ha.BuildConfig
import com.github.itskenny0.r1ha.core.ha.ConnectionState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.ui.components.ChevronBack

@Composable
fun AboutScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val connection by haRepository.connection.collectAsStateWithLifecycle()
    val appSettings by settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
        ) {
            ChevronBack(onClick = onBack)
            Text(
                text = "About",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {

            // ── App info ─────────────────────────────────────────────────────
            item {
                Text(
                    text = "APP".uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
                )
            }
            item { InfoRow(label = "Version", value = BuildConfig.VERSION_NAME) }
            item { InfoRow(label = "Build", value = BuildConfig.GIT_SHA) }
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

            item {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                )
            }

            // ── License ──────────────────────────────────────────────────────
            item {
                Text(
                    text = "LICENSE".uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            item {
                Text(
                    text = "Released into the public domain via The Unlicense. " +
                            "This is free and unencumbered software released into the public domain. " +
                            "Anyone is free to copy, modify, publish, use, compile, sell, or distribute this software, " +
                            "for any purpose, commercial or non-commercial, and by any means.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            item {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                )
            }

            // ── Device info ──────────────────────────────────────────────────
            item {
                Text(
                    text = "DEVICE".uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            item { InfoRow(label = "Manufacturer", value = Build.MANUFACTURER) }
            item { InfoRow(label = "Model", value = Build.MODEL) }
            item { InfoRow(label = "Android", value = "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})") }

            item {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                )
            }

            // ── Home Assistant connection state ──────────────────────────────
            item {
                Text(
                    text = "CONNECTION".uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            item { InfoRow(label = "Server", value = appSettings.server?.url ?: "(not configured)") }
            item {
                InfoRow(
                    label = "WebSocket",
                    value = when (val st = connection) {
                        ConnectionState.Idle -> "Idle"
                        ConnectionState.Connecting -> "Connecting…"
                        ConnectionState.Authenticating -> "Authenticating…"
                        is ConnectionState.Connected -> "Connected${st.haVersion?.let { " (HA $it)" } ?: ""}"
                        is ConnectionState.Disconnected -> when (val c = st.cause) {
                            ConnectionState.Cause.Network -> "Disconnected (network)"
                            ConnectionState.Cause.ServerClosed -> "Disconnected (server closed)"
                            is ConnectionState.Cause.Error -> "Disconnected (${c.throwable.message ?: "error"})"
                        }
                        is ConnectionState.AuthLost -> "Auth lost: ${st.reason ?: "tokens invalid"}"
                    },
                )
            }
            item { InfoRow(label = "Favourites", value = appSettings.favorites.size.toString()) }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun LinkRow(
    label: String,
    url: String,
    onOpen: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            softWrap = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
        )
    }
}
