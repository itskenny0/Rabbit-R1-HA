package com.github.itskenny0.r1ha.core.theme

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.prefs.DisplayMode
import com.github.itskenny0.r1ha.core.prefs.ThemeId

/**
 * "Mission Control" — the default theme. Heavy orange on near-black, monospace numerals,
 * uppercase letter-spaced labels, and a horizontal tape-meter slider directly under the
 * percent readout (rather than running the full screen height on the right edge). The whole
 * card reads like an instrument panel: SOURCE on top, value in the centre, status pill below.
 */
object PragmaticHybridTheme : R1Theme {
    override val id = ThemeId.PRAGMATIC_HYBRID
    override val displayName = "Pragmatic Hybrid"
    override val systemBars = SystemBarColors(status = R1.Bg, nav = R1.Bg)
    override val baseline = sharedDarkBaseline

    private fun accentColor(role: CardRenderModel.AccentRole) = when (role) {
        CardRenderModel.AccentRole.WARM -> R1.AccentWarm
        CardRenderModel.AccentRole.COOL -> R1.AccentCool
        CardRenderModel.AccentRole.GREEN -> R1.AccentGreen
        CardRenderModel.AccentRole.NEUTRAL -> R1.AccentNeutral
    }

    private fun domainLabel(glyph: CardRenderModel.Glyph): String = when (glyph) {
        CardRenderModel.Glyph.LIGHT -> "LIGHT"
        CardRenderModel.Glyph.FAN -> "FAN"
        CardRenderModel.Glyph.COVER -> "COVER"
        CardRenderModel.Glyph.MEDIA_PLAYER -> "MEDIA"
    }

    @Composable
    override fun Card(model: CardRenderModel, modifier: Modifier, onTapToggle: () -> Unit) {
        val accent = accentColor(model.accent)
        val ui = LocalUiOptions.current

        Column(
            modifier = modifier
                .fillMaxSize()
                .background(R1.Bg)
                .padding(horizontal = 22.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.Top,
        ) {
            // ── Header strip: DOMAIN · AREA ───────────────────────────────────────────
            DomainHeader(
                domainLabel = domainLabel(model.domainGlyph),
                area = model.area,
                accent = accent,
                showArea = ui.showAreaLabel,
            )
            Spacer(Modifier.height(6.dp))

            // ── Friendly name ────────────────────────────────────────────────────────
            Text(
                text = model.friendlyName,
                style = R1.titleCard,
                color = R1.Ink,
                maxLines = 2,
            )
            Spacer(Modifier.height(20.dp))

            // ── Big monospace readout with slot-machine digits ───────────────────────
            BigReadout(
                percent = model.percent,
                showPercentSuffix = ui.displayMode == DisplayMode.PERCENT,
                accent = accent,
            )
            Spacer(Modifier.height(14.dp))

            // ── Horizontal tape meter — the slider ───────────────────────────────────
            TapeMeter(percent = model.percent, accent = accent)

            // ── Pushes the on/off pill to the bottom of the card ─────────────────────
            Spacer(Modifier.weight(1f))

            if (ui.showOnOffPill) {
                OnOffPill(isOn = model.isOn, accent = accent)
            }
        }
    }
}

// ── Building blocks ──────────────────────────────────────────────────────────────────────

@Composable
private fun DomainHeader(
    domainLabel: String,
    area: String?,
    accent: Color,
    showArea: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Accent chip
        Box(
            modifier = Modifier
                .size(width = 14.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = domainLabel,
            style = R1.labelMicro,
            color = R1.Ink,
        )
        if (showArea && !area.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(text = "·", style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.width(8.dp))
            Text(
                text = area.replace('_', ' ').uppercase(),
                style = R1.labelMicro,
                color = R1.InkSoft,
            )
        }
    }
}

/**
 * The percent number, animated digit-by-digit when it changes. Each digit slides in from
 * above and the old digit slides out below — a small slot-machine feel that telegraphs
 * change without being noisy.
 */
@Composable
internal fun BigReadout(
    percent: Int,
    showPercentSuffix: Boolean,
    accent: Color,
) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.wrapContentSize(),
    ) {
        // The 0..100 value as up to 3 digits — leading "0"s get rendered as space-coloured
        // (effectively invisible) so the layout doesn't twitch as the number changes width.
        val text = percent.coerceIn(0, 100).toString().padStart(3, '0')
        text.forEachIndexed { idx, ch ->
            val isLeadingZero = idx < text.length - percent.toString().length
            val color = if (isLeadingZero) Color.Transparent else R1.Ink
            AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    val dur = 180
                    if (targetState > initialState) {
                        slideInVertically(tween(dur)) { -it } togetherWith
                            slideOutVertically(tween(dur)) { it }
                    } else {
                        slideInVertically(tween(dur)) { it } togetherWith
                            slideOutVertically(tween(dur)) { -it }
                    }
                },
                label = "digit-$idx",
            ) { digit ->
                Text(
                    text = digit.toString(),
                    style = R1.numeralXl,
                    color = color,
                )
            }
        }
        if (showPercentSuffix) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "%",
                style = R1.numeralM,
                color = accent,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
    }
}

/**
 * A horizontal tape-meter slider that lives directly under the readout. Far wider than tall;
 * tick marks every 25% so the user can read absolute position at a glance. The accent-coloured
 * fill is spring-animated via [rememberSliderFraction] — when the wheel turns, the bar
 * visibly bounces toward the new value rather than just snapping.
 */
@Composable
internal fun TapeMeter(percent: Int, accent: Color) {
    val fraction = rememberSliderFraction(percent)
    Column(modifier = Modifier.fillMaxWidth()) {
        // Track + fill
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(R1.SurfaceMuted),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(accent),
            )
        }
        Spacer(Modifier.height(4.dp))
        // Tick row: 0 · 25 · 50 · 75 · 100, monospace, very small
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            listOf("0", "25", "50", "75", "100").forEach { mark ->
                Text(
                    text = mark,
                    style = R1.numeralS,
                    color = R1.InkMuted,
                    modifier = Modifier.width(28.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun OnOffPill(isOn: Boolean, accent: Color) {
    val (label, fg, bg) = if (isOn) {
        Triple("● ON", R1.Bg, accent)
    } else {
        Triple("○ OFF", R1.InkSoft, R1.SurfaceMuted)
    }
    Box(
        modifier = Modifier
            .clip(R1.ShapeM)
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(text = label, style = R1.labelMicro, color = fg)
    }
}
