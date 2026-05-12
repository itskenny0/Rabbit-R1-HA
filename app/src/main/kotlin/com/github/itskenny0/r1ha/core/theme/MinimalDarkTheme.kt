package com.github.itskenny0.r1ha.core.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.prefs.DisplayMode
import com.github.itskenny0.r1ha.core.prefs.ThemeId

/**
 * "Minimal Dark" — pure black, single orange accent, no ornamental colour. Card layout is the
 * same instrument-panel as Pragmatic Hybrid but stripped down: no per-domain tinting, no
 * tick labels under the slider, no on/off pill background (just inline text).
 */
object MinimalDarkTheme : R1Theme {
    override val id = ThemeId.MINIMAL_DARK
    override val displayName = "Minimal Dark"
    override val systemBars = SystemBarColors(status = Color.Black, nav = Color.Black)
    override val baseline = sharedDarkBaseline.copy(background = Color.Black, surface = Color.Black)

    private val accent = R1.AccentWarm

    private fun domainLabel(glyph: CardRenderModel.Glyph): String = when (glyph) {
        CardRenderModel.Glyph.LIGHT -> "LIGHT"
        CardRenderModel.Glyph.FAN -> "FAN"
        CardRenderModel.Glyph.COVER -> "COVER"
        CardRenderModel.Glyph.MEDIA_PLAYER -> "MEDIA"
        CardRenderModel.Glyph.SWITCH -> "SWITCH"
        CardRenderModel.Glyph.LOCK -> "LOCK"
        CardRenderModel.Glyph.HUMIDIFIER -> "HUMIDIFIER"
        CardRenderModel.Glyph.CLIMATE -> "CLIMATE"
    }

    @Composable
    override fun Card(model: CardRenderModel, modifier: Modifier, onTapToggle: () -> Unit) {
        val ui = LocalUiOptions.current
        // Per-card accent override (from EntityOverride.accentColor) takes precedence
        // over MinimalDark's single warm accent. Lets the user tint a card without
        // switching themes.
        val accent = model.accentOverride ?: this.accent
        Row(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(start = 22.dp, top = 18.dp, bottom = 18.dp, end = 18.dp),
        ) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(width = 14.dp, height = 2.dp)
                            .background(R1.InkSoft),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = domainLabel(model.domainGlyph),
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                    if (ui.showAreaLabel && !model.area.isNullOrBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text("·", style = R1.labelMicro, color = R1.InkMuted)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = model.area.replace('_', ' ').uppercase(),
                            style = R1.labelMicro,
                            color = R1.InkMuted,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = model.friendlyName,
                    style = R1.titleCard,
                    color = R1.Ink,
                    maxLines = 2,
                )
                Spacer(Modifier.height(20.dp))
                BigReadout(
                    percent = model.percent,
                    showPercentSuffix = ui.displayMode == DisplayMode.PERCENT,
                    accent = accent,
                )
                Spacer(Modifier.weight(1f))
                if (ui.showOnOffPill) {
                    Text(
                        text = if (model.isOn) "● ON" else "○ OFF",
                        style = R1.labelMicro,
                        color = if (model.isOn) accent else R1.InkMuted,
                    )
                }
            }
            Spacer(Modifier.width(20.dp))
            // Stripped vertical slider — no tick labels, thin fill + thumb, accent orange.
            MinimalVerticalSlider(percent = model.percent, accent = accent)
        }
    }
}

@Composable
private fun MinimalVerticalSlider(percent: Int, accent: Color) {
    val fraction = rememberSliderFraction(percent).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(8.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .align(Alignment.Center)
                .background(R1.SurfaceMuted),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight(fraction)
                .width(3.dp)
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
    }
}
