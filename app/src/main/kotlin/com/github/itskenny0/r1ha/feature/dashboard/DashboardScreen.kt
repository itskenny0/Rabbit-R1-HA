package com.github.itskenny0.r1ha.feature.dashboard

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Today dashboard — single at-a-glance home screen composed from
 * outdoor weather, persons home/away, next calendar event, camera
 * count, and notification count. Each section is its own tappable
 * card that drills into the corresponding full-list screen.
 *
 * The dashboard is **read-only**; no toggles, no service calls. Its
 * job is to answer "what should I know right now?" in one glance,
 * then route the user to the right detail surface for follow-up.
 */
@Composable
fun DashboardScreen(
    haRepository: HaRepository,
    onBack: () -> Unit,
    onOpenWeather: () -> Unit,
    onOpenPersons: () -> Unit,
    onOpenCalendars: () -> Unit,
    onOpenCameras: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenScenes: () -> Unit,
) {
    val vm: DashboardViewModel = viewModel(factory = DashboardViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    // Auto-refresh while the screen is composed — 60 s is the sweet spot
    // between "stale enough to notice" and "polling /api/states 6× per
    // minute". LaunchedEffect cancellation on screen exit kills the loop
    // cleanly so backing out doesn't leak the work.
    LaunchedEffect(Unit) {
        while (true) {
            vm.refresh()
            kotlinx.coroutines.delay(60_000L)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "TODAY", onBack = onBack)
        if (ui.loading && ui.weather == null && ui.persons == null && ui.nextEvent == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = R1.AccentWarm,
                )
            }
            return@Column
        }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = ui.loading,
            onRefresh = { vm.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Greeting()
                ui.weather?.let { WeatherCard(it, onClick = onOpenWeather) }
                ui.sun?.let { SunCard(it) }
                if (ui.timers.isNotEmpty()) {
                    Text(text = "TIMERS", style = R1.labelMicro, color = R1.InkSoft)
                    for (t in ui.timers) {
                        TimerCard(t)
                    }
                }
                if (ui.media.isNotEmpty()) {
                    Text(text = "NOW PLAYING", style = R1.labelMicro, color = R1.InkSoft)
                    for (m in ui.media) {
                        MediaCard(
                            media = m,
                            onPlayPause = {
                                vm.mediaTransport(
                                    m.entityId,
                                    com.github.itskenny0.r1ha.core.ha.MediaTransport.PLAY_PAUSE,
                                )
                            },
                            onNext = {
                                vm.mediaTransport(
                                    m.entityId,
                                    com.github.itskenny0.r1ha.core.ha.MediaTransport.NEXT,
                                )
                            },
                            onPrev = {
                                vm.mediaTransport(
                                    m.entityId,
                                    com.github.itskenny0.r1ha.core.ha.MediaTransport.PREVIOUS,
                                )
                            },
                        )
                    }
                }
                ui.persons?.let { PersonsCard(it, onClick = onOpenPersons) }
                ui.nextEvent?.let { CalendarCard(it, onClick = onOpenCalendars) }
                MetricsRow(
                    cameraCount = ui.cameraCount,
                    notificationCount = ui.notifications.size,
                    lightsOnCount = ui.lightsOnCount,
                    totalPowerW = ui.totalPowerW,
                    onLights = onOpenScenes,
                    onCameras = onOpenCameras,
                    onNotifications = onOpenNotifications,
                )
                // If there are notifications, show the first 2 inline below
                // the metrics row so the user sees what HA is shouting about
                // without having to drill in.
                if (ui.notifications.isNotEmpty()) {
                    Spacer(Modifier.size(2.dp))
                    Text(text = "RECENT ALERTS", style = R1.labelMicro, color = R1.InkSoft)
                    for (notif in ui.notifications.take(2)) {
                        NotificationPreview(notif, onClick = onOpenNotifications)
                    }
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun WeatherCard(
    w: DashboardViewModel.WeatherSummary,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = conditionGlyph(w.condition),
            style = R1.numeralXl,
            color = conditionAccent(w.condition),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = w.name.uppercase(), style = R1.labelMicro, color = R1.InkSoft)
            Text(
                text = w.condition.replace('-', ' ').uppercase(),
                style = R1.body.copy(fontWeight = FontWeight.SemiBold),
                color = R1.Ink,
            )
        }
        if (w.temperature != null) {
            Text(
                text = "${"%.0f".format(w.temperature)}${w.temperatureUnit ?: "°"}",
                style = R1.numeralXl,
                color = R1.Ink,
            )
        }
    }
}

