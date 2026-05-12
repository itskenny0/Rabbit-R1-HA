package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Three-stroke menu glyph in the R1 idiom. Replaces Material's `Icons.Default.Menu` (which
 * has slightly rounded ends + Material proportions that read soft against the sharp
 * dashboard chrome). Three 1.5dp hairlines, 5dp gap, butt caps so they look like rules.
 */
@Composable
fun HamburgerGlyph(
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    tint: Color = R1.Ink.copy(alpha = 0.85f),
) {
    Canvas(modifier = modifier.size(size)) {
        val sw = 1.5.dp.toPx()
        val gap = 5.dp.toPx()
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val length = this.size.width * 0.78f
        val x0 = cx - length / 2f
        val x1 = cx + length / 2f
        drawLine(tint, Offset(x0, cy - gap), Offset(x1, cy - gap), sw, StrokeCap.Butt)
        drawLine(tint, Offset(x0, cy), Offset(x1, cy), sw, StrokeCap.Butt)
        drawLine(tint, Offset(x0, cy + gap), Offset(x1, cy + gap), sw, StrokeCap.Butt)
    }
}
