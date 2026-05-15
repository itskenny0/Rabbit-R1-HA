package com.github.itskenny0.r1ha.feature.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.PersistentNotification
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Notifications viewer — lists HA persistent_notification.* entries
 * with title, message and a DISMISS chip per row. Same conceptual
 * surface as HA's frontend bell icon: integration failures, firmware
 * updates available, "you should restart HA" prompts, automation-side
 * `persistent_notification.create` messages.
 *
 * Polling: refreshed once on screen entry; user pulls down or backs
 * out/in to re-fetch. We don't subscribe to a state-stream for these
 * because they're low-cardinality and short-lived; a fresh GET each
 * time is the lighter footprint.
 */
@Composable
fun NotificationsScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: NotificationsViewModel = viewModel(factory = NotificationsViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    // Auto-refresh — cadence comes from Settings → Integrations →
    // 'Notifications refresh'. 0 disables auto-refresh (pull-down only).
    val appSettings by settings.settings.collectAsState(
        initial = com.github.itskenny0.r1ha.core.prefs.AppSettings(),
    )
    val refreshSec = appSettings.integrations.notificationsRefreshSec
    if (refreshSec > 0) {
        com.github.itskenny0.r1ha.ui.components.AutoRefresh(refreshSec * 1000L) { vm.refresh() }
    } else {
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "NOTIFICATIONS", onBack = onBack)
        when {
            ui.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = R1.AccentWarm,
                )
            }
            ui.notifications.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No persistent notifications in HA — all clear.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = ui.loading,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp, vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(items = ui.notifications, key = { it.notificationId }) { n ->
                        NotificationRow(
                            notification = n,
                            pendingDismiss = n.notificationId in ui.pendingDismiss,
                            onDismiss = { vm.dismiss(n) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(
    notification: PersistentNotification,
    pendingDismiss: Boolean,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = notification.title?.takeIf { it.isNotBlank() } ?: notification.notificationId,
                style = R1.body,
                color = R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            Spacer(Modifier.width(8.dp))
            // Relative timestamp — same ticker as the rest of the app so
            // "2 m ago" updates without us having to invalidate manually.
            RelativeTimeLabel(
                at = notification.createdAt,
                color = R1.InkMuted,
                style = R1.labelMicro,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = notification.message,
            style = R1.body,
            color = R1.InkSoft,
            maxLines = 6,
        )
        Spacer(Modifier.size(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = notification.notificationId,
                style = R1.labelMicro,
                color = R1.InkMuted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(
                        if (pendingDismiss) R1.SurfaceMuted else R1.StatusRed.copy(alpha = 0.18f),
                    )
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = { if (!pendingDismiss) onDismiss() })
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = if (pendingDismiss) "DISMISSING…" else "DISMISS",
                    style = R1.labelMicro,
                    color = if (pendingDismiss) R1.InkMuted else R1.StatusRed,
                )
            }
        }
    }
}
