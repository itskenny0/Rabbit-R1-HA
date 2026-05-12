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
 * Small pencil glyph drawn as three line segments — the diagonal shaft + a tiny triangle
 * at the upper-right tip. Matches the rest of the chrome (hairline-stroked, butt caps,
 * canvas-drawn, no Material icons) so the rename affordance reads as part of the same
 * design language. Used in the favourites picker rows.
 */
@Composable
fun EditGlyph(
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    tint: Color = R1.InkMuted,
    strokeWidth: Dp = 1.5.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        val sw = strokeWidth.toPx()
        val w = this.size.width
        val h = this.size.height
        val inset = sw / 2f + 0.5f
        // Shaft — diagonal from bottom-left up to the tip near top-right.
        val shaftStart = Offset(inset, h - inset)
        val shaftEnd = Offset(w - inset - w * 0.18f, inset + h * 0.18f)
        drawLine(tint, shaftStart, shaftEnd, sw, StrokeCap.Butt)
        // Tip — two short strokes that meet at the top-right corner, forming an arrowhead.
        val tip = Offset(w - inset, inset)
        drawLine(tint, shaftEnd, tip, sw, StrokeCap.Butt)
        // Small cross-bar at the back of the pencil so it doesn't read as just a line.
        val crossA = Offset(inset, h - inset - h * 0.2f)
        val crossB = Offset(inset + w * 0.2f, h - inset)
        drawLine(tint, crossA, crossB, sw, StrokeCap.Butt)
    }
}