@Composable
private fun SunCard(s: DashboardViewModel.SunSummary) {
    // Read-only: there's no useful tap action on the sun. (Could route
    // to a /history?entity_id=sun.sun web view but the dashboard is
    // already exposing the salient fields.)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Sun glyph state — above_horizon = ☀, below_horizon = ☾ +
            // muted tint so the night state reads as quiet.
            val isUp = s.state == "above_horizon"
            Text(
                text = if (isUp) "☀" else "☾",
                style = R1.numeralXl,
                color = if (isUp) R1.AccentWarm else R1.AccentCool,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "SUN", style = R1.labelMicro, color = R1.InkSoft)
                Text(
                    text = (if (isUp) "ABOVE HORIZON" else "BELOW HORIZON") +
                        (s.elevation?.let { " · ${"%.1f".format(it)}°" } ?: ""),
                    style = R1.body.copy(fontWeight = FontWeight.SemiBold),
                    color = R1.Ink,
                )
            }
        }
        // Next rise / set — show whichever is upcoming (already past for
        // the other half of the day cycle, no point repeating).
        Row {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "NEXT RISE", style = R1.labelMicro, color = R1.InkMuted)
                RelativeTimeLabel(at = s.nextRising, color = R1.AccentWarm, style = R1.labelMicro)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "NEXT SET", style = R1.labelMicro, color = R1.InkMuted)
                RelativeTimeLabel(at = s.nextSetting, color = R1.AccentCool, style = R1.labelMicro)
            }
        }
    }
}

@Composable
private fun TimerCard(t: DashboardViewModel.TimerSummary) {
    // Read-only timer summary — state chip + remaining time. Pause/start
    // dispatch isn't exposed here (would clutter the dashboard with
    // controls); user can favourite a timer for full control via the
    // card stack or use the Service Caller.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (label, color) = when (t.state) {
            "active" -> "RUNNING" to R1.AccentGreen
            "paused" -> "PAUSED" to R1.StatusAmber
            else -> t.state.uppercase() to R1.InkSoft
        }
        Text(text = label, style = R1.labelMicro, color = color)
        Spacer(Modifier.width(10.dp))
        Text(text = t.name, style = R1.body, color = R1.Ink, modifier = Modifier.weight(1f), maxLines = 1)
        Spacer(Modifier.width(8.dp))
        RelativeTimeLabel(at = t.finishesAt, color = color, style = R1.labelMicro)
    }
}

@Composable
private fun Greeting() {
    // Time-of-day greeting + a short date line. Refreshes whenever the
    // Dashboard recomposes (every 60 s via the auto-refresh loop) which
    // is enough granularity for a greeting that only changes every few
    // hours. Kept lightweight: no animation, no live ticker.
    val now = java.time.LocalDateTime.now()
    val hour = now.hour
    val greeting = when (hour) {
        in 5..11 -> "GOOD MORNING"
        in 12..17 -> "GOOD AFTERNOON"
        in 18..21 -> "GOOD EVENING"
        else -> "GOOD NIGHT"
    }
    val dateLine = now.toLocalDate().format(
        java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMM").withLocale(java.util.Locale.getDefault()),
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        Text(text = greeting, style = R1.sectionHeader, color = R1.AccentWarm)
        Text(text = dateLine.uppercase(), style = R1.labelMicro, color = R1.InkSoft)
    }
}

@Composable
private fun MediaCard(
    media: DashboardViewModel.MediaSummary,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
) {
    val playing = media.state == "playing"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(if (playing) R1.AccentGreen.copy(alpha = 0.22f) else R1.SurfaceMuted)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = if (playing) "PLAYING" else media.state.uppercase(),
                    style = R1.labelMicro,
                    color = if (playing) R1.AccentGreen else R1.InkSoft,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = media.name,
                style = R1.body,
                color = R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
        }
        val titleLine = listOfNotNull(media.title, media.artist).joinToString(" · ")
        if (titleLine.isNotBlank()) {
            Text(text = titleLine, style = R1.labelMicro, color = R1.InkSoft, maxLines = 2)
        }
        // Transport row — prev / play-pause / next.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TransportButton(label = "◄◄", onClick = onPrev, modifier = Modifier.weight(1f))
            TransportButton(
                label = if (playing) "❚❚" else "▶",
                onClick = onPlayPause,
                modifier = Modifier.weight(1f),
                accent = R1.AccentWarm,
            )
            TransportButton(label = "►►", onClick = onNext, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TransportButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    accent: androidx.compose.ui.graphics.Color = R1.InkSoft,
) {
    Box(
        modifier = modifier
            .clip(R1.ShapeS)
            .background(R1.Bg)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = R1.body, color = accent)
    }
}

@Composable
private fun PersonsCard(
    p: DashboardViewModel.PersonsSummary,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "PEOPLE", style = R1.labelMicro, color = R1.InkSoft)
            Spacer(Modifier.weight(1f))
            Text(text = "${p.homeCount} HOME", style = R1.labelMicro, color = R1.AccentGreen)
            Spacer(Modifier.width(8.dp))
            Text(text = "${p.awayCount} AWAY", style = R1.labelMicro, color = R1.StatusAmber)
        }
        for ((name, state) in p.rows) {
            Row {
                Text(text = name, style = R1.body, color = R1.Ink, modifier = Modifier.weight(1f), maxLines = 1)
                Spacer(Modifier.width(8.dp))
                val color = when (state.lowercase()) {
                    "home" -> R1.AccentGreen
                    "not_home", "away" -> R1.StatusAmber
                    "unknown", "unavailable" -> R1.StatusRed
                    else -> R1.AccentCool
                }
                Text(text = state.uppercase(), style = R1.labelMicro, color = color)
            }
        }
    }
}

