package com.github.itskenny0.r1ha.core.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.itskenny0.r1ha.core.prefs.ThemeId

object PragmaticHybridTheme : R1Theme {
    override val id = ThemeId.PRAGMATIC_HYBRID
    override val displayName = "Pragmatic Hybrid"
    override val systemBars = SystemBarColors(status = Color(0xFF0A0A0A), nav = Color(0xFF0A0A0A))
    override val baseline = sharedDarkBaseline

    private fun accentColor(role: CardRenderModel.AccentRole) = when (role) {
        CardRenderModel.AccentRole.WARM -> Color(0xFFF36F21)
        CardRenderModel.AccentRole.COOL -> Color(0xFF41BDF5)
        CardRenderModel.AccentRole.GREEN -> Color(0xFF52C77F)
        CardRenderModel.AccentRole.NEUTRAL -> Color(0xFFB0B0B0)
    }

    @Composable
    override fun Card(model: CardRenderModel, modifier: Modifier, onTapToggle: () -> Unit) {
        val accent = accentColor(model.accent)
        val ui = LocalUiOptions.current
        Box(modifier = modifier.fillMaxSize().background(Color(0xFF0A0A0A)).padding(14.dp)) {
            // glow
            Box(Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = 0.30f), Color.Transparent),
                    radius = 320f, center = androidx.compose.ui.geometry.Offset(220f, 80f),
                )
            ))
            if (ui.showAreaLabel) {
                Text(buildString {
                    append(model.domainGlyph.name)
                    model.area?.let { append(" · "); append(it.uppercase()) }
                }, color = Color.White.copy(alpha = 0.75f), fontSize = 10.sp)
            }
            Text(model.friendlyName, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = if (ui.showAreaLabel) 18.dp else 0.dp))
            Box(Modifier.align(Alignment.CenterStart).padding(top = 60.dp)) {
                Text("${model.percent}", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Medium)
                if (ui.displayMode == com.github.itskenny0.r1ha.core.prefs.DisplayMode.PERCENT) {
                    Text("%", color = Color.White.copy(alpha = 0.55f), fontSize = 18.sp,
                        modifier = Modifier.padding(start = 80.dp, top = 6.dp))
                }
            }
            // chip
            if (ui.showOnOffPill) {
                Box(Modifier.align(Alignment.BottomStart).padding(bottom = 38.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(accent.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(if (model.isOn) "● ON" else "○ OFF",
                        color = accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            // vertical slider, right edge with visible thumb — spring-animated bouncy feedback
            val fraction = rememberSliderFraction(model.percent)
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(6.dp)
                .clip(RoundedCornerShape(3.dp)).background(Color(0xFF1A1A1A))) {
                Box(Modifier.fillMaxHeight(fraction = fraction)
                    .align(Alignment.BottomCenter).background(accent))
            }
        }
    }
}
