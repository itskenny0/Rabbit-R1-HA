package com.github.itskenny0.r1ha.feature.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollForScrollState
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * History drill-in surface — full-screen view of one entity's
 * state-change history. Pairs with the per-card sparkline on
 * SensorCard, which previews 24 h at 72 dp; this surface is what the
 * user gets when they want a closer look (longer window, larger chart,
 * numeric summary).
 *
 * The chart itself is a hand-drawn Canvas — same line-stroke
 * conventions as SensorHistoryChart, but bigger (180 dp tall), with
 * explicit axis labels (start time, mid, end), a horizontal mid-line
 * for orientation, and a faint band marking the min..max envelope.
 *
 * Time-window picker chips at the top flip between 1 h / 6 h / 24 h /
 * 7 d; each selection re-fetches via /api/history/period/<since> with
 * the new window.
 */
@Composable
fun HistoryScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    entityId: String,
    onBack: () -> Unit,
) {
    val parsedId = remember(entityId) { runCatching { EntityId(entityId) }.getOrNull() }
    if (parsedId == null) {
        // Defensive — invalid entity_id (shouldn't happen via legitimate
        // nav, but a deep-link could try). Surface a clean error rather
        // than crashing the VM factory.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(R1.Bg)
                .systemBarsPadding(),
        ) {
            R1TopBar(title = "HISTORY", onBack = onBack)
            Box(modifier = Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "Invalid entity_id: $entityId",
                    style = R1.body,
                    color = R1.StatusRed,
                )
            }
        }
        return
    }
    val vm: HistoryViewModel = viewModel(
        key = entityId,
        factory = HistoryViewModel.factory(haRepository, parsedId),
    )
    val ui by vm.ui.collectAsState()
    val scrollState = rememberScrollState()
    WheelScrollForScrollState(wheelInput = wheelInput, scrollState = scrollState, settings = settings)
    LaunchedEffect(entityId) { vm.refresh() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(
            title = ui.displayName.uppercase().take(22),
            onBack = onBack,
            action = {
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = { vm.refresh() })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (ui.loading) "…" else "REFRESH",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = entityId,
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
            WindowChips(current = ui.window, onSelect = { vm.setWindow(it) })
            HistoryChartPanel(ui)
            SummaryPanel(ui)
            if (ui.error != null && ui.points.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(R1.ShapeS)
                        .background(R1.StatusRed.copy(alpha = 0.12f))
                        .border(1.dp, R1.StatusRed.copy(alpha = 0.4f), R1.ShapeS)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = ui.error ?: "",
                        style = R1.labelMicro,
                        color = R1.StatusRed,
                    )
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun WindowChips(
    current: HistoryViewModel.Window,
    onSelect: (HistoryViewModel.Window) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HistoryViewModel.Window.entries.forEach { w ->
            val active = w == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(R1.ShapeS)
                    .background(if (active) R1.AccentWarm else R1.SurfaceMuted)
                    .r1Pressable(onClick = { onSelect(w) })
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = w.label,
                    style = R1.labelMicro,
                    color = if (active) R1.Bg else R1.InkSoft,
                )
            }
        }
    }
}

