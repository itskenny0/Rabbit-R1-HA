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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.prefs.DisplayMode
import com.github.itskenny0.r1ha.core.prefs.ThemeId

/**
 * "Colourful Cards" — a per-entity gradient sky behind the same Mission-Control layout. The
 * slider keeps the new horizontal tape position but draws on white for contrast against the
 * gradient. Background palettes are hashed by entity id so each card has a stable, recognisable
 * colour identity.
 */
object ColorfulCardsTheme : R1Theme {
    override val id = ThemeId.COLORFUL_CARDS
    override val displayName = "Colourful Cards"
    override val systemBars = SystemBarColors(status = Color.Black, nav = Color.Black)
    override val baseline = sharedDarkBaseline

    private val palette = listOf(
        listOf(Color(0xFFFFB347), Color(0xFFFF6B1A), Color(0xFFC7338A)), // warm
        listOf(Color(0xFF41BDF5), Color(0xFF1B7BB8), Color(0xFF0D3B66)), // cool
        listOf(Color(0xFF52C77F), Color(0xFF2C8B5A), Color(0xFF154A35)), // green
        listOf(Color(0xFF9B6BD8), Color(0xFF5B3B9E), Color(0xFF2E2057)), // violet
    )
    private fun paletteFor(id: String): List<Color> =
        palette[(id.hashCode().rem(palette.size) + palette.size) % palette.size]

    private fun domainLabel(glyph: CardRenderModel.Glyph): String = when (glyph) {
        CardRenderModel.Glyph.LIGHT -> "LIGHT"
        CardRenderModel.Glyph.FAN -> "FAN"
        CardRenderModel.Glyph.COVER -> "COVER"
        CardRenderModel.Glyph.MEDIA_PLAYER -> "MEDIA"
        CardRenderModel.Glyph.SWITCH -> "SWITCH"
        CardRenderModel.Glyph.LOCK -> "LOCK"
        CardRenderModel.Glyph.HUMIDIFIER -> "HUMIDIFIER"
        CardRenderModel.Glyph.CLIMATE -> "CLIMATE"
        CardRenderModel.Glyph.NUMBER -> "NUMBER"
        CardRenderModel.Glyph.VALVE -> "VALVE"
        CardRenderModel.Glyph.VACUUM -> "VACUUM"
        CardRenderModel.Glyph.WATER_HEATER -> "WATER HEATER"
    }

    @Composable
    override fun Card(model: CardRenderModel, modifier: Modifier, onTapToggle: () -> Unit) {
        val pal = paletteFor(model.entityIdText)
        val ui = LocalUiOptions.current

        Row(
            modifier = modifier
                .fillMaxSize()
                .background(Brush.linearGradient(pal))
                .padding(start = 22.dp, top = 18.dp, bottom = 18.dp, end = 18.dp),
        ) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(width = 14.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.9f)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = domainLabel(model.domainGlyph),
                        style = R1.labelMicro,
                        color = Color.White,
                    )
                    if (ui.showAreaLabel && !model.area.isNullOrBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text("·", style = R1.labelMicro, color = Color.White.copy(alpha = 0.7f))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = model.area.replace('_', ' ').uppercase(),
                            style = R1.labelMicro,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = model.friendlyName,
                    style = R1.titleCard,
                    color = Color.White,
                    maxLines = 2,
                )
                Spacer(Modifier.height(20.dp))
                BigReadout(
                    percent = model.percent,
                    showPercentSuffix = ui.displayMode == DisplayMode.PERCENT,
                    accent = Color.White,
                )
                Spacer(Modifier.weight(1f))
                if (ui.showOnOffPill) {
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeRound)
                            .background(Color.Black.copy(alpha = 0.22f))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = if (model.isOn) "● ON" else "○ OFF",
                            style = R1.labelMicro,
                            color = Color.White,
                        )
                    }
                }
            }
            Spacer(Modifier.width(20.dp))
            // Chunky white vertical slider, distinct from the other themes.
            ColorfulVerticalSlider(percent = model.percent)
        }
    }
}

@Composable
private fun ColorfulVerticalSlider(percent: Int) {
    val fraction = rememberSliderFraction(percent).coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(14.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color.Black.copy(alpha = 0.22f)),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight(fraction)
                .width(14.dp)
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(7.dp))
                .background(Color.White),
        )
    }
}
