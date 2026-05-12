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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.itskenny0.r1ha.core.prefs.DisplayMode
import com.github.itskenny0.r1ha.core.prefs.ThemeId

object MinimalDarkTheme : R1Theme {
    override val id = ThemeId.MINIMAL_DARK
    override val displayName = "Minimal Dark"
    override val systemBars = SystemBarColors(status = Color.Black, nav = Color.Black)
    override val baseline = sharedDarkBaseline.copy(background = Color.Black, surface = Color.Black)

    private val orange = Color(0xFFF36F21)

    @Composable
    override fun Card(
        model: CardRenderModel,
        modifier: Modifier,
        onTapToggle: () -> Unit,
    ) {
        val ui = LocalUiOptions.current
        Box(modifier = modifier.fillMaxSize().background(Color.Black).padding(14.dp)) {
            if (ui.showAreaLabel) {
                Text(model.area?.uppercase() ?: "", color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp)
            }
            Text(model.friendlyName, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp,
                modifier = Modifier.padding(top = if (ui.showAreaLabel) 16.dp else 0.dp))
            Box(modifier = Modifier.align(Alignment.CenterStart).padding(top = 56.dp)) {
                Text("${model.percent}", color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light)
                if (ui.displayMode == DisplayMode.PERCENT) {
                    Text("%", color = Color.White.copy(alpha = 0.5f), fontSize = 18.sp,
                        modifier = Modifier.padding(start = 80.dp, top = 6.dp))
                }
            }
            // vertical slider, right edge — spring-animated fill for bouncy wheel feedback
            val fraction = rememberSliderFraction(model.percent)
            Box(modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(8.dp)
                .clip(RoundedCornerShape(4.dp)).background(Color(0xFF111111))) {
                Box(
                    modifier = Modifier.fillMaxHeight(fraction = fraction)
                        .align(Alignment.BottomCenter).background(orange)
                )
            }
        }
    }
}