@Composable
private fun CalendarCard(
    c: DashboardViewModel.CalendarSummary,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (c.happeningNow) {
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.AccentGreen.copy(alpha = 0.22f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(text = "NOW", style = R1.labelMicro, color = R1.AccentGreen)
                }
                Spacer(Modifier.width(8.dp))
            } else {
                Text(text = "NEXT", style = R1.labelMicro, color = R1.InkSoft)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = c.calendarName.uppercase(),
                style = R1.labelMicro,
                color = R1.InkMuted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            RelativeTimeLabel(at = c.eventStart, color = R1.InkMuted, style = R1.labelMicro)
        }
        Text(text = c.eventTitle, style = R1.body, color = R1.Ink, maxLines = 2)
    }
}

@Composable
private fun MetricsRow(
    cameraCount: Int,
    notificationCount: Int,
    lightsOnCount: Int,
    totalPowerW: Int,
    onLights: () -> Unit,
    onCameras: () -> Unit,
    onNotifications: () -> Unit,
) {
    // Power tile sits on its own row when present (wider value display).
    // Hidden entirely when the install has no power-class sensors.
    if (totalPowerW >= 0) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "DRAW", style = R1.labelMicro, color = R1.InkSoft)
                    Text(
                        text = "${totalPowerW} W",
                        style = R1.numeralXl,
                        color = when {
                            totalPowerW > 2000 -> R1.StatusRed
                            totalPowerW > 500 -> R1.StatusAmber
                            else -> R1.AccentCool
                        },
                    )
                }
                Text(
                    text = "sum of power sensors",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Lights-on count from a server-side Jinja count() — much
        // lighter than fetching every light entity. -1 sentinel
        // renders as '—' so the tile doesn't claim "0 on" while the
        // template is still rendering. Tap routes to the Scenes
        // screen so the user can fire 'ALL LIGHTS OFF' from there
        // — the natural next action when noticing too many lights
        // are on.
        Metric(
            modifier = Modifier.weight(1f),
            label = "LIGHTS ON",
            value = if (lightsOnCount < 0) "—" else lightsOnCount.toString(),
            accent = if (lightsOnCount > 0) R1.AccentWarm else R1.InkSoft,
            onClick = onLights,
        )
        Metric(
            modifier = Modifier.weight(1f),
            label = "CAMERAS",
            value = cameraCount.toString(),
            accent = R1.AccentCool,
            onClick = onCameras,
        )
        Metric(
            modifier = Modifier.weight(1f),
            label = "ALERTS",
            value = notificationCount.toString(),
            accent = if (notificationCount > 0) R1.StatusRed else R1.InkSoft,
            onClick = onNotifications,
        )
    }
}

@Composable
private fun Metric(
    modifier: Modifier,
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = label, style = R1.labelMicro, color = R1.InkSoft)
        Text(text = value, style = R1.numeralXl, color = accent)
    }
}

@Composable
private fun NotificationPreview(
    n: com.github.itskenny0.r1ha.core.ha.PersistentNotification,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.StatusRed.copy(alpha = 0.10f))
            .border(1.dp, R1.StatusRed.copy(alpha = 0.35f), R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = n.title?.takeIf { it.isNotBlank() } ?: n.notificationId,
            style = R1.body.copy(fontWeight = FontWeight.SemiBold),
            color = R1.Ink,
            maxLines = 1,
        )
        Text(
            text = n.message,
            style = R1.labelMicro,
            color = R1.InkSoft,
            maxLines = 2,
        )
    }
}

private fun conditionGlyph(condition: String): String = when (condition.lowercase()) {
    "sunny", "clear" -> "☀"
    "clear-night" -> "☾"
    "partlycloudy" -> "⛅"
    "cloudy" -> "☁"
    "rainy" -> "☂"
    "pouring" -> "☔"
    "snowy", "snowy-rainy" -> "❄"
    "fog" -> "≋"
    "lightning", "lightning-rainy" -> "⚡"
    "windy", "windy-variant" -> "🌬"
    "hail" -> "•"
    else -> "·"
}

private fun conditionAccent(condition: String): androidx.compose.ui.graphics.Color =
    when (condition.lowercase()) {
        "sunny", "clear" -> R1.AccentWarm
        "rainy", "pouring", "snowy", "snowy-rainy", "fog" -> R1.AccentCool
        "lightning", "lightning-rainy" -> R1.StatusAmber
        "windy", "windy-variant" -> R1.AccentNeutral
        else -> R1.InkSoft
    }