@Composable
private fun HistoryChartPanel(ui: HistoryViewModel.UiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (ui.loading && ui.points.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(180.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = R1.AccentWarm,
                )
            }
            return@Column
        }
        if (ui.points.size < 2) {
            Box(
                modifier = Modifier.fillMaxWidth().height(180.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "NOT ENOUGH HISTORY YET",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
            return@Column
        }
        val numeric = ui.points.mapNotNull { p -> p.numeric?.let { p.timestamp to it } }
        if (numeric.size < 2) {
            Box(
                modifier = Modifier.fillMaxWidth().height(180.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "HISTORY ISN'T NUMERIC",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
            return@Column
        }
        val ys = numeric.map { it.second }
        val yMin = ys.min()
        val yMax = ys.max()
        val yRange = (yMax - yMin).takeIf { it > 1e-9 } ?: 1.0
        val tStart = numeric.first().first
        val tEnd = numeric.last().first
        val tSpan = Duration.between(tStart, tEnd).toMillis().coerceAtLeast(1L)
        val zone = ZoneId.systemDefault()
        // Pick an axis-label format that scales with the window — for
        // sub-day windows we show HH:mm; for multi-day windows we drop
        // the colon for compactness.
        val fmt = if (tSpan < Duration.ofHours(36).toMillis()) {
            DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        } else {
            DateTimeFormatter.ofPattern("d MMM").withZone(zone)
        }
        Row {
            // Y-axis labels on the right edge — min/max with units.
            Column(modifier = Modifier.weight(1f)) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(R1.Surface)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                ) {
                    val w = size.width
                    val h = size.height
                    // Faint horizontal mid-line for orientation
                    drawLine(
                        color = R1.Hairline,
                        start = Offset(0f, h * 0.5f),
                        end = Offset(w, h * 0.5f),
                        strokeWidth = 1f,
                    )
                    // Faint baseline gridline
                    drawLine(
                        color = R1.Hairline,
                        start = Offset(0f, h - 1f),
                        end = Offset(w, h - 1f),
                        strokeWidth = 1f,
                    )
                    // Project each numeric point onto the canvas:
                    //   x = (t - tStart) / tSpan * width
                    //   y = h - ((v - yMin) / yRange) * h
                    val pts = numeric.map { (ts, v) ->
                        val x = (Duration.between(tStart, ts).toMillis().toFloat() / tSpan) * w
                        val y = h - (((v - yMin) / yRange).toFloat() * h)
                        Offset(x, y)
                    }
                    for (i in 0 until pts.size - 1) {
                        drawLine(
                            color = R1.AccentWarm,
                            start = pts[i],
                            end = pts[i + 1],
                            strokeWidth = 2f,
                            cap = StrokeCap.Round,
                        )
                    }
                    // Bookend dots so the start/end samples are clearly
                    // located, particularly handy when the line is
                    // mostly flat.
                    drawCircle(
                        color = R1.AccentWarm,
                        radius = 3f,
                        center = pts.first(),
                    )
                    drawCircle(
                        color = R1.AccentWarm,
                        radius = 3f,
                        center = pts.last(),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    Text(
                        text = fmt.format(tStart),
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = fmt.format(tEnd),
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            // Inline Y-axis labels — just min and max, anchored to
            // top and bottom respectively. The mid-line we drew in
            // the canvas reads as the midpoint without a label.
            Column(modifier = Modifier.width(56.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "${formatNum(yMax)}${ui.unit?.let { " $it" } ?: ""}",
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${formatNum(yMin)}${ui.unit?.let { " $it" } ?: ""}",
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SummaryPanel(ui: HistoryViewModel.UiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "SUMMARY — ${ui.window.label}",
            style = R1.labelMicro,
            color = R1.InkSoft,
        )
        // 4-cell summary grid: CURRENT / MIN / MAX / AVG. Each cell
        // shows the readout with the unit appended; non-numeric
        // entities (text sensors) suppress the numeric rows.
        SummaryRow(
            label = "CURRENT",
            value = ui.current?.let { "$it${ui.unit?.let { u -> " $u" } ?: ""}" } ?: "—",
            accent = R1.Ink,
        )
        if (ui.min != null) SummaryRow(
            label = "MIN",
            value = "${formatNum(ui.min)}${ui.unit?.let { " $it" } ?: ""}",
            accent = R1.AccentCool,
        )
        if (ui.max != null) SummaryRow(
            label = "MAX",
            value = "${formatNum(ui.max)}${ui.unit?.let { " $it" } ?: ""}",
            accent = R1.AccentWarm,
        )
        if (ui.avg != null) SummaryRow(
            label = "AVG",
            value = "${formatNum(ui.avg)}${ui.unit?.let { " $it" } ?: ""}",
            accent = R1.AccentNeutral,
        )
        SummaryRow(
            label = "SAMPLES",
            value = "${ui.points.size}",
            accent = R1.InkSoft,
        )
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = R1.labelMicro, color = R1.InkSoft, modifier = Modifier.width(80.dp))
        Text(
            text = value,
            style = R1.body.copy(fontWeight = FontWeight.SemiBold),
            color = accent,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
    }
}

/** Drop unhelpful trailing decimals: 23.0 → "23", 23.45 → "23.45". */
private fun formatNum(v: Double): String =
    if (kotlin.math.abs(v - v.toLong()) < 1e-9) "${v.toLong()}"
    else "%.2f".format(v)
