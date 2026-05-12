package com.github.itskenny0.r1ha.core.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import com.github.itskenny0.r1ha.core.prefs.DisplayMode
import com.github.itskenny0.r1ha.core.prefs.ThemeId

object ColorfulCardsTheme : R1Theme {
    override val id = ThemeId.COLORFUL_CARDS
    override val displayName = "Colorful Cards"
    override val systemBars = SystemBarColors(status = Color.Black, nav = Color.Black)
    override val baseline = sharedDarkBaseline

    private val palette = listOf(
        listOf(Color(0xFFFFB347), Color(0xFFFF6B1A), Color(0xFFC7338A)), // warm
        listOf(Color(0xFF41BDF5), Color(0xFF1B7BB8), Color(0xFF0D3B66)), // cool
        listOf(Color(0xFF52C77F), Color(0xFF2C8B5A), Color(0xFF154A35)), // green
        listOf(Color(0xFF9B6BD8), Color(0xFF5B3B9E), Color(0xFF2E2057)), // violet
    )
    private fun paletteFor(id: String): List<Color> = palette[(id.hashCode().rem(palette.size) + palette.size) % palette.size]

    @Composable
    override fun Card(model: CardRenderModel, modifier: Modifier, onTapToggle: () -> Unit) {
        val ui = LocalUiOptions.current
        val pal = paletteFor(model.entityIdText)
        Box(modifier = modifier.fillMaxSize()
            .background(Brush.linearGradient(pal))
            .padding(16.dp)) {
            if (ui.showAreaLabel) {
                Text(model.area?.uppercase() ?: "", color = Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
            Text(model.friendlyName, color = Color.White, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = if (ui.showAreaLabel) 2.dp else 0.dp))
            Row(verticalAlignment = Alignment.Bottom,
                modifier = Modifier.align(Alignment.CenterStart).padding(top = 70.dp)) {
                Text("${model.percent}", color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Bold)
                if (ui.displayMode == DisplayMode.PERCENT) {
                    Text("%", color = Color.White.copy(alpha = 0.85f), fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, bottom = 12.dp))
                }
            }
            // chunky pill
            if (ui.showOnOffPill) {
                Box(Modifier.align(Alignment.BottomStart).padding(bottom = 28.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.Black.copy(alpha = 0.22f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(if (model.isOn) "● ON" else "○ OFF",
                        color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            // chunky slider — spring-animated bouncy feedback
            val fraction = rememberSliderFraction(model.percent)
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(14.dp)
                .clip(RoundedCornerShape(7.dp)).background(Color.Black.copy(alpha = 0.20f))) {
                Box(Modifier.fillMaxHeight(fraction = fraction)
                    .align(Alignment.BottomCenter).clip(RoundedCornerShape(7.dp))
                    .background(Color.White))
            }
        }
    }
}
